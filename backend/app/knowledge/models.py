"""Модель базы знаний (не зависит от источника: YAML-файлы, Google Sheets, Postgres)."""

from __future__ import annotations

from pydantic import BaseModel, Field

from app.lang import Lang


class LocalizedText(BaseModel):
    ru: str = ""
    kk: str = ""
    en: str = ""

    def get(self, lang: Lang) -> str:
        """Текст на нужном языке с фолбэком ru → en → kk."""
        for candidate in (lang, "ru", "en", "kk"):
            value = getattr(self, candidate, "")
            if value:
                return value
        return ""


class Fact(BaseModel):
    id: str
    tags: list[str] = Field(default_factory=list)
    keywords: list[str] = Field(default_factory=list)  # синонимы/ключевые слова для поиска
    question: LocalizedText = Field(default_factory=LocalizedText)
    answer: LocalizedText

    @property
    def source_id(self) -> str:
        return f"fact:{self.id}"


class Place(BaseModel):
    id: str
    map_name: str
    names: LocalizedText
    synonyms: list[str] = Field(default_factory=list)
    description: LocalizedText = Field(default_factory=LocalizedText)
    # Подсказки для vision-модели: как помещение выглядит (шаг 6.3; позже — эталонные фото)
    visual_hints: str = ""
    photos: list[str] = Field(default_factory=list)

    @property
    def source_id(self) -> str:
        return f"place:{self.id}"


class QuickQuestion(BaseModel):
    id: str
    text: LocalizedText


class KnowledgeBase(BaseModel):
    version: str = ""
    facts: list[Fact] = Field(default_factory=list)
    places: list[Place] = Field(default_factory=list)
    quick_questions: list[QuickQuestion] = Field(default_factory=list)

    def place(self, place_id: str | None) -> Place | None:
        if not place_id:
            return None
        return next((p for p in self.places if p.id == place_id), None)

    def source_ids(self) -> set[str]:
        return {f.source_id for f in self.facts} | {p.source_id for p in self.places}
