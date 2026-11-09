"""Индекс поиска Курултая: что должно лежать в kurultai_chunks и как его держать свежим.

Фрагменты:
- `bill` — законопроект (номер, название, статус, дата публикации);
- `request` — депутатский запрос (дата, номер, тема, авторы);
- `committee` — комитет с составом (ФИО, должности);
- `plan` — недельный и основной планы (название);
- `material` — опубликованный и не истёкший материал админки: №0 — справка (brief), №1..N — полный текст
  исходника (source_text), нарезка как у документов Astana Hub (`chunk_document`, ~2400 знаков с перекрытием).
Обновление: раз в KURULTAI_RAG_REINDEX_S — сравнение по sha256 (заголовок + текст); эмбеддинг считается только
для новых и изменившихся фрагментов, исчезнувшие удаляются. Ошибка эмбеддинга — фрагмент без вектора (найдётся
по словам), следующий проход попробует снова.
"""

from __future__ import annotations

import asyncio
import hashlib
import time
from dataclasses import dataclass, field
from datetime import UTC, datetime

from sqlalchemy import delete, select, tuple_
from sqlalchemy.dialects.postgresql import insert
from sqlalchemy.engine import Engine
from sqlalchemy.orm import Session

from app.db.models import KurultaiChunkRow, KurultaiMaterialRow
from app.knowledge.chunks import chunk_document
from app.kurultai.quryltai import _int, _list, _one_line, _text, format_epoch, status_name
from app.llm.embeddings import Embedder
from app.logging_setup import logger

ChunkKey = tuple[str, str, int]  # source, source_id, chunk_no


@dataclass(frozen=True)
class Chunk:
    source: str
    source_id: str
    chunk_no: int
    title: str
    content: str

    @property
    def key(self) -> ChunkKey:
        return (self.source, self.source_id, self.chunk_no)

    @property
    def content_hash(self) -> str:
        return hashlib.sha256(f"{self.title}\n{self.content}".encode()).hexdigest()


def snapshot_chunks(data: dict | None) -> list[Chunk]:
    """Снимок quryltai.kz → фрагменты (по одному на законопроект, запрос, комитет, план)."""
    if not isinstance(data, dict):
        return []
    out: list[Chunk] = []
    for bill in _list(data.get("bills")):
        if not isinstance(bill, dict) or not _int(bill, "id"):
            continue
        title = _one_line(_text(bill, "title"))
        out.append(Chunk("bill", str(_int(bill, "id")), 0, f"Законопроект: {title}",
                         f"Законопроект «{title}». Статус: {status_name(_int(bill, 'status'))}. "
                         f"Опубликован на quryltai.kz {format_epoch(_int(bill, 'publish_date'))}."))  # fmt: skip
    for req in _list(data.get("deputy_requests")):
        if not isinstance(req, dict) or not _int(req, "id"):
            continue
        authors = _text(req, "authors")
        out.append(Chunk("request", str(_int(req, "id")), 0,
                         f"Депутатский запрос {_text(req, 'number')}",
                         f"Депутатский запрос № {_text(req, 'number')} от {_text(req, 'date')}: "
                         f"{_text(req, 'title')}." + (f" Авторы: {authors}." if authors else "")))  # fmt: skip
    for com in _list(data.get("committees")):
        if not isinstance(com, dict):
            continue
        members = [d for d in _list(com.get("deputies")) if isinstance(d, dict)]
        lines = [_text(d, "name") + (f" — {_text(d, 'position')}" if _text(d, "position") else "")
                 for d in members]  # fmt: skip
        out.append(Chunk("committee", str(_int(com, "id") or _text(com, "name")), 0,
                         f"Комитет: {_text(com, 'name')}",
                         f"{_text(com, 'name')} Курултая Республики Казахстан. Членов: {len(members)}. "
                         f"Состав: {'; '.join(lines)}."))  # fmt: skip
    for kind, key in (
        ("Недельный план", "weekly_plans"),
        ("Основной план мероприятий", "main_plans"),
    ):
        for i, plan in enumerate(p for p in _list(data.get(key)) if isinstance(p, dict)):
            title = _one_line(_text(plan, "title"))
            if title:
                out.append(Chunk("plan", f"{key}:{_int(plan, 'id') or i}", 0, f"{kind}: {title}",
                                 f"{kind} Курултая: {title}."))  # fmt: skip
    return out


def material_chunks(rows: list[KurultaiMaterialRow]) -> list[Chunk]:
    """Опубликованные материалы → справка (№0) и полный текст исходника (№1..N)."""
    out: list[Chunk] = []
    for r in rows:
        sid = str(r.id)
        head = r.title or "Материал аппарата Курултая"
        if r.event_date:
            head += f" (дата события {r.event_date.strftime('%d.%m.%Y')})"
        if (r.brief or "").strip():
            out.append(Chunk("material", sid, 0, head, r.brief.strip()))
        for n, (title, content) in enumerate(chunk_document(head, r.source_text or ""), 1):
            if content.strip():
                out.append(Chunk("material", sid, n, title, content.strip()))
    return out


