"""Репозитории разговоров и аналитики: question_log, sessions, messages, feedback, robots.

Все методы синхронные и вызываются вне event loop (фоновый поток `BackgroundLogWriter`, CLI).
"""

from __future__ import annotations

import csv
import hashlib
import json
from datetime import UTC, datetime, timedelta
from typing import IO, Any

from sqlalchemy import delete, select, update
from sqlalchemy.dialects.postgresql import insert
from sqlalchemy.engine import Engine
from sqlalchemy.orm import Session

from app.db.models import FeedbackRow, MessageRow, QuestionLog, RobotRow, SessionRow

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
    "interrupted",
]


def _cutoff(days: int, now: datetime | None) -> datetime:
    return (now or datetime.now(UTC)) - timedelta(days=days)


class QuestionLogRepo:
    def __init__(self, engine: Engine) -> None:
        self.engine = engine

    def add(self, **fields: Any) -> None:
        with Session(self.engine) as s:
            s.add(QuestionLog(**fields))
            s.commit()

    def purge_older_than(self, days: int, now: datetime | None = None) -> int:
        """Удаляет записи старше `days` дней (и сессии с их сообщениями). Возвращает число
        удалённых строк лога вопросов."""
        cutoff = _cutoff(days, now)
        with Session(self.engine) as s:
            result = s.execute(delete(QuestionLog).where(QuestionLog.created_at < cutoff))
            s.execute(delete(MessageRow).where(MessageRow.created_at < cutoff))
            s.execute(delete(SessionRow).where(SessionRow.last_seen_at < cutoff))
            s.commit()
            return result.rowcount or 0

    def all(self, only_unanswered: bool = False) -> list[QuestionLog]:
        stmt = select(QuestionLog).order_by(QuestionLog.id)
        if only_unanswered:
            stmt = stmt.where(QuestionLog.found.is_(False))
        with Session(self.engine) as s:
            return list(s.scalars(stmt))

    def session_history(self, session_id: str, limit: int) -> list[QuestionLog]:
        """Последние `limit` обменов разговора, по порядку (старые первыми)."""
        stmt = (
            select(QuestionLog)
            .where(QuestionLog.session_id == session_id)
            .order_by(QuestionLog.id.desc())
            .limit(limit)
        )
        with Session(self.engine) as s:
            return list(reversed(list(s.scalars(stmt))))

    def export_csv(self, out: IO[str], only_unanswered: bool = False) -> int:
        writer = csv.DictWriter(out, fieldnames=CSV_FIELDS)
        writer.writeheader()
        rows = self.all(only_unanswered)
        for row in rows:
            values = {f: getattr(row, f) for f in CSV_FIELDS}
            values["sources"] = json.dumps(row.sources, ensure_ascii=False)
            writer.writerow(values)
        return len(rows)


class SessionRepo:
    def __init__(self, engine: Engine) -> None:
        self.engine = engine

    def touch(
        self,
        session_id: str,
        lang: str | None = None,
        robot_id: str | None = None,
        now: datetime | None = None,
    ) -> None:
        """Создаёт сессию или обновляет last_seen_at (и язык)."""
        ts = now or datetime.now(UTC)
        stmt = insert(SessionRow).values(
            id=session_id, lang=lang, robot_id=robot_id, started_at=ts, last_seen_at=ts
        )
        stmt = stmt.on_conflict_do_update(
            index_elements=[SessionRow.id],
            set_={
                "last_seen_at": stmt.excluded.last_seen_at,
                "lang": stmt.excluded.lang,
            },
        )
        with self.engine.begin() as conn:
            conn.execute(stmt)

    def get(self, session_id: str) -> SessionRow | None:
        with Session(self.engine) as s:
            return s.get(SessionRow, session_id)

    def end(self, session_id: str, now: datetime | None = None) -> None:
        with self.engine.begin() as conn:
            conn.execute(
                update(SessionRow)
                .where(SessionRow.id == session_id)
                .values(ended_at=now or datetime.now(UTC))
            )


class MessageRepo:
    """Память агента (шаг 3): история разговора по сессии."""

    def __init__(self, engine: Engine) -> None:
        self.engine = engine

    def add(
        self,
        session_id: str,
        role: str,
        content: str,
        tool_name: str | None = None,
        tool_args: dict | None = None,
    ) -> int:
        with Session(self.engine) as s:
            row = MessageRow(
                session_id=session_id,
                role=role,
                content=content,
                tool_name=tool_name,
                tool_args=tool_args,
            )
            s.add(row)
            s.commit()
            return row.id

    def history(self, session_id: str, limit: int = 20) -> list[MessageRow]:
        """Последние `limit` сообщений сессии в хронологическом порядке."""
        stmt = (
            select(MessageRow)
            .where(MessageRow.session_id == session_id)
            .order_by(MessageRow.created_at.desc(), MessageRow.id.desc())
            .limit(limit)
        )
        with Session(self.engine) as s:
            return list(reversed(list(s.scalars(stmt))))


class FeedbackRepo:
    def __init__(self, engine: Engine) -> None:
        self.engine = engine

    def add(
        self, question_log_id: int, rating: int, created_by: str, comment: str | None = None
    ) -> int:
        if rating not in (-1, 1):
            raise ValueError("rating must be -1 or 1")
        with Session(self.engine) as s:
            row = FeedbackRow(
                question_log_id=question_log_id,
                rating=rating,
                comment=comment,
                created_by=created_by,
            )
            s.add(row)
            s.commit()
            return row.id

    def for_question(self, question_log_id: int) -> list[FeedbackRow]:
        stmt = (
            select(FeedbackRow)
            .where(FeedbackRow.question_log_id == question_log_id)
            .order_by(FeedbackRow.id)
        )
        with Session(self.engine) as s:
            return list(s.scalars(stmt))


def hash_token(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


class RobotRepo:
    """Токены по роботам (шаг 3). Токен в открытом виде не хранится — только sha256."""

    def __init__(self, engine: Engine) -> None:
        self.engine = engine

    def add(self, robot_id: str, name: str, token: str) -> None:
        with Session(self.engine) as s:
            s.add(RobotRow(id=robot_id, name=name, token_hash=hash_token(token)))
            s.commit()

    def by_token(self, token: str) -> RobotRow | None:
        stmt = select(RobotRow).where(
            RobotRow.token_hash == hash_token(token), RobotRow.is_active.is_(True)
        )
        with Session(self.engine) as s:
            return s.scalars(stmt).first()

    def set_active(self, robot_id: str, active: bool) -> None:
        with self.engine.begin() as conn:
            conn.execute(update(RobotRow).where(RobotRow.id == robot_id).values(is_active=active))

    def touch(self, robot_id: str, now: datetime | None = None) -> None:
        with self.engine.begin() as conn:
            conn.execute(
                update(RobotRow)
                .where(RobotRow.id == robot_id)
                .values(last_seen_at=now or datetime.now(UTC))
            )
