"""Команда остановки голосом (LIVE_BARGE_IN=stop_only, docs/api-contract.md «Шумный зал», v1.4)."""

from __future__ import annotations

import pytest

from app.live.stop import is_stop_command

STOPS = [
    "Стоп",
    "стоп-стоп",
    "Стоп, пожалуйста",
    "Стоп стоп стоп!",
    "Стооп",
    "Хватит",
    "Ну хватит уже",
    "Всё, хватит",
    "Достаточно, спасибо",
    "Замолчи",
    "Подождите, пожалуйста",
    "подожди секунду",
    "Робот, стоп",
    "стоп, я понял",
    "Тоқта",
    "Токта",
    "Тоқтаңызшы",
    "Жетеді",
    "Болды, болды",
    "Тыңда",
    "Күте тұрыңыз",
    "Stop",
    "Stop it",
    "stop please",
    "Enough",
    "That's enough",
    "Wait",
    "Wait a second",
    "OK stop",
    "Hold on",
]

NOT_STOPS = [
    "",
    "я не могу остановиться",
    "Не останавливайся",
    "Стоп, а где туалет?",
    "Мне не нравится, подожди, мне не нравится тут…",
    "Подожди, мне надо позвонить",
    "Хватит болтать с ним, пошли",
    "стоп-кран",
    "стоп 2",
    "Подскажите, пожалуйста",
    "Слушай, дай-ка",
    "всё правильно",
    "Что? Всё.",
    "Всё",
    "Алло",
    "Привет",
    "Где кафе?",
    "Wait, what?",
    "Stop by the cafe",
    "Ты по нему тапнул?",
    "(смеётся)",
]


@pytest.mark.parametrize("text", STOPS)
def test_stop_commands(text):
    assert is_stop_command(text)


@pytest.mark.parametrize("text", NOT_STOPS)
def test_not_stop_commands(text):
    assert not is_stop_command(text)
