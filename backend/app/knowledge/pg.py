"""База знаний в Postgres: запись (синк таблица/YAML → БД) и чтение с кешем в памяти.

Запись (`KnowledgeRepo.apply`):
- одна транзакция под advisory-lock: upsert facts/places/quick_questions, исчезнувшие строки —
  `is_active=false` (строки с source='admin' синк не трогает), запись в `kb_sync`;
- ничего не изменилось → `kb_sync.status='skipped'`, иначе `ok` и `version` — хеш содержимого
  активной базы после записи (тот же, что `KnowledgeBase.version` при чтении);
- ошибка → откат и отдельная строка `kb_sync.status='error'`.

Чтение (`DbKnowledgeSource`): `fingerprint()`/`load()` работают только с памятью (не блокируют
event loop на /ask). `refresh()` (фоновый поток) раз в KNOWLEDGE_RELOAD_INTERVAL_S сверяет
последнюю `kb_sync.version` и перечитывает базу, только если она изменилась. БД недоступна
на старте → снимок последней загрузки на диске, иначе YAML.
"""

from __future__ import annotations

import os
import time
from dataclasses import dataclass, field
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from sqlalchemy import Table, func, or_, select, text, update
from sqlalchemy.dialects.postgresql import insert
from sqlalchemy.engine import Connection, Engine
from sqlalchemy.orm import Session

from app.db.models import EventRow, FactRow, KbSyncRow, PlaceRow, QuickQuestionRow
from app.knowledge.models import (
    Event,
    Fact,
    KnowledgeBase,
    LocalizedText,
    Place,
    QuickQuestion,
)
from app.knowledge.source import KnowledgeSource, build_kb
from app.logging_setup import logger

KB_WRITE_LOCK_KEY = 0x48554231  # "HUB1": один синк базы за раз (несколько контейнеров api)
# Синк заменяет всё, что пришло из таблицы/YAML; правки из будущей админки (source='admin') —
# не трогает
MANAGED_SOURCES = ("sheet", "yaml")
LANGS = ("ru", "kk", "en")


def _none(value: str | None) -> str | None:
    return value or None


def _loc_cols(prefix: str, loc: LocalizedText) -> dict[str, str | None]:
    return {f"{prefix}_{lang}": _none(getattr(loc, lang)) for lang in LANGS}


def _loc(row: Any, prefix: str) -> LocalizedText:
    return LocalizedText(**{lang: getattr(row, f"{prefix}_{lang}") or "" for lang in LANGS})


def _utc(value: datetime | None) -> datetime | None:
    return value.astimezone(UTC) if value is not None else None


def canonical_kb(kb: KnowledgeBase) -> KnowledgeBase:
    """Порядок как при чтении из БД (факты и места по id), чтобы версии совпадали."""
    return build_kb(
        sorted(kb.facts, key=lambda f: f.id),
        sorted(kb.places, key=lambda p: p.id),
        list(kb.quick_questions),
        sorted(kb.events, key=lambda e: (e.starts_at, e.id)),
    )


# ---------------------------------------------------------------- строки ↔ модели


def fact_values(f: Fact) -> dict[str, Any]:
    return {
        "id": f.id,
        "tags": list(f.tags),
        "keywords": list(f.keywords),
        **_loc_cols("question", f.question),
        **_loc_cols("answer", f.answer),
    }


def place_values(p: Place) -> dict[str, Any]:
    return {
        "id": p.id,
        "map_name": p.map_name,
        **_loc_cols("name", p.names),
        "synonyms": list(p.synonyms),
        **_loc_cols("description", p.description),
        "visual_hints": _none(p.visual_hints),
        "photos": list(p.photos),
        "floor": p.floor,
    }


def quick_question_values(q: QuickQuestion, order: int) -> dict[str, Any]:
    return {"id": q.id, **_loc_cols("text", q.text), "sort_order": order}


def event_values(e: Event) -> dict[str, Any]:
    return {
        "id": e.id,
        **_loc_cols("title", e.title),
        **_loc_cols("description", e.description),
        "starts_at": e.starts_at,
        "ends_at": e.ends_at,
        "place_id": e.place_id,
    }


def _fact(r: Any) -> Fact:
    return Fact(
        id=r.id,
        tags=list(r.tags),
        keywords=list(r.keywords),
        question=_loc(r, "question"),
        answer=_loc(r, "answer"),
    )


def _place(r: Any) -> Place:
    return Place(
        id=r.id,
        map_name=r.map_name,
        names=_loc(r, "name"),
        synonyms=list(r.synonyms),
        description=_loc(r, "description"),
        visual_hints=r.visual_hints or "",
        photos=list(r.photos),
        floor=r.floor,
    )


def _event(r: Any) -> Event:
    return Event(
        id=r.id,
        title=_loc(r, "title"),
        description=_loc(r, "description"),
        starts_at=_utc(r.starts_at),
        ends_at=_utc(r.ends_at),
        place_id=r.place_id,
    )


# ---------------------------------------------------------------- репозиторий


