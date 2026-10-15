"""Ошибки в формате контракта: {"error": {"code", "message"}, "request_id"}."""

from __future__ import annotations

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

from app.logging_setup import get_request_id


class ApiError(Exception):
    def __init__(
        self,
        status: int,
        code: str,
        message: str,
        headers: dict[str, str] | None = None,
    ) -> None:
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message
        self.headers = headers or {}


def bad_request(message: str) -> ApiError:
    return ApiError(400, "bad_request", message)


def unauthorized() -> ApiError:
    return ApiError(401, "unauthorized", "Missing or invalid bearer token")


def payload_too_large(message: str) -> ApiError:
    return ApiError(413, "payload_too_large", message)


def rate_limited(retry_after: int) -> ApiError:
    return ApiError(
        429, "rate_limited", "Too many requests", headers={"Retry-After": str(retry_after)}
    )


def llm_unavailable(message: str = "LLM provider is unavailable") -> ApiError:
    return ApiError(503, "llm_unavailable", message)


def llm_timeout() -> ApiError:
    return ApiError(504, "timeout", "LLM did not answer in time")


def error_body(code: str, message: str) -> dict:
    return {"error": {"code": code, "message": message}, "request_id": get_request_id()}


def error_response(status: int, code: str, message: str, headers: dict | None = None):
    return JSONResponse(error_body(code, message), status_code=status, headers=headers)


_HTTP_CODES = {
    400: "bad_request",
    401: "unauthorized",
    404: "not_found",
    405: "method_not_allowed",
    413: "payload_too_large",
    415: "unsupported_media_type",
    429: "rate_limited",
}


def install_error_handlers(app: FastAPI) -> None:
    @app.exception_handler(ApiError)
    async def _api_error(_: Request, exc: ApiError):
        return error_response(exc.status, exc.code, exc.message, exc.headers)

    @app.exception_handler(RequestValidationError)
    async def _validation_error(_: Request, exc: RequestValidationError):
        # Контракт: невалидный запрос → 400 bad_request (а не 422 FastAPI)
        parts = []
        for err in exc.errors()[:5]:
            loc = ".".join(str(x) for x in err.get("loc", ()) if x != "body")
            parts.append(f"{loc}: {err.get('msg')}" if loc else str(err.get("msg")))
        return error_response(400, "bad_request", "; ".join(parts) or "Invalid request")

    @app.exception_handler(StarletteHTTPException)
    async def _http_error(_: Request, exc: StarletteHTTPException):
        code = _HTTP_CODES.get(exc.status_code, "error")
        return error_response(exc.status_code, code, str(exc.detail), exc.headers)
