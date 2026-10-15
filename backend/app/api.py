"""Эндпоинты v1 по docs/api-contract.md."""

from __future__ import annotations

import hashlib
import json
import secrets
from collections.abc import AsyncIterator
from typing import Annotated

from fastapi import APIRouter, Depends, Query, Request, Response
from fastapi.responses import StreamingResponse
from pydantic import ValidationError
from starlette.datastructures import UploadFile
from starlette.formparsers import MultiPartException, MultiPartParser

from app import __version__
from app.errors import (
    bad_request,
    payload_too_large,
    rate_limited,
    unauthorized,
)
from app.lang import Lang
from app.logging_setup import get_request_id
from app.schemas import (
    AnswerResponse,
    AskRequest,
    ErrorResponse,
    HealthResponse,
    LookMeta,
    PlaceOut,
    PlacesResponse,
    QuickQuestionOut,
    QuickQuestionsResponse,
)
from app.service import AnswerService

router = APIRouter(prefix="/v1")

_ERRORS = {
    400: {"model": ErrorResponse},
    401: {"model": ErrorResponse},
    429: {"model": ErrorResponse},
}
_LLM_ERRORS = {**_ERRORS, 503: {"model": ErrorResponse}, 504: {"model": ErrorResponse}}


def check_token(request: Request) -> str:
    """Проверяет Bearer-токен, возвращает ключ для rate limit (хеш токена)."""
    settings = request.app.state.settings
    expected = settings.robot_token.get_secret_value()
    header = request.headers.get("authorization", "")
    scheme, _, token = header.partition(" ")
    if (
        not expected
        or scheme.lower() != "bearer"
        or not secrets.compare_digest(token.strip().encode(), expected.encode())
    ):
        raise unauthorized()
    return hashlib.sha256(token.strip().encode()).hexdigest()[:16]


def require_robot(request: Request) -> str:
    key = check_token(request)
    retry_after = request.app.state.rate_limiter.check(key)
    if retry_after is not None:
        raise rate_limited(retry_after)
    return key


def _service(request: Request) -> AnswerService:
    return request.app.state.service


def _request_id() -> str:
    return get_request_id() or ""


@router.get("/health", response_model=HealthResponse)
async def health() -> HealthResponse:
    return HealthResponse(version=__version__)


def _sse(event: str, data: dict) -> bytes:
    return f"event: {event}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n".encode()


