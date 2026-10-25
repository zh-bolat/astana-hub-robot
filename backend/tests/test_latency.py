"""Мониторинг задержек: latency_events (миграция 0003), запись этапов без текстов,
POST /v1/metrics/turn, GET /v1/admin/metrics(/page), потоковый /v1/tts?stream=true."""

from __future__ import annotations

import asyncio
import json
from datetime import UTC, datetime, timedelta

import httpx
import pytest
from alembic import command
from sqlalchemy import create_engine, inspect, select, text
from sqlalchemy.orm import Session

from app.db import LatencyRepo
from app.db.migrate import alembic_config, current_revision, head_revision, upgrade_head
from app.db.models import LatencyEvent
from app.lang import Lang
from app.speech.base import SpeechError
from app.speech.cache import cache_key
from app.speech.elevenlabs import VoiceSettings
from app.speech.wav import make_wav
from tests.conftest import AUTH, SESSION, TOKEN, ask_body, temp_database
from tests.test_chat import FakeModel, call, say
from tests.test_speech import tone
from tests.test_speech_providers import _eleven

ADMIN = "admin-secret-token"
ADMIN_AUTH = {"Authorization": f"Bearer {ADMIN}"}


def rows(client, kind: str | None = None) -> list[LatencyEvent]:
    assert client.app.state.log_writer.wait_idle(5)
    stmt = select(LatencyEvent).order_by(LatencyEvent.id)
    if kind:
        stmt = stmt.where(LatencyEvent.kind == kind)
    with Session(client.app.state.engine) as s:
        return list(s.scalars(stmt))


def stages(items: list[LatencyEvent]) -> dict[str, LatencyEvent]:
    return {r.stage: r for r in items}


def dump(items: list[LatencyEvent]) -> str:
    """Все значения строк одной строкой — проверить, что текстов посетителя там нет."""
    return json.dumps(
        [
            [r.kind, r.stage, r.ms, r.lang, r.provider, r.request_id, r.session_id, r.extra]
            for r in items
        ],
        ensure_ascii=False,
    )


# ---------- миграция 0003 ----------


def test_migration_0003_up_down():
    with temp_database() as url:
        upgrade_head(url)
        engine = create_engine(url)
        try:
            insp = inspect(engine)
            assert "latency_events" in insp.get_table_names()
            idx = {i["name"]: i["column_names"] for i in insp.get_indexes("latency_events")}
            assert idx["ix_latency_events_ts"] == ["ts"]
            assert idx["ix_latency_events_kind_ts"] == ["kind", "ts"]

            command.downgrade(alembic_config(url), "0002_question_log_interrupted")
            assert current_revision(url) == "0002_question_log_interrupted"
            assert "latency_events" not in inspect(engine).get_table_names()

            upgrade_head(url)
            assert current_revision(url) == head_revision()
            LatencyRepo(engine).add_many([{"kind": "tts", "stage": "tts_total_ms", "ms": 5}])
            with engine.connect() as conn:
                row = conn.execute(text("SELECT ok, extra FROM latency_events")).one()
            assert row.ok is True and row.extra == {}
        finally:
            engine.dispose()


def test_retention_purges_latency_events(client):
    repo: LatencyRepo = client.app.state.latency_repo
    old = datetime.now(UTC) - timedelta(days=200)
    repo.add_many(
        [
            {"kind": "tts", "stage": "tts_total_ms", "ms": 1, "ts": old},
            {"kind": "tts", "stage": "tts_total_ms", "ms": 2},
        ]
    )
    client.app.state.log_repo.purge_older_than(180)
    assert [r.ms for r in rows(client)] == [2]


# ---------- запись этапов сервера ----------


def test_stt_stage_recorded_without_text(client):
    files = {"audio": ("q.wav", make_wav(tone(1.0)), "audio/wav")}
    meta = json.dumps({"lang": "ru", "session_id": SESSION})
    r = client.post("/v1/stt", files=files, data={"meta": meta}, headers=AUTH)
    assert r.status_code == 200
    recognized = r.json()["text"]
    assert recognized

    items = rows(client, "stt")
    assert [i.stage for i in items] == ["stt_ms"]
    row = items[0]
    assert row.ms is not None and row.ms >= 0
    assert (row.lang, row.provider, row.session_id, row.ok) == ("ru", "fake", SESSION, True)
    assert row.request_id == r.headers["x-request-id"]
    assert row.extra["audio_s"] == 1.0
    assert recognized not in dump(items)


