"""Лог вопросов и ответов (шаг 3.7). Только текст, без изображений.

MVP — SQLite; переход на Postgres — сменой DATABASE_URL (+ драйвер psycopg).
БД не на критическом пути ответа: запись идёт фоном (`app.log_writer.BackgroundLogWriter`),
подключение и запросы ограничены таймаутами (`engine_options`), схема создаётся лениво.
"""

from __future__ import annotations

import csv
import json
import threading
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import IO

from sqlalchemy import (
    Boolean,
    DateTime,
    Integer,
    String,
    Text,
    create_engine,
    delete,
    inspect,
    select,
    text,
)
from sqlalchemy.engine import Engine, make_url
from sqlalchemy.orm import DeclarativeBase, Mapped, Session, mapped_column


class Base(DeclarativeBase):
    pass


class QuestionLog(Base):
    __tablename__ = "question_log"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=lambda: datetime.now(UTC), index=True
    )
    request_id: Mapped[str] = mapped_column(String(64), index=True)
    session_id: Mapped[str] = mapped_column(String(64), index=True)
    endpoint: Mapped[str] = mapped_column(String(16))  # ask | look
    question: Mapped[str] = mapped_column(Text)
    lang: Mapped[str] = mapped_column(String(8))
    place_id: Mapped[str | None] = mapped_column(String(64), nullable=True)
    found: Mapped[bool] = mapped_column(Boolean, index=True)
    # kb | small_talk | general | out_of_scope
    category: Mapped[str | None] = mapped_column(String(16), nullable=True, index=True)
    answer: Mapped[str] = mapped_column(Text)
    sources: Mapped[str] = mapped_column(Text, default="[]")  # JSON-массив
    provider: Mapped[str] = mapped_column(String(64))
    latency_ms: Mapped[int] = mapped_column(Integer)


CSV_FIELDS = [
    "id",
    "created_at",
    "request_id",
    "session_id",
    "endpoint",
    "question",
    "lang",
    "place_id",
    "found",
    "category",
    "answer",
    "sources",
    "provider",
    "latency_ms",
]


def is_postgres(url: str) -> bool:
    return make_url(url).get_backend_name() == "postgresql"


def engine_options(url: str, timeout_s: float = 2.0) -> dict:
    """Таймауты, чтобы зависшая/недоступная БД не держала поток записи лога бесконечно."""
    if url.startswith("sqlite"):
        # timeout — сколько ждать снятия блокировки файла (busy timeout)
        return {"connect_args": {"check_same_thread": False, "timeout": timeout_s}}
    opts: dict = {"pool_pre_ping": True, "pool_timeout": timeout_s}
    if is_postgres(url):
        ms = int(timeout_s * 1000)
        opts["connect_args"] = {
            "connect_timeout": max(1, round(timeout_s)),
            "options": f"-c statement_timeout={ms} -c lock_timeout={ms}",
        }
    return opts


def make_engine(url: str, timeout_s: float = 2.0) -> Engine:
    """Создаёт engine без подключения к БД: схема создаётся лениво (`QuestionLogRepo.ensure_schema`)."""
    if url.startswith("sqlite:///"):
        path = url.removeprefix("sqlite:///")
        if path and path != ":memory:":
            Path(path).parent.mkdir(parents=True, exist_ok=True)
    return create_engine(url, **engine_options(url, timeout_s))


# Ключ advisory-lock для миграций: два контейнера, стартующие одновременно, не мешают друг другу
_MIGRATION_LOCK_KEY = 0x48554230  # "HUB0"


def migrate(engine: Engine) -> None:
    """Создание таблиц и мини-миграции (без Alembic на MVP). Идемпотентно."""
    with engine.begin() as conn:
        if conn.dialect.name == "postgresql":
            conn.execute(text("SELECT pg_advisory_xact_lock(:k)"), {"k": _MIGRATION_LOCK_KEY})
            Base.metadata.create_all(conn)
            conn.execute(
                text("ALTER TABLE question_log ADD COLUMN IF NOT EXISTS category VARCHAR(16)")
            )
            return
        Base.metadata.create_all(conn)
        columns = {c["name"] for c in inspect(conn).get_columns("question_log")}
        if "category" not in columns:  # в SQLite нет ADD COLUMN IF NOT EXISTS
            conn.execute(text("ALTER TABLE question_log ADD COLUMN category VARCHAR(16)"))


class QuestionLogRepo:
    def __init__(self, engine: Engine) -> None:
        self.engine = engine
        self._schema_ready = False
        self._schema_lock = threading.Lock()

    def ensure_schema(self) -> None:
        if self._schema_ready:
            return
        with self._schema_lock:
            if not self._schema_ready:
                migrate(self.engine)
                self._schema_ready = True

    def add(self, **fields) -> None:
        self.ensure_schema()
        if isinstance(fields.get("sources"), list):
            fields["sources"] = json.dumps(fields["sources"], ensure_ascii=False)
        with Session(self.engine) as s:
            s.add(QuestionLog(**fields))
            s.commit()

    def purge_older_than(self, days: int, now: datetime | None = None) -> int:
        """Удаляет записи старше `days` дней. Возвращает число удалённых."""
        self.ensure_schema()
        cutoff = (now or datetime.now(UTC)) - timedelta(days=days)
        with Session(self.engine) as s:
            result = s.execute(delete(QuestionLog).where(QuestionLog.created_at < cutoff))
            s.commit()
            return result.rowcount or 0

    def all(self, only_unanswered: bool = False) -> list[QuestionLog]:
        self.ensure_schema()
        stmt = select(QuestionLog).order_by(QuestionLog.id)
        if only_unanswered:
            stmt = stmt.where(QuestionLog.found.is_(False))
        with Session(self.engine) as s:
            return list(s.scalars(stmt))

    def export_csv(self, out: IO[str], only_unanswered: bool = False) -> int:
        writer = csv.DictWriter(out, fieldnames=CSV_FIELDS)
        writer.writeheader()
        rows = self.all(only_unanswered)
        for row in rows:
            writer.writerow({f: getattr(row, f) for f in CSV_FIELDS})
        return len(rows)
