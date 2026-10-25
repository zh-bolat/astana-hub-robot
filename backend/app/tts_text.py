"""Текст фразы → текст для синтеза речи (live и /v1/tts). Субтитры и журнал остаются как есть.

- «…» / «...» → запятая или точка: eleven_v3 читает многоточие как запинку;
- скобки: [..] — вырезаются (у v3 это теги звуков), (..) — содержимое остаётся, через запятую;
- междометия в начале фразы («Хм,», «Ммм…», «Э-э,», «Ну,», «Um,») — убираются;
- числа и время словами для ru и kk: «с 9:00 до 18:00» → «с девяти до восемнадцати», «9:00-ден 18:00-ге
  дейін» → «тоғыздан он сегізге дейін», «на 2 этаже» → «на втором этаже», «2-қабат» → «екінші қабат»;
- словарь произношений: Astana Hub → Астана Хаб, TikTok → Тик-Ток, Wi-Fi → вай-фай / уай-фай, IT → ай-ти…
  Дополняется без программиста файлом `pronunciations.yaml` в папке базы знаний (KNOWLEDGE_DIR):

      ru:
        Astana Hub: Астана Хаб
      kk:
        Wi-Fi: уай-фай

  Ключи — без учёта регистра, целым словом; ключ из одних заглавных латинских букв («IT») — с учётом регистра.
"""

from __future__ import annotations

import re
import threading
from pathlib import Path

import yaml
from num2words import num2words

from app.lang import Lang
from app.logging_setup import logger

# ---------- словарь произношений ----------
PRONUNCIATIONS: dict[str, dict[str, str]] = {
    "ru": {
        "Astana Hub": "Астана Хаб",
        "AstanaHub": "Астана Хаб",
        "Astana": "Астана",
        "Hub": "Хаб",
        "TikTok": "Тик-Ток",
        "Tik Tok": "Тик-Ток",
        "Wi-Fi": "вай-фай",
        "WiFi": "вай-фай",
        "Wi Fi": "вай-фай",
        "IT": "ай-ти",
        "AI": "эй-ай",
        "ID": "ай-ди",
        "QR": "кью-ар",
        "EXPO": "Экспо",
        "Expo": "Экспо",
        "OK": "окей",
        "coworking": "коворкинг",
        "startup": "стартап",
    },
    "kk": {
        "Astana Hub": "Астана Хаб",
        "AstanaHub": "Астана Хаб",
        "Astana": "Астана",
        "Hub": "Хаб",
        "TikTok": "Тик-Ток",
        "Tik Tok": "Тик-Ток",
        "Wi-Fi": "уай-фай",
        "WiFi": "уай-фай",
        "Wi Fi": "уай-фай",
        "IT": "ай-ти",
        "AI": "эй-ай",
        "ID": "ай-ди",
        "QR": "кью-ар",
        "EXPO": "Экспо",
        "Expo": "Экспо",
        "OK": "окей",
    },
    "en": {},
}

_lock = threading.Lock()
_compiled: dict[tuple[str, str, float], list[tuple[re.Pattern[str], str]]] = {}


def _load_extra(path: Path | None) -> tuple[dict[str, dict[str, str]], float]:
    if path is None:
        return {}, 0.0
    try:
        mtime = path.stat().st_mtime
    except OSError:
        return {}, 0.0
    try:
        data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    except (OSError, yaml.YAMLError) as exc:
        logger.warning("tts_pronunciations_invalid", extra={"error": type(exc).__name__})
        return {}, mtime
    out: dict[str, dict[str, str]] = {}
    if isinstance(data, dict):
        for lang, table in data.items():
            if isinstance(table, dict):
                out[str(lang)] = {str(k): str(v) for k, v in table.items() if k and v is not None}
    return out, mtime


