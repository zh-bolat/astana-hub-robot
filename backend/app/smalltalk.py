"""Small talk: быстрые локальные ответы на самые частые реплики (без вызова LLM).

Остальной small talk («ты слышишь?», «что ты умеешь?» и т.п. в любой формулировке) распознаёт LLM
(категория small_talk в JSON-ответе). Здесь — только точные короткие реплики, где ошибиться нельзя.
"""

from __future__ import annotations

import re

from app.lang import Lang

ABOUT: dict[str, str] = {
    "ru": "Я робот-помощник Astana Hub. Могу рассказать о хабе, подсказать, где что находится, "
    "проводить до нужного места или сказать, что передо мной.",
    "kk": "Мен Astana Hub роботы-көмекшісімін. Хаб туралы айтып, қай жерде не бар екенін көрсетіп, "
    "керек жерге дейін шығарып салып, алдымда не тұрғанын айта аламын.",
    "en": "I'm the Astana Hub robot assistant. I can tell you about the hub, show where things are, "
    "walk you there, or tell you what I see in front of me.",
}

_REPLIES: dict[str, dict[str, str]] = {
    "greeting": {
        "ru": "Здравствуйте! " + ABOUT["ru"] + " Чем помочь?",
        "kk": "Сәлеметсіз бе! " + ABOUT["kk"] + " Қалай көмектесейін?",
        "en": "Hello! " + ABOUT["en"] + " How can I help?",
    },
    "thanks": {
        "ru": "Пожалуйста! Обращайтесь, если будут ещё вопросы.",
        "kk": "Оқасы жоқ! Тағы сұрақ болса, хабарласыңыз.",
        "en": "You're welcome! Ask me anytime.",
    },
    "bye": {
        "ru": "До свидания! Хорошего дня в Astana Hub.",
        "kk": "Сау болыңыз! Astana Hub-та күніңіз сәтті өтсін.",
        "en": "Goodbye! Have a great day at Astana Hub.",
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
