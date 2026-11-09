"""Знания робота Курултая для Realtime: индекс (фоном), поиск (инструмент search_kurultai) и сводка (промпт).

Индекс обновляется сразу после изменения материалов в админке (`request_reindex` — её вызывает
`MaterialsStore.on_change`) и раз в KURULTAI_RAG_REINDEX_S на всякий случай (снимок quryltai.kz, истёкшие сроки).
"""

from __future__ import annotations

import asyncio
import contextlib
import time
from datetime import UTC, datetime

from sqlalchemy import func, select
from sqlalchemy.engine import Engine
from sqlalchemy.orm import Session

from app.db.models import KurultaiChunkRow
from app.kurultai.rag.index import KurultaiIndexer, live_materials
from app.kurultai.rag.search import KurultaiSearch, format_hits
from app.kurultai.rag.summary import build_summary
from app.llm.embeddings import Embedder
from app.logging_setup import logger

SUMMARY_TTL_S = 30.0
REINDEX_DEBOUNCE_S = 1.0  # несколько правок подряд — один проход

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
        self._loop: asyncio.AbstractEventLoop | None = None
        self._wake: asyncio.Event | None = None

    def request_reindex(self) -> None:
        """Материалы изменились: сводка — заново, индекс — ближайшим проходом (можно из любого потока)."""
        self._summary = None
        if self._loop is not None and self._wake is not None:
            self._loop.call_soon_threadsafe(self._wake.set)

    async def run_periodic(self, interval_s: float) -> None:
        """Фоном: сразу при старте, затем по `request_reindex` или раз в interval_s."""
        self._loop = asyncio.get_running_loop()
        self._wake = asyncio.Event()
        while True:
            self._wake.clear()
            try:
                await asyncio.to_thread(self.indexer.index)
            except Exception:
                logger.exception("kurultai_rag_index_failed")
            self._summary = None
            with contextlib.suppress(TimeoutError):
                await asyncio.wait_for(self._wake.wait(), interval_s)
            if self._wake.is_set():
                await asyncio.sleep(REINDEX_DEBOUNCE_S)

    def material_chunks(self) -> dict[int, int]:
        """Сколько фрагментов каждого материала сейчас в поиске (админка: «в поиске» / «ждёт индексации»)."""
        with Session(self.engine) as s:
            rows = s.execute(
                select(KurultaiChunkRow.source_id, func.count())
                .where(KurultaiChunkRow.source == "material")
                .group_by(KurultaiChunkRow.source_id)
            )
            return {int(sid): n for sid, n in rows if str(sid).isdigit()}

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
