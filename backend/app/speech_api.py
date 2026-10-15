"""Голос через сервер: POST /v1/stt и POST /v1/tts (docs/api-contract.md, «Голос через сервер»).

Аудио посетителей живёт только в памяти на время запроса; в логи — только длительность и язык.
"""

from __future__ import annotations

import asyncio
import time
from collections.abc import AsyncIterator

from fastapi import APIRouter, Depends, Request, Response
from pydantic import BaseModel, Field, ValidationError, field_validator
from starlette.datastructures import UploadFile
from starlette.formparsers import MultiPartException

from app.api import _InMemoryMultiPartParser, _read_body_limited, check_token
from app.errors import ApiError, bad_request, payload_too_large, rate_limited
from app.lang import Lang, detect_lang
from app.logging_setup import get_request_id, logger
from app.schemas import ErrorResponse, LangField
from app.speech.base import SpeechError, SttProvider, SttResult, TtsProvider
from app.speech.cache import TtsCache, cache_key
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

    body = await _read_body_limited(request, settings.max_audio_bytes + 16 * 1024)

    async def _replay() -> AsyncIterator[bytes]:
        yield body

    audio_part: UploadFile | None = None
    try:
        try:
            form = await _InMemoryMultiPartParser(
                request.headers, _replay(), max_files=1, max_fields=5, max_part_size=16 * 1024
            ).parse()
        except MultiPartException as exc:
            raise bad_request(f"Invalid multipart body: {exc.message}") from exc
        body = b""

        audio_part = form.get("audio")  # type: ignore[assignment]
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
        if audio_part is not None:
            await audio_part.close()


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
