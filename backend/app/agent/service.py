"""Агентский чат /v1/chat: OpenAI Agents SDK + Claude Haiku через OpenRouter
(docs/decisions/0001-agent-chat-stack.md).

- Модель сама выбирает действие робота (инструменты app/agent/tools.py), помнит разговор (таблица
  messages) и переспрашивает. Ответ — SSE: phrase × N, action × M, затем done; формат — docs/api-contract.md.
- Текст каждого ответа модели придерживается до его конца: если в том же ответе модель вызвала инструмент,
  текст — это её рассуждение вслух («The user asks…»), он не озвучивается.
- Выходной фильтр и лимит длины — те же, что у /v1/ask (app/safety.py).
- Агент недоступен (ошибка API, лимит шагов) до первой фразы → ответ даёт /v1/ask (AnswerService).
  CHAT_PROVIDER=off → всегда /v1/ask.
- Трассировка SDK выключена: тексты посетителей не уходят в OpenAI.

Категории в логе вопросов (endpoint=chat): agent — ответ агента; small_talk — локальный быстрый ответ;
out_of_scope — агент вызвал no_answer или промолчал; blocked — ответ не прошёл выходной фильтр.
"""

from __future__ import annotations

import asyncio
import json
import re
import time
from collections import OrderedDict
from collections.abc import AsyncIterator
from contextlib import aclosing
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta, timezone

import httpx
from agents import (
    Agent,
    AgentsException,
    ModelSettings,
    OpenAIChatCompletionsModel,
    RunConfig,
    Runner,
    set_tracing_disabled,
)
from agents.agent import StopAtTools
from openai import AsyncOpenAI, OpenAIError

from app.agent.prompts import LOOKING, agent_instructions, agent_turn
from app.agent.tools import STOP_TOOLS, TOOLS, ChatContext
from app.config import Settings
from app.db import MessageRepo, SessionRepo
from app.errors import ApiError, error_body, llm_timeout
from app.knowledge.source import KnowledgeStore
from app.lang import Lang, resolve_lang, script_of
from app.log_writer import BackgroundLogWriter
from app.logging_setup import logger
from app.prompts import REFUSALS, wrap_visitor
from app.safety import BLOCKED_ANSWERS, blocked_reason, kb_corpus, limit_phrases
from app.schemas import AnswerResponse, AskRequest, ChatAction, ChatResponse
from app.service import AnswerService
from app.text import clean_for_tts, split_phrases

set_tracing_disabled(True)

ASTANA_TZ = timezone(timedelta(hours=5))
HISTORY_TIMEOUT_S = 1.0
_EPHEMERAL = {"type": "ephemeral"}
RECENT_SESSIONS = 1000  # разговоров в кеше памяти; старые вытесняются
_ERROR_STATUS = {"timeout": 504, "llm_unavailable": 503}


@dataclass
class _ChatState:
    """Что ушло клиенту — для лога и памяти, если посетитель перебил робота."""

    lang: Lang
    emitted: list[str] = field(default_factory=list)
    actions: list[dict] = field(default_factory=list)
    calls: list[tuple[str, dict, str]] = field(default_factory=list)
    sources: list[str] = field(default_factory=list)
    category: str = "agent"
    provider: str = ""
    stop: bool = False  # лимит длины или фильтр — дальше модель не слушаем
    logged: bool = False  # лог и память уже записаны (или ответ отдал /v1/ask)


# Модель без размышлений иногда «думает» текстом: «The visitor asks…», «Посетитель просит…»
_NARRATION = re.compile(
    r"^(the (visitor|user)|visitor|user|i should|i need to|"
    r"посетитель (спрашивает|просит|хочет|говорит)|пользователь)\b",
    re.IGNORECASE,
)
# Комментарии модели о своих правилах и инструментах: «по правилам я могу…», «вызову guide_to»
_META = re.compile(
    r"по правилам|согласно правилам|системн\w* промпт|\b(where_is|guide_to|no_answer|look\(\))",
    re.IGNORECASE,
)


def _is_narration(phrase: str, lang: Lang) -> bool:
    """Фраза — рассуждение модели, а не ответ посетителю (не озвучиваем)."""
    if _NARRATION.match(phrase) or _META.search(phrase):
        return True
    # по-английски посреди русского/казахского ответа — это разбор реплики, а не ответ
    return lang != "en" and script_of(phrase) == "lat" and len(phrase.split()) >= 4


