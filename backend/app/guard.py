"""Защита /v1/* до разбора запроса FastAPI (чистый ASGI, docs/api-contract.md «Таймауты и лимиты»).

Порядок для всех /v1/* кроме /v1/health:
1. Bearer-токен любого профиля робота (secrets.compare_digest, app/profiles.py). Нет/неверный → 401, тело не
   читается вообще.
2. Лимит тела по пути. Content-Length больше лимита → 413 сразу, без чтения.
   Без Content-Length (chunked) — байты считаются по мере чтения; превышение → 413, чтение обрывается.

Depends(require_robot) в эндпоинтах остаётся второй линией (и считает rate limit).
"""

from __future__ import annotations

from starlette.exceptions import HTTPException
from starlette.types import ASGIApp, Message, Receive, Scope, Send

from app.config import Settings
from app.errors import error_response
from app.profiles import bearer, profile_for_token

PUBLIC_PATHS = frozenset({"/v1/health"})
# Админка (статистика задержек): свой токен ADMIN_TOKEN, его проверяет сам эндпоинт (app/metrics_api.py)
ADMIN_PREFIX = "/v1/admin/"
DEFAULT_BODY_LIMIT = 16 * 1024


def body_limits(settings: Settings) -> dict[str, int]:
    return {
        "/v1/ask": 16 * 1024,
        "/v1/chat": 16 * 1024,
        "/v1/look": settings.max_image_bytes + 64 * 1024,
        "/v1/stt": settings.max_audio_bytes + 64 * 1024,
        "/v1/tts": 4 * 1024,
        "/v1/metrics/turn": 4 * 1024,
    }


class BodyTooLarge(HTTPException):
    """Starlette HTTPException: FastAPI пробрасывает её из чтения тела как есть (а не 400)."""

    def __init__(self, limit: int) -> None:
        super().__init__(413, f"Request body exceeds {limit} bytes")


def _content_length(headers: list[tuple[bytes, bytes]]) -> int | None:
    for name, value in headers:
        if name.lower() == b"content-length":
            try:
                return int(value)
            except ValueError:
                return None
    return None


def bearer_profile_ok(headers: list[tuple[bytes, bytes]], settings: Settings) -> bool:
    """Токен одного из профилей робота (ROBOT_TOKEN или KURULTAI_ROBOT_TOKEN, app/profiles.py)."""
    raw = b""
    for name, value in headers:
        if name.lower() == b"authorization":
            raw = value
            break
    return profile_for_token(settings, bearer(raw.decode("latin-1"))) is not None


class GuardMiddleware:
    def __init__(self, app: ASGIApp, settings: Settings) -> None:
        self.app = app
        self.settings = settings
        self.limits = body_limits(settings)

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        path = scope.get("path", "") if scope["type"] == "http" else ""
        if not path.startswith("/v1/") or path in PUBLIC_PATHS or path.startswith(ADMIN_PREFIX):
            await self.app(scope, receive, send)
            return

        headers = scope.get("headers", [])
        if not bearer_profile_ok(headers, self.settings):
            response = error_response(401, "unauthorized", "Missing or invalid bearer token")
            await response(scope, receive, send)
            return

        limit = self.limits.get(path, DEFAULT_BODY_LIMIT)
        declared = _content_length(headers)
        if declared is not None and declared > limit:
            await self._too_large(scope, receive, send, limit)
            return

        received = 0

        async def limited_receive() -> Message:
            nonlocal received
            message = await receive()
            if message["type"] == "http.request":
                received += len(message.get("body", b""))
                if received > limit:
                    raise BodyTooLarge(limit)
            return message

        started = False

        async def tracking_send(message: Message) -> None:
            nonlocal started
            if message["type"] == "http.response.start":
                started = True
            await send(message)

        try:
            await self.app(scope, limited_receive, tracking_send)
        except BodyTooLarge:
            # Обычно сюда не доходит (FastAPI сам отдаёт 413 через обработчик ошибок)
            if started:
                raise
            await self._too_large(scope, receive, send, limit)

    @staticmethod
    async def _too_large(scope: Scope, receive: Receive, send: Send, limit: int) -> None:
        response = error_response(413, "payload_too_large", f"Request body exceeds {limit} bytes")
        await response(scope, receive, send)
