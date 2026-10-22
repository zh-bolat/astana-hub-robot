"""Голос через сервер: POST /v1/stt и POST /v1/tts (docs/api-contract.md, «Голос через сервер»).

Аудио посетителей живёт только в памяти на время запроса; в логи — только длительность и язык.
"""

from __future__ import annotations

import asyncio
import json
import time
from collections.abc import AsyncIterator

from fastapi import APIRouter, Depends, Query, Request, Response, WebSocket, WebSocketDisconnect
from pydantic import BaseModel, Field, ValidationError, field_validator
from starlette.datastructures import UploadFile
from starlette.formparsers import MultiPartException

from app.api import _InMemoryMultiPartParser, _read_body_limited, check_token
from app.errors import ApiError, bad_request, payload_too_large, rate_limited
from app.lang import Lang, detect_lang, normalize_lang
from app.logging_setup import (
    get_request_id,
    logger,
    new_request_id,
    reset_request_id,
    set_request_id,
)
from app.schemas import ErrorResponse, LangField
from app.speech.base import SpeechError, SttProvider, SttResult, TtsProvider
from app.speech.cache import TtsCache, cache_key
from app.speech.streaming import SAMPLE_RATE, StreamingStt, SttStreamSession
from app.speech.wav import WavError, parse_wav, peak_amplitude

router = APIRouter(prefix="/v1")


def speech_unavailable(message: str = "Speech provider is unavailable") -> ApiError:
    return ApiError(503, "speech_unavailable", message)


def speech_timeout() -> ApiError:
    return ApiError(504, "timeout", "Speech provider did not answer in time")


def require_robot_speech(request: Request) -> str:
    key = check_token(request)
    retry_after = request.app.state.speech_rate_limiter.check(key)
    if retry_after is not None:
        raise rate_limited(retry_after)
    return key


_ERRORS = {code: {"model": ErrorResponse} for code in (400, 401, 413, 429, 503, 504)}


class SttMeta(BaseModel):
    lang: LangField | None = None
    session_id: str = Field(min_length=1, max_length=64)


class SttResponse(BaseModel):
    request_id: str
    text: str
    lang: LangField
    confidence: float | None = None


class TtsRequest(BaseModel):
    text: str = Field(min_length=1, max_length=300)
    lang: LangField | None = None

    @field_validator("text")
    @classmethod
    def _not_blank(cls, v: str) -> str:
        v = v.strip()
        if not v:
            raise ValueError("must not be blank")
        return v


async def _with_timeout(coro, timeout_s: float, headers: dict[str, str] | None = None):
    try:
        return await asyncio.wait_for(coro, timeout=timeout_s)
    except TimeoutError as exc:
        err = speech_timeout()
        err.headers.update(headers or {})
        raise err from exc
    except SpeechError as exc:
        logger.error("speech_error", extra={"error": str(exc)})
        err = speech_unavailable()
        err.headers.update(headers or {})
        raise err from exc


_STT_OPENAPI = {
    "requestBody": {
        "required": True,
        "content": {
            "multipart/form-data": {
                "schema": {
                    "type": "object",
                    "required": ["audio", "meta"],
                    "properties": {
                        "audio": {
                            "type": "string",
                            "format": "binary",
                            "description": "WAV PCM 16-bit mono 16 кГц, ≤ 15 с, ≤ 600 КБ",
                        },
                        "meta": {
                            "type": "string",
                            "description": 'JSON: {"lang": "ru"|"kk"|"en"|null, "session_id"}',
                        },
                    },
                }
            }
        },
    }
}


