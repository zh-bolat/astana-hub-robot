"""Логика /ask и /look: база → промпт → LLM → постобработка → лог."""

from __future__ import annotations

import asyncio
import time
from collections.abc import AsyncIterator

from starlette.concurrency import run_in_threadpool

from app.config import Settings
from app.db import QuestionLogRepo
from app.errors import ApiError, error_body, llm_timeout, llm_unavailable
from app.knowledge.source import KnowledgeStore
from app.lang import Lang, detect_lang, resolve_lang
from app.llm.base import LLMAnswer, LLMError, LLMProvider, LLMRequest
from app.logging_setup import logger
from app.prompts import (
    LOOK_REFUSALS,
    REFUSALS,
    ask_system_prompt,
    ask_user_prompt,
    look_system_prompt,
    look_user_prompt,
)
from app.schemas import AnswerResponse, AskRequest, LookMeta
from app.streaming import AnswerStreamParser
from app.text import clean_for_tts, split_phrases

DEFAULT_LOOK_QUESTION: dict[str, str] = {
    "ru": "Что это? Где я?",
    "kk": "Бұл не? Мен қайдамын?",
    "en": "What is this? Where am I?",
}


class AnswerService:
    def __init__(
        self,
        settings: Settings,
        store: KnowledgeStore,
        text_llm: LLMProvider,
        vision_llm: LLMProvider,
        log_repo: QuestionLogRepo,
    ) -> None:
        self.settings = settings
        self.store = store
        self.text_llm = text_llm
        self.vision_llm = vision_llm
        self.log_repo = log_repo

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
    ) -> AnswerResponse:
        lang: Lang = resolve_lang(requested_lang, res.lang, question)
        answer = clean_for_tts(res.answer)
        found = bool(res.found and answer)
        if found:
            sources = list(dict.fromkeys(s for s in res.sources if s in valid_sources))
        else:
            answer, sources = refusals[lang], []
        return AnswerResponse(
            request_id=request_id,
            answer=answer,
            phrases=split_phrases(answer),
            lang=lang,
            found=found,
            sources=sources,
        )

    async def _log(
        self,
        *,
        endpoint: str,
        request_id: str,
        session_id: str,
        question: str,
        place_id: str | None,
        resp: AnswerResponse,
        provider: LLMProvider,
        started: float,
        model: str | None = None,
    ) -> None:
        latency_ms = int((time.perf_counter() - started) * 1000)
        provider_id = f"{provider.name}:{model or provider.model}"
        logger.info(
            f"{endpoint}_answered",
            extra={
                "session_id": session_id,
                "question": question,
                "lang": resp.lang,
                "place_id": place_id,
                "found": resp.found,
                "sources": resp.sources,
                "provider": provider_id,
                "latency_ms": latency_ms,
            },
        )
        try:
            await run_in_threadpool(
                self.log_repo.add,
                request_id=request_id,
                session_id=session_id,
                endpoint=endpoint,
                question=question,
                lang=resp.lang,
                place_id=place_id,
                found=resp.found,
                answer=resp.answer,
                sources=resp.sources,
                provider=provider_id,
                latency_ms=latency_ms,
            )
        except Exception as exc:  # лог не должен ломать ответ роботу
            logger.error("question_log_failed", extra={"error": str(exc)})

    async def ask(self, req: AskRequest, request_id: str) -> AnswerResponse:
        started = time.perf_counter()
        kb = self.store.get()
        place = kb.place(req.place_id)
        llm_req = LLMRequest(
            system=ask_system_prompt(kb),
            user=ask_user_prompt(req.question, req.lang, place, req.place_id),
            question=req.question,
            lang=req.lang,
            place_id=req.place_id,
            kb=kb,
            deadline_s=self.settings.ask_timeout_s,
        )
        res = await self._call(self.text_llm.answer(llm_req), self.settings.ask_timeout_s)
        resp = self._finalize(res, req.lang, req.question, REFUSALS, kb.source_ids(), request_id)
        await self._log(
            endpoint="ask",
            request_id=request_id,
            session_id=req.session_id,
            question=req.question,
            place_id=req.place_id,
            resp=resp,
            provider=self.text_llm,
            started=started,
            model=res.model,
        )
        return resp

    async def ask_stream(self, req: AskRequest, request_id: str) -> AsyncIterator[tuple[str, dict]]:
        """SSE-версия /ask: события ("phrase", {text}) по мере готовности предложений,
        затем ("done", {...}) или ("error", {...}). Формат — docs/api-contract.md."""
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

        started = time.perf_counter()
        kb = self.store.get()
        place = kb.place(req.place_id)
        llm_req = LLMRequest(
            system=ask_system_prompt(kb),
            user=ask_user_prompt(req.question, req.lang, place, req.place_id),
            question=req.question,
            lang=req.lang,
            place_id=req.place_id,
            kb=kb,
            deadline_s=self.settings.ask_timeout_s,
        )
        loop = asyncio.get_running_loop()
        end = loop.time() + self.settings.ask_stream_total_s
        parser = AnswerStreamParser()
        emitted: list[str] = []
        held: list[str] = []  # предложения, пришедшие раньше поля found
        stream = self.text_llm.answer_stream(llm_req).__aiter__()  # type: ignore[attr-defined]
        first = True
        error: ApiError | None = None

        def to_phrases(sentences: list[str]) -> list[str]:
            return [p for s in sentences for p in split_phrases(clean_for_tts(s))]

        try:
            while True:
                timeout = llm_req.deadline_s + 0.5 if first else end - loop.time()
                try:
                    chunk = await asyncio.wait_for(stream.__anext__(), timeout=max(0.01, timeout))
                except StopAsyncIteration:
                    break
                first = False
                sentences = parser.feed(chunk)
                if parser.found is False:
                    break  # отказ — дальше модель не слушаем
                if parser.found is None:
                    held.extend(sentences)
                    continue
                for phrase in to_phrases([*held, *sentences]):
                    emitted.append(phrase)
                    yield "phrase", {"text": phrase}
                held.clear()
            if parser.found:
                for phrase in to_phrases([*held, *parser.finish()]):
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
        found = bool(parser.found and emitted)
        if found:
            raw_sources = parser.full_json().get("sources") or []
            valid = kb.source_ids()
            sources = list(dict.fromkeys(s for s in raw_sources if s in valid))
            answer = " ".join(emitted)
        else:
            answer, sources = REFUSALS[lang], []
            for phrase in split_phrases(answer):
                emitted.append(phrase)
                yield "phrase", {"text": phrase}
        resp = AnswerResponse(
            request_id=request_id,
            answer=answer,
            phrases=emitted,
            lang=lang,
            found=found,
            sources=sources,
        )
        yield "done", _done(resp)
        await self._log(
            endpoint="ask",
            request_id=request_id,
            session_id=req.session_id,
            question=req.question,
            place_id=req.place_id,
            resp=resp,
            provider=self.text_llm,
            started=started,
            model=llm_req.info.get("model"),
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
        lang_hint = meta.lang or (detect_lang(meta.question) if meta.question else None)
        resp = self._finalize(res, lang_hint, question, LOOK_REFUSALS, kb.source_ids(), request_id)
        await self._log(
            endpoint="look",
            request_id=request_id,
            session_id=meta.session_id,
            question=question,
            place_id=meta.place_id,
            resp=resp,
            provider=self.vision_llm,
            started=started,
            model=res.model,
        )
        return resp


def _done(resp: AnswerResponse) -> dict:
    return {
        "request_id": resp.request_id,
        "lang": resp.lang,
        "found": resp.found,
        "sources": resp.sources,
    }
