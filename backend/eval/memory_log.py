"""Лог вопросов в памяти: eval работает без Postgres (база знаний — YAML из backend/knowledge)."""

from __future__ import annotations

from types import SimpleNamespace


class MemoryQuestionLog:
    def __init__(self) -> None:
        self.rows: list[SimpleNamespace] = []

    def add(self, **fields) -> None:
        self.rows.append(SimpleNamespace(id=len(self.rows) + 1, **fields))

    def all(self, only_unanswered: bool = False) -> list[SimpleNamespace]:
        return [r for r in self.rows if not (only_unanswered and r.found)]

    def purge_older_than(self, days: int, now=None) -> int:
        return 0
