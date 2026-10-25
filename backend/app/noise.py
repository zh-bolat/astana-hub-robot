"""Серверная страховка от шума распознавания (docs/api-contract.md, «Шум вместо реплики (v1.2)»).

Реплика — шум, если в ней нет ничего, кроме междометий («ну», «м-м-м», «ой», «um», «әй»), пометок в скобках
(«(смеётся)», «[музыка]»), слов-пометок без скобок («Смеётся.»), знаков и повторов, коротких слов-паразитов
(«вот», «это», «я»). Такие реплики /v1/chat не отдаёт модели.

Короткие осмысленные реплики шумом не считаются: «привет», «спасибо», «кафе», «давайте», «ок» и любое слово
вне списков ниже. «Да/нет» («иә», «жоқ», «yes», «no», «ага», «угу») — ответ, если последняя реплика робота была
вопросом; иначе — шум («Да, я.», «М-м-м, не-не-не»). Цифры — всегда не шум. Детектор консервативен: при
сомнении реплика уходит модели.
"""

from __future__ import annotations

import re

# Пометки распознавания: (смеётся), [музыка], *кашляет*, {noise}
_BRACKETS = re.compile(r"\([^)]*\)|\[[^\]]*\]|\{[^}]*\}|\*[^*]*\*")
_WORDS = re.compile(r"[^\W\d_]+", re.UNICODE)
_DIGIT = re.compile(r"\d")
_RUNS = re.compile(r"(.)\1+")
# Смех: «хахаха», «ахах», «хи-хи», «hahaha», «lol»
_LAUGH = re.compile(r"^(а?(х[аеиы]){2,}х?|a?(h[aei]){2,}h?|аха|ахах|aha|ahah|lol|lmao)$")
_VOWELS = set("аеёиоуыэюяәіөұүaeiouy")


def _words(text: str) -> frozenset[str]:
    return frozenset(text.split())


# Междометия и звуки (сравниваются и как есть, и со схлопнутыми повторами букв: «ммм» → «м»)
_INTERJECTIONS = _words(
    "ну э эм эх а о ой ох ах ух уф фу хм м мм мгм мда ага угу вау опа оп тсс ш пф фух бр ха хе хи әй "
    "ә е уһ апырай ау жә па пай ойбай мә um umm uh uhm hm hmm mm er erm ah oh huh wow ooh oops mhm ha "
    "eh ugh aha oho shh"
)
# Слова-паразиты и обрывки, бессмысленные сами по себе
_FILLERS = _words(
    "вот это так типа короче блин значит я ты мы он она мен сен енді so well like i me you it this "
    "that just"
)
# Пометки распознавания без скобок: «Смеётся.», «Музыка», «Laughter»
_EVENT_WORDS = _words(
    "смеётся смеется смех смеются музыка кашель кашляет аплодисменты шум тишина вздыхает вздох "
    "неразборчиво неразборчивая речь звук звуки шорох күледі күлкі музыкасы шу laughs laughing "
    "laughter music cough coughs applause noise silence sighs inaudible unintelligible background "
    "sound sounds chuckles"
)
# «Да/нет»: ответ на вопрос робота, иначе шум
_YES_NO = _words("да нет не неа ага угу иә ия иа жоқ жок йә yes no yeah yep nope nah")
_NOISE = _INTERJECTIONS | _FILLERS | _EVENT_WORDS


def _variants(word: str) -> tuple[str, str]:
    return word, _RUNS.sub(r"\1", word)


def _in(word: str, vocab: frozenset[str]) -> bool:
    return any(v in vocab for v in _variants(word))


def _is_noise_word(word: str, raw: str) -> bool:
    if raw.isupper() and 1 < len(raw) <= 4:  # аббревиатуры («WC», «IT», «ЦОН») — не шум
        return False
    if len(word) == 1 or _in(word, _NOISE) or any(_LAUGH.match(v) for v in _variants(word)):
        return True
    return not set(word) & _VOWELS  # «мм», «пфф», «brr» — без гласных


def _ends_with_question(text: str | None) -> bool:
    return bool(text) and text.rstrip(" \t\n.!»\"')").endswith("?")


def is_noise(text: str, last_agent: str | None = None) -> bool:
    """True — реплика посетителя только шум; last_agent — последняя реплика робота в разговоре."""
    if _DIGIT.search(text):
        return False
    stripped = _BRACKETS.sub(" ", text)
    raw_words = _WORDS.findall(stripped.replace("-", " "))
    if not raw_words:
        return True
    words = [w.lower() for w in raw_words]
    yes_no = False
    for word, raw in zip(words, raw_words, strict=True):
        if _in(word, _YES_NO):
            yes_no = True
        elif not _is_noise_word(word, raw):
            return False
    return not (yes_no and _ends_with_question(last_agent))


def needs_history(text: str) -> bool:
    """В реплике есть «да/нет» — решение зависит от последней реплики робота."""
    return any(_in(w.lower(), _YES_NO) for w in _WORDS.findall(text.replace("-", " ")))
