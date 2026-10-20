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

from app.config import Settings
from app.errors import ApiError, error_body, llm_timeout, llm_unavailable
from app.knowledge.source import KnowledgeStore
from app.lang import Lang, resolve_lang
from app.llm.base import LLMAnswer, LLMError, LLMProvider, LLMRequest
from app.log_writer import BackgroundLogWriter
from app.logging_setup import logger
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
    ) -> None:
        self.settings = settings
        self.store = store
        self.text_llm = text_llm
        self.vision_llm = vision_llm
        self.log_writer = log_writer

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
    ) -> None:
        latency_ms = int((time.perf_counter() - started) * 1000)
        logger.info(
            f"{endpoint}_answered",
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
        quick = self._quick(req, request_id)
        if quick is not None:
            await self._log_ask(req, quick, "small_talk", "local:smalltalk", started)
            return quick
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
        await self._log_ask(
            req, resp, category, self._provider_id(self.text_llm, res.model), started
        )
        return resp

    async def _log_ask(
        self, req: AskRequest, resp: AnswerResponse, category: str, provider_id: str, started
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
        )

    async def ask_stream(self, req: AskRequest, request_id: str) -> AsyncIterator[tuple[str, dict]]:
        """SSE-версия /ask: события ("phrase", {text}) по мере готовности предложений,
        затем ("done", {...}) или ("error", {...}). Формат — docs/api-contract.md.
        Любое непредвиденное исключение превращается в event: error (поток уже начат)."""
        finished = False
        try:
            # aclosing: при отмене/обрыве SSE внутренний генератор (и стрим LLM) закрывается сразу
            async with aclosing(self._ask_stream(req, request_id)) as events:
                async for event, data in events:
                    if event in ("done", "error"):
                        finished = True
                    yield event, data
        except Exception:
            logger.exception("ask_stream_failed")
            if not finished:
                yield "error", error_body("internal_error", "Internal server error")

    async def _ask_stream(
        self, req: AskRequest, request_id: str
    ) -> AsyncIterator[tuple[str, dict]]:
        started = time.perf_counter()
        quick = self._quick(req, request_id)
        if quick is not None:
            for phrase in quick.phrases:
                yield "phrase", {"text": phrase}
            yield "done", _done(quick)
            await self._log_ask(req, quick, "small_talk", "local:smalltalk", started)
            return

        if not hasattr(self.text_llm, "answer_stream"):
            try:
                resp = await self.ask(req, request_id)
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
        emitted: list[str] = []
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
                first = False
                sentences = parser.feed(chunk)
                category = decide_category(parser.category, parser.found, self.mode)
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
        resp = AnswerResponse(
            request_id=request_id,
            answer=answer,
            phrases=emitted,
            lang=lang,
            found=category in ANSWERED,
            sources=sources,
        )
        yield "done", _done(resp)
        await self._log_ask(
            req,
            resp,
            category,
            self._provider_id(self.text_llm, llm_req.info.get("model")),
            started,
        )

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
        return resp


def _done(resp: AnswerResponse) -> dict:
    return {
        "request_id": resp.request_id,
        "lang": resp.lang,
        "found": resp.found,
        "sources": resp.sources,
    }
