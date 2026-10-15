from __future__ import annotations

import asyncio
import time

import pytest

from app.llm.base import LLMError
from app.llm.fallback import RetryableError, run_with_fallback


class Script:
    """Поведение моделей: список шагов на каждую модель (по порядку вызовов)."""

    def __init__(self, steps: dict[str, list]) -> None:
        self.steps = {k: list(v) for k, v in steps.items()}
        self.calls: list[str] = []

    async def __call__(self, model: str):
        self.calls.append(model)
        kind, value = self.steps[model].pop(0)
        if kind == "ok":
            await asyncio.sleep(value)
            return f"answer:{model}"
        if kind == "sleep_fail":
            await asyncio.sleep(value[0])
            raise value[1]
        raise value


def run(script: Script, deadline=2.0, hedge=0.3, retry_min=0.5, models=("main", "lite")):
    return asyncio.run(
        run_with_fallback(
            list(models), script, deadline_s=deadline, hedge_after_s=hedge, retry_min_s=retry_min
        )
    )


def test_primary_ok():
    s = Script({"main": [("ok", 0.01)]})
    assert run(s) == ("answer:main", "main")
    assert s.calls == ["main"]


def test_retry_primary_once_then_ok():
    s = Script({"main": [("fail", RetryableError("503")), ("ok", 0.01)]})
    assert run(s) == ("answer:main", "main")
    assert s.calls == ["main", "main"]


def test_retry_then_fallback():
    s = Script(
        {
            "main": [("fail", RetryableError("503")), ("fail", RetryableError("429"))],
            "lite": [("ok", 0.01)],
        }
    )
    assert run(s) == ("answer:lite", "lite")
    assert s.calls == ["main", "main", "lite"]


def test_no_retry_when_little_time_left():
    s = Script({"main": [("sleep_fail", (0.2, RetryableError("503")))], "lite": [("ok", 0.01)]})
    # дедлайн 0.6 с, после ошибки остаётся ~0.4 < retry_min 0.5 → сразу резерв
    assert run(s, deadline=0.6, hedge=5) == ("answer:lite", "lite")
    assert s.calls == ["main", "lite"]


def test_non_retryable_goes_straight_to_fallback():
    s = Script({"main": [("fail", LLMError("404 not found"))], "lite": [("ok", 0.01)]})
    assert run(s) == ("answer:lite", "lite")
    assert s.calls == ["main", "lite"]


def test_hedge_when_primary_is_slow():
    s = Script({"main": [("ok", 1.5)], "lite": [("ok", 0.05)]})
    t0 = time.perf_counter()
    assert run(s, hedge=0.1) == ("answer:lite", "lite")
    assert time.perf_counter() - t0 < 0.5  # не ждали медленную основную
    assert s.calls == ["main", "lite"]


def test_slow_primary_still_wins_if_faster():
    s = Script({"main": [("ok", 0.25)], "lite": [("ok", 1.0)]})
    assert run(s, hedge=0.1) == ("answer:main", "main")


def test_deadline_exceeded():
    s = Script({"main": [("ok", 5)], "lite": [("ok", 5)]})
    t0 = time.perf_counter()
    with pytest.raises(TimeoutError):
        run(s, deadline=0.3, hedge=0.1)
    assert time.perf_counter() - t0 < 0.6


def test_all_fail_raises_last_error():
    s = Script(
        {
            "main": [("fail", RetryableError("503")), ("fail", RetryableError("503"))],
            "lite": [("fail", LLMError("bad json"))],
        }
    )
    with pytest.raises(LLMError, match="bad json"):
        run(s)


def test_single_model():
    s = Script({"main": [("fail", RetryableError("503")), ("ok", 0.01)]})
    assert run(s, models=("main",)) == ("answer:main", "main")


# ---------- GeminiProvider: классификация ошибок и конфиг (без сети) ----------


def _gemini(monkeypatch, behaviour):
    from app.llm import gemini as gm

    provider = gm.GeminiProvider(
        api_key="test",
        model="main",
        fallback_models=["lite"],
        hedge_after_s=5,
        retry_min_s=0.1,
    )
    calls = []

    async def fake_generate(*, model, contents, config):
        calls.append((model, config))
        return behaviour(model, len(calls))

    monkeypatch.setattr(provider.client.aio.models, "generate_content", fake_generate)
    return provider, calls


class _Resp:
    def __init__(self, text):
        self.text = text


def test_gemini_falls_back_on_503(monkeypatch):
    from google.genai import errors as genai_errors

    from app.llm.base import LLMRequest

    def behaviour(model, n):
        if model == "main":
            raise genai_errors.ServerError(503, {"error": {"message": "high demand"}})
        return _Resp('{"found": true, "lang": "ru", "answer": "Ок.", "sources": []}')

    provider, calls = _gemini(monkeypatch, behaviour)
    res = asyncio.run(provider.answer(LLMRequest(system="s", user="u", deadline_s=2)))
    assert res.model == "lite"
    assert [c[0] for c in calls] == ["main", "main", "lite"]
    cfg = calls[0][1]
    assert cfg.thinking_config.thinking_level.value == "MINIMAL"
    assert cfg.thinking_config.thinking_budget is None


def test_gemini_404_is_not_retried(monkeypatch):
    from google.genai import errors as genai_errors

    from app.llm.base import LLMRequest

    def behaviour(model, n):
        if model == "main":
            raise genai_errors.ClientError(404, {"error": {"message": "no longer available"}})
        return _Resp('{"found": false, "lang": "en", "answer": "", "sources": []}')

    provider, calls = _gemini(monkeypatch, behaviour)
    res = asyncio.run(provider.answer(LLMRequest(system="s", user="u", deadline_s=2)))
    assert res.model == "lite"
    assert [c[0] for c in calls] == ["main", "lite"]


def test_gemini_all_unavailable_is_llm_error(monkeypatch):
    from google.genai import errors as genai_errors

    from app.llm.base import LLMRequest

    def behaviour(model, n):
        raise genai_errors.ClientError(429, {"error": {"message": "RESOURCE_EXHAUSTED"}})

    provider, _ = _gemini(monkeypatch, behaviour)
    with pytest.raises(LLMError):
        asyncio.run(provider.answer(LLMRequest(system="s", user="u", deadline_s=2)))
