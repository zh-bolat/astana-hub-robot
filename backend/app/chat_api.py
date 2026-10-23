"""Агентский чат: POST /v1/chat (SSE) и POST /v1/chat/{turn_id}/frame (docs/api-contract.md)."""

from __future__ import annotations

from collections.abc import AsyncIterator
from contextlib import aclosing
from typing import Annotated

from fastapi import APIRouter, Depends, Path, Request, Response
from starlette.datastructures import UploadFile
from starlette.formparsers import MultiPartException

from app.agent.service import ChatService
from app.api import (
    _ERRORS,
    ClosingStreamingResponse,
    _InMemoryMultiPartParser,
    _read_body_limited,
    _request_id,
    _sse,
    check_token,
    require_robot,
)
from app.errors import ApiError, bad_request, payload_too_large
from app.schemas import ChatRequest, ErrorResponse

router = APIRouter(prefix="/v1")

_CHAT_SSE = {
    200: {
        "content": {
            "text/event-stream": {
                "schema": {
                    "type": "string",
                    "description": "SSE: turn {turn_id, request_id} → emotion {name} → phrase {text} × N "
                    "→ action {name, args} × N → done {request_id, lang, tools} | error",
                }
            }
        }
    }
}


@router.post(
    "/chat",
    response_class=ClosingStreamingResponse,
    responses={**_ERRORS, **_CHAT_SSE},
    dependencies=[Depends(require_robot)],
)
async def chat(body: ChatRequest, request: Request) -> ClosingStreamingResponse:
    """Ход разговора с агентом. Ответ — только SSE (заголовок Accept не обязателен)."""
    service: ChatService = request.app.state.chat
    request_id = _request_id()

    async def stream() -> AsyncIterator[bytes]:
        async with aclosing(service.turn(body, request_id)) as events:
            async for event, data in events:
                yield _sse(event, data)

    return ClosingStreamingResponse(
        stream(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


_FRAME_OPENAPI = {
    "requestBody": {
        "required": True,
        "content": {
            "multipart/form-data": {
                "schema": {
                    "type": "object",
                    "required": ["image"],
                    "properties": {
                        "image": {
                            "type": "string",
                            "format": "binary",
                            "description": "JPEG ≤ 1 МБ",
                        }
                    },
                }
            }
        },
    }
}

TurnId = Annotated[str, Path(min_length=1, max_length=64)]


def _no_turn() -> ApiError:
    return ApiError(404, "not_found", "Turn not found, closed, or no frame was requested")


@router.post(
    "/chat/{turn_id}/frame",
    status_code=204,
    response_class=Response,
    responses={
        400: {"model": ErrorResponse},
        401: {"model": ErrorResponse},
        404: {"model": ErrorResponse},
        413: {"model": ErrorResponse},
    },
    openapi_extra=_FRAME_OPENAPI,
)
async def chat_frame(turn_id: TurnId, request: Request) -> Response:
    """Кадр по запросу агента (action look). Кадр только в памяти, на диск и в логи не попадает."""
    check_token(request)
    broker = request.app.state.chat.broker
    if not broker.is_waiting(turn_id):
        raise _no_turn()
    max_image = request.app.state.settings.max_image_bytes
    if not request.headers.get("content-type", "").startswith("multipart/form-data"):
        raise bad_request("Expected multipart/form-data with part 'image'")
    body = await _read_body_limited(request, max_image + 64 * 1024)

    async def _replay() -> AsyncIterator[bytes]:
        yield body

    image_part: object = None
    try:
        try:
            form = await _InMemoryMultiPartParser(
                request.headers, _replay(), max_files=1, max_fields=5, max_part_size=16 * 1024
            ).parse()
        except MultiPartException as exc:
            raise bad_request(f"Invalid multipart body: {exc.message}") from exc
        body = b""
        image_part = form.get("image")
        if not isinstance(image_part, UploadFile):
            raise bad_request("Part 'image' (image/jpeg) is required")
        image = await image_part.read()
        if len(image) > max_image:
            raise payload_too_large(f"Image exceeds {max_image} bytes")
        if not image.startswith(b"\xff\xd8"):
            raise bad_request("Image must be a JPEG")
        if not broker.deliver(turn_id, image):
            raise _no_turn()
        del image
        return Response(status_code=204)
    finally:
        if isinstance(image_part, UploadFile):
            await image_part.close()
