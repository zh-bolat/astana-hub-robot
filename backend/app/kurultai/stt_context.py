"""Подсказка распознаванию речи в профиле kurultai (live, STT отрезками через OpenRouter / Gemini).

Ошибки распознавания у Курултая не сводятся к списку слов: модель слышит речь без контекста и неразборчивое место
дописывает по звуку. Подсказка говорит ей, где стоит робот и о чём его спрашивают, и даёт последний ответ робота —
уточняющий вопрос («а когда он вступит в силу?») распознаётся по смыслу разговора. Правило «не дописывать то, чего
не сказали» — в самом промпте STT (`app.speech.gemini_stt._context_block`).

Astana Hub подсказку не получает: она подключается только в сессиях профиля kurultai (`app/live/session.py`).
"""

from __future__ import annotations

# Ситуация и темы. Слова на кириллице — чтобы модель знала написание (в том числе казахское), а не как список
# обязательных терминов.
SETTING = (
    "A voice assistant robot in the building of the Kurultai (Құрылтай, Курултай) — the parliament of the "
    "Republic of Kazakhstan, Astana. Visitors, deputies and staff speak Russian or Kazakh, often mixing them, "
    "sometimes with English words. Typical topics: the Kurultai and its work, deputies, committees, sessions, "
    "draft laws (законопроект / заң жобасы), the Constitution, government bodies, deputy requests, "
    "news of Kazakhstan, Astana, also everyday small talk and questions to the robot itself. "
    "The robot's name is Ақылтай (in Russian: Акылтай); people call it by name or just «робот»."
)

# Последний ответ робота: хватает конца ответа — с ним связан следующий вопрос; длиннее — дороже и медленнее.
LAST_ANSWER_CHARS = 400


def stt_context(last_answer: str | None) -> str:
    """Подсказка для STT: ситуация + последний ответ робота (обрезан до LAST_ANSWER_CHARS с конца)."""
    answer = " ".join((last_answer or "").split())
    if len(answer) > LAST_ANSWER_CHARS:
        answer = "…" + answer[-LAST_ANSWER_CHARS:].lstrip()
    if not answer:
        return SETTING
    return f"{SETTING} The robot's previous answer: «{answer}»"