@router.post(
    "/ask",
    response_model=AnswerResponse,
    responses={
        **_LLM_ERRORS,
        200: {
            "content": {
                "application/json": {},
                "text/event-stream": {
                    "schema": {
                        "type": "string",
                        "description": "SSE: event phrase {text} × N, затем done {request_id, lang, found, sources} или error",
                    }
                },
            }
        },
    },
    dependencies=[Depends(require_robot)],
)
async def ask(body: AskRequest, request: Request):
    service = _service(request)
    request_id = _request_id()
    if "text/event-stream" not in request.headers.get("accept", ""):
        return await service.ask(body, request_id)

    async def stream() -> AsyncIterator[bytes]:
        # Настоящий стриминг: фразы уходят по мере того, как модель дописывает предложения
        async for event, data in service.ask_stream(body, request_id):
            yield _sse(event, data)

    return StreamingResponse(
        stream(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


class _InMemoryMultiPartParser(MultiPartParser):
    # Файлы до 2 МБ держим в памяти (SpooledTemporaryFile не сбрасывается на диск):
    # кадр с камеры не должен попадать на диск. Тело больше лимита отсекаем раньше.
    spool_max_size = 2 * 1024 * 1024


_LOOK_OPENAPI = {
    "requestBody": {
        "required": True,
        "content": {
            "multipart/form-data": {
                "schema": {
                    "type": "object",
                    "required": ["image", "meta"],
                    "properties": {
                        "image": {
                            "type": "string",
                            "format": "binary",
                            "description": "JPEG ≤ 1 МБ",
                        },
                        "meta": {
                            "type": "string",
                            "description": 'JSON: {"question"?, "lang"?, "session_id", "place_id"?}',
                        },
                    },
                }
            }
        },
    }
}


async def _read_body_limited(request: Request, limit: int) -> bytes:
    declared = request.headers.get("content-length")
    if declared and declared.isdigit() and int(declared) > limit:
        raise payload_too_large(f"Request body exceeds {limit} bytes")
    chunks: list[bytes] = []
    size = 0
    async for chunk in request.stream():
        size += len(chunk)
        if size > limit:
            raise payload_too_large(f"Request body exceeds {limit} bytes")
        chunks.append(chunk)
    return b"".join(chunks)


@router.post(
    "/look",
    response_model=AnswerResponse,
    responses={**_LLM_ERRORS, 413: {"model": ErrorResponse}},
    dependencies=[Depends(require_robot)],
    openapi_extra=_LOOK_OPENAPI,
)
async def look(request: Request) -> AnswerResponse:
    service = _service(request)
    max_image = request.app.state.settings.max_image_bytes
    ctype = request.headers.get("content-type", "")
    if not ctype.startswith("multipart/form-data"):
        raise bad_request("Expected multipart/form-data with parts 'image' and 'meta'")

    body = await _read_body_limited(request, max_image + 64 * 1024)

    async def _replay() -> AsyncIterator[bytes]:
        yield body

    image_part: UploadFile | None = None
    try:
        try:
            form = await _InMemoryMultiPartParser(
                request.headers, _replay(), max_files=1, max_fields=5, max_part_size=16 * 1024
            ).parse()
        except MultiPartException as exc:
            raise bad_request(f"Invalid multipart body: {exc.message}") from exc
        body = b""  # тело больше не нужно — не держим копию кадра

        image_part = form.get("image")  # type: ignore[assignment]
        meta_raw = form.get("meta")
        if not isinstance(image_part, UploadFile):
            raise bad_request("Part 'image' (image/jpeg) is required")
        if not isinstance(meta_raw, str):
            raise bad_request("Part 'meta' (JSON string) is required")
        try:
            meta = LookMeta.model_validate_json(meta_raw)
        except ValidationError as exc:
            first = exc.errors()[0]
            loc = ".".join(str(x) for x in first.get("loc", ()))
            raise bad_request(
                f"meta.{loc}: {first.get('msg')}" if loc else "Invalid meta JSON"
            ) from exc

        image = await image_part.read()
        if len(image) > max_image:
            raise payload_too_large(f"Image exceeds {max_image} bytes")
        if not image.startswith(b"\xff\xd8"):
            raise bad_request("Image must be a JPEG")
        try:
            return await service.look(meta, image, _request_id())
        finally:
            del image
    finally:
        if image_part is not None:
            await image_part.close()


def _places_payload(request: Request) -> tuple[PlacesResponse, str]:
    kb = request.app.state.store.get()
    payload = PlacesResponse(
        places=[
            PlaceOut(
                id=p.id,
                map_name=p.map_name,
                names={k: v for k, v in p.names.model_dump().items() if v},
                synonyms=p.synonyms,
                description={k: v for k, v in p.description.model_dump().items() if v},
            )
            for p in kb.places
        ]
    )
    etag = '"' + hashlib.sha256(payload.model_dump_json().encode()).hexdigest()[:32] + '"'
    return payload, etag


def _etag_matches(header: str | None, etag: str) -> bool:
    if not header:
        return False
    if header.strip() == "*":
        return True
    tags = [t.strip().removeprefix("W/") for t in header.split(",")]
    return etag in tags


@router.get(
    "/places",
    response_model=PlacesResponse,
    responses={**_ERRORS, 304: {"description": "Not modified (If-None-Match)"}},
    dependencies=[Depends(require_robot)],
)
async def places(request: Request, response: Response):
    payload, etag = _places_payload(request)
    if _etag_matches(request.headers.get("if-none-match"), etag):
        return Response(status_code=304, headers={"ETag": etag})
    response.headers["ETag"] = etag
    response.headers["Cache-Control"] = "no-cache"
    return payload


@router.get(
    "/quick-questions",
    response_model=QuickQuestionsResponse,
    responses=_ERRORS,
    dependencies=[Depends(require_robot)],
)
async def quick_questions(
    request: Request, lang: Annotated[Lang, Query()] = "ru"
) -> QuickQuestionsResponse:
    kb = request.app.state.store.get()
    return QuickQuestionsResponse(
        items=[QuickQuestionOut(id=q.id, text=q.text.get(lang)) for q in kb.quick_questions]
    )
