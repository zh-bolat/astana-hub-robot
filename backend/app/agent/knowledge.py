"""Поиск по базе знаний для тулов агента (без сети и без SDK — легко тестировать).

- `search_knowledge` — факты и мероприятия. Пока база маленькая: лексический поиск по ключевым
  словам и текстам на всех языках; нет совпадений — агенту отдаётся вся база фактов (как
  RETRIEVAL_MODE=full у /ask), и модель сама решает, есть ли ответ. Позже — гибридный RAG (kb_chunks).
- `find_place` — места по названиям, синонимам и описаниям на 3 языках. Нет уверенного совпадения —
  отдаются все места как кандидаты: модель сама сопоставляет смысл («где помыть руки» → туалет).
- `guide_consent` — было ли согласие посетителя на проводы (см. docstring).
"""

from __future__ import annotations

import re
from datetime import UTC, datetime, timedelta

from app.knowledge.models import Event, Fact, KnowledgeBase, Place
from app.lang import Lang

MAX_FACT_HITS = 4
FULL_KB_MAX_FACTS = 40
MAX_EVENTS = 5

_WORD = re.compile(r"\w+", re.UNICODE)
# Слова, которые есть почти в любом вопросе и ничего не говорят о теме
_STOP = {
    "где",
    "как",
    "что",
    "это",
    "есть",
    "тут",
    "здесь",
    "можно",
    "мне",
    "меня",
    "какой",
    "какая",
    "какие",
    "скажи",
    "подскажи",
    "пожалуйста",
    "the",
    "is",
    "are",
    "where",
    "what",
    "how",
    "can",
    "here",
    "there",
    "please",
    "and",
    "for",
    "қай",
    "қайда",
    "жерде",
    "бар",
    "ма",
    "ме",
    "не",
    "қалай",
}
_EVENT_WORDS = re.compile(
    r"мероприят|событи|ивент|митап|хакатон|конференц|сегодня|завтра|расписан|"
    r"event|meetup|hackathon|today|tomorrow|schedule|"
    r"іс-шара|іс шара|шара|бүгін|ертең|кездесу",
    re.IGNORECASE,
)


def _stem(word: str) -> str:
    return word[:5] if len(word) > 5 else word


def _stems(text: str) -> set[str]:
    return {
        _stem(w) for w in (x.lower() for x in _WORD.findall(text)) if len(w) >= 3 and w not in _STOP
    }


def _texts(*values: object) -> str:
    parts: list[str] = []
    for v in values:
        if isinstance(v, str):
            parts.append(v)
        elif isinstance(v, list | tuple):
            parts.extend(str(x) for x in v)
        elif hasattr(v, "model_dump"):
            parts.extend(x for x in v.model_dump().values() if isinstance(x, str))
    return " ".join(p for p in parts if p)


def _fact_score(fact: Fact, query: str, q_stems: set[str]) -> int:
    q_lower = query.lower()
    score = 0
    for kw in fact.keywords:
        kw = kw.lower().strip()
        if not kw:
            continue
        if " " in kw:
            score += 3 if kw in q_lower else 0
        elif _stem(kw) in q_stems or kw in q_stems:
            score += 3
    score += len(q_stems & _stems(_texts(fact.question, fact.answer, fact.tags)))
    return score


def _fact_out(fact: Fact, lang: Lang) -> dict:
    out = {"id": fact.source_id, "answer": fact.answer.get(lang)}
    q = fact.question.get(lang)
    if q:
        out["question"] = q
    return out


def _event_out(event: Event, lang: Lang) -> dict:
    out: dict = {
        "id": f"event:{event.id}",
        "title": event.title.get(lang),
        "starts_at": event.starts_at.isoformat(),
    }
    if event.ends_at:
        out["ends_at"] = event.ends_at.isoformat()
    desc = event.description.get(lang)
    if desc:
        out["description"] = desc
    if event.place_id:
        out["place_id"] = event.place_id
    return out


def search_knowledge(
    kb: KnowledgeBase, query: str, lang: Lang, now: datetime | None = None
) -> dict:
    now = now or datetime.now(UTC)
    q_stems = _stems(query)
    scored = sorted(
        ((s, i, f) for i, f in enumerate(kb.facts) if (s := _fact_score(f, query, q_stems)) > 0),
        key=lambda x: (-x[0], x[1]),
    )
    result: dict = {}
    if scored:
        result["facts"] = [_fact_out(f, lang) for _, _, f in scored[:MAX_FACT_HITS]]
    else:
        result["facts"] = [_fact_out(f, lang) for f in kb.facts[:FULL_KB_MAX_FACTS]]
        result["note"] = (
            "Точного совпадения нет — это вся база фактов. Если ответа здесь нет, "
            "скажи, что не знаешь (метка [unknown])."
        )
    soon = [e for e in kb.events if (e.ends_at or e.starts_at) >= now - timedelta(hours=1)]
    soon.sort(key=lambda e: e.starts_at)
    matched = [e for e in soon if q_stems & _stems(_texts(e.title, e.description))]
    if matched or _EVENT_WORDS.search(query):
        events = (matched or soon)[:MAX_EVENTS]
        result["events"] = [_event_out(e, lang) for e in events]
        if not events:
            result["events_note"] = "Ближайших мероприятий в базе нет."
    return result


