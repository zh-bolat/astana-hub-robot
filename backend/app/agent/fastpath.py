"""Быстрый путь живого режима: «где X?» и «проводи до X» без модели (WS /v1/live).

Реплика — явный запрос места, и место X однозначно находится в базе (id, map_name, названия, синонимы;
русские падежи и казахский дательный падеж снимаются: «к лифту», «до лектория», «кафеге», «дәріс залына»)
→ сервер сразу отвечает шаблонной фразой на языке реплики и отдаёт действие (where_is / guide_to), как
агент. Всё сомнительное — несколько мест, лишние слова («где кафе и сколько стоит кофе»), «туда»,
«он» — уходит агенту: шаблоны отвечают только на то, в чём ошибиться нельзя.

Согласие на предложение проводить («Проводить вас?» → «да», «давайте», «иә», «yes please») — тоже без
модели: сервер помнит, какое место предложил (ChatService), и сразу отдаёт guide_to.

Направление «где X» — фразой робота (state.directions: сторона и метры от его позиции), если она свежая;
иначе — словами описания места или «смотрите стрелку на экране».

Шаблоны короткие и без «ммм»: первая фраза — то, что TTS начнёт озвучивать сразу. Фразы детерминированы
по месту и языку (плюс небольшой выбор вариантов), поэтому их звук кешируется (app/live/tts_cache.py).
"""

from __future__ import annotations

import random
import re
from dataclasses import dataclass
from typing import Literal

from app.knowledge.models import KnowledgeBase, Place
from app.lang import Lang

PlaceKind = Literal["where_is", "guide_to"]
# Выбор варианта шаблона (два-три на фразу: и живее, и звук быстро оказывается в кеше)
_RNG = random.Random()


@dataclass(frozen=True)
class PlaceIntent:
    kind: PlaceKind
    place: Place
    lang: Lang


# ---------- разбор реплики ----------
_PUNCT = re.compile(r"[^\w\s'-]+", re.UNICODE)
_SPACES = re.compile(r"\s+")


def _norm(text: str) -> str:
    text = text.casefold().replace("ё", "е").replace("’", "'")
    text = _PUNCT.sub(" ", text)
    text = re.sub(r"(?<!\w)-|-(?!\w)", " ", text)  # дефис только внутри слова («tiktok-ке»)
    return _SPACES.sub(" ", text).strip()


def _alt(*words: str) -> str:
    return "(?:" + "|".join(sorted(words, key=len, reverse=True)) + ")"


# Вежливые вступления: «Здравствуйте, подскажите, пожалуйста, где…», «Excuse me, where is…»
_LEAD = re.compile(
    r"^(?:"
    + _alt(
        "здравствуйте",
        "здравствуй",
        "привет",
        "добрый день",
        "доброе утро",
        "добрый вечер",
        "извините",
        "простите",
        "скажите",
        "подскажите",
        "скажи",
        "подскажи",
        "не подскажете",
        "вы не подскажете",
        "можете подсказать",
        "пожалуйста",
        "слушайте",
        "робот",
        "а",
        "и",
        "ну",
        "так",
        "сәлеметсіз бе",
        "сәлеметсіз",
        "сәлем",
        "кешіріңіз",
        "айтыңызшы",
        "өтінемін",
        "hi",
        "hello",
        "hey",
        "excuse me",
        "sorry",
        "please",
        "robot",
        "so",
        "and",
        "can you tell me",
        "could you tell me",
        "do you know",
    )
    + r")\s+"
)

_RU_FILL = _alt(
    "здесь",
    "тут",
    "у вас",
    "находится",
    "находятся",
    "расположен",
    "расположена",
    "расположено",
    "расположены",
    "можно",
    "можно найти",
    "найти",
    "ближайший",
    "ближайшая",
    "ближайшее",
    "ближайшие",
    "пожалуйста",
    "вообще",
    "же",
)
_RU_PREP = _alt("в", "во", "к", "ко", "до", "на")
_RU_GUIDE = _alt(
    "проводи",
    "проводите",
    "проводить",
    "отведи",
    "отведите",
    "отвести",
    "доведи",
    "доведите",
    "довести",
    "проведи",
    "проведите",
    "провести",
    "сопроводи",
    "сопроводите",
    "сопроводить",
    "веди",
    "ведите",
    "отвези",
    "отвезите",
    "покажи дорогу",
    "покажите дорогу",
    "показать дорогу",
    "пойдем",
    "пойдемте",
    "идем",
    "идемте",
    "пошли",
)
_RU_CAN = _alt("можете", "можешь", "могли бы", "не могли бы", "можно")

