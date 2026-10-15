"""Интерфейс провайдера речи (STT + TTS) для голоса через сервер."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol

from app.lang import Lang


class SpeechError(Exception):
    """Провайдер речи недоступен / вернул ошибку → 503 speech_unavailable."""


@dataclass
class SttResult:
    text: str
    lang: Lang
    confidence: float | None = None


class SttProvider(Protocol):
    name: str

    async def stt(self, pcm: bytes, sample_rate: int, lang: Lang | None) -> SttResult:
        """PCM 16-bit mono → текст. lang=None — автоопределение ru/kk/en."""
        ...


class TtsProvider(Protocol):
    name: str

    def voice_id(self, lang: Lang) -> str:
        """Идентификатор голоса/модели (входит в ключ кеша TTS)."""
        ...

    async def tts(self, text: str, lang: Lang) -> bytes:
        """Текст → MP3."""
        ...


class SpeechProvider(SttProvider, TtsProvider, Protocol):
    """Провайдер, умеющий и STT, и TTS."""


def has_letters(text: str) -> bool:
    """Отсекает «транскрипты» тишины вроде "00:00", "...", "[music]"."""
    stripped = text.strip()
    if stripped.startswith(("[", "(")) and stripped.endswith(("]", ")")):
        return False
    return any(ch.isalpha() for ch in stripped)
