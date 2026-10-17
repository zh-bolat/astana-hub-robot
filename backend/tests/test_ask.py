from __future__ import annotations

import json

import pytest

from app.llm.base import LLMAnswer
from tests.conftest import AUTH, FailingProvider, ScriptedProvider, SlowProvider, ask_body


def test_health_no_auth(client):
    r = client.get("/v1/health")
    assert r.status_code == 200
    assert r.json() == {"status": "ok", "version": "0.1.0"}
    assert r.headers["x-request-id"].startswith("req_")


@pytest.mark.parametrize(
    "headers",
    [{}, {"Authorization": "Bearer wrong"}, {"Authorization": "Basic test-token"}],
)
def test_ask_requires_token(client, headers):
    r = client.post("/v1/ask", json=ask_body("Где кафе?"), headers=headers)
    assert r.status_code == 401
    body = r.json()
    assert body["error"]["code"] == "unauthorized"
    assert body["request_id"] == r.headers["x-request-id"]


@pytest.mark.parametrize("path", ["/v1/places", "/v1/quick-questions"])
def test_get_endpoints_require_token(client, path):
    assert client.get(path).status_code == 401


def test_ask_found_ru(client):
    r = client.post("/v1/ask", json=ask_body("Какой пароль от Wi-Fi?", lang="ru"), headers=AUTH)
    assert r.status_code == 200
    body = r.json()
    assert body["found"] is True
    assert body["lang"] == "ru"
    assert body["sources"] == ["fact:wifi"]
    assert body["request_id"] == r.headers["x-request-id"]
    assert " ".join(body["phrases"]) == body["answer"]
    assert len(body["phrases"]) == 2
    assert all(len(p) <= 300 for p in body["phrases"])


@pytest.mark.parametrize(
    ("question", "lang", "fact"),
    [
        ("Wi-Fi құпиясөзі қандай?", "kk", "fact:wifi"),
        ("Кафе қайда?", "kk", "fact:cafe"),
        ("Where can I get some coffee?", "en", "fact:cafe"),
        ("Во сколько открывается хаб?", "ru", "fact:hours"),
        ("Where can I park my car?", "en", "fact:parking"),
    ],
)
def test_ask_detects_language(client, question, lang, fact):
    r = client.post("/v1/ask", json=ask_body(question), headers=AUTH)
    body = r.json()
    assert r.status_code == 200
    assert body["lang"] == lang
    assert body["found"] is True
    assert body["sources"] == [fact]


def test_explicit_lang_wins(client):
    r = client.post("/v1/ask", json=ask_body("wifi", lang="kk"), headers=AUTH)
    body = r.json()
    assert body["lang"] == "kk"
    assert "құпиясөз" in body["answer"].lower()


OUT_OF_BASE = [
    ("Кто выиграл чемпионат мира по футболу?", "ru"),
    ("Какая погода будет завтра?", "ru"),
    ("Сколько стоит биткоин?", "ru"),
    ("Расскажи анекдот", "ru"),
    ("Who is the president of France?", "en"),
    ("What is the capital of Australia?", "en"),
    ("Tell me a joke", "en"),
    ("Ертең ауа райы қандай болады?", "kk"),
    ("Қазақстанның астанасы қай қала?", "kk"),
    ("Сколько лет Илону Маску?", "ru"),
]


@pytest.mark.parametrize(("question", "lang"), OUT_OF_BASE)
def test_unknown_question_is_refused(client, question, lang):
    r = client.post("/v1/ask", json=ask_body(question), headers=AUTH)
    assert r.status_code == 200
    body = r.json()
    assert body["found"] is False
    assert body["lang"] == lang
    assert body["sources"] == []
    marker = {"ru": "ресепшен", "kk": "ресепшен", "en": "reception"}[lang]
    assert marker in body["answer"].lower()
    assert body["phrases"]


def test_unknown_question_logged_as_unanswered(client):
    client.post("/v1/ask", json=ask_body("Кто выиграл чемпионат мира?"), headers=AUTH)
    client.post("/v1/ask", json=ask_body("Где кафе?"), headers=AUTH)
    assert client.app.state.log_writer.wait_idle(5)
    repo = client.app.state.log_repo
    unanswered = repo.all(only_unanswered=True)
    assert [row.question for row in unanswered] == ["Кто выиграл чемпионат мира?"]
    assert len(repo.all()) == 2
    assert unanswered[0].endpoint == "ask"


def test_llm_refusal_text_is_replaced_and_sources_filtered(make_client):
    # Модель сказала found=false, но что-то «нафантазировала» — отдаём стандартный отказ
    llm = ScriptedProvider(
        LLMAnswer(found=False, answer="Наверное, в 10 утра.", sources=["fact:hours"])
    )
    c = make_client(text_llm=llm)
    body = c.post("/v1/ask", json=ask_body("Когда приедет автобус?"), headers=AUTH).json()
    assert body["found"] is False
    assert "ресепшен" in body["answer"]
    assert body["sources"] == []