def _rules(lang: str, path: Path | None) -> list[tuple[re.Pattern[str], str]]:
    extra, mtime = _load_extra(path)
    key = (lang, str(path), mtime)
    with _lock:
        cached = _compiled.get(key)
        if cached is not None:
            return cached
        table = {**PRONUNCIATIONS.get(lang, {}), **extra.get(lang, {})}
        rules = []
        for src in sorted(table, key=len, reverse=True):  # длинные раньше: «Astana Hub» до «Hub»
            flags = 0 if re.fullmatch(r"[A-Z]{2,}", src) else re.IGNORECASE
            body = r"\s*".join(re.escape(part) for part in src.split())
            rules.append((re.compile(rf"(?<![\w-]){body}(?![\w])", flags), table[src]))
        _compiled.clear()  # файл поменялся — старые правила не нужны
        _compiled[key] = rules
        return rules


def apply_pronunciations(text: str, lang: str, path: Path | None = None) -> str:
    for pattern, repl in _rules(lang, path):

        def sub(m: re.Match[str], repl: str = repl) -> str:
            start = m.string[: m.start()].rstrip()
            if m.group(0)[:1].isupper() and (not start or start[-1] in ".!?"):
                return repl[:1].upper() + repl[1:]  # начало предложения — с заглавной
            return repl

        text = pattern.sub(sub, text)
    return text


# ---------- паузы, скобки, междометия ----------
_SQUARE = re.compile(r"\[[^\]]*\]")
_PAREN = re.compile(r"\s*\(([^()]*)\)")
_ELLIPSIS = re.compile(r"\s*(?:\.{2,}|…)+\s*")
_FILLER = re.compile(
    r"^(?:(?:хм+|х-м+|м+|м-м+|мм+|э+(?:-э+)*|эм+|ээ+|ну|ох|ах|ой|әм+|ә+|hm+|h-m+|um+|uh+|uhm|er+|erm|mm+)"
    r"(?![\w])[\s,.!?…-]*)+",
    re.IGNORECASE,
)


def _tidy(text: str) -> str:
    text = re.sub(r"\s+([,.!?;:])", r"\1", text)
    text = re.sub(r",(\s*,)+", ",", text)
    text = re.sub(r",\s*([.!?])", r"\1", text)
    text = re.sub(r"([.!?])\s*,", r"\1", text)
    text = re.sub(r"^[\s,.;:-]+", "", text)
    return re.sub(r"\s{2,}", " ", text).strip()


def _ellipsis(m: re.Match[str]) -> str:
    rest = m.string[m.end() :]
    if not rest.strip():
        return "."
    return ". " if rest[:1].isupper() else ", "


def _cap_first(text: str) -> str:
    return text[:1].upper() + text[1:] if text else text


# ---------- числа: русский ----------
_RU_GEN_PREP = frozenset(
    [
        "с",
        "со",
        "до",
        "от",
        "после",
        "около",
        "для",
        "без",
        "из",
        "у",
        "кроме",
        "больше",
        "меньше",
        "более",
        "менее",
        "свыше",
    ]
)
_RU_DAT_PREP = frozenset(["к", "ко"])
_RU_LOC_PREP = frozenset(["о", "об", "при"])
_RU_FEM = (
    "минут",
    "секунд",
    "недел",
    "комнат",
    "зон",
    "переговорн",
    "лекци",
    "встреч",
    "смен",
    "тысяч",
)
_RU_NEUT = ("мест", "окн", "кресл")
# Порядковые: «2 этаж», «на 3 этаже», «в 2026 году»; окончание существительного → падеж
_RU_ORD_NOUNS = re.compile(
    r"(?P<n>\d{1,4})\s+(?P<noun>(?:этаж|корпус|подъезд|год)(?:ом|е|у|а|)?)\b", re.I
)
_RU_ORD_SUFFIX = re.compile(r"\b(?P<n>\d{1,4})-(?P<suf>й|я|е|го|му|м|ом|ой|ую|х)\b")
_TIME = re.compile(r"(?<![\d:])(?P<h>[01]?\d|2[0-3]):(?P<m>[0-5]\d)(?![\d:])")
_TIME_RANGE = re.compile(
    r"(?:(?P<pre>\bс)\s+)?(?<![\d:])(?P<a>(?:[01]?\d|2[0-3]):[0-5]\d)\s*[-–—]\s*(?P<b>(?:[01]?\d|2[0-3]):[0-5]\d)(?![\d:])"
)
_INT = re.compile(r"(?<![\w.,:-])(?P<n>\d{1,4})(?![\w]|[.,:]\d)")
_PERCENT = re.compile(r"(?<![\w.,])(?P<n>\d{1,4})\s*%")
_CASE_RU = {
    "n": "nominative",
    "g": "genitive",
    "d": "dative",
    "a": "accusative",
    "i": "instrumental",
    "p": "prepositional",
}