def test_ask_json_and_sse_stages_without_text(client):
    question = "Какой пароль от Wi-Fi?"
    r = client.post("/v1/ask", json=ask_body(question, lang="ru"), headers=AUTH)
    assert r.status_code == 200
    answer = r.json()["answer"]
    items = rows(client, "ask")
    assert [i.stage for i in items] == ["llm_total_ms"]
    assert items[0].provider == "fake:fake"
    assert items[0].extra["stream"] is False

    sse = {**AUTH, "Accept": "text/event-stream"}
    with client.stream("POST", "/v1/ask", json=ask_body(question, lang="ru"), headers=sse) as s:
        assert s.status_code == 200
        "".join(s.iter_text())
    items = rows(client, "ask")
    streamed = stages(items[1:])
    assert set(streamed) == {"llm_first_token_ms", "first_phrase_ms", "llm_total_ms"}
    assert streamed["llm_first_token_ms"].ms <= streamed["llm_total_ms"].ms
    assert streamed["llm_total_ms"].extra["stream"] is True
    assert streamed["llm_total_ms"].extra["interrupted"] is False
    text_dump = dump(items)
    assert question not in text_dump and answer not in text_dump


def test_ask_error_recorded_as_not_ok(make_client):
    from tests.conftest import FailingProvider

    c = make_client(text_llm=FailingProvider())
    assert c.post("/v1/ask", json=ask_body("Где кафе?"), headers=AUTH).status_code == 503
    (row,) = rows(c, "ask")
    assert row.ok is False and row.extra["error"] == "llm_unavailable"


def test_chat_agent_stages_with_action(make_client):
    model = FakeModel(
        call("guide_to", {"place_id": "place:cafe"}),
        say("[happy] Провожаю вас в кафе, идите за мной."),
    )
    c = make_client(chat_client=model.client())
    question = "Проводи меня в кафе"
    r = c.post("/v1/chat", json=ask_body(question, lang="ru"), headers=AUTH)
    assert r.status_code == 200
    items = rows(c, "chat")
    by = stages(items)
    assert set(by) == {"llm_first_token_ms", "first_phrase_ms", "llm_total_ms"}
    total = by["llm_total_ms"]
    assert total.provider == "openrouter:anthropic/claude-haiku-5.5"
    assert by["llm_first_token_ms"].ms <= by["first_phrase_ms"].ms <= total.ms
    assert total.extra["action"] is True
    assert total.extra["fallback"] is False
    assert total.extra["tools"] == ["guide_to"]
    assert total.lang == "ru" and total.session_id == SESSION and total.ok
    assert question not in dump(items) and "Провожаю" not in dump(items)


def test_chat_agent_failure_marks_fallback(make_client):
    c = make_client(chat_client=FakeModel(status=500).client())
    r = c.post("/v1/chat", json=ask_body("Расскажи о хабе", lang="ru"), headers=AUTH)
    assert r.status_code == 200
    by = stages(rows(c, "chat"))
    assert by["llm_total_ms"].extra["fallback"] is True
    assert "first_phrase_ms" in by
    assert "llm_first_token_ms" not in by  # модель не ответила ни токеном
    # ответ дал /ask — его этапы тоже записаны
    assert rows(c, "ask")


def test_tts_stages_cache_miss_then_hit(client):
    body = {"text": "Кафе на первом этаже.", "lang": "ru"}
    assert client.post("/v1/tts", json=body, headers=AUTH).headers["x-cache"] == "miss"
    assert client.post("/v1/tts", json=body, headers=AUTH).headers["x-cache"] == "hit"
    items = rows(client, "tts")
    assert [(i.stage, i.extra["cache"]) for i in items] == [
        ("tts_first_byte_ms", "miss"),
        ("tts_total_ms", "miss"),
        ("tts_first_byte_ms", "hit"),
        ("tts_total_ms", "hit"),
    ]
    assert all(i.provider == "fake" and i.lang == "ru" for i in items)
    assert items[0].extra["chars"] == len(body["text"])
    assert body["text"] not in dump(items)


