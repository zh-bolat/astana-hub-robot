"""Детерминированный провайдер без сети: для локальной разработки, тестов и CI без ключей.

Ищет факт по пересечению ключевых слов с вопросом и возвращает его готовый ответ.
Не выдумывает: нет совпадений → found=false.
"""

from __future__ import annotations

import json
import re
from collections.abc import AsyncIterator

from app.knowledge.models import Fact, KnowledgeBase
from app.lang import detect_lang, normalize_lang
from app.llm.base import LLMAnswer, LLMError, LLMRequest

_WORD = re.compile(r"[\w-]+", re.UNICODE)


def _stem(word: str) -> str:
    return word[:5] if len(word) > 5 else word


def _tokens(text: str) -> set[str]:
    return {w.lower() for w in _WORD.findall(text) if len(w) >= 2}


def _score(fact: Fact, question: str) -> int:
    q_lower = question.lower()
    q_tokens = _tokens(question)
    score = 0
    for kw in fact.keywords:
        kw = kw.lower().strip()
        if not kw:
            continue
        if " " in kw:  # фраза — ищем подстрокой
            if kw in q_lower:
                score += 2
            continue
        stem = _stem(kw)
        if any(t == kw or (len(stem) >= 4 and t.startswith(stem)) for t in q_tokens):
            score += 1
    return score


def best_fact(kb: KnowledgeBase, question: str) -> Fact | None:
    scored = [(_score(f, question), i, f) for i, f in enumerate(kb.facts)]
    scored = [s for s in scored if s[0] > 0]
    if not scored:
        return None
    scored.sort(key=lambda s: (-s[0], s[1]))
    return scored[0][2]


class FakeProvider:
    name = "fake"
    model = "fake"

    def __init__(self, fail: bool = False, chunk_size: int = 7) -> None:
        self.fail = fail
        self.chunk_size = chunk_size

    async def answer(self, req: LLMRequest) -> LLMAnswer:
        if self.fail:
            raise LLMError("fake provider configured to fail")
        lang = normalize_lang(req.lang) or detect_lang(req.question)
        kb = req.kb or KnowledgeBase()
        fact = best_fact(kb, req.question)
        if fact is None:
            return LLMAnswer(found=False, answer="", lang=lang, sources=[])
        return LLMAnswer(
            found=True, answer=fact.answer.get(lang), lang=lang, sources=[fact.source_id]
        )

    async def answer_stream(self, req: LLMRequest) -> AsyncIterator[str]:
        """Тот же ответ, но JSON-ом по кускам — как стрим реальной модели."""
        res = await self.answer(req)
        payload = json.dumps(
            {"found": res.found, "lang": res.lang, "answer": res.answer, "sources": res.sources},
            ensure_ascii=False,
        )
        for i in range(0, len(payload), self.chunk_size):
            yield payload[i : i + self.chunk_size]

    async def describe_image(self, req: LLMRequest) -> LLMAnswer:
        if self.fail:
            raise LLMError("fake provider configured to fail")
        lang = normalize_lang(req.lang) or detect_lang(req.question)
        kb = req.kb or KnowledgeBase()
        place = kb.place(req.place_id)
        if place is None:
            return LLMAnswer(found=False, answer="", lang=lang, sources=[])
        intro = {"ru": "Похоже, это", "kk": "Бұл", "en": "This looks like"}[lang]
        name = place.names.get(lang)
        text = f"{intro} {name[:1].lower() + name[1:] if lang != 'kk' else name}. {place.description.get(lang)}"
        return LLMAnswer(found=True, answer=text, lang=lang, sources=[place.source_id])
