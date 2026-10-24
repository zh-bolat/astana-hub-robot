"""Голос через сервер: POST /v1/stt и POST /v1/tts (docs/api-contract.md, «Голос через сервер»).

Аудио посетителей живёт только в памяти на время запроса; в логи — только длительность и язык.
"""

from __future__ import annotations

import asyncio
import json
import time
from collections.abc import AsyncIterator, Callable
from typing import Annotated

from fastapi import APIRouter, Depends, Query, Request, Response, WebSocket, WebSocketDisconnect
from pydantic import BaseModel, Field, ValidationError, field_validator
from starlette.datastructures import UploadFile
from starlette.formparsers import MultiPartException
from starlette.types import Receive, Scope, Send

from app.api import _InMemoryMultiPartParser, _read_body_limited, check_token
from app.errors import ApiError, bad_request, error_response, payload_too_large, rate_limited
from app.lang import Lang, detect_lang, normalize_lang
from app.logging_setup import (
    get_request_id,
    logger,
    new_request_id,
    reset_request_id,
    set_request_id,
)
from app.metrics import LatencyRecorder, elapsed_ms
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
        metrics: LatencyRecorder = request.app.state.metrics
        audio_s = round(audio.duration_s, 2)
        silence = peak_amplitude(audio.pcm) < settings.stt_silence_peak or audio.duration_s < 0.15
        try:
            if silence:
                # тишина — не тратим запрос к провайдеру и не ловим «галлюцинации»
                result = SttResult(text="", lang=meta.lang or "ru")
            else:
                try:
                    result = await _with_timeout(
                        provider.stt(audio.pcm, audio.sample_rate, meta.lang),
                        settings.stt_timeout_s,
                    )
                except ApiError as exc:
                    metrics.record(
                        "stt",
                        {"stt_ms": elapsed_ms(started)},
                        lang=meta.lang,
                        provider=provider.name,
                        session_id=meta.session_id,
                        ok=False,
                        extra={"audio_s": audio_s, "error": exc.code},
                    )
                    raise
        finally:
            audio.pcm = b""  # аудио посетителя не храним
        if not silence:  # тишина без вызова провайдера не искажает задержку STT
            metrics.record(
                "stt",
                {"stt_ms": elapsed_ms(started)},
                lang=result.lang,
                provider=provider.name,
                session_id=meta.session_id,
                extra={"audio_s": audio_s, "empty": not result.text},
            )
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

    state = {
        "bytes": 0,
        "ended_by": None,
        "ended_at": None,
        "partials": 0,
        "peak": 0,
        "final_at": None,
    }
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
            state["final_at"] = time.perf_counter()
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
    if state["ended_at"] is not None and (final is not None or error is not None):
        # live: от конца речи (end клиента / VAD / лимит) до финала
        ws.app.state.metrics.record(
            "stt",
            {
                "stt_live_final_ms": int(
                    ((state["final_at"] or time.perf_counter()) - ended_at) * 1000
                )
            },
            lang=final["lang"] if final else hint,
            provider=session.provider,
            request_id=get_request_id(),
            session_id=session_id,
            ok=error is None,
            extra={
                "ended_by": state["ended_by"],
                "partials": state["partials"],
                "audio_s": round(state["bytes"] / _BYTES_PER_S, 2),
                "empty": not (final and final["text"]),
                "error": error[0] if error else None,
            },
        )
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


# ---------- TTS ----------


async def _first_chunk(chunks: AsyncIterator[bytes]) -> bytes:
    async for chunk in chunks:
        if chunk:
            return chunk
    raise SpeechError("tts stream returned empty audio")


async def _aclose(chunks: AsyncIterator[bytes]) -> None:
    aclose = getattr(chunks, "aclose", None)
    if aclose is not None:
        try:
            await aclose()
        except Exception as exc:  # закрытие потока провайдера не должно ронять ответ
            logger.warning("tts_stream_close_failed", extra={"error": type(exc).__name__})