@dataclass
class _Recent:
    """Память разговора в процессе: (роль в messages, текст), старые первыми."""

    turns: list[tuple[str, str]] = field(default_factory=list)
    loaded: bool = False  # история из БД уже подмешана


def _merge(stored: list[tuple[str, str]], fresh: list[tuple[str, str]]) -> list[tuple[str, str]]:
    """История из БД + реплики из кеша, которые до БД ещё не дошли (без повторов на стыке)."""
    for k in range(len(fresh), 0, -1):
        if stored[-k:] == fresh[:k]:
            return stored + fresh[k:]
    return stored + fresh


def _model_items(turns: list[tuple[str, str]], limit: int) -> list[dict]:
    items: list[dict] = []
    for role, text in turns:
        model_role = "user" if role == "visitor" else "assistant"
        content = wrap_visitor(text) if model_role == "user" else text
        if items and items[-1]["role"] == model_role:  # перебитый ответ — реплики подряд
            items[-1]["content"] += "\n" + content
        else:
            items.append({"role": model_role, "content": content})
    items = items[-limit:]
    while items and items[0]["role"] != "user":
        items.pop(0)
    return items


class PromptCacheTransport(httpx.AsyncBaseTransport):
    """Точка кеша промпта Claude сразу после системного сообщения.

    `cache_control` в корне запроса (ModelSettings.extra_body) кеширует запрос целиком — это помогает только
    второму запросу внутри одного ответа (после вызова тула). Чтобы правила, тулы и база знаний кешировались
    для всех посетителей, системное сообщение отправляется блоком с `cache_control` (так OpenRouter передаёт
    точку кеша в Anthropic). SDK сам так не умеет, поэтому тело запроса правится здесь."""

    def __init__(self, inner: httpx.AsyncBaseTransport) -> None:
        self.inner = inner

    async def handle_async_request(self, request: httpx.Request) -> httpx.Response:
        if request.method == "POST" and request.url.path.endswith("/chat/completions"):
            body = json.loads(request.content)
            for message in body.get("messages", []):
                if message.get("role") == "system" and isinstance(message.get("content"), str):
                    message["content"] = [
                        {"type": "text", "text": message["content"], "cache_control": _EPHEMERAL}
                    ]
                    break
            request = httpx.Request(
                request.method,
                request.url,
                headers=[
                    (k, v) for k, v in request.headers.items() if k.lower() != "content-length"
                ],
                json=body,
                extensions=request.extensions,
            )
        return await self.inner.handle_async_request(request)

    async def aclose(self) -> None:
        await self.inner.aclose()


def build_chat_client(
    settings: Settings, transport: httpx.AsyncBaseTransport | None = None
) -> AsyncOpenAI | None:
    """transport — подменить сеть (тесты); точка кеша промпта добавляется всегда."""
    if settings.resolved_chat_provider() != "openrouter" or settings.openrouter_api_key is None:
        return None
    return AsyncOpenAI(
        base_url=settings.openrouter_base_url,
        api_key=settings.openrouter_api_key.get_secret_value(),
        timeout=settings.agent_total_s,
        max_retries=0,
        http_client=httpx.AsyncClient(
            transport=PromptCacheTransport(transport or httpx.AsyncHTTPTransport()),
            timeout=settings.agent_total_s,
        ),
    )


