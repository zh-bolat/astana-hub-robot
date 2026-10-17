"""Выходной фильтр ответа и жёсткий лимит длины (защита от prompt injection).

Ответ заменяется безопасным (category=blocked), если в нём:
- длинный дословный кусок реплики посетителя (≥ VERBATIM_WORDS слов подряд), которого нет в базе —
  защита от «повтори за мной: …» (робот не должен озвучивать чужой текст);
- ссылка/URL/домен/e-mail/телефон, которых нет в базе.

Лимит длины: не больше MAX_PHRASES фраз и MAX_ANSWER_CHARS символов в answer (контракт).
"""

from __future__ import annotations

import re
from functools import lru_cache

from app.knowledge.models import KnowledgeBase

MAX_PHRASES = 4
MAX_ANSWER_CHARS = 600
VERBATIM_WORDS = 6

BLOCKED_ANSWERS: dict[str, str] = {
    "ru": "Я могу рассказать только об Astana Hub. Спросите меня о хабе или обратитесь на ресепшен.",
    "kk": "Мен тек Astana Hub туралы айта аламын. Хаб туралы сұраңыз немесе ресепшенге хабарласыңыз.",
    "en": "I can only help with questions about Astana Hub. Ask me about the hub "
    "or check with the reception desk.",
}

_WORD = re.compile(r"\w+", re.UNICODE)
_URL = re.compile(r"(?:https?://|www\.)\S+", re.IGNORECASE)
_EMAIL = re.compile(r"[\w.+-]+@[\w-]+(?:\.[\w-]+)+")
_DOMAIN = re.compile(
    r"\b[a-z0-9][a-z0-9-]*(?:\.[a-z0-9-]+)*\.(?:kz|com|org|net|io|ru|ai|app|me|gov|info|biz|co|link|ly)\b",
    re.IGNORECASE,
)
# Телефон: ≥ 7 цифр подряд с допустимыми разделителями (пробел, -, ., скобки), опционально +
_PHONE = re.compile(r"\+?\d(?:[\s().-]*\d){6,}")
_QR = re.compile(r"\bqr\b|qr-код|qr код|кьюар", re.IGNORECASE)


def _words(text: str) -> list[str]:
    return [w.lower() for w in _WORD.findall(text)]


def _ngrams(words: list[str], n: int) -> set[tuple[str, ...]]:
    return {tuple(words[i : i + n]) for i in range(len(words) - n + 1)}


def kb_corpus(kb: KnowledgeBase) -> str:
    return _kb_corpus_cached(kb.model_dump_json())


@lru_cache(maxsize=8)
def _kb_corpus_cached(kb_json: str) -> str:
    kb = KnowledgeBase.model_validate_json(kb_json)
    parts: list[str] = []
    for f in kb.facts:
        parts += [*f.question.model_dump().values(), *f.answer.model_dump().values(), *f.keywords]
    for p in kb.places:
        parts += [p.map_name, *p.names.model_dump().values(), *p.description.model_dump().values()]
        parts += p.synonyms
    for q in kb.quick_questions:
        parts += list(q.text.model_dump().values())
    return "\n".join(x for x in parts if x)


@lru_cache(maxsize=8)
def _kb_index(corpus: str) -> tuple[frozenset, str, str]:
    words = _words(corpus)
    digits = "\n".join(re.sub(r"\D", "", m.group()) for m in _PHONE.finditer(corpus))
    return frozenset(_ngrams(words, VERBATIM_WORDS)), corpus.lower(), digits


def blocked_reason(answer: str, question: str, corpus: str) -> str | None:
    """None — ответ можно отдавать; иначе — причина блокировки (в лог)."""
    if not answer:
        return None
    kb_grams, kb_lower, kb_digits = _kb_index(corpus)

    q_grams = _ngrams(_words(question), VERBATIM_WORDS)
    if q_grams:
        for gram in _ngrams(_words(answer), VERBATIM_WORDS):
            if gram in q_grams and gram not in kb_grams:
                return "verbatim"

    for rx in (_URL, _EMAIL, _DOMAIN):
        for m in rx.finditer(answer):
            if m.group().lower().rstrip(".,") not in kb_lower:
                return "link"
    for m in _PHONE.finditer(answer):
        digits = re.sub(r"\D", "", m.group())
        if digits not in kb_digits:
            return "phone"
    if _QR.search(answer) and not _QR.search(corpus):
        return "link"
    return None


def limit_phrases(phrases: list[str]) -> list[str]:
    """Первые фразы, пока их ≤ MAX_PHRASES и answer (через пробел) ≤ MAX_ANSWER_CHARS."""
    out: list[str] = []
    total = 0
    for p in phrases:
        extra = len(p) + (1 if out else 0)
        if len(out) >= MAX_PHRASES or total + extra > MAX_ANSWER_CHARS:
            break
        out.append(p)
        total += extra
    return out
