"""Sentry (ошибки сервера). Подключается, только если задан SENTRY_DSN.

Без ПДн: send_default_pii=False (нет IP, cookies, заголовков авторизации), тела запросов не
отправляются, локальные переменные стека не отправляются, а тексты вопросов/ответов/транскриптов
вырезаются из событий и breadcrumbs (`scrub`). Трейсинг выключен.
"""

from __future__ import annotations

from typing import Any

from app import __version__
from app.config import Settings
from app.logging_setup import logger

FILTERED = "[Filtered]"
# Поля, в которых может оказаться речь посетителя или ответ робота
SENSITIVE_KEYS = frozenset(
    {
        "question",
        "answer",
        "text",
        "phrases",
        "transcript",
        "prompt",
        "system",
        "user",
        "body",
        "meta",
        "raw",
        "image",
        "audio",
        "authorization",
        "cookie",
        "x-forwarded-for",
        "x-real-ip",
    }
)


def scrub(value: Any, depth: int = 0) -> Any:
    """Рекурсивно заменяет значения чувствительных ключей на [Filtered]."""
    if depth > 12:
        return value
    if isinstance(value, dict):
        return {
            k: FILTERED
            if isinstance(k, str) and k.lower() in SENSITIVE_KEYS
            else scrub(v, depth + 1)
            for k, v in value.items()
        }
    if isinstance(value, list | tuple):
        return [scrub(v, depth + 1) for v in value]
    return value


def before_send(event: dict, hint: dict | None = None) -> dict:
    request = event.get("request")
    if isinstance(request, dict):
        for key in ("data", "cookies", "query_string", "env"):
            request.pop(key, None)
        if isinstance(request.get("headers"), dict):
            request["headers"] = scrub(request["headers"])
    for key in ("extra", "contexts", "tags"):
        if key in event:
            event[key] = scrub(event[key])
    logentry = event.get("logentry")
    if isinstance(logentry, dict) and "params" in logentry:
        logentry["params"] = FILTERED
    crumbs = event.get("breadcrumbs")
    if isinstance(crumbs, dict) and isinstance(crumbs.get("values"), list):
        crumbs["values"] = [before_breadcrumb(c) for c in crumbs["values"]]
    event.pop("user", None)
    return event


def before_breadcrumb(crumb: dict, hint: dict | None = None) -> dict:
    if isinstance(crumb, dict) and "data" in crumb:
        crumb["data"] = scrub(crumb["data"])
    return crumb


def init_sentry(settings: Settings) -> bool:
    if not settings.sentry_dsn:
        return False
    import sentry_sdk

    sentry_sdk.init(
        dsn=settings.sentry_dsn.get_secret_value(),
        environment=settings.sentry_environment,
        release=f"astana-hub-backend@{__version__}",
        send_default_pii=False,
        include_local_variables=False,
        max_request_body_size="never",
        traces_sample_rate=0.0,
        before_send=before_send,
        before_breadcrumb=before_breadcrumb,
    )
    logger.info("sentry_enabled", extra={"environment": settings.sentry_environment})
    return True
