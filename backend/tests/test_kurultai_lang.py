"""Язык ответа робота Курултая: язык экрана, голосом — только явная смена (app/kurultai/lang.py)."""

from __future__ import annotations

import pytest

from app.kurultai.lang import answer_lang
from app.kurultai.prompts import kurultai_turn
from app.lang import utterance_lang


@pytest.mark.parametrize(
    ("text", "screen", "expected"),
    [
        # русская речь с казахским написанием имён и названий — не казахский
        ("Ақылтай, расскажи про законы", "kk", "ru"),
        ("Ақылтай, какие законопроекты сейчас рассматриваются в Құрылтае?", "kk", "ru"),
        ("Как дела у Әшімбаева?", "kk", "ru"),
        ("Расскажи про цифровизацию и искусственный интеллект в Казахстане", "kk", "ru"),
        ("Сәлем, расскажи про Курултай", "kk", "ru"),
        # без единой казахской приметы — русская, даже при экране ҚАЗ (2026-11-07: «говорю по-русски — отвечает
        # по-казахски»)
        ("Законопроекты", "kk", "ru"),
        ("Депутаты Курултая", "kk", "ru"),
        ("Законопроекты рассматриваются", "kk", "ru"),
        ("Сколько депутатов в Мәжілісе?", "kk", "ru"),
        ("Алло", "kk", "ru"),
        # явный казахский
        ("Жарайды", "ru", "kk"),
        ("Сәлем", "ru", "kk"),
        ("Ақылтай, сәлем", "ru", "kk"),
        ("Құрылтайда цифровизация туралы заң бар ма?", "ru", "kk"),
        ("Заң жобалары туралы айтып берші", "ru", "kk"),
        ("Сен кімсің?", "ru", "kk"),
        # неясно — язык экрана
        ("Ақылтай", "ru", "ru"),
        ("Ақылтай", "kk", "kk"),
        ("Құрылтай", "ru", "ru"),
        ("Законопроекты", "ru", "ru"),
        ("Он екі депутат", "ru", "ru"),
        ("hello", "kk", "kk"),
        ("", None, "ru"),
    ],
)
def test_answer_lang(text, screen, expected):
    got = answer_lang(text, screen)
    assert got == expected
    assert answer_lang(text, got) == got  # повторный выбор (live → агент) не меняет язык


def test_turn_language_line_uses_screen_rule():
    from datetime import datetime

    turn = kurultai_turn("Ақылтай, расскажи про законы", "kk", datetime(2026, 11, 6, 12), False)
    assert "Язык ответа: ru (русский)" in turn


def test_hub_rule_unchanged():
    """Astana Hub — прежнее правило: казахская буква → kk."""
    assert utterance_lang("Ақылтай, расскажи про законы", "ru", "ru") == "kk"
