"""Ход агентского чата /v1/chat → поток SSE-событий (docs/api-contract.md, «POST /v1/chat»).

Порядок событий: turn → emotion(thinking) → [emotion ответа] → phrase… → action… → done | error.
`action look` уходит сразу (агент ждёт кадр), остальные действия — после фраз, перед done.

Как идёт ход:
1. История сессии (ChatMemory) + контекст робота + реплика в разделителях → Runner.run_streamed.
2. Текст модели собирается в фразы по мере стрима (PhraseAssembler), каждая фраза — через
   clean_for_tts, выходной фильтр safety (blocked_reason) и лимит 4 фразы / 600 символов.
3. Эмоция ответа — метка в начале ответа модели ([happy] …), см. app/agent/phrases.py: выбор
   эмоции ничего не стоит по задержке. Нет метки — neutral перед первой фразой.

Надёжность (решение — в README):
- Модель упала/вернула ошибку до первого события роботу → следующая модель из AGENT_FALLBACK_MODELS.
- Ни одна модель не ответила, или нет ни фразы, ни действия за AGENT_FIRST_PHRASE_TIMEOUT_S →
  деградация внутри того же потока на логику /v1/ask (AnswerService.ask_stream: база + LLM
  /ask-провайдера), для visitor_arrived — готовое приветствие. `error` — только если и запасной
  путь не смог ответить.
- Ошибка/таймаут после того, как фразы уже ушли роботу → ход закрывается `done` с тем, что сказано
  (отложенные действия не отправляются).
- Клиент оборвал поток → агент останавливается (result.cancel + отмена задачи), ход пишется с
  interrupted=true (если хоть одна фраза ушла роботу).
"""

from __future__ import annotations

import asyncio
import contextlib
import json
import secrets
import time
from collections.abc import AsyncIterator
from contextlib import aclosing
from dataclasses import dataclass, field

from agents import Agent, RunConfig, Runner
from openai.types.responses import ResponseCompletedEvent, ResponseTextDeltaEvent

from app.agent.context import FrameBroker, TurnContext
from app.agent.memory import ChatMemory, HistoryMessage
from app.agent.models import AgentModel
from app.agent.phrases import EMOTIONS, UNKNOWN_TAG, PhraseAssembler, strip_tags
from app.agent.prompts import ARRIVED_HISTORY, agent_instructions, turn_message
from app.agent.tools import ALL_TOOLS
from app.config import Settings
from app.errors import error_body
from app.knowledge.models import KnowledgeBase
from app.knowledge.source import KnowledgeStore
from app.lang import Lang, resolve_lang
from app.llm.base import LLMError, LLMProvider, LLMRequest
from app.log_writer import BackgroundLogWriter
from app.logging_setup import logger
from app.prompts import look_system_prompt, look_user_prompt, wrap_visitor
from app.safety import BLOCKED_ANSWERS, blocked_reason, kb_corpus, limit_phrases
from app.schemas import AskRequest, ChatRequest
from app.service import DEFAULT_LOOK_QUESTION, AnswerService
from app.text import clean_for_tts, split_phrases

GREETINGS: dict[str, str] = {
    "ru": "Здравствуйте! Я робот-помощник Astana Hub. Чем могу помочь?",
    "kk": "Сәлеметсіз бе! Мен Astana Hub роботы-көмекшісімін. Қалай көмектесейін?",
    "en": "Hello! I'm the Astana Hub robot assistant. How can I help you?",
}
LOOK_WAIT: dict[str, str] = {
    "ru": "Секунду, сейчас посмотрю.",
    "kk": "Бір сәт, қазір қараймын.",
    "en": "One moment, let me take a look.",
}


class _AgentFailure(Exception):
    def __init__(self, kind: str, detail: str = "") -> None:
        super().__init__(f"{kind}: {detail}" if detail else kind)
        self.kind = kind  # timeout | error


