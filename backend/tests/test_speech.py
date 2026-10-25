from __future__ import annotations

import asyncio
import json
import logging
import math
import struct

import httpx
import pytest

from app.speech.cache import TtsCache, cache_key
from app.speech.fake import SILENT_MP3, FakeSpeechProvider
from app.speech.wav import make_wav, parse_wav
from app.speech.yandex import YandexSpeechKitProvider, choose_hypothesis
from tests.conftest import AUTH, SESSION


def tone(seconds: float, rate: int = 16000, amp: int = 8000) -> bytes:
    n = int(seconds * rate)
    return b"".join(
        struct.pack("<h", int(amp * math.sin(2 * math.pi * 440 * i / rate))) for i in range(n)
    )


def silence(seconds: float, rate: int = 16000) -> bytes:
    return b"\x00\x00" * int(seconds * rate)


def _stt(client, audio: bytes | None, meta: dict | str | None, headers=AUTH):
    files = {"audio": ("q.wav", audio, "audio/wav")} if audio is not None else None
    data = None
    if meta is not None:
        data = {"meta": meta if isinstance(meta, str) else json.dumps(meta)}
    return client.post("/v1/stt", files=files, data=data, headers=headers)


# ---------- /v1/stt ----------


@pytest.mark.parametrize(
    ("lang", "text"),
    [("kk", "Кафе қайда?"), ("ru", "Где можно поесть?"), ("en", "Where can I eat?")],
)
def test_stt_lang(client, lang, text):
    r = _stt(client, make_wav(tone(1.0)), {"lang": lang, "session_id": SESSION})
    assert r.status_code == 200, r.text
    body = r.json()
    assert body == {
        "request_id": r.headers["x-request-id"],
        "text": text,
        "lang": lang,
        "confidence": 1.0,
    }


def test_stt_null_lang_defaults_to_ru_in_fake(client):
    r = _stt(client, make_wav(tone(0.5)), {"lang": None, "session_id": SESSION})
    assert r.json()["lang"] == "ru"


def test_stt_silence_returns_empty_text(client):
    r = _stt(client, make_wav(silence(1.0)), {"lang": "kk", "session_id": SESSION})
    assert r.status_code == 200
    assert r.json()["text"] == ""


def test_stt_requires_token(client):
    r = _stt(client, make_wav(tone(0.5)), {"session_id": SESSION}, headers={})
    assert r.status_code == 401


@pytest.mark.parametrize(
    "audio",
    [
        b"not a wav at all" * 10,
        make_wav(tone(0.5), sample_rate=8000),
        make_wav(tone(0.5) * 2, channels=2),
        make_wav(b"\x10" * 8000, width=1),
        make_wav(tone(0.5))[:30],
    ],
    ids=["garbage", "8khz", "stereo", "8bit", "truncated"],
)
def test_stt_bad_wav(client, audio):
    r = _stt(client, audio, {"session_id": SESSION})
    assert r.status_code == 400
    assert r.json()["error"]["code"] == "bad_request"


def test_stt_too_long(client):
    # 16 с тишины = 512 КБ < 600 КБ, но длиннее 15 с
    r = _stt(client, make_wav(silence(16)), {"session_id": SESSION})
    assert r.status_code == 413
    assert r.json()["error"]["code"] == "payload_too_large"


def test_stt_too_large(client):
    r = _stt(client, make_wav(silence(20)), {"session_id": SESSION})
    assert r.status_code == 413


def test_stt_bad_meta_and_parts(client):
    wav = make_wav(tone(0.3))
    assert _stt(client, wav, None).status_code == 400
    assert _stt(client, None, {"session_id": SESSION}).status_code == 400
    assert _stt(client, wav, "{oops").status_code == 400
    assert _stt(client, wav, {"lang": "de", "session_id": SESSION}).status_code == 400
    assert _stt(client, wav, {"lang": "ru"}).status_code == 400
    r = client.post("/v1/stt", json={"session_id": SESSION}, headers=AUTH)
    assert r.status_code == 400


def test_stt_provider_down(make_client):
    c = make_client(speech=FakeSpeechProvider(fail=True))
    r = _stt(c, make_wav(tone(0.5)), {"lang": "ru", "session_id": SESSION})
    assert r.status_code == 503
    assert r.json()["error"]["code"] == "speech_unavailable"


def test_stt_audio_not_logged(client, caplog):
    marker = b"AUDIO-SECRET-MARKER!" * 50
    with caplog.at_level(logging.DEBUG):
        r = _stt(client, make_wav(marker), {"lang": "ru", "session_id": SESSION})
    assert r.status_code == 200
    rec = next(r for r in caplog.records if r.getMessage() == "stt_recognized")
    assert rec.audio_s > 0 and rec.lang == "ru"
    for record in caplog.records:
        assert "AUDIO-SECRET-MARKER" not in repr(record.__dict__)


