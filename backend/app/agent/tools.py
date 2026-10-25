"""Инструменты агента. Это не код на сервере, а действия робота: сервер только проверяет место
и отдаёт действие клиенту событием `action` (docs/api-contract.md, /v1/chat). Ехать, показывать стрелку,
снимать кадр — делает приложение.

Исключение — search_kb: поиск по базе на сервере (app/knowledge/search.py), регистрируется только при
RETRIEVAL_MODE=hybrid. Найденное уходит модели (до 3 фрагментов, ≤ ~2500 символов) и в ответ клиенту —
`sources` и `citations` (docs/api-contract.md, «Источники и поиск по документам»).
"""

from __future__ import annotations

import json
import time
from dataclasses import dataclass, field

from agents import FunctionTool, RunContextWrapper, function_tool

from app.agent.fastpath import direction_phrase
from app.knowledge.models import KnowledgeBase
from app.knowledge.search import KbSearch, SearchHit, excerpt
from app.lang import Lang
from app.logging_setup import logger

# После этих инструментов агент не говорит: фразу подставляет сервер
STOP_TOOLS = ["look", "no_answer"]
SEARCH_RESULTS = 3
SEARCH_BUDGET_CHARS = 2500  # текст всех фрагментов в ответе инструмента
SEARCH_MAX_CALLS = 2  # повторный поиск в том же ходе — один раз (защита от циклов)


@dataclass
class ChatContext:
    kb: KnowledgeBase
    lang: Lang
    actions: list[dict] = field(default_factory=list)
    sources: list[str] = field(default_factory=list)
    # (имя, аргументы, результат) — в память разговора (таблица messages)
    calls: list[tuple[str, dict, str]] = field(default_factory=list)
    stopped_by: str | None = None
    # WS /v1/live: фразы «где X» от позиции робота (state.directions), place_id → {ru|kk|en: фраза}
    directions: dict[str, dict[str, str]] = field(default_factory=dict)
    # search_kb: поиск (None — инструмента нет), найденное в этом ходе
    search: KbSearch | None = None
    searches: int = 0
    search_ms: int = 0
    citations: list[dict] = field(default_factory=list)
    found_texts: list[str] = field(
        default_factory=list
    )  # отданное модели — в корпус выходного фильтра

    def add_citation(self, hit: SearchHit) -> None:
        if hit.id not in self.sources:
            self.sources.append(hit.id)
        if all(c["id"] != hit.id for c in self.citations):
            self.citations.append(
                {"id": hit.id, "type": hit.type, "title": hit.title, "url": hit.url}
            )


_NEXT = {
    "where_is": "на экране робота появится стрелка к месту",
    "guide_to": "робот поедет, когда ты договоришь; вы ещё не пришли — скажи, что провожаешь и куда",
}


def _result(ctx: ChatContext, name: str, args: dict, result: dict) -> str:
    text = json.dumps(result, ensure_ascii=False)
    ctx.calls.append((name, args, text))
    return text


def _place_action(ctx: ChatContext, kind: str, place_id: str) -> str:
    # Модель может передать "lift", «лифт» или "elevator" вместо "place:lift" — ищем по именам и синонимам
    place = ctx.kb.find_place(place_id)
    args = {"place_id": place_id}
    if place is None:
        known = [
            {"id": p.source_id, "aliases": [p.map_name, *p.synonyms[:4]]} for p in ctx.kb.places
        ]
        return _result(ctx, kind, args, {"ok": False, "error": "unknown place", "places": known})
    ctx.actions.append({"type": kind, "place_id": place.id})
    if place.source_id not in ctx.sources:
        ctx.sources.append(place.source_id)
    result = {
        "ok": True,
        "place_id": place.source_id,
        "name": place.names.get(ctx.lang),
        "description": place.description.get(ctx.lang),
        "next": _NEXT[kind],
    }
    if kind == "where_is":
        # направление и метры от посетителя — измерил робот по карте (live), на языке ответа
        direction = direction_phrase(ctx.directions, place, ctx.lang)
        if direction:
            result["direction"] = direction
    return _result(ctx, kind, args, result)


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


def search_texts(hits: list[SearchHit], query: str, budget: int = SEARCH_BUDGET_CHARS) -> list[str]:
    """Текст каждого фрагмента для модели: всё вместе ≤ budget, поровну (первый по RRF не обязательно
    содержит ответ); короткий фрагмент отдаёт недобор следующим. Длинный — выдержка по словам вопроса."""
    out: list[str] = []
    left = budget
    for i, hit in enumerate(hits):
        part = excerpt(hit.content, query, max(left // (len(hits) - i), 200))
        out.append(part)
        left = max(0, left - len(part))
    return out


@function_tool
async def search_kb(wrapper: RunContextWrapper[ChatContext], query: str) -> str:
    """Найти подробности о хабе: программы, правила, цены, льготы, резидентство, визы, пространства,
    мероприятия. Возвращает до трёх фрагментов базы знаний.

    Args:
        query: суть вопроса по-русски, например "льготы для резидентов".
    """
    ctx = wrapper.context
    args = {"query": query}
    if ctx.search is None:
        return _result(ctx, "search_kb", args, {"ok": False, "error": "search unavailable"})
    if ctx.searches >= SEARCH_MAX_CALLS:
        return _result(
            ctx,
            "search_kb",
            args,
            {"ok": False, "error": "search limit reached, answer from what was found"},
        )
    ctx.searches += 1
    started = time.perf_counter()
    try:
        hits = await ctx.search.search(query, ctx.lang, k=SEARCH_RESULTS)
    except Exception as exc:  # БД недоступна
        logger.warning("kb_search_failed", extra={"error": f"{type(exc).__name__}: {exc}"[:300]})
        return _result(ctx, "search_kb", args, {"ok": False, "error": "search unavailable"})
    finally:
        ctx.search_ms += int((time.perf_counter() - started) * 1000)
    results = []
    for hit, part in zip(hits, search_texts(hits, query), strict=True):
        ctx.add_citation(hit)
        ctx.found_texts.append(part)
        results.append({"id": hit.id, "title": hit.title, "text": part})
    if not results:
        return _result(ctx, "search_kb", args, {"ok": True, "results": [], "note": "nothing found"})
    return _result(ctx, "search_kb", args, {"ok": True, "results": results})


TOOLS: list[FunctionTool] = [where_is, guide_to, look, no_answer]
SEARCH_TOOLS: list[FunctionTool] = [*TOOLS, search_kb]