_KK_WHERE = _alt("қайда", "қай жерде")
_KK_WHERE_TAIL = _alt("орналасқан", "екен", "бар", "болады", "айтыңызшы", "өтінемін")
_KK_GUIDE = _alt(
    "апарыңыз",
    "апарыңызшы",
    "апарып салыңыз",
    "апарып салыңызшы",
    "апарып жіберіңіз",
    "апарып жіберіңізші",
    "апарып беріңіз",
    "апарып беріңізші",
    "апар",
    "апаршы",
    "апарып сал",
    "апарып салшы",
    "апарасыз ба",
    "апарып саласыз ба",
    "апара аласыз ба",
    "апарып бере аласыз ба",
    "апарып жібересіз бе",
    "алып барыңыз",
    "алып барыңызшы",
    "ертіп барыңыз",
    "бастап барыңыз",
    "жеткізіп салыңыз",
    "жол көрсетіңіз",
    "жол көрсетіңізші",
)
_EN_ART = r"(?:(?:the|a|an|nearest|closest)\s+)*"

_PATTERNS: list[tuple[PlaceKind, Lang, re.Pattern[str]]] = [
    # ru: «где (здесь) X (находится)», «как пройти к X», «X где»
    (
        "where_is",
        "ru",
        re.compile(rf"^где\s+(?:{_RU_FILL}\s+)*(?P<x>.+?)(?:\s+{_RU_FILL})*$"),
    ),
    (
        "where_is",
        "ru",
        re.compile(
            rf"^как\s+(?:мне\s+)?(?:пройти|попасть|дойти|найти|добраться)\s+(?:{_RU_PREP}\s+)?"
            rf"(?P<x>.+?)(?:\s+{_RU_FILL})*$"
        ),
    ),
    ("where_is", "ru", re.compile(rf"^(?P<x>.+?)\s+где(?:\s+{_RU_FILL})*$")),
    # ru: «проводите (меня) до X», «можете отвести меня к X»
    (
        "guide_to",
        "ru",
        re.compile(
            rf"^(?:{_RU_CAN}\s+(?:вы\s+|ты\s+)?)?(?:пожалуйста\s+)?{_RU_GUIDE}\s+"
            rf"(?:(?:меня|нас|пожалуйста)\s+)*(?:{_RU_PREP}\s+)?(?P<x>.+?)"
            rf"(?:\s+(?:пожалуйста|меня|нас))*$"
        ),
    ),
    # kk: «X қайда (орналасқан)», «X-ке апарыңыз», «мені X-ке апарып салыңызшы»
    (
        "where_is",
        "kk",
        re.compile(rf"^(?P<x>.+?)\s+{_KK_WHERE}(?:\s+{_KK_WHERE_TAIL})*$"),
    ),
    (
        "guide_to",
        "kk",
        re.compile(
            rf"^(?:(?:мені|бізді)\s+)?(?P<x>.+?)(?:\s+дейін)?\s+(?:(?:мені|бізді)\s+)?{_KK_GUIDE}"
            rf"(?:\s+өтінемін)?$"
        ),
    ),
    # en: «where is the X», «how do I get to the X», «take me to the X»
    (
        "where_is",
        "en",
        re.compile(
            rf"^(?:where\s+(?:is|are|'s)|where's|where\s+can\s+i\s+find|how\s+(?:do|can)\s+i\s+get\s+to|"
            rf"how\s+to\s+get\s+to|which\s+way\s+(?:is|to))\s+{_EN_ART}(?P<x>.+?)"
            rf"(?:\s+(?:please|here|located))*$"
        ),
    ),
    (
        "guide_to",
        "en",
        re.compile(
            rf"^(?:(?:can|could|would|will)\s+you\s+)?(?:please\s+)?"
            rf"(?:take|guide|lead|walk|bring|show)\s+(?:me|us)\s+(?:the\s+way\s+)?(?:to|towards)\s+"
            rf"{_EN_ART}(?P<x>.+?)(?:\s+please)?$"
        ),
    ),
]

