"""GuardMiddleware: 401 до чтения тела, лимит тела по пути (Content-Length и chunked), бакеты rate limit."""

from __future__ import annotations

import asyncio
import json
import tracemalloc

import pytest

from tests.conftest import AUTH, ask_body

CHUNK = b"x" * 65536
HUGE = 200 * 1024 * 1024  # 200 МБ


async def _call(app, path: str, headers: dict[str, str], total: int, declare_length: bool):
    """Прямой ASGI-вызов: тело отдаётся кусками по требованию, считаем, сколько прочитано."""
    pulled = 0
    sent_all = False
    hdrs = [(k.lower().encode(), v.encode()) for k, v in headers.items()]
    hdrs.append((b"content-type", b"application/json"))
    if declare_length:
        hdrs.append((b"content-length", str(total).encode()))
    scope = {
        "type": "http",
        "asgi": {"version": "3.0"},
        "http_version": "1.1",
        "method": "POST",
        "scheme": "http",
        "path": path,
        "raw_path": path.encode(),
        "query_string": b"",
        "root_path": "",
        "headers": hdrs,
        "client": ("127.0.0.1", 1234),
        "server": ("testserver", 80),
        "app": app,
    }

    async def receive():
        nonlocal pulled, sent_all
        if sent_all:
            await asyncio.sleep(3600)
        n = min(len(CHUNK), total - pulled)
        pulled += n
        more = pulled < total
        if not more:
            sent_all = True
        return {"type": "http.request", "body": CHUNK[:n], "more_body": more}

    messages: list[dict] = []

    async def send(message):
        messages.append(message)

    await app(scope, receive, send)
    start = next(m for m in messages if m["type"] == "http.response.start")
    body = b"".join(m.get("body", b"") for m in messages if m["type"] == "http.response.body")
    return start["status"], json.loads(body) if body else None, pulled


@pytest.fixture
def app(client):
    return client.app


def _run(coro):
    tracemalloc.start()
    try:
        result = asyncio.run(coro)
        _, peak = tracemalloc.get_traced_memory()
    finally:
        tracemalloc.stop()
    return (*result, peak)


@pytest.mark.parametrize("declare_length", [True, False])
def test_huge_body_without_token_is_401_without_reading(app, declare_length):
    status, body, pulled, peak = _run(_call(app, "/v1/ask", {}, HUGE, declare_length))
    assert status == 401
    assert body["error"]["code"] == "unauthorized"
    assert body["request_id"]
    assert pulled == 0
    assert peak < 5 * 1024 * 1024


def test_huge_body_with_bad_token_is_401(app):
    hdrs = {"Authorization": "Bearer wrong"}
    status, _, pulled, _ = _run(_call(app, "/v1/look", hdrs, HUGE, True))
    assert status == 401
    assert pulled == 0


def test_huge_body_with_token_declared_length_is_413_without_reading(app):
    status, body, pulled, peak = _run(_call(app, "/v1/ask", AUTH, HUGE, True))
    assert status == 413
    assert body["error"]["code"] == "payload_too_large"
    assert pulled == 0
    assert peak < 5 * 1024 * 1024


@pytest.mark.parametrize(
    ("path", "limit"),
    [
        ("/v1/ask", 16 * 1024),
        ("/v1/look", 1024 * 1024 + 64 * 1024),
        ("/v1/stt", 600 * 1024 + 64 * 1024),
        ("/v1/tts", 4 * 1024),
    ],
)
def test_chunked_body_over_limit_is_cut_at_limit(app, path, limit):
    hdrs = {**AUTH}
    if path in ("/v1/look", "/v1/stt"):
        hdrs["content-type"] = "multipart/form-data; boundary=x"
    status, body, pulled, peak = _run(_call(app, path, hdrs, HUGE, False))
    assert status == 413
    assert body["error"]["code"] == "payload_too_large"
    assert pulled <= limit + len(CHUNK)
    assert peak < 20 * 1024 * 1024


def test_declared_length_limits_per_path(app):
    # /tts 4 КБ: 5 КБ → 413
    status, _, pulled, _ = _run(_call(app, "/v1/tts", AUTH, 5 * 1024, True))
    assert (status, pulled) == (413, 0)
    # /places (прочее) 16 КБ
    status, _, _, _ = _run(_call(app, "/v1/places", AUTH, 17 * 1024, True))
    assert status == 413


def test_health_skips_guard(client):
    r = client.get("/v1/health")
    assert r.status_code == 200
    assert r.json() == {"status": "ok", "version": r.json()["version"]}


def test_testclient_chunked_over_limit(client):
    def gen():
        for _ in range(64):
            yield b"a" * 1024

    r = client.post("/v1/ask", content=gen(), headers={**AUTH, "content-type": "application/json"})
    assert r.status_code == 413
    assert r.json()["error"]["code"] == "payload_too_large"


def test_small_body_passes(client):
    r = client.post("/v1/ask", json=ask_body("Где кафе?"), headers=AUTH)
    assert r.status_code == 200


def test_unauthorized_unknown_v1_path(client):
    assert client.get("/v1/nope").status_code == 401
    assert client.get("/v1/nope", headers=AUTH).status_code == 404


def test_kb_bucket_separate_from_ask(make_client):
    c = make_client(rate_limit_per_min=2, kb_rate_limit_per_min=3)
    for _ in range(2):
        assert c.post("/v1/ask", json=ask_body("Где кафе?"), headers=AUTH).status_code == 200
    assert c.post("/v1/ask", json=ask_body("Где кафе?"), headers=AUTH).status_code == 429
    # /places и /quick-questions не съедены лимитом вопросов, но у них свой лимит
    assert c.get("/v1/places", headers=AUTH).status_code == 200
    assert c.get("/v1/quick-questions", headers=AUTH).status_code == 200
    assert c.get("/v1/places", headers=AUTH).status_code == 200
    r = c.get("/v1/quick-questions", headers=AUTH)
    assert r.status_code == 429
    assert r.headers["Retry-After"]


def test_kb_bucket_does_not_eat_ask(make_client):
    c = make_client(rate_limit_per_min=1, kb_rate_limit_per_min=100)
    for _ in range(5):
        assert c.get("/v1/places", headers=AUTH).status_code == 200
    assert c.post("/v1/ask", json=ask_body("Где кафе?"), headers=AUTH).status_code == 200
