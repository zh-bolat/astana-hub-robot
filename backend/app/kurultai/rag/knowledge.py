"""Знания робота Курултая для Realtime: индекс (фоном), поиск (инструмент search_kurultai) и сводка (промпт)."""

from __future__ import annotations

import time
from datetime import UTC, datetime

from sqlalchemy.engine import Engine

from app.kurultai.rag.index import KurultaiIndexer, live_materials
from app.kurultai.rag.search import KurultaiSearch, format_hits
from app.kurultai.rag.summary import build_summary
from app.llm.embeddings import Embedder

SUMMARY_TTL_S = 30.0

TOOL = {
    "type": "function",
    "name": "search_kurultai",
    "description": (
        "Найти в данных Курултая подробности: законопроекты (названия, статусы), депутатские запросы (темы, "
        "номера, авторы), состав комитетов (ФИО, должности), планы, повестки и заседания, материалы аппарата "
        "(доклады, справки, Регламент Курултая, правила, отчёты). Возвращает найденные фрагменты."
    ),
    "parameters": {
        "type": "object",
        "properties": {"query": {"type": "string", "description": "Что найти, по-русски, кратко"}},
        "required": ["query"],
    },
}


class KurultaiKnowledge:
    def __init__(self, engine: Engine, embedder: Embedder | None, quryltai, query_timeout_s: float = 1.0,
                 batch_size: int = 64) -> None:  # fmt: skip
        self.engine = engine
        self.quryltai = quryltai
        self.indexer = KurultaiIndexer(engine, embedder, quryltai, batch_size)
        self.search_engine = KurultaiSearch(engine, embedder, query_timeout_s)
        self._summary: tuple[float, str] | None = None

    def summary(self) -> str:
        now = time.monotonic()
        if self._summary is None or now - self._summary[0] > SUMMARY_TTL_S:
            data = getattr(self.quryltai, "data", None)
            text = build_summary(data, live_materials(self.engine), datetime.now(UTC))
            self._summary = (now, text)
        return self._summary[1]

    async def search(self, query: str, k: int = 5) -> str:
        hits = await self.search_engine.search(query, k)
        return format_hits(hits, query)