def test_metrics_disabled_when_log_repo_is_replaced(settings):
    """eval/openapi подменяют лог вопросов — тайминги тогда не пишутся (БД может не быть)."""
    from app.llm.fake import FakeProvider
    from app.main import create_app

    class NullLog:
        def add(self, **_):
            pass

    app = create_app(
        settings, FakeProvider(), FakeProvider(), log_repo=NullLog(), knowledge_source=None
    )
    try:
        assert app.state.metrics.enabled is False
    finally:
        app.state.engine.dispose()


# ---------- POST /v1/metrics/turn ----------

TURN = {
    "session_id": SESSION,
    "request_id": "req_abc",
    "lang": "ru",
    "mode": "agent",
    "stt_final_ms": 420,
    "chat_first_event_ms": 1350,
    "first_phrase_ms": 1400,
    "tts_first_byte_ms": 1650,
    "first_audio_ms": 1820,
    "vad_silence_ms": 500,
    "fallback": False,
    "interrupted": False,
}


def test_turn_metrics_204_and_rows(client):
    r = client.post("/v1/metrics/turn", json={**TURN, "question": "секрет"}, headers=AUTH)
    assert r.status_code == 204
    assert r.content == b""
    items = rows(client, "robot")
    by = stages(items)
    assert set(by) == {
        "stt_final_ms",
        "chat_first_event_ms",
        "first_phrase_ms",
        "tts_first_byte_ms",
        "first_audio_ms",
        "vad_silence_ms",
        "turn",
    }
    assert by["first_audio_ms"].ms == 1820
    assert by["turn"].ms is None
    assert by["turn"].extra == {"mode": "agent", "fallback": False, "interrupted": False}
    assert all(i.request_id == "req_abc" and i.provider == "agent" for i in items)
    assert "секрет" not in dump(items)  # незнакомые поля не сохраняются


def test_turn_metrics_optional_fields(client):
    body = {"session_id": SESSION, "mode": "local", "first_audio_ms": None, "fallback": True}
    assert client.post("/v1/metrics/turn", json=body, headers=AUTH).status_code == 204
    items = rows(client, "robot")
    assert [i.stage for i in items] == ["turn"]
    assert items[0].request_id is None and items[0].extra["fallback"] is True


@pytest.mark.parametrize(
    "patch",
    [
        {"mode": "voice"},
        {"stt_final_ms": -1},
        {"first_audio_ms": 10**7},
        {"first_audio_ms": "fast"},
        {"lang": "de"},
        {"session_id": ""},
        {"session_id": "x" * 65},
    ],
)
def test_turn_metrics_validation(client, patch):
    r = client.post("/v1/metrics/turn", json={**TURN, **patch}, headers=AUTH)
    assert r.status_code == 400
    assert r.json()["error"]["code"] == "bad_request"
    assert rows(client, "robot") == []


def test_turn_metrics_requires_session_and_mode(client):
    for missing in ("session_id", "mode"):
        body = {k: v for k, v in TURN.items() if k != missing}
        assert client.post("/v1/metrics/turn", json=body, headers=AUTH).status_code == 400


def test_turn_metrics_requires_token(client):
    assert client.post("/v1/metrics/turn", json=TURN).status_code == 401
    bad = {"Authorization": "Bearer wrong"}
    assert client.post("/v1/metrics/turn", json=TURN, headers=bad).status_code == 401


def test_turn_metrics_body_limit_and_shared_rate_limit(make_client):
    c = make_client(speech_rate_limit_per_min=2)
    big = {**TURN, "padding": "x" * 5000}
    assert c.post("/v1/metrics/turn", json=big, headers=AUTH).status_code == 413
    assert c.post("/v1/tts", json={"text": "a", "lang": "ru"}, headers=AUTH).status_code == 200
    assert c.post("/v1/metrics/turn", json=TURN, headers=AUTH).status_code == 204
    r = c.post("/v1/metrics/turn", json=TURN, headers=AUTH)
    assert r.status_code == 429 and "retry-after" in r.headers


