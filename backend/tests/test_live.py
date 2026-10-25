"""WS /v1/live (docs/api-contract.md, «WS /v1/live»): настоящий пайплайн Pipecat и настоящий агент /v1/chat
(модель — FakeModel через подменённый транспорт OpenRouter), фейковые VAD / STT / TTS без сети.

Сервер поднимается uvicorn в потоке, клиент — websockets с таймаутами (тест не может зависнуть).
"""

from __future__ import annotations

import asyncio
import contextlib
import json
import math
import socket
import struct
import threading
import time
from collections.abc import AsyncIterator
from contextlib import aclosing

import pytest
import uvicorn
from pipecat.audio.vad.vad_analyzer import VADAnalyzer, VADParams
from pipecat.frames.frames import (
    Frame,
    InputAudioRawFrame,
    InputTransportMessageFrame,
    InterimTranscriptionFrame,
    ManuallySwitchServiceFrame,
    OutputAudioRawFrame,
    OutputTransportMessageFrame,
    OutputTransportMessageUrgentFrame,
    TranscriptionFrame,
    TTSAudioRawFrame,
    VADUserStartedSpeakingFrame,
    VADUserStoppedSpeakingFrame,
)
from pipecat.processors.frame_processor import FrameDirection, FrameProcessor
from pipecat.services.settings import TTSSettings
from pipecat.services.tts_service import TTSService
from pydantic import SecretStr
from sqlalchemy import text
from websockets.asyncio.client import connect
from websockets.exceptions import ConnectionClosed, InvalidStatus
from websockets.frames import Close

from app.agent.service import LiveTurn
from app.config import Settings
from app.live.conversation import (
    DIRECTIONS_TTL_S,
    LiveConversation,
    cut_words,
    is_echo,
    parse_directions,
)
from app.live.playout import EchoGate, Playout, rms
from app.live.protocol import AUDIO_FORMAT, LiveSerializer
from app.live.services import LiveServices, SegmentedProviderSTT, build_live_services
from app.llm.fake import FakeProvider
from app.main import create_app
from app.metrics import NULL_RECORDER
from app.schemas import ChatRequest
from app.speech.base import SttResult
from tests.conftest import AUTH, JPEG, SESSION, TOKEN
from tests.test_chat import FakeModel, call, messages, say

FRAME_MS = 40
FRAME_BYTES = 16_000 * 2 * FRAME_MS // 1000
LONG = (
    "[happy] Кафе на первом этаже, справа от входа, там отличный кофе и выпечка. "
    "Работает с восьми утра до восьми вечера без выходных и перерывов на обед. "
    "Ещё рядом есть зона отдыха с диванами, где можно спокойно поработать."
)


# ---------- фейковые сервисы ----------
class FakeVAD(VADAnalyzer):
    """Речь — громкий кадр (пик выше 3000)."""

    def __init__(self) -> None:
        super().__init__(
            sample_rate=16_000,
            params=VADParams(confidence=0.5, start_secs=0.08, stop_secs=0.16, min_volume=0.0),
        )

    def num_frames_required(self) -> int:
        return 512

    def voice_confidence(self, buffer: bytes) -> float:
        samples = struct.unpack(f"<{len(buffer) // 2}h", buffer)
        return 1.0 if max(abs(s) for s in samples) > 3000 else 0.0


class FakeSTT(FrameProcessor):
    """По сценарию: на каждую фразу посетителя — промежуточный текст после 3 кадров речи и финал на паузе."""

    def __init__(self, script: list[str]) -> None:
        super().__init__()
        self.script = script
        self.current = ""
        self.frames = 0
        self.speaking = False

    async def process_frame(self, frame: Frame, direction: FrameDirection) -> None:
        await super().process_frame(frame, direction)
        await self.push_frame(frame, direction)
        if direction != FrameDirection.DOWNSTREAM:
            return
        if isinstance(frame, VADUserStartedSpeakingFrame):
            self.speaking, self.frames = True, 0
            self.current = self.script.pop(0) if self.script else ""
        elif isinstance(frame, InputAudioRawFrame) and self.speaking:
            self.frames += 1
            if self.frames == 3 and self.current:
                partial = " ".join(self.current.split()[:2])
                await self.push_frame(InterimTranscriptionFrame(partial, "user", "now"))
        elif isinstance(frame, VADUserStoppedSpeakingFrame):
            self.speaking = False
            if self.current:
                await self.push_frame(TranscriptionFrame(self.current, "user", "now"))


