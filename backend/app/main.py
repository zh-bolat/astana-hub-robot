"""Точка входа: `uv run uvicorn app.main:app --port 8000`."""

from __future__ import annotations

import asyncio
import contextlib
import inspect
import time
from contextlib import asynccontextmanager

from fastapi import FastAPI
from openai import AsyncOpenAI
from starlette.types import ASGIApp, Message, Receive, Scope, Send

from app import __version__
from app.agent import ensure_tracing_disabled
from app.agent.service import ChatService, build_chat_client
from app.api import router
from app.client_ip import Network, client_ip, parse_networks
from app.config import Settings, secret_text
from app.db import LatencyRepo, MessageRepo, QuestionLogRepo, SessionRepo, make_engine
from app.db.migrate import upgrade_head
from app.errors import error_response, install_error_handlers
from app.guard import GuardMiddleware
from app.knowledge.index import KbIndexer
from app.knowledge.pg import DbKnowledgeSource, KnowledgeRepo
from app.knowledge.search import KbSearch
from app.knowledge.sheets import SheetFetcher
from app.knowledge.source import KnowledgeSource, KnowledgeStore, YamlKnowledgeSource
from app.knowledge.sync import KnowledgeSyncer
from app.kurultai.agent import KurultaiChatService
from app.kurultai.quryltai import QuryltaiSync
from app.live.services import LiveServicesFactory, build_live_services
from app.live.session import LiveRegistry, route_pipecat_logs
from app.live.session import router as live_router
from app.llm.base import LLMProvider
from app.llm.embeddings import Embedder, build_embedder
from app.llm.factory import build_text_provider, build_vision_provider
from app.log_writer import BackgroundLogWriter, purge_job
from app.logging_setup import (
    configure_logging,
    logger,
    new_request_id,
    reset_request_id,
    set_request_id,
)
from app.metrics import NULL_RECORDER, LatencyRecorder
from app.metrics_api import router as metrics_router
from app.profiles import ASTANA_HUB, KURULTAI
from app.ratelimit import RateLimiter
from app.sentry_setup import init_sentry
from app.service import AnswerService
from app.speech.base import SttProvider, TtsProvider
from app.speech.cache import TtsCache
from app.speech.factory import (
    build_stt_provider,
    build_stt_stream_provider,
    build_tts_provider,
)
from app.speech.streaming import StreamingStt
from app.speech_api import router as speech_router

RETENTION_INTERVAL_S = 24 * 3600


class RequestContextMiddleware:
    """X-Request-Id + access-лог одной JSON-строкой (с IP клиента). Чистый ASGI (корректно со стримингом)."""

    def __init__(self, app: ASGIApp, trusted_proxies: tuple[Network, ...] = ()) -> None:
        self.app = app
        self.trusted_proxies = trusted_proxies

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        request_id = new_request_id()
        token = set_request_id(request_id)
        started = time.perf_counter()
        status = 500
        started_response = False

        async def send_wrapper(message: Message) -> None:
            nonlocal status, started_response
            if message["type"] == "http.response.start":
                started_response = True
                status = message["status"]
                headers = list(message.get("headers", []))
                headers.append((b"x-request-id", request_id.encode()))
                message = {**message, "headers": headers}
            await send(message)

        try:
            await self.app(scope, receive, send_wrapper)
        except Exception:
            logger.exception("unhandled_error")
            if started_response:
                raise
            response = error_response(500, "internal_error", "Internal server error")
            await response(scope, receive, send_wrapper)
        finally:
            logger.info(
                "http_request",
                extra={
                    "method": scope["method"],
                    "path": scope["path"],
                    "client_ip": client_ip(scope, self.trusted_proxies),
                    "status": status,
                    "duration_ms": int((time.perf_counter() - started) * 1000),
                },
            )
            reset_request_id(token)


def build_sheet_fetcher(settings: Settings) -> SheetFetcher | None:
    if not settings.knowledge_sheet_id:
        return None
    return SheetFetcher(
        sheet_id=settings.knowledge_sheet_id,
        tabs=(
            settings.knowledge_sheet_facts,
            settings.knowledge_sheet_places,
            settings.knowledge_sheet_buttons,
        ),
        timeout_s=settings.knowledge_sync_timeout_s,
    )