class ChatService:
    def __init__(
        self,
        settings: Settings,
        store: KnowledgeStore,
        answers: AnswerService,
        log_writer: BackgroundLogWriter,
        client: AsyncOpenAI | None,
        messages: MessageRepo | None = None,
        sessions: SessionRepo | None = None,
    ) -> None:
        self.settings = settings
        self.store = store
        self.answers = answers
        self.log_writer = log_writer
        self.client = client
        self.messages = messages
        self.sessions = sessions
        self.model = (
            OpenAIChatCompletionsModel(settings.agent_model, client) if client is not None else None
        )
        self._recent: OrderedDict[str, _Recent] = OrderedDict()

    @property
    def name(self) -> str:
        return f"openrouter:{self.settings.agent_model}" if self.model else "off"

    async def aclose(self) -> None:
        if self.client is not None:
            await self.client.close()

    def _model_settings(self) -> ModelSettings:
        extra: dict = {"reasoning": {"enabled": False}}  # размышления Haiku: +1–4 с к первой фразе
        if self.settings.agent_model.startswith("anthropic/"):
            # Кеш промпта Claude: весь запрос (второй запрос после тула); правила и база — PromptCacheTransport
            extra["cache_control"] = _EPHEMERAL
        return ModelSettings(
            max_tokens=self.settings.agent_max_tokens, include_usage=True, extra_body=extra
        )

    # ---------- память ----------
    async def _history(self, session_id: str) -> list[dict]:
        """Последние реплики разговора для модели.

        Источник — кеш в памяти процесса: запись в БД идёт фоном, а следующий вопрос робот присылает сразу
        после done, раньше, чем она дойдёт до базы. Сессии нет в кеше (перезапуск сервера) — читаем БД;
        БД недоступна — без истории."""
        limit = self.settings.agent_history_messages
        if limit <= 0:
            return []
        entry = self._recent.get(session_id)
        if entry is None or not entry.loaded:
            stored = await self._load(session_id, limit)
            entry = self._recent_entry(session_id)
            if stored is not None:
                entry.turns = _merge(stored, entry.turns)[-limit * 2 :]
                entry.loaded = True
        self._recent.move_to_end(session_id)
        return _model_items(entry.turns, limit)

    async def _load(self, session_id: str, limit: int) -> list[tuple[str, str]] | None:
        if self.messages is None:
            return []
        try:
            rows = await asyncio.wait_for(
                asyncio.to_thread(self.messages.history, session_id, limit * 2),
                timeout=HISTORY_TIMEOUT_S,
            )
        except Exception as exc:
            logger.warning("chat_history_failed", extra={"error": type(exc).__name__})
            return None
        return [(r.role, r.content) for r in rows if r.role in ("visitor", "assistant")]

    def _recent_entry(self, session_id: str) -> _Recent:
        entry = self._recent.get(session_id)
        if entry is None:
            entry = self._recent[session_id] = _Recent()
            while len(self._recent) > RECENT_SESSIONS:
                self._recent.popitem(last=False)
        return entry

    def remember(
        self,
        session_id: str,
        lang: str,
        question: str,
        answer: str,
        calls: list[tuple[str, dict, str]] = (),  # type: ignore[assignment]
    ) -> None:
        """Обмен — в память агента: сразу в кеш, в БД — фоном (ответ БД не ждёт)."""
        entry = self._recent_entry(session_id)
        entry.turns.append(("visitor", question))
        if answer:
            entry.turns.append(("assistant", answer))
        del entry.turns[: -self.settings.agent_history_messages * 2 or None]
        if self.messages is None:
            return
        items: list[dict] = [{"role": "visitor", "content": question}]
        items += [
            {"role": "tool", "content": result, "tool_name": name, "tool_args": args}
            for name, args, result in calls
        ]
        if answer:
            items.append({"role": "assistant", "content": answer})
        messages, sessions = self.messages, self.sessions

        def job() -> None:
            if sessions is not None:
                sessions.touch(session_id, lang)
            messages.add_many(session_id, items)

        self.log_writer.submit_job(job, name="messages")

    # ---------- ответ ----------
    async def chat(self, req: AskRequest, request_id: str) -> ChatResponse:
        """JSON-режим: тот же поток, собранный целиком."""
        phrases: list[str] = []
        actions: list[ChatAction] = []
        async with aclosing(self.chat_stream(req, request_id)) as events:
            async for event, data in events:
                if event == "phrase":
                    phrases.append(data["text"])
                elif event == "action":
                    actions.append(ChatAction(**data))
                elif event == "error":
                    err = data["error"]
                    raise ApiError(_ERROR_STATUS.get(err["code"], 500), err["code"], err["message"])
                elif event == "done":
                    return ChatResponse(
                        request_id=data["request_id"],
                        answer=" ".join(phrases),
                        phrases=phrases,
                        lang=data["lang"],
                        found=data["found"],
                        sources=data["sources"],
                        actions=actions,
                    )
        raise ApiError(500, "internal_error", "Internal server error")

    async def chat_stream(
        self, req: AskRequest, request_id: str
    ) -> AsyncIterator[tuple[str, dict]]:
        """SSE: ("phrase", {text}), ("action", {type, place_id}), затем ("done", {...}) или ("error", {...}).
        Клиент оборвал поток до done → в лог и память идут уже отправленные фразы (interrupted=true)."""
        started = time.perf_counter()
        st = _ChatState(lang=resolve_lang(req.lang, None, req.question))
        finished = False
        try:
            async with aclosing(self._chat_stream(req, request_id, st, started)) as events:
                async for event, data in events:
                    if event in ("done", "error"):
                        finished = True
                    yield event, data
        except Exception:
            logger.exception("chat_stream_failed")
            if not finished:
                yield "error", error_body("internal_error", "Internal server error")
        finally:
            if not finished and not st.logged and st.emitted:
                await self._finish(req, request_id, st, started, interrupted=True)

    async def _finish(
        self,
        req: AskRequest,
        request_id: str,
        st: _ChatState,
        started: float,
        interrupted: bool = False,
    ) -> dict:
        st.logged = True
        found = st.category not in ("out_of_scope", "blocked")
        resp = AnswerResponse(
            request_id=request_id,
            answer=" ".join(st.emitted),
            phrases=list(st.emitted),
            lang=st.lang,
            found=found,
            sources=st.sources if found else [],
        )
        await self.answers._log(
            endpoint="chat",
            request_id=request_id,
            session_id=req.session_id,
            question=req.question,
            place_id=req.place_id,
            resp=resp,
            category=st.category,
            provider_id=st.provider,
            started=started,
            interrupted=interrupted,
        )
        self.remember(req.session_id, st.lang, req.question, resp.answer, st.calls)
        return {
            "request_id": request_id,
            "lang": resp.lang,
            "found": resp.found,
            "sources": resp.sources,
            "actions": st.actions,
        }

    def _admit(self, st: _ChatState, text: str, question: str, corpus: str) -> list[str]:
        """Фразы, которые можно отправить: выходной фильтр + лимит 4 фразы / 600 символов."""
        out: list[str] = []
        for phrase in split_phrases(clean_for_tts(text)):
            if _is_narration(phrase, st.lang):
                logger.warning("agent_narration_dropped", extra={"chars": len(phrase)})
                continue
            reason = blocked_reason(" ".join([*st.emitted, *out, phrase]), question, corpus)
            if reason:
                logger.warning("answer_blocked", extra={"endpoint": "chat", "reason": reason})
                st.category = "blocked"
                st.stop = True
                break
            if len(limit_phrases([*st.emitted, *out, phrase])) <= len(st.emitted) + len(out):
                st.stop = True
                break
            out.append(phrase)
        return out

    async def _chat_stream(
        self, req: AskRequest, request_id: str, st: _ChatState, started: float
    ) -> AsyncIterator[tuple[str, dict]]:
        quick = self.answers._quick(req, request_id)
        if quick is not None:
            st.lang, st.category, st.provider = quick.lang, "small_talk", "local:smalltalk"
            st.emitted.extend(quick.phrases)
            done = await self._finish(req, request_id, st, started)
            for phrase in quick.phrases:
                yield "phrase", {"text": phrase}
            yield "done", done
            return
        if self.model is None:
            async for event in self._fallback(req, request_id, st):
                yield event
            return

        st.provider = self.name
        kb = self.store.get()
        corpus = kb_corpus(kb)
        history = await self._history(req.session_id)
        ctx = ChatContext(kb=kb, lang=st.lang)
        agent = Agent[ChatContext](
            name="astana_hub",
            instructions=agent_instructions(kb, self.settings.answer_mode),
            model=self.model,
            tools=TOOLS,
            tool_use_behavior=StopAtTools(stop_at_tool_names=STOP_TOOLS),
            model_settings=self._model_settings(),
        )
        turn = agent_turn(
            req.question,
            req.lang,
            kb.place(req.place_id),
            req.place_id,
            datetime.now(UTC).astimezone(ASTANA_TZ),
        )
        result = Runner.run_streamed(
            agent,
            [*history, {"role": "user", "content": turn}],
            context=ctx,
            max_turns=self.settings.agent_max_turns,
            run_config=RunConfig(tracing_disabled=True),
        )

        loop = asyncio.get_running_loop()
        first_deadline = loop.time() + self.settings.agent_timeout_s
        total_deadline = loop.time() + self.settings.agent_total_s
        events = result.stream_events().__aiter__()
        turn_text: list[str] = []
        sent_actions = 0
        first_phrase_ms: int | None = None
        dropped = 0
        error: ApiError | None = None
        failure: Exception | None = None
        try:
            while not st.stop:
                deadline = first_deadline if not st.emitted else total_deadline
                try:
                    ev = await asyncio.wait_for(
                        events.__anext__(), timeout=max(0.01, deadline - loop.time())
                    )
                except StopAsyncIteration:
                    break
                if ev.type == "raw_response_event":
                    data = ev.data
                    if data.type == "response.output_text.delta":
                        turn_text.append(data.delta)
                    elif data.type == "response.completed":
                        text = "".join(turn_text)
                        turn_text.clear()
                        if any(
                            getattr(i, "type", None) == "function_call"
                            for i in data.response.output
                        ):
                            dropped += len(text.strip())
                            continue
                        for phrase in self._admit(st, text, req.question, corpus):
                            if first_phrase_ms is None:
                                first_phrase_ms = int((time.perf_counter() - started) * 1000)
                            st.emitted.append(phrase)
                            yield "phrase", {"text": phrase}
                elif ev.type == "run_item_stream_event" and ev.name == "tool_output":
                    for action in ctx.actions[sent_actions:]:
                        st.actions.append(action)
                        yield "action", action
                    sent_actions = len(ctx.actions)
        except TimeoutError:
            error = llm_timeout()
        except (AgentsException, OpenAIError) as exc:
            failure = exc
        finally:
            result.cancel()

        usage = result.context_wrapper.usage
        logger.info(
            "agent_run",
            extra={
                "model": self.settings.agent_model,
                "requests": usage.requests,
                "input_tokens": usage.input_tokens,
                "cached_tokens": getattr(usage.input_tokens_details, "cached_tokens", 0),
                "output_tokens": usage.output_tokens,
                "tools": [name for name, _, _ in ctx.calls],
                "preamble_dropped_chars": dropped,
                "first_phrase_ms": first_phrase_ms,
                "error": type(failure or error).__name__ if (failure or error) else None,
            },
        )
        st.calls = ctx.calls
        st.sources = ctx.sources
        for action in ctx.actions[sent_actions:]:
            st.actions.append(action)
            yield "action", action

        nothing_sent = not st.emitted and not st.actions
        if failure is not None and nothing_sent:
            logger.warning(
                "agent_failed_fallback",
                extra={"error": type(failure).__name__, "detail": str(failure)[:200]},
            )
            async for event in self._fallback(req, request_id, st):
                yield event
            return
        if error is not None and nothing_sent:
            yield "error", error_body(error.code, error.message)
            return

        extra: str | None = None
        if st.category == "blocked":
            extra = BLOCKED_ANSWERS[st.lang]
        elif not st.emitted and ctx.stopped_by == "look":
            extra = LOOKING[st.lang]
        elif not st.emitted:
            st.category = "out_of_scope"
            extra = REFUSALS[st.lang]
        if extra:
            for phrase in limit_phrases([*st.emitted, *split_phrases(extra)])[len(st.emitted) :]:
                st.emitted.append(phrase)
                yield "phrase", {"text": phrase}
        yield "done", await self._finish(req, request_id, st, started)

    async def _fallback(
        self, req: AskRequest, request_id: str, st: _ChatState
    ) -> AsyncIterator[tuple[str, dict]]:
        """Ответ через /v1/ask (лог пишет AnswerService, endpoint=ask); в память — после done."""
        st.logged = True
        phrases: list[str] = []
        async with aclosing(self.answers.ask_stream(req, request_id)) as events:
            async for event, data in events:
                if event == "phrase":
                    phrases.append(data["text"])
                elif event == "done":
                    self.remember(req.session_id, data["lang"], req.question, " ".join(phrases))
                    data = {**data, "actions": []}
                yield event, data
