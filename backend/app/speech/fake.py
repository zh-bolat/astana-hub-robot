"""Провайдер речи без сети: для локальной разработки, тестов и CI."""

from __future__ import annotations

from app.lang import Lang
from app.speech.base import SpeechError, SttResult
from app.speech.wav import peak_amplitude

FAKE_TEXT: dict[str, str] = {
    "ru": "Где можно поесть?",
    "kk": "Кафе қайда?",
    "en": "Where can I eat?",
}

# Тишина ниже этого порога → пустой текст (как «пустая речь» в контракте)
SILENCE_PEAK = 500


def _silent_mp3(frames: int = 10) -> bytes:
    """Валидный MP3: N кадров MPEG-1 Layer III, 128 кбит/с, 44.1 кГц, mono, тишина (~0.26 с)."""
    header = bytes([0xFF, 0xFB, 0x90, 0xC4])  # sync, MPEG1 L3 no CRC, 128k/44.1k, mono
    frame_len = 144 * 128_000 // 44_100  # 417 байт
    frame = header + b"\x00" * (frame_len - len(header))
    return frame * frames


SILENT_MP3 = _silent_mp3()


class FakeSpeechProvider:
    name = "fake"

    def __init__(self, fail: bool = False) -> None:
        self.fail = fail
        self.tts_calls = 0

    def voice_id(self, lang: Lang) -> str:
        return f"fake-{lang}"

    async def stt(self, pcm: bytes, sample_rate: int, lang: Lang | None) -> SttResult:
        if self.fail:
            raise SpeechError("fake speech provider configured to fail")
        lang = lang or "ru"
        if peak_amplitude(pcm) < SILENCE_PEAK:
            return SttResult(text="", lang=lang, confidence=None)
        return SttResult(text=FAKE_TEXT[lang], lang=lang, confidence=1.0)

    async def tts(self, text: str, lang: Lang) -> bytes:
        if self.fail:
            raise SpeechError("fake speech provider configured to fail")
        self.tts_calls += 1
        return SILENT_MP3
