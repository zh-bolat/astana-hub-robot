"""Память агента: история сессии из таблицы `messages` (docs/db-schema.md).

- Чтение: сначала кеш процесса (последние сообщения недавних сессий), иначе — БД с коротким
  таймаутом. БД не ответила — ход идёт без истории (ответ роботу важнее памяти).
- Запись хода — фоном через BackgroundLogWriter (как question_log): сессия (touch), реплика
  посетителя, вызовы тулов с аргументами (role=tool), ответ ассистента. У ответа в tool_args —
  служебные поля хода: request_id, turn_id, actions, emotion, interrupted (отдельная колонка
  не понадобилась, миграции нет).
"""

from __future__ import annotations

import asyncio
from collections import OrderedDict
from dataclasses import dataclass, field

from app.db import MessageRepo
from app.log_writer import BackgroundLogWriter
from app.logging_setup import logger

CACHE_SESSIONS = 1000


@dataclass
class HistoryMessage:
    role: str  # visitor | assistant | tool
    content: str
    tool_name: str | None = None
    tool_args: dict | None = field(default=None)


class ChatMemory:
    def __init__(
        self,
        repo: MessageRepo | None,
        writer: BackgroundLogWriter,
        limit: int = 10,
        timeout_s: float = 0.7,
    ) -> None:
        self.repo = repo
        self.writer = writer
        self.limit = limit
        self.timeout_s = timeout_s
        self._cache: OrderedDict[str, list[HistoryMessage]] = OrderedDict()

    def _remember(self, session_id: str, messages: list[HistoryMessage]) -> None:
        self._cache[session_id] = messages[-self.limit :]
        self._cache.move_to_end(session_id)
        while len(self._cache) > CACHE_SESSIONS:
            self._cache.popitem(last=False)

    async def load(self, session_id: str) -> list[HistoryMessage]:
        """Последние `limit` сообщений посетителя и ассистента, старые первыми."""
        cached = self._cache.get(session_id)
        if cached is not None:
            self._cache.move_to_end(session_id)
            return list(cached)
        if self.repo is None:
            return []
        repo = self.repo
        try:
            rows = await asyncio.wait_for(
                asyncio.to_thread(repo.history, session_id, self.limit * 3), self.timeout_s
            )
        except Exception as exc:  # таймаут/БД недоступна — без истории
            logger.warning(
                "chat_history_unavailable", extra={"error": f"{type(exc).__name__}: {exc}"[:300]}
            )
            return []
        messages = [
            HistoryMessage(r.role, r.content, r.tool_name, r.tool_args)
            for r in rows
            if r.role in ("visitor", "assistant")
        ][-self.limit :]
        self._remember(session_id, messages)
        return list(messages)

    def record(self, session_id: str, lang: str | None, entries: list[HistoryMessage]) -> None:
        """Ход в память: сразу — в кеш, в БД — фоном (сессия создаётся до сообщений)."""
        convo = [e for e in entries if e.role in ("visitor", "assistant")]
        self._remember(session_id, [*self._cache.get(session_id, []), *convo])
        if self.repo is None:
            return
        repo = self.repo
        self.writer.touch_session(session_id, lang)

        def job() -> None:
            for e in entries:
                repo.add(session_id, e.role, e.content, e.tool_name, e.tool_args)

        self.writer.submit_job(job, name="messages")
