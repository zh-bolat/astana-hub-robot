from __future__ import annotations

import asyncio
import base64
import json

import httpx
import pytest

from app.config import Settings
from app.llm.base import LLMError, LLMRequest
from app.llm.factory import build_text_provider, build_vision_provider
from app.llm.openrouter import OpenRouterProvider
from tests.conftest import AUTH, JPEG, ask_body

ANSWER = {"found": True, "lang": "ru", "answer": "Кафе на первом этаже.", "sources": ["fact:cafe"]}


def _provider(handler) -> OpenRouterProvider:
    return OpenRouterProvider(
        api_key="test",
        model="google/lite",
        fallback_models=["google/flash"],
        base_url="https://openrouter.test/api/v1",
        client=httpx.AsyncClient(transport=httpx.MockTransport(handler)),
    )


def _chat(answer: dict) -> httpx.Response:
    content = json.dumps(answer, ensure_ascii=False)
    return httpx.Response(200, json={"choices": [{"message": {"content": content}}]})


def _sse(*pieces: str, comment: bool = True) -> httpx.Response:
    lines = [": OPENROUTER PROCESSING", ""] if comment else []
    for p in pieces:
        lines += [f"data: {json.dumps({'choices': [{'delta': {'content': p}}]})}", ""]
    lines += ["data: [DONE]", ""]
    return httpx.Response(
        200, content="\n".join(lines).encode(), headers={"content-type": "text/event-stream"}
    )


def _req(**kw) -> LLMRequest:
    return LLMRequest(system="SYSTEM", user="Где кафе?", deadline_s=2, **kw)


def test_answer_request_and_parse():
    seen = []

    def handler(request):
        seen.append(json.loads(request.content))
        return _chat(ANSWER)

    res = asyncio.run(_provider(handler).answer(_req()))
    assert (res.found, res.answer, res.sources, res.model) == (
        True,
        "Кафе на первом этаже.",
        ["fact:cafe"],
        "google/lite",
    )
    body = seen[0]
    assert body["model"] == "google/lite"
    assert body["response_format"]["type"] == "json_schema"
    assert body["messages"] == [
        {"role": "system", "content": "SYSTEM"},
        {"role": "user", "content": "Где кафе?"},
    ]
    assert "stream" not in body


def test_answer_falls_back_on_503():
    models = []

    def handler(request):
        model = json.loads(request.content)["model"]
        models.append(model)
        if model == "google/lite":
            return httpx.Response(503, json={"error": {"message": "overloaded"}})
        return _chat(ANSWER)

    res = asyncio.run(_provider(handler).answer(_req()))
    assert res.model == "google/flash"
    assert models[-1] == "google/flash"


def test_answer_client_error_is_llm_error():
    def handler(request):
        return httpx.Response(400, json={"error": {"message": "bad request"}})

    with pytest.raises(LLMError):
        asyncio.run(_provider(handler).answer(_req()))


def test_describe_image_sends_data_uri():
    seen = []

    def handler(request):
        seen.append(json.loads(request.content))
        return _chat(ANSWER)

    asyncio.run(_provider(handler).describe_image(_req(image=JPEG)))
    image, text = seen[0]["messages"][1]["content"]
    prefix = "data:image/jpeg;base64,"
    assert image["image_url"]["url"].startswith(prefix)
    assert base64.b64decode(image["image_url"]["url"][len(prefix) :]) == JPEG
    assert text == {"type": "text", "text": "Где кафе?"}


def test_describe_image_requires_image():
    with pytest.raises(LLMError):
        asyncio.run(_provider(lambda r: _chat(ANSWER)).describe_image(_req()))


def test_answer_stream_yields_text_and_skips_comments():
    seen = []

    def handler(request):
        seen.append(json.loads(request.content))
        return _sse('{"found": true, ', '"answer": "Кафе."}')

    async def run():
        req = _req()
        chunks = [c async for c in _provider(handler).answer_stream(req)]
        return req, chunks

    req, chunks = asyncio.run(run())
    assert chunks == ['{"found": true, ', '"answer": "Кафе."}']
    assert seen[0]["stream"] is True
    assert req.info["model"] == "google/lite"


def test_answer_stream_falls_back_before_first_chunk():
    def handler(request):
        if json.loads(request.content)["model"] == "google/lite":
            return httpx.Response(429, json={"error": {"message": "rate limited"}})
        return _sse('{"found": false}')

    async def run():
        req = _req()
        chunks = [c async for c in _provider(handler).answer_stream(req)]
        return req, chunks

    req, chunks = asyncio.run(run())
    assert chunks == ['{"found": false}']
    assert req.info["model"] == "google/flash"


def test_answer_stream_error_event_is_llm_error():
    def handler(request):
        lines = [
            f"data: {json.dumps({'choices': [{'delta': {'content': '{'}}]})}",
            "",
            'data: {"error": {"message": "provider died"}}',
            "",
        ]
        return httpx.Response(200, content="\n".join(lines).encode())

    async def run():
        return [c async for c in _provider(handler).answer_stream(_req())]

    with pytest.raises(LLMError):
        asyncio.run(run())


def test_ask_sse_end_to_end(make_client):
    answer = json.dumps(
        {
            "found": True,
            "lang": "ru",
            "answer": "Кафе на первом этаже. Работает с девяти.",
            "sources": ["fact:cafe"],
        },
        ensure_ascii=False,
    )
    pieces = [answer[i : i + 15] for i in range(0, len(answer), 15)]
    client = make_client(text_llm=_provider(lambda r: _sse(*pieces)))
    with client.stream(
        "POST",
        "/v1/ask",
        json=ask_body("Где кафе?"),
        headers={**AUTH, "Accept": "text/event-stream"},
    ) as r:
        body = r.read().decode()
    phrases = [
        json.loads(line[6:])["text"]
        for line in body.splitlines()
        if line.startswith("data: ") and '"text"' in line
    ]
    assert phrases == ["Кафе на первом этаже.", "Работает с девяти."]
    assert '"found": true' in body


# ---------- выбор провайдера ----------


def _s(**kw) -> Settings:
    base = dict(
        _env_file=None,
        robot_token="t",
        gemini_api_key=None,
        openrouter_api_key=None,
        deepseek_api_key=None,
    )
    return Settings(**{**base, **kw})


@pytest.mark.parametrize(
    ("keys", "expected"),
    [
        ({}, "fake"),
        ({"openrouter_api_key": "o"}, "openrouter"),
        ({"openrouter_api_key": "o", "gemini_api_key": "g"}, "gemini"),
    ],
)
def test_auto_llm_selection(keys, expected):
    s = _s(**keys)
    assert s.resolved_text_provider() == expected
    assert s.resolved_vision_provider() == expected


def test_openrouter_vision_reuses_text_provider():
    s = _s(openrouter_api_key="o", llm_provider="openrouter", vision_provider="openrouter")
    text = build_text_provider(s)
    assert text.name == "openrouter"
    assert build_vision_provider(s, text) is text


def test_explicit_openrouter_without_key_fails_fast():
    with pytest.raises(RuntimeError):
        build_text_provider(_s(llm_provider="openrouter"))