class FakeTTS(TTSService):
    """PCM 24 кГц: 15 мс звука на символ."""

    def __init__(self) -> None:
        super().__init__(
            sample_rate=24_000,
            push_start_frame=True,
            push_stop_frames=True,
            stop_frame_timeout_s=0.2,
            settings=TTSSettings(model=None, voice=None, language=None),
        )
        self.texts: list[str] = []

    def can_generate_metrics(self) -> bool:
        return False

    async def run_tts(self, text: str, context_id: str) -> AsyncIterator[Frame | None]:
        self.texts.append(text)
        total = int(24_000 * 0.015 * len(text)) * 2
        chunk = 24_000 * 2 // 10  # 100 мс
        for i in range(0, total, chunk):
            yield TTSAudioRawFrame(
                b"\x01\x00" * (min(chunk, total - i) // 2), 24_000, 1, context_id
            )


class ScriptSttProvider:
    """SttProvider для STT по отрезкам: на каждый отрезок — следующая фраза сценария через `delay` с."""

    name = "script"

    def __init__(self, script: list[str], delay: float) -> None:
        self.script = script
        self.delay = delay
        self.calls: list[tuple[int, str | None]] = []  # (байт PCM, язык)

    async def stt(self, pcm: bytes, sample_rate: int, lang):
        self.calls.append((len(pcm), lang))
        await asyncio.sleep(self.delay)
        text = self.script.pop(0) if self.script else ""
        return SttResult(text=text, lang=lang or "ru", confidence=None)


class Fakes:
    def __init__(self, script: list[str], segmented_delay: float | None = None) -> None:
        self.script = script
        self.segmented_delay = segmented_delay
        self.stt_provider: ScriptSttProvider | None = None
        self.tts: list[FakeTTS] = []
        self.voices: list[str] = []  # switch_tts: голос (язык) каждой смены
        self.synth: list[tuple[str, str]] = []  # synth_pcm: (текст, язык) — прогрев и кеш шаблонов
        self.warmed: list[str] = []

    async def synth_pcm(self, text: str, lang: str) -> bytes:
        self.synth.append((text, lang))
        return b"\x02\x00" * 24_000  # 1 с

    async def warmup(self, lang: str) -> None:
        self.warmed.append(lang)

    def switch_tts(self, lang: str) -> None:
        self.voices.append(lang)
        return None  # один FakeTTS на все языки: кадр переключения не нужен

    @staticmethod
    def tts_text(text: str, lang: str) -> str:
        return f"[cheerfully] {text}" if lang == "kk" else text  # как тег v3 у настоящих сервисов

    def __call__(self, settings: Settings, lang: str) -> LiveServices:
        tts = FakeTTS()
        self.tts.append(tts)
        extras = {
            "switch_tts": self.switch_tts,
            "synth_pcm": self.synth_pcm,
            "tts_signature": lambda code: f"fake-{code}",
            "warmup": self.warmup,
            "tts_text": self.tts_text,
        }
        if self.segmented_delay is not None:
            self.stt_provider = ScriptSttProvider(self.script, self.segmented_delay)
            stt = SegmentedProviderSTT(self.stt_provider, lang, sample_rate=16_000, timeout_s=3.0)
            return LiveServices(
                vad=FakeVAD(), stt=stt, tts=tts, provider="fake", stt_segmented=True, **extras
            )
        return LiveServices(
            vad=FakeVAD(), stt=FakeSTT(self.script), tts=tts, provider="fake", **extras
        )


# ---------- сервер и клиент ----------
def _free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


@pytest.fixture
def live_server(settings):
    servers = []

    def _start(
        model: FakeModel | None = None,
        script: list[str] | None = None,
        segmented_delay: float | None = None,
        **overrides,
    ):
        fakes = Fakes(script if script is not None else [], segmented_delay)
        s = settings.model_copy(
            update={
                "live_enabled": True,
                "live_turn_silence_s": 0.15,
                "live_stt_final_timeout_s": 0.6,
                "live_barge_in_min_ms": 150,
                **overrides,
            }
        )
        app = create_app(
            s,
            FakeProvider(),
            FakeProvider(),
            chat_client=(model or FakeModel()).client(),
            live_services=fakes,
        )
        port = _free_port()
        server = uvicorn.Server(
            uvicorn.Config(app, host="127.0.0.1", port=port, log_level="warning", lifespan="off")
        )
        thread = threading.Thread(target=server.run, daemon=True)
        thread.start()
        deadline = time.time() + 10
        while not server.started and time.time() < deadline:
            time.sleep(0.02)
        servers.append((server, thread, app))
        return f"ws://127.0.0.1:{port}", app, fakes

    yield _start
    for server, thread, app in servers:
        server.should_exit = True
        thread.join(10)
        app.state.log_writer.wait_idle(5)
        app.state.engine.dispose()


def _tone(amplitude: int) -> bytes:
    n = FRAME_BYTES // 2
    return struct.pack(
        f"<{n}h", *(int(amplitude * math.sin(2 * math.pi * 440 * i / 16_000)) for i in range(n))
    )


LOUD, QUIET = _tone(12_000), b"\x00" * FRAME_BYTES


class LiveClient:
    """Микрофон робота (непрерывный поток кадров) + приём событий и звука."""

    def __init__(self, ws) -> None:
        self.ws = ws
        self.items: list[dict | int] = []  # JSON-событие или длина бинарного кадра
        self.loud_until = 0.0
        self._new = asyncio.Event()
        self.closed: ConnectionClosed | None = None

    async def mic(self) -> None:
        with contextlib.suppress(ConnectionClosed):
            while True:
                await self.ws.send(LOUD if time.monotonic() < self.loud_until else QUIET)
                await asyncio.sleep(FRAME_MS / 1000)

    async def reader(self) -> None:
        try:
            async for msg in self.ws:
                self.items.append(len(msg) if isinstance(msg, bytes) else json.loads(msg))
                self._new.set()
        except ConnectionClosed as exc:
            self.closed = exc
        if self.closed is None:  # нормальное закрытие: итерация кончается без исключения
            code = self.ws.close_code
            rcvd = Close(code, self.ws.close_reason or "") if code is not None else None
            self.closed = ConnectionClosed(rcvd, None)
        self._new.set()

    def speak(self, seconds: float) -> None:
        self.loud_until = time.monotonic() + seconds

    async def send(self, message: dict) -> None:
        await self.ws.send(json.dumps(message))

    def events(self, kind: str | None = None) -> list[dict]:
        return [
            i for i in self.items if isinstance(i, dict) and (kind is None or i["type"] == kind)
        ]

    def kinds(self) -> list[str]:
        out: list[str] = []
        for i in self.items:
            k = "audio" if isinstance(i, int) else i["type"]
            if not out or out[-1] != k:
                out.append(k)
        return out

    async def wait(self, kind: str, count: int = 1, timeout: float = 8.0) -> dict:
        deadline = time.monotonic() + timeout
        while True:
            found = self.events(kind)
            if len(found) >= count:
                return found[count - 1]
            if self.closed is not None:
                raise AssertionError(f"closed before {kind}: {self.kinds()}")
            left = deadline - time.monotonic()
            if left <= 0:
                raise AssertionError(f"no {kind} in {self.kinds()}")
            self._new.clear()
            with contextlib.suppress(TimeoutError):
                await asyncio.wait_for(self._new.wait(), left)

    async def wait_closed(self, timeout: float = 8.0) -> ConnectionClosed:
        deadline = time.monotonic() + timeout
        while self.closed is None and time.monotonic() < deadline:
            self._new.clear()
            with contextlib.suppress(TimeoutError):
                await asyncio.wait_for(self._new.wait(), deadline - time.monotonic())
        assert self.closed is not None, self.kinds()
        return self.closed


@contextlib.asynccontextmanager
async def session(url: str, session_id: str = SESSION, lang: str = "ru", hello: dict | None = None):
    async with connect(
        f"{url}/v1/live?session_id={session_id}&lang={lang}",
        additional_headers={"Authorization": f"Bearer {TOKEN}"},
        open_timeout=5,
    ) as ws:
        client = LiveClient(ws)
        tasks = [asyncio.create_task(client.reader()), asyncio.create_task(client.mic())]
        try:
            await client.wait("ready")
            await client.send(
                hello or {"type": "hello", "lang": lang, "place_id": "reception", "aec": True}
            )
            yield client
        finally:
            for t in tasks:
                t.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)


def run(coro) -> None:
    asyncio.run(asyncio.wait_for(coro, 40))