@dataclass
class _Turn:
    req: ChatRequest
    request_id: str
    turn_id: str
    lang: Lang
    started: float
    deadline: float = 0.0
    prev_assistant: str | None = None
    emitted: list[str] = field(default_factory=list)
    actions_sent: list[dict] = field(default_factory=list)
    emotion: str | None = None  # эмоция ответа, уже отправленная роботу
    visible: bool = False  # роботу ушла фраза или action — повторять ход другой моделью нельзя
    unknown: bool = False
    blocked: bool = False
    stop: bool = False  # лимит длины/фильтр: модель дальше не слушаем
    ctx: TurnContext | None = None
    provider: str = "none"
    finished: bool = False
    errored: bool = False
    recorded: bool = False
    fallback_logged: bool = False  # запасной путь /ask сам записал question_log
    fallback_found: bool | None = None

    @property
    def text(self) -> str:
        return self.req.text

    @property
    def arrived(self) -> bool:
        return self.req.event == "visitor_arrived" and not self.req.text


class ChatService:
    def __init__(
        self,
        settings: Settings,
        store: KnowledgeStore,
        answers: AnswerService,
        vision_llm: LLMProvider,
        memory: ChatMemory,
        broker: FrameBroker,
        log_writer: BackgroundLogWriter,
        models: list[AgentModel],
    ) -> None:
        self.settings = settings
        self.store = store
        self.answers = answers
        self.vision_llm = vision_llm
        self.memory = memory
        self.broker = broker
        self.log_writer = log_writer
        self.models = models
        instructions = agent_instructions(settings.answer_mode)
        self._agents = [
            Agent(
                name="astana_hub_robot",
                instructions=instructions,
                tools=list(ALL_TOOLS),
                model=m.model,
                model_settings=m.settings,
            )
            for m in models
        ]

    @property
    def enabled(self) -> bool:
        return self.settings.agent_enabled and bool(self.models)

    # ---------- публичный поток ----------

    async def turn(self, req: ChatRequest, request_id: str) -> AsyncIterator[tuple[str, dict]]:
        text = req.text
        lang: Lang = resolve_lang(req.lang, None, text) if text else (req.lang or "ru")
        t = _Turn(
            req=req,
            request_id=request_id,
            turn_id=f"trn_{secrets.token_hex(10)}",
            lang=lang,
            started=time.perf_counter(),
        )
        yield "turn", {"turn_id": t.turn_id, "request_id": request_id}
        yield "emotion", {"name": "thinking"}
        try:
            async with aclosing(self._turn(t)) as events:
                async for event, data in events:
                    if event in ("done", "error"):
                        t.finished = True
                        t.errored = event == "error"
                    yield event, data
        except Exception:
            logger.exception("chat_turn_failed")
            if not t.finished:
                t.finished = t.errored = True
                yield "error", error_body("internal_error", "Internal server error")
        finally:
            self.broker.close(t.turn_id)
            self._record(t, interrupted=not t.finished)

    async def _turn(self, t: _Turn) -> AsyncIterator[tuple[str, dict]]:
        kb = self.store.get()
        history = await self.memory.load(t.req.session_id)
        t.prev_assistant = next(
            (m.content for m in reversed(history) if m.role == "assistant" and m.content), None
        )
        if not self.enabled:
            async for ev in self._fallback(t):
                yield ev
            return

        loop = asyncio.get_running_loop()
        t.deadline = loop.time() + self.settings.agent_turn_timeout_s
        first_deadline = loop.time() + self.settings.agent_first_phrase_timeout_s
        items = self._input(history, t, kb)
        corpus = kb_corpus(kb)

        for agent, model in zip(self._agents, self.models, strict=True):
            queue: asyncio.Queue = asyncio.Queue()
            ctx = self._context(t, kb, queue)
            t.ctx = ctx
            t.provider = model.label
            try:
                async for ev in self._run(agent, ctx, items, t, queue, first_deadline, corpus):
                    yield ev
            except _AgentFailure as exc:
                logger.warning(
                    "agent_failed",
                    extra={"model": model.label, "reason": str(exc)[:300], "visible": t.visible},
                )
                if t.visible:
                    break  # роботу уже что-то ушло — закрываем ход тем, что есть
                if exc.kind == "timeout":
                    break  # времени на другую модель нет → запасной путь
                continue
            for action in ctx.actions:
                t.actions_sent.append(action)
                yield "action", action
            yield "done", self._done(t)
            return

        if t.emitted:
            yield "done", self._done(t)
            return
        if t.visible:  # ушёл только look, фраз нет — коротко извиниться
            for phrase in split_phrases(BLOCKED_ANSWERS[t.lang] if t.blocked else _sorry(t.lang)):
                t.emitted.append(phrase)
                yield "phrase", {"text": phrase}
            yield "done", self._done(t)
            return
        t.ctx = None
        async for ev in self._fallback(t):
            yield ev

    # ---------- агент ----------

    def _input(self, history: list[HistoryMessage], t: _Turn, kb: KnowledgeBase) -> list:
        items: list = []
        for m in history:
            if m.role == "visitor":
                if (m.tool_args or {}).get("event") == "visitor_arrived" and not m.content:
                    items.append({"role": "user", "content": ARRIVED_HISTORY})
                else:
                    items.append({"role": "user", "content": wrap_visitor(m.content)})
            elif m.role == "assistant" and m.content:
                items.append({"role": "assistant", "content": m.content})
        state = t.req.robot_state
        place_id = state.nearest_place_id if state else None
        items.append(
            {
                "role": "user",
                "content": turn_message(
                    t.text,
                    t.req.lang if t.arrived else t.lang,
                    kb.place(place_id),
                    place_id,
                    bool(state and state.navigating),
                    event=t.req.event,
                ),
            }
        )
        return items

    def _context(self, t: _Turn, kb: KnowledgeBase, queue: asyncio.Queue) -> TurnContext:
        async def emit(kind: str, data: dict) -> None:
            queue.put_nowait((kind, data))

        async def look(ctx: TurnContext, _question: str | None) -> str:
            return await self._look(t, ctx, emit)

        state = t.req.robot_state
        return TurnContext(
            kb=kb,
            session_id=t.req.session_id,
            turn_id=t.turn_id,
            text=t.text,
            lang=t.lang,
            nearest_place_id=state.nearest_place_id if state else None,
            navigating=bool(state and state.navigating),
            prev_assistant=t.prev_assistant,
            max_tool_calls=self.settings.agent_max_tool_calls,
            emit=emit,
            look=look,
        )

    async def _run(
        self,
        agent: Agent,
        ctx: TurnContext,
        items: list,
        t: _Turn,
        queue: asyncio.Queue,
        first_deadline: float,
        corpus: str,
    ) -> AsyncIterator[tuple[str, dict]]:
        result = Runner.run_streamed(
            agent,
            items,
            context=ctx,
            max_turns=self.settings.agent_max_tool_calls + 2,
            run_config=RunConfig(tracing_disabled=True, trace_include_sensitive_data=False),
        )

        async def pump() -> None:
            try:
                async for ev in result.stream_events():
                    if ev.type != "raw_response_event":
                        continue
                    if isinstance(ev.data, ResponseTextDeltaEvent):
                        queue.put_nowait(("text", ev.data.delta))
                    elif isinstance(ev.data, ResponseCompletedEvent):
                        queue.put_nowait(("flush", None))
                queue.put_nowait(("end", None))
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                queue.put_nowait(("fail", exc))

        loop = asyncio.get_running_loop()
        task = asyncio.create_task(pump())
        asm = PhraseAssembler()
        try:
            while not t.stop:
                limit = t.deadline if t.visible else min(first_deadline, t.deadline)
                try:
                    kind, payload = await asyncio.wait_for(
                        queue.get(), timeout=max(0.01, limit - loop.time())
                    )
                except TimeoutError as exc:
                    raise _AgentFailure("timeout") from exc
                if kind == "fail":
                    raise _AgentFailure("error", f"{type(payload).__name__}: {payload}")
                if kind == "action":
                    t.visible = True
                    t.actions_sent.append(payload)
                    yield "action", payload
                    continue
                if kind == "text":
                    tags, sentences = asm.feed(payload)
                elif kind == "say":
                    tags, sentences = [], [payload["text"]]
                else:  # flush | end
                    tags, sentences = asm.flush()
                for ev in self._admit(t, ctx, tags, sentences, corpus):
                    yield ev
                if kind == "end":
                    break
        finally:
            result.cancel()
            task.cancel()
            with contextlib.suppress(asyncio.CancelledError, Exception):
                await task

    def _admit(
        self, t: _Turn, ctx: TurnContext, tags: list[str], sentences: list[str], corpus: str
    ) -> list[tuple[str, dict]]:
        """Метки → emotion; предложения → фразы через фильтр и лимит длины."""
        out: list[tuple[str, dict]] = []
        for tag in tags:
            if tag == UNKNOWN_TAG:
                t.unknown = True
            elif tag in EMOTIONS and tag != "thinking" and tag != t.emotion:
                t.emotion = tag
                out.append(("emotion", {"name": tag}))
        for phrase in (p for s in sentences for p in split_phrases(clean_for_tts(strip_tags(s)))):
            if t.stop:
                break
            if blocked_reason(" ".join([*t.emitted, phrase]), t.text, corpus):
                logger.warning("answer_blocked", extra={"endpoint": "chat"})
                t.blocked = t.stop = True
                ctx.actions.clear()
                if t.emotion != "neutral":
                    t.emotion = "neutral"
                    out.append(("emotion", {"name": "neutral"}))
                for p in limit_phrases([*t.emitted, *split_phrases(BLOCKED_ANSWERS[t.lang])])[
                    len(t.emitted) :
                ]:
                    t.emitted.append(p)
                    out.append(("phrase", {"text": p}))
                break
            if len(limit_phrases([*t.emitted, phrase])) <= len(t.emitted):
                t.stop = True  # 4 фразы / 600 символов — дальше не слушаем
                break
            if t.emotion is None:
                t.emotion = "neutral"
                out.append(("emotion", {"name": "neutral"}))
            t.emitted.append(phrase)
            t.visible = True
            out.append(("phrase", {"text": phrase}))
        return out

    async def _look(self, t: _Turn, ctx: TurnContext, emit) -> str:
        """Тул look: action look роботу → кадр (≤ AGENT_LOOK_FRAME_TIMEOUT_S) → vision → описание."""
        if not t.emitted:
            await emit("say", {"text": LOOK_WAIT[t.lang]})
        t.deadline += self.settings.agent_look_extra_s
        frame = self.broker.open(t.turn_id)
        await emit("action", {"name": "look", "args": {}})
        try:
            image = await asyncio.wait_for(frame, self.settings.agent_look_frame_timeout_s)
        except TimeoutError:
            logger.info("chat_look_no_frame", extra={"turn_id": t.turn_id})
            return json.dumps(
                {
                    "ok": False,
                    "error": "no_frame",
                    "note": "Кадр с камеры не пришёл. Скажи, что не получилось посмотреть, "
                    "и предложи попробовать ещё раз.",
                },
                ensure_ascii=False,
            )
        finally:
            self.broker.close(t.turn_id)

        kb = ctx.kb
        place = kb.place(ctx.nearest_place_id)
        question = t.text or DEFAULT_LOOK_QUESTION[t.lang]
        req = LLMRequest(
            system=look_system_prompt(kb),
            user=look_user_prompt(question, t.lang, place, ctx.nearest_place_id),
            question=question,
            lang=t.lang,
            place_id=ctx.nearest_place_id,
            kb=kb,
            image=image,
            deadline_s=self.settings.look_timeout_s,
        )
        del image  # кадр живёт только в запросе к vision-модели
        try:
            res = await asyncio.wait_for(
                self.vision_llm.describe_image(req), self.settings.look_timeout_s + 0.5
            )
        except (TimeoutError, LLMError) as exc:
            logger.error("chat_look_vision_failed", extra={"error": str(exc)[:300]})
            return json.dumps(
                {
                    "ok": False,
                    "error": "vision_unavailable",
                    "note": "Не получилось разобрать кадр.",
                },
                ensure_ascii=False,
            )
        finally:
            req.image = None
        answer = clean_for_tts(res.answer or "")
        if not res.found or not answer:
            return json.dumps(
                {"ok": False, "note": "На кадре не удалось ничего разобрать (темно, размыто)."},
                ensure_ascii=False,
            )
        for s in res.sources:
            if s in kb.source_ids():
                ctx.add_source(s)
        return json.dumps({"ok": True, "description": answer}, ensure_ascii=False)

    # ---------- запасной путь ----------

    async def _fallback(self, t: _Turn) -> AsyncIterator[tuple[str, dict]]:
        if t.arrived:
            t.provider = "local:greeting"
            if t.emotion != "happy":
                t.emotion = "happy"
                yield "emotion", {"name": "happy"}
            for phrase in split_phrases(GREETINGS[t.lang]):
                t.emitted.append(phrase)
                yield "phrase", {"text": phrase}
            yield "done", self._done(t)
            return
        state = t.req.robot_state
        ask = AskRequest(
            question=t.text,
            lang=t.req.lang,
            session_id=t.req.session_id,
            place_id=state.nearest_place_id if state else None,
        )
        t.provider = "fallback:ask"
        t.fallback_logged = True  # ask_stream сам пишет question_log (endpoint=ask)
        async with aclosing(self.answers.ask_stream(ask, t.request_id)) as events:
            async for event, data in events:
                if event == "phrase":
                    if t.emotion is None:
                        t.emotion = "neutral"
                        yield "emotion", {"name": "neutral"}
                    t.emitted.append(data["text"])
                    yield "phrase", data
                elif event == "done":
                    t.fallback_found = data.get("found")
                    t.lang = data.get("lang", t.lang)
                    yield "done", self._done(t)
                    return
                elif event == "error":
                    yield "error", data
                    return

    # ---------- завершение хода ----------

    def _done(self, t: _Turn) -> dict:
        tools = list(dict.fromkeys(t.ctx.tools_used)) if t.ctx else []
        return {"request_id": t.request_id, "lang": t.lang, "tools": tools}

    def _category(self, t: _Turn) -> str:
        if t.blocked:
            return "blocked"
        if t.fallback_found is not None:
            return "kb" if t.fallback_found else "out_of_scope"
        if t.unknown or not t.emitted:
            return "out_of_scope"
        ctx = t.ctx
        if ctx and (ctx.knowledge_used or ctx.looked or ctx.actions):
            return "kb"
        return "small_talk"

    def _record(self, t: _Turn, interrupted: bool) -> None:
        """Ход → память (messages) и лог вопросов (endpoint=chat). Ничего не сказано — не пишем."""
        if t.recorded or not t.emitted:
            return
        t.recorded = True
        answer = " ".join(t.emitted)
        ctx = t.ctx
        category = self._category(t)
        latency_ms = int((time.perf_counter() - t.started) * 1000)
        visitor_meta = {k: v for k, v in (("event", t.req.event), ("input", t.req.input)) if v}
        entries = [HistoryMessage("visitor", t.text, None, visitor_meta or None)]
        for item in ctx.tool_log if ctx else []:
            entries.append(HistoryMessage("tool", item["result"], item["name"], item["args"]))
        entries.append(
            HistoryMessage(
                "assistant",
                answer,
                None,
                {
                    "request_id": t.request_id,
                    "turn_id": t.turn_id,
                    "actions": t.actions_sent,
                    "emotion": t.emotion,
                    "category": category,
                    "provider": t.provider,
                    "interrupted": interrupted,
                },
            )
        )
        self.memory.record(t.req.session_id, t.lang, entries)
        logger.info(
            "chat_interrupted" if interrupted else "chat_answered",
            extra={
                "session_id": t.req.session_id,
                "question": t.text,
                "event": t.req.event,
                "lang": t.lang,
                "category": category,
                "tools": ctx.tools_used if ctx else [],
                "actions": [a["name"] for a in t.actions_sent],
                "provider": t.provider,
                "latency_ms": latency_ms,
                "interrupted": interrupted,
            },
        )
        if t.fallback_logged or t.arrived:
            return  # /ask-путь уже записал вопрос; приветствие — не вопрос
        state = t.req.robot_state
        self.log_writer.submit(
            request_id=t.request_id,
            session_id=t.req.session_id,
            endpoint="chat",
            question=t.text,
            lang=t.lang,
            place_id=state.nearest_place_id if state else None,
            found=category in ("kb", "small_talk"),
            category=category,
            answer=answer,
            sources=ctx.sources if ctx else [],
            provider=t.provider[:64],
            latency_ms=latency_ms,
            interrupted=interrupted,
        )


def _sorry(lang: str) -> str:
    return {
        "ru": "Извините, не получилось ответить. Спросите ещё раз, пожалуйста.",
        "kk": "Кешіріңіз, жауап бере алмадым. Қайта сұраңызшы.",
        "en": "Sorry, I couldn't answer that. Please ask again.",
    }[lang]