@dataclass
class SyncResult:
    status: str  # ok | skipped
    version: str
    counts: dict[str, dict[str, int]] = field(default_factory=dict)

    @property
    def changed(self) -> bool:
        return self.status == "ok"


class KnowledgeRepo:
    def __init__(self, engine: Engine) -> None:
        self.engine = engine

    # --- чтение ---
    def load_active(self, conn: Connection | None = None) -> KnowledgeBase:
        if conn is None:
            with self.engine.connect() as c:
                return self.load_active(c)
        facts = [
            _fact(r)
            for r in conn.execute(
                select(FactRow.__table__).where(FactRow.is_active).order_by(FactRow.id)
            )
        ]
        places = [
            _place(r)
            for r in conn.execute(
                select(PlaceRow.__table__).where(PlaceRow.is_active).order_by(PlaceRow.id)
            )
        ]
        qq = [
            QuickQuestion(id=r.id, text=_loc(r, "text"))
            for r in conn.execute(
                select(QuickQuestionRow.__table__)
                .where(QuickQuestionRow.is_active)
                .order_by(QuickQuestionRow.sort_order, QuickQuestionRow.id)
            )
        ]
        events = [
            _event(r)
            for r in conn.execute(
                select(EventRow.__table__)
                .where(EventRow.is_active)
                .order_by(EventRow.starts_at, EventRow.id)
            )
        ]
        return build_kb(facts, places, qq, events)

    def latest_version(self) -> str | None:
        """Версия базы по последней удачной (ok/skipped) синхронизации; None — синков не было."""
        stmt = (
            select(KbSyncRow.version)
            .where(KbSyncRow.status.in_(("ok", "skipped")))
            .order_by(KbSyncRow.id.desc())
            .limit(1)
        )
        with self.engine.connect() as conn:
            return conn.execute(stmt).scalar_one_or_none()

    def is_empty(self) -> bool:
        """В базе знаний нет ни одного факта (включая выключенные) — признак «новая БД»."""
        with self.engine.connect() as conn:
            return conn.execute(select(func.count()).select_from(FactRow)).scalar_one() == 0

    def sync_log(self, limit: int = 20) -> list[KbSyncRow]:
        with Session(self.engine) as s:
            return list(s.scalars(select(KbSyncRow).order_by(KbSyncRow.id.desc()).limit(limit)))

    # --- запись ---
    def apply(
        self,
        kb: KnowledgeBase,
        source: str,
        *,
        replace_events: bool = False,
        started_at: datetime | None = None,
    ) -> SyncResult:
        """Записывает базу из источника `source` (sheet | yaml). Мероприятия — только если
        `replace_events` (в таблице и YAML их пока нет: синк их не трогает)."""
        started_at = started_at or datetime.now(UTC)
        kb = canonical_kb(kb)  # проверка уникальности id и map_name
        try:
            with self.engine.begin() as conn:
                conn.execute(text("SELECT pg_advisory_xact_lock(:k)"), {"k": KB_WRITE_LOCK_KEY})
                counts = {
                    "facts": self._upsert(
                        conn, FactRow.__table__, [fact_values(f) for f in kb.facts], source
                    ),
                    "places": self._upsert(
                        conn, PlaceRow.__table__, [place_values(p) for p in kb.places], source
                    ),
                    "quick_questions": self._upsert(
                        conn,
                        QuickQuestionRow.__table__,
                        [quick_question_values(q, i) for i, q in enumerate(kb.quick_questions)],
                        source,
                    ),
                }
                if replace_events:
                    counts["events"] = self._upsert(
                        conn, EventRow.__table__, [event_values(e) for e in kb.events], source
                    )
                changed = any(
                    c["inserted"] or c["updated"] or c["deactivated"] for c in counts.values()
                )
                version = self.load_active(conn).version
                result = SyncResult("ok" if changed else "skipped", version, counts)
                conn.execute(
                    KbSyncRow.__table__.insert().values(
                        source=source,
                        started_at=started_at,
                        finished_at=datetime.now(UTC),
                        status=result.status,
                        version=version,
                        counts=counts,
                    )
                )
        except Exception as exc:
            self.record_error(source, exc, started_at)
            raise
        logger.info(
            "knowledge_db_synced",
            extra={
                "source": source,
                "status": result.status,
                "kb_version": version,
                "counts": counts,
            },
        )
        return result

    def record_error(self, source: str, exc: BaseException | str, started_at: datetime) -> None:
        """Строка kb_sync со статусом error (лучшее усилие: БД может быть недоступна)."""
        message = exc if isinstance(exc, str) else f"{type(exc).__name__}: {_orig(exc)}"
        try:
            with self.engine.begin() as conn:
                conn.execute(
                    KbSyncRow.__table__.insert().values(
                        source=source,
                        started_at=started_at,
                        finished_at=datetime.now(UTC),
                        status="error",
                        error=message[:2000],
                    )
                )
        except Exception as db_exc:
            logger.warning("kb_sync_error_not_recorded", extra={"error": _orig(db_exc)[:300]})

    @staticmethod
    def _upsert(
        conn: Connection, table: Table, rows: list[dict[str, Any]], source: str
    ) -> dict[str, int]:
        ids = [r["id"] for r in rows]
        # Сначала выключаем исчезнувшие — освобождаем map_name для новых точек
        deactivated = conn.execute(
            update(table)
            .where(
                table.c.is_active,
                table.c.source.in_(MANAGED_SOURCES),
                table.c.id.not_in(ids),
            )
            .values(is_active=False, updated_at=func.now())
        ).rowcount
        inserted = updated = 0
        if rows:
            cols = [c for c in rows[0] if c != "id"]
            stmt = insert(table).values([{**r, "source": source, "is_active": True} for r in rows])
            changed = [table.c[c].is_distinct_from(stmt.excluded[c]) for c in cols]
            changed.append(table.c.is_active.is_(False))
            changed.append(table.c.source != stmt.excluded.source)
            stmt = stmt.on_conflict_do_update(
                index_elements=[table.c.id],
                set_={
                    **{c: stmt.excluded[c] for c in cols},
                    "source": stmt.excluded.source,
                    "is_active": True,
                    "updated_at": func.now(),
                },
                where=or_(*changed),
            ).returning(text("(xmax = 0) AS inserted"))
            for (is_insert,) in conn.execute(stmt):
                if is_insert:
                    inserted += 1
                else:
                    updated += 1
        return {"inserted": inserted, "updated": updated, "deactivated": deactivated or 0}


