"""Логика /ask и /look: база → промпт → LLM → постобработка → лог.

Категории ответа (поле category у модели и в логе вопросов):
- kb          — ответ по базе знаний (found=true, sources заполнены);
- small_talk  — приветствие, «ты меня слышишь?», «что умеешь?» и т.п. (found=true, sources=[]);
- general     — только ANSWER_MODE=open: в базе ответа нет, модель ответила из общих знаний (found=false);
- out_of_scope — ответа нет: стандартный отказ «обратитесь на ресепшен» (found=false);
- blocked     — ответ модели не прошёл выходной фильтр (дословный повтор реплики, чужие ссылки/телефоны),
                заменён безопасным (found=false). См. app/safety.py.

Длина ответа: не больше 4 фраз и 600 символов (и в обычном ответе, и в SSE).
"""

from __future__ import annotations

import asyncio
import time
from collections.abc import AsyncIterator
from contextlib import aclosing
from dataclasses import dataclass, field

from app.config import Settings
from app.errors import ApiError, error_body, llm_timeout, llm_unavailable
from app.knowledge.source import KnowledgeStore
from app.lang import Lang, resolve_lang
from app.llm.base import LLMAnswer, LLMError, LLMProvider, LLMRequest
from app.log_writer import BackgroundLogWriter
from app.logging_setup import logger
from app.metrics import NULL_RECORDER, LatencyRecorder, elapsed_ms
from app.prompts import (
    LOOK_REFUSALS,
    REFUSALS,
    ask_system_prompt,
    ask_user_prompt,
    look_system_prompt,
    look_user_prompt,
)
from app.safety import BLOCKED_ANSWERS, blocked_reason, kb_corpus, limit_phrases
from app.schemas import AnswerResponse, AskRequest, LookMeta
from app.smalltalk import quick_reply
from app.streaming import AnswerStreamParser
from app.text import clean_for_tts, split_phrases

DEFAULT_LOOK_QUESTION: dict[str, str] = {
    "ru": "Что это? Где я?",
    "kk": "Бұл не? Мен қайдамын?",
    "en": "What is this? Where am I?",
}

ANSWERED = ("kb", "small_talk")


@dataclass
class _StreamState:
    """Что успело уйти клиенту по SSE — для лога, если посетитель перебил робота."""

    emitted: list[str] = field(default_factory=list)
    category: str | None = None
    lang: str | None = None
    model: str | None = None
    logged: bool = False
    # тайминги (latency_events): от начала запроса
    started: float = 0.0
    provider: str | None = None
    first_token_ms: int | None = None
    first_phrase_ms: int | None = None
    error: str | None = None


def decide_category(category: str | None, found: bool | None, mode: str) -> str | None:
    """Категория с учётом режима. None — ещё неизвестно (стрим), "out_of_scope" — отказ."""
    if category is None:
        if found is None:
            return None
        return "kb" if found else "out_of_scope"
    if category in ANSWERED:
        return category
    if category == "general":
        return "general" if mode == "open" else "out_of_scope"
    return "out_of_scope"


