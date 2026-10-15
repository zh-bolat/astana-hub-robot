"""Структурные JSON-логи в stdout + request_id через contextvar.

В логи пишется только текст. Изображения (/look) в логи не попадают никогда.
"""

from __future__ import annotations

import json
import logging
import sys
import uuid
from contextvars import ContextVar
from datetime import UTC, datetime

_request_id: ContextVar[str | None] = ContextVar("request_id", default=None)

logger = logging.getLogger("hub")

_STD_ATTRS = set(vars(logging.makeLogRecord({})).keys()) | {"message", "asctime"}


def new_request_id() -> str:
    return "req_" + uuid.uuid4().hex[:24]


def set_request_id(value: str | None):
    return _request_id.set(value)


def reset_request_id(token) -> None:
    _request_id.reset(token)


def get_request_id() -> str | None:
    return _request_id.get()


class JsonFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        payload: dict = {
            "ts": datetime.fromtimestamp(record.created, UTC).isoformat(timespec="milliseconds"),
            "level": record.levelname.lower(),
            "logger": record.name,
            "event": record.getMessage(),
        }
        rid = get_request_id()
        if rid:
            payload["request_id"] = rid
        for key, value in record.__dict__.items():
            if key not in _STD_ATTRS and not key.startswith("_"):
                payload[key] = value
        if record.exc_info:
            payload["exc"] = self.formatException(record.exc_info)
        return json.dumps(payload, ensure_ascii=False, default=str)


def configure_logging(level: str = "INFO") -> None:
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(JsonFormatter())
    root = logging.getLogger()
    root.handlers = [handler]
    root.setLevel(level.upper())
    # uvicorn access-лог дублирует наш middleware-лог
    logging.getLogger("uvicorn.access").disabled = True
    for name in ("uvicorn", "uvicorn.error"):
        lg = logging.getLogger(name)
        lg.handlers = []
        lg.propagate = True
    # SDK-шные логи могут содержать тела запросов — приглушаем
    for name in ("httpx", "httpcore", "google_genai", "openai"):
        logging.getLogger(name).setLevel(logging.WARNING)
