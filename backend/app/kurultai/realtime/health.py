"""Предохранитель Realtime: OpenAI недоступен (кончились деньги, квота, ключ, обрывы подряд) → новые сессии
робота идут старой цепочкой (OpenRouter + Dmitry) без попыток Realtime, пока не истечёт пауза.

Пауза растёт 2→4→…→max минут при повторных отказах и сбрасывается первым успешным подключением. Один объект на
процесс (`app.state.realtime_health`); мост сообщает сюда отказы (`mark_down`) и обрывы (`note_loss`), а
`app/live/session.py` перед созданием моста спрашивает `is_down()`.
"""

from __future__ import annotations

import time
from collections import deque
from collections.abc import Callable

from app.logging_setup import logger

# Коды OpenAI, после которых пробовать дальше бессмысленно, пока не вмешается человек
FATAL_CODES = frozenset(
    {
        "credit_balance_exhausted",
        "insufficient_quota",
        "billing_hard_limit_reached",
        "billing_not_active",
        "invalid_api_key",
        "account_deactivated",
        "access_terminated",
    }
)
LOSS_WINDOW_S = 60.0
LOSS_LIMIT = 3  # обрывов связи с OpenAI за минуту → считаем, что он лежит


class RealtimeHealth:
    def __init__(
        self,
        retry_s: float = 120.0,
        max_retry_s: float = 600.0,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self.retry_s = max(1.0, retry_s)
        self.max_retry_s = max(self.retry_s, max_retry_s)
        self.clock = clock
        self.down_until = 0.0
        self.reason = ""
        self._penalty = self.retry_s
        self._losses: deque[float] = deque()

    def is_down(self) -> bool:
        return self.clock() < self.down_until

    def seconds_left(self) -> int:
        return max(0, int(self.down_until - self.clock()))

    def mark_down(self, reason: str) -> None:
        """Отказ OpenAI: пауза на Realtime (растёт при повторах)."""
        self.reason = reason
        self.down_until = self.clock() + self._penalty
        logger.warning(
            "realtime_down",
            extra={"reason": reason, "retry_in_s": int(self._penalty), "pipeline": True},
        )
        self._penalty = min(self._penalty * 2, self.max_retry_s)
        self._losses.clear()

    def mark_up(self) -> None:
        """Сессия OpenAI поднялась — штраф сбрасывается."""
        if self._penalty != self.retry_s or self.down_until:
            logger.info("realtime_up", extra={"after": self.reason})
        self._penalty = self.retry_s
        self.down_until = 0.0
        self.reason = ""

    def note_loss(self) -> bool:
        """Обрыв связи с OpenAI. True — обрывов слишком много подряд, Realtime выключен на паузу."""
        now = self.clock()
        self._losses.append(now)
        while self._losses and now - self._losses[0] > LOSS_WINDOW_S:
            self._losses.popleft()
        if len(self._losses) >= LOSS_LIMIT:
            self.mark_down("reconnect_loop")
            return True
        return False
