"""Индексация базы для поиска: активные факты, места и документы → kb_chunks с эмбеддингами.

`KbIndexer.index()` (блокирующий: фоновый поток сервера или CLI `kb index`):
1. Желаемые фрагменты (app/knowledge/chunks.py) сравниваются с kb_chunks по ключу
   (source_type, source_id, lang, chunk_no), content_hash и embedding_model.
2. Лишние фрагменты выключаются (is_active=false: поиск их не видит; вернулся тот же текст — включается без
   эмбеддинга), выключенные с тем же текстом — включаются. Это делается до эмбеддингов, поэтому выключенный
   факт пропадает из поиска даже при недоступном провайдере.
3. Эмбеддинги — только для новых и изменённых фрагментов (или всех с force), батчами; каждый батч пишется
   сразу. Ошибка провайдера → в лог, остальные фрагменты остаются старыми (до следующей попытки).
Повторный запуск без изменений — ноль вызовов эмбеддингов. Индексирует один процесс за раз
(pg_try_advisory_lock): второй пропускает ход, его догонит следующая проверка.

`KbIndexer.run_periodic()` (сервер, в фоне): раз в KNOWLEDGE_RELOAD_INTERVAL_S сверяет отпечаток базы —
версию последнего синка (kb_sync), отпечаток документов и модель эмбеддингов; при старте и после каждого
синка/сида, который что-то поменял, — индексация. Ошибка → повтор не раньше чем через RETRY_MIN_S (растёт
до RETRY_MAX_S). Старт сервера и ответы роботу индексацию не ждут.
"""

from __future__ import annotations

import asyncio
import time
from dataclasses import dataclass, field

from sqlalchemy import func, select, text, tuple_, update
from sqlalchemy.dialects.postgresql import insert
from sqlalchemy.engine import Connection, Engine

from app.db.models import DocumentRow, KbChunkRow
from app.knowledge.chunks import ChunkKey, ChunkSpec, DocumentSource, kb_chunks
from app.knowledge.pg import KnowledgeRepo, _orig
from app.llm.embeddings import Embedder, EmbeddingError
from app.logging_setup import logger

KB_INDEX_LOCK_KEY = 0x48554232  # "HUB2": одна индексация за раз (сервер, соседний контейнер, CLI)
RETRY_MIN_S = 60.0
RETRY_MAX_S = 600.0


@dataclass
class IndexReport:
    embedded: int = 0  # фрагментов с новым эмбеддингом
    unchanged: int = 0
    removed: int = 0  # выключено (нет больше в базе)
    restored: int = 0  # снова включено без эмбеддинга (тот же текст)
    failed: int = 0  # не удалось эмбеддить (провайдер) — остались старые
    calls: int = 0  # запросов к провайдеру эмбеддингов
    skipped: bool = False  # индексирует другой процесс
    error: str | None = None
    by_type: dict[str, int] = field(default_factory=dict)  # активных фрагментов по типам

    @property
    def ok(self) -> bool:
        return not self.skipped and self.error is None


@dataclass(frozen=True)
class _Existing:
    content_hash: str
    embedding_model: str | None
    has_embedding: bool
    is_active: bool


def active_documents(conn: Connection) -> list[DocumentSource]:
    t = DocumentRow.__table__
    rows = conn.execute(
        select(t.c.slug, t.c.title, t.c.lang, t.c.content, t.c.source_url)
        .where(t.c.is_active)
        .order_by(t.c.slug)
    )
    return [
        DocumentSource(r.slug, r.title, (r.lang or "ru").strip(), r.content, r.source_url)
        for r in rows
    ]


def documents_fingerprint(conn: Connection) -> str:
    t = DocumentRow.__table__
    row = conn.execute(
        select(
            func.count(),
            func.md5(
                func.string_agg(
                    t.c.slug + ":" + t.c.content_hash + ":" + func.coalesce(t.c.title, ""),
                    text("',' ORDER BY slug"),
                )
            ),
        ).where(t.c.is_active)
    ).one()
    return f"{row[0]}:{row[1] or ''}"


def chunk_counts(conn: Connection) -> dict[str, int]:
    """Активные фрагменты с эмбеддингом по типам (для kb status и отчёта)."""
    t = KbChunkRow.__table__
    rows = conn.execute(
        select(t.c.source_type, func.count())
        .where(t.c.is_active, t.c.embedding.is_not(None))
        .group_by(t.c.source_type)
    )
    return {r[0]: r[1] for r in rows}


