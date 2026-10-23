"""Агентский чат /v1/chat (ADR docs/decisions/0001-agent-chat-stack.md).

OpenAI Agents SDK + модель через LiteLLM. Трассировка SDK выключается здесь, при первом импорте
пакета, — до создания любого агента: иначе тексты посетителей ушли бы в OpenAI.
"""

from __future__ import annotations

import os

os.environ["OPENAI_AGENTS_DISABLE_TRACING"] = "1"
# LiteLLM при импорте качает таблицу цен моделей с GitHub — не нужно, берём локальную копию
os.environ.setdefault("LITELLM_LOCAL_MODEL_COST_MAP", "True")
# Тексты реплик и ответы тулов не попадают в debug-логи SDK
os.environ.setdefault("OPENAI_AGENTS_DONT_LOG_MODEL_DATA", "1")
os.environ.setdefault("OPENAI_AGENTS_DONT_LOG_TOOL_DATA", "1")

from agents import set_tracing_disabled

set_tracing_disabled(True)


def ensure_tracing_disabled() -> None:
    """Повторно (на старте приложения): никакие настройки не включают трассировку обратно."""
    os.environ["OPENAI_AGENTS_DISABLE_TRACING"] = "1"
    set_tracing_disabled(True)
