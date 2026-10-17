"""Эксплуатация: IP клиента в access-логе, Dockerfile и пути данных, Yandex STT lang=null, Sentry."""

from __future__ import annotations

import asyncio
import json
import logging
import re
import time

import httpx
import pytest
from pydantic import SecretStr

from app.client_ip import client_ip, parse_networks
from app.config import BACKEND_DIR, Settings
from app.speech.factory import build_stt_provider
from app.speech.yandex import YandexSpeechKitProvider

TRUSTED = parse_networks("172.16.0.0/12,127.0.0.1")


def _scope(peer: str, xff: str | None = None) -> dict:
    headers = [(b"x-forwarded-for", xff.encode())] if xff is not None else []
    return {"type": "http", "client": (peer, 5000), "headers": headers}


# ---------- IP клиента ----------


def test_client_ip_direct_ignores_spoofed_xff():
    assert client_ip(_scope("203.0.113.7", "1.2.3.4"), TRUSTED) == "203.0.113.7"


def test_client_ip_from_trusted_proxy():
    assert client_ip(_scope("172.18.0.5", "198.51.100.9"), TRUSTED) == "198.51.100.9"
    # клиент подставил свой XFF — берём правый недоверенный адрес (добавленный caddy)
    assert client_ip(_scope("172.18.0.5", "1.1.1.1, 198.51.100.9"), TRUSTED) == "198.51.100.9"
    assert client_ip(_scope("127.0.0.1", "198.51.100.9, 172.18.0.2"), TRUSTED) == "198.51.100.9"


def test_client_ip_trusted_without_xff_and_no_client():
    assert client_ip(_scope("127.0.0.1"), TRUSTED) == "127.0.0.1"
    assert client_ip({"type": "http", "headers": []}, TRUSTED) is None
    assert client_ip(_scope("172.20.0.1", "10.0.0.1"), ()) == "172.20.0.1"


def test_access_log_has_client_ip(client):
    records: list[logging.LogRecord] = []

    class Grab(logging.Handler):
        def emit(self, record):
            records.append(record)

    h = Grab()
    logging.getLogger("hub").addHandler(h)
    try:
        client.get("/v1/health")  # TestClient: peer = "testclient"
        r = client.get(
            "/v1/health",
            headers={"X-Forwarded-For": "8.8.8.8"},
        )
        assert r.status_code == 200
    finally:
        logging.getLogger("hub").removeHandler(h)
    access = [r for r in records if r.getMessage() == "http_request"]
    assert access and all(hasattr(r, "client_ip") for r in access)
    assert all(r.client_ip != "8.8.8.8" for r in access)  # не доверенный peer → XFF игнорируем


# ---------- Dockerfile и пути данных ----------


def test_dockerfile_data_volume_and_trusted_proxies():
    df = (BACKEND_DIR / "Dockerfile").read_text(encoding="utf-8")
    assert "mkdir -p /data && chown app:app /data" in df
    assert "DATABASE_URL=sqlite:////data/" in df
    assert "KNOWLEDGE_SNAPSHOT_PATH=/data/" in df
    assert "TTS_CACHE_DIR=/data/" in df
    assert not re.search(r"forwarded-allow-ips[\"', ]+\*", df)
    assert '--forwarded-allow-ips \\"$TRUSTED_PROXIES\\"' in df


def test_local_defaults_stay_in_backend_data():
    s = Settings(_env_file=None)
    assert s.knowledge_snapshot_path == BACKEND_DIR / "data" / "knowledge_snapshot.json"
    assert s.tts_cache_dir == BACKEND_DIR / "data" / "tts_cache"
    assert s.database_url == f"sqlite:///{BACKEND_DIR / 'data' / 'questions.db'}"
    assert s.question_log_retention_days == 180
    assert s.trusted_proxies == "172.16.0.0/12,127.0.0.1"
    assert s.sentry_dsn is None


def test_httpx_is_runtime_dependency():
    import tomllib

    py = tomllib.loads((BACKEND_DIR / "pyproject.toml").read_text(encoding="utf-8"))
    deps = py["project"]["dependencies"]
    assert any(d.startswith("httpx") for d in deps)
    assert any(d.startswith("sentry-sdk") for d in deps)


# ---------- Yandex STT, lang=null ----------


def _yandex(handler, per_lang: float) -> YandexSpeechKitProvider:
    client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    return YandexSpeechKitProvider(
        api_key="test",
        folder_id=None,
        stt_url="https://stt.example/stt",
        tts_url="https://tts.example/tts",
        voices={"ru": "jane", "kk": "amira", "en": "john"},
        client=client,
        auto_lang_timeout_s=per_lang,
    )


