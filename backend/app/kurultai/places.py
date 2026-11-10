"""Точки карты робота Курултая (knowledge/kurultai_places.yaml, docs/kurultai/navigation.md).

Сервер решает, куда робот может проводить: модель Realtime вызывает `guide_to(place_id)`, сервер находит точку
(по id, имени на карте или синониму, без регистра) и шлёт роботу `action guide_to` с `map_name` РОВНО как на
карте. В `hello` робот присылает список имён точек своей карты (`places`) — остальные считаются недоступными.
Служебная точка `home` («Пункт приема», пост робота): целью guide_to не бывает и посетителям не предлагается, но
в кадрах `state` от робота (авто-возврат на пост) распознаётся — для журнала.
"""

from __future__ import annotations

from collections.abc import Iterable
from dataclasses import dataclass, field
from pathlib import Path

import yaml

from app.lang import Lang
from app.logging_setup import logger

FILE_NAME = "kurultai_places.yaml"


def _norm(text: str) -> str:
    return " ".join(str(text).lower().replace("ё", "е").split())


@dataclass(frozen=True)
class Place:
    id: str
    map_name: str
    names: dict[str, str]
    synonyms: tuple[str, ...] = ()
    description: dict[str, str] = field(default_factory=dict)
    service: bool = False  # пост робота (home): посетителям не предлагать, целью guide_to не бывает

    def name(self, lang: Lang | str) -> str:
        """Название на языке ответа (en → ru: робот говорит только ru/kk)."""
        return self.names.get(lang) or self.names.get("ru") or self.id

    def describe(self, lang: Lang | str) -> str:
        return self.description.get(lang) or self.description.get("ru") or ""

    def search_keys(self) -> set[str]:
        """Всё, по чему точку ищут: id, имя на карте, названия, синонимы."""
        return {
            _norm(k) for k in (self.id, self.map_name, *self.names.values(), *self.synonyms) if k
        }


class KurultaiPlaces:
    def __init__(self, places: Iterable[Place] = ()) -> None:
        self.places: list[Place] = list(places)
        self._index: dict[str, Place] = {}
        for p in self.places:
            for k in p.search_keys():
                self._index.setdefault(k, p)

    def __len__(self) -> int:
        return len(self.places)

    def __bool__(self) -> bool:
        return bool(self.places)

    def ids(self) -> list[str]:
        return [p.id for p in self.targets()]

    def get(self, place_id: str) -> Place | None:
        return next((p for p in self.places if p.id == place_id), None)

    def find(self, query: str | None, *, targets_only: bool = True) -> Place | None:
        """Точка по id / имени на карте / названию / синониму (без регистра, «ё» = «е»); нет — None.
        targets_only — только куда можно проводить (служебный пост робота не считается)."""
        if not query:
            return None
        place = self._index.get(_norm(query))
        if place is not None and targets_only and place.service:
            return None
        return place

    def targets(self) -> list[Place]:
        """Куда можно проводить посетителя (без служебных точек)."""
        return [p for p in self.places if not p.service]

    def available(self, map_names: Iterable[str] | None) -> KurultaiPlaces:
        """Только точки, которые есть на карте робота (hello.places; None — считаем, что есть все)."""
        if map_names is None:
            return self
        wanted = {_norm(n) for n in map_names if isinstance(n, str)}
        return KurultaiPlaces(
            p for p in self.places if _norm(p.map_name) in wanted or _norm(p.id) in wanted
        )

    def prompt_block(self, lang: Lang | str) -> str:
        """Список точек для инструкций модели: id — название (синонимы)."""
        lines = []
        for p in self.targets():
            syn = ", ".join(p.synonyms[:8])
            tail = f" — {p.describe(lang)}" if p.describe(lang) else ""
            lines.append(f"- {p.id}: {p.name(lang)}{tail} (так же называют: {syn})")
        return "\n".join(lines)


def load_places(path: Path) -> KurultaiPlaces:
    """Точки из yaml; файла нет или он битый — пусто (навигации не будет, остальное работает)."""
    try:
        raw = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    except FileNotFoundError:
        logger.warning("kurultai_places_missing", extra={"path": str(path)})
        return KurultaiPlaces()
    except (OSError, yaml.YAMLError) as exc:
        logger.warning("kurultai_places_invalid", extra={"error": type(exc).__name__})
        return KurultaiPlaces()
    places: list[Place] = []
    for item in raw.get("places") or []:
        if not isinstance(item, dict) or not item.get("id") or not item.get("map_name"):
            continue
        names = item.get("names") or {}
        places.append(
            Place(
                id=str(item["id"]),
                map_name=str(item["map_name"]),
                names={str(k): str(v) for k, v in names.items() if v},
                synonyms=tuple(str(s) for s in item.get("synonyms") or [] if s),
                description={
                    str(k): str(v) for k, v in (item.get("description") or {}).items() if v
                },
                service=bool(item.get("service", False)),
            )
        )
    return KurultaiPlaces(places)