class TtsStreamResponse(Response):
    """audio/mpeg кусками по мере синтеза (chunked, без Content-Length).

    Первый кусок ждём до отправки заголовков: ошибка/таймаут провайдера до первых байт → обычный ответ
    об ошибке (503 speech_unavailable / 504 timeout). Обрыв посреди потока → соединение закрывается без
    завершающего куска (клиент видит обрыв и дозапрашивает фразу без stream). Весь поток копится в памяти
    и после успешного окончания кладётся в кеш. Все шаги потока провайдера — в одной задаче."""

    media_type = "audio/mpeg"

    def __init__(
        self,
        chunks: AsyncIterator[bytes],
        *,
        first_timeout_s: float,
        total_timeout_s: float,
        headers: dict[str, str],
        on_first: Callable[[int], None],
        on_done: Callable[[bytes | None, str | None], None],
    ) -> None:
        super().__init__(media_type=self.media_type)
        self.chunks = chunks
        self.first_timeout_s = first_timeout_s
        self.total_timeout_s = total_timeout_s
        self.extra_headers = headers
        self.on_first = on_first
        self.on_done = on_done

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        loop = asyncio.get_running_loop()
        started = loop.time()
        audio: list[bytes] = []
        error: str | None = "interrupted"
        try:
            try:
                async with asyncio.timeout(self.first_timeout_s):
                    first = await _first_chunk(self.chunks)
            except TimeoutError:
                error = "timeout"
                err: ApiError = speech_timeout()
            except SpeechError as exc:
                logger.error("speech_error", extra={"error": str(exc), "stream": True})
                error = "speech_unavailable"
                err = speech_unavailable()
            else:
                err = None  # type: ignore[assignment]
            if err is not None:
                headers = {**err.headers, **self.extra_headers, "Cache-Control": "no-store"}
                await error_response(err.status, err.code, err.message, headers)(
                    scope, receive, send
                )
                return
            self.on_first(int((loop.time() - started) * 1000))
            headers = [
                (b"content-type", b"audio/mpeg"),
                *((k.lower().encode(), v.encode()) for k, v in self.extra_headers.items()),
            ]
            await send({"type": "http.response.start", "status": 200, "headers": headers})
            audio.append(first)
            await send({"type": "http.response.body", "body": first, "more_body": True})
            try:
                async with asyncio.timeout(max(0.01, started + self.total_timeout_s - loop.time())):
                    async for chunk in self.chunks:
                        if chunk:
                            audio.append(chunk)
                            await send(
                                {"type": "http.response.body", "body": chunk, "more_body": True}
                            )
            except (TimeoutError, SpeechError) as exc:
                error = "timeout" if isinstance(exc, TimeoutError) else "speech_unavailable"
                logger.warning("tts_stream_broken", extra={"error": str(exc) or error})
                return  # без завершающего куска: сервер закроет соединение, клиент увидит обрыв
            await send({"type": "http.response.body", "body": b"", "more_body": False})
            error = None
        finally:
            await _aclose(self.chunks)
            self.on_done(b"".join(audio) if error is None else None, error)


@router.post(
    "/tts",
    response_class=Response,
    responses={
        200: {
            "description": "MP3 целиком; с stream=true — кусками по мере синтеза (chunked)",
            "content": {"audio/mpeg": {"schema": {"type": "string", "format": "binary"}}},
        },
        **_ERRORS,
    },
    dependencies=[Depends(require_robot_speech)],
)
async def tts(
    body: TtsRequest,
    request: Request,
    stream: Annotated[
        bool, Query(description="Потоковый режим: audio/mpeg кусками по мере синтеза")
    ] = False,
) -> Response:
    settings = request.app.state.settings
    provider: TtsProvider = request.app.state.tts
    cache: TtsCache = request.app.state.tts_cache
    metrics: LatencyRecorder = request.app.state.metrics
    lang: Lang = body.lang or detect_lang(body.text)
    key = cache_key(provider.name, provider.voice_id(lang), lang, body.text)
    common = {"X-Speech-Provider": provider.name, "Cache-Control": "no-store"}

    def record(first_ms: int | None, total_ms: int, hit: bool, ok: bool, extra: dict) -> None:
        metrics.record(
            "tts",
            {"tts_first_byte_ms": first_ms, "tts_total_ms": total_ms},
            lang=lang,
            provider=provider.name,
            ok=ok,
            extra={"cache": "hit" if hit else "miss", "chars": len(body.text), "stream": stream}
            | extra,
        )

    def log(hit: bool, size: int, latency_ms: int, **extra) -> None:
        logger.info(
            "tts_synthesized",
            extra={
                "lang": lang,
                "chars": len(body.text),
                "cache": "hit" if hit else "miss",
                "bytes": size,
                "provider": provider.name,
                "latency_ms": latency_ms,
                "stream": stream,
                **extra,
            },
        )

    started = time.perf_counter()
    audio = cache.get(key)
    if audio is not None:  # готовая фраза — сразу целиком, и в потоковом режиме тоже
        ms = elapsed_ms(started)
        record(ms, ms, hit=True, ok=True, extra={})
        log(True, len(audio), ms)
        return Response(
            content=audio, media_type="audio/mpeg", headers={"X-Cache": "hit", **common}
        )

    stream_fn = getattr(provider, "tts_stream", None) if stream else None
    if stream_fn is not None:

        def on_first(first_ms: int) -> None:
            state["first_ms"] = first_ms

        def on_done(full: bytes | None, error: str | None) -> None:
            total = elapsed_ms(started)
            if full is not None:
                cache.put(key, full)
            record(state["first_ms"], total, hit=False, ok=full is not None, extra={"error": error})
            log(False, len(full or b""), total, first_byte_ms=state["first_ms"], error=error)

        state: dict = {"first_ms": None}
        return TtsStreamResponse(
            stream_fn(body.text, lang),
            first_timeout_s=settings.tts_timeout_s,
            total_timeout_s=settings.tts_stream_total_s,
            headers={"X-Cache": "miss", **common},
            on_first=on_first,
            on_done=on_done,
        )

    try:
        audio = await _with_timeout(
            provider.tts(body.text, lang),
            settings.tts_timeout_s,
            headers={"X-Speech-Provider": provider.name},
        )
    except ApiError as exc:
        record(None, elapsed_ms(started), hit=False, ok=False, extra={"error": exc.code})
        raise
    cache.put(key, audio)
    ms = elapsed_ms(started)
    # без потока первый байт уходит вместе со всем ответом
    record(ms, ms, hit=False, ok=True, extra={})
    log(False, len(audio), ms)
    return Response(content=audio, media_type="audio/mpeg", headers={"X-Cache": "miss", **common})