def live_rows(app) -> list[tuple[str, int | None, dict, bool]]:
    assert app.state.log_writer.wait_idle(5)
    with app.state.engine.connect() as conn:
        rows = conn.execute(
            text("SELECT stage, ms, extra, ok FROM latency_events WHERE kind = 'live' ORDER BY id")
        )
        return [(r.stage, r.ms, r.extra, r.ok) for r in rows]


# ---------- полный ход ----------
def test_full_turn_audio_to_answer(live_server):
    model = FakeModel(say("[happy] Кафе на первом этаже. Проводить вас?"))
    url, app, fakes = live_server(model, script=["Где здесь кафе?"], live_fast_places=False)

    async def scenario() -> None:
        async with session(url) as c:
            ready = c.events("ready")[0]
            assert ready["audio_format"] == AUDIO_FORMAT == "pcm_s16le_24000"
            c.speak(0.6)
            final = await c.wait("user_final")
            assert final == {"type": "user_final", "text": "Где здесь кафе?", "lang": "ru"}
            end = await c.wait("turn_end")
            start = c.events("turn_start")[0]
            assert end["turn_id"] == start["turn_id"] and end["lang"] == "ru" and end["found"]
            await c.send({"type": "playback_done", "turn_id": end["turn_id"]})
            await c.wait("listening", count=2)
            kinds = c.kinds()
            order = [
                "user_speaking",
                "user_partial",
                "user_final",
                "thinking",
                "turn_start",
                "emotion",
                "phrase",
                "audio",
                "phrase",
                "audio",
                "turn_end",
            ]
            assert [k for k in kinds if k in order] == order, kinds
            assert c.events("emotion")[0]["name"] == "happy"
            assert [p["text"] for p in c.events("phrase")] == [
                "Кафе на первом этаже.",
                "Проводить вас?",
            ]
            assert c.events("user_partial")[0]["text"] == "Где здесь"

    run(scenario())
    assert fakes.tts[0].texts == ["Кафе на первом этаже.", "Проводить вас?"]
    assert messages_of(app) == [
        ("visitor", "Где здесь кафе?", None),
        ("assistant", "Кафе на первом этаже. Проводить вас?", None),
    ]
    stages = {stage: (ms, extra) for stage, ms, extra, _ in live_rows(app)}
    for stage in ("live_stt_final_ms", "live_llm_first_token_ms", "live_first_audio_ms"):
        assert stage in stages and stages[stage][0] is not None and stages[stage][0] < 5000
    assert "live_session_ms" in stages
    dump = json.dumps([r[2] for r in live_rows(app)], ensure_ascii=False)
    assert "кафе" not in dump.lower() and "Где" not in dump  # тексты реплик в метрики не попадают
    with app.state.engine.connect() as conn:
        endpoint = conn.execute(text("SELECT endpoint FROM question_log")).scalar_one()
    assert endpoint == "live"


def messages_of(app) -> list[tuple[str, str, str | None]]:
    class _C:  # helper test_chat.messages ждёт TestClient
        pass

    c = _C()
    c.app = app  # type: ignore[attr-defined]
    return messages(c)


def test_visitor_arrived_greets(live_server):
    model = FakeModel(say("[excited] Здравствуйте! Что ищете?"))
    url, app, _ = live_server(model)

    async def scenario() -> None:
        async with session(url) as c:
            await c.send({"type": "visitor_arrived"})
            await c.wait("turn_end")
            assert not c.events("user_final")
            assert [p["text"] for p in c.events("phrase")] == ["Здравствуйте!", "Что ищете?"]
            assert c.events("emotion")[0]["name"] == "excited"
            assert any(isinstance(i, int) for i in c.items)

    run(scenario())
    assert len(model.requests) == 1
    with app.state.engine.connect() as conn:  # приветствие — не в журнал вопросов
        assert conn.execute(text("SELECT count(*) FROM question_log")).scalar_one() == 0


def test_noise_is_ignored_without_model(live_server):
    model = FakeModel(say("не должна вызываться"))
    url, _, _ = live_server(model, script=["Угу."])

    async def scenario() -> None:
        async with session(url) as c:
            c.speak(0.5)
            await c.wait("ignored")
            assert c.events("user_final")[0]["text"] == "Угу."
            assert not c.events("turn_start") and not c.events("thinking")

    run(scenario())
    assert model.requests == []


def test_barge_in_stops_audio_and_remembers_only_said(live_server):
    model = FakeModel(say(LONG), say("[happy] Кафе справа."))
    url, app, _ = live_server(model, script=["Расскажи про кафе", "Стоп, а где туалет?"])

    async def scenario() -> None:
        async with session(url) as c:
            c.speak(0.5)
            start = await c.wait("turn_start")
            await c.wait("phrase")
            for _ in range(100):  # звук пошёл
                if any(isinstance(i, int) for i in c.items):
                    break
                await asyncio.sleep(0.02)
            await asyncio.sleep(0.4)
            c.speak(0.6)
            interrupted = await c.wait("interrupted")
            assert interrupted["turn_id"] == start["turn_id"]
            idx = c.items.index(interrupted)
            await c.wait("user_final", count=2)
            await c.wait("turn_end")
            second = c.events("turn_start")[1]["turn_id"]
            await c.send({"type": "playback_done", "turn_id": second})
            await c.wait("listening", count=2)
            assert second != start["turn_id"]
            # после interrupted звук первого хода не приходит: до turn_start второго — не больше 200 мс звука
            later = c.items[idx : c.items.index(c.events("turn_start")[1])]
            assert sum(i for i in later if isinstance(i, int)) <= 24_000 * 2 * 0.2
            assert not [e for e in c.events("turn_end") if e["turn_id"] == start["turn_id"]]

    run(scenario())
    rows = messages_of(app)
    assert rows[0] == ("visitor", "Расскажи про кафе", None)
    said = rows[1][1]
    assert rows[1][0] == "assistant" and said and len(said) < len(LONG) - 30
    assert said.startswith("Кафе на первом этаже")
    assert rows[2] == ("visitor", "Стоп, а где туалет?", None)
    with app.state.engine.connect() as conn:
        flags = conn.execute(text("SELECT interrupted FROM question_log ORDER BY id")).scalars()
        assert list(flags) == [True, False]
    stages = [r[0] for r in live_rows(app)]
    assert "live_interrupt_ms" in stages
    ms = next(r[1] for r in live_rows(app) if r[0] == "live_interrupt_ms")
    assert 100 <= ms < 2000


