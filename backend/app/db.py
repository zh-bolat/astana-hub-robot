"""Лог вопросов и ответов (шаг 3.7). Только текст, без изображений.

MVP — SQLite; переход на Postgres — сменой DATABASE_URL (+ драйвер psycopg).
"""

from __future__ import annotations

import csv
import json
from datetime import UTC, datetime
from pathlib import Path
from typing import IO

from sqlalchemy import Boolean, DateTime, Integer, String, Text, create_engine, select
from sqlalchemy.engine import Engine
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
    "answer",
    "sources",
    "provider",
    "latency_ms",
]


def make_engine(url: str) -> Engine:
    if url.startswith("sqlite:///"):
        path = url.removeprefix("sqlite:///")
        if path and path != ":memory:":
            Path(path).parent.mkdir(parents=True, exist_ok=True)
        engine = create_engine(url, connect_args={"check_same_thread": False})
    else:
        engine = create_engine(url, pool_pre_ping=True)
    Base.metadata.create_all(engine)
    return engine


class QuestionLogRepo:
    def __init__(self, engine: Engine) -> None:
        self.engine = engine

    def add(self, **fields) -> None:
        if isinstance(fields.get("sources"), list):
            fields["sources"] = json.dumps(fields["sources"], ensure_ascii=False)
        with Session(self.engine) as s:
            s.add(QuestionLog(**fields))
            s.commit()

    def all(self, only_unanswered: bool = False) -> list[QuestionLog]:
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
