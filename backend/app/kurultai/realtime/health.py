"""Предохранитель Realtime: OpenAI недоступен (кончились деньги, квота, ключ, обрывы подряд) → новые сессии
робота идут старой цепочкой (OpenRouter + Dmitry) без попыток Realtime, пока OpenAI не оживёт.

Один объект на процесс (`app.state.realtime_health`); мост сообщает сюда отказы (`mark_down`) и обрывы
(`note_loss`), а `app/live/session.py` перед созданием моста спрашивает `is_down()`.

Проверка «ожил ли OpenAI» идёт ФОНОМ (`prober`), а не на подключении робота: 12.11.2026 деньги на OpenAI кончились,
и каждые несколько минут очередное подключение робота тратилось на попытку Realtime (2–3 с ожидания и обрыв).
Теперь, пока OpenAI лежит, робот всегда сразу получает старую цепочку; фоновая проверка раз в паузу (1→2→4→5 мин)
подключается к OpenAI и, как только он отвечает, возвращает Realtime — следующая сессия робота уже на нём.
"""

from __future__ import annotations

import asyncio
import contextlib
import time
from collections import deque
from collections.abc import Awaitable, Callable

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

Prober = Callable[[], Awaitable[str | None]]  # None — OpenAI отвечает; строка — код ошибки


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
        self.prober: Prober | None = (
            None  # задаёт create_app; нет — проверяет первое же подключение робота
        )
        self._probe_task: asyncio.Task | None = None

    def _probing(self) -> bool:
        return self._probe_task is not None and not self._probe_task.done()

    def is_down(self) -> bool:
        """Realtime выключен: идёт пауза или фоновая проверка ещё не подтвердила, что OpenAI ожил."""
        return self._probing() or self.clock() < self.down_until

    def seconds_left(self) -> int:
        return max(0, int(self.down_until - self.clock()))

    def _penalize(self, reason: str) -> None:
        self.reason = reason
        self.down_until = self.clock() + self._penalty
        logger.warning(
            "realtime_down",
            extra={"reason": reason, "retry_in_s": int(self._penalty), "pipeline": True},
        )
        self._penalty = min(self._penalty * 2, self.max_retry_s)
        self._losses.clear()

    def mark_down(self, reason: str) -> None:
        """Отказ OpenAI: пауза на Realtime (растёт при повторах) и фоновая проверка до восстановления."""
        self._penalize(reason)
        self._ensure_probe()

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

    # ---------- фоновая проверка ----------
    def _ensure_probe(self) -> None:
        if self.prober is None or self._probing():
            return
        try:
            loop = asyncio.get_running_loop()
        except RuntimeError:  # вне цикла событий (юнит-тесты) — без фоновой проверки
            return
        self._probe_task = loop.create_task(self._probe_loop(), name="realtime_health_probe")

    async def _probe_loop(self) -> None:
        assert self.prober is not None
        while True:
            await asyncio.sleep(max(0.05, self.down_until - self.clock()))
            try:
                error = await self.prober()
            except Exception as exc:
                error = type(exc).__name__
            if error is None:
                self.mark_up()
                return
            self._penalize(error)

    async def stop(self) -> None:
        task, self._probe_task = self._probe_task, None
        if task is not None and not task.done():
            task.cancel()
            with contextlib.suppress(asyncio.CancelledError, Exception):
                await task