# Ссылки на прошлое («туда», «он») и вопросы о самом месте — не место
_NOT_PLACE = frozenset(
    [
        "туда",
        "там",
        "тут",
        "здесь",
        "он",
        "она",
        "оно",
        "они",
        "это",
        "то",
        "его",
        "ее",
        "их",
        "сюда",
        "мне",
        "нам",
        "вас",
        "вам",
        "сонда",
        "онда",
        "осында",
        "ол",
        "бұл",
        "оны",
        "соған",
        "it",
        "there",
        "here",
        "this",
        "that",
        "them",
    ]
)

_RU_ENDINGS = sorted(
    [
        "ами",
        "ями",
        "ого",
        "его",
        "ому",
        "ему",
        "ыми",
        "ими",
        "ую",
        "юю",
        "ая",
        "яя",
        "ое",
        "ее",
        "ые",
        "ие",
        "ый",
        "ий",
        "ой",
        "ей",
        "ых",
        "их",
        "ом",
        "ем",
        "ам",
        "ям",
        "ах",
        "ях",
        "ию",
        "ия",
        "ье",
        "ью",
        "а",
        "я",
        "у",
        "ю",
        "е",
        "и",
        "ы",
        "о",
    ],
    key=len,
    reverse=True,
)
_KK_DATIVE = ("ға", "ге", "қа", "ке", "на", "не")


def _ru_stem(word: str) -> str:
    for end in _RU_ENDINGS:
        if word.endswith(end) and len(word) - len(end) >= 3:
            return word[: -len(end)]
    return word


def _en_stem(word: str) -> str:
    if len(word) > 4 and word.endswith("s") and not word.endswith("ss"):
        return word[:-1]
    return word


def _key(text: str) -> tuple[str, ...]:
    """Слова без окончаний (русские падежи, английское множественное) — для сравнения с базой."""
    out: list[str] = []
    for word in _norm(text).replace("-", " ").split():
        if any("a" <= ch <= "z" for ch in word):
            out.append(_en_stem(word))
        else:
            out.append(_ru_stem(word))
    return tuple(out)


def _kk_variants(x: str) -> list[str]:
    """Казахский дательный падеж: «кафеге» → «кафе», «дәріс залына» → «дәріс залы», «tiktok-ке»."""
    out = []
    head, _, last = x.rpartition(" ")
    for end in _KK_DATIVE:
        stem = last.removesuffix(end).rstrip("-")
        if stem != last and len(stem) >= 2:
            out.append(f"{head} {stem}".strip())
    return out


def _terms(place: Place) -> list[str]:
    return [
        t
        for t in (place.id, place.map_name, *place.names.model_dump().values(), *place.synonyms)
        if t
    ]


def resolve_place(kb: KnowledgeBase, query: str, lang: Lang) -> Place | None:
    """Место по словам посетителя — только если оно одно. Сначала совпадение с названием/синонимом с точностью
    до окончаний, затем — KnowledgeBase.find_place (id, map_name, вхождение) для одного слова."""
    x = _norm(query)
    if not x or set(x.split()) & _NOT_PLACE:
        return None
    variants = [x, *(_kk_variants(x) if lang == "kk" else ())]
    keys = {_key(v) for v in variants}
    hits = {p.id: p for p in kb.places if any(_key(t) in keys for t in _terms(p))}
    if len(hits) == 1:
        return next(iter(hits.values()))
    if hits or len(x.split()) > 1:
        return None
    for v in variants:
        place = kb.find_place(v)
        # вхождение засчитываем только целым словом: «theatre» не должно найти «eat» (кафе)
        if place is not None and any(
            v.startswith(t) or t.startswith(v)
            for t in (_norm(t) for t in _terms(place))
            if len(t) >= 3
        ):
            return place
    return None


