"""DeepSeek через OpenAI-совместимый API (заготовка: только текст, vision у DeepSeek нет)."""

from __future__ import annotations

from openai import AsyncOpenAI

from app.llm.base import LLMAnswer, LLMError, LLMRequest, parse_answer_json


class DeepSeekProvider:
    name = "deepseek"

    def __init__(self, api_key: str, base_url: str, model: str, max_tokens: int = 320) -> None:
        self.client = AsyncOpenAI(api_key=api_key, base_url=base_url, max_retries=0)
        self.model = model
        self.max_tokens = max_tokens

    async def answer(self, req: LLMRequest) -> LLMAnswer:
        try:
            resp = await self.client.chat.completions.create(
                model=self.model,
                messages=[
                    {"role": "system", "content": req.system},
                    {"role": "user", "content": req.user},
                ],
                temperature=0.2,
                max_tokens=self.max_tokens,
                response_format={"type": "json_object"},
            )
        except Exception as exc:
            raise LLMError(f"deepseek error: {type(exc).__name__}: {exc}") from exc
        return parse_answer_json(resp.choices[0].message.content or "")

    async def describe_image(self, req: LLMRequest) -> LLMAnswer:
        raise LLMError("DeepSeek has no vision API; use VISION_PROVIDER=gemini")
