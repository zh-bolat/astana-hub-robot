"""Нужен ли веб-поиск на реплику — порт isLatestNewsQuestion / needsWebSearch из MainActivity.java
приложения Курултая (android-app/app/src/main/java/kz/kurultai/assistant/MainActivity.java).

Вопросы о данных снимка (законопроекты, комитеты, депутаты, запросы) отвечаются по снимку quryltai.kz без поиска:
быстрее и дешевле. Поиск — для новостей, прошедших событий и расписания; общие слова «Курултай», «парламент»,
«сейчас» поиск не включают. Ключи — подстроки (как String.contains в Java), без учёта регистра.
"""

from __future__ import annotations

import re

_LATEST_NEWS_KEYS = (
    "вчера",
    "позавчера",
    "на прошлой неделе",
    "итог",
    "чем закончил",
    "кеше",
    "өткен апта",
    "нәтиже",
    "последн",
    "новых закон",
    "новые закон",
    "подписал",
    "қол қойды",
    "новост",
    "недавн",
    "актуальн",
    "сегодня",
    "на этой неделе",
    "повестк",
    "обсужда",
    "соңғы",
    "жаңалық",
    "бүгін",
    "өзекті",
    "күн тәртіб",
    "талқыла",
)
_SCHEDULE_KEYS = (
    "завтра",
    "недел",
    "мероприят",
    "запланир",
    "план",
    "заседан",
    "событи",
    "ивент",
    "ближайш",
    "через час",
    "в течение час",
    "ертең",
    "апта",
    "іс-шара",
    "іс шара",
    "жоспар",
    "отырыс",
    "оқиға",
    "жақын",
)


def is_latest_news_question(message: str | None) -> bool:
    text = (message or "").lower()
    return any(key in text for key in _LATEST_NEWS_KEYS)


def needs_web_search(message: str | None) -> bool:
    """Новости, прошедшие события или расписание — веб-поиск; остальное — по снимку quryltai.kz."""
    if is_latest_news_question(message):
        return True
    text = (message or "").lower()
    return any(key in text for key in _SCHEDULE_KEYS)


# Приветствия, «слышишь?», благодарность — без поиска: он добавляет 3–5 с к ответу, а искать тут нечего.
_GREETING_START = re.compile(
    r"^(привет|здравств|здраств|добр(ый|ое|ого)|салем|сәлем|сәлеметсіз|ассалау|салам|хай|hello|hi\b|"
    r"спасибо|рахмет|благодар|пока$|до свидания|сау бол|кто ты|ты кто|сен кімсің|алло|ау\b)",
    re.IGNORECASE,
)
_SMALLTALK_ANY = re.compile(
    r"(слышишь|слышно|естисің|естіп тұрсың|меня видишь|көріп тұрсың)", re.IGNORECASE
)


def is_smalltalk(message: str | None) -> bool:
    """Короткая реплика без вопроса по существу: приветствие, «слышишь?», «спасибо», «кто ты» (до 5 слов)."""
    text = re.sub(r"[^\w\s]", " ", (message or "").lower()).strip()
    if not text:
        return True
    if len(text.split()) > 5:
        return False
    return bool(_GREETING_START.match(text) or _SMALLTALK_ANY.search(text))