def match_place_intent(text: str, kb: KnowledgeBase) -> PlaceIntent | None:
    """«Где кафе?», «Проводите меня до лифта», «Дәретхана қайда?», «Take me to the cafe» → место и язык.
    Несколько предложений, несколько запросов, неизвестное или неоднозначное место — None (решает агент)."""
    if not kb.places or len(text) > 120:
        return None
    sentences = [s for s in re.split(r"[.!?;]+", text) if _norm(s)]
    if not sentences:
        return None
    *before, last = sentences
    # «Здравствуйте! Где кафе?» — приветствие перед вопросом не мешает; два вопроса подряд — агенту
    if any(_strip_lead(_norm(s)) for s in before):
        return None
    phrase = _strip_lead(_norm(last))
    for kind, lang, pattern in _PATTERNS:
        m = pattern.match(phrase)
        if m is None:
            continue
        place = resolve_place(kb, m.group("x"), lang)
        if place is not None:
            return PlaceIntent(kind=kind, place=place, lang=lang)
    return None


def _strip_lead(phrase: str) -> str:
    while True:
        m = _LEAD.match(phrase + " ")
        if m is None:
            return phrase
        phrase = phrase[m.end() :].strip()
        if not phrase:
            return ""


# ---------- согласие проводить ----------
_YES = frozenset(
    [
        "да",
        "давай",
        "давайте",
        "ага",
        "ок",
        "окей",
        "хорошо",
        "конечно",
        "ладно",
        "поехали",
        "пошли",
        "пойдем",
        "пойдемте",
        "идем",
        "идемте",
        "веди",
        "ведите",
        "проводи",
        "проводите",
        "отведи",
        "отведите",
        "проведи",
        "проведите",
        "можно",
        "иә",
        "ия",
        "иа",
        "жарайды",
        "мақұл",
        "болады",
        "жүр",
        "жүрейік",
        "жүріңіз",
        "апар",
        "апарыңыз",
        "апарыңызшы",
        "барайық",
        "әрине",
        "yes",
        "yeah",
        "yep",
        "sure",
        "ok",
        "okay",
        "go",
        "lead",
        "take",
        "guide",
        "please",
        "lets",
        "let's",
    ]
)
_YES_FILLER = frozenset(
    [
        "туда",
        "там",
        "меня",
        "нас",
        "мне",
        "ну",
        "тогда",
        "пожалуйста",
        "робот",
        "вот",
        "и",
        "а",
        "же",
        "мені",
        "бізді",
        "сонда",
        "өтінемін",
        "ал",
        "me",
        "us",
        "there",
        "then",
        "ahead",
        "now",
        "please",
        "robot",
        "the",
        "way",
        "it",
        "do",
        "and",
    ]
)


def is_consent(text: str) -> bool:
    """Короткое «да» на предложение проводить: «да», «давайте», «да, проводите меня», «иә», «yes please».
    «Нет», «не надо» и фразы с новым местом — не согласие."""
    words = [w for w in _norm(text).split() if w not in _YES_FILLER]
    return 0 < len(words) <= 3 and all(w in _YES for w in words)


# ---------- ответ ----------
_ORD_RU = (
    "первом",
    "втором",
    "третьем",
    "четвёртом",
    "пятом",
    "шестом",
    "седьмом",
    "восьмом",
    "девятом",
)
_ORD_KK = (
    "бірінші",
    "екінші",
    "үшінші",
    "төртінші",
    "бесінші",
    "алтыншы",
    "жетінші",
    "сегізінші",
    "тоғызыншы",
)
_ORD_EN = ("first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth")
# Описание места говорит, как пройти: тогда where_is отвечает его словами
_DIRECTION = {
    "ru": re.compile(
        r"\bна \w+ этаже\b|\bсправа\b|\bслева\b|\bпрямо по\b|\bнаправо\b|\bналево\b|\bнапротив\b|"
        r"\bрядом с\b|\bвозле\b|\bу входа\b|\bот входа\b|\bв конце\b|\bпо коридору\b",
        re.IGNORECASE,
    ),
    "kk": re.compile(
        r"\bқабатта\b|\bоң жақта\b|\bсол жақта\b|\bтура\b|\bқарсы\b|\bжанында\b|\bкіреберіс",
        re.IGNORECASE,
    ),
    "en": re.compile(
        r"\bon the \w+ floor\b|\bon your (?:left|right)\b|\bto the (?:left|right)\b|\bstraight ahead\b|"
        r"\bopposite\b|\bnext to\b|\bnear the\b|\bby the entrance\b|\bend of the\b",
        re.IGNORECASE,
    ),
}


