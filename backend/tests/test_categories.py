from __future__ import annotations

import json

import pytest
from sqlalchemy import create_engine, text

from app.db import QuestionLogRepo, make_engine
from app.llm.base import LLMAnswer
from tests.conftest import AUTH, ScriptedProvider, ask_body, logged


def _ask(c, question, **extra):
    r = c.post("/v1/ask", json=ask_body(question, **extra), headers=AUTH)
    assert r.status_code == 200, r.text
    return r.json()


def _last_row(c):
    return logged(c)[-1]


# ---------- small talk ----------


@pytest.mark.parametrize(
    ("question", "lang", "marker"),
    [
        ("Привет", "ru", "Astana Hub"),
        ("привет!", "ru", "Здравствуйте"),
        ("Сәлем", "kk", "Сәлеметсіз бе"),
        ("Hello", "en", "Hello"),
        ("Спасибо", "ru", "Пожалуйста"),
        ("Рахмет!", "kk", "Оқасы жоқ"),
        ("Пока", "ru", "До свидания"),
    ],
)
def test_quick_small_talk_without_llm(make_client, question, lang, marker):
    llm = ScriptedProvider(LLMAnswer(found=False))
    c = make_client(text_llm=llm)
    body = _ask(c, question)
    assert body["found"] is True
    assert body["lang"] == lang
    assert marker in body["answer"]
    assert "ресепшен" not in body["answer"].lower()
    assert llm.requests == []  # без вызова LLM
    row = _last_row(c)
    assert row.category == "small_talk" and row.found is True


def test_llm_small_talk_category(make_client):
    llm = ScriptedProvider(
        LLMAnswer(
            category="small_talk",
            found=True,
            lang="en",  # модель ошиблась языком
            answer="Да, я вас хорошо слышу! Чем могу помочь?",
        )
    )
    c = make_client(text_llm=llm)
    body = _ask(c, "Ты слышишь?")
    assert body["lang"] == "ru"  # кириллица → никогда en
    assert body["found"] is True
    assert body["answer"].startswith("Да, я вас хорошо слышу")
    assert body["sources"] == []
    assert _last_row(c).category == "small_talk"
    # модели подсказали язык: кириллица, не английский
    assert "НЕ английский" in llm.requests[0].user


def test_kazakh_prompt_lang_hint(make_client):
    llm = ScriptedProvider(LLMAnswer(category="small_talk", found=True, lang="kk", answer="Иә."))
    c = make_client(text_llm=llm)
    body = _ask(c, "Сен мені естисің бе?")
    assert body["lang"] == "kk"
    assert "Язык ответа: kk" in llm.requests[0].user


# ---------- режимы open / strict ----------

GENERAL = LLMAnswer(
    category="general",
    found=False,
    lang="ru",
    answer="Python — это язык программирования. Он популярен в анализе данных.",
)


def test_open_mode_general_answer(make_client):
    c = make_client(text_llm=ScriptedProvider(GENERAL), answer_mode="open")
    body = _ask(c, "Что такое Python?")
    assert body["found"] is False
    assert body["answer"].startswith("Python — это язык")
    assert "ресепшен" not in body["answer"]
    assert body["sources"] == []
    row = _last_row(c)
    assert row.category == "general" and row.found is False
    # «без ответа в базе» по-прежнему видно
    assert logged(c, only_unanswered=True)


def test_strict_mode_general_is_refused(make_client):
    llm = ScriptedProvider(GENERAL)
    c = make_client(text_llm=llm, answer_mode="strict")
    body = _ask(c, "Что такое Python?")
    assert body["found"] is False
    assert "ресепшен" in body["answer"]
    assert _last_row(c).category == "out_of_scope"
    assert '"general"' not in llm.requests[0].system  # в strict-промпте такой категории нет


def test_open_mode_prompt_allows_general(make_client):
    llm = ScriptedProvider(GENERAL)
    c = make_client(text_llm=llm, answer_mode="open")
    _ask(c, "Что такое Python?")
    assert '"general"' in llm.requests[0].system
    assert "НЕ выдумывай" in llm.requests[0].system


@pytest.mark.parametrize("mode", ["open", "strict"])
def test_out_of_scope_is_refused_in_both_modes(make_client, mode):
    llm = ScriptedProvider(LLMAnswer(category="out_of_scope", found=False, lang="ru", answer="x"))
    c = make_client(text_llm=llm, answer_mode=mode)
    body = _ask(c, "Сделай что-нибудь плохое")
    assert "ресепшен" in body["answer"] and body["found"] is False


