"""База данных: PostgreSQL 16 + pgvector (docs/db-schema.md).

- Схема — только миграциями Alembic (`backend/migrations/`, `app.db.migrate`).
- Лог вопросов и сессии пишутся фоном (`app.log_writer.BackgroundLogWriter`): ответ роботу БД
  не ждёт; подключение и запросы ограничены таймаутами (`engine_options`).
"""

from __future__ import annotations

from sqlalchemy import create_engine
from sqlalchemy.engine import Engine, make_url

from app.db.models import Base, QuestionLog
from app.db.repos import (
    CSV_FIELDS,
    FeedbackRepo,
    MessageRepo,
    QuestionLogRepo,
    RobotRepo,
    SessionRepo,
)

__all__ = [
    "CSV_FIELDS",
    "Base",
    "FeedbackRepo",
    "MessageRepo",
    "QuestionLog",
    "QuestionLogRepo",
    "RobotRepo",
    "SessionRepo",
    "engine_options",
    "is_postgres",
    "make_engine",
]


def is_postgres(url: str) -> bool:
    return make_url(url).get_backend_name() == "postgresql"


def engine_options(url: str, timeout_s: float = 2.0) -> dict:
    """Таймауты, чтобы зависшая/недоступная БД не держала поток записи бесконечно."""
    opts: dict = {"pool_pre_ping": True, "pool_timeout": timeout_s}
    if is_postgres(url):
        ms = int(timeout_s * 1000)
        opts["connect_args"] = {
            "connect_timeout": max(1, round(timeout_s)),
            "options": f"-c statement_timeout={ms} -c lock_timeout={ms}",
        }
    return opts


def make_engine(url: str, timeout_s: float = 2.0) -> Engine:
    """Engine без подключения к БД (подключение — при первом запросе)."""
    if not is_postgres(url):
        raise ValueError("DATABASE_URL must be postgresql+psycopg://... (SQLite is not supported)")
    return create_engine(url, **engine_options(url, timeout_s))