def build_knowledge(settings: Settings, engine) -> KnowledgeSyncer:
    """Знания из Postgres. Старт сеть не трогает: БД → снимок → YAML; таблица — фоновым синком.
    Пустая БД без таблицы → сид из YAML (KB_SEED_YAML_IF_EMPTY)."""
    yaml_source = YamlKnowledgeSource(settings.knowledge_dir)
    repo = KnowledgeRepo(engine)
    source = DbKnowledgeSource(repo, yaml_source, settings.knowledge_snapshot_path)
    fetcher = build_sheet_fetcher(settings)
    seed = yaml_source if settings.kb_seed_yaml_if_empty and fetcher is None else None
    syncer = KnowledgeSyncer(repo, source, fetcher, yaml_seed=seed)
    syncer.bootstrap()
    if source.from_db is False and source.load_snapshot() is False:
        logger.warning("knowledge_using_yaml_fallback")
    return syncer


async def close_resources(*objs: object) -> None:
    """Закрывает HTTP-клиенты провайдеров (каждый объект один раз; ошибки только в лог)."""
    seen: set[int] = set()
    for obj in objs:
        if obj is None or id(obj) in seen:
            continue
        seen.add(id(obj))
        aclose = getattr(obj, "aclose", None)
        if not callable(aclose):
            continue
        try:
            result = aclose()
            if inspect.isawaitable(result):
                await asyncio.wait_for(result, timeout=2)
        except Exception as exc:
            logger.warning(
                "resource_close_failed", extra={"resource": type(obj).__name__, "error": str(exc)}
            )


async def _retention_loop(writer: BackgroundLogWriter, repo: QuestionLogRepo, days: int) -> None:
    while True:
        writer.submit_job(purge_job(repo, days), name="retention")
        await asyncio.sleep(RETENTION_INTERVAL_S)


async def _cancel(task: asyncio.Task | None) -> None:
    if task is None:
        return
    task.cancel()
    with contextlib.suppress(asyncio.CancelledError, Exception):
        await task


