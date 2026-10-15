"""Источники базы знаний и хранилище с горячей перезагрузкой.

`KnowledgeSource` — интерфейс: `fingerprint()` (дешёвая проверка «изменилось ли») и `load()`.
Сейчас есть `YamlKnowledgeSource` (папка backend/knowledge/). Синк из Google Sheets (шаг 3.3)
добавляется новой реализацией источника (или выгрузкой таблицы в Postgres + `DbKnowledgeSource`),
API и остальной код при этом не меняются.

`KnowledgeStore` отдаёт актуальную базу и перечитывает её при изменении источника —
перезапуск сервера (и тем более робота) не нужен.
"""

from __future__ import annotations

import hashlib
import threading
import time
from pathlib import Path
from typing import Protocol

import yaml

from app.knowledge.models import Fact, KnowledgeBase, Place, QuickQuestion
from app.logging_setup import logger


class KnowledgeSource(Protocol):
    def fingerprint(self) -> str: ...

    def load(self) -> KnowledgeBase: ...


class YamlKnowledgeSource:
    FILES = ("facts.yaml", "places.yaml", "quick_questions.yaml")

    def __init__(self, directory: Path) -> None:
        self.directory = Path(directory)

    def fingerprint(self) -> str:
        h = hashlib.sha256()
        for name in self.FILES:
            path = self.directory / name
            if path.exists():
                st = path.stat()
                h.update(f"{name}:{st.st_mtime_ns}:{st.st_size};".encode())
        return h.hexdigest()

    def _read(self, name: str) -> dict:
        path = self.directory / name
        if not path.exists():
            return {}
        data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
        if not isinstance(data, dict):
            raise ValueError(f"{name}: expected a mapping at top level")
        return data

    def load(self) -> KnowledgeBase:
        facts = [Fact.model_validate(x) for x in self._read("facts.yaml").get("facts", [])]
        places = [Place.model_validate(x) for x in self._read("places.yaml").get("places", [])]
        qq = [
            QuickQuestion.model_validate(x)
            for x in self._read("quick_questions.yaml").get("items", [])
        ]
        return build_kb(facts, places, qq)


def build_kb(
    facts: list[Fact], places: list[Place], quick_questions: list[QuickQuestion]
) -> KnowledgeBase:
    """Собирает базу, проверяет уникальность id и считает версию по содержимому."""
    _check_unique("fact", [f.id for f in facts])
    _check_unique("place", [p.id for p in places])
    content = KnowledgeBase(facts=facts, places=places, quick_questions=quick_questions)
    content.version = hashlib.sha256(content.model_dump_json().encode()).hexdigest()[:16]
    return content


def _check_unique(kind: str, ids: list[str]) -> None:
    seen: set[str] = set()
    for i in ids:
        if i in seen:
            raise ValueError(f"duplicate {kind} id: {i}")
        seen.add(i)


class KnowledgeStore:
    def __init__(self, source: KnowledgeSource, reload_interval_s: float = 5.0) -> None:
        self.source = source
        self.reload_interval_s = reload_interval_s
        self._lock = threading.Lock()
        self._kb: KnowledgeBase | None = None
        self._fingerprint: str | None = None
        self._checked_at = 0.0

    def get(self) -> KnowledgeBase:
        now = time.monotonic()
        if self._kb is not None and now - self._checked_at < self.reload_interval_s:
            return self._kb
        with self._lock:
            self._checked_at = now
            fp = self.source.fingerprint()
            if self._kb is None or fp != self._fingerprint:
                try:
                    kb = self.source.load()
                except Exception as exc:
                    if self._kb is None:
                        raise
                    # Сломанный файл после правки не должен ронять сервис — работаем на старой версии
                    logger.error("knowledge_reload_failed", extra={"error": str(exc)})
                    self._fingerprint = fp
                    return self._kb
                self._kb, self._fingerprint = kb, fp
                logger.info(
                    "knowledge_loaded",
                    extra={
                        "kb_version": kb.version,
                        "facts": len(kb.facts),
                        "places": len(kb.places),
                        "quick_questions": len(kb.quick_questions),
                    },
                )
            return self._kb