# ---------- STT по отрезкам (LIVE_STT_MODE=segmented) ----------
def test_segmented_stt_full_turn_waits_for_slow_text(live_server):
    """Текст отрезка приходит через 0,8 с после паузы (дольше прежних 0,5 с ожидания) — реплика не теряется."""
    model = FakeModel(say("Лифт справа."))
    url, app, fakes = live_server(
        model, script=["Где лифт?"], segmented_delay=0.8, live_fast_places=False
    )

    async def scenario() -> None:
        async with session(url) as c:
            c.speak(0.6)
            final = await c.wait("user_final")
            assert final["text"] == "Где лифт?"
            end = await c.wait("turn_end")
            await c.send({"type": "playback_done", "turn_id": end["turn_id"]})
            await c.wait("listening", count=2)
            kinds = [k for k in c.kinds() if k in ("user_speaking", "user_final", "turn_start")]
            assert kinds == ["user_speaking", "user_final", "turn_start"], c.kinds()

    run(scenario())
    assert fakes.tts[0].texts == ["Лифт справа."]
    assert fakes.stt_provider is not None
    (pcm_bytes, lang), *_ = fakes.stt_provider.calls
    assert lang == "ru"
    # в STT ушла только речь (~0,6 с) с паузой и хвостом тишины — не весь поток микрофона
    assert 16_000 * 2 * 0.4 < pcm_bytes < 16_000 * 2 * 1.6
    stages = {stage: ms for stage, ms, _, _ in live_rows(app)}
    assert stages.get("live_stt_final_ms") is not None


def test_segmented_stt_barge_in_by_segment_text(live_server):
    model = FakeModel(say(LONG), say("Туалет слева."))
    url, app, _ = live_server(
        model, script=["Расскажи про кафе", "Стоп, а где туалет?"], segmented_delay=0.3
    )

    async def scenario() -> None:
        async with session(url) as c:
            c.speak(0.5)
            start = await c.wait("turn_start")
            await c.wait("phrase")
            await asyncio.sleep(0.4)
            c.speak(0.5)
            interrupted = await c.wait("interrupted")
            assert interrupted["turn_id"] == start["turn_id"]
            final = await c.wait("user_final", count=2)
            assert final["text"] == "Стоп, а где туалет?"
            end = await c.wait("turn_end")
            await c.send({"type": "playback_done", "turn_id": end["turn_id"]})
            await c.wait("listening", count=2)

    run(scenario())
    rows = messages_of(app)
    assert rows[2] == ("visitor", "Стоп, а где туалет?", None)


def test_segmented_stt_noise_during_answer_does_not_interrupt(live_server):
    model = FakeModel(say(LONG))
    url, _, _ = live_server(model, script=["Расскажи про кафе", "угу"], segmented_delay=0.3)

    async def scenario() -> None:
        async with session(url) as c:
            c.speak(0.5)
            await c.wait("phrase")
            await asyncio.sleep(0.3)
            c.speak(0.5)  # «угу»: текст отрезка — шум, перебиванием не считается
            await c.wait("turn_end", timeout=12)
            assert not c.events("interrupted")
            assert len(c.events("user_final")) == 1

    run(scenario())


def test_short_noise_during_answer_does_not_interrupt(live_server):
    model = FakeModel(say(LONG))
    url, _, _ = live_server(model, script=["Расскажи про кафе", "угу"])

    async def scenario() -> None:
        async with session(url) as c:
            c.speak(0.5)
            await c.wait("phrase")
            await asyncio.sleep(0.3)
            c.speak(0.5)  # «угу» — шум, перебиванием не считается
            await c.wait("turn_end", timeout=10)
            assert not c.events("interrupted")
            assert len(c.events("user_final")) == 1

    run(scenario())


def test_client_interrupt_command(live_server):
    model = FakeModel(say(LONG))
    url, _, _ = live_server(model, script=["Расскажи про кафе"])

    async def scenario() -> None:
        async with session(url) as c:
            c.speak(0.5)
            await c.wait("phrase")
            await c.send({"type": "interrupt"})
            got = await c.wait("interrupted")
            assert got["turn_id"] == c.events("turn_start")[0]["turn_id"]

    run(scenario())


def test_guide_to_after_playback_done(live_server):
    model = FakeModel(
        call("guide_to", {"place_id": "place:cafe"}), say("[excited] Провожаю вас в кафе.")
    )
    url, _, _ = live_server(model, script=["Проводи в кафе"], live_fast_places=False)

    async def scenario() -> None:
        async with session(url) as c:
            c.speak(0.5)
            end = await c.wait("turn_end")
            start = c.events("turn_start")[0]
            await asyncio.sleep(0.3)
            assert not c.events("action")  # провожать — только когда робот договорил
            await c.send({"type": "playback_done", "turn_id": end["turn_id"]})
            action = await c.wait("action")
            # turn_id хода: робот едет сразу (этот ход уже доигран), а не ждёт ещё один playback_done
            assert action == {
                "type": "action",
                "type_": "guide_to",
                "place_id": "cafe",
                "turn_id": start["turn_id"],
            }
            assert end["turn_id"] == start["turn_id"]
            kinds = c.kinds()
            assert kinds.index("turn_start") < kinds.index("turn_end") < kinds.index("action")

    run(scenario())


def test_where_is_action_is_sent_with_answer(live_server):
    model = FakeModel(call("where_is", {"place_id": "place:cafe"}), say("[happy] Кафе справа."))
    url, _, _ = live_server(model, script=["Где кафе?"], live_fast_places=False)

    async def scenario() -> None:
        async with session(url) as c:
            c.speak(0.5)
            await c.wait("turn_end")
            kinds = c.kinds()
            assert kinds.index("action") < kinds.index("turn_end")
            action = c.events("action")[0]
            assert action["type_"] == "where_is"
            assert action["turn_id"] == c.events("turn_start")[0]["turn_id"]

    run(scenario())


# ---------- быстрый путь: места без модели ----------
async def _turn(c: LiveClient, n: int, seconds: float = 0.5) -> tuple[dict, dict]:
    """Посетитель говорит n-ю реплику → (turn_start, turn_end) n-го хода; робот отчитывается playback_done."""
    c.speak(seconds)
    end = await c.wait("turn_end", count=n)
    start = c.events("turn_start")[n - 1]
    await c.send({"type": "playback_done", "turn_id": end["turn_id"]})
    await c.wait("listening", count=n + 1)
    return start, end


