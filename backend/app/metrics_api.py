"""Мониторинг задержек (docs/api-contract.md, «Мониторинг задержек»).

- POST /v1/metrics/turn — тайминги хода с робота (токен робота, лимит общий с /stt и /tts) → 204.
- GET /v1/admin/metrics?hours=24 — сводка p50/p95/count (ADMIN_TOKEN; не задан — 404).
- GET /v1/admin/metrics/page — HTML-страница сводки без внешних ресурсов (данных в ней нет;
  сводку она запрашивает сама с токеном из поля ввода). ADMIN_TOKEN не задан — 404.
"""

from __future__ import annotations

import base64
import hashlib
import re
import secrets
from functools import cache
from pathlib import Path
from typing import Annotated, Literal

from fastapi import APIRouter, Depends, Query, Request, Response
from fastapi.responses import HTMLResponse
from pydantic import BaseModel, ConfigDict, Field, SecretStr
from starlette.concurrency import run_in_threadpool

from app.errors import ApiError, rate_limited, unauthorized
from app.metrics import LatencyRecorder
from app.schemas import ErrorResponse, LangField
from app.speech_api import require_robot_speech

router = APIRouter(prefix="/v1")

PAGE_PATH = Path(__file__).with_name("metrics_page.html")
MAX_TURN_MS = 120_000

# Поля *_ms хода робота (все — от момента, когда VAD решил, что посетитель закончил)
ROBOT_STAGES = (
    "stt_final_ms",
    "chat_first_event_ms",
    "first_phrase_ms",
    "tts_first_byte_ms",
    "first_audio_ms",
    "vad_silence_ms",
)

TurnMs = Annotated[int | None, Field(default=None, ge=0, le=MAX_TURN_MS)]


class TurnMetrics(BaseModel):
    """Тайминги одного хода разговора на стороне робота. Тексты реплик сюда не отправляются:
    незнакомые поля отбрасываются и никуда не пишутся."""

    model_config = ConfigDict(extra="ignore")

    session_id: str = Field(min_length=1, max_length=64)
    request_id: str | None = Field(default=None, max_length=64)
    lang: LangField | None = None
    mode: Literal["agent", "ask", "local"]
    stt_final_ms: TurnMs
    chat_first_event_ms: TurnMs
    first_phrase_ms: TurnMs
    tts_first_byte_ms: TurnMs
    first_audio_ms: TurnMs
    vad_silence_ms: TurnMs
    fallback: bool = False
    interrupted: bool = False


@router.post(
    "/metrics/turn",
    status_code=204,
    response_class=Response,
    responses={code: {"model": ErrorResponse} for code in (400, 401, 413, 429)},
    dependencies=[Depends(require_robot_speech)],
)
async def metrics_turn(body: TurnMetrics, request: Request) -> Response:
    recorder: LatencyRecorder = request.app.state.metrics
    stages: dict[str, int | None] = {name: getattr(body, name) for name in ROBOT_STAGES}
    stages["turn"] = None  # строка хода: флаги fallback/interrupted (доля фолбэков)
    recorder.record(
        "robot",
        stages,
        lang=body.lang,
        provider=body.mode,
        request_id=body.request_id or "",
        session_id=body.session_id,
        extra={"mode": body.mode, "fallback": body.fallback, "interrupted": body.interrupted},
        keep_null=("turn",),
    )
    return Response(status_code=204)


# ---------- админ ----------


def _not_found() -> ApiError:
    return ApiError(404, "not_found", "Not Found")


def admin_token(request: Request) -> str:
    value = request.app.state.settings.admin_token
    secret = value.get_secret_value() if isinstance(value, SecretStr) else (value or "")
    return secret.strip()


def require_admin(request: Request) -> None:
    """ADMIN_TOKEN не задан → 404 (админки нет); неверный/нет Bearer → 401. Лимит — на всех."""
    expected = admin_token(request)
    if not expected:
        raise _not_found()
    retry_after = request.app.state.admin_rate_limiter.check("admin")
    if retry_after is not None:
        raise rate_limited(retry_after)
    scheme, _, token = request.headers.get("authorization", "").partition(" ")
    if scheme.lower() != "bearer" or not secrets.compare_digest(
        token.strip().encode(), expected.encode()
    ):
        raise unauthorized()


class StageStats(BaseModel):
    kind: str
    stage: str
    count: int
    errors: int
    p50: int | None
    p95: int | None


class ProviderStats(StageStats):
    provider: str | None


class LangStats(StageStats):
    lang: str | None


class HourStats(StageStats):
    hour: str


class FallbackStats(BaseModel):
    kind: str
    turns: int
    fallbacks: int
    interrupted: int
    fallback_share: float


class MetricsSummary(BaseModel):
    hours: int
    since: str
    generated_at: str
    stages: list[StageStats]
    by_provider: list[ProviderStats]
    by_lang: list[LangStats]
    fallback: list[FallbackStats]
    hourly: list[HourStats]


@router.get(
    "/admin/metrics",
    response_model=MetricsSummary,
    responses={code: {"model": ErrorResponse} for code in (400, 401, 404, 429)},
    dependencies=[Depends(require_admin)],
)
async def admin_metrics(
    request: Request, hours: Annotated[int, Query(ge=1, le=24 * 31)] = 24
) -> MetricsSummary:
    """p50/p95/count по этапам (робот и сервер), по моделям/провайдерам и языкам, доля фолбэков, по часам."""
    summary = await run_in_threadpool(request.app.state.latency_repo.summary, hours)
    return MetricsSummary.model_validate(summary)


@cache
def _page() -> tuple[str, str]:
    """HTML страницы и CSP: inline-скрипт и стиль разрешены только по sha256 (без unsafe-inline)."""
    html = PAGE_PATH.read_text(encoding="utf-8")

    def digest(tag: str) -> str:
        m = re.search(rf"<{tag}>(.*?)</{tag}>", html, re.DOTALL)
        assert m is not None, f"<{tag}> is missing in {PAGE_PATH.name}"
        return (
            "'sha256-"
            + base64.b64encode(hashlib.sha256(m.group(1).encode()).digest()).decode()
            + "'"
        )

    csp = (
        "default-src 'none'; "
        f"script-src {digest('script')}; style-src {digest('style')}; "
        "connect-src 'self'; img-src 'self'; base-uri 'none'; form-action 'none'; "
        "frame-ancestors 'none'"
    )
    return html, csp


@router.get("/admin/metrics/page", response_class=HTMLResponse, include_in_schema=True)
async def admin_metrics_page(request: Request) -> HTMLResponse:
    if not admin_token(request):
        raise _not_found()
    html, csp = _page()
    return HTMLResponse(
        html,
        headers={
            "Content-Security-Policy": csp,
            "Cache-Control": "no-store",
            "X-Content-Type-Options": "nosniff",
            "Referrer-Policy": "no-referrer",
        },
    )