def _ru_case_before(text: str, start: int) -> str:
    words = re.findall(r"[^\W\d_]+", text[:start].lower())
    if not words:
        return "n"
    prev = words[-1]
    if prev in _RU_GEN_PREP:
        return "g"
    if prev in _RU_DAT_PREP:
        return "d"
    if prev in _RU_LOC_PREP:
        return "p"
    return "n"


def _ru_gender_after(text: str, end: int) -> str:
    m = re.match(r"\s+([^\W\d_]+)", text[end:])
    if not m:
        return "m"
    word = m.group(1).lower()
    if word.startswith(_RU_FEM):
        return "f"
    if word.startswith(_RU_NEUT):
        return "n"
    return "m"


def _ru_card(n: int, case: str = "n", gender: str = "m") -> str:
    words = num2words(n, lang="ru", case=_CASE_RU[case], gender=gender)
    # 1500 → «тысяча пятьсот», а не «одна тысяча пятьсот»
    return (
        re.sub(r"^(?:одна|одной|одну|одною) (тысяч)", r"\1", words) if 1000 <= n < 2000 else words
    )


def _ru_ord(n: int, case: str = "n", gender: str = "m") -> str:
    return num2words(n, lang="ru", to="ordinal", case=_CASE_RU[case], gender=gender, animate=False)


def _ru_time(hh: str, mm: str, case: str) -> str:
    words = _ru_card(int(hh), case)
    if mm != "00":
        minutes = _ru_card(int(mm), case, gender="f")
        words += f" ноль {minutes}" if mm.startswith("0") else f" {minutes}"
    return words


def _ru_numbers(text: str) -> str:
    def time_range(m: re.Match[str]) -> str:
        a, b = m.group("a").split(":"), m.group("b").split(":")
        return f"с {_ru_time(*a, 'g')} до {_ru_time(*b, 'g')}"

    text = _TIME_RANGE.sub(time_range, text)
    text = _TIME.sub(lambda m: _ru_time(m["h"], m["m"], _ru_case_before(m.string, m.start())), text)

    def ord_noun(m: re.Match[str]) -> str:
        noun = m.group("noun")
        end = noun.lower().removeprefix("этаж").removeprefix("корпус").removeprefix("подъезд")
        end = end.removeprefix("год")
        n = int(m.group("n"))
        case = {
            "": "n",
            "е": "p",
            "у": "p" if noun.lower().startswith("год") else "d",
            "ом": "i",
        }.get(end)
        if end == "а":  # «до 3 этажа» — порядковый; «3 этажа» без предлога — количество
            if _ru_case_before(m.string, m.start()) == "g" or n >= 1000:
                case = "g"
            else:
                return f"{_ru_card(n)} {noun}"
        if case is None:
            return m.group(0)
        return f"{_ru_ord(n, case)} {noun}"

    text = _RU_ORD_NOUNS.sub(ord_noun, text)

    def ord_suffix(m: re.Match[str]) -> str:
        n, suf = int(m.group("n")), m.group("suf")
        case, gender = {
            "й": ("n", "m"),
            "я": ("n", "f"),
            "е": ("n", "n"),
            "го": ("g", "m"),
            "му": ("d", "m"),
            "м": ("p", "m"),
            "ом": ("p", "m"),
            "ой": ("g", "f"),
            "ую": ("a", "f"),
            "х": ("p", "m"),
        }[suf]
        return _ru_ord(n, case, gender)

    text = _RU_ORD_SUFFIX.sub(ord_suffix, text)

    def percent(m: re.Match[str]) -> str:
        n = int(m.group("n"))
        tail = n % 100
        word = (
            "процентов"
            if 11 <= tail <= 14
            else {1: "процент", 2: "процента", 3: "процента", 4: "процента"}.get(
                n % 10, "процентов"
            )
        )
        return f"{_ru_card(n, _ru_case_before(m.string, m.start()))} {word}"

    text = _PERCENT.sub(percent, text)
    return _INT.sub(
        lambda m: _ru_card(
            int(m["n"]), _ru_case_before(m.string, m.start()), _ru_gender_after(m.string, m.end())
        ),
        text,
    )


