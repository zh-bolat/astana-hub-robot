"""Live STT: распознавание, пока посетитель говорит (WebSocket /v1/stt/stream).

Два режима за одним интерфейсом [StreamingStt]:
- `elevenlabs` — ElevenLabs Scribe v2 Realtime. Сверено с elevenlabs.io/docs (2026-10):
  wss://api.elevenlabs.io/v1/speech-to-text/realtime?model_id=scribe_v2_realtime&audio_format=pcm_16000
  &commit_strategy=vad&language_code=..., заголовок `xi-api-key`. Клиент шлёт
  {"message_type": "input_audio_chunk", "audio_base_64", "commit", "sample_rate"}, сервер отвечает
  `partial_transcript` / `committed_transcript[_with_timestamps]` {text, language_code?} и ошибками
  {message_type: error|auth_error|quota_exceeded|..., error}. Конец фразы находит VAD ElevenLabs.
- `buffered` — копим PCM и по концу фразы отдаём обычному STT-провайдеру (OpenRouter/Gemini/…).
  Промежуточного текста нет, конец фразы определяет приложение (`{"type": "end"}`).

Аудио посетителя живёт только в памяти на время сессии.
"""

from __future__ import annotations

import asyncio
import base64
import contextlib
import json
from collections.abc import AsyncIterator
from dataclasses import dataclass
from typing import Literal, Protocol
from urllib.parse import urlencode

from app.lang import Lang, detect_lang, normalize_lang
from app.speech.base import SpeechError, SttProvider, has_letters
from app.speech.wav import peak_amplitude

SAMPLE_RATE = 16_000

_ISO3 = {"rus": "ru", "kaz": "kk", "eng": "en"}
_COMMITTED = {"committed_transcript", "committed_transcript_with_timestamps"}


@dataclass
class SttStreamEvent:
    kind: Literal["partial", "final"]
    text: str
    lang: Lang | None = None


class SttStreamSession(Protocol):
    provider: str  # кто на самом деле распознаёт (для лога)
    has_partials: bool  # шлёт ли промежуточный текст (тогда «речи нет» = нет ни одного partial)

    async def send(self, pcm: bytes) -> None:
        """Кусок PCM 16-bit mono 16 кГц."""
        ...

    async def end(self) -> None:
        """Посетитель закончил фразу: дальше будет только финальный текст."""
        ...

    def events(self) -> AsyncIterator[SttStreamEvent]:
        """Промежуточные тексты, затем один `final` (после него поток кончается)."""
        ...

    async def close(self) -> None: ...


class StreamingStt(Protocol):
    name: str

    async def open(self, lang: Lang | None) -> SttStreamSession:
        """Открывает сессию; SpeechError — провайдер недоступен."""
        ...


def _final(text: str, lang: Lang | None, hint: Lang | None) -> SttStreamEvent:
    text = text.strip() if has_letters(text) else ""
    return SttStreamEvent("final", text, hint or lang or (detect_lang(text) if text else None))


# ---------- buffered: обычный STT по концу фразы ----------


class _BufferedSession:
    def __init__(
        self, provider: SttProvider, lang: Lang | None, timeout_s: float, silence_peak: int
    ) -> None:
        self.provider = f"buffered:{provider.name}"
        self.has_partials = False
        self._stt = provider
        self._silence_peak = silence_peak
        self._lang = lang
        self._timeout_s = timeout_s
        self._pcm = bytearray()
        self._ended = asyncio.Event()

    async def send(self, pcm: bytes) -> None:
        if not self._ended.is_set():
            self._pcm += pcm

    async def end(self) -> None:
        self._ended.set()

    async def events(self) -> AsyncIterator[SttStreamEvent]:
        await self._ended.wait()
        pcm, self._pcm = bytes(self._pcm), bytearray()
        if len(pcm) < SAMPLE_RATE * 2 * 0.15 or peak_amplitude(pcm) < self._silence_peak:
            yield _final("", None, self._lang)  # тишина — провайдера не зовём, как в /v1/stt
            return
        try:
            result = await asyncio.wait_for(
                self._stt.stt(pcm, SAMPLE_RATE, self._lang), self._timeout_s
            )
        except TimeoutError as exc:
            raise SpeechError("stt timeout") from exc
        yield _final(result.text, result.lang, self._lang)

    async def close(self) -> None:
        self._pcm = bytearray()


class BufferedStreamingStt:
    """Live-интерфейс поверх обычного STT: промежуточного текста нет, задержка — как у /v1/stt."""

    def __init__(self, provider: SttProvider, timeout_s: float, silence_peak: int = 500) -> None:
        self.provider = provider
        self.name = f"buffered:{provider.name}"
        self._timeout_s = timeout_s
        self._silence_peak = silence_peak

    async def open(self, lang: Lang | None) -> SttStreamSession:
        return _BufferedSession(self.provider, lang, self._timeout_s, self._silence_peak)