# ---------- GET /v1/admin/metrics ----------


def test_admin_metrics_404_without_admin_token(client):
    assert client.get("/v1/admin/metrics", headers=ADMIN_AUTH).status_code == 404
    assert client.get("/v1/admin/metrics/page").status_code == 404
    # и токен робота туда не пускает
    assert client.get("/v1/admin/metrics", headers=AUTH).status_code == 404


def test_admin_metrics_401_with_wrong_token(make_client):
    c = make_client(admin_token=ADMIN)
    assert c.get("/v1/admin/metrics").status_code == 401
    assert c.get("/v1/admin/metrics", headers=AUTH).status_code == 401  # токен робота — не админ
    r = c.get("/v1/admin/metrics", headers={"Authorization": f"Bearer {ADMIN}x"})
    assert r.status_code == 401 and r.json()["error"]["code"] == "unauthorized"


def test_admin_metrics_percentiles_on_synthetic_data(make_client):
    c = make_client(admin_token=ADMIN)
    repo: LatencyRepo = c.app.state.latency_repo
    now = datetime.now(UTC)
    synthetic = []
    for i, ms in enumerate(range(10, 101, 10)):  # 10..100: p50 = 55, p95 = 95.5
        synthetic.append(
            {
                "kind": "chat",
                "stage": "llm_total_ms",
                "ms": ms,
                "lang": "ru" if i < 5 else "kk",
                "provider": "model-a" if i % 2 == 0 else "model-b",
                "ok": i != 9,
                "extra": {"fallback": i < 2},
                "ts": now - timedelta(minutes=5),
            }
        )
    synthetic.append(  # вне окна — не учитывается
        {"kind": "chat", "stage": "llm_total_ms", "ms": 99999, "ts": now - timedelta(hours=30)}
    )
    synthetic += [
        {"kind": "robot", "stage": "turn", "ms": None, "extra": {"fallback": True}},
        {"kind": "robot", "stage": "turn", "ms": None, "extra": {"fallback": False}},
        {"kind": "robot", "stage": "first_audio_ms", "ms": 1800},
    ]
    repo.add_many(synthetic)

    r = c.get("/v1/admin/metrics?hours=24", headers=ADMIN_AUTH)
    assert r.status_code == 200
    data = r.json()
    assert data["hours"] == 24
    chat = next(s for s in data["stages"] if s["kind"] == "chat")
    # percentile_cont (линейная интерполяция): p50 = 55, p95 = 95.5 → округление до целых мс
    assert chat.pop("p95") in (95, 96)
    assert chat == {"kind": "chat", "stage": "llm_total_ms", "count": 10, "errors": 1, "p50": 55}
    # строки хода без ms (stage=turn) в перцентили не попадают
    assert {(s["kind"], s["stage"]) for s in data["stages"]} == {
        ("chat", "llm_total_ms"),
        ("robot", "first_audio_ms"),
    }
    by_model = {p["provider"]: p for p in data["by_provider"] if p["stage"] == "llm_total_ms"}
    assert by_model["model-a"]["count"] == 5 and by_model["model-a"]["p50"] == 50
    assert by_model["model-b"]["p50"] == 60
    by_lang = {p["lang"]: p for p in data["by_lang"] if p["kind"] == "chat"}
    assert by_lang["ru"]["p50"] == 30 and by_lang["kk"]["p50"] == 80
    fallback = {f["kind"]: f for f in data["fallback"]}
    assert fallback["chat"]["turns"] == 10 and fallback["chat"]["fallbacks"] == 2
    assert fallback["chat"]["fallback_share"] == 0.2
    assert fallback["robot"]["fallback_share"] == 0.5
    hourly = [h for h in data["hourly"] if h["kind"] == "chat"]
    assert sum(h["count"] for h in hourly) == 10

    wide = c.get("/v1/admin/metrics?hours=48", headers=ADMIN_AUTH).json()
    assert next(s for s in wide["stages"] if s["kind"] == "chat")["count"] == 11
    assert c.get("/v1/admin/metrics?hours=0", headers=ADMIN_AUTH).status_code == 400


