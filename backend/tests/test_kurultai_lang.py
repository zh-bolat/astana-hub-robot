"""Язык ответа робота Курултая: язык экрана, голосом — только явная смена (app/kurultai/lang.py)."""

from __future__ import annotations

import pytest

from app.kurultai.lang import answer_lang, wrong_language
from app.kurultai.prompts import kurultai_turn
from app.lang import utterance_lang


@pytest.mark.parametrize(
    "text",
    [
        "Ақылтай, расскажи про законы",
        "Сәлем",
        "Құрылтайда цифровизация туралы заң бар ма?",
        "Депутаты Курултая",
        "Сколько депутатов в Мәжілісе?",
        "hello",
        "",
    ],
)
@pytest.mark.parametrize("screen", ["ru", "kk"])
def test_answer_lang_is_screen_only(text, screen):
    """Язык ответа — только кнопка ҚАЗ|РУС (2026-11-07): текст реплики язык не меняет."""
    assert answer_lang(text, screen) == screen


def test_answer_lang_default_ru():
    assert answer_lang("Сәлем", None) == "ru"
    assert answer_lang("Сәлем", "en") == "ru"


def test_turn_language_line_uses_screen():
    from datetime import datetime

    turn = kurultai_turn("Ақылтай, расскажи про законы", "kk", datetime(2026, 11, 6, 12), False)
    assert "Язык ответа: kk (казахский)" in turn
    turn = kurultai_turn("Құрылтайда заң бар ма?", "ru", datetime(2026, 11, 6, 12), False)
    assert "Язык ответа: ru (русский)" in turn


@pytest.mark.parametrize(
    ("phrase", "lang", "wrong"),
    [
        ("Құрылтайда сегіз комитет жұмыс істейді.", "ru", True),
        ("В Құрылтае восемь комитетов.", "ru", False),  # название по-казахски — не смена языка
        ("Рахмет, всего доброго!", "ru", False),
        ("Рахмет!", "ru", False),  # короткое — не режем
        ("В Курултае сейчас работает восемь комитетов.", "kk", True),
        ("Құрылтайда сегіз комитет бар.", "kk", False),
        ("Курултай Казахстана.", "kk", False),  # короткое
    ],
)
def test_wrong_language(phrase, lang, wrong):
    assert wrong_language(phrase, lang) is wrong


def test_hub_rule_unchanged():
    """Astana Hub — прежнее правило: казахская буква → kk."""
    assert utterance_lang("Ақылтай, расскажи про законы", "ru", "ru") == "kk"
