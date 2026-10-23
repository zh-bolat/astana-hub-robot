from __future__ import annotations

import asyncio
import json

from app.schemas import AskRequest
from tests.conftest import AUTH, SESSION, ask_body
from tests.test_streaming import StreamProvider

URL = f"/v1/sessions/{SESSION}/messages"


def _local(**extra) -> dict:
    return {
        "kind": "where_is",
        "question": "Где кафе?",
        "answer": "Кафе справа от вас, метров 15.",
        "lang": "ru",
        "place_id": "cafe",
        **extra,
    }


def test_history_has_question_and_answer(client):
    r = client.post("/v1/ask", json=ask_body("Какой пароль от Wi-Fi?"), headers=AUTH)
    request_id = r.json()["request_id"]

    h = client.get(URL, headers=AUTH)
    assert h.status_code == 200
    body = h.json()
    assert body["session_id"] == SESSION
    user, bot = body["messages"]
    assert (user["role"], user["kind"], user["text"]) == ("user", "ask", "Какой пароль от Wi-Fi?")
    assert user["id"] == f"{request_id}:q" and user["found"] is None
    assert (bot["role"], bot["kind"], bot["text"]) == ("assistant", "ask", r.json()["answer"])
    assert bot["id"] == f"{request_id}:a"
    assert bot["found"] is r.json()["found"] and bot["interrupted"] is False


def test_local_message_goes_to_history(client):
    r = client.post(URL, json=_local(), headers=AUTH)
    assert r.status_code == 201
    request_id = r.json()["request_id"]
    assert request_id == r.headers["x-request-id"]

    user, bot = client.get(URL, headers=AUTH).json()["messages"]
    assert (user["kind"], user["text"]) == ("where_is", "Где кафе?")
    assert (bot["kind"], bot["text"], bot["found"]) == (
        "where_is",
        "Кафе справа от вас, метров 15.",
        True,
    )
    assert bot["id"] == f"{request_id}:a"


def test_local_interrupted_flag(client):
    client.post(URL, json=_local(kind="guide_to", interrupted=True), headers=AUTH)
    bot = client.get(URL, headers=AUTH).json()["messages"][1]
    assert bot["kind"] == "guide_to" and bot["interrupted"] is True


def test_history_order_limit_and_isolation(client):
    for q in ("Первый?", "Второй?", "Третий?"):
        client.post(URL, json=_local(question=q), headers=AUTH)
    client.post("/v1/sessions/other/messages", json=_local(question="Чужой?"), headers=AUTH)

    texts = [
        m["text"] for m in client.get(URL, headers=AUTH).json()["messages"] if m["role"] == "user"
    ]
    assert texts == ["Первый?", "Второй?", "Третий?"]
    last_two = client.get(URL, params={"limit": 2}, headers=AUTH).json()["messages"]
    assert [m["text"] for m in last_two if m["role"] == "user"] == ["Второй?", "Третий?"]
    assert client.get("/v1/sessions/empty/messages", headers=AUTH).json()["messages"] == []


def test_sessions_require_token(client):
    assert client.get(URL).status_code == 401
    assert client.post(URL, json=_local()).status_code == 401


def test_local_message_validation(client):
    assert client.post(URL, json=_local(kind="ask"), headers=AUTH).status_code == 400
    assert client.post(URL, json=_local(answer="  "), headers=AUTH).status_code == 400
    assert client.get(URL, params={"limit": 0}, headers=AUTH).status_code == 400
    assert client.get(f"/v1/sessions/{'x' * 65}/messages", headers=AUTH).status_code == 400


# ---------- Посетитель перебил робота во время SSE-ответа ----------

_CHUNKS = [
    json.dumps(
        {
            "found": True,
            "lang": "ru",
            "answer": "Кафе на первом этаже. Работает с девяти до семи. Там есть выпечка.",
            "sources": ["fact:cafe"],
        },
        ensure_ascii=False,
    )[i : i + 12]
    for i in range(0, 200, 12)
]


def _read_one_phrase_and_hang_up(client) -> dict:
    service = client.app.state.service

    async def run() -> dict:
        events = service.ask_stream(AskRequest(**ask_body("Где кафе?")), "req_cut")
        event, data = await events.__anext__()
        assert event == "phrase"
        await events.aclose()  # так выглядит обрыв соединения для генератора
        return data

    return asyncio.run(run())


def test_cut_stream_is_logged_as_interrupted(make_client):
    client = make_client(text_llm=StreamProvider(_CHUNKS))
    first = _read_one_phrase_and_hang_up(client)

    user, bot = client.get(URL, headers=AUTH).json()["messages"]
    assert user["text"] == "Где кафе?"
    assert bot["interrupted"] is True
    assert bot["text"] == first["text"] == "Кафе на первом этаже."
    assert bot["found"] is True


def test_cut_stream_non_streaming_provider(client):
    first = _read_one_phrase_and_hang_up(client)
    bot = client.get(URL, headers=AUTH).json()["messages"][1]
    assert bot["interrupted"] is True and bot["text"] == first["text"]


def test_full_stream_is_not_interrupted(make_client):
    client = make_client(text_llm=StreamProvider(_CHUNKS))
    with client.stream(
        "POST",
        "/v1/ask",
        json=ask_body("Где кафе?"),
        headers={**AUTH, "Accept": "text/event-stream"},
    ) as r:
        body = r.read().decode()
    assert "event: done" in body
    bot = client.get(URL, headers=AUTH).json()["messages"][1]
    assert bot["interrupted"] is False
    assert bot["text"].endswith("Там есть выпечка.")


def test_stream_error_is_not_logged(make_client):
    client = make_client(text_llm=StreamProvider(_CHUNKS, fail_after=1))
    with client.stream(
        "POST",
        "/v1/ask",
        json=ask_body("Где кафе?"),
        headers={**AUTH, "Accept": "text/event-stream"},
    ) as r:
        assert "event: error" in r.read().decode()
    assert client.get(URL, headers=AUTH).json()["messages"] == []


# ---------- База, созданная до появления колонки interrupted ----------
