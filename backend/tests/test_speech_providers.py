from __future__ import annotations

import asyncio
import base64
import json

import httpx
import pytest

from app.config import Settings
from app.speech.base import SpeechError, has_letters
from app.speech.elevenlabs import ElevenLabsProvider, VoiceSettings
from app.speech.factory import FallbackTtsProvider, build_stt_provider, build_tts_provider
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
        openrouter_api_key=None,
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
        ({"openrouter_api_key": "o"}, "openrouter", "openrouter"),
        ({"openrouter_api_key": "o", "gemini_api_key": "g"}, "gemini", "openrouter"),
        ({"openrouter_api_key": "o", "yandex_api_key": "y"}, "openrouter", "yandex"),
        ({"openrouter_api_key": "o", "elevenlabs_api_key": "e"}, "elevenlabs", "elevenlabs"),
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
    body = json.loads(req.content)
    assert body.pop("voice_settings") == VoiceSettings().for_model(model)
    # тег подачи v3 (ELEVENLABS_V3_TAG) — только в теле запроса синтеза
    text = "[cheerfully] Сәлем." if model == "eleven_v3" else "Сәлем."
    assert body == {"text": text, "model_id": model, "language_code": lang}
    assert model in p.voice_id(lang)  # модель входит в ключ кеша


def test_elevenlabs_voice_settings_per_model():
    vs = VoiceSettings(
        stability=0.3, similarity_boost=0.7, style=0.2, use_speaker_boost=False, speed=1.5
    )
    # Eleven v3: только своя stability (ELEVENLABS_V3_STABILITY), округлённая до 0.0 / 0.5 / 1.0
    assert vs.for_model("eleven_v3") == {"stability": 0.5}
    assert VoiceSettings(v3_stability=0.2).for_model("eleven_v3") == {"stability": 0.0}
    assert VoiceSettings(v3_stability=0.9).for_model("eleven_v3") == {"stability": 1.0}
    assert VoiceSettings(stability=0.0).for_model("eleven_v3") == {"stability": 0.5}
    flash = vs.for_model("eleven_flash_v2_5")
    assert flash == {
        "stability": 0.3,
        "similarity_boost": 0.7,
        "style": 0.2,
        "use_speaker_boost": False,
        "speed": 1.2,  # обрезано до допустимого максимума
    }


def test_elevenlabs_voice_settings_change_cache_key():
    a = _eleven(lambda r: httpx.Response(200, content=b"mp3"))
    b = _eleven(lambda r: httpx.Response(200, content=b"mp3"))
    b.voice_settings = VoiceSettings(stability=0.9, v3_stability=1.0)
    assert a.voice_id("ru") != b.voice_id("ru")
    assert a.voice_id("kk") != b.voice_id("kk")  # v3: 0.5 -> 1.0
    c = _eleven(lambda r: httpx.Response(200, content=b"mp3"))
    c.voice_settings = VoiceSettings(v3_tag="")
    assert a.voice_id("kk") != c.voice_id("kk")  # тег v3 — другое аудио
    assert a.voice_id("ru") == c.voice_id("ru")  # flash тег не получает


def test_elevenlabs_defaults_are_the_chosen_voices():
    s = Settings(_env_file=None)
    assert (s.elevenlabs_voice_ru, s.elevenlabs_voice_en, s.elevenlabs_voice_kk) == (
        "dVRDrbP5ULGXB94se4KZ",  # Alina
        "FGY2WhTYpPnrIDTdsKH5",  # Laura
        "Jbte7ht1CqapnZvc4KpK",  # Kari
    )
    vs = VoiceSettings.from_settings(s)
    assert vs.for_model("eleven_flash_v2_5") == {
        "stability": 0.35,
        "similarity_boost": 0.8,
        "style": 0.45,
        "use_speaker_boost": True,
        "speed": 1.1,
    }
    assert vs.for_model("eleven_v3") == {"stability": 0.5}
    assert vs.tag_text("eleven_v3", "Сәлем!") == "[cheerfully] Сәлем!"
    assert vs.tag_text("eleven_flash_v2_5", "Привет!") == "Привет!"