def test_admin_metrics_empty(make_client):
    c = make_client(admin_token=ADMIN)
    data = c.get("/v1/admin/metrics", headers=ADMIN_AUTH).json()
    assert data["stages"] == [] and data["fallback"] == [] and data["hourly"] == []


def test_admin_metrics_page_served_without_auth(make_client):
    c = make_client(admin_token=ADMIN)
    r = c.get("/v1/admin/metrics/page")
    assert r.status_code == 200
    assert r.headers["content-type"].startswith("text/html")
    html = r.text
    assert "sessionStorage" in html and "Authorization" in html
    assert "http://" not in html and "https://" not in html  # без внешних ресурсов
    assert ADMIN not in html
    csp = r.headers["content-security-policy"]
    assert "default-src 'none'" in csp and "connect-src 'self'" in csp
    assert "script-src 'sha256-" in csp and "unsafe-inline" not in csp
    assert r.headers["cache-control"] == "no-store"


# ---------- потоковый TTS ----------

CHUNKS = [b"ID3-first", b"-second", b"-third"]


class StreamingTts:
    """Фейковый потоковый TTS: первый кусок, затем (по сигналу gate) остальные."""

    name = "elevenlabs"

    def __init__(
        self,
        chunks: list[bytes] = CHUNKS,
        fail_before: bool = False,
        fail_after_first: bool = False,
        first_delay_s: float = 0.0,
    ) -> None:
        self.chunks = chunks
        self.fail_before = fail_before
        self.fail_after_first = fail_after_first
        self.first_delay_s = first_delay_s
        self.gate: asyncio.Event | None = None
        self.stream_calls = 0
        self.tts_calls = 0

    def voice_id(self, lang: Lang) -> str:
        return f"voice-{lang}"

    async def tts(self, text: str, lang: Lang) -> bytes:
        self.tts_calls += 1
        return b"".join(self.chunks)

    async def tts_stream(self, text: str, lang: Lang):
        self.stream_calls += 1
        if self.first_delay_s:
            await asyncio.sleep(self.first_delay_s)
        if self.fail_before:
            raise SpeechError("http 401: quota exceeded")
        yield self.chunks[0]
        if self.gate is not None:
            await self.gate.wait()
        if self.fail_after_first:
            raise SpeechError("connection reset")
        for chunk in self.chunks[1:]:
            yield chunk


def _asgi_post(app, path: str, query: bytes, body: dict, on_body=None) -> list[dict]:
    """Прямой вызов ASGI: видно каждое сообщение ответа по отдельности (TestClient склеивает поток)."""
    raw = json.dumps(body).encode()
    scope = {
        "type": "http",
        "asgi": {"version": "3.0"},
        "http_version": "1.1",
        "method": "POST",
        "scheme": "http",
        "path": path,
        "raw_path": path.encode(),
        "root_path": "",
        "query_string": query,
        "headers": [
            (b"host", b"test"),
            (b"authorization", f"Bearer {TOKEN}".encode()),
            (b"content-type", b"application/json"),
            (b"content-length", str(len(raw)).encode()),
        ],
        "client": ("127.0.0.1", 50000),
        "server": ("test", 80),
    }
    sent: list[dict] = []

    async def run() -> None:
        delivered = False

        async def receive() -> dict:
            nonlocal delivered
            if not delivered:
                delivered = True
                return {"type": "http.request", "body": raw, "more_body": False}
            await asyncio.Event().wait()  # клиент не уходит
            return {"type": "http.disconnect"}

        async def send(message: dict) -> None:
            sent.append(message)
            if on_body is not None and message["type"] == "http.response.body":
                on_body(message)

        await asyncio.wait_for(app(scope, receive, send), timeout=5)

    asyncio.run(run())
    return sent


