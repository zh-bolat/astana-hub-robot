"""WS /v1/live: подключение, лимиты, пайплайн Pipecat на одну сессию (docs/api-contract.md, «WS /v1/live»).

Пайплайн (один PipelineWorker на WebSocket):

    transport.input (LiveSerializer: PCM 16 кГц, JSON-команды)
      → EchoGate (без AEC глушит эхо ответа) → VADProcessor (Silero) → STT (ElevenLabs realtime)
      → LiveConversation (конец реплики smart-turn, перебивание, агент /v1/chat, события)
      → TTS (ElevenLabs: ru/en WebSocket flash, kk HTTP v3) → transport.output (PCM 24 кГц, JSON)
      → PlayoutTracker (часы воспроизведения на роботе)

Подключение: токен робота в заголовке Authorization (без него — закрытие 1008 до accept), не больше
LIVE_MAX_SESSIONS сессий на токен (иначе error rate_limited + 1013), повторное подключение с тем же
session_id закрывает старое. LIVE_ENABLED=false или нет ключей — error speech_unavailable + 1011 (робот
уходит в запасной режим /v1/chat + /v1/stt + /v1/tts). Простой (ни речи, ни звука ответа, ни команд робота)
дольше LIVE_IDLE_TIMEOUT_S — закрытие 1000 «idle timeout». Звук посетителя живёт только в памяти пайплайна.
"""

from __future__ import annotations

import contextlib
import logging
import time

from fastapi import APIRouter, Query, WebSocket, WebSocketDisconnect
from loguru import logger as pipecat_logger
from pipecat.frames.frames import (
    BotSpeakingFrame,
    ErrorFrame,
    InputTransportMessageFrame,
    InterimTranscriptionFrame,
    TranscriptionFrame,
    UserSpeakingFrame,
)
from pipecat.pipeline.pipeline import Pipeline
from pipecat.pipeline.worker import PipelineParams, PipelineWorker
from pipecat.processors.audio.vad_processor import VADProcessor
from pipecat.transports.websocket.fastapi import FastAPIWebsocketParams, FastAPIWebsocketTransport
from pipecat.workers.runner import WorkerRunner
from starlette.websockets import WebSocketState

from app.api import check_token
from app.errors import ApiError
from app.lang import Lang, normalize_lang
from app.live.conversation import LiveConversation
from app.live.playout import EchoGate, Playout, PlayoutTracker
from app.live.protocol import AUDIO_FORMAT, AUDIO_IN_RATE, AUDIO_OUT_RATE, LiveSerializer
from app.live.protocol import error_event as _error_event
from app.live.protocol import event as _event
from app.live.services import LiveServices
from app.logging_setup import logger, new_request_id, reset_request_id, set_request_id

router = APIRouter(prefix="/v1")

# Ошибок STT/TTS подряд, после которых сессия закрывается (робот уходит в запасной режим)
MAX_SERVICE_ERRORS = 3


def route_pipecat_logs() -> None:
    """Логи Pipecat (loguru) → наш JSON-лог, только WARNING и выше: в debug-логах Pipecat есть тексты реплик."""
    pipecat_logger.remove()

    def sink(message) -> None:
        record = message.record
        level = logging.ERROR if record["level"].no >= 40 else logging.WARNING
        logger.log(
            level, "pipecat", extra={"source": record["name"], "detail": record["message"][:300]}
        )

    pipecat_logger.add(sink, level="WARNING")


class LiveRegistry:
    """Активные live-сессии процесса: лимит на токен, поиск по session_id (озвучить ответ /v1/look)."""

    def __init__(self) -> None:
        self._sessions: dict[str, LiveSession] = {}

    def count(self, key: str) -> int:
        return sum(1 for s in self._sessions.values() if s.key == key)

    def get(self, session_id: str) -> LiveSession | None:
        return self._sessions.get(session_id)

    def add(self, session: LiveSession) -> None:
        self._sessions[session.session_id] = session

    def remove(self, session: LiveSession) -> None:
        if self._sessions.get(session.session_id) is session:
            del self._sessions[session.session_id]

    def __len__(self) -> int:
        return len(self._sessions)