def _orig(exc: BaseException) -> str:
    """Ошибка без параметров SQL: у SQLAlchemy берём исходную ошибку драйвера."""
    orig = getattr(exc, "orig", None)
    return str(orig) if orig is not None else str(exc)


# ---------------------------------------------------------------- источник для KnowledgeStore


class DbKnowledgeSource:
    """Знания из Postgres с кешем в памяти и инвалидацией по `kb_sync.version`."""

    def __init__(
        self,
        repo: KnowledgeRepo,
        fallback: KnowledgeSource,
        snapshot_path: Path | None = None,
    ) -> None:
        self.repo = repo
        self.fallback = fallback
        self.snapshot_path = Path(snapshot_path) if snapshot_path else None
        self._kb: KnowledgeBase | None = None
        self._synced_version: str | None = None  # kb_sync.version, по которой загружен _kb
        self.from_db = False
        self.last_error: str | None = None
        self.last_refresh_at: float | None = None

    # --- интерфейс KnowledgeSource (только память) ---
    def fingerprint(self) -> str:
        if self._kb is None:
            return "fallback:" + self.fallback.fingerprint()
        return ("db:" if self.from_db else "snapshot:") + self._kb.version

    def load(self) -> KnowledgeBase:
        return self._kb if self._kb is not None else self.fallback.load()

    # --- обновление из БД (вызывать вне event loop) ---
    def refresh(self) -> bool:
        """Перечитывает базу, если в kb_sync новая версия. True — база в памяти изменилась.
        Исключения БД пробрасываются (вызывающий логирует и остаётся на прошлой версии)."""
        version = self.repo.latest_version()
        self.last_refresh_at = time.time()
        if self.from_db and version is not None and version == self._synced_version:
            return False
        if self.from_db and version is None:
            return False  # синков не было, база уже загружена
        kb = self.repo.load_active()
        if not kb.facts and not kb.places and version is None:
            return False  # пустая БД: остаёмся на снимке/YAML до первого синка
        changed = self._kb is None or self._kb.version != kb.version
        self._kb, self._synced_version, self.from_db = kb, version, True
        self.last_error = None
        if changed:
            self._save_snapshot(kb)
            logger.info(
                "knowledge_db_loaded",
                extra={"kb_version": kb.version, "facts": len(kb.facts), "places": len(kb.places)},
            )
        return changed

    def try_refresh(self) -> bool:
        try:
            return self.refresh()
        except Exception as exc:
            self.last_error = f"{type(exc).__name__}: {_orig(exc)[:300]}"
            logger.error("knowledge_db_refresh_failed", extra={"error": self.last_error})
            return False

    # --- снимок ---
    def _save_snapshot(self, kb: KnowledgeBase) -> None:
        if self.snapshot_path is None:
            return
        try:
            self.snapshot_path.parent.mkdir(parents=True, exist_ok=True)
            tmp = self.snapshot_path.with_suffix(".tmp")
            tmp.write_text(kb.model_dump_json(), encoding="utf-8")
            os.replace(tmp, self.snapshot_path)
        except OSError as exc:
            logger.warning("knowledge_snapshot_write_failed", extra={"error": str(exc)})

    def load_snapshot(self) -> bool:
        if self.snapshot_path is None:
            return False
        try:
            kb = KnowledgeBase.model_validate_json(self.snapshot_path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return False
        self._kb, self.from_db = kb, False
        logger.warning("knowledge_from_snapshot", extra={"kb_version": kb.version})
        return True
