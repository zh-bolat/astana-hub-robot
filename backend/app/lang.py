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
_KK_STRONG_WORDS = {
    "ма",
    "ме",
    "ба",
    "бе",
    "па",
    "пе",
    "деген",
    "нешеде",
    "жоқ",
    "рахмет",
    "сау",
    "бол",
}
# Латинские названия, которые встречаются в любой речи и не говорят о языке
_NEUTRAL_LATIN = {"astana", "hub", "wi-fi", "wifi", "it", "ok", "id", "qr", "expo"}


def _is_cyrillic(ch: str) -> bool:
    return "Ѐ" <= ch <= "ӿ"


def _is_latin(ch: str) -> bool:
    return ("a" <= ch <= "z") or ("A" <= ch <= "Z")


def _words(text: str) -> list[str]:
    return [w.strip(".,!?;:«»\"'()…-").lower() for w in text.split()]


def script_of(text: str) -> str | None:
    """Письменность текста: "cyr", "lat" или None (нет букв / только бренды вроде «Astana Hub»)."""
    if any(ch in KAZAKH_LETTERS for ch in text):
        return "cyr"
    words = _words(text)
    cyr = sum(1 for w in words if any(_is_cyrillic(ch) for ch in w))
    lat = sum(
        1
        for w in words
        if w not in _NEUTRAL_LATIN
        and any(_is_latin(ch) for ch in w)
        and not any(_is_cyrillic(ch) for ch in w)
    )
    if cyr == 0 and lat == 0:
        return None
    return "cyr" if cyr >= lat else "lat"


def _is_kazakh(text: str) -> bool:
    return any(ch in KAZAKH_LETTERS for ch in text) or bool(set(_words(text)) & _KK_STRONG_WORDS)


def detect_lang(text: str, default: Lang = DEFAULT_LANG) -> Lang:
    script = script_of(text)
    if script is None:
        return default
    if script == "cyr":
        return "kk" if _is_kazakh(text) else "ru"
    return "en"


def normalize_lang(value: str | None) -> Lang | None:
    if not value:
        return None
    v = value.strip().lower()
    aliases = {"kz": "kk", "kaz": "kk", "rus": "ru", "eng": "en"}
    v = aliases.get(v, v)
    return v if v in LANGS else None  # type: ignore[return-value]


def resolve_lang(requested: str | None, llm_lang: str | None, text: str) -> Lang:
    """Итоговый язык ответа. Письменность текста важнее всего:
    - кириллица → только ru или kk (kk — казахские буквы/частицы; иначе kk, если так сказали робот
      или LLM; иначе ru). Никогда en;
    - латиница → en (kk — только если явно сказали робот или LLM: казахская латиница);
    - без букв / смешанное неуверенно → lang робота, затем LLM, затем ru.
    """
    req, llm = normalize_lang(requested), normalize_lang(llm_lang)
    script = script_of(text)
    if script == "cyr":
        if _is_kazakh(text):
            return "kk"
        return "kk" if "kk" in (req, llm) else "ru"
    if script == "lat":
        return "kk" if "kk" in (req, llm) else "en"
    return req or llm or DEFAULT_LANG


def lang_instruction(requested: str | None, text: str) -> str:
    """Подсказка модели, на каком языке отвечать (до её ответа)."""
    req = normalize_lang(requested)
    script = script_of(text)
    if script == "cyr":
        if _is_kazakh(text) or req == "kk":
            return "kk (казахский)"
        return "ru или kk — по языку вопроса (вопрос на кириллице, НЕ английский)"
    if script == "lat":
        return "kk (казахский)" if req == "kk" else "en (английский)"
    return req or "определи по вопросу"


# Коды языка от STT: ISO 639-1/639-3 и локали («rus», «kaz», «en-US»)
_STT_CODES = {"rus": "ru", "kaz": "kk", "kz": "kk", "eng": "en"}


def stt_lang_code(value: object) -> Lang | None:
    """Язык, который вернул распознаватель, → ru / kk / en; другой язык или пусто — None."""
    raw = str(getattr(value, "value", value) or "").strip().lower().replace("_", "-")
    if not raw:
        return None
    base = raw.split("-")[0]
    return normalize_lang(_STT_CODES.get(base, base))


def utterance_lang(text: str, stt_lang: Lang | None, session_lang: Lang) -> Lang:
    """Язык реплики посетителя (live): казахские буквы и частицы → kk; иначе язык от STT, если он из
    ru/kk/en и совпадает с письменностью; иначе — язык сессии (STT вернул другой язык или ничего)."""
    script = script_of(text)
    if script == "cyr":
        if _is_kazakh(text):
            return "kk"
        if stt_lang in ("ru", "kk"):
            return stt_lang
        return session_lang if session_lang in ("ru", "kk") else "ru"
    if script == "lat":
        return "kk" if stt_lang == "kk" else "en"
    return stt_lang or session_lang


def tts_lang(phrase: str, fallback: Lang) -> Lang:
    """Голос для фразы ответа — по самой фразе: казахские буквы/частицы → kk (казахский текст никогда не
    уходит в ru/en-голос и модель flash), латиница → en, кириллица без примет — ru или kk хода."""
    script = script_of(phrase)
    if script == "cyr":
        if _is_kazakh(phrase):
            return "kk"
        return fallback if fallback in ("ru", "kk") else "ru"
    if script == "lat":
        return "en"
    return fallback
