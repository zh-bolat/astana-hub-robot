"""Gemini через google-genai SDK: текст (/ask) и vision (/look) в JSON-режиме.

- База знаний идёт в system_instruction первой и не меняется между запросами — стабильный префикс,
  который Gemini кеширует неявно (implicit context caching).
- «Размышления»: для Gemini 3.x управляются `thinking_level` (minimal/low/medium/high), для 2.5 —
  `thinking_budget`. По умолчанию minimal — в замерах thoughts_token_count = 0.
  `max_output_tokens` включает токены размышлений; по умолчанию 320 (ответ ≤ 600 символов + JSON) —
  при включённых размышлениях его нужно поднять (LLM_MAX_OUTPUT_TOKENS). Обрезанный по лимиту JSON
  не теряется: из него берутся законченные предложения (parse_answer_json).
- Внутренние ретраи SDK выключены (retry_options=None); повторы и резервные модели — в
  `run_with_fallback` в рамках дедлайна запроса.
- Стримы SDK закрываются явно (aclose) при любом исходе: отмена SSE клиентом, break на отказе,
  проигравшие хедж-попытки. HTTP-клиент SDK закрывается при остановке сервиса (`aclose`).
- usage_metadata (в т.ч. cached_content_token_count) пишется в лог `llm_usage` — видно, работает ли
  неявный кеш промпта.
"""

from __future__ import annotations

import asyncio
import contextlib
from collections.abc import AsyncIterator

import httpx
from google import genai
from google.genai import errors as genai_errors
from google.genai import types

from app.llm.base import LLMAnswer, LLMError, LLMRequest, parse_answer_json
from app.llm.fallback import RetryableError, run_with_fallback
from app.logging_setup import logger

ANSWER_SCHEMA = {
    "type": "OBJECT",
    "properties": {
        "category": {"type": "STRING", "enum": ["kb", "small_talk", "general", "out_of_scope"]},
        "found": {"type": "BOOLEAN"},
        "answer": {"type": "STRING"},
        "lang": {"type": "STRING", "enum": ["ru", "kk", "en"]},
        "sources": {"type": "ARRAY", "items": {"type": "STRING"}},
    },
    "required": ["category", "found", "answer", "lang", "sources"],
    "propertyOrdering": ["category", "found", "lang", "answer", "sources"],
}

_RETRYABLE_HTTP = {408, 429, 500, 502, 503, 504}


def log_usage(model: str, usage, stream: bool = False) -> None:
    """Токены вызова; cached_tokens > 0 — сработал неявный кеш префикса (база знаний)."""
    if usage is None:
        return
    logger.info(
        "llm_usage",
        extra={
            "model": model,
            "stream": stream,
            "prompt_tokens": getattr(usage, "prompt_token_count", None),
            "cached_tokens": getattr(usage, "cached_content_token_count", None) or 0,
            "output_tokens": getattr(usage, "candidates_token_count", None),
            "thoughts_tokens": getattr(usage, "thoughts_token_count", None),
        },
    )


async def _aclose(iterator) -> None:
    aclose = getattr(iterator, "aclose", None)
    if aclose is not None:
        with contextlib.suppress(Exception):
            await aclose()


