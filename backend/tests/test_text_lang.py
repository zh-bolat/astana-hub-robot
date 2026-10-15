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
    assert resolve_lang("en", "ru", "Где кафе?") == "en"
    assert resolve_lang(None, "kk", "Кофе бар?") == "kk"
    assert resolve_lang(None, "xx", "Where?") == "en"
    assert resolve_lang(None, None, "Қайда?") == "kk"


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
