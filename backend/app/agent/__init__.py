"""Агентский чат /v1/chat (docs/decisions/0001-agent-chat-stack.md).

Трассировка OpenAI Agents SDK выключается здесь, при первом импорте пакета, — до создания любого агента:
иначе тексты посетителей ушли бы в OpenAI. create_app выключает её ещё раз (ensure_tracing_disabled).
"""

from __future__ import annotations

import os

os.environ["OPENAI_AGENTS_DISABLE_TRACING"] = "1"
# Тексты реплик и ответы тулов не попадают в debug-логи SDK
os.environ.setdefault("OPENAI_AGENTS_DONT_LOG_MODEL_DATA", "1")
os.environ.setdefault("OPENAI_AGENTS_DONT_LOG_TOOL_DATA", "1")

from agents import set_tracing_disabled

set_tracing_disabled(True)


def ensure_tracing_disabled() -> None:
    """Повторно (на старте приложения): никакие настройки не включают трассировку обратно."""
    os.environ["OPENAI_AGENTS_DISABLE_TRACING"] = "1"
    set_tracing_disabled(True)
