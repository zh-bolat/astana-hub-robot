"""Вступления в начале ответа робота Курултая вырезаются (app/kurultai/openers.py)."""

from __future__ import annotations

import pytest

from app.kurultai.openers import strip_opener, visitor_greeted


@pytest.mark.parametrize(
    ("phrase", "expected"),
    [
        ("Здравствуйте!", ""),
        ("Здравствуйте! Заседание в 10:00.", "Заседание в 10:00."),
        ("Добрый день, комитетов восемь.", "Комитетов восемь."),
        ("Хороший вопрос! Комитетов восемь.", "Комитетов восемь."),
        ("Здравствуйте! Отличный вопрос. Законопроектов сорок.", "Законопроектов сорок."),
        ("Конечно, заседание завтра.", "Заседание завтра."),
        ("О, это интересно! Курултай создан в 2026 году.", "Курултай создан в 2026 году."),
        ("Сәлеметсіз бе! Отырыс ертең.", "Отырыс ертең."),
        ("Жақсы сұрақ! Комитет сегіз.", "Комитет сегіз."),
        ("Hello! There are eight committees.", "There are eight committees."),
    ],
)
def test_opener_removed(phrase, expected):
    assert strip_opener(phrase) == expected


@pytest.mark.parametrize(
    "phrase",
    [
        "Комитетов восемь.",
        "Конечное решение принимает Курултай.",
        "Приветственное слово скажет председатель.",
        "Сәлемдесу рәсімі сағат онда.",
        "Хорошие новости: закон подписан.",
    ],
)
def test_meaningful_start_kept(phrase):
    assert strip_opener(phrase) == phrase


@pytest.mark.parametrize(
    ("question", "phrase", "expected"),
    [
        ("Акылтай, привет", "Здравствуйте! Я Акылтай.", "Здравствуйте! Я Акылтай."),
        ("Сәлем, Ақылтай", "Сәлеметсіз бе! Тыңдап тұрмын.", "Сәлеметсіз бе! Тыңдап тұрмын."),
        (
            "Добрый день, робот, сколько комитетов?",
            "Добрый день! Комитетов восемь.",
            "Добрый день! Комитетов восемь.",
        ),
        ("Привет, Акылтай", "Хороший вопрос! Восемь.", "Восемь."),  # реакции — никогда
        ("Акылтай, сколько комитетов?", "Здравствуйте! Комитетов восемь.", "Комитетов восемь."),
    ],
)
def test_greeting_only_in_reply_to_greeting(question, phrase, expected):
    assert strip_opener(phrase, question) == expected


def test_visitor_greeted():
    assert visitor_greeted("Акылтай, привет")
    assert visitor_greeted("Ассалаумағалейкум")
    assert not visitor_greeted("Акылтай, расскажи про приветственное слово")
    assert not visitor_greeted("")
