from __future__ import annotations

import asyncio
import json
from urllib.parse import parse_qs, urlparse

import pytest
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from app.config import Settings
from app.llm.fake import FakeProvider
from app.main import create_app
from app.speech.factory import build_stt_stream_provider
from app.speech.fake import FakeSpeechProvider
from app.speech.streaming import (
    BufferedStreamingStt,
    ElevenLabsRealtimeStt,
    FallbackStreamingStt,
)
from tests.conftest import AUTH, SESSION
from tests.test_speech import tone

SILENCE = b"\x00\x00" * 1600  # 0.1 с тишины


def _url(**params) -> str:
    query = "&".join(f"{k}={v}" for k, v in {"session_id": SESSION, **params}.items())
    return f"/v1/stt/stream?{query}"


def _stream(client: TestClient, chunks: list[bytes], end: bool = True, **params) -> list[dict]:
    with client.websocket_connect(_url(**params), headers=AUTH) as ws:
        messages = [ws.receive_json()]
        for chunk in chunks:
            ws.send_bytes(chunk)
        if end:
            ws.send_text(json.dumps({"type": "end"}))
        while messages[-1]["type"] not in ("final", "error"):
            messages.append(ws.receive_json())
    return messages


# ---------- buffered (без ключа ElevenLabs) ----------


def test_buffered_final_after_end(client):
    speech = tone(1.0)
    msgs = _stream(client, [speech[i : i + 3200] for i in range(0, len(speech), 3200)], lang="kk")
    assert msgs[0]["type"] == "ready"
    assert msgs[0]["provider"] == "buffered:fake"
    final = msgs[-1]
    assert final["type"] == "final"
    assert final["lang"] == "kk"
    assert final["text"]
    assert final["request_id"] == msgs[0]["request_id"]


def test_buffered_silence_gives_empty_final(client):
    msgs = _stream(client, [SILENCE] * 5)
    assert msgs[-1] == {**msgs[-1], "type": "final", "text": ""}


def test_no_speech_closes_by_itself(make_client):
    client = make_client(stt_stream_no_speech_s=0.2)
    msgs = _stream(client, [SILENCE], end=False)
    assert msgs[-1]["type"] == "final"
    assert msgs[-1]["text"] == ""


def test_max_length_ends_session(make_client):
    client = make_client(max_audio_seconds=0.5)
    msgs = _stream(client, [tone(0.3), tone(0.3), tone(0.3)], end=False)
    assert msgs[-1]["type"] == "final"
    assert msgs[-1]["text"]


def test_requires_token(client):
    with pytest.raises(WebSocketDisconnect) as exc, client.websocket_connect(_url()) as ws:
        ws.receive_json()
    assert exc.value.code == 1008


def test_bad_lang_is_error(client):
    msgs = _stream(client, [], end=False, lang="de")
    assert msgs[-1]["type"] == "error"
    assert msgs[-1]["code"] == "bad_request"


def test_provider_failure_is_speech_unavailable(make_client):
    client = make_client(speech=FakeSpeechProvider(fail=True))
    msgs = _stream(client, [tone(0.5)])
    assert msgs[-1]["type"] == "error"
    assert msgs[-1]["code"] == "speech_unavailable"


# ---------- ElevenLabs Scribe Realtime (подменённый WebSocket) ----------


class FakeRealtimeWs:
    """Отвечает как Scribe Realtime: partial на каждый кусок звука, committed по commit."""

    def __init__(self, words: list[str], lang_code: str | None = None) -> None:
        self.words = words
        self.lang_code = lang_code
        self.sent: list[dict] = []
        self.queue: asyncio.Queue[str] = asyncio.Queue()
        self.closed = False

    async def send(self, raw: str) -> None:
        msg = json.loads(raw)
        self.sent.append(msg)
        heard = len([m for m in self.sent if m["audio_base_64"]])
        if msg["audio_base_64"] and heard <= len(self.words):
            text = " ".join(self.words[:heard])
            await self.queue.put(json.dumps({"message_type": "partial_transcript", "text": text}))
        if msg["commit"]:
            committed = {"message_type": "committed_transcript", "text": " ".join(self.words)}
            if self.lang_code:
                committed = {
                    **committed,
                    "message_type": "committed_transcript_with_timestamps",
                    "language_code": self.lang_code,
                }
            await self.queue.put(json.dumps(committed))

    async def recv(self) -> str:
        return await self.queue.get()

    async def close(self) -> None:
        self.closed = True


