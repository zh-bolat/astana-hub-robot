"""Стримы и клиенты: итераторы SDK и HTTP-клиенты всегда закрываются."""

from __future__ import annotations

import asyncio
import json
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient

from app.api import ClosingStreamingResponse
from app.llm.base import LLMRequest
from app.llm.fake import FakeProvider
from app.llm.fallback import run_with_fallback
from app.llm.gemini import GeminiProvider
from app.main import close_resources, create_app
from tests.conftest import AUTH, ask_body


class TrackedStream:
    """Подделка async-итератора SDK: считает, закрыт ли."""

    def __init__(self, chunks: list[str], delay: float = 0.0, usage=None) -> None:
        self.chunks = list(chunks)
        self.delay = delay
        self.closed = False
        self.usage = usage

    def __aiter__(self):
        return self

    async def __anext__(self):
        if self.delay:
            await asyncio.sleep(self.delay)
        if not self.chunks:
            raise StopAsyncIteration
        text = self.chunks.pop(0)
        return SimpleNamespace(text=text, usage_metadata=self.usage if not self.chunks else None)

    async def aclose(self) -> None:
        self.closed = True


class FakeModels:
    def __init__(self, streams: dict[str, TrackedStream]) -> None:
        self.streams = streams
        self.opened: list[str] = []

    async def generate_content_stream(self, model, contents, config):
        self.opened.append(model)
        return self.streams[model]

    async def generate_content(self, model, contents, config):
        usage = SimpleNamespace(
            prompt_token_count=1000,
            cached_content_token_count=900,
            candidates_token_count=20,
            thoughts_token_count=0,
        )
        return SimpleNamespace(
            text='{"category":"kb","found":true,"lang":"ru","answer":"Да.","sources":[]}',
            usage_metadata=usage,
        )


def _gemini(streams: dict[str, TrackedStream], fallback=None, hedge=3.0) -> GeminiProvider:
    p = GeminiProvider(
        api_key="x", model="main", fallback_models=fallback or [], hedge_after_s=hedge
    )
    closed = SimpleNamespace(aio=False, sync=False)

    async def aio_close():
        closed.aio = True

    def sync_close():
        closed.sync = True

    p.client = SimpleNamespace(  # type: ignore[assignment]
        aio=SimpleNamespace(models=FakeModels(streams), aclose=aio_close), close=sync_close
    )
    p._closed = closed  # type: ignore[attr-defined]
    return p


def _req() -> LLMRequest:
    return LLMRequest(system="s", user="u", deadline_s=2.0)


PAYLOAD = '{"category":"kb","found":true,"lang":"ru","answer":"Раз. Два. Три.","sources":[]}'


def test_gemini_stream_closed_when_consumer_stops_early():
    sdk = TrackedStream([PAYLOAD[:10], PAYLOAD[10:30], PAYLOAD[30:]])
    p = _gemini({"main": sdk})

    async def run():
        gen = p.answer_stream(_req())
        assert await gen.__anext__()
        await gen.aclose()  # отмена SSE клиентом / break на out_of_scope

    asyncio.run(run())
    assert sdk.closed


def test_gemini_stream_closed_after_normal_end_and_logs_usage(caplog):
    usage = SimpleNamespace(prompt_token_count=10, cached_content_token_count=8)
    sdk = TrackedStream([PAYLOAD[:20], PAYLOAD[20:]], usage=usage)
    p = _gemini({"main": sdk})
    logged = []

    import app.llm.gemini as gem

    orig = gem.logger.info
    gem.logger.info = lambda event, extra=None: logged.append((event, extra))  # type: ignore[method-assign]
    try:

        async def run():
            return [c async for c in p.answer_stream(_req())]

        assert "".join(asyncio.run(run())) == PAYLOAD
    finally:
        gem.logger.info = orig  # type: ignore[method-assign]
    assert sdk.closed
    usage_events = [e for e in logged if e[0] == "llm_usage"]
    assert usage_events and usage_events[0][1]["cached_tokens"] == 8


def test_gemini_non_stream_logs_cached_tokens():
    p = _gemini({})
    logged = []
    import app.llm.gemini as gem

    orig = gem.logger.info
    gem.logger.info = lambda event, extra=None: logged.append((event, extra))  # type: ignore[method-assign]
    try:
        ans = asyncio.run(p.answer(_req()))
    finally:
        gem.logger.info = orig  # type: ignore[method-assign]
    assert ans.answer == "Да."
    (_event, extra) = next(e for e in logged if e[0] == "llm_usage")
    assert extra["cached_tokens"] == 900 and extra["prompt_tokens"] == 1000