# ---------- /v1/tts ----------


def test_tts_returns_mp3_and_caches(make_client, tmp_path):
    speech = FakeSpeechProvider()
    c = make_client(speech=speech)
    body = {"text": "Асхана бірінші қабатта.", "lang": "kk"}
    r1 = c.post("/v1/tts", json=body, headers=AUTH)
    assert r1.status_code == 200
    assert r1.headers["content-type"] == "audio/mpeg"
    assert r1.headers["x-request-id"].startswith("req_")
    assert r1.headers["x-cache"] == "miss"
    assert r1.content[:2] == b"\xff\xfb"
    r2 = c.post("/v1/tts", json=body, headers=AUTH)
    assert r2.headers["x-cache"] == "hit"
    assert r2.content == r1.content
    assert speech.tts_calls == 1
    # другой язык — другой ключ
    c.post("/v1/tts", json={"text": "Кафе рядом.", "lang": "kk"}, headers=AUTH)
    c.post("/v1/tts", json={"text": "Кафе рядом.", "lang": "ru"}, headers=AUTH)
    assert speech.tts_calls == 3
    assert list((tmp_path / "tts_cache").glob("*.mp3"))


class RecordingSpeech(FakeSpeechProvider):
    def __init__(self) -> None:
        super().__init__()
        self.calls: list[tuple[str, str]] = []

    async def tts(self, text: str, lang: str) -> bytes:
        self.calls.append((text, lang))
        return await super().tts(text, lang)


def test_tts_kazakh_text_never_goes_to_another_voice_and_is_normalized(make_client):
    speech = RecordingSpeech()
    c = make_client(speech=speech)
    r = c.post("/v1/tts", json={"text": "Хм... Кафе 9:00-ден ашық.", "lang": "ru"}, headers=AUTH)
    assert r.status_code == 200
    r = c.post("/v1/tts", json={"text": "Ммм, кафе с 9:00 до 18:00.", "lang": "ru"}, headers=AUTH)
    assert r.status_code == 200
    assert speech.calls == [
        ("Кафе тоғыздан ашық.", "kk"),
        ("Кафе с девяти до восемнадцати.", "ru"),
    ]


def test_tts_lang_detected_when_missing(make_client):
    c = make_client()
    r = c.post("/v1/tts", json={"text": "Hello there."}, headers=AUTH)
    assert r.status_code == 200


@pytest.mark.parametrize(
    "payload",
    [
        {"text": "", "lang": "ru"},
        {"text": "   ", "lang": "ru"},
        {"text": "x" * 301, "lang": "ru"},
        {"text": "a", "lang": "de"},
        {},
    ],
)
def test_tts_validation(client, payload):
    r = client.post("/v1/tts", json=payload, headers=AUTH)
    assert r.status_code == 400


def test_tts_requires_token(client):
    assert client.post("/v1/tts", json={"text": "a", "lang": "ru"}).status_code == 401


def test_tts_provider_down(make_client):
    c = make_client(speech=FakeSpeechProvider(fail=True))
    r = c.post("/v1/tts", json={"text": "Привет.", "lang": "ru"}, headers=AUTH)
    assert r.status_code == 503
    assert r.json()["error"]["code"] == "speech_unavailable"


def test_speech_rate_limit_is_separate(make_client):
    c = make_client(rate_limit_per_min=1, speech_rate_limit_per_min=2)
    for _ in range(2):
        assert c.post("/v1/tts", json={"text": "a", "lang": "ru"}, headers=AUTH).status_code == 200
    assert c.post("/v1/tts", json={"text": "a", "lang": "ru"}, headers=AUTH).status_code == 429
    # бакет /ask не тронут
    r = c.post("/v1/ask", json={"question": "Где кафе?", "session_id": "s"}, headers=AUTH)
    assert r.status_code == 200


# ---------- кеш ----------


def test_cache_memory_lru_limit():
    cache = TtsCache(memory_bytes=10)
    cache.put("a", b"12345")
    cache.put("b", b"12345")
    assert cache.get("a") == b"12345"  # a свежее b
    cache.put("c", b"12345")
    assert cache.get("b") is None
    assert cache.get("a") is not None and cache.get("c") is not None
    cache.put("big", b"x" * 11)
    assert cache.get("big") is None


def test_cache_disk_limit_and_reload(tmp_path):
    d = tmp_path / "c"
    cache = TtsCache(memory_bytes=0, disk_dir=d, disk_bytes=25)
    for i in range(5):
        cache.put(f"k{i}", b"0123456789")
    total = sum(p.stat().st_size for p in d.glob("*.mp3"))
    assert total <= 25
    fresh = TtsCache(memory_bytes=100, disk_dir=d, disk_bytes=25)
    assert fresh.get("k4") == b"0123456789"


