"""Обращение к роботу Курултая: имя «Ақылтай» (как его записывает распознавание) или слово «робот»."""

from __future__ import annotations

import pytest

from app.kurultai.wake import is_addressed


@pytest.mark.parametrize(
    "text",
    [
        "Акылтай, где зал заседаний?",
        "Ақылтай",
        "акыл тай привет",
        "Ақылтайым, сәлем",
        "Aqyltai, hello",
        "Робот, сколько депутатов?",
        "Эй, робот!",
        "Спроси у робота",
        "Роботқа сұрақ қойайын",
        "Скажи, роботик, ты слышишь?",
        "Hey robot",
        "Ақыл",  # имя, обрезанное распознаванием
        "Акыл!",
    ],
)
def test_addressed(text):
    assert is_addressed(text)


@pytest.mark.parametrize(
    "text",
    [
        "",
        None,
        "Пойдём кофе попьём",
        "Когда пленарное заседание?",
        "У нас кружок робототехники",
        "Роботизация производства",
        "Работа депутатов",
        "Құрылтайда заң бар ма?",
        "Ақыл айтшы",  # «дай совет» — не имя
    ],
)
def test_not_addressed(text):
    assert not is_addressed(text)
