"""Перенос лога вопросов из SQLite (до перехода на Postgres: /data/questions.db) в Postgres.

Идемпотентно: строки, чей request_id уже есть в Postgres, пропускаются.
"""

from __future__ import annotations

import json
import sqlite3
from datetime import UTC, datetime
from pathlib import Path

from sqlalchemy import select
from sqlalchemy.engine import Engine

from app.db.models import QuestionLog

COLUMNS = [
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


def _ts(value: object) -> datetime:
    if isinstance(value, datetime):
        dt = value
    elif value:
        dt = datetime.fromisoformat(str(value))
    else:
        dt = datetime.now(UTC)
    return dt if dt.tzinfo else dt.replace(tzinfo=UTC)


def _sources(value: object) -> list:
    if not value:
        return []
    try:
        data = json.loads(str(value))
    except ValueError:
        return []
    return data if isinstance(data, list) else []


def import_question_log(path: str | Path, engine: Engine, batch: int = 500) -> tuple[int, int]:
    """Возвращает (перенесено, пропущено)."""
    src = sqlite3.connect(f"file:{Path(path)}?mode=ro", uri=True)
    src.row_factory = sqlite3.Row
    try:
        present = {c[1] for c in src.execute("PRAGMA table_info(question_log)")}
        if not present:
            raise ValueError(f"{path}: no question_log table")
        cols = [c for c in COLUMNS if c in present]
        rows = src.execute(f"SELECT {', '.join(cols)} FROM question_log ORDER BY id").fetchall()
    finally:
        src.close()

    with engine.begin() as conn:
        existing = set(conn.execute(select(QuestionLog.request_id)).scalars())
        values = []
        skipped = 0
        for r in rows:
            d = dict(zip(cols, r, strict=True))
            if d["request_id"] in existing:
                skipped += 1
                continue
            existing.add(d["request_id"])
            d["created_at"] = _ts(d.get("created_at"))
            d["found"] = bool(d.get("found"))
            d["sources"] = _sources(d.get("sources"))
            d.setdefault("category", None)
            values.append(d)
        for i in range(0, len(values), batch):
            conn.execute(QuestionLog.__table__.insert(), values[i : i + batch])
    return len(values), skipped