class LiveSession:
    def __init__(self, ws: WebSocket, key: str, session_id: str, lang: Lang) -> None:
        self.ws = ws
        self.key = key
        self.session_id = session_id
        self.lang: Lang = lang
        self.app = ws.app
        self.settings = ws.app.state.settings
        self.ended_by: str | None = None
        self.conversation: LiveConversation | None = None
        self.worker: PipelineWorker | None = None
        self.service_errors = 0
        self.close_code = 1000
        self.close_reason = ""

    async def stop(
        self, ended_by: str, code: int = 1000, reason: str = "", error: dict | None = None
    ) -> None:
        """Закрыть сессию: error — событие роботу прямо в сокет (мимо очереди пайплайна), затем close."""
        if self.ended_by is None:
            self.ended_by, self.close_code, self.close_reason = ended_by, code, reason
            if error is not None:
                with contextlib.suppress(Exception):
                    await self.ws.send_json(error)
            if code != 1000 or reason:
                await self._close_ws()  # свой код закрытия — раньше, чем транспорт закроет с 1000
        if self.worker is not None:
            await self.worker.cancel(reason=ended_by)

    async def speak_look(self, text: str, lang: Lang | None) -> bool:
        """Ответ /v1/look в этой сессии озвучивается голосом live (робот сам его не говорит)."""
        if self.conversation is None or self.ended_by is not None:
            return False
        return await self.conversation.speak_text(text, lang)

    async def run(self, services: LiveServices) -> None:
        s = self.settings
        started = time.perf_counter()
        playout = Playout(sample_rate=AUDIO_OUT_RATE)
        gate = EchoGate(playout, threshold=s.live_echo_rms, tail_s=s.live_echo_tail_s)
        serializer = LiveSerializer()
        transport = FastAPIWebsocketTransport(
            self.ws,
            FastAPIWebsocketParams(
                audio_in_enabled=True,
                audio_in_sample_rate=AUDIO_IN_RATE,
                audio_out_enabled=True,
                audio_out_sample_rate=AUDIO_OUT_RATE,
                audio_out_end_silence_secs=0,
                serializer=serializer,
                allowed_origins=[],  # робот шлёт без Origin; доступ — по токену
            ),
        )
        conv = LiveConversation(
            chat=self.app.state.chat,
            settings=s,
            session_id=self.session_id,
            lang=self.lang,
            playout=playout,
            echo_gate=gate,
            metrics=self.app.state.metrics,
            turn_analyzer=services.turn_analyzer,
            switch_tts=services.switch_tts,
            set_stt_lang=services.set_stt_lang,
            provider=services.provider,
        )
        self.conversation = conv
        pipeline = Pipeline(
            [
                transport.input(),
                gate,
                VADProcessor(vad_analyzer=services.vad),
                services.stt,
                conv,
                services.tts,
                transport.output(),
                PlayoutTracker(playout),
            ]
        )
        worker = PipelineWorker(
            pipeline,
            params=PipelineParams(
                audio_in_sample_rate=AUDIO_IN_RATE, audio_out_sample_rate=AUDIO_OUT_RATE
            ),
            enable_rtvi=False,  # свой протокол кадров, RTVI-сообщения роботу не нужны
            enable_turn_tracking=False,
            check_dangling_tasks=False,
            idle_timeout_secs=s.live_idle_timeout_s,
            idle_timeout_frames=(
                UserSpeakingFrame,
                BotSpeakingFrame,
                InterimTranscriptionFrame,
                TranscriptionFrame,
                InputTransportMessageFrame,
            ),
            cancel_on_idle_timeout=False,
        )
        self.worker = worker

        @worker.event_handler("on_pipeline_started")
        async def _started(_worker, _frame) -> None:
            await conv.send_now(_event("ready", audio_format=AUDIO_FORMAT))
            await conv.send_now(_event("listening"))

        @worker.event_handler("on_idle_timeout")
        async def _idle(_worker) -> None:
            await self.stop("idle", 1000, "idle timeout")

        @worker.event_handler("on_pipeline_error")
        async def _error(_worker, frame: ErrorFrame) -> None:
            source = frame.processor
            unusable = frame.fatal or (source is not None and not source.is_usable)
            self.service_errors += 1
            logger.warning(
                "live_pipeline_error",
                extra={
                    "session_id": self.session_id,
                    "processor": type(source).__name__ if source else None,
                    "fatal": unusable,
                },
            )
            if unusable or self.service_errors >= MAX_SERVICE_ERRORS:
                self.app.state.metrics.record(
                    "live",
                    {"live_error": None},
                    lang=conv.lang,
                    provider=services.provider,
                    session_id=self.session_id,
                    ok=False,
                    extra={"processor": type(source).__name__ if source else None},
                    keep_null=("live_error",),
                )
                await self.stop(
                    "error",
                    1011,
                    "speech_unavailable",
                    _error_event("speech_unavailable", "Speech provider is unavailable"),
                )

        @transport.event_handler("on_client_disconnected")
        async def _gone(_transport, _ws) -> None:
            await self.stop("disconnect")

        runner = WorkerRunner(handle_sigint=False, handle_sigterm=False, check_dangling_tasks=False)
        try:
            await runner.add_workers(worker)
            await runner.run()
        finally:
            await conv.close(interrupted=True)
            if services.aclose is not None:
                with contextlib.suppress(Exception):
                    await services.aclose()
            await self._close_ws()
            logger.info(
                "live_session_closed",
                extra={
                    "session_id": self.session_id,
                    "ended_by": self.ended_by or "pipeline",
                    "session_s": round(time.perf_counter() - started, 1),
                    "turns": conv.stats.turns,
                    "interrupts": conv.stats.interrupts,
                    "ignored": conv.stats.ignored,
                    "errors": conv.stats.errors + self.service_errors,
                    "echo_muted_frames": gate.muted_frames,
                    "bad_frames": serializer.dropped,
                    "aec": gate.aec,
                },
            )
            self.app.state.metrics.record(
                "live",
                {"live_session_ms": int((time.perf_counter() - started) * 1000)},
                lang=conv.lang,
                provider=services.provider,
                session_id=self.session_id,
                extra={
                    "ended_by": self.ended_by or "pipeline",
                    "turns": conv.stats.turns,
                    "interrupts": conv.stats.interrupts,
                    "aec": gate.aec,
                },
            )

    async def _close_ws(self) -> None:
        if self.ws.client_state == WebSocketState.CONNECTED:
            with contextlib.suppress(Exception):
                await self.ws.close(self.close_code, self.close_reason or None)


