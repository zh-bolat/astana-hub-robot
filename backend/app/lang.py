"""Определение языка вопроса: ru / kk / en (шаг 3.6).

Эвристика по алфавиту:
- есть специфичные казахские буквы (ә ғ қ ң ө ұ ү һ і) → kk;
- кириллицы больше латиницы → ru;
- латиницы больше → en.
Казахский текст без специфичных букв ("Кофе бар ма?") эвристика примет за ru —
поэтому итоговый язык: lang робота > язык, который вернула LLM > эвристика.
"""

from __future__ import annotations

from typing import Literal

Lang = Literal["ru", "kk", "en"]
LANGS: tuple[Lang, ...] = ("ru", "kk", "en")
DEFAULT_LANG: Lang = "ru"

KAZAKH_LETTERS = set("әғқңөұүһіӘҒҚҢӨҰҮҺІ")
# Казахские вопросительные частицы без специфичных букв ("Кофе бар ма?")
_KK_STRONG_WORDS = {"ма", "ме", "ба", "бе", "па", "пе", "деген", "нешеде", "жоқ"}
# Латинские названия, которые встречаются в любой речи и не говорят о языке
_NEUTRAL_LATIN = {"astana", "hub", "wi-fi", "wifi", "it", "ok", "id", "qr", "expo"}


def _is_cyrillic(ch: str) -> bool:
    return "Ѐ" <= ch <= "ӿ"


def _is_latin(ch: str) -> bool:
    return ("a" <= ch <= "z") or ("A" <= ch <= "Z")


def detect_lang(text: str, default: Lang = DEFAULT_LANG) -> Lang:
    if any(ch in KAZAKH_LETTERS for ch in text):
        return "kk"
    words = [w.strip(".,!?;:«»\"'()").lower() for w in text.split()]
    cyr = sum(1 for w in words if any(_is_cyrillic(ch) for ch in w))
    lat = sum(
        1
        for w in words
        if w not in _NEUTRAL_LATIN
        and any(_is_latin(ch) for ch in w)
        and not any(_is_cyrillic(ch) for ch in w)
    )
    if cyr == 0 and lat == 0:
        return default
    if cyr >= lat:
        return "kk" if set(words) & _KK_STRONG_WORDS else "ru"
    return "en"


def normalize_lang(value: str | None) -> Lang | None:
    if not value:
        return None
    v = value.strip().lower()
    aliases = {"kz": "kk", "kaz": "kk", "rus": "ru", "eng": "en"}
    v = aliases.get(v, v)
    return v if v in LANGS else None  # type: ignore[return-value]


def resolve_lang(requested: str | None, llm_lang: str | None, text: str) -> Lang:
    return normalize_lang(requested) or normalize_lang(llm_lang) or detect_lang(text)
