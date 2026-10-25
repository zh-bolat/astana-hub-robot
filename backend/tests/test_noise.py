"""Детектор шума распознавания (docs/api-contract.md, «Шум вместо реплики (v1.2)»)."""

from __future__ import annotations

import pytest

from app.noise import is_noise, needs_history


@pytest.mark.parametrize(
    "text",
    [
        "Ну",
        "Ну...",
        "М-м-м",
        "ммм",
        "Эээ, ну",
        "(смеётся)",
        "[музыка]",
        "Смеётся.",
        "*кашляет*",
        "Хахаха",
        "хи-хи",
        "Ой",
        "Ну вот",
        "Это, ну",
        "Әй",
        "Um, uh",
        "Hmm",
        "(laughs) haha",
        "Laughter",
        "пфф",
        "...",
        "?",
        "Да, я.",
        "М-м-м, не-не-не",
        "Угу",
    ],
)
def test_noise(text):
    assert is_noise(text) is True


@pytest.mark.parametrize(
    "text",
    [
        "Привет",
        "Спасибо",
        "Кафе",
        "Где кафе?",
        "Ну где тут туалет?",
        "Давайте",
        "Ок",
        "WC",
        "IT",
        "3",
        "Этаж 2",
        "Рахмет",
        "Кофе қайда?",
        "Hello",
        "Coffee",
        "Wi-Fi",
        "(смеётся) Где кафе?",
    ],
)
def test_not_noise(text):
    assert is_noise(text) is False


@pytest.mark.parametrize("text", ["Да", "Нет", "Иә", "Жоқ", "Yes", "No", "Ага", "Да, да"])
def test_yes_no_is_answer_after_robot_question(text):
    assert needs_history(text)
    assert is_noise(text, last_agent="Кафе на первом этаже. Проводить вас?") is False
    assert is_noise(text, last_agent="Кафе на первом этаже.") is True
    assert is_noise(text) is True


def test_needs_history_only_for_yes_no():
    assert not needs_history("Ну")
    assert not needs_history("(смеётся)")
    assert needs_history("Ну да")