class AnswerService:
    def __init__(
        self,
        settings: Settings,
        store: KnowledgeStore,
        text_llm: LLMProvider,
        vision_llm: LLMProvider,
        log_writer: BackgroundLogWriter,
        metrics: LatencyRecorder | None = None,
    ) -> None:
        self.settings = settings
        self.store = store
        self.text_llm = text_llm
        self.vision_llm = vision_llm
        self.log_writer = log_writer
        self.metrics = metrics or NULL_RECORDER

    @property
    def mode(self) -> str:
        return self.settings.answer_mode

    async def _call(self, coro, timeout_s: float) -> LLMAnswer:
        try:
            # Провайдер сам держит дедлайн (req.deadline_s); здесь — страховка сверху
            return await asyncio.wait_for(coro, timeout=timeout_s + 0.5)
        except TimeoutError as exc:
            raise llm_timeout() from exc
        except LLMError as exc:
            logger.error("llm_error", extra={"error": str(exc)})
            raise llm_unavailable() from exc

    def _finalize(
        self,
        res: LLMAnswer,
        requested_lang: str | None,
        question: str,
        refusals: dict[str, str],
        valid_sources: set[str],
        request_id: str,
        mode: str,
        corpus: str,
    ) -> tuple[AnswerResponse, str]:
        lang: Lang = resolve_lang(requested_lang, res.lang, question)
        answer = clean_for_tts(res.answer)
        category = decide_category(res.category, res.found, mode) or "out_of_scope"
        if not answer:
            category = "out_of_scope"
        if category != "out_of_scope":
            reason = blocked_reason(answer, question, corpus)
            if reason:
                logger.warning("answer_blocked", extra={"reason": reason})
                category = "blocked"
        sources: list[str] = []
        if category == "kb":
            sources = list(dict.fromkeys(s for s in res.sources if s in valid_sources))
        elif category == "out_of_scope":
            answer = refusals[lang]
        elif category == "blocked":
            answer = BLOCKED_ANSWERS[lang]
        phrases = limit_phrases(split_phrases(answer))
        resp = AnswerResponse(
            request_id=request_id,
            answer=" ".join(phrases),
            phrases=phrases,
            lang=lang,
            found=category in ANSWERED,
            sources=sources,
        )
        return resp, category

    async def _log(
        self,
        *,
        endpoint: str,
        request_id: str,
        session_id: str,
        question: str,
        place_id: str | None,
        resp: AnswerResponse,
        category: str,
        provider_id: str,
        started: float,
        interrupted: bool = False,
        event: str | None = None,  # имя события в логе вместо answered/interrupted (not_addressed)
    ) -> None:
        latency_ms = int((time.perf_counter() - started) * 1000)
        if event is None:
            event = "interrupted" if interrupted else "answered"
        logger.info(
            f"{endpoint}_{event}",
            extra={
                "session_id": session_id,
                "question": question,
                "lang": resp.lang,
                "place_id": place_id,
                "found": resp.found,
                "category": category,
                "sources": resp.sources,
                "provider": provider_id,
                "latency_ms": latency_ms,
                "interrupted": interrupted,
            },
        )
        # Фоном: ответ роботу не ждёт БД (зависшая/упавшая БД на /ask не влияет)
        self.log_writer.touch_session(session_id, resp.lang)
        self.log_writer.submit(
            request_id=request_id,
            session_id=session_id,
            endpoint=endpoint,
            question=question,
            lang=resp.lang,
            place_id=place_id,
            found=resp.found,
            category=category,
            answer=resp.answer,
            sources=resp.sources,
            provider=provider_id,
            latency_ms=latency_ms,
            interrupted=interrupted,
        )

    @staticmethod
    def _provider_id(provider: LLMProvider, model: str | None) -> str:
        return f"{provider.name}:{model or provider.model}"

    def _quick(self, req: AskRequest, request_id: str) -> AnswerResponse | None:
        lang = resolve_lang(req.lang, None, req.question)
        text = quick_reply(req.question, lang)
        if text is None:
            return None
        return AnswerResponse(
            request_id=request_id,
            answer=text,
            phrases=split_phrases(text),
            lang=lang,
            found=True,
            sources=[],
        )

    def _ask_request(self, req: AskRequest) -> LLMRequest:
        kb = self.store.get()
        place = kb.place(req.place_id)
        return LLMRequest(
            system=ask_system_prompt(kb, self.mode),
            user=ask_user_prompt(req.question, req.lang, place, req.place_id),
            question=req.question,
            lang=req.lang,
            place_id=req.place_id,
            kb=kb,
            deadline_s=self.settings.ask_timeout_s,
        )

    async def ask(self, req: AskRequest, request_id: str) -> AnswerResponse:
        started = time.perf_counter()
        try:
            resp, category, provider = await self._ask_json(req, request_id, started)
        except ApiError as exc:
            self.metrics.record(
                "ask",
                {"llm_total_ms": elapsed_ms(started)},
                lang=req.lang,
                provider=self._provider_id(self.text_llm, None),
                request_id=request_id,
                session_id=req.session_id,
                ok=False,
                extra={"stream": False, "error": exc.code},
            )
            raise
        self.metrics.record(
            "ask",
            {"llm_total_ms": elapsed_ms(started)},
            lang=resp.lang,
            provider=provider,
            request_id=request_id,
            session_id=req.session_id,
            extra={"stream": False, "category": category},
        )
        return resp

    async def _ask_json(
        self, req: AskRequest, request_id: str, started: float
    ) -> tuple[AnswerResponse, str, str]:
        """Ответ целиком (без записи таймингов): (ответ, категория, провайдер:модель)."""
        quick = self._quick(req, request_id)
        if quick is not None:
            await self._log_ask(req, quick, "small_talk", "local:smalltalk", started)
            return quick, "small_talk", "local:smalltalk"
        llm_req = self._ask_request(req)
        res = await self._call(self.text_llm.answer(llm_req), self.settings.ask_timeout_s)
        assert llm_req.kb is not None
        resp, category = self._finalize(
            res,
            req.lang,
            req.question,
            REFUSALS,
            llm_req.kb.source_ids(),
            request_id,
            self.mode,
            kb_corpus(llm_req.kb),
        )
        provider = self._provider_id(self.text_llm, res.model)
        await self._log_ask(req, resp, category, provider, started)
        return resp, category, provider

    async def _log_ask(
        self,
        req: AskRequest,
        resp: AnswerResponse,
        category: str,
        provider_id: str,
        started,
        interrupted: bool = False,
    ) -> None:
        await self._log(
            endpoint="ask",
            request_id=resp.request_id,
            session_id=req.session_id,
            question=req.question,
            place_id=req.place_id,
            resp=resp,
            category=category,
            provider_id=provider_id,
            started=started,
            interrupted=interrupted,
        )

    async def ask_stream(self, req: AskRequest, request_id: str) -> AsyncIterator[tuple[str, dict]]:
        """SSE-версия /ask: события ("phrase", {text}) по мере готовности предложений,
        затем ("done", {...}) или ("error", {...}). Формат — docs/api-contract.md.
        Любое непредвиденное исключение превращается в event: error (поток уже начат).

        Клиент оборвал поток до done (посетитель перебил робота) → в лог идут вопрос и уже отправленные
        фразы с interrupted=true. Ошибка LLM (event error) в лог не пишется, как и в JSON-режиме."""
        started = time.perf_counter()
        st = _StreamState(started=started)
        finished = False
        try:
            # aclosing: при отмене/обрыве SSE внутренний генератор (и стрим LLM) закрывается сразу
            async with aclosing(self._ask_stream(req, request_id, st)) as events:
                async for event, data in events:
                    if event == "phrase" and st.first_phrase_ms is None:
                        st.first_phrase_ms = elapsed_ms(started)
                    elif event == "error":
                        st.error = data["error"]["code"]
                    elif event == "done":
                        st.lang = data["lang"]
                    if event in ("done", "error"):
                        finished = True
                    yield event, data
        except Exception:
            logger.exception("ask_stream_failed")
            if not finished:
                st.error = "internal_error"
                yield "error", error_body("internal_error", "Internal server error")
        finally:
            total_ms = elapsed_ms(started)
            if not finished and not st.logged and st.emitted:
                # _log не ждёт БД (запись фоном), поэтому отмена задачи обрыва его не прервёт
                await self._log_ask(
                    req,
                    AnswerResponse(
                        request_id=request_id,
                        answer=" ".join(st.emitted),
                        phrases=list(st.emitted),
                        lang=resolve_lang(req.lang, st.lang, req.question),
                        found=st.category in ANSWERED,
                        sources=[],
                    ),
                    st.category or "out_of_scope",
                    self._provider_id(self.text_llm, st.model),
                    started,
                    interrupted=True,
                )
            # после лога вопросов: он важнее и не должен ждать в очереди за таймингами
            self.metrics.record(
                "ask",
                {
                    "llm_first_token_ms": st.first_token_ms,
                    "first_phrase_ms": st.first_phrase_ms,
                    "llm_total_ms": total_ms,
                },
                lang=resolve_lang(req.lang, st.lang, req.question),
                provider=st.provider or self._provider_id(self.text_llm, st.model),
                request_id=request_id,
                session_id=req.session_id,
                ok=st.error is None,
                extra={
                    "stream": True,
                    "category": st.category,
                    "interrupted": not finished,
                    "error": st.error,
                },
            )

    async def _ask_stream(
        self, req: AskRequest, request_id: str, st: _StreamState
    ) -> AsyncIterator[tuple[str, dict]]:
        started = time.perf_counter()
        quick = self._quick(req, request_id)
        if quick is not None:
            st.logged = True  # короткий локальный ответ: пишем как полный
            st.provider, st.category = "local:smalltalk", "small_talk"
            await self._log_ask(req, quick, "small_talk", "local:smalltalk", started)
            for phrase in quick.phrases:
                yield "phrase", {"text": phrase}
            yield "done", _done(quick)
            return

        if not hasattr(self.text_llm, "answer_stream"):
            try:
                resp, st.category, st.provider = await self._ask_json(req, request_id, started)
            except ApiError as exc:
                yield "error", error_body(exc.code, exc.message)
                return
            for phrase in resp.phrases:
                yield "phrase", {"text": phrase}
            yield "done", _done(resp)
            return

        llm_req = self._ask_request(req)
        kb = llm_req.kb
        assert kb is not None
        corpus = kb_corpus(kb)
        loop = asyncio.get_running_loop()
        end = loop.time() + self.settings.ask_stream_total_s
        parser = AnswerStreamParser()
        emitted = st.emitted
        held: list[str] = []  # предложения, пришедшие раньше категории/found
        stream = self.text_llm.answer_stream(llm_req).__aiter__()  # type: ignore[attr-defined]
        first = True
        error: ApiError | None = None
        category: str | None = None
        stop = False  # лимит длины или фильтр — дальше модель не слушаем

        def admit(sentences: list[str]) -> list[str]:
            """Фразы, которые можно отправить: выходной фильтр + лимит 4 фразы / 600 символов."""
            nonlocal category, stop
            out: list[str] = []
            for phrase in (p for s in sentences for p in split_phrases(clean_for_tts(s))):
                reason = blocked_reason(" ".join([*emitted, *out, phrase]), req.question, corpus)
                if reason:
                    logger.warning("answer_blocked", extra={"reason": reason, "stream": True})
                    category = "blocked"
                    stop = True
                    break
                if len(limit_phrases([*emitted, *out, phrase])) <= len(emitted) + len(out):
                    stop = True
                    break
                out.append(phrase)
            return out

        try:
            while not stop:
                timeout = llm_req.deadline_s + 0.5 if first else end - loop.time()
                try:
                    chunk = await asyncio.wait_for(stream.__anext__(), timeout=max(0.01, timeout))
                except StopAsyncIteration:
                    break
                if first:
                    st.first_token_ms = elapsed_ms(st.started)
                first = False
                sentences = parser.feed(chunk)
                category = decide_category(parser.category, parser.found, self.mode)
                st.category, st.lang = category, parser.lang
                st.model = llm_req.info.get("model")
                if category == "out_of_scope":
                    break  # отказ — дальше модель не слушаем
                if category is None:
                    held.extend(sentences)
                    continue
                for phrase in admit([*held, *sentences]):
                    emitted.append(phrase)
                    yield "phrase", {"text": phrase}
                held.clear()
            if not stop and category not in (None, "out_of_scope"):
                for phrase in admit([*held, *parser.finish()]):
                    emitted.append(phrase)
                    yield "phrase", {"text": phrase}
        except TimeoutError:
            error = llm_timeout()
        except LLMError as exc:
            logger.error("llm_error", extra={"error": str(exc)})
            error = llm_unavailable()
        finally:
            await stream.aclose()

        if error is not None:
            yield "error", error_body(error.code, error.message)
            return

        lang: Lang = resolve_lang(req.lang, parser.lang, req.question)
        if category != "blocked" and (not emitted or category is None):
            category = "out_of_scope"
        sources: list[str] = []
        if category == "kb":
            raw_sources = parser.full_json().get("sources") or []
            valid = kb.source_ids()
            sources = list(dict.fromkeys(s for s in raw_sources if s in valid))
        if category in ("out_of_scope", "blocked"):
            fallback = REFUSALS[lang] if category == "out_of_scope" else BLOCKED_ANSWERS[lang]
            for phrase in limit_phrases([*emitted, *split_phrases(fallback)])[len(emitted) :]:
                emitted.append(phrase)
                yield "phrase", {"text": phrase}
        answer = " ".join(emitted)
        st.category = category
        st.logged = True  # до done: клиент может закрыть поток сразу после него
        resp = AnswerResponse(
            request_id=request_id,
            answer=answer,
            phrases=emitted,
            lang=lang,
            found=category in ANSWERED,
            sources=sources,
        )
        await self._log_ask(
            req,
            resp,
            category,
            self._provider_id(self.text_llm, llm_req.info.get("model")),
            started,
        )
        yield "done", _done(resp)

    async def look(self, meta: LookMeta, image: bytes, request_id: str) -> AnswerResponse:
        started = time.perf_counter()
        kb = self.store.get()
        place = kb.place(meta.place_id)
        question = meta.question or DEFAULT_LOOK_QUESTION[meta.lang or "ru"]
        llm_req = LLMRequest(
            system=look_system_prompt(kb),
            user=look_user_prompt(question, meta.lang, place, meta.place_id),
            question=question,
            lang=meta.lang,
            place_id=meta.place_id,
            kb=kb,
            image=image,
            deadline_s=self.settings.look_timeout_s,
        )
        try:
            res = await self._call(
                self.vision_llm.describe_image(llm_req), self.settings.look_timeout_s
            )
        except ApiError as exc:
            self.metrics.record(
                "look",
                {"llm_total_ms": elapsed_ms(started)},
                lang=meta.lang,
                provider=self._provider_id(self.vision_llm, None),
                request_id=request_id,
                session_id=meta.session_id,
                ok=False,
                extra={"error": exc.code},
            )
            raise
        finally:
            llm_req.image = None  # кадр живёт только в памяти и только на время запроса
        # /look: любое описание кадра — ответ; отказ — только если модель не смогла описать
        if res.category in ("general", "small_talk"):
            res.category = "kb"
        resp, category = self._finalize(
            res,
            meta.lang,
            question,
            LOOK_REFUSALS,
            kb.source_ids(),
            request_id,
            "strict",
            kb_corpus(kb),
        )
        await self._log(
            endpoint="look",
            request_id=request_id,
            session_id=meta.session_id,
            question=question,
            place_id=meta.place_id,
            resp=resp,
            category=category,
            provider_id=self._provider_id(self.vision_llm, res.model),
            started=started,
        )
        self.metrics.record(
            "look",
            {"llm_total_ms": elapsed_ms(started)},
            lang=resp.lang,
            provider=self._provider_id(self.vision_llm, res.model),
            request_id=request_id,
            session_id=meta.session_id,
            extra={"category": category},
        )
        return resp


def _done(resp: AnswerResponse) -> dict:
    return {
        "request_id": resp.request_id,
        "lang": resp.lang,
        "found": resp.found,
        "sources": resp.sources,
    }