@pytest.mark.parametrize(
    ("question", "lang", "kind", "place", "spoken"),
    [
        ("Где здесь кафе?", "ru", "where_is", "cafe", "Проводить вас?"),
        ("Подскажите, где находится туалет?", "ru", "where_is", "restroom", "Проводить вас?"),
        ("Проводите меня до лифта", "ru", "guide_to", "lift", "до лифта!"),
        ("Дәретхана қайда?", "kk", "where_is", "restroom", "Апарып салайын ба?"),
        ("Мені кафеге апарыңызшы", "kk", "guide_to", "cafe", "кафеге"),
        ("Where is the elevator?", "en", "where_is", "lift", "Want me to take you there?"),
        ("Take me to the lecture hall, please", "en", "guide_to", "lecture_hall", "lecture hall"),
    ],
)
def test_fast_place_path_answers_without_model(live_server, question, lang, kind, place, spoken):
    model = FakeModel(say("не должна вызываться"))
    url, app, _fakes = live_server(model, script=[question])

    async def scenario() -> None:
        async with session(url) as c:
            c.speak(0.5)
            end = await c.wait("turn_end")
            start = c.events("turn_start")[0]
            assert end["lang"] == lang and end["found"]
            assert c.events("user_final")[0]["lang"] == lang
            if kind == "guide_to":
                await asyncio.sleep(0.2)
                assert not c.events("action")  # проводить — после playback_done
            await c.send({"type": "playback_done", "turn_id": end["turn_id"]})
            await c.wait("listening", count=2)  # ход закрыт: память и журнал записаны
            action = await c.wait("action")
            assert action == {
                "type": "action",
                "type_": kind,
                "place_id": place,
                "turn_id": start["turn_id"],
            }
            phrases = [p["text"] for p in c.events("phrase")]
            assert spoken in " ".join(phrases)
            assert any(isinstance(i, int) for i in c.items)  # звук шаблонной фразы

    run(scenario())
    assert model.requests == []  # без модели
    rows = messages_of(app)
    assert rows[0] == ("visitor", question, None)
    assert rows[-1][0] == "assistant" and spoken in rows[-1][1]
    with app.state.engine.connect() as conn:
        log = conn.execute(text("SELECT endpoint, category, found FROM question_log")).one()
    assert tuple(log) == ("live", "kb", True)


def test_fast_place_offer_then_yes_guides_and_keeps_context(live_server):
    """«Где кафе?» (шаблон) → «Да» (без модели — guide_to) → следующий вопрос агенту — с историей."""
    model = FakeModel(say("[happy] С восьми утра."))
    url, _app, _ = live_server(
        model, script=["Где кафе?", "Да, проводите", "А во сколько оно открывается?"]
    )

    async def scenario() -> None:
        async with session(url) as c:
            await _turn(c, 1)
            start, _ = await _turn(c, 2)
            action = await c.wait("action", count=2)
            assert action["type_"] == "guide_to" and action["place_id"] == "cafe"
            assert action["turn_id"] == start["turn_id"]
            await _turn(c, 3)

    run(scenario())
    assert len(model.requests) == 1  # только третья реплика
    history = json.dumps(model.requests[0]["messages"], ensure_ascii=False)
    assert "Где кафе?" in history and "Проводить вас?" in history and "Да, проводите" in history


def test_doubtful_place_question_goes_to_agent(live_server):
    model = FakeModel(say("[happy] Кафе справа, кофе от тысячи тенге."))
    url, _, _ = live_server(model, script=["Где кафе и сколько стоит кофе?"])

    async def scenario() -> None:
        async with session(url) as c:
            await c.wait("ready")
            c.speak(0.5)
            await c.wait("turn_end")

    run(scenario())
    assert len(model.requests) == 1


def test_template_phrase_audio_comes_from_cache_second_time(live_server, monkeypatch):
    """Шаблонная фраза: первый раз — синтез + фоновое заполнение кеша, второй — звук из кеша без TTS."""
    from app.agent import fastpath

    monkeypatch.setattr(fastpath._RNG, "choice", lambda seq: seq[0])
    url, _, fakes = live_server(
        FakeModel(), script=["Проводите меня до лифта", "Проводите меня до лифта"]
    )

    async def scenario() -> None:
        async with session(url) as c:
            await _turn(c, 1)
            for _ in range(100):  # кеш заполняется в фоне
                if fakes.synth and any(t.startswith("Пойдёмте") for t, _ in fakes.synth):
                    break
                await asyncio.sleep(0.02)
            await asyncio.sleep(0.2)
            await _turn(c, 2)
            phrases = [p["text"] for p in c.events("phrase")]
            assert phrases == ["Пойдёмте, провожу до лифта!"] * 2
            assert sum(i for i in c.items if isinstance(i, int)) >= 24_000 * 2  # звук из кеша дошёл

    run(scenario())
    tts = fakes.tts[0].texts
    assert tts.count("Пойдёмте, провожу до лифта!") == 1  # второй раз — без синтеза
    assert ("Пойдёмте, провожу до лифта!", "ru") in fakes.synth


# ---------- направление от робота: state.directions ----------
DIRECTIONS = {
    "cafe": {
        "ru": "Кафе — справа от вас, примерно в 15 метрах. Могу проводить.",
        "kk": "Кафе сіздің оң жағыңызда, шамамен 15 метр жерде.",
        "en": "Cafe is on your right, about 15 meters away.",
    },
    "lift": {"ru": "Лифт — слева от вас, примерно в 3 метрах."},
}


def test_parse_directions_validates_and_drops_garbage():
    assert parse_directions(None) is None  # поля нет — набор не трогаем
    assert parse_directions(["cafe"]) is None
    assert parse_directions("cafe") is None
    assert parse_directions({}) == {}  # у робота нет позиции — фраз нет
    raw = {
        "cafe": {"ru": "  Кафе —\n справа.  ", "kk": 15, "en": "", "de": "Rechts.", "fr": None},
        "lift": "слева",  # не объект
        "x" * 65: {"ru": "длинный ключ"},
        "": {"ru": "пустой ключ"},
        "hall": {"ru": "я" * 301},  # фраза длиннее 300
        "restroom": {"en": "e" * 300},
        "nothing": {"de": "nur Deutsch"},
    }
    assert parse_directions(raw) == {
        "cafe": {"ru": "Кафе — справа."},
        "restroom": {"en": "e" * 300},
    }
    many = {f"p{i}": {"ru": f"Место {i}."} for i in range(100)}
    parsed = parse_directions(many)
    assert parsed is not None and len(parsed) == 64 and "p0" in parsed and "p64" not in parsed


