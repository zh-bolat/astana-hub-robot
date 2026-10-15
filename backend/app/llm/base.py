"""Интерфейс LLM-провайдера (текст + vision). Провайдер и модель выбираются через .env."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Protocol

from pydantic import BaseModel, Field

from app.knowledge.models import KnowledgeBase


class LLMError(Exception):
    """Провайдер недоступен / вернул ошибку → 503 llm_unavailable."""


class LLMAnswer(BaseModel):
    """Структурированный ответ модели (JSON-режим)."""

    found: bool
    # kb | small_talk | out_of_scope (None — модель/провайдер категорию не вернули)
    category: str | None = None
    answer: str = ""
    lang: str | None = None
    sources: list[str] = Field(default_factory=list)
    # Какая модель фактически ответила (заполняет провайдер, не модель)
    model: str | None = None


@dataclass
class LLMRequest:
    system: str
    user: str
    # Сырые данные для FakeProvider/отладки; реальные провайдеры используют только system/user/image
    question: str = ""
    lang: str | None = None
    place_id: str | None = None
    kb: KnowledgeBase | None = None
    image: bytes | None = field(default=None, repr=False)
    image_mime: str = "image/jpeg"
    # Общий бюджет времени на ответ (повторы/резервные модели укладываются в него)
    deadline_s: float = 6.5
    # Провайдер может записать сюда сведения о вызове (например, какая модель ответила в стриме)
    info: dict = field(default_factory=dict)


class LLMProvider(Protocol):
    name: str
    model: str

    async def answer(self, req: LLMRequest) -> LLMAnswer:
        """Текстовый ответ по базе (/ask)."""
        ...

    async def describe_image(self, req: LLMRequest) -> LLMAnswer:
        """Ответ по кадру с камеры (/look). `req.image` обязателен."""
        ...


def parse_answer_json(raw: str) -> LLMAnswer:
    """Разбор JSON-ответа модели с допуском ```json-обёрток."""
    text = (raw or "").strip()
    if text.startswith("```"):
        text = text.strip("`")
        if text.lower().startswith("json"):
            text = text[4:]
    start, end = text.find("{"), text.rfind("}")
    if start == -1 or end == -1:
        raise LLMError("LLM returned non-JSON output")
    try:
        return LLMAnswer.model_validate_json(text[start : end + 1])
    except Exception as exc:
        raise LLMError(f"LLM returned invalid JSON: {exc}") from exc
