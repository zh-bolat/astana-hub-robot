from __future__ import annotations

import asyncio
import json

import httpx
import pytest

from app.config import Settings
from app.speech.base import SpeechError, has_letters
from app.speech.elevenlabs import ElevenLabsProvider
from app.speech.factory import build_stt_provider, build_tts_provider
from app.speech.fake import SILENT_MP3, FakeSpeechProvider
from tests.conftest import AUTH

# ---------- выбор провайдеров ----------


def _s(**kw) -> Settings:
    base = dict(
        _env_file=None,
        robot_token="t",
        gemini_api_key=None,
        elevenlabs_api_key=None,
        yandex_api_key=None,
    )
    return Settings(**{**base, **kw})


@pytest.mark.parametrize(
    ("keys", "stt", "tts"),
    [
        ({}, "fake", "fake"),
        ({"gemini_api_key": "g"}, "gemini", "fake"),
        ({"gemini_api_key": "g", "yandex_api_key": "y"}, "gemini", "yandex"),
        ({"gemini_api_key": "g", "elevenlabs_api_key": "e"}, "elevenlabs", "elevenlabs"),
        ({"elevenlabs_api_key": "e", "yandex_api_key": "y"}, "elevenlabs", "elevenlabs"),
    ],
)
def test_auto_provider_selection(keys, stt, tts):
    s = _s(**keys)
    assert s.resolved_stt_provider() == stt
    assert s.resolved_tts_provider() == tts
    assert build_stt_provider(s).name == stt
    assert build_tts_provider(s).name == tts


def test_explicit_provider_overrides_auto():
    s = _s(gemini_api_key="g", elevenlabs_api_key="e", stt_provider="gemini", tts_provider="fake")
    assert build_stt_provider(s).name == "gemini"
    assert build_tts_provider(s).name == "fake"


def test_explicit_provider_without_key_fails_fast():
    with pytest.raises(RuntimeError):
        build_tts_provider(_s(tts_provider="elevenlabs"))


def test_has_letters():
    assert has_letters("Кафе қайда?")
    assert not has_letters("00:00")
    assert not has_letters(" ... ")
    assert not has_letters("[music]")
    assert not has_letters("")


# ---------- X-Speech-Provider ----------


def test_tts_speech_provider_header(make_client):
    c = make_client()
    r = c.post("/v1/tts", json={"text": "Привет.", "lang": "ru"}, headers=AUTH)
    assert r.headers["x-speech-provider"] == "fake"


def test_tts_speech_provider_header_on_error(make_client):
    c = make_client(speech=FakeSpeechProvider(fail=True))
    r = c.post("/v1/tts", json={"text": "Привет.", "lang": "ru"}, headers=AUTH)
    assert r.status_code == 503
    assert r.headers["x-speech-provider"] == "fake"


def test_separate_stt_and_tts_providers(settings):
    from fastapi.testclient import TestClient

    from app.llm.fake import FakeProvider
    from app.main import create_app

    class NamedTts(FakeSpeechProvider):
        name = "named"

    app = create_app(settings, FakeProvider(), FakeProvider(), tts=NamedTts())
    with TestClient(app) as c:
        r = c.post("/v1/tts", json={"text": "Привет.", "lang": "ru"}, headers=AUTH)
        assert r.headers["x-speech-provider"] == "named"
        assert app.state.stt.name == "fake"


# ---------- ElevenLabs (MockTransport) ----------


def _eleven(handler) -> ElevenLabsProvider:
    client = httpx.AsyncClient(
        transport=httpx.MockTransport(handler), headers={"xi-api-key": "test"}
    )
    return ElevenLabsProvider(
        api_key="test",
        base_url="https://api.elevenlabs.test",
        voices={"ru": "voice-ru", "kk": "voice-kk", "en": "voice-en"},
        client=client,
    )


@pytest.mark.parametrize(
    ("lang", "model", "voice"),
    [
        ("kk", "eleven_v3", "voice-kk"),
        ("ru", "eleven_flash_v2_5", "voice-ru"),
        ("en", "eleven_flash_v2_5", "voice-en"),
    ],
)
def test_elevenlabs_tts_request(lang, model, voice):
    seen = []

    def handler(req: httpx.Request) -> httpx.Response:
        seen.append(req)
        return httpx.Response(200, content=SILENT_MP3, headers={"content-type": "audio/mpeg"})

    p = _eleven(handler)
    audio = asyncio.run(p.tts("Сәлем.", lang))
    assert audio == SILENT_MP3
    req = seen[0]
    assert req.method == "POST"
    assert req.url.path == f"/v1/text-to-speech/{voice}"
    assert req.url.params["output_format"] == "mp3_44100_128"
    assert req.headers["xi-api-key"] == "test"
    assert json.loads(req.content) == {"text": "Сәлем.", "model_id": model, "language_code": lang}
    assert model in p.voice_id(lang)  # модель входит в ключ кеша