def test_tts_stream_first_bytes_before_end_then_cached(make_client):
    provider = StreamingTts()
    c = make_client(tts=provider)
    body = {"text": "Кафе на первом этаже.", "lang": "ru"}

    def on_body(message: dict) -> None:
        # первый кусок ушёл клиенту, пока провайдер ещё не досинтезировал остальное
        if message.get("body") == CHUNKS[0]:
            assert provider.gate is not None and not provider.gate.is_set()
            provider.gate.set()

    async def make_gate() -> None:
        provider.gate = asyncio.Event()

    original = provider.tts_stream

    async def gated(text, lang):
        await make_gate()
        async for chunk in original(text, lang):
            yield chunk

    provider.tts_stream = gated  # type: ignore[method-assign]
    sent = _asgi_post(c.app, "/v1/tts", b"stream=true", body, on_body)

    start = sent[0]
    assert start["type"] == "http.response.start" and start["status"] == 200
    headers = {k.decode(): v.decode() for k, v in start["headers"]}
    assert headers["content-type"] == "audio/mpeg"
    assert "content-length" not in headers  # chunked
    assert headers["x-cache"] == "miss" and headers["x-speech-provider"] == "elevenlabs"
    assert "x-request-id" in headers
    bodies = [m for m in sent if m["type"] == "http.response.body"]
    assert [m["body"] for m in bodies] == [*CHUNKS, b""]
    assert [m.get("more_body", False) for m in bodies] == [True, True, True, False]

    key = cache_key("elevenlabs", "voice-ru", "ru", body["text"])
    assert c.app.state.tts_cache.get(key) == b"".join(CHUNKS)

    items = rows(c, "tts")
    by = stages(items)
    assert by["tts_first_byte_ms"].ms <= by["tts_total_ms"].ms
    assert by["tts_total_ms"].extra["stream"] is True
    assert by["tts_total_ms"].extra["cache"] == "miss"
    assert by["tts_total_ms"].ok is True

    # повтор — из кеша целиком, без обращения к провайдеру
    r = c.post("/v1/tts?stream=true", json=body, headers=AUTH)
    assert r.status_code == 200
    assert r.headers["x-cache"] == "hit"
    assert r.headers["content-length"] == str(len(b"".join(CHUNKS)))
    assert r.content == b"".join(CHUNKS)
    assert provider.stream_calls == 1 and provider.tts_calls == 0


def test_tts_stream_via_test_client(make_client):
    provider = StreamingTts()
    c = make_client(tts=provider)
    with c.stream(
        "POST", "/v1/tts?stream=true", json={"text": "Hello.", "lang": "en"}, headers=AUTH
    ) as r:
        assert r.status_code == 200
        assert r.headers["content-type"] == "audio/mpeg"
        assert b"".join(r.iter_bytes()) == b"".join(CHUNKS)


def test_tts_stream_error_before_first_bytes_is_503_and_not_cached(make_client):
    provider = StreamingTts(fail_before=True)
    c = make_client(tts=provider)
    body = {"text": "Кафе на первом этаже.", "lang": "ru"}
    r = c.post("/v1/tts?stream=true", json=body, headers=AUTH)
    assert r.status_code == 503
    assert r.json()["error"]["code"] == "speech_unavailable"
    assert r.headers["x-speech-provider"] == "elevenlabs"
    assert r.json()["request_id"] == r.headers["x-request-id"]
    assert (
        c.app.state.tts_cache.get(cache_key("elevenlabs", "voice-ru", "ru", body["text"])) is None
    )
    (total,) = [i for i in rows(c, "tts") if i.stage == "tts_total_ms"]
    assert total.ok is False and total.extra["error"] == "speech_unavailable"
    assert not [i for i in rows(c, "tts") if i.stage == "tts_first_byte_ms"]


def test_tts_stream_timeout_before_first_bytes_is_504(make_client):
    c = make_client(tts=StreamingTts(first_delay_s=1.0), tts_timeout_s=0.2)
    r = c.post("/v1/tts?stream=true", json={"text": "Привет.", "lang": "ru"}, headers=AUTH)
    assert r.status_code == 504 and r.json()["error"]["code"] == "timeout"


