"""Gemini через google-genai SDK: текст (/ask) и vision (/look) в JSON-режиме.

- База знаний идёт в system_instruction первой и не меняется между запросами — стабильный префикс,
  который Gemini кеширует неявно (implicit context caching).
- «Размышления»: для Gemini 3.x управляются `thinking_level` (minimal/low/medium/high), для 2.5 —
  `thinking_budget`. По умолчанию minimal — в замерах thoughts_token_count = 0.
  `max_output_tokens` включает токены размышлений, поэтому запас 1024.
- Внутренние ретраи SDK выключены (retry_options=None); повторы и резервные модели — в
  `run_with_fallback` в рамках дедлайна запроса.
"""

from __future__ import annotations

from collections.abc import AsyncIterator

import httpx
from google import genai
from google.genai import errors as genai_errors
from google.genai import types

from app.llm.base import LLMAnswer, LLMError, LLMRequest, parse_answer_json
from app.llm.fallback import RetryableError, run_with_fallback

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
        max_output_tokens: int = 1024,
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
        answer = parse_answer_json(resp.text or "")
        answer.model = model
        return answer

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
        """Открывает стрим и дожидается первого куска (на этом этапе работают повтор/fallback)."""
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
            msg = f"gemini {model}: {exc.code} {exc.status or ''}".strip()
            if exc.code in _RETRYABLE_HTTP:
                raise RetryableError(msg) from exc
            raise LLMError(msg) from exc
        except (httpx.TransportError, ConnectionError, OSError) as exc:
            raise RetryableError(f"gemini {model}: {type(exc).__name__}") from exc
        return iterator, first

    async def answer_stream(self, req: LLMRequest) -> AsyncIterator[str]:
        """Сырые куски JSON-ответа. Повтор/резервная модель — до первого куска."""
        models = list(dict.fromkeys([self.model, *self.fallback_models]))
        try:
            (iterator, first), model = await run_with_fallback(
                models,
                lambda m: self._open_stream(m, req.system, [req.user]),
                deadline_s=req.deadline_s,
                hedge_after_s=self.hedge_after_s,
                retry_min_s=self.retry_min_s,
            )
        except RetryableError as exc:
            raise LLMError(str(exc)) from exc
        req.info["model"] = model
        if first is not None and first.text:
            yield first.text
        try:
            async for chunk in iterator:
                if chunk.text:
                    yield chunk.text
        except genai_errors.APIError as exc:
            raise LLMError(f"gemini {model} stream: {exc.code}") from exc
        except (httpx.TransportError, ConnectionError, OSError) as exc:
            raise LLMError(f"gemini {model} stream: {type(exc).__name__}") from exc

    async def describe_image(self, req: LLMRequest) -> LLMAnswer:
        if not req.image:
            raise LLMError("image is required")
        image_part = types.Part.from_bytes(data=req.image, mime_type=req.image_mime)
        return await self._generate(self.vision_model, req, [image_part, req.user])