def test_llm_answer_cleaned_for_tts(make_client):
    llm = ScriptedProvider(
        LLMAnswer(
            found=True,
            lang="ru",
            answer="**Кафе** на 1 этаже 😊. Подробнее: [сайт](https://astanahub.com) https://x.y",
            sources=["fact:cafe", "fact:unknown", "fact:cafe"],
        )
    )
    c = make_client(text_llm=llm)
    body = c.post("/v1/ask", json=ask_body("Где кафе?"), headers=AUTH).json()
    assert body["answer"] == "Кафе на 1 этаже. Подробнее: сайт"
    assert body["sources"] == ["fact:cafe"]


def test_ask_passes_place_and_kb_to_llm(make_client):
    llm = ScriptedProvider(LLMAnswer(found=True, lang="ru", answer="Ок."))
    c = make_client(text_llm=llm)
    c.post("/v1/ask", json=ask_body("Что здесь?", place_id="cafe"), headers=AUTH)
    req = llm.requests[0]
    assert "place:cafe" in req.user
    assert "fact:wifi" in req.system  # вся база в контексте
    assert "ресепшен" in req.system.lower() or "только" in req.system.lower()


@pytest.mark.parametrize(
    "payload",
    [
        {"question": "", "session_id": "s"},
        {"question": "   ", "session_id": "s"},
        {"question": "x" * 501, "session_id": "s"},
        {"question": "Где кафе?"},
        {"question": "Где кафе?", "session_id": "s", "lang": "de"},
        {"session_id": "s"},
    ],
)
def test_ask_validation_errors(client, payload):
    r = client.post("/v1/ask", json=payload, headers=AUTH)
    assert r.status_code == 400
    assert r.json()["error"]["code"] == "bad_request"


def test_ask_invalid_json(client):
    r = client.post(
        "/v1/ask", content=b"{not json", headers={**AUTH, "Content-Type": "application/json"}
    )
    assert r.status_code == 400
    assert r.json()["error"]["code"] == "bad_request"


def test_llm_unavailable(make_client):
    c = make_client(text_llm=FailingProvider())
    r = c.post("/v1/ask", json=ask_body("Где кафе?"), headers=AUTH)
    assert r.status_code == 503
    assert r.json()["error"]["code"] == "llm_unavailable"


def test_llm_timeout(make_client):
    c = make_client(text_llm=SlowProvider(), ask_timeout_s=0.05)
    r = c.post("/v1/ask", json=ask_body("Где кафе?"), headers=AUTH)
    assert r.status_code == 504
    assert r.json()["error"]["code"] == "timeout"


def test_rate_limit(make_client):
    c = make_client(rate_limit_per_min=2)
    for _ in range(2):
        assert c.post("/v1/ask", json=ask_body("Где кафе?"), headers=AUTH).status_code == 200
    r = c.post("/v1/ask", json=ask_body("Где кафе?"), headers=AUTH)
    assert r.status_code == 429
    assert r.json()["error"]["code"] == "rate_limited"
    assert int(r.headers["retry-after"]) >= 1


def _parse_sse(text: str) -> list[tuple[str, dict]]:
    events = []
    for block in text.strip().split("\n\n"):
        lines = dict(line.split(": ", 1) for line in block.splitlines())
        events.append((lines["event"], json.loads(lines["data"])))
    return events


def test_ask_sse(client):
    r = client.post(
        "/v1/ask",
        json=ask_body("Какой пароль от Wi-Fi?"),
        headers={**AUTH, "Accept": "text/event-stream"},
    )
    assert r.status_code == 200
    assert r.headers["content-type"].startswith("text/event-stream")
    events = _parse_sse(r.text)
    kinds = [e[0] for e in events]
    assert kinds == ["phrase", "phrase", "done"]
    assert events[0][1]["text"].startswith("Гостевая сеть")
    done = events[-1][1]
    assert done == {
        "request_id": r.headers["x-request-id"],
        "lang": "ru",
        "found": True,
        "sources": ["fact:wifi"],
    }


def test_ask_sse_error_event(make_client):
    c = make_client(text_llm=FailingProvider())
    r = c.post(
        "/v1/ask", json=ask_body("Где кафе?"), headers={**AUTH, "Accept": "text/event-stream"}
    )
    events = _parse_sse(r.text)
    assert events == [
        (
            "error",
            {
                "error": {"code": "llm_unavailable", "message": "LLM provider is unavailable"},
                "request_id": r.headers["x-request-id"],
            },
        )
    ]


def test_ask_sse_still_checks_auth(client):
    r = client.post("/v1/ask", json=ask_body("Где кафе?"), headers={"Accept": "text/event-stream"})
    assert r.status_code == 401


def test_unknown_route_error_format(client):
    r = client.get("/v1/nope", headers=AUTH)
    assert r.status_code == 404
    assert r.json()["error"]["code"] == "not_found"