def test_elevenlabs_kk_never_uses_flash():
    p = _eleven(lambda r: httpx.Response(200, content=b"mp3"))
    p.tts_model_kk = "eleven_flash_v2_5"  # ошибка в env
    assert p.tts_model("kk") == "eleven_v3"
    p.tts_models = {"ru": "eleven_turbo_v2_5"}
    assert p.tts_model("ru") == "eleven_turbo_v2_5" and p.tts_model("en") == "eleven_flash_v2_5"


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


# ---------- OpenRouter (MockTransport) ----------


def _openrouter(handler):
    from app.speech.openrouter import OpenRouterSpeechProvider

    client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    return OpenRouterSpeechProvider(
        api_key="test",
        stt_model="google/lite",
        stt_fallback_models=["google/flash"],
        voices={"ru": "voice-ru", "kk": "voice-kk", "en": "voice-en"},
        base_url="https://openrouter.test/api/v1",
        deadline_s=2,
        client=client,
    )


@pytest.mark.parametrize(
    ("lang", "model", "voice"),
    [
        ("kk", "elevenlabs/eleven-v3", "voice-kk"),
        ("ru", "elevenlabs/eleven-flash-v2.5", "voice-ru"),
        ("en", "elevenlabs/eleven-flash-v2.5", "voice-en"),
    ],
)
def test_openrouter_tts_request(lang, model, voice):
    seen = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        return httpx.Response(200, content=b"ID3mp3", headers={"content-type": "audio/mpeg"})

    p = _openrouter(handler)
    assert asyncio.run(p.tts("Сәлем.", lang)) == b"ID3mp3"
    req = seen[0]
    assert str(req.url) == "https://openrouter.test/api/v1/audio/speech"
    assert json.loads(req.content) == {
        "model": model,
        "input": "Сәлем.",
        "voice": voice,
        "response_format": "mp3",
    }
    assert p.voice_id(lang) == f"{voice}:{model}:mp3"


@pytest.mark.parametrize(
    "response",
    [
        httpx.Response(400, json={"error": {"message": "Unknown voice"}}),
        httpx.Response(200, json={"error": "no audio"}),
        httpx.Response(200, content=b"", headers={"content-type": "audio/mpeg"}),
    ],
)
def test_openrouter_tts_errors_are_speech_error(response):
    with pytest.raises(SpeechError):
        asyncio.run(_openrouter(lambda request: response).tts("Привет.", "ru"))


def _chat(content: str) -> httpx.Response:
    return httpx.Response(200, json={"choices": [{"message": {"content": content}}]})


def test_openrouter_stt_request_and_detected_lang():
    seen = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        return _chat('{"lang": "kk", "text": "Асхана қайда?"}')

    res = asyncio.run(_openrouter(handler).stt(b"\x01\x02" * 100, 16000, None))
    assert (res.text, res.lang, res.confidence) == ("Асхана қайда?", "kk", None)
    req = seen[0]
    assert str(req.url) == "https://openrouter.test/api/v1/chat/completions"
    body = json.loads(req.content)
    assert body["model"] == "google/lite"
    assert body["response_format"]["type"] == "json_schema"
    system, user = body["messages"]
    assert "Cyrillic" in system["content"]
    prompt, audio = user["content"]
    assert audio["input_audio"]["format"] == "wav"
    assert base64.b64decode(audio["input_audio"]["data"])[:4] == b"RIFF"
    assert "detect" in prompt["text"]


def test_openrouter_stt_known_lang_and_hallucination_filter():
    seen = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(json.loads(request.content))
        return _chat('{"lang": "en", "text": "00:00"}')

    res = asyncio.run(_openrouter(handler).stt(b"\x01\x02" * 100, 16000, "ru"))
    assert (res.text, res.lang) == ("", "ru")
    assert "Russian" in seen[0]["messages"][1]["content"][0]["text"]


