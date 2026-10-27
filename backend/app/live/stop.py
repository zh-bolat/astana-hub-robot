"""Команда остановки голосом (docs/api-contract.md, «Шумный зал», v1.4): LIVE_BARGE_IN=stop_only.

Пока робот отвечает, перебивает его только команда остановки — короткая фраза, где команда главное:
«стоп», «стоп-стоп», «хватит уже», «подождите, пожалуйста», «тоқта», «жетеді», «stop», «wait a second».
Вокруг команды допустимы только вежливые и служебные слова («пожалуйста», «робот», «ну», «всё», «спасибо»,
«өтінемін», «please», «ok»). Любое другое слово — и это уже не команда: «я не могу остановиться», «подожди,
мне не нравится тут», «стоп, а где туалет?» (вопрос — главное) не перебивают робота.

Сравнение — без регистра и знаков, казахские буквы приводятся к русским (STT пишет «тоқта» и как «токта»),
повторы букв схлопываются («стооп» → «стоп»).
"""

from __future__ import annotations

import re

MAX_WORDS = 8  # длиннее — это уже разговор, а не команда

_BRACKETS = re.compile(r"\([^)]*\)|\[[^\]]*\]|\{[^}]*\}|\*[^*]*\*")
_WORDS = re.compile(r"[^\W\d_]+", re.UNICODE)
_DIGIT = re.compile(r"\d")
_RUNS = re.compile(r"(.)\1+")
_KK = str.maketrans("әғқңөұүһіё", "агкноуухие")


def _norm(word: str) -> str:
    return word.lower().translate(_KK)


def _vocab(words: str) -> frozenset[str]:
    return frozenset(_norm(w) for w in words.split())


# Команды остановки: ru / kk / en (формы на «ты» и «вы»)
_COMMANDS = _vocab(
    # ru
    "стоп стой стойте хватит достаточно замолчи замолчите замолкни помолчи помолчите молчи "
    "молчите подожди подождите погоди погодите остановись остановитесь прекрати прекратите перестань "
    "перестаньте "
    # kk
    "тоқта тоқтаңыз тоқташы тоқтаңызшы тоқтат тоқтатыңыз тохта жетеді жетер болды тыңда тыңдаңыз "
    "тыңдашы тыңдаңызшы үндеме үндемеңіз сөйлеме сөйлемеңіз "
    # en
    "stop enough wait pause quiet"
)
# Вторая половина составной команды: «shut up», «hold on», «күте тұр(ыңыз)»
_PAIRS = {
    ("shut", "up"),
    ("hold", "on"),
    ("hang", "on"),
    (_norm("күте"), _norm("тұр")),
    (_norm("күте"), _norm("тұрыңыз")),
    (_norm("күте"), _norm("тұршы")),
}
# Вежливые и служебные слова вокруг команды
_FILLERS = _vocab(
    # ru
    "пожалуйста плиз робот робошка ну так все уже ой эй а да ладно хорошо ок окей спасибо понял поняла "
    "понятно ясно я ты вы мне секунду секундочку минуту минутку чуть немного говорить болтать "
    "разговаривать не надо пока "
    # kk
    "өтінемін өтінем өтініш робот енді жарайды рахмет иә ал түсіндім түсінікті бір сәт минут сен сіз "
    "маған әзір "
    # en
    "please robot ok okay oh hey now it talking that s is thanks thank you got a moment second sec "
    "minute just i see right alright all already yes no"
)


def _variants(word: str) -> tuple[str, str]:
    return word, _RUNS.sub(r"\1", word)


def _is(word: str, vocab: frozenset[str]) -> bool:
    return any(v in vocab for v in _variants(word))


def is_stop_command(text: str) -> bool:
    """True — реплика целиком команда остановки (с вежливыми словами вокруг), а не обычная речь."""
    if not text or _DIGIT.search(text):
        return False
    words = [_norm(w) for w in _WORDS.findall(_BRACKETS.sub(" ", text))]
    if not words or len(words) > MAX_WORDS:
        return False
    command = False
    i = 0
    while i < len(words):
        word = words[i]
        if i + 1 < len(words) and (word, words[i + 1]) in _PAIRS:
            command, i = True, i + 2
            continue
        if _is(word, _COMMANDS):
            command = True
        elif not _is(word, _FILLERS):
            return False
        i += 1
    return command