def create_app(
    settings: Settings | None = None,
    text_llm: LLMProvider | None = None,
    vision_llm: LLMProvider | None = None,
    speech: SttProvider | TtsProvider | None = None,
    tts: TtsProvider | None = None,
    knowledge_source: KnowledgeSource | None = None,
    log_repo: QuestionLogRepo | None = None,
    stt_stream: StreamingStt | None = None,
    chat_client: AsyncOpenAI | None = None,
    live_services: LiveServicesFactory | None = None,
    embedder: Embedder | None = None,
    kurultai_chat_client: AsyncOpenAI | None = None,
    quryltai: QuryltaiSync | None = None,
) -> FastAPI:
    """speech — подменить и STT, и TTS одним провайдером (тесты); tts — только TTS.
    knowledge_source / log_repo — подменить базу знаний и лог вопросов (eval, openapi); с подменённой базой
    поиска по базе нет (он работает по kb_chunks в Postgres).
    chat_client — OpenAI-совместимый клиент агента /v1/chat (тесты: фейковый транспорт).
    live_services — фабрика сервисов Pipecat для WS /v1/live (тесты: фейковые VAD/STT/TTS).
    embedder — эмбеддинги поиска (тесты: FakeEmbeddings); иначе по EMBEDDING_PROVIDER.
    kurultai_chat_client / quryltai — клиент агента и снимок quryltai.kz профиля kurultai (тесты: фейковые);
    профиль включён, только если задан KURULTAI_ROBOT_TOKEN."""
    settings = settings or Settings()
    configure_logging(settings.log_level)
    route_pipecat_logs()
    # ADR 0001: трассировка Agents SDK выключена всегда (тексты посетителей не уходят в OpenAI)
    ensure_tracing_disabled()
    if not settings.robot_token.get_secret_value():
        raise RuntimeError("ROBOT_TOKEN is not set (see backend/.env.example)")
    if secret_text(settings.kurultai_robot_token) == secret_text(settings.robot_token):
        raise RuntimeError(
            "KURULTAI_ROBOT_TOKEN must differ from ROBOT_TOKEN (token selects the profile)"
        )
    init_sentry(settings)

    engine = make_engine(settings.database_url, settings.db_timeout_s)
    if settings.db_auto_migrate:
        # До приёма запросов; ошибка → исключение, процесс завершается с ненулевым кодом
        upgrade_head(settings.database_url)
    syncer: KnowledgeSyncer | None = None
    if knowledge_source is None:
        syncer = build_knowledge(settings, engine)
        knowledge_source = syncer.source
    store = KnowledgeStore(knowledge_source, settings.knowledge_reload_interval_s)
    store.get()  # битая база — падаем на старте, а не на первом вопросе
    text_llm = text_llm or build_text_provider(settings)
    vision_llm = vision_llm or build_vision_provider(settings, text_llm)
    # БД не на критическом пути ответа: записи лога и сессий — в фоновом потоке
    repo = log_repo or QuestionLogRepo(engine)
    log_writer = BackgroundLogWriter(
        repo,
        sessions=SessionRepo(engine) if log_repo is None else None,
        max_pending=settings.question_log_queue_max,
    )
    latency_repo = LatencyRepo(engine)
    # Тайминги этапов — той же фоновой очередью; при подменённом логе (eval, openapi) — выключены
    metrics = (
        LatencyRecorder(log_writer.submit_job, latency_repo.add_many)
        if log_repo is None
        else NULL_RECORDER
    )
    service = AnswerService(settings, store, text_llm, vision_llm, log_writer, metrics)
    # Поиск по базе (RAG): справка к ходу (prefetch) или инструмент search_kb (tool) + фоновая индексация
    kb_search: KbSearch | None = None
    indexer: KbIndexer | None = None
    if (
        syncer is not None  # база из Postgres (не подменена)
        and settings.retrieval_kind() != "off"
        and (embedder is not None or settings.resolved_retrieval_mode() != "off")
    ):
        embedder = embedder or build_embedder(settings)
    else:
        embedder = None
    if embedder is not None:
        kb_search = KbSearch(engine, embedder, settings.embedding_query_timeout_s)
        indexer = KbIndexer(engine, embedder, settings.embedding_batch_size)
    chat = ChatService(
        settings,
        store,
        service,
        log_writer,
        chat_client or build_chat_client(settings),
        messages=MessageRepo(engine) if log_repo is None else None,
        sessions=SessionRepo(engine) if log_repo is None else None,
        metrics=metrics,
        search=kb_search,
    )
    # Профиль kurultai (KURULTAI_ROBOT_TOKEN): свой агент, снимок quryltai.kz, память разговоров — отдельно от хаба
    kurultai_chat: KurultaiChatService | None = None
    if settings.kurultai_enabled():
        if quryltai is None:
            quryltai = QuryltaiSync(
                settings.quryltai_base_url,
                settings.quryltai_timeout_s,
                settings.quryltai_snapshot_path,
            )
            quryltai.load_cache()  # последний снимок — сразу, сеть — фоном в lifespan
        kurultai_chat = KurultaiChatService(
            settings,
            store,
            service,
            log_writer,
            kurultai_chat_client or build_chat_client(settings),
            messages=MessageRepo(engine) if log_repo is None else None,
            sessions=SessionRepo(engine) if log_repo is None else None,
            metrics=metrics,
            model_name=settings.resolved_kurultai_model(),
            quryltai=quryltai,
        )
    else:
        quryltai = None
    chats = {ASTANA_HUB: chat} | ({KURULTAI: kurultai_chat} if kurultai_chat is not None else {})
    stt = speech or build_stt_provider(settings)
    tts_provider = tts or speech or build_tts_provider(settings)

    @asynccontextmanager
    async def lifespan(_: FastAPI):
        sync_task = refresh_task = retention_task = index_task = quryltai_task = None
        sheet_client = None
        if syncer is not None:
            refresh_task = asyncio.create_task(
                syncer.run_refresh(settings.knowledge_reload_interval_s)
            )
            if syncer.fetcher is not None:
                sheet_client = syncer.fetcher.new_client()
                sync_task = asyncio.create_task(
                    syncer.run_sheet_periodic(settings.knowledge_sync_interval_s, sheet_client)
                )
        if indexer is not None:
            # при старте (фрагментов нет / устарели) и после синков, которые что-то поменяли; не блокирует
            index_task = asyncio.create_task(
                indexer.run_periodic(settings.knowledge_reload_interval_s)
            )
        if quryltai is not None and settings.quryltai_sync:
            # при старте (снимка нет или он старше часа) и раз в QURYLTAI_REFRESH_S; без сети — прежний снимок
            quryltai_task = asyncio.create_task(
                quryltai.run_periodic(settings.quryltai_refresh_s, settings.quryltai_retry_s)
            )
        if settings.question_log_retention_days > 0:
            retention_task = asyncio.create_task(
                _retention_loop(log_writer, repo, settings.question_log_retention_days)
            )
        try:
            yield
        finally:
            await _cancel(sync_task)
            await _cancel(refresh_task)
            await _cancel(retention_task)
            await _cancel(index_task)
            await _cancel(quryltai_task)
            await close_resources(
                sheet_client,
                text_llm,
                vision_llm,
                stt,
                tts_provider,
                chat,
                kurultai_chat,
                quryltai,
                embedder,
            )
            await asyncio.to_thread(log_writer.close, 3.0)
            engine.dispose()

    app = FastAPI(
        lifespan=lifespan,
        title="Astana Hub Robot API",
        version=__version__,
        description="Контракт: docs/api-contract.md (v1)",
        docs_url="/docs" if settings.expose_docs else None,
        redoc_url="/redoc" if settings.expose_docs else None,
        openapi_url="/openapi.json" if settings.expose_docs else None,
    )
    app.state.settings = settings
    app.state.store = store
    app.state.knowledge = syncer
    app.state.engine = engine
    app.state.rate_limiter = RateLimiter(settings.rate_limit_per_min)
    app.state.kb_rate_limiter = RateLimiter(settings.kb_rate_limit_per_min)
    app.state.service = service
    app.state.chat = chat  # агент Astana Hub (профиль astana_hub)
    app.state.chats = chats  # профиль → агент (app.api.chat_for)
    app.state.quryltai = quryltai
    app.state.kb_search = kb_search
    app.state.indexer = indexer
    app.state.log_repo = repo
    app.state.log_writer = log_writer
    app.state.metrics = metrics
    app.state.latency_repo = latency_repo
    app.state.admin_rate_limiter = RateLimiter(settings.admin_rate_limit_per_min)
    app.state.stt = stt
    app.state.tts = tts_provider
    app.state.stt_stream = stt_stream or build_stt_stream_provider(settings, stt)
    app.state.speech_rate_limiter = RateLimiter(settings.speech_rate_limit_per_min)
    app.state.live_registry = LiveRegistry()
    app.state.live_services = live_services or (
        build_live_services if settings.resolved_live_enabled() else None
    )
    app.state.tts_cache = TtsCache(
        memory_bytes=settings.tts_cache_memory_mb * 1024 * 1024,
        disk_dir=settings.tts_cache_dir,
        disk_bytes=settings.tts_cache_disk_mb * 1024 * 1024,
    )

    install_error_handlers(app)
    app.include_router(router)
    app.include_router(speech_router)
    app.include_router(live_router)
    app.include_router(metrics_router)
    # Порядок: RequestContext (внешний, request_id) → Guard (токен и лимит тела) → FastAPI
    app.add_middleware(GuardMiddleware, settings=settings)
    app.add_middleware(
        RequestContextMiddleware, trusted_proxies=parse_networks(settings.trusted_proxies)
    )

    logger.info(
        "startup",
        extra={
            "version": __version__,
            "text_llm": f"{text_llm.name}:{text_llm.model}",
            "vision_llm": f"{vision_llm.name}:{vision_llm.model}",
            "chat": chat.name,
            "kurultai": kurultai_chat.name if kurultai_chat is not None else "off",
            "retrieval": (
                f"{chat.retrieval}:{embedder.name}:{embedder.model}" if embedder else "off"
            ),
            "stt": app.state.stt.name,
            "stt_stream": app.state.stt_stream.name,
            "tts": app.state.tts.name,
            "live": app.state.live_services is not None and settings.resolved_live_enabled(),
        },
    )
    return app


def __getattr__(name: str):
    # `app.main:app` создаётся лениво, чтобы импорт модуля в тестах не требовал env
    if name == "app":
        global app
        app = create_app()
        return app
    raise AttributeError(name)