class GeminiProvider:
    name = "gemini"

    def __init__(
        self,
        api_key: str,
        model: str,
        vision_model: str | None = None,
        fallback_models: list[str] | None = None,
        thinking_level: str | None = "minimal",
        thinking_budget: int | None = None,
        max_output_tokens: int = 320,
        hedge_after_s: float = 3.0,
        retry_min_s: float = 2.5,
    ) -> None:
        self.client = genai.Client(
            api_key=api_key, http_options=types.HttpOptions(retry_options=None)
        )
        self.model = model
        self.vision_model = vision_model or model
        self.fallback_models = [m for m in (fallback_models or []) if m]
        self.thinking_level = (thinking_level or "").upper() or None
        self.thinking_budget = thinking_budget
        self.max_output_tokens = max_output_tokens
        self.hedge_after_s = hedge_after_s
        self.retry_min_s = retry_min_s

    def _config(self, system: str) -> types.GenerateContentConfig:
        kwargs: dict = {
            "system_instruction": system,
            "temperature": 0.2,
            "max_output_tokens": self.max_output_tokens,
            "response_mime_type": "application/json",
            "response_schema": ANSWER_SCHEMA,
        }
        if self.thinking_level:
            kwargs["thinking_config"] = types.ThinkingConfig(thinking_level=self.thinking_level)
        elif self.thinking_budget is not None and self.thinking_budget >= 0:
            kwargs["thinking_config"] = types.ThinkingConfig(thinking_budget=self.thinking_budget)
        return types.GenerateContentConfig(**kwargs)

    async def _attempt(self, model: str, system: str, contents: list) -> LLMAnswer:
        try:
            resp = await self.client.aio.models.generate_content(
                model=model, contents=contents, config=self._config(system)
            )
        except genai_errors.APIError as exc:
            msg = f"gemini {model}: {exc.code} {exc.status or ''}".strip()
            if exc.code in _RETRYABLE_HTTP:
                raise RetryableError(msg) from exc
            raise LLMError(msg) from exc
        except (httpx.TransportError, ConnectionError, OSError) as exc:
            raise RetryableError(f"gemini {model}: {type(exc).__name__}") from exc
        log_usage(model, getattr(resp, "usage_metadata", None))
        answer = parse_answer_json(resp.text or "")
        answer.model = model
        return answer

    async def aclose(self) -> None:
        """Закрывает HTTP-клиенты SDK (остановка сервиса)."""
        with contextlib.suppress(Exception):
            await self.client.aio.aclose()
        with contextlib.suppress(Exception):
            self.client.close()

    async def _generate(self, primary: str, req: LLMRequest, contents: list) -> LLMAnswer:
        models = list(dict.fromkeys([primary, *self.fallback_models]))
        try:
            answer, _ = await run_with_fallback(
                models,
                lambda m: self._attempt(m, req.system, contents),
                deadline_s=req.deadline_s,
                hedge_after_s=self.hedge_after_s,
                retry_min_s=self.retry_min_s,
            )
        except RetryableError as exc:
            raise LLMError(str(exc)) from exc
        return answer

    async def answer(self, req: LLMRequest) -> LLMAnswer:
        return await self._generate(self.model, req, [req.user])

    async def _open_stream(self, model: str, system: str, contents: list):
        """Открывает стрим и дожидается первого куска (на этом этапе работают повтор/fallback).

        Отмена (проигравший хедж, дедлайн) или ошибка после открытия — стрим SDK закрывается.
        """
        iterator = None
        try:
            stream = await self.client.aio.models.generate_content_stream(
                model=model, contents=contents, config=self._config(system)
            )
            iterator = stream.__aiter__()
            try:
                first = await iterator.__anext__()
            except StopAsyncIteration:
                first = None
        except genai_errors.APIError as exc:
            await _aclose(iterator)
            msg = f"gemini {model}: {exc.code} {exc.status or ''}".strip()
            if exc.code in _RETRYABLE_HTTP:
                raise RetryableError(msg) from exc
            raise LLMError(msg) from exc
        except (httpx.TransportError, ConnectionError, OSError) as exc:
            await _aclose(iterator)
            raise RetryableError(f"gemini {model}: {type(exc).__name__}") from exc
        except BaseException:
            if iterator is not None:
                # CancelledError: закрываем под щитом, чтобы закрытие не прервалось той же отменой
                with contextlib.suppress(BaseException):
                    await asyncio.shield(_aclose(iterator))
            raise
        return iterator, first

    async def answer_stream(self, req: LLMRequest) -> AsyncIterator[str]:
        """Сырые куски JSON-ответа. Повтор/резервная модель — до первого куска.

        Итератор SDK закрывается в finally: и при нормальном конце, и при aclose() снаружи
        (отмена SSE клиентом, break на out_of_scope), и при ошибке.
        """
        models = list(dict.fromkeys([self.model, *self.fallback_models]))
        try:
            (iterator, first), model = await run_with_fallback(
                models,
                lambda m: self._open_stream(m, req.system, [req.user]),
                deadline_s=req.deadline_s,
                hedge_after_s=self.hedge_after_s,
                retry_min_s=self.retry_min_s,
                on_discard=lambda result: _aclose(result[0]),
            )
        except RetryableError as exc:
            raise LLMError(str(exc)) from exc
        req.info["model"] = model
        usage = None
        try:
            if first is not None:
                usage = getattr(first, "usage_metadata", None) or usage
                if first.text:
                    yield first.text
            async for chunk in iterator:
                usage = getattr(chunk, "usage_metadata", None) or usage
                if chunk.text:
                    yield chunk.text
            log_usage(model, usage, stream=True)
        except genai_errors.APIError as exc:
            raise LLMError(f"gemini {model} stream: {exc.code}") from exc
        except (httpx.TransportError, ConnectionError, OSError) as exc:
            raise LLMError(f"gemini {model} stream: {type(exc).__name__}") from exc
        finally:
            await _aclose(iterator)

    async def describe_image(self, req: LLMRequest) -> LLMAnswer:
        if not req.image:
            raise LLMError("image is required")
        image_part = types.Part.from_bytes(data=req.image, mime_type=req.image_mime)
        return await self._generate(self.vision_model, req, [image_part, req.user])
