from __future__ import annotations

import asyncio
import json

import pytest

from app.llm.base import LLMError, LLMRequest
from app.streaming import AnswerStreamParser
from tests.conftest import AUTH, ask_body


def _feed_all(payload: str, size: int) -> tuple[AnswerStreamParser, list[str]]:
    p = AnswerStreamParser()
    out: list[str] = []
    for i in range(0, len(payload), size):
        out += p.feed(payload[i : i + size])
    out += p.finish()
    return p, out


@pytest.mark.parametrize("size", [1, 2, 3, 7, 1000])
def test_parser_sentences_any_chunking(size):
    answer = 'Кафе на 1 этаже. Там есть "кофе" и\nвыпечка! Работает с 9 до 19 часов…'
    payload = json.dumps(
        {"found": True, "lang": "ru", "answer": answer, "sources": ["fact:cafe"]},
        ensure_ascii=False,
    )
    p, out = _feed_all(payload, size)
    assert p.found is True and p.lang == "ru"
    assert " ".join(out) == 'Кафе на 1 этаже. Там есть "кофе" и\nвыпечка! Работает с 9 до 19 часов…'
    assert out[0] == "Кафе на 1 этаже."
    assert p.full_json()["sources"] == ["fact:cafe"]


def test_parser_unicode_escape_split():
    payload = json.dumps({"found": True, "lang": "kk", "answer": "Сәлем. Қош келдіңіз."})
    _, out = _feed_all(payload, 3)  # ensure_ascii → \\uXXXX, режутся между кусками
    assert out == ["Сәлем.", "Қош келдіңіз."]


def test_parser_emits_before_end():
    p = AnswerStreamParser()
    assert p.feed('{"found":true,"lang":"en","answer":"First sentence. Sec') == ["First sentence."]
    assert p.feed("ond one") == []
    assert p.feed('."}') == ["Second one."]


class StreamProvider:
    name = "stream"
    model = "stream"

    def __init__(self, chunks, fail_after: int | None = None, delay: float = 0.0):
        self.chunks = chunks
        self.fail_after = fail_after
        self.delay = delay

    async def answer(self, req: LLMRequest):
        raise AssertionError("non-stream path must not be used")

    async def describe_image(self, req):
        raise AssertionError

    async def answer_stream(self, req: LLMRequest):
        for i, chunk in enumerate(self.chunks):
            if self.fail_after is not None and i == self.fail_after:
                raise LLMError("stream broke")
            await asyncio.sleep(self.delay)
            yield chunk


def _sse_events(text: str) -> list[tuple[str, dict]]:
    events = []
    for block in text.strip().split("\n\n"):
        lines = dict(line.split(": ", 1) for line in block.splitlines())
        events.append((lines["event"], json.loads(lines["data"])))
    return events


def _post_sse(client, question="Где кафе?"):
    r = client.post(
        "/v1/ask", json=ask_body(question), headers={**AUTH, "Accept": "text/event-stream"}
    )
    return r, _sse_events(r.text)


def test_sse_streams_phrases_and_sources(make_client):
    chunks = [
        '{"found": true, "lang": "ru", "answer": "Кафе на ',
        "первом этаже. Оно ",
        'работает до 19.", "sources": ["fact:cafe", "fact:nope"]}',
    ]
    c = make_client(text_llm=StreamProvider(chunks))
    r, events = _post_sse(c)
    assert events[:2] == [
        ("phrase", {"text": "Кафе на первом этаже."}),
        ("phrase", {"text": "Оно работает до 19."}),
    ]
    assert events[2] == (
        "done",
        {
            "request_id": r.headers["x-request-id"],
            "lang": "ru",
            "found": True,
            "sources": ["fact:cafe"],
        },
    )
    row = c.app.state.log_repo.all()[-1]
    assert row.found is True and row.answer == "Кафе на первом этаже. Оно работает до 19."


def test_sse_refusal_when_not_found(make_client):
    chunks = ['{"found": false, "lang": "kk", "answer": "Ойдан шығарылған жауап.', ' Тағы."}']
    c = make_client(text_llm=StreamProvider(chunks))
    _, events = _post_sse(c, "Ертең ауа райы қандай?")
    phrases = [d["text"] for e, d in events if e == "phrase"]
    assert "Ресепшенге" in " ".join(phrases)
    assert "Ойдан" not in " ".join(phrases)
    assert events[-1][1]["found"] is False
    assert c.app.state.log_repo.all(only_unanswered=True)


def test_sse_found_true_but_empty_answer_is_refusal(make_client):
    c = make_client(
        text_llm=StreamProvider(['{"found": true, "lang": "en", "answer": "", "sources": []}'])
    )
    _, events = _post_sse(c, "What?")
    assert events[-1][1]["found"] is False
    assert "reception" in " ".join(d["text"] for e, d in events if e == "phrase").lower()


def test_sse_error_mid_stream(make_client):
    chunks = ['{"found": true, "lang": "ru", "answer": "Первое. ', "Второе", " ещё."]
    c = make_client(text_llm=StreamProvider(chunks, fail_after=2))
    _, events = _post_sse(c)
    assert events[0] == ("phrase", {"text": "Первое."})
    assert events[-1][0] == "error"
    assert events[-1][1]["error"]["code"] == "llm_unavailable"


def test_sse_first_chunk_timeout(make_client):
    c = make_client(text_llm=StreamProvider(['{"found": true'], delay=1.0), ask_timeout_s=0.05)
    _, events = _post_sse(c)
    assert events == [("error", events[0][1])]
    assert events[0][1]["error"]["code"] == "timeout"


def test_sse_fake_provider_streams_in_chunks(client):
    _, events = _post_sse(client, "Какой пароль от Wi-Fi?")
    assert [e for e, _ in events] == ["phrase", "phrase", "done"]
    assert events[-1][1]["sources"] == ["fact:wifi"]