def _display(name: str, lang: Lang) -> str:
    """Название посреди фразы: «кафе», «дәріс залы», «lecture hall», но «TikTok-пространство»."""
    if (
        len(name) > 1
        and name[1:].islower()
        and not any("a" <= ch <= "z" for ch in name[1:].lower())
    ):
        return name[0].lower() + name[1:]
    if lang == "en" and len(name) > 1 and name[1:].islower():
        return name.lower()
    return name


def _cap(text: str) -> str:
    return text[:1].upper() + text[1:]


_RU_INDECLINABLE_O = ("кино", "метро", "бюро", "казино", "депо", "радио", "фото", "видео", "лобби")


def _ru_genitive(name: str) -> str | None:
    """Родительный падеж одного слова для «до …»: лифт → лифта, лекторий → лектория, кафе → кафе.
    Не уверены (мягкий знак, множественное число, несколько слов) — None."""
    head, sep, word = name.rpartition("-") if "-" in name else ("", "", name)
    if not word or " " in word or " " in head:
        return None
    low = word.lower()
    if low.endswith(("ие", "ье")):
        out = word[:-1] + "я"
    elif low.endswith("е") or low.endswith(_RU_INDECLINABLE_O):  # кафе, кофе, фойе, кино
        out = word
    elif low.endswith("ий"):
        out = word[:-2] + "ия"
    elif low.endswith("й"):
        out = word[:-1] + "я"
    elif low.endswith("о"):
        out = word[:-1] + "а"
    elif low.endswith("а"):
        out = word[:-1] + ("и" if low[-2:-1] in "гкхжчшщ" else "ы")
    elif low.endswith("я"):
        out = word[:-1] + "и"
    elif low[-1:] in "бвгдзклмнпрстфхжчшщц":
        out = word + "а"
    else:
        return None
    return f"{head}{sep}{out}"


_KK_BACK = set("аоұыя")
_KK_FRONT = set("әеөүіэи")
_KK_VOWELS = _KK_BACK | _KK_FRONT | set("уюё")
_KK_HARD = set("кқпстфхцчшщбвгд")


def _kk_dative(name: str) -> str | None:
    """Дательный падеж для «… апарып саламын»: кафе → кафеге, лифт → лифтке, дәріс залы → дәріс залына,
    TikTok → TikTok-қа."""
    word = name.rpartition(" ")[2].rpartition("-")[2].lower()
    if not word:
        return None
    if all("a" <= ch <= "z" for ch in word):  # латиница: по последней гласной, через дефис
        vowels = [ch for ch in word if ch in "aeiouy"]
        front = bool(vowels) and vowels[-1] in "ei"
        hard = word[-1] in "kptsfhcqxbdg"
        return f"{name}-" + (("ке" if hard else "ге") if front else ("қа" if hard else "ға"))
    vowels = [ch for ch in word if ch in _KK_BACK | _KK_FRONT]
    if not vowels:
        return None
    front = vowels[-1] in _KK_FRONT
    last = word[-1]
    if " " in name and last in "ыі":  # тәуелдік жалғау: «дәріс залы» → «залына»
        return name + ("не" if front else "на")
    if last in _KK_HARD:
        return name + ("ке" if front else "қа")
    return name + ("ге" if front else "ға")


def _direction(place: Place, lang: Lang) -> list[str] | None:
    """Как пройти — словами из базы: предложение описания с «этаж», «справа», «қабат», «floor» и т. п.;
    иначе — этаж из поля floor."""
    desc = getattr(place.description, lang, "") or ""
    for sentence in re.split(r"(?<=[.!?])\s+", desc.strip()):
        if sentence and _DIRECTION[lang].search(sentence):
            return [sentence if sentence[-1] in ".!?" else sentence + "."]
    floor = place.floor
    if floor is not None and 1 <= floor <= 9:
        name = place.names.get(lang)
        if lang == "ru":
            return [f"{_cap(name)} на {_ORD_RU[floor - 1]} этаже."]
        if lang == "kk":
            return [f"{_cap(name)} {_ORD_KK[floor - 1]} қабатта."]
        return [f"The {_display(name, 'en')} is on the {_ORD_EN[floor - 1]} floor."]
    return None


