"""Гибридный поиск по kurultai_chunks — тот же метод, что у базы Astana Hub (`app/knowledge/search.py`):
по смыслу (pgvector, косинус) + по словам (полнотекст, BM25) → слияние RRF. Общие функции (разбор слов вопроса,
BM25, RRF, выдержка) — оттуда же; таблица своя. Эмбеддинг вопроса не успел (таймаут) — ищем только по словам.
"""

from __future__ import annotations

import asyncio
import time
from dataclasses import dataclass

from sqlalchemy import bindparam, text
from sqlalchemy.engine import Engine

from app.knowledge.search import (
    CANDIDATES,
    RRF_K,
    _operand,
    _vector_literal,
    bm25,
    excerpt,
    query_terms,
    rrf,
    tsquery,
)
from app.llm.embeddings import Embedder
from app.logging_setup import logger

RESULT_CHARS = (
    6000  # всё, что уходит модели, на один вызов (глубокие ответы по документам пленарки)
)


@dataclass(frozen=True)
class Hit:
    source: str
    source_id: str
    chunk_no: int
    title: str
    content: str
    score: float


class KurultaiSearch:
    def __init__(
        self, engine: Engine, embedder: Embedder | None, query_timeout_s: float = 1.0
    ) -> None:
        self.engine = engine
        self.embedder = embedder
        self.query_timeout_s = query_timeout_s

    async def search(self, query: str, k: int = 5) -> list[Hit]:
        started = time.perf_counter()
        vec: list[float] | None = None
        if self.embedder is not None:
            try:
                vec = await asyncio.wait_for(self.embedder.embed_query(query), self.query_timeout_s)
            except Exception as exc:  # только по словам
                logger.warning(
                    "kurultai_rag_query_embed_failed", extra={"error": type(exc).__name__}
                )
        hits = await asyncio.to_thread(self._search_db, query, vec, k)
        logger.info("kurultai_rag_search", extra={
            "hits": [f"{h.source}:{h.source_id}#{h.chunk_no}" for h in hits], "vector": vec is not None,
            "ms": int((time.perf_counter() - started) * 1000)})  # fmt: skip
        return hits

    def _search_db(self, query: str, vec: list[float] | None, k: int) -> list[Hit]:
        terms = query_terms(query)
        lists: list[list[tuple]] = []
        with self.engine.connect() as conn:
            if vec is not None and self.embedder is not None:
                rows = conn.execute(text("""
                    WITH c AS MATERIALIZED (
                        SELECT id, source, source_id, chunk_no, embedding <=> CAST(:vec AS vector) AS dist
                        FROM kurultai_chunks WHERE embedding IS NOT NULL AND embedding_model = :model)
                    SELECT source, source_id, chunk_no FROM c ORDER BY dist, id LIMIT :n"""),
                    {"vec": _vector_literal(vec), "model": self.embedder.model, "n": CANDIDATES})  # fmt: skip
                lists.append([tuple(r) for r in rows])
            if terms:
                total, avg_len = conn.execute(
                    text("SELECT count(*), avg(length(tsv)) FROM kurultai_chunks")).one()  # fmt: skip
                matched = ", ".join(
                    f"tsv @@ to_tsquery('simple', :t{i}) AS m{i}" for i in range(len(terms))
                )
                rows = conn.execute(text(
                    f"SELECT source, source_id, chunk_no, length(tsv), {matched} FROM kurultai_chunks "
                    "WHERE tsv @@ to_tsquery('simple', :q) ORDER BY id"),
                    {"q": tsquery(terms), **{f"t{i}": _operand(t) for i, t in enumerate(terms)}}).all()  # fmt: skip
                lists.append(bm25(rows, len(terms), total, float(avg_len or 0))[:CANDIDATES])
            top = rrf(lists, RRF_K)[:k]
            if not top:
                return []
            found = {(r.source, r.source_id, r.chunk_no): r for r in conn.execute(text(
                "SELECT source, source_id, chunk_no, title, content FROM kurultai_chunks "
                "WHERE (source, source_id, chunk_no) IN :keys").bindparams(bindparam("keys", expanding=True)),
                {"keys": [key for key, _ in top]})}  # fmt: skip
        return [Hit(*key, title=found[key].title or "", content=found[key].content, score=round(score, 5))
                for key, score in top if key in found]  # fmt: skip


def format_hits(hits: list[Hit], query: str, budget: int = RESULT_CHARS) -> str:
    """Найденное — модели: заголовок и выдержка по словам вопроса, всё вместе ≤ budget."""
    if not hits:
        return "Ничего не нашлось в данных Курултая."
    out: list[str] = []
    left = budget
    for i, h in enumerate(hits):
        part = excerpt(h.content, query, max(200, left // (len(hits) - i)))
        out.append(f"[{h.title}]\n{part}")
        left = max(0, left - len(part) - len(h.title) - 4)
    return "\n\n".join(out)
