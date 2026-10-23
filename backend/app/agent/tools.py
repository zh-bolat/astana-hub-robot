"""Инструменты агента. Это не код на сервере, а действия робота: сервер только проверяет место
и отдаёт действие клиенту событием `action` (docs/api-contract.md, /v1/chat). Ехать, показывать стрелку,
снимать кадр — делает приложение.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field

from agents import FunctionTool, RunContextWrapper, function_tool

from app.knowledge.models import KnowledgeBase
from app.lang import Lang

# После этих инструментов агент не говорит: фразу подставляет сервер
STOP_TOOLS = ["look", "no_answer"]


@dataclass
class ChatContext:
    kb: KnowledgeBase
    lang: Lang
    actions: list[dict] = field(default_factory=list)
    sources: list[str] = field(default_factory=list)
    # (имя, аргументы, результат) — в память разговора (таблица messages)
    calls: list[tuple[str, dict, str]] = field(default_factory=list)
    stopped_by: str | None = None


_NEXT = {
    "where_is": "под ответом на экране появится кнопка «Проводить»; стрелки и карты нет",
    "guide_to": "робот поедет, когда ты договоришь; вы ещё не пришли — скажи, что провожаешь и куда",
}


def _result(ctx: ChatContext, name: str, args: dict, result: dict) -> str:
    text = json.dumps(result, ensure_ascii=False)
    ctx.calls.append((name, args, text))
    return text


def _place_action(ctx: ChatContext, kind: str, place_id: str) -> str:
    pid = place_id.strip().removeprefix("place:")
    place = ctx.kb.place(pid)
    args = {"place_id": place_id}
    if place is None:
        known = [p.source_id for p in ctx.kb.places]
        return _result(ctx, kind, args, {"ok": False, "error": "unknown place", "places": known})
    ctx.actions.append({"type": kind, "place_id": place.id})
    if place.source_id not in ctx.sources:
        ctx.sources.append(place.source_id)
    return _result(
        ctx,
        kind,
        args,
        {
            "ok": True,
            "place_id": place.source_id,
            "name": place.names.get(ctx.lang),
            "description": place.description.get(ctx.lang),
            "next": _NEXT[kind],
        },
    )


@function_tool
def where_is(wrapper: RunContextWrapper[ChatContext], place_id: str) -> str:
    """Показать посетителю на экране робота, где находится место.

    Args:
        place_id: id места из раздела «Места», например "place:cafe".
    """
    return _place_action(wrapper.context, "where_is", place_id)


@function_tool
def guide_to(wrapper: RunContextWrapper[ChatContext], place_id: str) -> str:
    """Проводить посетителя: робот поедет к месту, посетитель идёт за ним.

    Args:
        place_id: id места из раздела «Места», например "place:cafe".
    """
    return _place_action(wrapper.context, "guide_to", place_id)


@function_tool
def look(wrapper: RunContextWrapper[ChatContext]) -> str:
    """Посмотреть камерой робота и рассказать, что перед ним (робот снимет кадр сам)."""
    ctx = wrapper.context
    ctx.actions.append({"type": "look", "place_id": None})
    ctx.stopped_by = "look"
    return _result(ctx, "look", {}, {"ok": True})


@function_tool
def no_answer(wrapper: RunContextWrapper[ChatContext]) -> str:
    """Ответа на вопрос нет (в базе знаний нет данных или вопрос неприемлемый)."""
    ctx = wrapper.context
    ctx.stopped_by = "no_answer"
    return _result(ctx, "no_answer", {}, {"ok": True})


TOOLS: list[FunctionTool] = [where_is, guide_to, look, no_answer]
