"""OpenRouter: текст (/ask, со стримингом) и vision (/look) через OpenAI-совместимый API.

- POST {base}/chat/completions, JSON по схеме {found, lang, answer, sources} (`response_format: json_schema`),
  `reasoning: minimal` — у Gemini 3.x это минимальная задержка.
- Стрим — SSE `data: {...}` с `choices[0].delta.content`; строки-комментарии `: OPENROUTER PROCESSING` пропускаем.
- Кадр для /look — часть `image_url` с data-URI (base64), на диск и в логи не попадает.
- Повторы и резервные модели — `run_with_fallback` в рамках дедлайна запроса, как у Gemini; в стриме — до
  первого куска текста.
Замеры 2026-10-21 (google/gemini-3.1-flash-lite): стрим — первый кусок ~3 с, vision — ~2 с.
"""

from __future__ import annotations

import base64
import json
from collections.abc import AsyncIterator

import httpx

from app.llm.base import LLMAnswer, LLMError, LLMRequest, parse_answer_json
from app.llm.fallback import RetryableError, run_with_fallback

_RESPONSE_FORMAT = {
    "type": "json_schema",
    "json_schema": {
        "name": "answer",
        "strict": True,
        "schema": {
            "type": "object",
            "properties": {
                "found": {"type": "boolean"},
                "lang": {"type": "string", "enum": ["ru", "kk", "en"]},
                "answer": {"type": "string"},
                "sources": {"type": "array", "items": {"type": "string"}},
            },
            "required": ["found", "lang", "answer", "sources"],
            "additionalProperties": False,
        },
    },
}

_RETRYABLE_HTTP = {408, 429, 500, 502, 503, 504}


class OpenRouterProvider:
    name = "openrouter"

    def __init__(
        self,
        api_key: str,
        model: str,
        vision_model: str | None = None,
        fallback_models: list[str] | None = None,
        base_url: str = "https://openrouter.ai/api/v1",
        max_output_tokens: int = 1024,
        hedge_after_s: float = 3.0,
        retry_min_s: float = 2.5,
        timeout_s: float = 15.0,
        client: httpx.AsyncClient | None = None,
    ) -> None:
        self.model = model
        self.vision_model = vision_model or model
        self.fallback_models = [m for m in (fallback_models or []) if m]
        self.base_url = base_url.rstrip("/")
        self.max_output_tokens = max_output_tokens
        self.hedge_after_s = hedge_after_s
        self.retry_min_s = retry_min_s
        self.client = client or httpx.AsyncClient(
            timeout=timeout_s, headers={"Authorization": f"Bearer {api_key}"}
        )

    def _body(self, model: str, req: LLMRequest, user_content, stream: bool = False) -> dict:
        body = {
            "model": model,
            "temperature": 0.2,
            "max_tokens": self.max_output_tokens,
            "response_format": _RESPONSE_FORMAT,
            "reasoning": {"effort": "minimal"},
            "messages": [
                {"role": "system", "content": req.system},
                {"role": "user", "content": user_content},
            ],
        }
        if stream:
            body["stream"] = True
        return body

    def _models(self, primary: str) -> list[str]:
        return list(dict.fromkeys([primary, *self.fallback_models]))

    @staticmethod
    def _raise_for_status(model: str, r: httpx.Response, text: str) -> None:
        msg = f"openrouter {model}: http {r.status_code}"
        if r.status_code in _RETRYABLE_HTTP:
            raise RetryableError(msg)
        if r.status_code != 200:
            raise LLMError(f"{msg}: {text[:200]}")

    async def _attempt(self, model: str, req: LLMRequest, user_content) -> LLMAnswer:
        try:
            r = await self.client.post(
                f"{self.base_url}/chat/completions", json=self._body(model, req, user_content)
            )
        except httpx.HTTPError as exc:
            raise RetryableError(f"openrouter {model}: {type(exc).__name__}") from exc
        self._raise_for_status(model, r, r.text)
        try:
            content = r.json()["choices"][0]["message"]["content"] or ""
        except (ValueError, KeyError, IndexError, TypeError) as exc:
            raise RetryableError(f"openrouter {model}: malformed response") from exc
        answer = parse_answer_json(content)
        answer.model = model
        return answer

    async def _generate(self, primary: str, req: LLMRequest, user_content) -> LLMAnswer:
        try:
            answer, _ = await run_with_fallback(
                self._models(primary),
                lambda m: self._attempt(m, req, user_content),
                deadline_s=req.deadline_s,
                hedge_after_s=self.hedge_after_s,
                retry_min_s=self.retry_min_s,
            )
        except RetryableError as exc:
            raise LLMError(str(exc)) from exc
        return answer

    async def answer(self, req: LLMRequest) -> LLMAnswer:
        return await self._generate(self.model, req, req.user)

    async def describe_image(self, req: LLMRequest) -> LLMAnswer:
        if not req.image:
            raise LLMError("image is required")
        data_uri = f"data:{req.image_mime};base64,{base64.b64encode(req.image).decode()}"
        content = [
            {"type": "image_url", "image_url": {"url": data_uri}},
            {"type": "text", "text": req.user},
        ]
        return await self._generate(self.vision_model, req, content)

    # ---------- стрим ----------

    @staticmethod
    async def _deltas(lines: AsyncIterator[str]) -> AsyncIterator[str]:
        async for line in lines:
            if not line.startswith("data:"):
                continue  # пустые строки и комментарии ": OPENROUTER PROCESSING"
            payload = line[5:].strip()
            if payload == "[DONE]":
                return
            try:
                data = json.loads(payload)
            except ValueError:
                continue
            if "error" in data:
                raise LLMError(f"openrouter stream error: {str(data['error'])[:200]}")
            for choice in data.get("choices") or []:
                text = (choice.get("delta") or {}).get("content")
                if text:
                    yield text

    async def _open_stream(self, model: str, req: LLMRequest):
        """Открывает стрим и ждёт первый кусок текста (на этом этапе работают повтор/fallback)."""
        request = self.client.build_request(
            "POST",
            f"{self.base_url}/chat/completions",
            json=self._body(model, req, req.user, stream=True),
        )
        try:
            r = await self.client.send(request, stream=True)
        except httpx.HTTPError as exc:
            raise RetryableError(f"openrouter {model}: {type(exc).__name__}") from exc
        try:
            if r.status_code != 200:
                self._raise_for_status(model, r, (await r.aread()).decode(errors="replace"))
            deltas = self._deltas(r.aiter_lines())
            try:
                first = await deltas.__anext__()
            except StopAsyncIteration:
                first = None
        except httpx.HTTPError as exc:
            await r.aclose()
            raise RetryableError(f"openrouter {model}: {type(exc).__name__}") from exc
        except BaseException:
            await r.aclose()
            raise
        return r, deltas, first

    async def answer_stream(self, req: LLMRequest) -> AsyncIterator[str]:
        """Сырые куски JSON-ответа. Повтор/резервная модель — до первого куска."""
        try:
            (response, deltas, first), model = await run_with_fallback(
                self._models(self.model),
                lambda m: self._open_stream(m, req),
                deadline_s=req.deadline_s,
                hedge_after_s=self.hedge_after_s,
                retry_min_s=self.retry_min_s,
            )
        except RetryableError as exc:
            raise LLMError(str(exc)) from exc
        req.info["model"] = model
        try:
            if first is not None:
                yield first
            async for text in deltas:
                yield text
        except httpx.HTTPError as exc:
            raise LLMError(f"openrouter {model} stream: {type(exc).__name__}") from exc
        finally:
            await deltas.aclose()
            await response.aclose()