def live_materials(engine: Engine, now: datetime | None = None) -> list[KurultaiMaterialRow]:
    """Опубликованные и не истёкшие материалы (как в промпте у агента Курултая)."""
    now = now or datetime.now(UTC)
    with Session(engine) as s:
        rows = list(
            s.scalars(select(KurultaiMaterialRow).where(KurultaiMaterialRow.status == "published"))
        )
        for r in rows:
            s.expunge(r)
    return [r for r in rows if r.valid_until is None or r.valid_until >= now]


@dataclass
class IndexReport:
    embedded: int = 0
    unchanged: int = 0
    removed: int = 0
    failed: int = 0
    by_source: dict[str, int] = field(default_factory=dict)
    ms: int = 0


class KurultaiIndexer:
    def __init__(
        self, engine: Engine, embedder: Embedder | None, quryltai=None, batch_size: int = 64
    ) -> None:
        self.engine = engine
        self.embedder = embedder
        self.quryltai = quryltai
        self.batch_size = batch_size
        self.last: IndexReport | None = None

    def wanted(self) -> list[Chunk]:
        data = getattr(self.quryltai, "data", None) if self.quryltai is not None else None
        return snapshot_chunks(data) + material_chunks(live_materials(self.engine))

    def index(self) -> IndexReport:
        started = time.perf_counter()
        report = IndexReport()
        chunks = {c.key: c for c in self.wanted()}
        for c in chunks.values():
            report.by_source[c.source] = report.by_source.get(c.source, 0) + 1
        model = self.embedder.model if self.embedder is not None else None
        with Session(self.engine) as s:
            existing = {
                (r.source, r.source_id, r.chunk_no): (r.content_hash, r.embedding_model, r.embedding is not None)
                for r in s.execute(select(KurultaiChunkRow.source, KurultaiChunkRow.source_id,
                                          KurultaiChunkRow.chunk_no, KurultaiChunkRow.content_hash,
                                          KurultaiChunkRow.embedding_model,
                                          KurultaiChunkRow.embedding.isnot(None).label("embedding")))
            }  # fmt: skip
        gone = [k for k in existing if k not in chunks]
        todo = [c for k, c in chunks.items()
                if k not in existing or existing[k][0] != c.content_hash
                or (model is not None and (existing[k][1] != model or not existing[k][2]))]  # fmt: skip
        report.unchanged = len(chunks) - len(todo)
        for i in range(0, len(todo), self.batch_size):
            batch = todo[i : i + self.batch_size]
            vectors: list[list[float] | None] = [None] * len(batch)
            if self.embedder is not None:
                try:
                    vectors = list(
                        self.embedder.embed_documents([(c.title, c.content) for c in batch])
                    )
                    report.embedded += len(batch)
                except (
                    Exception
                ) as exc:  # без вектора — найдётся по словам; следующий проход повторит
                    logger.warning("kurultai_rag_embed_failed", extra={"error": type(exc).__name__})
                    report.failed += len(batch)
            self._upsert(batch, vectors, model if vectors[0] is not None else None)
        if gone:
            key = tuple_(
                KurultaiChunkRow.source, KurultaiChunkRow.source_id, KurultaiChunkRow.chunk_no
            )
            with Session(self.engine) as s, s.begin():
                for i in range(0, len(gone), 500):  # одним запросом, партиями
                    s.execute(delete(KurultaiChunkRow).where(key.in_(gone[i : i + 500])))
            report.removed = len(gone)
        report.ms = int((time.perf_counter() - started) * 1000)
        self.last = report
        if todo or gone:
            logger.info("kurultai_rag_indexed", extra={
                "embedded": report.embedded, "unchanged": report.unchanged, "removed": report.removed,
                "failed": report.failed, "chunks": report.by_source, "ms": report.ms})  # fmt: skip
        return report

    def _upsert(self, batch: list[Chunk], vectors: list, model: str | None) -> None:
        now = datetime.now(UTC)
        with Session(self.engine) as s, s.begin():
            for c, vec in zip(batch, vectors, strict=True):
                values = {"source": c.source, "source_id": c.source_id, "chunk_no": c.chunk_no,
                          "title": c.title, "content": c.content, "content_hash": c.content_hash,
                          "embedding": vec, "embedding_model": model if vec is not None else None,
                          "updated_at": now}  # fmt: skip
                stmt = insert(KurultaiChunkRow).values(**values)
                s.execute(stmt.on_conflict_do_update(
                    constraint="uq_kurultai_chunks_source_source_id_chunk_no",
                    set_={k: stmt.excluded[k] for k in values if k not in ("source", "source_id", "chunk_no")}))  # fmt: skip

    async def run_periodic(self, interval_s: float) -> None:
        """Фоном: сразу и раз в interval_s (снимок обновляется раз в час, материалы — по публикации)."""
        while True:
            try:
                await asyncio.to_thread(self.index)
            except Exception:
                logger.exception("kurultai_rag_index_failed")
            await asyncio.sleep(interval_s)
