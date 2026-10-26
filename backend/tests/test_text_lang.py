from __future__ import annotations

import pytest

from app.lang import detect_lang, resolve_lang
from app.text import clean_for_tts, split_phrases


@pytest.mark.parametrize(
    ("text", "lang"),
    [
        ("Где находится кафе?", "ru"),
        ("Где находится coworking?", "ru"),
        ("Какой пароль от Wi-Fi?", "ru"),
        ("Кафе қайда?", "kk"),
        ("Wi-Fi құпиясөзі қандай?", "kk"),
        ("Кофе бар ма?", "kk"),
        ("Сәлем!", "kk"),
        ("Where is the cafe?", "en"),
        ("Where is the кафе?", "en"),
        ("Astana Hub деген не?", "kk"),
        ("Astana Hub это что?", "ru"),
        ("123?", "ru"),
        ("", "ru"),
    ],
)
def test_detect_lang(text, lang):
    assert detect_lang(text) == lang


def test_resolve_lang_priority():
    # кириллица — никогда en, даже если робот или LLM сказали en
    assert resolve_lang("en", "ru", "Где кафе?") == "ru"
    assert resolve_lang(None, "en", "Ты слышишь?") == "ru"
    assert resolve_lang("en", "en", "Меня слышно?") == "ru"
    # кириллица без казахских букв: kk, если так сказали робот/LLM
    assert resolve_lang(None, "kk", "Кофе бар?") == "kk"
    assert resolve_lang("kk", None, "Кафе где?") == "kk"
    # казахские буквы/частицы — kk независимо от подсказок
    assert resolve_lang("ru", "ru", "Қайда?") == "kk"
    assert resolve_lang(None, "en", "Сен мені естисің бе?") == "kk"
    # латиница — en; kk только явно
    assert resolve_lang(None, "ru", "Can you hear me?") == "en"
    assert resolve_lang("kk", None, "Salem, qalaisyz?") == "kk"
    # нет букв / только бренды — робот, затем LLM, затем ru
    assert resolve_lang(None, "xx", "Where?") == "en"
    assert resolve_lang(None, "kk", "Astana Hub?") == "kk"
    assert resolve_lang(None, None, "?!") == "ru"


@pytest.mark.parametrize(
    ("text", "lang"),
    [
        ("Ты слышишь?", "ru"),
        ("Меня слышно?", "ru"),
        ("Привет", "ru"),
        ("Как дела?", "ru"),
        ("Сәлем", "kk"),
        ("Қалайсың?", "kk"),
        ("Hello", "en"),
        ("Can you hear me?", "en"),
        ("Привет, Astana Hub!", "ru"),
    ],
)
def test_short_phrases_lang(text, lang):
    assert detect_lang(text) == lang
    assert resolve_lang(None, "en" if lang != "en" else "ru", text) == lang


def test_split_sentences():
    text = "Первое предложение. Второе! Третье? Четвёртое…"
    assert split_phrases(text) == ["Первое предложение.", "Второе!", "Третье?", "Четвёртое…"]


def test_split_long_sentence_by_commas():
    long = ", ".join([f"часть номер {i} с текстом" for i in range(30)]) + "."
    phrases = split_phrases(long)
    assert len(phrases) > 1
    assert all(len(p) <= 300 for p in phrases)
    assert " ".join(phrases).replace(" ,", ",") == long


def test_split_long_without_punctuation():
    long = " ".join(["слово"] * 200)
    phrases = split_phrases(long)
    assert all(0 < len(p) <= 300 for p in phrases)
    assert " ".join(phrases) == long


def test_split_giant_token():
    phrases = split_phrases("x" * 700)
    assert [len(p) for p in phrases] == [300, 300, 100]


def test_split_empty():
    assert split_phrases("   ") == []


def test_clean_for_tts():
    raw = "## Ответ\n- **Кафе** на 1 этаже 😊\n- [Сайт](https://example.com) www.example.com"
    assert clean_for_tts(raw) == "Ответ Кафе на 1 этаже Сайт"


# ---------- ремарки и смех текстом (игривый характер робота): не озвучиваются и не показываются ----------
@pytest.mark.parametrize(
    ("raw", "clean"),
    [
        ("*смеётся* Конечно! Кафе — справа от вас.", "Конечно! Кафе — справа от вас."),
        ("Конечно! (улыбается) Кафе справа.", "Конечно! Кафе справа."),
        ("Кафе справа (подмигивает).", "Кафе справа."),
        ("Кафе справа, *машет рукой*, идёмте!", "Кафе справа, идёмте!"),
        ("*тихо смеётся*, конечно!", "Конечно!"),
        ("Конечно! *смеётся*. Кафе справа.", "Конечно! Кафе справа."),
        ("Ха-ха, хороший вопрос! Кафе справа.", "Хороший вопрос! Кафе справа."),
        ("Кафе справа. Хе-хе, проводить вас?", "Кафе справа. Проводить вас?"),
        ("Конечно, ха-ха.", "Конечно."),
        ("(с улыбкой) Здравствуйте!", "Здравствуйте!"),
        ("(күледі) Әрине! Кафе оң жақта.", "Әрине! Кафе оң жақта."),
        (
            "*laughs* Sure thing! The cafe is on your right.",
            "Sure thing! The cafe is on your right.",
        ),
        ("[playful] Sure! [winks]", "Sure!"),
        ("*beep boop* Hello there!", "Hello there!"),
        ("Hehe, hi! Welcome.", "Hi! Welcome."),
        ("*laughs*", ""),
    ],
)
def test_clean_for_tts_drops_stage_directions(raw, clean):
    assert clean_for_tts(raw) == clean


@pytest.mark.parametrize(
    ("raw", "clean"),
    [
        # скобки и звёздочки по делу — не ремарка: остаются (у разметки снимаются только звёздочки)
        (
            "Павильон (C4.6) рядом, кафе *на втором этаже*.",
            "Павильон (C4.6) рядом, кафе на втором этаже.",
        ),
        ("Astana Hub (EXPO) — **главный** IT-хаб.", "Astana Hub (EXPO) — главный IT-хаб."),
        (
            "Кафе справа. (Смотрите стрелку на экране.)",
            "Кафе справа. (Смотрите стрелку на экране.)",
        ),
        ("Hi! Welcome to the hub.", "Hi! Welcome to the hub."),
        (
            "Он хихикнул бы, но это кафе Global coffee.",
            "Он хихикнул бы, но это кафе Global coffee.",
        ),
    ],
)
def test_clean_for_tts_keeps_parentheses_with_content(raw, clean):
    assert clean_for_tts(raw) == clean