def test_elevenlabs_tts_no_language_code_for_multilingual_v2():
    seen = []

    def handler(req):
        seen.append(json.loads(req.content))
        return httpx.Response(200, content=b"mp3")

    p = _eleven(handler)
    p.tts_model_default = "eleven_multilingual_v2"
    asyncio.run(p.tts("Hi.", "en"))
    assert "language_code" not in seen[0]


def test_elevenlabs_tts_error():
    p = _eleven(lambda req: httpx.Response(401, json={"detail": "invalid key"}))
    with pytest.raises(SpeechError):
        asyncio.run(p.tts("Hi.", "en"))


def test_elevenlabs_stt_request_and_iso3_lang():
    seen = []

    def handler(req: httpx.Request) -> httpx.Response:
        seen.append(req)
        return httpx.Response(
            200,
            json={
                "language_code": "kaz",
                "language_probability": 0.93,
                "text": "Асхана қайда?",
                "words": [],
            },
        )

    res = asyncio.run(_eleven(handler).stt(b"\x01\x02" * 1000, 16000, None))
    assert (res.text, res.lang, res.confidence) == ("Асхана қайда?", "kk", 0.93)
    req = seen[0]
    assert req.url.path == "/v1/speech-to-text"
    body = req.content
    assert b'name="model_id"\r\n\r\nscribe_v2' in body
    assert b'name="file_format"\r\n\r\npcm_s16le_16' in body
    assert b"language_code" not in body  # автоопределение
    assert b"\x01\x02" * 100 in body  # сырой PCM без WAV-заголовка


def test_elevenlabs_stt_with_lang_and_empty():
    seen = []

    def handler(req):
        seen.append(req)
        return httpx.Response(200, json={"language_code": "ru", "text": "(шум)"})

    res = asyncio.run(_eleven(handler).stt(b"\x01\x02", 16000, "ru"))
    assert res.text == "" and res.lang == "ru"
    assert b'name="language_code"\r\n\r\nru' in seen[0].content


def test_elevenlabs_stt_network_error():
    def handler(req):
        raise httpx.ConnectError("down")

    with pytest.raises(SpeechError):
        asyncio.run(_eleven(handler).stt(b"\x01\x02", 16000, "ru"))


# ---------- Gemini STT (без сети) ----------


class _Resp:
    def __init__(self, text):
        self.text = text


def _gemini_stt(monkeypatch, behaviour):
    from app.speech.gemini_stt import GeminiSttProvider

    p = GeminiSttProvider(api_key="test", model="lite", fallback_models=["flash"], deadline_s=2)
    calls = []

    async def fake_generate(*, model, contents, config):
        calls.append((model, contents, config))
        return behaviour(model, len(calls))

    monkeypatch.setattr(p.client.aio.models, "generate_content", fake_generate)
    return p, calls


def test_gemini_stt_detects_language(monkeypatch):
    p, calls = _gemini_stt(
        monkeypatch, lambda m, n: _Resp('{"lang": "kk", "text": "Асхана қайда?"}')
    )
    res = asyncio.run(p.stt(b"\x01\x02" * 100, 16000, None))
    assert (res.text, res.lang) == ("Асхана қайда?", "kk")
    model, contents, config = calls[0]
    assert model == "lite"
    audio, prompt = contents
    assert audio.inline_data.mime_type == "audio/wav"
    assert audio.inline_data.data[:4] == b"RIFF"
    assert "detect" in prompt
    assert config.response_mime_type == "application/json"


def test_gemini_stt_known_lang_and_hallucination_filter(monkeypatch):
    p, calls = _gemini_stt(monkeypatch, lambda m, n: _Resp('{"lang": "en", "text": "00:00"}'))
    res = asyncio.run(p.stt(b"\x01\x02" * 100, 16000, "ru"))
    assert (res.text, res.lang) == ("", "ru")
    assert "Russian" in calls[0][1][1]


def test_gemini_stt_falls_back(monkeypatch):
    from google.genai import errors as genai_errors

    def behaviour(model, n):
        if model == "lite":
            raise genai_errors.ClientError(429, {"error": {"message": "RESOURCE_EXHAUSTED"}})
        return _Resp('{"lang": "ru", "text": "Где кафе?"}')

    p, calls = _gemini_stt(monkeypatch, behaviour)
    res = asyncio.run(p.stt(b"\x01\x02", 16000, None))
    assert res.text == "Где кафе?"
    assert [c[0] for c in calls][-1] == "flash"


def test_gemini_stt_all_fail_is_speech_error(monkeypatch):
    from google.genai import errors as genai_errors

    def behaviour(model, n):
        raise genai_errors.ServerError(503, {"error": {"message": "high demand"}})

    p, _ = _gemini_stt(monkeypatch, behaviour)
    with pytest.raises(SpeechError):
        asyncio.run(p.stt(b"\x01\x02", 16000, None))