# ---------- ElevenLabs Scribe v2 Realtime ----------


class _ElevenLabsSession:
    provider = "elevenlabs-realtime"
    has_partials = True

    def __init__(self, ws, lang: Lang | None, finalize_s: float) -> None:
        self._ws = ws
        self._lang = lang
        self._finalize_s = finalize_s
        self._ended = asyncio.Event()
        self._last_partial = ""

    async def _chunk(self, pcm: bytes, commit: bool) -> None:
        await self._ws.send(
            json.dumps(
                {
                    "message_type": "input_audio_chunk",
                    "audio_base_64": base64.b64encode(pcm).decode(),
                    "commit": commit,
                    "sample_rate": SAMPLE_RATE,
                }
            )
        )

    async def send(self, pcm: bytes) -> None:
        if self._ended.is_set():
            return
        try:
            await self._chunk(pcm, commit=False)
        except Exception as exc:  # обрыв соединения с ElevenLabs
            raise SpeechError(f"elevenlabs realtime send: {type(exc).__name__}") from exc

    async def end(self) -> None:
        if self._ended.is_set():
            return
        self._ended.set()
        try:
            await self._chunk(b"", commit=True)
        except Exception as exc:
            raise SpeechError(f"elevenlabs realtime commit: {type(exc).__name__}") from exc

    async def _recv(self) -> dict:
        # после end() ждём committed недолго: иначе отдаём последний промежуточный текст
        timeout = self._finalize_s if self._ended.is_set() else None
        raw = await asyncio.wait_for(self._ws.recv(), timeout)
        try:
            return json.loads(raw)
        except (TypeError, ValueError):
            return {}

    async def events(self) -> AsyncIterator[SttStreamEvent]:
        while True:
            try:
                msg = await self._recv()
            except TimeoutError:
                yield _final(self._last_partial, None, self._lang)
                return
            except Exception as exc:
                raise SpeechError(f"elevenlabs realtime recv: {type(exc).__name__}") from exc
            kind = msg.get("message_type")
            text = str(msg.get("text") or "")
            if kind == "partial_transcript":
                if text.strip() and text != self._last_partial:
                    self._last_partial = text
                    yield SttStreamEvent("partial", text.strip(), self._lang)
            elif kind in _COMMITTED:
                if not has_letters(text) and not self._ended.is_set():
                    continue  # VAD закоммитил шум — ждём настоящую фразу
                code = str(msg.get("language_code") or "").lower()
                yield _final(text, normalize_lang(_ISO3.get(code, code)), self._lang)
                return
            elif "error" in msg or kind in {"error", "auth_error", "quota_exceeded"}:
                raise SpeechError(f"elevenlabs realtime {kind}: {str(msg.get('error'))[:200]}")

    async def close(self) -> None:
        with contextlib.suppress(Exception):
            await self._ws.close()


class ElevenLabsRealtimeStt:
    name = "elevenlabs-realtime"

    def __init__(
        self,
        api_key: str,
        url: str = "wss://api.elevenlabs.io/v1/speech-to-text/realtime",
        model: str = "scribe_v2_realtime",
        silence_s: float = 0.8,
        connect_timeout_s: float = 3.0,
        finalize_s: float = 2.0,
        connect=None,
    ) -> None:
        self._api_key = api_key
        self._url = url
        self._model = model
        self._silence_s = silence_s
        self._connect_timeout_s = connect_timeout_s
        self._finalize_s = finalize_s
        self._connect = connect  # тесты подменяют websockets.connect

    def url(self, lang: Lang | None) -> str:
        params: dict[str, str] = {
            "model_id": self._model,
            "audio_format": "pcm_16000",
            "commit_strategy": "vad",
            "vad_silence_threshold_secs": f"{self._silence_s:g}",
        }
        if lang:
            params["language_code"] = lang
        else:
            params["include_language_detection"] = "true"
            params["include_timestamps"] = "true"  # language_code приходит только с таймстемпами
        return f"{self._url}?{urlencode(params)}"

    async def open(self, lang: Lang | None) -> SttStreamSession:
        connect = self._connect
        if connect is None:
            from websockets.asyncio.client import connect
        try:
            ws = await asyncio.wait_for(
                connect(self.url(lang), additional_headers={"xi-api-key": self._api_key}),
                self._connect_timeout_s,
            )
        except Exception as exc:
            raise SpeechError(f"elevenlabs realtime connect: {type(exc).__name__}") from exc
        return _ElevenLabsSession(ws, lang, self._finalize_s)


class FallbackStreamingStt:
    """Realtime, а если он не открылся — буферный режим, чтобы посетитель не остался без ответа."""

    def __init__(self, primary: StreamingStt, fallback: StreamingStt) -> None:
        self.primary = primary
        self.fallback = fallback
        self.name = primary.name

    async def open(self, lang: Lang | None) -> SttStreamSession:
        try:
            return await self.primary.open(lang)
        except SpeechError:
            return await self.fallback.open(lang)
