"""Тулы агента (`@function_tool`). Только наши функции: никаких shell/файлов/браузера.

- search_knowledge, find_place — выполняются на сервере по текущей базе знаний;
- where_is, guide_to, stop — НЕ выполняются на сервере: проверяют аргументы и откладывают
  SSE-событие `action` (робот выполняет его по своей карте после фраз ответа);
- look — сразу отдаёт роботу `action look`, ждёт кадр (POST /v1/chat/{turn_id}/frame) и
  возвращает агенту описание кадра от vision-модели.

Не больше `max_tool_calls` вызовов за ход: после лимита тулы скрываются от модели (is_enabled),
а лишний вызов, если всё же пришёл, получает ответ «лимит исчерпан».
"""

from __future__ import annotations

import json
from typing import Any

from agents import FunctionTool, RunContextWrapper, function_tool

from app.agent import knowledge
from app.agent.context import TurnContext

MAX_TOOL_RESULT_LOG = 500


def _tools_left(wrapper: RunContextWrapper[TurnContext], _agent: Any) -> bool:
    return wrapper.context.tools_left()


def _begin(ctx: TurnContext, name: str, args: dict) -> str | None:
    """None — можно выполнять; иначе ответ модели «лимит исчерпан»."""
    if not ctx.tools_left():
        result = {
            "error": "tool_limit",
            "note": "Лимит инструментов на этот ход исчерпан. Ответь посетителю без них.",
        }
        ctx.tool_log.append({"name": name, "args": args, "result": "tool_limit"})
        return json.dumps(result, ensure_ascii=False)
    ctx.tool_calls += 1
    ctx.tools_used.append(name)
    return None


def _result(ctx: TurnContext, name: str, args: dict, result: dict | str) -> str:
    text = result if isinstance(result, str) else json.dumps(result, ensure_ascii=False)
    ctx.tool_log.append({"name": name, "args": args, "result": text[:MAX_TOOL_RESULT_LOG]})
    return text


def _add_action(ctx: TurnContext, name: str, args: dict) -> None:
    action = {"name": name, "args": args}
    if action not in ctx.actions:
        ctx.actions.append(action)


def _unknown_place(place_id: str) -> dict:
    return {
        "ok": False,
        "error": f"Места с place_id {place_id!r} нет. place_id бери только из ответа find_place.",
    }


@function_tool(is_enabled=_tools_left)
async def search_knowledge(wrapper: RunContextWrapper[TurnContext], query: str) -> str:
    """Найти факты об Astana Hub в базе знаний: часы работы, Wi-Fi, правила, услуги, программы,
    мероприятия. Любые факты о хабе бери только отсюда.

    Args:
        query: Короткий поисковый запрос по смыслу вопроса посетителя.
    """
    ctx = wrapper.context
    args = {"query": query}
    if (limit := _begin(ctx, "search_knowledge", args)) is not None:
        return limit
    ctx.knowledge_used = True
    result = knowledge.search_knowledge(ctx.kb, query, ctx.lang)
    for item in [*result.get("facts", []), *result.get("events", [])]:
        if "note" not in result:  # вся база целиком — это не источник ответа
            ctx.add_source(item["id"])
    return _result(ctx, "search_knowledge", args, result)


@function_tool(is_enabled=_tools_left)
async def find_place(wrapper: RunContextWrapper[TurnContext], query: str) -> str:
    """Найти место в хабе (точку карты робота) по названию или по смыслу: «кафе», «туалет»,
    «переговорная», «где помыть руки» → туалет. Возвращает place_id и map_name или кандидатов.

    Args:
        query: Что ищет посетитель: название места или его назначение, коротко.
    """
    ctx = wrapper.context
    args = {"query": query}
    if (limit := _begin(ctx, "find_place", args)) is not None:
        return limit
    ctx.knowledge_used = True
    return _result(ctx, "find_place", args, knowledge.find_place(ctx.kb, query, ctx.lang))


