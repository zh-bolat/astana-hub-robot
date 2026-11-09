"""Мост «робот Курултая ↔ OpenAI Realtime» (docs/kurultai/realtime-voice.md).

Робот говорит с сервером по прежнему протоколу `WS /v1/live` (docs/api-contract.md): микрофон PCM16 16 кГц, команды
`hello` / `visitor_arrived` / `interrupt` / `playback_done`; сервер шлёт `ready`, `listening`, `user_speaking`,
`user_final`, `ignored`, `thinking`, `turn_start`, `phrase`, `turn_end`, `interrupted` и звук PCM16 24 кГц.
Приложение робота не меняется: меняется только то, что за сервером.

Внутри — одна сессия OpenAI Realtime на соединение робота:
- микрофон → 24 кГц → `input_audio_buffer.append`; конец фразы находит VAD OpenAI (`server_vad`), но ответ сам не
  создаётся (`create_response: false`) и речь ответ не перебивает (`interrupt_response: false`);
- расшифровка фразы (`conversation.item.input_audio_transcription.completed`) → решение сервера: робот говорит —
  только «стоп» отменяет ответ, прочее (эхо, чужая речь) удаляется; обращение «Ақылтай / робот / бот» или окно
  разговора 30 с (`app/kurultai/wake.py` через чат-сервис Курултая) → `response.create`; иначе фраза удаляется и
  робот получает `ignored`;
- звук ответа → роботу как есть (PCM16 24 кГц); расшифровка ответа → `phrase` (субтитры) и журнал;
- `web_search` → справка из интернета (`websearch.py`) → `function_call_output` → продолжение того же ответа.
Инструкции — та же база Курултая (`instructions.py`); язык — с экрана (`hello.lang`).
"""

from __future__ import annotations

import asyncio
import base64
import contextlib
import json
import re
import time
from collections.abc import Awaitable, Callable
from datetime import UTC, datetime
from typing import Any

import numpy as np
import websockets
from fastapi import WebSocket
from starlette.websockets import WebSocketState

from app.config import Settings, secret_text
from app.kurultai.quryltai import ASTANA_TZ
from app.kurultai.realtime import websearch
from app.kurultai.realtime.instructions import realtime_instructions
from app.lang import Lang, normalize_lang
from app.live.protocol import AUDIO_FORMAT, MAX_AUDIO_FRAME, MAX_TEXT_FRAME, event, new_turn_id
from app.live.stop import is_stop_command
from app.logging_setup import logger, new_request_id
from app.speech.base import has_letters

IN_RATE = 16_000  # микрофон робота
OA_RATE = 24_000  # вход и выход OpenAI Realtime, он же звук ответа роботу
SEND_CHUNK = OA_RATE * 2 // 10  # копим 100 мс перед отправкой в OpenAI
TRANSCRIBE_PROMPT = (
    "Ақылтай, Акылтай, Құрылтай, Курултай, робот, депутат, законопроект, заң жобасы, Мәжіліс."
)
_SENTENCE = re.compile(r"(.+?[.!?…])(\s+|$)", re.S)

Connector = Callable[..., Awaitable[Any]]


