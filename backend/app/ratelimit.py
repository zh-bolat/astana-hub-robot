"""In-memory rate limit (скользящее окно) на токен. Для одного процесса API достаточно."""

from __future__ import annotations

import math
import threading
import time
from collections import defaultdict, deque


class RateLimiter:
    def __init__(self, limit: int, window_s: float = 60.0) -> None:
        self.limit = limit
        self.window_s = window_s
        self._hits: dict[str, deque[float]] = defaultdict(deque)
        self._lock = threading.Lock()

    def check(self, key: str) -> int | None:
        """None — можно; иначе — через сколько секунд повторить (Retry-After)."""
        if self.limit <= 0:
            return None
        now = time.monotonic()
        with self._lock:
            hits = self._hits[key]
            while hits and now - hits[0] >= self.window_s:
                hits.popleft()
            if len(hits) >= self.limit:
                return max(1, math.ceil(self.window_s - (now - hits[0])))
            hits.append(now)
            return None
