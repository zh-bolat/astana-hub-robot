"""Модель базы знаний (не зависит от источника: YAML-файлы, Google Sheets, Postgres)."""

from __future__ import annotations

from datetime import datetime

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
    floor: int | None = None

    @property
    def source_id(self) -> str:
        return f"place:{self.id}"


class QuickQuestion(BaseModel):
    id: str
    text: LocalizedText


class Event(BaseModel):
    """Мероприятие (таблица events; в промпт пока не попадает — для агента, шаг 3)."""

    id: str
    title: LocalizedText
    description: LocalizedText = Field(default_factory=LocalizedText)
    starts_at: datetime
    ends_at: datetime | None = None
    place_id: str | None = None


class KnowledgeBase(BaseModel):
    version: str = ""
    facts: list[Fact] = Field(default_factory=list)
    places: list[Place] = Field(default_factory=list)
    quick_questions: list[QuickQuestion] = Field(default_factory=list)
    events: list[Event] = Field(default_factory=list)

    def place(self, place_id: str | None) -> Place | None:
        if not place_id:
            return None
        return next((p for p in self.places if p.id == place_id), None)

    def find_place(self, query: str | None) -> Place | None:
        """Место по id, `place:<id>`, map_name, названию или синониму (без учёта регистра).

        Для инструментов агента: модель иногда передаёт "lift", «Лифт» или "elevator" вместо "place:lift".
        Сначала точное совпадение, затем — одно место, у которого совпадает начало/вхождение.
        """
        if not query:
            return None
        q = query.strip().removeprefix("place:").strip().casefold()
        if not q:
            return None
        if (exact := self.place(q)) is not None:
            return exact

        def terms(p: Place) -> list[str]:
            vals = [p.id, p.map_name, *p.names.model_dump().values(), *p.synonyms]
            return [v.casefold() for v in vals if v]

        for p in self.places:
            if q in terms(p):
                return p
        partial = [p for p in self.places if any(q in t or t in q for t in terms(p) if len(t) >= 3)]
        return partial[0] if len(partial) == 1 else None

    def source_ids(self) -> set[str]:
        return {f.source_id for f in self.facts} | {p.source_id for p in self.places}