def resample_16_to_24(pcm: bytes) -> bytes:
    """PCM16 mono 16 кГц → 24 кГц (линейная интерполяция; для распознавания и VAD этого достаточно)."""
    x = np.frombuffer(pcm[: len(pcm) // 2 * 2], dtype="<i2").astype(np.float32)
    if x.size == 0:
        return b""
    n = x.size * 3 // 2
    y = np.interp(
        np.arange(n, dtype=np.float32) * (2.0 / 3.0), np.arange(x.size, dtype=np.float32), x
    )
    return np.clip(np.round(y), -32768, 32767).astype("<i2").tobytes()


class RealtimeBridge:
    def __init__(
        self,
        ws: WebSocket,
        *,
        settings: Settings,
        session_id: str,
        lang: Lang,
        chat: Any,
        metrics: Any = None,
        connector: Connector | None = None,
        search: Callable[[str], Awaitable[str]] | None = None,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self.ws = ws
        self.settings = settings
        self.session_id = session_id
        self.lang: Lang = lang if lang in ("ru", "kk") else "ru"
        self.chat = chat
        self.metrics = metrics
        self._connector = connector or websockets.connect
        self._search = search or (lambda q: websearch.web_search(settings, q))
        self._clock = clock
        self.oa: Any = None
        self._send_lock = asyncio.Lock()
        self._audio_buf = bytearray()
        self._instructions = ""
        self._closed = False
        self._tasks: set[asyncio.Task] = set()
        # разговор
        self._engaged_until = 0.0
        self._speech_end_at: float | None = None
        # ход робота: открыт от response.create до turn_end; робот играет звук до playback_done
        self._turn_id: str | None = None
        self._turn_open = False
        self._response_active = False
        self._dropping = False  # ответ отменён: остаток звука не шлём
        self._playing_turn: str | None = None
        self._playing_until = (
            0.0  # страховка: робот не прислал playback_done — считаем доигранным к этому времени
        )
        self._first_audio_at: float | None = None
        self._audio_bytes = 0
        self._question = ""
        self._answer = ""
        self._phrase_buf = ""
        self._first_audio_ms: int | None = None
        self._stt_ms: int | None = None
        self._request_id = ""
        self.turns = 0

    # ---------- подключение ----------
    async def connect(self) -> bool:
        """Сессия OpenAI Realtime; нет ключа или не подключились — False (сессия пойдёт старым pipeline)."""
        key = secret_text(self.settings.kurultai_openai_api_key)
        if not key:
            return False
        url = f"{self.settings.kurultai_realtime_url}?model={self.settings.kurultai_realtime_model}"
        try:
            self.oa = await asyncio.wait_for(
                self._connector(
                    url, additional_headers={"Authorization": f"Bearer {key}"}, max_size=None
                ),
                self.settings.kurultai_realtime_connect_timeout_s,
            )
        except Exception as exc:
            logger.warning("realtime_connect_failed", extra={"error": type(exc).__name__})
            return False
        return True

    async def run(self) -> None:
        """Сессия робота целиком: настройка Realtime, ready, два потока (робот → OpenAI, OpenAI → робот)."""
        try:
            await self._oa_send(self._session_config())
            await self._robot_json(event("ready", audio_format=AUDIO_FORMAT))
            await self._robot_json(event("listening"))
            tasks = [
                asyncio.create_task(self._robot_loop(), name="realtime_robot"),
                asyncio.create_task(self._openai_loop(), name="realtime_openai"),
            ]
            done, pending = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
            for t in pending:
                t.cancel()
            for t in done:
                if not t.cancelled() and t.exception() is not None:
                    logger.warning(
                        "realtime_loop_failed", extra={"error": type(t.exception()).__name__}
                    )
        finally:
            await self.close()

    async def close(self) -> None:
        if self._closed:
            return
        self._closed = True
        for t in list(self._tasks):
            t.cancel()
        if self.oa is not None:
            with contextlib.suppress(Exception):
                await self.oa.close()
        if self.ws.client_state == WebSocketState.CONNECTED:
            with contextlib.suppress(Exception):
                await self.ws.close(1000)

    # ---------- настройки сессии ----------
    def _now_text(self) -> datetime:
        return datetime.now(UTC).astimezone(ASTANA_TZ).replace(second=0, microsecond=0)

    def _build_instructions(self) -> str:
        return realtime_instructions(
            self.chat.snapshot(), self.chat.materials_block(), self.lang, self._now_text()
        )

    def _session_config(self) -> dict:
        s = self.settings
        self._instructions = self._build_instructions()
        return {
            "type": "session.update",
            "session": {
                "type": "realtime",
                "instructions": self._instructions,
                "output_modalities": ["audio"],
                "audio": {
                    "input": {
                        "format": {"type": "audio/pcm", "rate": OA_RATE},
                        "noise_reduction": {"type": "far_field"},
                        "transcription": {
                            "model": s.kurultai_realtime_transcribe_model,
                            "prompt": TRANSCRIBE_PROMPT,
                        },
                        "turn_detection": {
                            "type": "server_vad",
                            "threshold": s.kurultai_realtime_vad_threshold,
                            "prefix_padding_ms": 300,
                            "silence_duration_ms": s.kurultai_realtime_vad_silence_ms,
                            "create_response": False,
                            "interrupt_response": False,
                        },
                    },
                    "output": {
                        "format": {"type": "audio/pcm", "rate": OA_RATE},
                        "voice": s.kurultai_realtime_voice,
                    },
                },
                "tools": [websearch.TOOL] if s.kurultai_web_search else [],
                "tool_choice": "auto",
            },
        }

    async def _refresh_instructions(self) -> None:
        """Материалы, снимок, язык или минута поменялись — новые инструкции до ответа (префикс прежний — кэш)."""
        text = self._build_instructions()
        if text != self._instructions:
            self._instructions = text
            await self._oa_send(
                {"type": "session.update", "session": {"type": "realtime", "instructions": text}}
            )

    # ---------- отправка ----------
    async def _oa_send(self, msg: dict) -> None:
        if self.oa is not None and not self._closed:
            await self.oa.send(json.dumps(msg, ensure_ascii=False))

    async def _robot_json(self, msg: dict) -> None:
        async with self._send_lock:
            if self.ws.client_state == WebSocketState.CONNECTED:
                await self.ws.send_text(json.dumps(msg, ensure_ascii=False, separators=(",", ":")))

    async def _robot_bytes(self, data: bytes) -> None:
        async with self._send_lock:
            if self.ws.client_state == WebSocketState.CONNECTED:
                await self.ws.send_bytes(data)

    def _spawn(self, coro: Awaitable[None], name: str) -> None:
        task = asyncio.ensure_future(coro)
        task.set_name(name)
        self._tasks.add(task)
        task.add_done_callback(self._tasks.discard)

    # ---------- робот → OpenAI ----------
    async def _robot_loop(self) -> None:
        while True:
            msg = await self.ws.receive()
            if msg.get("type") == "websocket.disconnect":
                return
            data = msg.get("bytes")
            if data is not None:
                if data and len(data) % 2 == 0 and len(data) <= MAX_AUDIO_FRAME:
                    await self._audio_in(data)
                continue
            text = msg.get("text")
            if text is not None and len(text) <= MAX_TEXT_FRAME:
                try:
                    cmd = json.loads(text)
                except ValueError:
                    continue
                if isinstance(cmd, dict):
                    await self._on_robot(cmd)

    async def _audio_in(self, pcm16k: bytes) -> None:
        self._audio_buf += resample_16_to_24(pcm16k)
        if len(self._audio_buf) >= SEND_CHUNK:
            chunk, self._audio_buf = bytes(self._audio_buf), bytearray()
            await self._oa_send(
                {"type": "input_audio_buffer.append", "audio": base64.b64encode(chunk).decode()}
            )

    async def _on_robot(self, cmd: dict) -> None:
        kind = cmd.get("type")
        if kind == "hello":
            lang = normalize_lang(cmd.get("lang"))
            if lang in ("ru", "kk") and lang != self.lang:
                self.lang = lang
                await self._refresh_instructions()
        elif kind == "visitor_arrived":
            # тап по лицу: у Курултая по умолчанию молча (KURULTAI_LIVE_GREETING); включено — открыть разговор
            if getattr(self.chat, "live_greeting", False):
                self._engage()
        elif kind == "interrupt":
            await self._cancel("client")
        elif kind == "playback_done":
            if cmd.get("turn_id") and cmd.get("turn_id") == self._playing_turn:
                self._playing_turn = None
                self._engage()  # окно разговора — от конца ответа робота
                await self._robot_json(event("listening"))

    # ---------- OpenAI → робот ----------
    async def _openai_loop(self) -> None:
        async for raw in self.oa:
            try:
                msg = json.loads(raw)
            except (TypeError, ValueError):
                continue
            await self._on_openai(msg)

    async def _on_openai(self, msg: dict) -> None:
        kind = msg.get("type", "")
        if kind == "input_audio_buffer.speech_started":
            if not self._bot_busy():
                await self._robot_json(event("user_speaking"))
        elif kind == "input_audio_buffer.speech_stopped":
            self._speech_end_at = self._clock()
        elif kind == "conversation.item.input_audio_transcription.completed":
            await self._on_utterance(
                str(msg.get("item_id") or ""), str(msg.get("transcript") or "")
            )
        elif kind == "conversation.item.input_audio_transcription.failed":
            await self._delete(str(msg.get("item_id") or ""))
        elif kind == "response.created":
            self._response_active = True
        elif kind in ("response.output_audio.delta", "response.audio.delta"):
            await self._on_audio(msg.get("delta") or "")
        elif kind in ("response.output_audio_transcript.delta", "response.audio_transcript.delta"):
            await self._on_transcript(msg.get("delta") or "", final=False)
        elif kind in ("response.output_audio_transcript.done", "response.audio_transcript.done"):
            await self._on_transcript("", final=True)
        elif kind == "response.done":
            await self._on_response_done(msg.get("response") or {})
        elif kind == "error":
            err = msg.get("error") or {}
            code = str(err.get("code") or "")
            if code not in ("response_cancel_not_active", "item_delete_invalid_item_id"):
                logger.warning(
                    "realtime_error", extra={"code": code, "message": str(err.get("message"))[:200]}
                )

    # ---------- решения сервера ----------
    def _bot_busy(self) -> bool:
        if self._playing_turn is not None and self._clock() > self._playing_until:
            self._playing_turn = None  # playback_done не пришёл — звук давно доигран
        return self._turn_open or self._playing_turn is not None

    def _engage(self) -> None:
        window = getattr(self.chat, "wake_window_s", None)
        if window is not None:
            self._engaged_until = self._clock() + window

    def _addressed(self, text: str) -> bool:
        window = getattr(self.chat, "wake_window_s", None)
        if window is None:
            return True
        if self._clock() < self._engaged_until or self.chat.addressed(text):
            self._engage()
            return True
        return False

    async def _delete(self, item_id: str) -> None:
        if item_id:
            await self._oa_send({"type": "conversation.item.delete", "item_id": item_id})

    async def _on_utterance(self, item_id: str, text: str) -> None:
        text = text.strip()
        if not has_letters(text):
            await self._delete(item_id)
            return
        if self._bot_busy():
            # робот говорит: эхо его голоса и чужая речь в разговор не идут; «стоп» — отменить ответ
            await self._delete(item_id)
            if is_stop_command(text):
                await self._cancel("stop")
            return
        stt_ms = int((self._clock() - self._speech_end_at) * 1000) if self._speech_end_at else None
        await self._robot_json(event("user_final", text=text, lang=self.lang))
        if is_stop_command(text) or not self._addressed(text):
            await self._delete(item_id)
            logger.info("live_not_addressed", extra={"chars": len(text), "engine": "realtime"})
            await self._robot_json(event("ignored", reason="not_addressed"))
            await self._robot_json(event("listening"))
            return
        self._start_turn(text, stt_ms)
        await self._robot_json(event("thinking"))
        await self._refresh_instructions()
        await self._oa_send({"type": "response.create"})

    def _start_turn(self, question: str, stt_ms: int | None) -> None:
        self._turn_id = new_turn_id()
        self._request_id = new_request_id()
        self._turn_open = True
        self._dropping = False
        self._question, self._answer, self._phrase_buf = question, "", ""
        self._first_audio_ms = None
        self._first_audio_at = None
        self._audio_bytes = 0
        self._stt_ms = stt_ms
        self.turns += 1

    async def _on_audio(self, b64: str) -> None:
        if self._dropping or not self._turn_open or not b64:
            return
        pcm = base64.b64decode(b64)
        self._audio_bytes += len(pcm)
        if self._first_audio_ms is None:
            self._first_audio_at = self._clock()
            if self._speech_end_at is not None:
                self._first_audio_ms = max(0, int((self._clock() - self._speech_end_at) * 1000))
            await self._robot_json(event("turn_start", turn_id=self._turn_id))
        await self._robot_bytes(pcm)

    async def _on_transcript(self, delta: str, final: bool) -> None:
        if self._dropping or not self._turn_open:
            return
        self._phrase_buf += delta
        while True:
            m = _SENTENCE.match(self._phrase_buf)
            if not m:
                break
            phrase = m.group(1).strip()
            self._phrase_buf = self._phrase_buf[m.end() :]
            if phrase:
                await self._phrase(phrase)
        if final and self._phrase_buf.strip():
            await self._phrase(self._phrase_buf.strip())
            self._phrase_buf = ""

    async def _phrase(self, phrase: str) -> None:
        self._answer = f"{self._answer} {phrase}".strip()
        await self._robot_json(event("phrase", text=phrase))

    async def _on_response_done(self, response: dict) -> None:
        self._response_active = False
        if self._dropping or not self._turn_open:
            self._dropping = False
            return
        calls = [o for o in response.get("output") or [] if o.get("type") == "function_call"]
        if calls and response.get("status") == "completed":
            self._spawn(self._run_tools(calls), "realtime_tools")
            return
        await self._end_turn(ok=response.get("status") in (None, "completed"))

    async def _run_tools(self, calls: list[dict]) -> None:
        for call in calls:
            name, call_id = call.get("name"), call.get("call_id")
            try:
                args = json.loads(call.get("arguments") or "{}")
            except ValueError:
                args = {}
            if name == "web_search":
                started = self._clock()
                result = await self._search(str(args.get("query") or ""))
                logger.info(
                    "realtime_web_search", extra={"ms": int((self._clock() - started) * 1000)}
                )
            else:
                result = "Неизвестный инструмент."
            if not self._turn_open:  # пока искали, ответ отменили («стоп», сон)
                return
            await self._oa_send(
                {
                    "type": "conversation.item.create",
                    "item": {
                        "type": "function_call_output",
                        "call_id": call_id,
                        "output": json.dumps({"result": result}, ensure_ascii=False),
                    },
                }
            )
        if self._turn_open:
            await self._oa_send({"type": "response.create"})

    async def _end_turn(self, ok: bool) -> None:
        turn_id = self._turn_id
        await self._on_transcript("", final=True)
        self._turn_open = False
        if self._first_audio_ms is None:  # звука не было (ошибка) — робот не ждёт playback_done
            await self._robot_json(event("listening"))
        else:
            self._playing_turn = turn_id
            started = self._first_audio_at if self._first_audio_at is not None else self._clock()
            self._playing_until = started + self._audio_bytes / (OA_RATE * 2) + 3.0
            await self._robot_json(event("turn_end", turn_id=turn_id, lang=self.lang, found=True))
        logger.info(
            "live_answered",
            extra={
                "engine": "realtime",
                "question": self._question[:500],
                "answer": self._answer[:1000],
                "lang": self.lang,
                "first_audio_ms": self._first_audio_ms,
                "profile": "kurultai",
            },
        )
        self._record(ok=ok and self._first_audio_ms is not None)

    async def _cancel(self, reason: str) -> None:
        """«Стоп» / кнопка / сон: ответ отменить, остаток звука не слать, робот сбрасывает воспроизведение."""
        if not self._turn_open and self._playing_turn is None:
            return
        turn_id = self._turn_id
        if self._response_active:
            self._dropping = True
            await self._oa_send({"type": "response.cancel"})
        was_open = self._turn_open
        self._turn_open = False
        self._playing_turn = None
        await self._robot_json(event("interrupted", turn_id=turn_id))
        await self._robot_json(event("listening"))
        logger.info("realtime_interrupted", extra={"reason": reason})
        if was_open:
            self._record(ok=True, interrupted=True)

    def _record(self, ok: bool, interrupted: bool = False) -> None:
        if self.metrics is None:
            return
        with contextlib.suppress(Exception):
            self.metrics.record(
                "live",
                {"live_stt_final_ms": self._stt_ms, "live_first_audio_ms": self._first_audio_ms},
                lang=self.lang,
                provider="openai_realtime",
                request_id=self._request_id,
                session_id=self.session_id,
                ok=ok,
                extra={"engine": "realtime", "profile": "kurultai", "interrupted": interrupted},
            )
