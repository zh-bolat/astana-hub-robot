"""Где сейчас воспроизведение на роботе и эхо-фильтр микрофона.

Транспорт Pipecat отдаёт звук быстрее реального времени (в 2 раза), робот копит его в буфере и играет
подряд. Поэтому «что посетитель уже услышал» считается по виртуальным часам воспроизведения: кусок звука,
пришедший в момент t, начинает играть в max(t, конец предыдущего) и длится len/(2·24000) с.

- PlayoutTracker стоит в пайплайне сразу после transport.output(): туда кадры приходят в момент отправки
  клиенту, в том же порядке, что и в сокете (события phrase / turn_end и звук между ними).
- EchoGate стоит перед VAD: если на роботе нет эхоподавления (hello.aec=false), пока звучит ответ (+ хвост
  реверберации), тихие кадры микрофона (RMS ниже порога) заменяются тишиной — это собственный голос робота
  из динамика. Громкая речь рядом с роботом проходит и может перебить.
"""

from __future__ import annotations

import time
from collections.abc import Callable
from dataclasses import dataclass, field

import numpy as np
from pipecat.frames.frames import (
    Frame,
    InputAudioRawFrame,
    OutputAudioRawFrame,
    OutputTransportMessageFrame,
)
from pipecat.processors.frame_processor import FrameDirection, FrameProcessor


def rms(pcm: bytes) -> float:
    if len(pcm) < 2:
        return 0.0
    samples = np.frombuffer(pcm[: len(pcm) // 2 * 2], dtype=np.int16).astype(np.float32)
    return float(np.sqrt(np.mean(samples * samples)))


@dataclass
class _Segment:
    start: float
    end: float
    phrase: int  # индекс фразы хода (-1 — звук до первой фразы)


@dataclass
class Playout:
    """Оценка воспроизведения на роботе (time.monotonic())."""

    sample_rate: int
    clock_end: float = 0.0
    turn_id: str | None = None
    phrase: int = -1
    segments: list[_Segment] = field(default_factory=list)
    first_audio_at: float | None = None
    on_first_audio: Callable[[str, float], None] | None = None
    on_turn_end: Callable[[str, float], None] | None = None

    def start_turn(self, turn_id: str) -> None:
        self.turn_id, self.phrase, self.segments, self.first_audio_at = turn_id, -1, [], None

    def next_phrase(self) -> None:
        self.phrase += 1

    def audio(self, nbytes: int, now: float | None = None) -> None:
        now = time.monotonic() if now is None else now
        duration = nbytes / 2 / self.sample_rate
        start = max(self.clock_end, now)
        self.clock_end = start + duration
        self.segments.append(_Segment(start, self.clock_end, self.phrase))
        if self.first_audio_at is None:
            self.first_audio_at = now
            if self.on_first_audio and self.turn_id:
                self.on_first_audio(self.turn_id, now)

    def end_turn(self, turn_id: str) -> None:
        if self.on_turn_end:
            self.on_turn_end(turn_id, self.clock_end)

    def playing(self, now: float | None = None, tail: float = 0.0) -> bool:
        now = time.monotonic() if now is None else now
        return now < self.clock_end + tail

    def heard(self, now: float | None = None) -> dict[int, float]:
        """Доля каждой фразы хода, которая уже прозвучала: {индекс фразы: 0..1}."""
        now = time.monotonic() if now is None else now
        total: dict[int, float] = {}
        played: dict[int, float] = {}
        for seg in self.segments:
            length = seg.end - seg.start
            total[seg.phrase] = total.get(seg.phrase, 0.0) + length
            played[seg.phrase] = played.get(seg.phrase, 0.0) + max(
                0.0, min(now, seg.end) - seg.start
            )
        return {i: (played[i] / total[i] if total[i] else 0.0) for i in total if i >= 0}

    def flush(self, now: float | None = None) -> None:
        """Перебивание: робот выбросил буфер — воспроизведение кончилось сейчас."""
        now = time.monotonic() if now is None else now
        self.clock_end = min(self.clock_end, now)


class PlayoutTracker(FrameProcessor):
    """После transport.output(): события хода и звук в порядке отправки клиенту."""

    def __init__(self, playout: Playout, **kwargs) -> None:
        super().__init__(**kwargs)
        self.playout = playout

    async def process_frame(self, frame: Frame, direction: FrameDirection) -> None:
        await super().process_frame(frame, direction)
        if direction == FrameDirection.DOWNSTREAM:
            if isinstance(frame, OutputAudioRawFrame) and frame.audio:
                self.playout.audio(len(frame.audio))
            elif isinstance(frame, OutputTransportMessageFrame):
                kind = frame.message.get("type")
                if kind == "turn_start":
                    self.playout.start_turn(frame.message["turn_id"])
                elif kind == "phrase":
                    self.playout.next_phrase()
                elif kind == "turn_end":
                    self.playout.end_turn(frame.message["turn_id"])
        await self.push_frame(frame, direction)


class EchoGate(FrameProcessor):
    """Перед VAD: без AEC на роботе глушит тихий звук микрофона, пока звучит ответ."""

    def __init__(self, playout: Playout, threshold: float, tail_s: float, **kwargs) -> None:
        super().__init__(**kwargs)
        self.playout = playout
        self.threshold = threshold
        self.tail_s = tail_s
        self.aec = True  # hello.aec; True — эхо гасит робот, фильтр выключен
        self.muted_frames = 0

    def should_mute(self, pcm: bytes) -> bool:
        """Без AEC, пока звучит ответ (+ хвост), тихий кадр — эхо динамика робота."""
        return not self.aec and self.playout.playing(tail=self.tail_s) and rms(pcm) < self.threshold

    async def process_frame(self, frame: Frame, direction: FrameDirection) -> None:
        await super().process_frame(frame, direction)
        if isinstance(frame, InputAudioRawFrame) and self.should_mute(frame.audio):
            self.muted_frames += 1
            frame = InputAudioRawFrame(
                audio=b"\x00" * len(frame.audio),
                sample_rate=frame.sample_rate,
                num_channels=frame.num_channels,
            )
        await self.push_frame(frame, direction)
