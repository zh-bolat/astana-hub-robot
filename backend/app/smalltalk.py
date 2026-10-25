"""Small talk: быстрые локальные ответы на самые частые реплики (без вызова LLM).

Остальной small talk («ты слышишь?», «что ты умеешь?» и т.п. в любой формулировке) распознаёт LLM
(категория small_talk в JSON-ответе). Здесь — только точные короткие реплики, где ошибиться нельзя.
"""

from __future__ import annotations

import re

from app.lang import Lang

# Коротко и тепло, без перечня умений («что ты умеешь?» отвечает LLM)
_REPLIES: dict[str, dict[str, str]] = {
    "greeting": {
        "ru": "Здравствуйте! Я робот Astana Hub, что подсказать?",
        "kk": "Сәлеметсіз бе! Мен Astana Hub роботымын, қалай көмектесейін?",
        "en": "Hello! I'm the Astana Hub robot, what can I help with?",
    },
    "thanks": {
        "ru": "Пожалуйста, обращайтесь!",
        "kk": "Оқасы жоқ, келіп тұрыңыз!",
        "en": "You're welcome, anytime!",
    },
    "bye": {
        "ru": "До свидания! Хорошего дня!",
        "kk": "Сау болыңыз! Күніңіз сәтті өтсін!",
        "en": "Goodbye! Have a great day!",
    },
}

_PHRASES: dict[str, set[str]] = {
    "greeting": {
        "привет",
        "здравствуй",
        "здравствуйте",
        "добрый день",
        "доброе утро",
        "добрый вечер",
        "салам",
        "сәлем",
        "сәлеметсіз бе",
        "сәлеметсіз",
        "армысыз",
        "қайырлы күн",
        "қайырлы таң",
        "hello",
        "hi",
        "hey",
        "good morning",
        "good afternoon",
        "good evening",
        "привет робот",
        "hello robot",
        "hi robot",
        "сәлем робот",
    },
    "thanks": {
        "спасибо",
        "спасибо большое",
        "благодарю",
        "рахмет",
        "көп рахмет",
        "рақмет",
        "көп рақмет",
        "thanks",
        "thank you",
        "thank you very much",
    },
    "bye": {
        "пока",
        "до свидания",
        "всего доброго",
        "сау бол",
        "сау болыңыз",
        "көріскенше",
        "bye",
        "goodbye",
        "see you",
    },
}

_NORM = re.compile(r"[^\w\s-]+", re.UNICODE)


def normalize(text: str) -> str:
    return " ".join(_NORM.sub(" ", text.lower()).split())


def quick_reply(text: str, lang: Lang) -> str | None:
    """Готовый ответ для точной короткой реплики или None (тогда решает LLM)."""
    norm = normalize(text)
    for kind, phrases in _PHRASES.items():
        if norm in phrases:
            return _REPLIES[kind][lang]
    return None