def test_gemini_losing_hedge_stream_is_closed():
    slow = TrackedStream([PAYLOAD], delay=1.0)  # основная молчит → хедж
    fast = TrackedStream([PAYLOAD])
    p = _gemini({"main": slow, "lite": fast}, fallback=["lite"], hedge=0.05)

    async def run():
        out = [c async for c in p.answer_stream(_req())]
        await asyncio.sleep(0.05)  # отменённая попытка успевает закрыть стрим
        return out

    assert "".join(asyncio.run(run())) == PAYLOAD
    assert fast.closed  # победитель закрыт после конца
    assert slow.closed  # проигравший закрыт при отмене


def test_run_with_fallback_discards_simultaneous_winner():
    discarded = []

    started: list[str] = []
    both = asyncio.Event()

    async def attempt(model: str):
        started.append(model)
        if len(started) == 2:
            both.set()
        await both.wait()  # обе попытки завершаются в одной итерации цикла
        return model

    async def run():
        async def discard(x):
            discarded.append(x)

        return await run_with_fallback(
            ["a", "b"], attempt, deadline_s=2, hedge_after_s=0.0, on_discard=discard
        )

    result, model = asyncio.run(run())
    # обе попытки завершаются одновременно: одна — в ответ, другая — в on_discard
    assert result == model
    assert discarded == [x for x in ("a", "b") if x != model]


def test_closing_streaming_response_acloses_iterator_on_disconnect():
    state = {"closed": False}

    async def body():
        try:
            yield b"one"
            yield b"two"
            yield b"three"
        finally:
            state["closed"] = True

    async def run():
        resp = ClosingStreamingResponse(body(), media_type="text/event-stream")
        sent = []

        async def send(message):
            sent.append(message)
            if message["type"] == "http.response.body" and len(sent) >= 2:
                raise OSError("client went away")

        with pytest.raises(OSError):
            await resp.stream_response(send)

    asyncio.run(run())
    assert state["closed"]


class ClosingStreamProvider:
    name = "s"
    model = "s"

    def __init__(self, chunks):
        self.chunks = chunks
        self.closed = False

    async def answer(self, req):
        raise AssertionError

    async def describe_image(self, req):
        raise AssertionError

    async def answer_stream(self, req):
        try:
            for c in self.chunks:
                yield c
        finally:
            self.closed = True


def test_service_closes_provider_stream_on_out_of_scope(make_client):
    payload = json.dumps(
        {"category": "out_of_scope", "found": False, "lang": "ru", "answer": "x. y. z."}
    )
    prov = ClosingStreamProvider([payload[:30], payload[30:], "never"])
    c = make_client(text_llm=prov)
    r = c.post("/v1/ask", json=ask_body("Кто ты?"), headers={**AUTH, "Accept": "text/event-stream"})
    assert "event: done" in r.text
    assert prov.closed


class ClosableProvider(FakeProvider):
    def __init__(self) -> None:
        super().__init__()
        self.closes = 0

    async def aclose(self) -> None:
        self.closes += 1


class ClosableSpeech:
    name = "fake"

    def __init__(self) -> None:
        self.closes = 0

    def voice_id(self, lang):
        return "v"

    async def tts(self, text, lang):
        return b"x"

    async def stt(self, pcm, sample_rate, lang):
        raise AssertionError

    def aclose(self) -> None:  # синхронный aclose тоже поддерживается
        self.closes += 1


def test_lifespan_closes_clients_once(settings):
    llm = ClosableProvider()
    speech = ClosableSpeech()
    app = create_app(settings, llm, llm, speech)  # один объект в нескольких ролях
    with TestClient(app):
        pass
    assert llm.closes == 1
    assert speech.closes == 1


def test_close_resources_survives_errors():
    class Bad:
        async def aclose(self):
            raise RuntimeError("boom")

    good = ClosableProvider()
    asyncio.run(close_resources(Bad(), None, good))
    assert good.closes == 1


def test_gemini_aclose_closes_sdk_clients():
    p = _gemini({})
    asyncio.run(p.aclose())
    assert p._closed.aio and p._closed.sync  # type: ignore[attr-defined]
