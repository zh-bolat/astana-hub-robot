"""Разбор и проверка WAV из /v1/stt: PCM 16-bit, mono, 16 кГц."""

from __future__ import annotations

import array
import io
import sys
import wave
from dataclasses import dataclass

EXPECTED_RATE = 16000


class WavError(ValueError):
    pass


@dataclass
class PcmAudio:
    pcm: bytes = b""
    sample_rate: int = EXPECTED_RATE
    duration_s: float = 0.0


def parse_wav(data: bytes) -> PcmAudio:
    if len(data) < 44 or data[:4] != b"RIFF" or data[8:12] != b"WAVE":
        raise WavError("audio must be a WAV file (RIFF/WAVE header)")
    try:
        with wave.open(io.BytesIO(data), "rb") as w:
            channels, width, rate = w.getnchannels(), w.getsampwidth(), w.getframerate()
            frames = w.readframes(w.getnframes())
    except (wave.Error, EOFError) as exc:
        raise WavError(f"invalid WAV: {exc}") from exc
    if channels != 1:
        raise WavError(f"audio must be mono, got {channels} channels")
    if width != 2:
        raise WavError(f"audio must be PCM 16-bit, got {width * 8}-bit")
    if rate != EXPECTED_RATE:
        raise WavError(f"audio must be {EXPECTED_RATE} Hz, got {rate} Hz")
    return PcmAudio(pcm=frames, sample_rate=rate, duration_s=len(frames) / 2 / rate)


def peak_amplitude(pcm: bytes) -> int:
    """Максимальная амплитуда (0..32768) — для детектора тишины."""
    if len(pcm) < 2:
        return 0
    samples = array.array("h")
    samples.frombytes(pcm[: len(pcm) - len(pcm) % 2])
    if sys.byteorder == "big":
        samples.byteswap()
    return max((abs(s) for s in samples), default=0)


def make_wav(
    pcm: bytes, sample_rate: int = EXPECTED_RATE, channels: int = 1, width: int = 2
) -> bytes:
    """Сборка WAV (для тестов и примеров)."""
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(channels)
        w.setsampwidth(width)
        w.setframerate(sample_rate)
        w.writeframes(pcm)
    return buf.getvalue()