def test_state_directions_are_independent_and_expire(settings):
    async def check() -> None:
        playout = Playout(sample_rate=24_000)
        conv = LiveConversation(
            chat=None,  # type: ignore[arg-type]
            settings=settings,
            session_id=SESSION,
            lang="ru",
            playout=playout,
            echo_gate=EchoGate(playout, threshold=1800, tail_s=0.3),
            metrics=NULL_RECORDER,
        )
        assert conv.directions() == {}
        await conv._on_client({"type": "state", "place_id": "reception", "navigating": True})
        await conv._on_client({"type": "state", "directions": DIRECTIONS})
        # directions отдельно от place_id / navigating: те не меняются
        assert (conv.place_id, conv.navigating) == ("reception", True)
        assert conv.directions()["cafe"]["kk"] == DIRECTIONS["cafe"]["kk"]
        await conv._on_client({"type": "state", "place_id": "lift", "navigating": False})
        assert (
            conv.directions()["lift"] == DIRECTIONS["lift"]
        )  # state без directions — набор тот же
        await conv._on_client({"type": "state", "directions": "мусор"})
        assert "cafe" in conv.directions()  # мусор не стирает набор, ошибки нет
        await conv._on_client({"type": "state", "directions": {"lift": DIRECTIONS["lift"]}})
        assert list(conv.directions()) == ["lift"]  # новый набор — полный, заменяет старый
        conv._directions_at = time.monotonic() - DIRECTIONS_TTL_S - 1
        assert conv.directions() == {}  # старше 10 минут — не верим
        await conv._on_client({"type": "state", "directions": {}})
        assert conv.directions() == {}

    asyncio.run(check())


@pytest.mark.parametrize(
    ("lang", "question", "spoken", "tts"),
    [
        (
            "ru",
            "Где здесь кафе?",
            ["Кафе — справа от вас, примерно в 15 метрах.", "Проводить вас?"],
            "примерно в пятнадцати метрах",
        ),
        (
            "kk",
            "Кафе қайда?",
            ["Кафе сіздің оң жағыңызда, шамамен 15 метр жерде.", "Апарып салайын ба?"],
            "шамамен он бес метр жерде",
        ),
    ],
)
def test_fast_place_says_robot_direction(live_server, lang, question, spoken, tts):
    model = FakeModel(say("не должна вызываться"))
    url, app, fakes = live_server(model, script=[question])

    async def scenario() -> None:
        async with session(url, lang=lang) as c:
            await c.send({"type": "state", "directions": DIRECTIONS})
            c.speak(0.5)
            end = await c.wait("turn_end")
            assert [p["text"] for p in c.events("phrase")] == spoken
            action = c.events("action")[0]
            assert (action["type_"], action["place_id"]) == ("where_is", "cafe")
            await c.send({"type": "playback_done", "turn_id": end["turn_id"]})
            await c.wait("listening", count=2)

    run(scenario())
    assert model.requests == []
    spoken_tts = " ".join(fakes.tts[0].texts)
    assert tts in spoken_tts and "15" not in spoken_tts  # числа словами, с падежом
    assert not any(
        "метр" in text for text, _ in fakes.synth
    )  # фраза о направлении — не в кеш звука
    assert messages_of(app)[-1] == ("assistant", " ".join(spoken), None)


def test_fast_place_without_direction_for_this_place_answers_as_before(live_server):
    url, _, _ = live_server(FakeModel(), script=["Где здесь кафе?"])

    async def scenario() -> None:
        async with session(url) as c:
            await c.send({"type": "state", "directions": {"lift": DIRECTIONS["lift"]}})
            c.speak(0.5)
            await c.wait("turn_end")
            phrases = [p["text"] for p in c.events("phrase")]
            assert phrases[-1] == "Проводить вас?" and "метр" not in " ".join(phrases)
            assert "экран" in phrases[0]  # стрелка на экране, как без направлений

    run(scenario())


def test_agent_where_is_gets_robot_direction(live_server):
    model = FakeModel(
        call("where_is", {"place_id": "place:cafe"}),
        say("[happy] Кафе — справа от вас, примерно в 15 метрах. Проводить вас?"),
    )
    url, _, _ = live_server(model, script=["Где кафе?"], live_fast_places=False)

    async def scenario() -> None:
        async with session(url) as c:
            await c.send({"type": "state", "directions": DIRECTIONS})
            c.speak(0.5)
            await c.wait("turn_end")

    run(scenario())
    result = json.loads(model.requests[1]["messages"][-1]["content"])
    assert result["ok"] is True and result["place_id"] == "place:cafe"
    # своё «Могу проводить.» робота убрано: предложить проводить — дело ответа
    assert result["direction"] == "Кафе — справа от вас, примерно в 15 метрах."


def test_voice_follows_each_phrase_and_v3_tag_only_in_tts(live_server):
    """Ход начался как русский, ответ — по-казахски: голос kk, тег подачи только в запросе синтеза."""
    model = FakeModel(say("[happy] Кафе бірінші қабатта. Кофе мен тоқаш бар."))
    url, app, fakes = live_server(model, script=["Расскажи про кафе"])

    async def scenario() -> None:
        async with session(url) as c:
            c.speak(0.5)
            end = await c.wait("turn_end")
            await c.send({"type": "playback_done", "turn_id": end["turn_id"]})
            await c.wait("listening", count=2)
            phrases = [p["text"] for p in c.events("phrase")]
            assert phrases == ["Кафе бірінші қабатта.", "Кофе мен тоқаш бар."]
            assert not any("cheerfully" in p for p in phrases)

    run(scenario())
    assert fakes.voices[-1] == "kk"  # казахский текст — голос kk, не ru/flash
    assert fakes.tts[0].texts == [
        "[cheerfully] Кафе бірінші қабатта.",
        "[cheerfully] Кофе мен тоқаш бар.",
    ]
    rows = messages_of(app)
    assert rows[1] == ("assistant", "Кафе бірінші қабатта. Кофе мен тоқаш бар.", None)
    with app.state.engine.connect() as conn:
        answer = conn.execute(text("SELECT answer FROM question_log")).scalar_one()
    assert "cheerfully" not in answer


