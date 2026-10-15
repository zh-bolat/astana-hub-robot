"""Промпты (шаг 3.5): отвечать только по базе, коротко, на языке вопроса, без markdown.

Структура: неизменная часть (правила + база) → system_instruction (кешируемый префикс),
изменяемая часть (вопрос, место) → user.
"""

from __future__ import annotations

import json

from app.knowledge.models import KnowledgeBase, Place
from app.lang import Lang

REFUSALS: dict[str, str] = {
    "ru": "К сожалению, у меня нет ответа на этот вопрос. Пожалуйста, обратитесь на ресепшен.",
    "kk": "Өкінішке қарай, бұл сұраққа жауабым жоқ. Ресепшенге хабарласыңыз.",
    "en": "Sorry, I don't have an answer to that. Please ask at the reception desk.",
}

LOOK_REFUSALS: dict[str, str] = {
    "ru": "Не могу точно сказать, что это. Пожалуйста, уточните на ресепшене.",
    "kk": "Бұл не екенін нақты айта алмаймын. Ресепшеннен сұраңыз.",
    "en": "I can't tell exactly what this is. Please ask at the reception desk.",
}

_RULES = """Ты — голосовой робот-помощник технопарка Astana Hub. Твои ответы озвучивает синтезатор речи.

Правила:
1. Отвечай ТОЛЬКО на основе раздела «БАЗА ЗНАНИЙ» ниже. Ничего не придумывай: ни фактов, ни чисел, ни имён, ни времени.
2. Если в базе нет ответа, или вопрос не про Astana Hub, или ответ неоднозначен — верни found=false и пустой answer.
3. Ответ — 1–3 коротких предложения разговорным языком. Каждое предложение не длиннее 250 символов.
4. Отвечай на языке вопроса: ru — русский, kk — казахский, en — английский. Если язык указан в запросе — используй его. В поле lang верни код языка ответа.
5. Только обычный текст для озвучки: без markdown, списков, ссылок, URL, эмодзи и спецсимволов.
6. Если вопрос про «здесь/рядом», учитывай текущее место робота.
7. В sources перечисли id использованных записей базы (например "fact:wifi", "place:cafe").

Формат ответа — строго JSON: {"found": bool, "lang": "ru"|"kk"|"en", "answer": string, "sources": [string]}"""

_LOOK_RULES = """Ты — робот-помощник технопарка Astana Hub. Посетитель показывает тебе то, что видит камера робота, и спрашивает «что это?».
Твои ответы озвучивает синтезатор речи.

Правила:
1. Опиши, что на кадре, и если это одно из помещений из раздела «МЕСТА» — назови его и коротко расскажи о нём по описанию из базы.
2. Используй текущее место робота как сильную подсказку, но верь кадру, если он явно ему противоречит.
3. Не выдумывай факты о хабе, которых нет в базе. Не описывай внешность людей и не пытайся их узнать.
4. Если кадр непонятен (темно, размыто, нечего описать) — верни found=false и пустой answer.
5. Ответ — 1–3 коротких предложения на языке вопроса (ru/kk/en), без markdown, ссылок и эмодзи.
6. В sources укажи id использованных мест (например "place:cafe").

Формат ответа — строго JSON: {"found": bool, "lang": "ru"|"kk"|"en", "answer": string, "sources": [string]}"""


def _kb_facts_json(kb: KnowledgeBase) -> str:
    facts = [
        {
            "id": f.source_id,
            "q": {k: v for k, v in f.question.model_dump().items() if v},
            "a": {k: v for k, v in f.answer.model_dump().items() if v},
        }
        for f in kb.facts
    ]
    return json.dumps(facts, ensure_ascii=False, separators=(",", ":"))


def _kb_places_json(kb: KnowledgeBase, with_visual: bool = False) -> str:
    places = []
    for p in kb.places:
        item = {
            "id": p.source_id,
            "names": {k: v for k, v in p.names.model_dump().items() if v},
            "description": {k: v for k, v in p.description.model_dump().items() if v},
        }
        if with_visual and p.visual_hints:
            item["looks_like"] = p.visual_hints
        places.append(item)
    return json.dumps(places, ensure_ascii=False, separators=(",", ":"))


def ask_system_prompt(kb: KnowledgeBase) -> str:
    return (
        f"{_RULES}\n\n"
        f"БАЗА ЗНАНИЙ (версия {kb.version})\n"
        f"Факты:\n{_kb_facts_json(kb)}\n\n"
        f"Места:\n{_kb_places_json(kb)}"
    )


def _place_line(place: Place | None, place_id: str | None) -> str:
    if place:
        return f"Текущее место робота: {place.source_id} ({place.names.ru or place.map_name})"
    if place_id:
        return f"Текущее место робота: {place_id} (нет в базе)"
    return "Текущее место робота: неизвестно"


def ask_user_prompt(
    question: str, lang: Lang | None, place: Place | None, place_id: str | None
) -> str:
    lang_line = f"Язык ответа: {lang}" if lang else "Язык ответа: определи по вопросу"
    return f"{_place_line(place, place_id)}\n{lang_line}\nВопрос посетителя: {question}"


def look_system_prompt(kb: KnowledgeBase) -> str:
    return f"{_LOOK_RULES}\n\nМЕСТА (версия {kb.version}):\n{_kb_places_json(kb, with_visual=True)}"


def look_user_prompt(
    question: str, lang: Lang | None, place: Place | None, place_id: str | None
) -> str:
    lines = [_place_line(place, place_id)]
    if place and place.visual_hints:
        lines.append(f"Как выглядит текущее место: {place.visual_hints}")
    lines.append(f"Язык ответа: {lang}" if lang else "Язык ответа: определи по вопросу")
    lines.append(f"Вопрос посетителя: {question}")
    return "\n".join(lines)