@router.post(
    "/stt",
    response_model=SttResponse,
    responses=_ERRORS,
    dependencies=[Depends(require_robot_speech)],
    openapi_extra=_STT_OPENAPI,
)
async def stt(request: Request) -> SttResponse:
    settings = request.app.state.settings
    provider: SttProvider = request.app.state.stt
    if not request.headers.get("content-type", "").startswith("multipart/form-data"):
        raise bad_request("Expected multipart/form-data with parts 'audio' and 'meta'")

    body = await _read_body_limited(request, settings.max_audio_bytes + 64 * 1024)

    async def _replay() -> AsyncIterator[bytes]:
        yield body

    audio_part: object = None
    try:
        try:
            form = await _InMemoryMultiPartParser(
                request.headers, _replay(), max_files=1, max_fields=5, max_part_size=16 * 1024
            ).parse()
        except MultiPartException as exc:
            raise bad_request(f"Invalid multipart body: {exc.message}") from exc
        body = b""

        audio_part = form.get("audio")
        meta_raw = form.get("meta")
        if not isinstance(audio_part, UploadFile):
            raise bad_request("Part 'audio' (audio/wav) is required")
        if not isinstance(meta_raw, str):
            raise bad_request("Part 'meta' (JSON string) is required")
        try:
            meta = SttMeta.model_validate_json(meta_raw)
        except ValidationError as exc:
            first = exc.errors()[0]
            loc = ".".join(str(x) for x in first.get("loc", ()))
            raise bad_request(
                f"meta.{loc}: {first.get('msg')}" if loc else "Invalid meta JSON"
            ) from exc

        raw = await audio_part.read()
        if len(raw) > settings.max_audio_bytes:
            raise payload_too_large(f"Audio exceeds {settings.max_audio_bytes} bytes")
        try:
            audio = parse_wav(raw)
        except WavError as exc:
            raise bad_request(str(exc)) from exc
        finally:
            del raw
        if audio.duration_s > settings.max_audio_seconds:
            raise payload_too_large(f"Audio longer than {settings.max_audio_seconds:g} s")

        started = time.perf_counter()
        try:
            if peak_amplitude(audio.pcm) < settings.stt_silence_peak or audio.duration_s < 0.15:
                # тишина — не тратим запрос к провайдеру и не ловим «галлюцинации»
                result = SttResult(text="", lang=meta.lang or "ru")
            else:
                result = await _with_timeout(
                    provider.stt(audio.pcm, audio.sample_rate, meta.lang), settings.stt_timeout_s
                )
        finally:
            audio.pcm = b""  # аудио посетителя не храним
        logger.info(
            "stt_recognized",
            extra={
                "session_id": meta.session_id,
                "audio_s": round(audio.duration_s, 2),
                "lang_requested": meta.lang,
                "lang": result.lang,
                "empty": not result.text,
                "provider": provider.name,
                "latency_ms": int((time.perf_counter() - started) * 1000),
            },
        )
        return SttResponse(
            request_id=get_request_id() or "",
            text=result.text,
            lang=result.lang,
            confidence=result.confidence,
        )
    finally:
        # audio может прийти текстовым полем (str) — тогда закрывать нечего (иначе было 500)
        if isinstance(audio_part, UploadFile):
            await audio_part.close()


# ---------- live STT ----------

_BYTES_PER_S = SAMPLE_RATE * 2  # PCM 16-bit mono
_MAX_FRAME_BYTES = 64 * 1024


async def _ws_error(ws: WebSocket, code: str, message: str, close_code: int = 1011) -> None:
    try:
        await ws.send_json({"type": "error", "code": code, "message": message})
        await ws.close(close_code)
    except (WebSocketDisconnect, RuntimeError):
        pass


@router.websocket("/stt/stream")
async def stt_stream(
    ws: WebSocket,
    session_id: str = Query(min_length=1, max_length=64),
    lang: str | None = Query(default=None),
) -> None:
    """Live STT: бинарные кадры PCM → {"type": "partial"} … {"type": "final"}. Протокол — api-contract.md."""
    settings = ws.app.state.settings
    try:
        key = check_token(ws)  # type: ignore[arg-type]  # нужны только headers и app
    except ApiError:
        await ws.close(1008, "unauthorized")
        return
    retry_after = ws.app.state.speech_rate_limiter.check(key)
    if retry_after is not None:
        await ws.close(1013, f"rate_limited: retry after {retry_after} s")
        return
    hint: Lang | None = normalize_lang(lang)
    await ws.accept()
    token = set_request_id(new_request_id())
    try:
        await _stt_stream_session(ws, settings, session_id, lang, hint)
    finally:
        reset_request_id(token)


