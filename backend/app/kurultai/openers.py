"""Вступления в начале ответа робота Курултая (решение владельца 2026-11-07).

Робот здоровается, только если посетитель поздоровался сам; реакции («Хороший вопрос!», «Конечно,») — никогда.
Модель иногда начинает с приветствия или реакции вопреки промпту — поэтому фильтр.
Режется только начало первой фразы ответа; остаток с заглавной буквы. Фраза из одного вступления — выбрасывается.
"""

from __future__ import annotations

import re

_GREETING = (
    r"здравствуйте|здравствуй|добрый\s+(?:день|вечер)|доброе\s+утро|привет(?:ствую)?|"
    r"сәлеметсіз\s*бе|сәлеметсіздер\s*ме|сәлем|салем|салам|қайырлы\s+(?:күн|кеш|таң)|"
    r"hello|hi|good\s+(?:morning|afternoon|evening)"
)
_FILLER = (
    r"(?:очень\s+)?(?:хороший|отличный|интересный|прекрасный|замечательный)\s+вопрос|"
    r"(?:о,?\s+)?это\s+(?:очень\s+)?интересно|"
    r"жақсы\s+сұрақ|қызық\s+сұрақ|great\s+question|good\s+question|"
    r"конечно|разумеется|давайте\s+разбер[её]мся|итак"
)
_TAIL = r")(?=[\s!.,:;—–-]|$)[\s!.,:;—–-]*"
_ANY_OPENER = re.compile(r"^\s*(?:" + _GREETING + "|" + _FILLER + _TAIL, re.IGNORECASE)
_FILLER_OPENER = re.compile(r"^\s*(?:" + _FILLER + _TAIL, re.IGNORECASE)
# Посетитель поздоровался: приветствие в любом месте реплики («Акылтай, привет», «Сәлем, Ақылтай»)
_VISITOR_GREETING = re.compile(
    r"(?<!\w)(?:" + _GREETING + r"|салем|ассалаумағалейкум|салам\s*алейкум)(?!\w)", re.IGNORECASE
)


def visitor_greeted(question: str | None) -> bool:
    return bool(_VISITOR_GREETING.search(question or ""))


def strip_opener(phrase: str, question: str | None = None) -> str:
    """Убрать вступления в начале фразы (подряд несколько); пусто — фраза была одним вступлением.
    Приветствие оставляется, только если посетитель сам поздоровался (question)."""
    pattern = _FILLER_OPENER if visitor_greeted(question) else _ANY_OPENER
    text = phrase
    for _ in range(3):
        cut = pattern.sub("", text, count=1)
        if cut == text:
            break
        text = cut
    if text == phrase:
        return phrase
    text = text.strip()
    return text[:1].upper() + text[1:] if text else ""
