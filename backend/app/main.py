"""Точка входа: `uv run uvicorn app.main:app --port 8000`."""

from __future__ import annotations

import asyncio
import time
from contextlib import asynccontextmanager

from fastapi import FastAPI
from starlette.types import ASGIApp, Message, Receive, Scope, Send

from app import __version__
from app.api import router
from app.config import Settings
from app.db import QuestionLogRepo, make_engine
from app.errors import error_response, install_error_handlers
from app.knowledge.sheets import SheetKnowledgeSource
from app.knowledge.source import KnowledgeSource, KnowledgeStore, YamlKnowledgeSource
from app.llm.base import LLMProvider
from app.llm.factory import build_text_provider, build_vision_provider
from app.logging_setup import (
    configure_logging,
    logger,
    new_request_id,
    reset_request_id,
    set_request_id,
)
from app.ratelimit import RateLimiter
from app.service import AnswerService
from app.speech.base import SttProvider, TtsProvider
from app.speech.cache import TtsCache
from app.speech.factory import build_stt_provider, build_tts_provider
from app.speech_api import router as speech_router


class RequestContextMiddleware:
    """X-Request-Id + access-лог одной JSON-строкой. Чистый ASGI (корректно со стримингом)."""

    def __init__(self, app: ASGIApp) -> None:
        self.app = app

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
                    "status": status,
                    "duration_ms": int((time.perf_counter() - started) * 1000),
                },
            )
            reset_request_id(token)


def build_knowledge_source(settings: Settings) -> KnowledgeSource:
    """YAML по умолчанию; Google-таблица — если задан KNOWLEDGE_SHEET_ID."""
    yaml_source = YamlKnowledgeSource(settings.knowledge_dir)
    if not settings.knowledge_sheet_id:
        return yaml_source
    sheet = SheetKnowledgeSource(
        sheet_id=settings.knowledge_sheet_id,
        tabs=(
            settings.knowledge_sheet_facts,
            settings.knowledge_sheet_places,
            settings.knowledge_sheet_buttons,
        ),
        snapshot_path=settings.knowledge_snapshot_path,
        fallback=yaml_source,
    )
    sheet.initial_load()
    return sheet


def create_app(
    settings: Settings | None = None,
    text_llm: LLMProvider | None = None,
    vision_llm: LLMProvider | None = None,
    speech: SttProvider | TtsProvider | None = None,
    tts: TtsProvider | None = None,
) -> FastAPI:
    """speech — подменить и STT, и TTS одним провайдером (тесты); tts — только TTS."""
    settings = settings or Settings()
    configure_logging(settings.log_level)
    if not settings.robot_token.get_secret_value():
        raise RuntimeError("ROBOT_TOKEN is not set (see backend/.env.example)")

    source = build_knowledge_source(settings)
    store = KnowledgeStore(source, settings.knowledge_reload_interval_s)
    store.get()  # битая база — падаем на старте, а не на первом вопросе
    text_llm = text_llm or build_text_provider(settings)
    vision_llm = vision_llm or build_vision_provider(settings, text_llm)
    repo = QuestionLogRepo(make_engine(settings.database_url))

    @asynccontextmanager
    async def lifespan(_: FastAPI):
        task = None
        if isinstance(source, SheetKnowledgeSource) and settings.knowledge_sync_interval_s > 0:
            task = asyncio.create_task(source.run_periodic(settings.knowledge_sync_interval_s))
        try:
            yield
        finally:
            if task:
                task.cancel()

    app = FastAPI(
        lifespan=lifespan,
        title="Astana Hub Robot API",
        version=__version__,
        description="Контракт: docs/api-contract.md (v1)",
    )
    app.state.settings = settings
    app.state.store = store
    app.state.rate_limiter = RateLimiter(settings.rate_limit_per_min)
    app.state.service = AnswerService(settings, store, text_llm, vision_llm, repo)
    app.state.log_repo = repo
    app.state.stt = speech or build_stt_provider(settings)
    app.state.tts = tts or speech or build_tts_provider(settings)
    app.state.speech_rate_limiter = RateLimiter(settings.speech_rate_limit_per_min)
    app.state.tts_cache = TtsCache(
        memory_bytes=settings.tts_cache_memory_mb * 1024 * 1024,
        disk_dir=settings.tts_cache_dir,
        disk_bytes=settings.tts_cache_disk_mb * 1024 * 1024,
    )

    install_error_handlers(app)
    app.include_router(router)
    app.include_router(speech_router)
    app.add_middleware(RequestContextMiddleware)

    logger.info(
        "startup",
        extra={
            "version": __version__,
            "text_llm": f"{text_llm.name}:{text_llm.model}",
            "vision_llm": f"{vision_llm.name}:{vision_llm.model}",
            "stt": app.state.stt.name,
            "tts": app.state.tts.name,
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
