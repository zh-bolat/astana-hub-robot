"""Политика предложений «Хотите, провожу вас в музей?» (docs/kurultai/navigation.md).

«Не часто и не редко»: между любыми двумя предложениями на роботе — не меньше KURULTAI_OFFER_MIN_INTERVAL_S
(общий на процесс, т. е. на всех посетителей); не больше одного предложения на live-сессию и не предлагать, если
в сессии уже была поездка — это решает мост (`RealtimeBridge`), здесь только общий интервал.
"""

from __future__ import annotations

import time
from collections.abc import Callable


class OfferPolicy:
    def __init__(self, clock: Callable[[], float] = time.monotonic) -> None:
        self._clock = clock
        self.last_at: float | None = None  # когда предлагали в последний раз (монотонное время)
        self.count = 0

    def due(self, min_interval_s: float) -> bool:
        """Интервал с прошлого предложения прошёл (или предложений ещё не было)."""
        return self.last_at is None or self._clock() - self.last_at >= min_interval_s

    def mark(self) -> None:
        """Предложение отправлено — время фиксируется при отправке, а не по ответу посетителя."""
        self.last_at = self._clock()
        self.count += 1