def test_tts_text_is_normalized_but_subtitle_is_not(live_server):
    model = FakeModel(say("[happy] Хм… Ну, кафе работает с 9:00 до 18:00."))
    url, _, fakes = live_server(model, script=["Расскажи про кафе"])

    async def scenario() -> None:
        async with session(url) as c:
            c.speak(0.5)
            await c.wait("turn_end")
            # «Хм…» отдельной фразой не звучит и не показывается; остальное в субтитре — как написала модель
            assert [p["text"] for p in c.events("phrase")] == ["Ну, кафе работает с 9:00 до 18:00."]

    run(scenario())
    assert fakes.tts[0].texts == ["Кафе работает с девяти до восемнадцати."]


def test_session_warms_up_voices(live_server):
    url, _, fakes = live_server(FakeModel())

    async def scenario() -> None:
        async with session(url, lang="kk") as c:
            await c.wait("listening")
            for _ in range(50):
                if len(fakes.warmed) >= 3:
                    break
                await asyncio.sleep(0.02)

    run(scenario())
    assert fakes.warmed[:3] == ["kk", "ru", "en"]  # язык сессии — первым


def test_warmup_is_throttled_per_voice():
    from app.live import services

    calls: list[tuple[str, str]] = []
    now = [1000.0]

    async def synth(text: str, lang: str) -> bytes:
        calls.append((text, lang))
        return b""

    services._WARMED.clear()
    warm = services.make_warmup(
        synth,
        {"ru": "v-ru", "kk": "v-kk", "en": "v-en"},
        {"ru": "f", "kk": "v3", "en": "f"},
        600,
        lambda: now[0],
    )

    async def check() -> None:
        await warm("kk")
        await warm("kk")
        now[0] += 601
        await warm("kk")
        await warm("ru")

    asyncio.run(check())
    services._WARMED.clear()
    assert calls == [("Сәлем", "kk"), ("Сәлем", "kk"), ("Привет", "ru")]


