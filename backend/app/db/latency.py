"""Репозиторий таймингов этапов (таблица latency_events) и сводка для админа.

Запись — только из фонового потока `BackgroundLogWriter`; сводка — из threadpool (админ-эндпоинт).
Перцентили считает Postgres (percentile_cont), чтобы не тянуть строки в Python.
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from typing import Any

from sqlalchemy import delete, insert, text
from sqlalchemy.engine import Engine

from app.db.models import LatencyEvent

# Этапы, по строкам которых считается доля фолбэков (одна строка на ход)
FALLBACK_STAGES = {"chat": "llm_total_ms", "robot": "turn"}

_PCT = """count(ms) AS count,
    count(*) FILTER (WHERE NOT ok) AS errors,
    percentile_cont(0.5) WITHIN GROUP (ORDER BY ms) AS p50,
    percentile_cont(0.95) WITHIN GROUP (ORDER BY ms) AS p95"""

_GROUPED_SQL = f"""
SELECT kind, stage, provider, lang,
    GROUPING(provider) AS g_provider, GROUPING(lang) AS g_lang,
    {_PCT}
FROM latency_events
WHERE ts >= :since AND ms IS NOT NULL
GROUP BY GROUPING SETS ((kind, stage), (kind, stage, provider), (kind, stage, lang))
ORDER BY kind, stage, provider NULLS FIRST, lang NULLS FIRST
"""

_HOURLY_SQL = f"""
SELECT date_trunc('hour', ts) AS hour, kind, stage, {_PCT}
FROM latency_events
WHERE ts >= :since AND ms IS NOT NULL
GROUP BY 1, 2, 3
ORDER BY 1, 2, 3
"""

_FALLBACK_SQL = """
SELECT kind, count(*) AS turns,
    count(*) FILTER (WHERE extra->>'fallback' = 'true') AS fallbacks,
    count(*) FILTER (WHERE extra->>'interrupted' = 'true') AS interrupted
FROM latency_events
WHERE ts >= :since AND (
    (kind = 'chat' AND stage = :chat_stage) OR (kind = 'robot' AND stage = :robot_stage)
)
GROUP BY kind
ORDER BY kind
"""


def _ms(value: Any) -> int | None:
    return None if value is None else round(float(value))


def _stats(row: Any) -> dict:
    return {
        "count": int(row.count),
        "errors": int(row.errors),
        "p50": _ms(row.p50),
        "p95": _ms(row.p95),
    }


class LatencyRepo:
    def __init__(self, engine: Engine) -> None:
        self.engine = engine

    def add_many(self, rows: list[dict]) -> None:
        """Строки с разным набором полей — отдельными пачками (executemany требует одинаковых ключей;
        отсутствующие ts/ok/extra берут значения по умолчанию из БД)."""
        groups: dict[tuple[str, ...], list[dict]] = {}
        for row in rows:
            groups.setdefault(tuple(sorted(row)), []).append(row)
        if groups:
            with self.engine.begin() as conn:
                for batch in groups.values():
                    conn.execute(insert(LatencyEvent), batch)

    def purge_older_than(self, days: int, now: datetime | None = None) -> int:
        cutoff = (now or datetime.now(UTC)) - timedelta(days=days)
        with self.engine.begin() as conn:
            return conn.execute(delete(LatencyEvent).where(LatencyEvent.ts < cutoff)).rowcount or 0

    def summary(self, hours: int, now: datetime | None = None) -> dict:
        """p50/p95/count по этапам, разрезы по провайдеру/модели и языку, фолбэки, по часам."""
        now = now or datetime.now(UTC)
        since = now - timedelta(hours=hours)
        stages: list[dict] = []
        by_provider: list[dict] = []
        by_lang: list[dict] = []
        with self.engine.begin() as conn:
            # сводка за неделю может быть тяжелее обычного запроса — свой таймаут
            conn.execute(text("SET LOCAL statement_timeout = 15000"))
            for r in conn.execute(text(_GROUPED_SQL), {"since": since}):
                base = {"kind": r.kind, "stage": r.stage, **_stats(r)}
                if r.g_provider and r.g_lang:
                    stages.append(base)
                elif not r.g_provider:
                    by_provider.append({**base, "provider": r.provider})
                else:
                    by_lang.append({**base, "lang": r.lang})
            hourly = [
                {"hour": r.hour.isoformat(), "kind": r.kind, "stage": r.stage, **_stats(r)}
                for r in conn.execute(text(_HOURLY_SQL), {"since": since})
            ]
            fallback = [
                {
                    "kind": r.kind,
                    "turns": int(r.turns),
                    "fallbacks": int(r.fallbacks),
                    "interrupted": int(r.interrupted),
                    "fallback_share": round(r.fallbacks / r.turns, 4) if r.turns else 0.0,
                }
                for r in conn.execute(
                    text(_FALLBACK_SQL),
                    {
                        "since": since,
                        "chat_stage": FALLBACK_STAGES["chat"],
                        "robot_stage": FALLBACK_STAGES["robot"],
                    },
                )
            ]
        return {
            "hours": hours,
            "since": since.isoformat(),
            "generated_at": now.isoformat(),
            "stages": stages,
            "by_provider": by_provider,
            "by_lang": by_lang,
            "fallback": fallback,
            "hourly": hourly,
        }