async def _reject(ws: WebSocket, code: str, message: str, close_code: int) -> None:
    """До пайплайна: accept → error → close (робот видит причину и уходит в запасной режим)."""
    try:
        await ws.accept()
        await ws.send_json(_error_event(code, message))
        await ws.close(close_code)
    except (WebSocketDisconnect, RuntimeError):
        pass


@router.websocket("/live")
async def live(
    ws: WebSocket,
    session_id: str = Query(min_length=1, max_length=64),
    lang: str | None = Query(default=None),
) -> None:
    """Живой голосовой режим. Протокол кадров — docs/api-contract.md, «WS /v1/live»."""
    app = ws.app
    settings = app.state.settings
    try:
        key = check_token(ws)  # type: ignore[arg-type]  # нужны только headers и app
    except ApiError:
        await ws.close(1008, "unauthorized")
        return
    hint = normalize_lang(lang)
    if lang and hint is None:
        await _reject(ws, "bad_request", "lang must be ru, kk or en", 1008)
        return
    factory = app.state.live_services
    if not settings.resolved_live_enabled() or factory is None:
        await _reject(ws, "speech_unavailable", "Live mode is disabled on the server", 1011)
        return
    registry: LiveRegistry = app.state.live_registry
    previous = registry.get(session_id)
    if (
        previous is not None and previous.key == key
    ):  # робот переподключился: старое соединение — закрыть
        registry.remove(previous)
        await previous.stop("replaced")
    if registry.count(key) >= settings.live_max_sessions:
        await _reject(
            ws, "rate_limited", f"Too many live sessions (max {settings.live_max_sessions})", 1013
        )
        return
    session = LiveSession(ws, key, session_id, hint or "ru")
    registry.add(session)
    token = set_request_id(new_request_id())
    try:
        try:
            services = factory(settings, session.lang)
        except Exception:
            logger.exception("live_services_failed")
            await _reject(ws, "speech_unavailable", "Speech provider is unavailable", 1011)
            return
        await ws.accept()
        logger.info(
            "live_session_opened",
            extra={"session_id": session_id, "lang": session.lang, "active": len(registry)},
        )
        await session.run(services)
    except WebSocketDisconnect:
        pass
    finally:
        registry.remove(session)
        reset_request_id(token)