@function_tool(is_enabled=_tools_left)
async def where_is(wrapper: RunContextWrapper[TurnContext], place_id: str) -> str:
    """Показать посетителю, где место: робот сам озвучит направление и расстояние по своей карте.

    Args:
        place_id: place_id из ответа find_place.
    """
    ctx = wrapper.context
    args = {"place_id": place_id}
    if (limit := _begin(ctx, "where_is", args)) is not None:
        return limit
    place = ctx.kb.place(place_id)
    if place is None:
        return _result(ctx, "where_is", args, _unknown_place(place_id))
    _add_action(ctx, "where_is", {"place_id": place.id, "map_name": place.map_name})
    ctx.add_source(place.source_id)
    return _result(
        ctx,
        "where_is",
        args,
        {
            "ok": True,
            "place": place.names.get(ctx.lang),
            "note": (
                "После твоей фразы робот сам скажет направление и расстояние. Сам направление "
                "не называй: одной фразой скажи о месте и спроси, проводить ли туда."
            ),
        },
    )


@function_tool(is_enabled=_tools_left)
async def guide_to(wrapper: RunContextWrapper[TurnContext], place_id: str) -> str:
    """Проводить посетителя до места (робот поедет). Только после явного согласия посетителя:
    он сам попросил проводить или ответил «да» на твоё предложение проводить.

    Args:
        place_id: place_id из ответа find_place.
    """
    ctx = wrapper.context
    args = {"place_id": place_id}
    if (limit := _begin(ctx, "guide_to", args)) is not None:
        return limit
    place = ctx.kb.place(place_id)
    if place is None:
        return _result(ctx, "guide_to", args, _unknown_place(place_id))
    name = place.names.get(ctx.lang)
    if not knowledge.guide_consent(ctx.text, ctx.prev_assistant):
        return _result(
            ctx,
            "guide_to",
            args,
            {
                "ok": False,
                "error": "no_consent",
                "note": (
                    "Робот НЕ поедет: посетитель не соглашался на проводы. Не говори, что ведёшь; "
                    f"спроси, проводить ли его до «{name}»."
                ),
            },
        )
    # Проводы заменяют «показать, где» — роботу одно действие навигации
    ctx.actions[:] = [a for a in ctx.actions if a["name"] != "where_is"]
    _add_action(ctx, "guide_to", {"place_id": place.id, "map_name": place.map_name})
    ctx.add_source(place.source_id)
    return _result(
        ctx,
        "guide_to",
        args,
        {
            "ok": True,
            "place": name,
            "note": "Робот поедет после твоей фразы. Скажи коротко, что ведёшь.",
        },
    )


@function_tool(is_enabled=_tools_left)
async def stop(wrapper: RunContextWrapper[TurnContext]) -> str:
    """Остановить движение робота (посетитель просит остановиться или подождать во время проводов)."""
    ctx = wrapper.context
    if (limit := _begin(ctx, "stop", {})) is not None:
        return limit
    ctx.actions[:] = [a for a in ctx.actions if a["name"] not in ("where_is", "guide_to")]
    _add_action(ctx, "stop", {})
    return _result(ctx, "stop", {}, {"ok": True})


@function_tool(is_enabled=_tools_left)
async def look(wrapper: RunContextWrapper[TurnContext]) -> str:
    """Посмотреть камерой робота: «что это?», «что ты видишь?», «где я?», «что передо мной?».
    Возвращает описание кадра."""
    ctx = wrapper.context
    if (limit := _begin(ctx, "look", {})) is not None:
        return limit
    if ctx.looked:
        return _result(ctx, "look", {}, {"ok": False, "error": "Кадр в этом ходе уже был."})
    ctx.looked = True
    description = await ctx.look(ctx, None)
    return _result(ctx, "look", {}, description)


ALL_TOOLS: list[FunctionTool] = [search_knowledge, find_place, where_is, guide_to, stop, look]
ROBOT_ACTIONS = ("where_is", "guide_to", "stop", "look")
