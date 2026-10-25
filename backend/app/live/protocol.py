"""Кадры WS /v1/live (docs/api-contract.md, «WS /v1/live»): сериализатор Pipecat под наш контракт.

Клиент → сервер: бинарный PCM 16-bit LE mono 16 кГц (микрофон) и JSON-команды (`hello`, `visitor_arrived`,
`interrupt`, `playback_done`, `state`). Сервер → клиент: JSON-события и бинарный PCM ответа (24 кГц).

- Бинарный кадр → `InputAudioRawFrame` (нечётная длина или больше 64 КБ — кадр отбрасывается).
- JSON с известным `type` → `InputTransportMessageFrame` (его разбирает LiveConversation); прочее — отбрасывается.
- `OutputAudioRawFrame` → байты PCM; `OutputTransportMessage[Urgent]Frame` → JSON-текст; остальное не уходит.
"""

from __future__ import annotations

import json
import uuid

from pipecat.frames.frames import (
    Frame,
    InputAudioRawFrame,
    InputTransportMessageFrame,
    OutputAudioRawFrame,
    OutputTransportMessageFrame,
    OutputTransportMessageUrgentFrame,
)
from pipecat.serializers.base_serializer import FrameSerializer

AUDIO_IN_RATE = 16_000
AUDIO_OUT_RATE = 24_000
AUDIO_FORMAT = f"pcm_s16le_{AUDIO_OUT_RATE}"
MAX_AUDIO_FRAME = 64 * 1024
MAX_TEXT_FRAME = 4 * 1024
CLIENT_TYPES = frozenset({"hello", "visitor_arrived", "interrupt", "playback_done", "state"})


def event(kind: str, **fields: object) -> dict:
    """JSON-событие сервера: {"type": kind, ...}."""
    return {"type": kind, **fields}


def error_event(code: str, message: str) -> dict:
    return event("error", error={"code": code, "message": message})


def new_turn_id() -> str:
    return f"trn_{uuid.uuid4().hex[:16]}"


class LiveSerializer(FrameSerializer):
    def __init__(self) -> None:
        super().__init__()
        self.dropped = 0  # битые кадры клиента (для лога)

    async def serialize(self, frame: Frame) -> str | bytes | None:
        if isinstance(frame, OutputAudioRawFrame):
            return bytes(frame.audio)
        if isinstance(frame, OutputTransportMessageFrame | OutputTransportMessageUrgentFrame):
            return json.dumps(frame.message, ensure_ascii=False, separators=(",", ":"))
        return None

    async def deserialize(self, data: str | bytes) -> Frame | None:
        if isinstance(data, bytes | bytearray):
            if not data or len(data) % 2 or len(data) > MAX_AUDIO_FRAME:
                self.dropped += 1
                return None
            return InputAudioRawFrame(audio=bytes(data), sample_rate=AUDIO_IN_RATE, num_channels=1)
        if len(data) > MAX_TEXT_FRAME:
            self.dropped += 1
            return None
        try:
            message = json.loads(data)
        except ValueError:
            self.dropped += 1
            return None
        if not isinstance(message, dict) or message.get("type") not in CLIENT_TYPES:
            self.dropped += 1
            return None
        return InputTransportMessageFrame(message=message)