def test_cache_key_depends_on_voice_and_lang():
    assert cache_key("y", "jane", "ru", "a") != cache_key("y", "filipp", "ru", "a")
    assert cache_key("y", "jane", "ru", "a") != cache_key("y", "jane", "kk", "a")


# ---------- WAV / fake ----------


def test_parse_wav_duration():
    audio = parse_wav(make_wav(silence(2.5)))
    assert audio.sample_rate == 16000
    assert audio.duration_s == pytest.approx(2.5)


def test_silent_mp3_frames():
    assert len(SILENT_MP3) == 417 * 10
    assert SILENT_MP3[:4] == b"\xff\xfb\x90\xc4"


# ---------- Yandex (без сети, через MockTransport) ----------


def _yandex(handler) -> YandexSpeechKitProvider:
    client = httpx.AsyncClient(
        transport=httpx.MockTransport(handler), headers={"Authorization": "Api-Key test"}
    )
    return YandexSpeechKitProvider(
        api_key="test",
        folder_id="b1gfolder",
        stt_url="https://stt.example/speech/v1/stt:recognize",
        tts_url="https://tts.example/speech/v1/tts:synthesize",
        voices={"ru": "jane", "kk": "amira", "en": "john"},
        client=client,
    )


def test_yandex_stt_request_format():
    seen = []

    def handler(req: httpx.Request) -> httpx.Response:
        seen.append(req)
        return httpx.Response(200, json={"result": "Кафе қайда"})

    res = asyncio.run(_yandex(handler).stt(b"\x01\x02" * 100, 16000, "kk"))
    assert res.text == "Кафе қайда" and res.lang == "kk"
    req = seen[0]
    assert req.headers["authorization"] == "Api-Key test"
    assert req.url.params["lang"] == "kk-KZ"
    assert req.url.params["format"] == "lpcm"
    assert req.url.params["sampleRateHertz"] == "16000"
    assert req.url.params["folderId"] == "b1gfolder"
    assert req.content == b"\x01\x02" * 100  # PCM без WAV-заголовка


def test_yandex_stt_auto_picks_kazakh():
    answers = {"ru-RU": "кафе кайда", "kk-KZ": "Кафе қайда", "en-US": "cafe kaida"}

    def handler(req: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"result": answers[req.url.params["lang"]]})

    res = asyncio.run(_yandex(handler).stt(b"\x00\x01" * 10, 16000, None))
    assert (res.text, res.lang) == ("Кафе қайда", "kk")


def test_yandex_stt_auto_survives_partial_failure():
    def handler(req: httpx.Request) -> httpx.Response:
        if req.url.params["lang"] == "ru-RU":
            return httpx.Response(200, json={"result": "Где кафе"})
        return httpx.Response(500, text="oops")

    res = asyncio.run(_yandex(handler).stt(b"\x00\x01", 16000, None))
    assert (res.text, res.lang) == ("Где кафе", "ru")


def test_yandex_stt_error_is_speech_error():
    from app.speech.base import SpeechError

    def handler(req: httpx.Request) -> httpx.Response:
        return httpx.Response(401, json={"error_message": "bad key"})

    with pytest.raises(SpeechError):
        asyncio.run(_yandex(handler).stt(b"\x00\x01", 16000, "ru"))


def test_yandex_tts_request_format():
    seen = []

    def handler(req: httpx.Request) -> httpx.Response:
        seen.append(req)
        return httpx.Response(200, content=SILENT_MP3)

    audio = asyncio.run(_yandex(handler).tts("Асхана бірінші қабатта.", "kk"))
    assert audio == SILENT_MP3
    form = dict(httpx.QueryParams(seen[0].content.decode()))
    assert form == {
        "text": "Асхана бірінші қабатта.",
        "lang": "kk-KK",
        "voice": "amira",
        "format": "mp3",
        "folderId": "b1gfolder",
    }


@pytest.mark.parametrize(
    ("texts", "expected"),
    [
        ({"ru": "где кафе", "kk": "где кафе", "en": "gde kafe"}, ("где кафе", "ru")),
        ({"ru": "вере ис зе кафе", "kk": "", "en": "where is the cafe"}, ("вере ис зе кафе", "ru")),
        ({"ru": "", "kk": "", "en": "where is the cafe"}, ("where is the cafe", "en")),
        ({"ru": "", "kk": "", "en": ""}, ("", "ru")),
    ],
)
def test_choose_hypothesis(texts, expected):
    r = choose_hypothesis(texts)
    assert (r.text, r.lang) == expected