async def _stt_stream_session(ws, settings, session_id, lang_raw, hint) -> None:
    if lang_raw and hint is None:
        await _ws_error(ws, "bad_request", "lang must be ru, kk or en", 1008)
        return
    provider: StreamingStt = ws.app.state.stt_stream
    started = time.perf_counter()
    try:
        session: SttStreamSession = await provider.open(hint)
    except SpeechError as exc:
        logger.error("speech_error", extra={"error": str(exc)})
        await _ws_error(ws, "speech_unavailable", "Speech provider is unavailable")
        return

    state = {"bytes": 0, "ended_by": None, "ended_at": None, "partials": 0, "peak": 0}
    max_bytes = int(settings.max_audio_seconds * _BYTES_PER_S)

    async def end(by: str) -> None:
        if state["ended_by"] is None:
            state["ended_by"], state["ended_at"] = by, time.perf_counter()
            await session.end()

    async def read_client() -> None:
        """Звук и команды посетителя; возвращается, когда клиент ушёл или отменил."""
        while True:
            msg = await ws.receive()
            if msg["type"] == "websocket.disconnect":
                state["ended_by"] = state["ended_by"] or "disconnect"
                return
            data = msg.get("bytes")
            if data is not None:
                if len(data) > _MAX_FRAME_BYTES:
                    raise ValueError(f"audio frame larger than {_MAX_FRAME_BYTES} bytes")
                if state["ended_by"] is None:
                    if state["bytes"] + len(data) > max_bytes:
                        await end("max_length")
                        continue
                    state["bytes"] += len(data)
                    state["peak"] = max(state["peak"], peak_amplitude(data))
                    await session.send(data)
                continue
            try:
                command = json.loads(msg.get("text") or "")
            except ValueError:
                command = {}
            kind = command.get("type") if isinstance(command, dict) else None
            if kind == "end":
                await end("client")
            elif kind == "cancel":
                state["ended_by"] = "cancel"
                return

    async def no_speech_watchdog() -> None:
        await asyncio.sleep(settings.stt_stream_no_speech_s)
        # live-провайдер: не услышал ни слова; буферный: звук не громче тишины (шум зала — не речь)
        quiet = session.has_partials or state["peak"] < settings.stt_silence_peak
        if state["partials"] == 0 and quiet:
            await end("no_speech")

    async def forward_events() -> dict | None:
        async for ev in session.events():
            if ev.kind == "partial":
                state["partials"] += 1
                await ws.send_json({"type": "partial", "text": ev.text})
                continue
            return {
                "type": "final",
                "request_id": get_request_id() or "",
                "text": ev.text,
                "lang": ev.lang or hint or (detect_lang(ev.text) if ev.text else "ru"),
            }
        return None

    reader = asyncio.create_task(read_client())
    watchdog = asyncio.create_task(no_speech_watchdog())
    events = asyncio.create_task(forward_events())
    final = None
    error: tuple[str, str] | None = None
    try:
        await ws.send_json(
            {"type": "ready", "request_id": get_request_id() or "", "provider": session.provider}
        )
        done, _ = await asyncio.wait(
            {reader, events},
            timeout=settings.max_audio_seconds + settings.stt_stream_no_speech_s,
            return_when=asyncio.FIRST_COMPLETED,
        )
        if events in done:
            final = events.result()
        elif reader in done:
            reader.result()  # клиент ушёл или отменил — отвечать некому; ValueError — битый кадр
        else:  # клиент перестал слать звук, не сказав "end"
            await end("timeout")
            final = await asyncio.wait_for(events, settings.stt_timeout_s + 3)
    except SpeechError as exc:
        logger.error("speech_error", extra={"error": str(exc)})
        error = ("speech_unavailable", "Speech provider is unavailable")
    except TimeoutError:
        error = ("timeout", "Speech provider did not answer in time")
    except ValueError as exc:
        error = ("bad_request", str(exc))
    except (WebSocketDisconnect, asyncio.CancelledError):
        state["ended_by"] = state["ended_by"] or "disconnect"
    finally:
        for task in (reader, watchdog, events):
            task.cancel()
        await asyncio.gather(reader, watchdog, events, return_exceptions=True)
        await session.close()

    if final is not None:
        await ws.send_json(final)
        await ws.close(1000)
    elif error is not None:
        await _ws_error(ws, *error, 1008 if error[0] == "bad_request" else 1011)
    ended_at = state["ended_at"] or time.perf_counter()
    logger.info(
        "stt_stream_recognized",
        extra={
            "session_id": session_id,
            "audio_s": round(state["bytes"] / _BYTES_PER_S, 2),
            "lang_requested": hint,
            "lang": final["lang"] if final else None,
            "empty": not (final and final["text"]),
            "partials": state["partials"],
            "ended_by": state["ended_by"] or "provider",
            "error": error[0] if error else None,
            "provider": session.provider,
            "session_ms": int((time.perf_counter() - started) * 1000),
            "latency_ms": int((time.perf_counter() - ended_at) * 1000),
        },
    )


@router.post(
    "/tts",
    response_class=Response,
    responses={
        200: {"content": {"audio/mpeg": {"schema": {"type": "string", "format": "binary"}}}},
        **_ERRORS,
    },
    dependencies=[Depends(require_robot_speech)],
)
async def tts(body: TtsRequest, request: Request) -> Response:
    settings = request.app.state.settings
    provider: TtsProvider = request.app.state.tts
    cache: TtsCache = request.app.state.tts_cache
    lang: Lang = body.lang or detect_lang(body.text)
    key = cache_key(provider.name, provider.voice_id(lang), lang, body.text)

    started = time.perf_counter()
    audio = cache.get(key)
    hit = audio is not None
    if audio is None:
        audio = await _with_timeout(
            provider.tts(body.text, lang),
            settings.tts_timeout_s,
            headers={"X-Speech-Provider": provider.name},
        )
        cache.put(key, audio)
    logger.info(
        "tts_synthesized",
        extra={
            "lang": lang,
            "chars": len(body.text),
            "cache": "hit" if hit else "miss",
            "bytes": len(audio),
            "provider": provider.name,
            "latency_ms": int((time.perf_counter() - started) * 1000),
        },
    )
    return Response(
        content=audio,
        media_type="audio/mpeg",
        headers={
            "X-Cache": "hit" if hit else "miss",
            "X-Speech-Provider": provider.name,
            "Cache-Control": "no-store",
        },
    )