def _realtime_client(settings: Settings, ws: FakeRealtimeWs | None, opened: list) -> TestClient:
    async def connect(url, additional_headers):
        opened.append((url, additional_headers))
        if ws is None:
            raise OSError("connection refused")
        return ws

    speech = FakeSpeechProvider()
    stream = FallbackStreamingStt(
        ElevenLabsRealtimeStt(api_key="xi-test", connect=connect),
        BufferedStreamingStt(speech, timeout_s=2),
    )
    app = create_app(settings, FakeProvider(), FakeProvider(), speech, stt_stream=stream)
    return TestClient(app)


def test_realtime_partials_then_final(settings):
    ws = FakeRealtimeWs(["Где", "здесь", "кофе?"])
    opened: list = []
    with _realtime_client(settings, ws, opened) as client:
        msgs = _stream(client, [tone(0.1)] * 3, lang="ru")
    assert [m["type"] for m in msgs] == ["ready", "partial", "partial", "partial", "final"]
    assert msgs[0]["provider"] == "elevenlabs-realtime"
    assert [m["text"] for m in msgs[1:4]] == ["Где", "Где здесь", "Где здесь кофе?"]
    assert msgs[-1]["text"] == "Где здесь кофе?"
    assert msgs[-1]["lang"] == "ru"
    url, headers = opened[0]
    query = parse_qs(urlparse(url).query)
    assert query["model_id"] == ["scribe_v2_realtime"]
    assert query["audio_format"] == ["pcm_16000"]
    assert query["commit_strategy"] == ["vad"]
    assert query["language_code"] == ["ru"]
    assert headers == {"xi-api-key": "xi-test"}
    assert ws.sent[-1]["commit"] is True
    assert ws.closed


def test_realtime_detects_language_without_hint(settings):
    ws = FakeRealtimeWs(["Хаб", "қашан", "ашылады?"], lang_code="kaz")
    opened: list = []
    with _realtime_client(settings, ws, opened) as client:
        msgs = _stream(client, [tone(0.1)] * 3)
    assert msgs[-1]["lang"] == "kk"
    query = parse_qs(urlparse(opened[0][0]).query)
    assert "language_code" not in query
    assert query["include_language_detection"] == ["true"]


def test_realtime_unavailable_falls_back_to_buffered(settings):
    opened: list = []
    with _realtime_client(settings, None, opened) as client:
        msgs = _stream(client, [tone(0.5)], lang="en")
    assert opened
    assert msgs[0]["provider"] == "buffered:fake"
    assert msgs[-1]["type"] == "final"
    assert msgs[-1]["text"]


# ---------- выбор провайдера ----------


def test_stream_provider_auto_selection():
    base = dict(_env_file=None, robot_token="t", elevenlabs_api_key=None)
    stt = FakeSpeechProvider()
    assert build_stt_stream_provider(Settings(**base), stt).name == "buffered:fake"
    with_key = Settings(**{**base, "elevenlabs_api_key": "xi"})
    assert build_stt_stream_provider(with_key, stt).name == "elevenlabs-realtime"


def test_realtime_noise_without_words_closes_as_no_speech(settings):
    ws = FakeRealtimeWs([])  # шум зала: звук громкий, но слов нет
    with _realtime_client(settings.model_copy(update={"stt_stream_no_speech_s": 0.3}), ws, []) as c:
        msgs = _stream(c, [tone(0.1)] * 2, end=False, lang="ru")
    assert msgs[-1]["type"] == "final"
    assert msgs[-1]["text"] == ""