def _delayed(delays: dict[str, float], texts: dict[str, str]):
    async def handler(req: httpx.Request) -> httpx.Response:
        lang = req.url.params["lang"][:2]
        await asyncio.sleep(delays.get(lang, 0))
        return httpx.Response(200, json={"result": texts.get(lang, "")})

    return handler


def test_yandex_auto_chooses_from_languages_that_answered_in_time():
    handler = _delayed(
        {"ru": 0.01, "kk": 5.0, "en": 5.0}, {"ru": "Где кафе", "kk": "Кафе қайда", "en": "x"}
    )
    p = _yandex(handler, per_lang=0.2)
    t0 = time.perf_counter()
    res = asyncio.run(p.stt(b"\x00\x01" * 100, 16000, None))
    assert time.perf_counter() - t0 < 1.0
    assert (res.text, res.lang) == ("Где кафе", "ru")


def test_yandex_auto_all_slow_is_timeout():
    p = _yandex(_delayed({"ru": 5, "kk": 5, "en": 5}, {}), per_lang=0.1)
    with pytest.raises(TimeoutError):
        asyncio.run(p.stt(b"\x00\x01" * 100, 16000, None))


def test_yandex_auto_lang_timeout_within_stt_timeout():
    s = Settings(_env_file=None, stt_provider="yandex", yandex_api_key="k", stt_timeout_s=2.0)
    p = build_stt_provider(s)
    assert p.auto_lang_timeout_s == 2.0  # type: ignore[attr-defined]
    s = Settings(_env_file=None, stt_provider="yandex", yandex_api_key="k", stt_timeout_s=5.0)
    assert build_stt_provider(s).auto_lang_timeout_s == 3.0  # type: ignore[attr-defined]


def test_stt_endpoint_auto_lang_timeout_is_504(make_client):
    from app.speech.wav import make_wav
    from tests.conftest import SESSION
    from tests.test_speech import _stt, tone

    p = _yandex(_delayed({"ru": 5, "kk": 5, "en": 5}, {}), per_lang=0.1)
    c = make_client(speech=p)
    r = _stt(c, make_wav(tone(0.5), 16000), {"session_id": SESSION})
    assert r.status_code == 504


# ---------- Sentry ----------


def test_sentry_not_initialized_without_dsn(monkeypatch):
    import sentry_sdk

    from app.sentry_setup import init_sentry

    calls = []
    monkeypatch.setattr(sentry_sdk, "init", lambda **kw: calls.append(kw))
    assert init_sentry(Settings(_env_file=None)) is False
    assert init_sentry(Settings(_env_file=None, sentry_dsn="  ")) is False
    assert calls == []


def test_sentry_initialized_without_pii(monkeypatch, settings):
    import sentry_sdk

    from app.llm.fake import FakeProvider
    from app.main import create_app

    calls = []
    monkeypatch.setattr(sentry_sdk, "init", lambda **kw: calls.append(kw))
    create_app(
        settings.model_copy(update={"sentry_dsn": SecretStr("https://key@o0.ingest.sentry.io/1")}),
        FakeProvider(),
        FakeProvider(),
    )
    (kw,) = calls
    assert kw["dsn"] == "https://key@o0.ingest.sentry.io/1"
    assert kw["send_default_pii"] is False
    assert kw["include_local_variables"] is False
    assert kw["max_request_body_size"] == "never"
    assert kw["before_send"] and kw["before_breadcrumb"]


def test_sentry_scrubs_question_and_answer_texts():
    from app.sentry_setup import FILTERED, before_breadcrumb, before_send

    event = {
        "request": {
            "url": "https://hub/v1/ask",
            "data": {"question": "Где кафе?"},
            "headers": {"Authorization": "Bearer t", "User-Agent": "okhttp"},
        },
        "extra": {"question": "Где кафе?", "answer": "На 1 этаже.", "latency_ms": 5},
        "logentry": {"message": "%s", "params": ["Где кафе?"]},
        "breadcrumbs": {
            "values": [
                {"message": "ask_answered", "data": {"question": "Где кафе?", "found": True}}
            ]
        },
        "user": {"ip_address": "1.2.3.4"},
    }
    out = before_send(event, {})
    blob = json.dumps(out, ensure_ascii=False)
    assert "Где кафе" not in blob and "На 1 этаже" not in blob and "Bearer" not in blob
    assert out["extra"]["latency_ms"] == 5
    assert out["breadcrumbs"]["values"][0]["data"] == {"question": FILTERED, "found": True}
    assert "user" not in out and "data" not in out["request"]
    crumb = before_breadcrumb({"data": {"phrases": ["a"], "text": "b", "status": 200}})
    assert crumb["data"] == {"phrases": FILTERED, "text": FILTERED, "status": 200}