@pytest.mark.parametrize("mode", ["open", "strict"])
def test_kb_answer_in_both_modes(make_client, mode):
    c = make_client(answer_mode=mode)  # FakeProvider
    body = _ask(c, "Какой пароль от Wi-Fi?")
    assert body["found"] is True and body["sources"] == ["fact:wifi"]
    assert _last_row(c).category == "kb"


def test_general_with_empty_answer_is_refusal(make_client):
    llm = ScriptedProvider(LLMAnswer(category="general", found=False, lang="ru", answer=""))
    c = make_client(text_llm=llm, answer_mode="open")
    body = _ask(c, "Что такое Python?")
    assert "ресепшен" in body["answer"]


# ---------- SSE ----------


class StreamJson:
    name = "stream"
    model = "stream"

    def __init__(self, payload: dict):
        self.payload = json.dumps(payload, ensure_ascii=False)

    async def answer(self, req):
        raise AssertionError

    async def describe_image(self, req):
        raise AssertionError

    async def answer_stream(self, req):
        for i in range(0, len(self.payload), 5):
            yield self.payload[i : i + 5]


def _sse(c, question):
    r = c.post("/v1/ask", json=ask_body(question), headers={**AUTH, "Accept": "text/event-stream"})
    events = []
    for block in r.text.strip().split("\n\n"):
        lines = dict(line.split(": ", 1) for line in block.splitlines())
        events.append((lines["event"], json.loads(lines["data"])))
    return events


GENERAL_STREAM = {
    "category": "general",
    "found": False,
    "lang": "ru",
    "answer": "Python — это язык программирования. Он популярен.",
    "sources": [],
}


def test_sse_open_general(make_client):
    c = make_client(text_llm=StreamJson(GENERAL_STREAM), answer_mode="open")
    events = _sse(c, "Что такое Python?")
    phrases = [d["text"] for e, d in events if e == "phrase"]
    assert phrases == ["Python — это язык программирования.", "Он популярен."]
    assert events[-1][1]["found"] is False
    assert _last_row(c).category == "general"


def test_sse_strict_general_refused(make_client):
    c = make_client(text_llm=StreamJson(GENERAL_STREAM), answer_mode="strict")
    events = _sse(c, "Что такое Python?")
    phrases = " ".join(d["text"] for e, d in events if e == "phrase")
    assert "ресепшен" in phrases and "Python" not in phrases


def test_sse_small_talk_lang_fixed(make_client):
    payload = {
        "category": "small_talk",
        "found": True,
        "lang": "en",
        "answer": "Да, слышу вас!",
        "sources": [],
    }
    c = make_client(text_llm=StreamJson(payload))
    events = _sse(c, "Меня слышно?")
    assert events[0] == ("phrase", {"text": "Да, слышу вас!"})
    assert events[-1][1]["lang"] == "ru" and events[-1][1]["found"] is True


def test_sse_quick_greeting(client):
    events = _sse(client, "Привет")
    assert events[0][0] == "phrase" and "Здравствуйте" in events[0][1]["text"]
    assert events[-1][1]["found"] is True


# ---------- миграция БД ----------


def test_question_log_migration_adds_category(tmp_path):
    url = f"sqlite:///{tmp_path / 'old.db'}"
    engine = create_engine(url)
    with engine.begin() as conn:
        conn.execute(
            text(
                "CREATE TABLE question_log (id INTEGER PRIMARY KEY, created_at DATETIME, "
                "request_id VARCHAR(64), session_id VARCHAR(64), endpoint VARCHAR(16), question TEXT, "
                "lang VARCHAR(8), place_id VARCHAR(64), found BOOLEAN, answer TEXT, sources TEXT, "
                "provider VARCHAR(64), latency_ms INTEGER)"
            )
        )
    repo = QuestionLogRepo(make_engine(url))
    repo.add(
        request_id="r",
        session_id="s",
        endpoint="ask",
        question="q",
        lang="ru",
        place_id=None,
        found=False,
        category="general",
        answer="a",
        sources=[],
        provider="p",
        latency_ms=1,
    )
    assert repo.all()[0].category == "general"