def _place_out(place: Place, lang: Lang, brief: bool = False) -> dict:
    out: dict = {"place_id": place.id, "map_name": place.map_name, "name": place.names.get(lang)}
    if not brief:
        desc = place.description.get(lang)
        if desc:
            out["description"] = desc
        if place.floor is not None:
            out["floor"] = place.floor
    return out


def _place_score(place: Place, query: str, q_stems: set[str]) -> int:
    q_lower = query.lower().strip()
    names = [place.id, place.map_name, *place.names.model_dump().values(), *place.synonyms]
    names = [n.lower().strip() for n in names if n and n.strip()]
    score = 0
    for n in names:
        if n == q_lower:
            score = max(score, 10)
        elif len(n) >= 3 and re.search(rf"(?<!\w){re.escape(n)}", q_lower):
            score = max(score, 6)
    name_stems = _stems(" ".join(names))
    score += 3 * len(q_stems & name_stems)
    score += len(q_stems & _stems(_texts(place.description)))
    return score


def find_place(kb: KnowledgeBase, query: str, lang: Lang) -> dict:
    q_stems = _stems(query)
    scored = sorted(
        ((s, i, p) for i, p in enumerate(kb.places) if (s := _place_score(p, query, q_stems)) > 0),
        key=lambda x: (-x[0], x[1]),
    )
    if scored and scored[0][0] >= 3:
        best = scored[0][0]
        hits = [p for s, _, p in scored if s >= max(3, best // 2)][:3]
        return {"match": "found", "places": [_place_out(p, lang) for p in hits]}
    return {
        "match": "none",
        "note": (
            "Прямого совпадения нет. Вот все места: выбери подходящее по смыслу; "
            "если подходящего нет — скажи, что такого места не знаешь."
        ),
        "places": [_place_out(p, lang, brief=True) for p in kb.places],
    }


# ---------- Согласие на проводы ----------

# Посетитель сам просит проводить/отвести
_GUIDE_REQUEST = re.compile(
    r"провод|провож|провед|сопровож|отвед|отвест|довед|довест|сопровод|покажи\s+дорог|покажите\s+дорог|"
    r"веди\s+меня|ведите\s+меня|отвези|"
    r"\btake\s+me\b|\bguide\s+me\b|\bwalk\s+me\b|\blead\s+me\b|\bescort\b|show\s+me\s+the\s+way|"
    r"апар|шығарып\s+сал|бастап\s+бар|алып\s+бар|жеткіз|ертіп",
    re.IGNORECASE,
)
# Робот в прошлой реплике предложил проводить
_GUIDE_OFFER = re.compile(
    r"провод|провож|провест|провед|довед|сопровож|отвест|отвед|довест|сопровод|"
    r"take\s+you|walk\s+you|guide\s+you|show\s+you\s+the\s+way|lead\s+you|escort|"
    r"апар|шығарып\s+сал|бастап\s+бар|алып\s+бар|жеткіз|ертіп",
    re.IGNORECASE,
)
_AFFIRM = re.compile(
    r"^\W*(да|давай|давайте|конечно|ага|угу|хочу|пожалуйста|можно|ок|окей|пойд[её]м|пошли|"
    r"веди|ведите|хорошо|согласен|согласна|yes|yeah|yep|yup|sure|ok|okay|please|let'?s\s+go|"
    r"go\s+ahead|of\s+course|иә|ия|йә|әрине|жарайды|болады|келістік|мақұл|рахмет|рақмет)\b",
    re.IGNORECASE,
)
_NEGATE = re.compile(
    r"\b(нет|не\s+надо|не\s+нужно|не\s+стоит|no|nope|don'?t|not\s+now|жоқ|керек\s+емес|қажет\s+емес)\b",
    re.IGNORECASE,
)


def guide_consent(visitor_text: str, prev_assistant: str | None) -> bool:
    """Согласие на guide_to: посетитель сам попросил проводить в этой реплике, либо робот в
    прошлой реплике предложил проводить, а посетитель ответил согласием («да», «иә», «yes»…).
    Отказ («нет», «не надо», «жоқ») — не согласие."""
    text = (visitor_text or "").strip()
    if not text or _NEGATE.search(text):
        return False
    if _GUIDE_REQUEST.search(text):
        return True
    return bool(prev_assistant and _GUIDE_OFFER.search(prev_assistant) and _AFFIRM.search(text))
