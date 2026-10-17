"""Фоновая запись лога вопросов: ответ роботу никогда не ждёт БД.

- Записи складываются в ограниченную очередь и пишутся одним daemon-потоком (не занимает общий
  threadpool и не держит выход процесса, если БД зависла).
- Очередь переполнена (БД зависла надолго) → новые записи отбрасываются с предупреждением в логе.
- Ошибка БД → строка `question_log_failed` в логе, на ответ не влияет.
- Здесь же — служебные задачи с БД (создание схемы, очистка по сроку хранения), чтобы они тоже
  не выполнялись в event loop.
"""

from __future__ import annotations

import queue
import threading
import time
from collections.abc import Callable

from app.db import QuestionLogRepo
from app.logging_setup import logger

_STOP = object()


class BackgroundLogWriter:
    def __init__(self, repo: QuestionLogRepo, max_pending: int = 1000) -> None:
        self.repo = repo
        self.max_pending = max_pending
        self.dropped = 0
        self._queue: queue.Queue = queue.Queue()
        self._pending = 0
        self._cond = threading.Condition()
        self._thread: threading.Thread | None = None
        self._closed = False

    # --- жизненный цикл ---
    def start(self) -> None:
        if self._thread is None:
            self._thread = threading.Thread(
                target=self._run, name="question-log-writer", daemon=True
            )
            self._thread.start()

    def close(self, timeout_s: float = 3.0) -> None:
        """Дописывает очередь (не дольше timeout_s) и останавливает поток."""
        if self._closed:
            return
        self._closed = True
        if not self.wait_idle(timeout_s):
            logger.warning("question_log_flush_timeout", extra={"pending": self._pending})
        self._queue.put(_STOP)

    # --- API ---
    def submit_job(self, job: Callable[[], object], name: str = "job") -> bool:
        """Ставит задачу в очередь. False — очередь переполнена или writer закрыт."""
        with self._cond:
            if self._closed or self._pending >= self.max_pending:
                self.dropped += 1
                logger.warning(
                    "question_log_dropped",
                    extra={"job": name, "pending": self._pending, "dropped": self.dropped},
                )
                return False
            self._pending += 1
        self.start()
        self._queue.put((name, job))
        return True

    def submit(self, **fields) -> bool:
        """Запись строки лога вопросов (поля QuestionLog)."""
        return self.submit_job(lambda: self.repo.add(**fields), name="question_log")

    def wait_idle(self, timeout_s: float = 5.0) -> bool:
        """Ждёт, пока очередь опустеет (тесты, остановка). True — успели."""
        end = time.monotonic() + timeout_s
        with self._cond:
            while self._pending > 0:
                left = end - time.monotonic()
                if left <= 0:
                    return False
                self._cond.wait(left)
        return True

    @property
    def pending(self) -> int:
        return self._pending

    # --- поток ---
    def _run(self) -> None:
        while True:
            item = self._queue.get()
            if item is _STOP:
                return
            name, job = item
            try:
                job()
            except Exception as exc:  # БД недоступна/зависла — ответ роботу уже ушёл
                logger.error("question_log_failed", extra={"job": name, "error": _describe(exc)})
            finally:
                with self._cond:
                    self._pending -= 1
                    self._cond.notify_all()


def purge_job(repo: QuestionLogRepo, days: int) -> Callable[[], None]:
    def job() -> None:
        deleted = repo.purge_older_than(days)
        logger.info("question_log_purged", extra={"retention_days": days, "deleted": deleted})

    return job


def _describe(exc: Exception) -> str:
    """Ошибка без параметров SQL (в них текст вопроса): у SQLAlchemy берём исходную ошибку драйвера."""
    orig = getattr(exc, "orig", None)
    detail = str(orig) if orig is not None else str(exc)
    return f"{type(exc).__name__}: {detail[:300]}"