def test_openrouter_stt_falls_back_on_503():
    models = []

    def handler(request: httpx.Request) -> httpx.Response:
        model = json.loads(request.content)["model"]
        models.append(model)
        if model == "google/lite":
            return httpx.Response(503, json={"error": {"message": "overloaded"}})
        return _chat('{"lang": "ru", "text": "Где кафе?"}')

    res = asyncio.run(_openrouter(handler).stt(b"\x01\x02", 16000, None))
    assert res.text == "Где кафе?"
    assert models[-1] == "google/flash"


@pytest.mark.parametrize(
    "response",
    [
        httpx.Response(400, json={"error": {"message": "bad audio"}}),
        httpx.Response(200, json={"choices": [{"message": {"content": "not json"}}]}),
    ],
)
def test_openrouter_stt_errors_are_speech_error(response):
    with pytest.raises(SpeechError):
        asyncio.run(_openrouter(lambda request: response).stt(b"\x01\x02", 16000, None))


def test_openrouter_stt_network_error():
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("down")

    with pytest.raises(SpeechError):
        asyncio.run(_openrouter(handler).stt(b"\x01\x02", 16000, "ru"))


# ---------- запасной TTS ----------


class _BrokenTts:
    name = "broken"

    def voice_id(self, lang):
        return f"broken-{lang}"

    async def tts(self, text, lang):
        raise SpeechError("quota exceeded")


def test_tts_fallback_used_on_primary_error():
    fallback = FakeSpeechProvider()
    tts = FallbackTtsProvider(_BrokenTts(), fallback)
    assert asyncio.run(tts.tts("Привет", "ru")) == SILENT_MP3
    assert fallback.tts_calls == 1
    assert (tts.name, tts.voice_id("kk")) == ("broken", "broken-kk")


class _QuotaTts(_BrokenTts):
    calls = 0

    async def tts(self, text, lang):
        self.calls += 1
        raise SpeechError("elevenlabs tts http 401: quota_exceeded")


def test_tts_primary_paused_after_quota_error():
    """Кончилась квота: следующие фразы идут сразу в запасной, основной не дёргаем PAUSE_S."""
    now = [1000.0]
    primary, fallback = _QuotaTts(), FakeSpeechProvider()
    tts = FallbackTtsProvider(primary, fallback, clock=lambda: now[0])
    assert asyncio.run(tts.tts("Раз", "ru")) == SILENT_MP3
    assert asyncio.run(tts.tts("Два", "ru")) == SILENT_MP3
    assert (primary.calls, fallback.tts_calls, tts.primary_paused()) == (1, 2, True)
    assert b"".join(asyncio.run(_collect(tts.tts_stream("Три", "ru")))) == SILENT_MP3
    assert primary.calls == 1
    now[0] += FallbackTtsProvider.PAUSE_S + 1
    asyncio.run(tts.tts("Четыре", "ru"))
    assert primary.calls == 2  # пауза прошла — снова пробуем основной


async def _collect(stream):
    return [chunk async for chunk in stream]


def test_tts_network_error_does_not_pause_primary():
    primary, fallback = (
        _BrokenTts(),
        FakeSpeechProvider(),
    )  # "quota exceeded" без кода — обычная ошибка
    tts = FallbackTtsProvider(primary, fallback)
    asyncio.run(tts.tts("Раз", "ru"))
    assert tts.primary_paused() is False


def test_tts_fallback_built_from_settings():
    s = Settings(
        _env_file=None,
        robot_token="t",
        tts_provider="elevenlabs",
        tts_fallback_provider="openrouter",
        elevenlabs_api_key="xi",
        openrouter_api_key="o",
    )
    tts = build_tts_provider(s)
    assert isinstance(tts, FallbackTtsProvider)
    assert (tts.primary.name, tts.fallback.name) == ("elevenlabs", "openrouter")
    same = s.model_copy(update={"tts_fallback_provider": "elevenlabs"})
    assert build_tts_provider(same).name == "elevenlabs"