_OFFER = {"ru": "Проводить вас?", "kk": "Апарып салайын ба?", "en": "Want me to take you there?"}
# Предложение проводить внутри фразы робота (WayPhrases.offerGuide) — сервер ставит своё «Проводить вас?»
_ROBOT_OFFERS = frozenset(
    _norm(s)
    for s in ("Могу проводить.", "Апарып салайын ба?", "I can take you there.", *_OFFER.values())
)


def direction_phrase(directions: dict[str, dict[str, str]], place: Place, lang: Lang) -> str | None:
    """Фраза робота «где X» для места на языке lang (state.directions WS /v1/live): «Кафе — справа от вас,
    примерно в 15 метрах.» Ключ — place_id, как в action (Place.id); на всякий случай и `place:<id>`, map_name.
    Предложение проводить из фразы убирается: его добавляет ответ. Нет фразы — None."""
    phrases = next(
        (directions[k] for k in (place.id, place.source_id, place.map_name) if k in directions),
        None,
    )
    text = (phrases or {}).get(lang)
    if not text:
        return None
    sentences = [
        s
        for s in re.split(r"(?<=[.!?])\s+", text.strip())
        if _norm(s) and _norm(s) not in _ROBOT_OFFERS
    ]
    if not sentences:
        return None
    text = " ".join(sentences)
    return text if text[-1] in ".!?" else text + "."


def place_reply(
    intent: PlaceIntent, rng: random.Random | None = None, direction: str | None = None
) -> tuple[str, list[str]]:
    """(эмоция, фразы) ответа быстрого пути на языке реплики. direction — фраза робота о направлении от его
    позиции (direction_phrase): для where_is она важнее описания места и стрелки на экране."""
    rng = rng or _RNG
    lang, place = intent.lang, intent.place
    name = place.names.get(lang)
    shown = _display(name, lang)
    if intent.kind == "guide_to":
        return "excited", [guide_phrase(place, lang, rng)]
    if direction:
        return "happy", [direction, _OFFER[lang]]
    described = _direction(place, lang)
    if described:
        return "happy", [*described, _OFFER[lang]]
    if lang == "ru":
        first = rng.choice(
            [f"{_cap(shown)} — смотрите стрелку на экране.", f"Показала на экране, где {shown}."]
        )
    elif lang == "kk":
        first = rng.choice(
            [
                f"{_cap(shown)} — экрандағы көрсеткіге қараңыз.",
                f"{_cap(shown)} қайда екенін экранда көрсеттім.",
            ]
        )
    else:
        first = rng.choice(
            [
                f"The {shown} is this way, see my screen.",
                f"My screen shows the way to the {shown}.",
            ]
        )
    return "happy", [first, _OFFER[lang]]


def guide_phrase(place: Place, lang: Lang, rng: random.Random | None = None) -> str:
    """«Пойдёмте, провожу до кафе!» / «Жүріңіз, кафеге апарып саламын!» / «Sure, follow me to the cafe!»"""
    rng = rng or _RNG
    name = place.names.get(lang)
    shown = _display(name, lang)
    if lang == "ru":
        gen = _ru_genitive(shown)
        if gen is None:
            return rng.choice(["Пойдёмте, провожу!", "Конечно, идите за мной!"])
        return rng.choice([f"Пойдёмте, провожу до {gen}!", f"Конечно, идёмте до {gen}!"])
    if lang == "kk":
        dat = _kk_dative(shown)
        if dat is None:
            return "Жүріңіз, апарып саламын!"
        return rng.choice([f"Жүріңіз, {dat} апарып саламын!", f"Әрине, {dat} бірге барайық!"])
    return rng.choice(
        [f"Sure, follow me to the {shown}!", f"Let's go, I'll take you to the {shown}!"]
    )