def test_segmented_stt_skips_silent_and_short_segments():
    provider = ScriptSttProvider(["Где кафе?"], delay=0.0)
    stt = SegmentedProviderSTT(provider, None, sample_rate=16_000, timeout_s=1.0, silence_peak=500)

    async def texts(audio: bytes) -> list[TranscriptionFrame]:
        return [f async for f in stt.run_stt(audio)]

    quiet = b"\x10\x00" * 16_000  # 1 с, пик 16
    short = LOUD[: 16_000 * 2 // 10]  # 0,1 с
    loud = LOUD * 25  # 1 с
    assert [f.text for f in asyncio.run(texts(quiet))] == [""]
    assert [f.text for f in asyncio.run(texts(short))] == [""]
    assert provider.calls == [] and stt.skipped == 2  # тишина в STT не ушла
    [frame] = asyncio.run(texts(loud))
    assert frame.text == "Где кафе?" and len(provider.calls) == 1


def test_segmented_stt_unknown_language_is_left_to_session():
    class Kyrgyz(ScriptSttProvider):
        async def stt(self, pcm, sample_rate, lang):
            return SttResult(text="Кафе кайда?", lang="ru", confidence=None, detected="ky")

    stt = SegmentedProviderSTT(Kyrgyz([], 0.0), None, sample_rate=16_000, timeout_s=1.0)

    async def run_once() -> TranscriptionFrame:
        [frame] = [f async for f in stt.run_stt(LOUD * 25)]
        return frame

    frame = asyncio.run(run_once())
    assert frame.text == "Кафе кайда?" and frame.language is None


def test_look_answer_is_spoken_in_live_session(live_server):
    url, app, _ = live_server(FakeModel())

    async def scenario() -> None:
        async with session(url) as c:
            from fastapi.testclient import TestClient

            meta = json.dumps({"question": "Что это?", "lang": "ru", "session_id": SESSION})

            def post() -> int:
                with TestClient(app) as tc:
                    r = tc.post(
                        "/v1/look",
                        files={"image": ("f.jpg", JPEG, "image/jpeg")},
                        data={"meta": meta},
                        headers=AUTH,
                    )
                    return r.status_code

            assert await asyncio.to_thread(post) == 200
            await c.wait("turn_end")
            assert c.events("phrase")

    run(scenario())


# ---------- подключение ----------
def test_requires_token(live_server):
    url, _, _ = live_server()

    async def scenario() -> None:
        with pytest.raises(InvalidStatus):
            async with connect(f"{url}/v1/live?session_id=s1&lang=ru", open_timeout=5):
                pass
        with pytest.raises(InvalidStatus):
            async with connect(
                f"{url}/v1/live?session_id=s1",
                additional_headers={"Authorization": "Bearer wrong"},
                open_timeout=5,
            ):
                pass

    run(scenario())


def test_session_limit_per_token(live_server):
    url, _, _ = live_server(live_max_sessions=1)

    async def scenario() -> None:
        async with (
            session(url, session_id="first"),
            connect(
                f"{url}/v1/live?session_id=second&lang=ru",
                additional_headers={"Authorization": f"Bearer {TOKEN}"},
                open_timeout=5,
            ) as ws,
        ):
            msg = json.loads(await asyncio.wait_for(ws.recv(), 5))
            assert msg["type"] == "error" and msg["error"]["code"] == "rate_limited"
            with pytest.raises(ConnectionClosed) as closed:
                await asyncio.wait_for(ws.recv(), 5)
            assert closed.value.rcvd.code == 1013

    run(scenario())


def test_reconnect_with_same_session_replaces_old(live_server):
    url, app, _ = live_server(live_max_sessions=1)

    async def scenario() -> None:
        async with session(url) as old, session(url) as new:
            await old.wait_closed()
            assert new.events("ready")
            assert len(app.state.live_registry) == 1

    run(scenario())


def test_idle_timeout_closes(live_server):
    url, _, _ = live_server(live_idle_timeout_s=0.6)

    async def scenario() -> None:
        async with session(url) as c:
            closed = await c.wait_closed(timeout=6)
            assert closed.rcvd is not None and closed.rcvd.code == 1000
            assert closed.rcvd.reason == "idle timeout"

    run(scenario())


def test_disabled_live_closes_with_error(live_server):
    url, _, _ = live_server(live_enabled=False)

    async def scenario() -> None:
        async with connect(
            f"{url}/v1/live?session_id=s1&lang=ru",
            additional_headers={"Authorization": f"Bearer {TOKEN}"},
            open_timeout=5,
        ) as ws:
            msg = json.loads(await asyncio.wait_for(ws.recv(), 5))
            assert msg == {
                "type": "error",
                "error": {
                    "code": "speech_unavailable",
                    "message": "Live mode is disabled on the server",
                },
            }
            with pytest.raises(ConnectionClosed) as closed:
                await asyncio.wait_for(ws.recv(), 5)
            assert closed.value.rcvd.code == 1011

    run(scenario())


def test_live_enabled_auto():
    base = {"_env_file": None, "robot_token": "t"}
    assert not Settings(**base).resolved_live_enabled()
    assert not Settings(**base, elevenlabs_api_key="k").resolved_live_enabled()
    assert Settings(**base, elevenlabs_api_key="k", openrouter_api_key="o").resolved_live_enabled()
    assert not Settings(
        **base, elevenlabs_api_key="k", openrouter_api_key="o", live_enabled=False
    ).resolved_live_enabled()


# ---------- сериализатор, эхо, часы воспроизведения ----------
def test_serializer_frames():
    async def check() -> None:
        s = LiveSerializer()
        frame = await s.deserialize(b"\x01\x00" * 160)
        assert isinstance(frame, InputAudioRawFrame) and frame.sample_rate == 16_000
        assert await s.deserialize(b"\x01\x00\x02") is None  # нечётная длина
        assert await s.deserialize(b"\x00" * (64 * 1024 + 2)) is None
        msg = await s.deserialize('{"type": "playback_done", "turn_id": "trn_1"}')
        assert isinstance(msg, InputTransportMessageFrame) and msg.message["turn_id"] == "trn_1"
        assert await s.deserialize('{"type": "unknown"}') is None
        assert await s.deserialize("not json") is None
        assert s.dropped == 4
        assert await s.serialize(OutputAudioRawFrame(b"\x05\x00", 24_000, 1)) == b"\x05\x00"
        out = await s.serialize(
            OutputTransportMessageFrame(message={"type": "phrase", "text": "Привет"})
        )
        assert out == '{"type":"phrase","text":"Привет"}'
        urgent = await s.serialize(OutputTransportMessageUrgentFrame(message={"type": "listening"}))
        assert urgent == '{"type":"listening"}'
        assert await s.serialize(TranscriptionFrame("x", "u", "t")) is None

    asyncio.run(check())


def test_playout_heard_and_echo_helpers():
    p = Playout(sample_rate=24_000)
    p.start_turn("t")
    p.next_phrase()
    p.audio(48_000, now=100.0)  # 1 с звука фразы 0
    p.next_phrase()
    p.audio(48_000, now=100.1)  # встаёт в очередь: 101..102
    assert p.heard(now=101.5) == {0: 1.0, 1: 0.5}
    assert p.playing(now=101.9) and not p.playing(now=102.1)
    assert p.playing(now=102.1, tail=0.3)
    assert cut_words("раз два три четыре", 0.5) == "раз два…"
    assert cut_words("раз два", 1.0) == "раз два"
    assert is_echo("кафе на первом этаже", ["Кафе на первом этаже, справа."])
    assert not is_echo("а где туалет", ["Кафе на первом этаже, справа."])
    assert rms(b"\x00\x00" * 10) == 0.0 and rms(LOUD) > 5000


def test_echo_gate_mutes_quiet_audio_while_playing():
    p = Playout(sample_rate=24_000)
    gate = EchoGate(p, threshold=1800, tail_s=0.3)
    quiet_echo, loud = _tone(800), _tone(12_000)
    gate.aec = False
    assert not gate.should_mute(quiet_echo)  # робот молчит — микрофон как есть
    p.audio(24_000 * 2 * 5)  # 5 с ответа от «сейчас»
    assert gate.should_mute(quiet_echo)  # тихое — эхо собственного голоса
    assert not gate.should_mute(loud)  # громкая речь рядом с роботом проходит
    gate.aec = True
    assert not gate.should_mute(quiet_echo)  # эхо гасит робот


# ---------- агент в живом режиме ----------
def test_agent_live_turn_defers_memory_until_commit(make_client):
    model = FakeModel(say("[happy] Кафе на первом этаже. Справа от входа."))
    client = make_client(chat_client=model.client())
    chat = client.app.state.chat

    async def check() -> None:
        live = LiveTurn()
        req = ChatRequest(question="Расскажи про кафе", lang="ru", session_id=SESSION)
        events = [e async for e in chat.chat_stream(req, "req_live", live=live)]
        assert [d["text"] for k, d in events if k == "phrase"] == [
            "Кафе на первом этаже.",
            "Справа от входа.",
        ]
        assert live.pending and live.first_token_at is not None
        assert messages(client) == []  # память и журнал ждут, что робот успел сказать
        await live.commit(["Кафе на первом этаже."], True)
        await live.commit(["второй раз не пишется"], False)

    asyncio.run(check())
    assert messages(client) == [
        ("visitor", "Расскажи про кафе", None),
        ("assistant", "Кафе на первом этаже.", None),
    ]


def test_agent_where_is_direction_only_in_live_and_in_answer_language(make_client):
    async def tool_result(live: LiveTurn | None, lang: str) -> dict:
        model = FakeModel(call("where_is", {"place_id": "cafe"}), say("[happy] Ок."))
        client = make_client(chat_client=model.client(), live_fast_places=False)
        req = ChatRequest(question="Кафе қайда?", lang=lang, session_id=SESSION)
        async with aclosing(client.app.state.chat.chat_stream(req, "req_dir", live=live)) as evs:
            async for _ in evs:
                pass
        return json.loads(model.requests[1]["messages"][-1]["content"])

    async def check() -> None:
        plain = await tool_result(None, "kk")  # /v1/chat: направлений нет — как раньше
        assert plain["ok"] is True and "direction" not in plain
        live = await tool_result(LiveTurn(directions=DIRECTIONS), "kk")
        assert live["direction"] == DIRECTIONS["cafe"]["kk"]
        no_lang = await tool_result(LiveTurn(directions={"cafe": {"en": "Right."}}), "kk")
        assert "direction" not in no_lang  # фразы на языке ответа нет — без direction

    asyncio.run(check())


def test_real_services_build_without_network(settings):
    s = settings.model_copy(update={"elevenlabs_api_key": SecretStr("k"), "live_enabled": True})

    async def check() -> None:
        services = build_live_services(s, "kk")
        assert services.turn_analyzer is not None
        assert services.stt_segmented and isinstance(services.stt, SegmentedProviderSTT)
        await services.set_stt_lang("ru")
        assert services.stt.lang == "ru"
        assert isinstance(services.switch_tts("ru"), ManuallySwitchServiceFrame)
        assert services.provider.startswith("pipecat:elevenlabs")
        await services.aclose()

    asyncio.run(check())