# ---------- числа: казахский ----------
_KK_BACK = set("аоұы")
_KK_FRONT = set("әеөүіэи")
_KK_HARD = set("кқпстфхцчшщ")
_KK_SONOR = set("рлйу")
_KK_NASAL = set("мнң")
_KK_SUFFIX = {
    **dict.fromkeys(("ға", "ге", "қа", "ке", "а", "е", "на", "не"), "dat"),
    **dict.fromkeys(("дан", "ден", "тан", "тен", "нан", "нен"), "abl"),
    **dict.fromkeys(("да", "де", "та", "те", "нда", "нде"), "loc"),
    **dict.fromkeys(("дың", "дің", "тың", "тің", "ның", "нің"), "gen"),
    **dict.fromkeys(("ды", "ді", "ты", "ті", "ны", "ні"), "acc"),
    **dict.fromkeys(("мен", "бен", "пен"), "ins"),
    **dict.fromkeys(("ші", "шы", "інші", "ыншы", "нші", "ншы"), "ord"),
}


def _kk_front(word: str) -> bool:
    vowels = [ch for ch in word if ch in _KK_BACK | _KK_FRONT]
    return bool(vowels) and vowels[-1] in _KK_FRONT


def kk_suffix(word: str, case: str) -> str:
    """Падежное окончание по сингармонизму и последней букве: тоғыз+abl → тоғыздан, үш+dat → үшке."""
    front, last = _kk_front(word), word[-1:]
    vowel = last in _KK_BACK | _KK_FRONT
    hard = last in _KK_HARD

    def pick(back: str, fr: str) -> str:
        return fr if front else back

    if case == "dat":
        return pick("қа", "ке") if hard else pick("ға", "ге")
    if case == "abl":
        if hard:
            return pick("тан", "тен")
        if last in _KK_NASAL:
            return pick("нан", "нен")
        return pick("дан", "ден")
    if case == "loc":
        return pick("та", "те") if hard else pick("да", "де")
    if case == "gen":
        if vowel:
            return pick("ның", "нің")
        return pick("тың", "тің") if hard else pick("дың", "дің")
    if case == "acc":
        if vowel:
            return pick("ны", "ні")
        return pick("ты", "ті") if hard else pick("ды", "ді")
    if case == "ins":
        if vowel or last in _KK_SONOR:
            return "мен"
        return "пен" if hard else "бен"
    if case == "ord":
        return pick("ншы", "нші") if vowel else pick("ыншы", "інші")
    return ""


def _kk_card(n: int) -> str:
    words = num2words(n, lang="kz")
    return words.removeprefix("бір ") if 1000 <= n < 2000 else words  # «мың бес жүз»


def _kk_ord(n: int) -> str:
    words = _kk_card(n)
    return words + kk_suffix(words, "ord")