class KbIndexer:
    def __init__(self, engine: Engine, embedder: Embedder, batch_size: int = 64) -> None:
        self.engine = engine
        self.embedder = embedder
        self.batch_size = batch_size
        self.repo = KnowledgeRepo(engine)
        self._indexed_state: str | None = None  # отпечаток базы последней удачной индексации
        self._retry_at = 0.0
        self._retry_s = RETRY_MIN_S
        self.last_report: IndexReport | None = None

    # ---------- индексация ----------
    def index(self, force: bool = False) -> IndexReport:
        # Блокировка сессии на отдельном соединении (без транзакции), работа — в своих транзакциях
        lock = self.engine.connect()
        try:
            lock.execution_options(isolation_level="AUTOCOMMIT")
            got = lock.execute(
                text("SELECT pg_try_advisory_lock(:k)"), {"k": KB_INDEX_LOCK_KEY}
            ).scalar()
            if not got:
                logger.info("kb_index_skipped_locked")
                return IndexReport(skipped=True)
            try:
                return self._index(force)
            finally:
                try:
                    lock.execute(text("SELECT pg_advisory_unlock(:k)"), {"k": KB_INDEX_LOCK_KEY})
                except Exception:
                    lock.invalidate()  # соединение закроется, блокировка снимется вместе с сессией
        finally:
            lock.close()

    def _index(self, force: bool) -> IndexReport:
        started = time.perf_counter()
        model = self.embedder.model
        with self.engine.connect() as conn:
            kb = self.repo.load_active(conn)
            desired = kb_chunks(kb, active_documents(conn))
            existing = self._existing(conn)

        report = IndexReport()
        to_embed: list[ChunkSpec] = []
        restore: list[ChunkKey] = []
        for key, spec in desired.items():
            old = existing.get(key)
            fresh = (
                old is not None
                and old.content_hash == spec.content_hash
                and old.embedding_model == model
                and old.has_embedding
            )
            if force or not fresh:
                to_embed.append(spec)
            elif not old.is_active:
                restore.append(key)
            else:
                report.unchanged += 1
        remove = [k for k, old in existing.items() if old.is_active and k not in desired]

        with self.engine.begin() as conn:
            report.removed = self._set_active(conn, remove, False)
            report.restored = self._set_active(conn, restore, True)

        for i in range(0, len(to_embed), self.batch_size):
            batch = to_embed[i : i + self.batch_size]
            try:
                vectors = self.embedder.embed_documents([(c.title, c.content) for c in batch])
                report.calls += 1
            except (EmbeddingError, OSError) as exc:
                report.failed = len(to_embed) - i
                report.error = f"{type(exc).__name__}: {str(exc)[:300]}"
                logger.error(
                    "kb_index_embeddings_failed",
                    extra={"error": report.error, "failed": report.failed, "model": model},
                )
                break
            with self.engine.begin() as conn:
                self._upsert(conn, batch, vectors, model)
            report.embedded += len(batch)

        with self.engine.connect() as conn:
            report.by_type = chunk_counts(conn)
        logger.info(
            "kb_indexed",
            extra={
                "model": model,
                "embedded": report.embedded,
                "unchanged": report.unchanged,
                "removed": report.removed,
                "restored": report.restored,
                "failed": report.failed,
                "chunks": report.by_type,
                "ms": int((time.perf_counter() - started) * 1000),
            },
        )
        self.last_report = report
        return report

    @staticmethod
    def _existing(conn: Connection) -> dict[ChunkKey, _Existing]:
        t = KbChunkRow.__table__
        rows = conn.execute(
            select(
                t.c.source_type,
                t.c.source_id,
                t.c.lang,
                t.c.chunk_no,
                t.c.content_hash,
                t.c.embedding_model,
                t.c.embedding.is_not(None).label("has_embedding"),
                t.c.is_active,
            )
        )
        return {
            (r.source_type, r.source_id, r.lang.strip(), r.chunk_no): _Existing(
                r.content_hash, r.embedding_model, r.has_embedding, r.is_active
            )
            for r in rows
        }

    @staticmethod
    def _set_active(conn: Connection, keys: list[ChunkKey], active: bool) -> int:
        if not keys:
            return 0
        t = KbChunkRow.__table__
        return conn.execute(
            update(t)
            .where(tuple_(t.c.source_type, t.c.source_id, t.c.lang, t.c.chunk_no).in_(keys))
            .values(is_active=active, updated_at=func.now())
        ).rowcount

    @staticmethod
    def _upsert(
        conn: Connection, batch: list[ChunkSpec], vectors: list[list[float]], model: str
    ) -> None:
        t = KbChunkRow.__table__
        rows = [
            {
                "source_type": c.source_type,
                "source_id": c.source_id,
                "lang": c.lang,
                "chunk_no": c.chunk_no,
                "title": c.title,
                "content": c.content,
                "content_hash": c.content_hash,
                "embedding": vec,
                "embedding_model": model,
                "is_active": True,
            }
            for c, vec in zip(batch, vectors, strict=True)
        ]
        stmt = insert(t).values(rows)
        conn.execute(
            stmt.on_conflict_do_update(
                index_elements=[t.c.source_type, t.c.source_id, t.c.lang, t.c.chunk_no],
                set_={
                    **{
                        c: stmt.excluded[c]
                        for c in (
                            "title",
                            "content",
                            "content_hash",
                            "embedding",
                            "embedding_model",
                        )
                    },
                    "is_active": True,
                    "updated_at": func.now(),
                },
            )
        )

    # ---------- автоматическая переиндексация (сервер) ----------
    def state(self) -> str:
        """Отпечаток того, что индексируется: версия базы (kb_sync), документы, модель."""
        with self.engine.connect() as conn:
            docs = documents_fingerprint(conn)
        return f"{self.repo.latest_version()}|{docs}|{self.embedder.model}"

    def check(self) -> IndexReport | None:
        """Индексация, если база изменилась с прошлой удачной (или её не было). None — делать нечего."""
        now = time.monotonic()
        if now < self._retry_at:
            return None
        try:
            state = self.state()
            if state == self._indexed_state:
                return None
            report = self.index()
        except Exception as exc:  # БД недоступна
            report = IndexReport(error=f"{type(exc).__name__}: {_orig(exc)[:300]}")
            logger.error("kb_index_failed", extra={"error": report.error})
        if report.ok:
            self._indexed_state, self._retry_s = state, RETRY_MIN_S
        elif not report.skipped:
            self._retry_at = now + self._retry_s
            self._retry_s = min(self._retry_s * 2, RETRY_MAX_S)
        return report

    async def run_periodic(self, interval_s: float) -> None:
        while True:
            await asyncio.to_thread(self.check)
            await asyncio.sleep(max(interval_s, 1.0))
