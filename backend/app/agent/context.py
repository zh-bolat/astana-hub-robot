"""Состояние одного хода агента и приём кадров для `look`."""

from __future__ import annotations

import asyncio
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field

from app.knowledge.models import KnowledgeBase
from app.lang import Lang


class FrameBroker:
    """Кадры по запросу агента: turn_id → future. Только в памяти процесса (один воркер uvicorn),
    кадр не сохраняется: future держит байты, пока их не заберёт тул `look`.

    Ожидание открывается только когда агент запросил кадр (action look) и закрывается сразу
    после получения кадра или таймаута — в остальное время POST …/frame получает 404."""

    def __init__(self) -> None:
        self._waiting: dict[str, asyncio.Future[bytes]] = {}

    def open(self, turn_id: str) -> asyncio.Future[bytes]:
        fut: asyncio.Future[bytes] = asyncio.get_running_loop().create_future()
        self._waiting[turn_id] = fut
        return fut

    def is_waiting(self, turn_id: str) -> bool:
        fut = self._waiting.get(turn_id)
        return fut is not None and not fut.done()

    def deliver(self, turn_id: str, image: bytes) -> bool:
        fut = self._waiting.pop(turn_id, None)
        if fut is None or fut.done():
            return False
        fut.set_result(image)
        return True

    def close(self, turn_id: str) -> None:
        fut = self._waiting.pop(turn_id, None)
        if fut is not None and not fut.done():
            fut.cancel()

    def __len__(self) -> int:
        return len(self._waiting)


@dataclass
class TurnContext:
    """Контекст хода — доступен тулам агента через RunContextWrapper.context."""

    kb: KnowledgeBase
    session_id: str
    turn_id: str
    text: str
    lang: Lang  # язык ответа (для текстов тулов)
    nearest_place_id: str | None
    navigating: bool
    prev_assistant: str | None
    max_tool_calls: int
    # Немедленное событие роботу (look, «секунду, посмотрю») — через очередь хода
    emit: Callable[[str, dict], Awaitable[None]]
    # Снять кадр и описать его (реализация — ChatService)
    look: Callable[[TurnContext, str | None], Awaitable[str]]
    tool_calls: int = 0
    tools_used: list[str] = field(default_factory=list)
    # {name, args, result} → messages (role=tool)
    tool_log: list[dict] = field(default_factory=list)
    actions: list[dict] = field(default_factory=list)  # отложенные действия: после фраз, до done
    sources: list[str] = field(default_factory=list)
    knowledge_used: bool = False
    looked: bool = False

    def tools_left(self) -> bool:
        return self.tool_calls < self.max_tool_calls

    def add_source(self, source_id: str) -> None:
        if source_id not in self.sources:
            self.sources.append(source_id)