def test_tts_stream_broken_midway_is_not_cached(make_client):
    provider = StreamingTts(fail_after_first=True)
    c = make_client(tts=provider)
    body = {"text": "Кафе на первом этаже.", "lang": "ru"}
    sent = _asgi_post(c.app, "/v1/tts", b"stream=true", body)
    bodies = [m for m in sent if m["type"] == "http.response.body"]
    assert [m["body"] for m in bodies] == [CHUNKS[0]]
    assert bodies[-1]["more_body"] is True  # без завершающего куска — клиент видит обрыв
    assert (
        c.app.state.tts_cache.get(cache_key("elevenlabs", "voice-ru", "ru", body["text"])) is None
    )
    (total,) = [i for i in rows(c, "tts") if i.stage == "tts_total_ms"]
    assert total.ok is False


def test_tts_without_stream_param_unchanged(make_client):
    provider = StreamingTts()
    c = make_client(tts=provider)
    r = c.post("/v1/tts", json={"text": "Hello.", "lang": "en"}, headers=AUTH)
    assert r.status_code == 200 and r.content == b"".join(CHUNKS)
    assert r.headers["content-length"] == str(len(r.content))
    assert provider.tts_calls == 1 and provider.stream_calls == 0


def test_tts_stream_falls_back_to_full_response_without_stream_support(client):
    """Провайдер без потока (OpenRouter, fake) — stream=true отдаёт фразу целиком."""
    r = client.post("/v1/tts?stream=true", json={"text": "Hello.", "lang": "en"}, headers=AUTH)
    assert r.status_code == 200 and r.headers["x-cache"] == "miss"
    assert r.headers["content-type"] == "audio/mpeg"


def test_fallback_tts_provider_stream_uses_fallback_before_first_bytes():
    from app.speech.factory import FallbackTtsProvider

    class Full:
        name = "openrouter"

        def voice_id(self, lang):
            return lang

        async def tts(self, text, lang):
            return b"full-audio"

    async def collect(provider) -> list[bytes]:
        return [chunk async for chunk in provider.tts_stream("Привет.", "ru")]

    broken = FallbackTtsProvider(StreamingTts(fail_before=True), Full())
    assert asyncio.run(collect(broken)) == [b"full-audio"]
    healthy = FallbackTtsProvider(StreamingTts(), Full())
    assert asyncio.run(collect(healthy)) == CHUNKS


# ---------- ElevenLabs: потоковый endpoint ----------


@pytest.mark.parametrize(("lang", "model"), [("kk", "eleven_v3"), ("ru", "eleven_flash_v2_5")])
def test_elevenlabs_tts_stream_request(lang, model):
    seen: dict = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["url"] = str(request.url.copy_with(query=None))
        seen["params"] = dict(request.url.params)
        seen["body"] = json.loads(request.content)
        return httpx.Response(200, content=b"mp3-part-1mp3-part-2")

    async def collect() -> bytes:
        provider = _eleven(handler)
        try:
            return b"".join([c async for c in provider.tts_stream("Сәлем.", lang)])
        finally:
            await provider.aclose()

    assert asyncio.run(collect()) == b"mp3-part-1mp3-part-2"
    assert seen["url"] == f"https://api.elevenlabs.test/v1/text-to-speech/voice-{lang}/stream"
    assert seen["params"] == {"output_format": "mp3_44100_128"}
    body = seen["body"]
    assert body.pop("voice_settings") == VoiceSettings().for_model(model)
    text = (
        "[cheerfully] Сәлем." if model == "eleven_v3" else "Сәлем."
    )  # тег подачи v3 — только в запрос
    assert body == {"text": text, "model_id": model, "language_code": lang}


def test_elevenlabs_tts_stream_http_error_is_speech_error():
    async def run() -> None:
        provider = _eleven(lambda r: httpx.Response(401, json={"detail": "quota_exceeded"}))
        try:
            async for _ in provider.tts_stream("Hi.", "en"):
                pass
        finally:
            await provider.aclose()

    with pytest.raises(SpeechError, match="http 401"):
        asyncio.run(run())
