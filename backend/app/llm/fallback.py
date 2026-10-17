"""Запрос к LLM с повтором, резервными моделями и хеджированием в рамках общего дедлайна.

Стратегия (все попытки укладываются в `deadline_s`):
1. Запрос к основной модели.
2. Быстрая ошибка, после которой есть смысл повторить (503, 429, обрыв соединения), → один повтор основной
   модели, но только если после него ещё остаётся время на резервную модель (`retry_min_s`).
   Иначе, как и при любой другой ошибке, — сразу следующая модель из списка.
3. Хедж: если активная попытка молчит дольше `hedge_after_s`, параллельно запускается следующая модель.
   Медленный запрос не отменяется: побеждает первый успешный ответ, остальные отменяются.
4. Дедлайн вышел → TimeoutError (в API это 504). Все модели ответили ошибкой → последняя ошибка.
5. Результаты, которые не пошли в ответ (две попытки завершились одновременно), передаются в
   `on_discard` — например, чтобы закрыть открытый стрим проигравшей хедж-попытки.
"""

from __future__ import annotations

import asyncio
import contextlib
from collections.abc import Awaitable, Callable

from app.logging_setup import logger


class RetryableError(Exception):
    """Временная ошибка провайдера (503/429/сеть): основную модель можно повторить."""


async def run_with_fallback[T](
    models: list[str],
    attempt: Callable[[str], Awaitable[T]],
    deadline_s: float,
    hedge_after_s: float = 3.0,
    retry_min_s: float = 2.5,
    on_discard: Callable[[T], Awaitable[object]] | None = None,
) -> tuple[T, str]:
    """Возвращает (результат, модель, которая ответила)."""
    if not models:
        raise ValueError("no models configured")
    loop = asyncio.get_running_loop()
    end = loop.time() + deadline_s
    queue = list(models[1:])
    primary = models[0]
    retried = False
    last_error: BaseException | None = None
    pending: dict[asyncio.Task, str] = {}
    trace: list[str] = []

    def launch(model: str) -> None:
        trace.append(model)
        pending[asyncio.ensure_future(attempt(model))] = model

    launch(primary)
    last_launch = loop.time()
    try:
        while True:
            now = loop.time()
            remaining = end - now
            if remaining <= 0:
                raise TimeoutError(f"LLM deadline {deadline_s}s exceeded (tried {trace})")
            if not pending:
                if queue:
                    launch(queue.pop(0))
                    last_launch = loop.time()
                    continue
                assert last_error is not None
                raise last_error
            wait_for = remaining
            if queue:
                wait_for = min(wait_for, max(0.0, last_launch + hedge_after_s - now))
            done, _ = await asyncio.wait(
                set(pending), timeout=wait_for, return_when=asyncio.FIRST_COMPLETED
            )
            if not done:
                if queue and loop.time() < end:
                    logger.warning(
                        "llm_hedge",
                        extra={"slow": list(pending.values()), "next_model": queue[0]},
                    )
                    launch(queue.pop(0))
                    last_launch = loop.time()
                continue
            for task in done:
                model = pending.pop(task)
                exc = task.exception()
                if exc is None:
                    if len(trace) > 1:
                        logger.info("llm_fallback_used", extra={"model": model, "tried": trace})
                    return task.result(), model
                last_error = exc
                logger.warning(
                    "llm_attempt_failed",
                    extra={"model": model, "error": f"{type(exc).__name__}: {str(exc)[:200]}"},
                )
                can_retry = (
                    model == primary
                    and not retried
                    and isinstance(exc, RetryableError)
                    and (end - loop.time()) > retry_min_s
                )
                if can_retry:
                    retried = True
                    launch(primary)
                elif queue and not pending:
                    launch(queue.pop(0))
                last_launch = loop.time()
    finally:
        for task in pending:
            if not task.done():
                task.cancel()  # попытка сама освобождает ресурсы при отмене
            elif on_discard and not task.cancelled() and task.exception() is None:
                with contextlib.suppress(Exception):
                    await on_discard(task.result())
