"""Диагностика робота Курултая: робот раз в 20 с шлёт счётчики (камера, лица, команды головы, ответ RobotOS) →
журнал `robot_diag`. ADB на роботе закрыт — иначе не увидеть, почему, например, не двигается голова.

Только токен профиля kurultai (иначе 404). Принимаются только простые значения (строки до 300 знаков, числа,
true/false) и не больше 40 полей; тексты разговоров робот сюда не шлёт. В базу не пишется — только в журнал.
"""

from __future__ import annotations

from fastapi import APIRouter, Request
from fastapi.responses import Response

from app.api import check_token, robot_profile
from app.errors import ApiError
from app.logging_setup import logger
from app.profiles import KURULTAI

router = APIRouter(prefix="/v1")

MAX_FIELDS = 40
MAX_STR = 300


def clean(payload: object) -> dict:
    """Плоский словарь простых значений; остальное отбрасывается."""
    if not isinstance(payload, dict):
        return {}
    out: dict = {}
    for key, value in list(payload.items())[:MAX_FIELDS]:
        if not isinstance(key, str) or len(key) > 40:
            continue
        if isinstance(value, bool) or value is None or isinstance(value, int | float):
            out[key] = value
        elif isinstance(value, str):
            out[key] = value[:MAX_STR]
    return out


@router.post("/robot/diag", status_code=204, include_in_schema=False)
async def robot_diag(request: Request) -> Response:
    check_token(request)
    if robot_profile(request) != KURULTAI:
        raise ApiError(404, "not_found", "Not Found")
    try:
        payload = await request.json()
    except Exception as exc:
        raise ApiError(400, "bad_request", "JSON expected") from exc
    logger.info("robot_diag", extra={"diag": clean(payload)})
    return Response(status_code=204)
