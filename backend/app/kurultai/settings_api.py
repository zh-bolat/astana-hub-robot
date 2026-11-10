"""Переключатели вкладки «Робот и настройки» (docs/kurultai/robot-settings.md, раздел «Сервер»):
`GET/POST /v1/robot/settings` — движок live (realtime / pipeline) и режим зала (normal / noisy), плюс состояние для
экрана робота: предохранитель Realtime, остаток ElevenLabs, версия сервера, время снимка quryltai.kz.

Только токен профиля kurultai (как `/v1/robot/diag`). POST: записать в БД (app/kurultai/runtime_settings.py),
лог `robot_settings_changed`, закрыть открытые live-сессии Курултая кодом 1012 `settings_changed` — приложение
переподключится и новая сессия прочитает новые настройки. PIN проверяет приложение; сервер его не знает.
"""

from __future__ import annotations

import asyncio
from datetime import UTC, datetime

from fastapi import APIRouter, Request

from app import __version__
from app.api import check_token, robot_profile
from app.client_ip import client_ip, parse_networks
from app.config import secret_text
from app.errors import ApiError
from app.kurultai.runtime_settings import ALLOWED, RuntimeSettings, validate
from app.logging_setup import logger
from app.profiles import KURULTAI

router = APIRouter(prefix="/v1")

CLOSE_CODE = 1012
CLOSE_REASON = "settings_changed"


def _runtime(request: Request) -> RuntimeSettings:
    check_token(request)
    runtime = getattr(request.app.state, "kurultai_runtime", None)
    if robot_profile(request) != KURULTAI or runtime is None:
        raise ApiError(404, "not_found", "Not Found")
    return runtime


def effective_engine(app, selected: str) -> tuple[str, dict]:
    """Какой движок получит следующая сессия и почему: выбран realtime, но предохранитель сработал, нет ключа
    OpenAI или нет голоса (гибрид text без ElevenLabs) → pipeline."""
    settings = app.state.settings
    health = getattr(app.state, "realtime_health", None)
    down = health is not None and health.is_down()
    breaker = {
        "down": down,
        "reason": health.reason if down else "",
        "retry_in_s": health.seconds_left() if down else 0,
    }
    if selected != "realtime":
        return "pipeline", breaker
    if down:
        return "pipeline", breaker
    if not secret_text(settings.kurultai_openai_api_key):
        return "pipeline", {**breaker, "reason": "no_openai_key"}
    if (
        settings.kurultai_realtime_output == "text"
        and getattr(app.state, "realtime_tts", None) is None
    ):
        return "pipeline", {**breaker, "reason": "no_voice"}
    return "realtime", breaker


async def payload(request: Request, runtime: RuntimeSettings) -> dict:
    app = request.app
    selected = runtime.live_engine
    effective, breaker = effective_engine(app, selected)
    credits_src = getattr(app.state, "kurultai_voice_credits", None)
    credits, checked_at = (await credits_src.get()) if credits_src is not None else (None, None)
    quryltai = getattr(app.state, "quryltai", None)
    updated_ms = quryltai.updated_at_ms if quryltai is not None else 0
    return {
        "engine": {"selected": selected, "effective": effective, "breaker": breaker},
        "noise_mode": runtime.noise_mode,
        "voice": {
            "provider": credits_src.provider if credits_src is not None else None,
            "credits": credits,
            "checked_at": checked_at.isoformat() if checked_at is not None else None,
        },
        "server": {"version": __version__, "time": datetime.now(UTC).isoformat()},
        "quryltai_updated_at": (
            datetime.fromtimestamp(updated_ms / 1000, UTC).isoformat() if updated_ms else None
        ),
    }


@router.get("/robot/settings", include_in_schema=False)
async def get_settings(request: Request) -> dict:
    return await payload(request, _runtime(request))


@router.post("/robot/settings", include_in_schema=False)
async def post_settings(request: Request) -> dict:
    runtime = _runtime(request)
    try:
        body = await request.json()
    except ValueError:
        raise ApiError(400, "bad_request", "Body must be JSON") from None
    if not isinstance(body, dict) or not body:
        raise ApiError(
            400, "bad_request", "Body must be an object with live_engine and/or noise_mode"
        )
    unknown = [k for k in body if k not in ALLOWED]
    if unknown:
        raise ApiError(400, "bad_request", f"Unknown setting: {', '.join(map(str, unknown))}")
    for (
        key,
        value,
    ) in body.items():  # сперва проверить всё, потом писать: половинных изменений не бывает
        try:
            validate(key, value)
        except ValueError as exc:
            raise ApiError(400, "bad_request", str(exc)) from None
    settings = request.app.state.settings
    ip = client_ip(request.scope, parse_networks(settings.trusted_proxies)) or "?"
    changed = False
    for key, value in body.items():
        try:
            old, new = await asyncio.to_thread(runtime.set, key, value, ip)
        except ValueError as exc:
            raise ApiError(400, "bad_request", str(exc)) from None
        if old != new:
            changed = True
        logger.info(
            "robot_settings_changed",
            extra={"key": key, "old": old, "new": new, "client_ip": ip, "changed": old != new},
        )
    if changed:
        await close_live_sessions(request.app)
    return await payload(request, runtime)


async def close_live_sessions(app) -> int:
    """Закрыть открытые live-сессии Курултая: следующая прочитает новые настройки."""
    registry = getattr(app.state, "live_registry", None)
    if registry is None:
        return 0
    sessions = [s for s in registry.all() if s.profile == KURULTAI]
    for s in sessions:
        try:
            await s.stop(CLOSE_REASON, CLOSE_CODE, CLOSE_REASON)
        except Exception as exc:
            logger.warning("live_close_failed", extra={"error": type(exc).__name__})
    if sessions:
        logger.info("live_sessions_closed", extra={"count": len(sessions), "reason": CLOSE_REASON})
    return len(sessions)
