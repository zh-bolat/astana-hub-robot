"""Звук шаблонных фраз live (быстрый путь мест: «Пойдёмте, провожу до кафе!») — из кеша TTS.

Фраза из шаблона повторяется дословно, поэтому её звук (PCM 24 кГц, тот же голос, модель и voice_settings,
что у live) хранится в общем кеше TTS (app/speech/cache.py: память + диск TTS_CACHE_DIR). Попадание — звук
уходит роботу сразу, без синтеза; промах — фраза озвучивается как обычно, а в фоне синтезируется ещё раз
целиком по REST и кладётся в кеш (один раз на текст и голос), чтобы в следующий раз ответ был мгновенным.
"""

from __future__ import annotations

import asyncio
from collections.abc import Awaitable, Callable

from app.lang import Lang
from app.logging_setup import logger
from app.speech.cache import TtsCache, cache_key

# Короче — не звук (ошибка провайдера); длиннее — не шаблонная фраза
_MIN_BYTES = 2_400  # 50 мс PCM 24 кГц
_MAX_BYTES = 24_000 * 2 * 20


class PhraseAudioCache:
    def __init__(
        self,
        cache: TtsCache,
        synth: Callable[[str, Lang], Awaitable[bytes]],
        signature: Callable[[Lang], str],
    ) -> None:
        self.cache = cache
        self.synth = synth
        self.signature = signature
        self._tasks: dict[str, asyncio.Task] = {}
        self.hits = 0
        self.misses = 0

    def key(self, text: str, lang: Lang) -> str:
        return cache_key("live-pcm", self.signature(lang), lang, text)

    def get(self, text: str, lang: Lang) -> bytes | None:
        audio = self.cache.get(self.key(text, lang))
        if audio is None:
            self.misses += 1
        else:
            self.hits += 1
        return audio

    def fill_later(self, text: str, lang: Lang) -> None:
        """Промах: синтезировать фразу в фоне и положить в кеш (одна задача на фразу)."""
        key = self.key(text, lang)
        if key in self._tasks:
            return
        task = asyncio.get_running_loop().create_task(self._fill(key, text, lang))
        self._tasks[key] = task
        task.add_done_callback(lambda _t, k=key: self._tasks.pop(k, None))

    async def _fill(self, key: str, text: str, lang: Lang) -> None:
        try:
            audio = await self.synth(text, lang)
        except Exception as exc:
            logger.warning(
                "live_phrase_cache_fill_failed", extra={"lang": lang, "error": str(exc)[:200]}
            )
            return
        if _MIN_BYTES <= len(audio) <= _MAX_BYTES and len(audio) % 2 == 0:
            await asyncio.to_thread(self.cache.put, key, audio)

    async def wait_idle(self) -> None:
        """Тесты: дождаться фонового заполнения."""
        while self._tasks:
            await asyncio.gather(*list(self._tasks.values()), return_exceptions=True)

    async def aclose(self) -> None:
        for task in list(self._tasks.values()):
            task.cancel()
        await asyncio.gather(*self._tasks.values(), return_exceptions=True)