def _kk_time(hh: str, mm: str) -> str:
    words = _kk_card(int(hh))
    if mm != "00":
        words += (" нөл " if mm.startswith("0") else " ") + _kk_card(int(mm))
    return words


_KK_TIME = re.compile(
    r"(?<![\d:])(?P<h>[01]?\d|2[0-3]):(?P<m>[0-5]\d)(?:-(?P<suf>[^\W\d_]+))?(?![\d:])"
)
_KK_NUM = re.compile(
    r"(?<![\w.,:])(?P<n>\d{1,4})(?:-(?P<suf>[^\W\d_]+)|(?P<ord>(?=\s+қабат)))?(?![\w]|[.,:]\d)"
)


def _kk_with_suffix(words: str, suffix: str | None) -> str:
    if not suffix:
        return words
    case = _KK_SUFFIX.get(suffix.lower())
    if case is None:
        return f"{words} {suffix}"  # «2-қабат» — порядковый перед словом
    return words + kk_suffix(words, case)


def _kk_numbers(text: str) -> str:
    def time_range(m: re.Match[str]) -> str:
        a, b = m.group("a").split(":"), m.group("b").split(":")
        a_words, b_words = _kk_time(*a), _kk_time(*b)
        if re.match(r"\s+аралығ", m.string[m.end() :]):  # «9:00–18:00 аралығында»
            return f"{a_words} {kk_suffix(a_words, 'ins')} {b_words}"
        return f"{a_words}{kk_suffix(a_words, 'abl')} {b_words}{kk_suffix(b_words, 'dat')} дейін"

    text = _TIME_RANGE.sub(time_range, text)
    text = _KK_TIME.sub(lambda m: _kk_with_suffix(_kk_time(m["h"], m["m"]), m["suf"]), text)

    def number(m: re.Match[str]) -> str:
        n, suf = int(m["n"]), m["suf"]
        if m["ord"] is not None:  # «2 қабатта» → «екінші қабатта»
            return _kk_ord(n)
        if suf is not None and _KK_SUFFIX.get(suf.lower()) in (None, "ord"):
            return _kk_ord(n) + ("" if _KK_SUFFIX.get(suf.lower()) == "ord" else f" {suf}")
        return _kk_with_suffix(_kk_card(n), suf)

    return _KK_NUM.sub(number, text)


# ---------- всё вместе ----------
def is_filler_only(text: str) -> bool:
    """Фраза — одни междометия и паузы («Хм…», «Ммм.», «Э-э,»): не озвучивать и не показывать."""
    cleaned = _tidy(_ELLIPSIS.sub(_ellipsis, _SQUARE.sub(" ", text)))
    return bool(cleaned) and not re.search(r"\w", _FILLER.sub("", cleaned))


def normalize_for_tts(text: str, lang: Lang, pronunciations: Path | None = None) -> str:
    """Фраза для синтеза: без многоточий, скобок и междометий в начале, числа словами (ru/kk),
    произношение брендов и аббревиатур. Пустой результат — исходный текст (синтезу нечего терять)."""
    original = text
    text = _SQUARE.sub(" ", text)
    text = _PAREN.sub(lambda m: f", {m.group(1).strip()}," if m.group(1).strip() else "", text)
    text = _ELLIPSIS.sub(_ellipsis, text)
    text = _tidy(text)
    stripped = _FILLER.sub("", text)
    if re.search(r"\w", stripped):
        text = _cap_first(stripped)
    text = apply_pronunciations(text, lang, pronunciations)
    try:
        if lang == "ru":
            text = _ru_numbers(text)
        elif lang == "kk":
            text = _kk_numbers(text)
    except Exception as exc:  # num2words на редком числе не должен ронять озвучку
        logger.warning("tts_numbers_failed", extra={"error": type(exc).__name__})
    text = _tidy(text)
    return text or original.strip()
