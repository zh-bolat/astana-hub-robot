"""Инструмент web_search для OpenAI Realtime: короткая справка из интернета через OpenRouter (плагин web).

Тот же поиск, что у текстового агента Курултая (`app/kurultai/agent.py`, плагин web), но вызывается только когда
модель Realtime сама решила, что нужны новости или свежие факты. Ответ — 3–5 предложений фактов с датами без
ссылок: Realtime перескажет их голосом.
"""

from __future__ import annotations

from datetime import UTC, datetime

import httpx

from app.config import Settings, secret_text
from app.kurultai import prompts
from app.kurultai.agent import strip_citations
from app.kurultai.quryltai import ASTANA_TZ
from app.logging_setup import logger

TOOL = {
    "type": "function",
    "name": "web_search",
    "description": (
        "Найти в интернете новости, прошедшие события и свежие факты, которых нет в данных о Курултае. "
        "Возвращает краткую справку с датами."
    ),
    "parameters": {
        "type": "object",
        "properties": {"query": {"type": "string", "description": "Что искать, по-русски, кратко"}},
        "required": ["query"],
    },
}
MAX_CHARS = 1500


async def web_search(
    settings: Settings, query: str, client: httpx.AsyncClient | None = None
) -> str:
    """Справка по запросу; ошибка — короткий текст «поиск недоступен» (модель скажет, что данных нет)."""
    key = secret_text(settings.openrouter_api_key)
    query = (query or "").strip()[:300]
    if not key or not query:
        return "Поиск недоступен."
    now = datetime.now(UTC).astimezone(ASTANA_TZ)
    plugin: dict = {
        "id": "web",
        "max_results": settings.kurultai_web_max_results,
        "search_prompt": prompts.web_search_prompt(now),
    }
    if settings.kurultai_web_engine:
        plugin["engine"] = settings.kurultai_web_engine
    body = {
        "model": settings.resolved_kurultai_model(),
        "messages": [
            {
                "role": "system",
                "content": "Ты собираешь факты для голосового помощника. Ответь 3–5 предложениями: "
                "только проверенные факты с датами, без ссылок, адресов сайтов и рассуждений.",
            },
            {"role": "user", "content": f"Сейчас {now:%Y-%m-%d %H:%M} (Астана). Запрос: {query}"},
        ],
        "plugins": [plugin],
        "max_tokens": 900,
        "reasoning": {"effort": "low"},
    }
    own = client is None
    c = client or httpx.AsyncClient(timeout=settings.kurultai_web_timeout_s)
    try:
        r = await c.post(
            f"{settings.openrouter_base_url.rstrip('/')}/chat/completions",
            headers={"Authorization": f"Bearer {key}"},
            json=body,
        )
        r.raise_for_status()
        text = (r.json()["choices"][0]["message"].get("content") or "").strip()
    except Exception as exc:
        logger.warning("realtime_web_search_failed", extra={"error": type(exc).__name__})
        return "Поиск недоступен."
    finally:
        if own:
            await c.aclose()
    text = strip_citations(text)[:MAX_CHARS]
    return text or "Ничего не нашлось."
