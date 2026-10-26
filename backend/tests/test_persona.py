"""Характер робота: мужской род, живые готовые фразы, спокойный промпт (app/agent/prompts.py, fastpath, smalltalk).

Модель здесь не вызывается: тон ответов модели проверяет eval/persona_questions.yaml на реальной модели.
"""

from __future__ import annotations

import random
import re
from datetime import datetime
from pathlib import Path

import pytest
import yaml

from app.agent.fastpath import OFFERS, PlaceIntent, is_consent, place_reply
from app.agent.prompts import (
    GREETING_STYLES,
    GREETINGS,
    LOOKING,
    agent_greeting_turn,
    agent_instructions,
    ready_greeting,
)
from app.config import BACKEND_DIR
from app.knowledge.models import KnowledgeBase, LocalizedText, Place
from app.knowledge.source import YamlKnowledgeSource
from app.prompts import REFUSALS
from app.smalltalk import _REPLIES
from app.text import split_phrases
from eval.run_eval import _check as check_question

LANGS = ("ru", "kk", "en")
# Робот говорит о себе в мужском роде: женские формы глаголов и прилагательных о себе — ошибка
FEMININE = re.compile(
    r"(?<!\w)(показала|рада|расслышала|нашла|поняла|готова|провела|проводила|посмотрела|увидела|"
    r"сама|должна|смогла|ошиблась|запомнила|заряжена)(?!\w)",
    re.IGNORECASE,
)
FILLERS = re.compile(r"(?<!\w)(хм+|м{2,}|э-э|ну|um+|uh+|hmm+)(?!\w)|…|\.\.\.", re.IGNORECASE)
# Капс в правилах заставлял Haiku рассуждать вслух; допустимы только заголовки разделов и «ДАННЫЕ»
CAPS_ALLOWED = {"БАЗЫ", "ЗНАНИЙ", "БАЗА", "БЕЗОПАСНОСТЬ", "ДАННЫЕ"}


@pytest.fixture(scope="module")
def kb() -> KnowledgeBase:
    return YamlKnowledgeSource(Path(BACKEND_DIR / "knowledge")).load()


def _rules(kb: KnowledgeBase, mode: str = "open", retrieval: str = "prefetch") -> str:
    return agent_instructions(kb, mode, retrieval).split("БАЗА ЗНАНИЙ (версия")[0]


@pytest.mark.parametrize("mode", ["open", "strict"])
@pytest.mark.parametrize("retrieval", ["off", "tool", "prefetch"])
def test_prompt_is_masculine_playful_and_calm(kb, mode, retrieval):
    rules = _rules(kb, mode, retrieval)
    assert "мужском роде" in rules and "женском" not in rules
    assert "игривый" in rules and "энергичный" in rules
    assert not FEMININE.search(rules), FEMININE.search(rules)
    caps = set(re.findall(r"\b[А-ЯЁ]{4,}\b", rules))
    assert caps <= CAPS_ALLOWED, caps - CAPS_ALLOWED
    # прежние правила на месте
    for rule in ("no_answer", "guide_to", "[sad]", "<visitor_message>", "По-казахски"):
        assert rule in rules
    assert "Ремарок" in rules and "*смеётся*" in rules  # действия текстом не пишем
    assert "«Ха-ха!»" in rules and "«Haha!»" in rules  # смех — коротким словом, к месту
    assert "раза за разговор" in rules


def test_greeting_turn_uses_one_of_the_styles():
    turn = agent_greeting_turn("ru", None, None, datetime(2026, 10, 26, 10))
    assert "энергично" in turn
    assert any(style in turn for style in GREETING_STYLES)


def _ready_phrases() -> list[tuple[str, str]]:
    out = [(lang, g) for lang in LANGS for g in GREETINGS[lang]]
    out += [(lang, LOOKING[lang]) for lang in LANGS]
    out += [(lang, REFUSALS[lang]) for lang in LANGS]
    out += [(lang, reply[lang]) for reply in _REPLIES.values() for lang in LANGS]
    out += [(lang, offer) for lang in LANGS for offer in OFFERS[lang]]
    return out


@pytest.mark.parametrize(("lang", "text"), _ready_phrases())
def test_ready_phrases_are_masculine_short_and_clean(lang, text):
    assert not FEMININE.search(text)
    assert not FILLERS.search(text)
    first = split_phrases(text)[0]
    assert len(first.split()) <= 8, first  # первая фраза короткая: звук начинается сразу


def test_ready_greetings_are_different():
    for lang in LANGS:
        assert len(set(GREETINGS[lang])) >= 3
        seen = {ready_greeting(lang, random.Random(seed)) for seed in range(30)}
        assert seen == set(GREETINGS[lang])


class _Nth:
    """rng, который всегда берёт i-й вариант шаблона (или последний, если вариантов меньше)."""

    def __init__(self, i: int) -> None:
        self.i = i

    def choice(self, seq):
        return seq[min(self.i, len(seq) - 1)]


# Место без описания и этажа: where_is отвечает шаблоном про экран
CAFE = Place(id="cafe", map_name="cafe", names=LocalizedText(ru="Кафе", kk="Кафе", en="Cafe"))
LIFT = Place(id="lift", map_name="lift", names=LocalizedText(ru="Лифт", kk="Лифт", en="Elevator"))
HALL = Place(
    id="hall",
    map_name="hall",
    names=LocalizedText(ru="Лекционный зал", kk="Дәріс залы", en="Lecture hall"),
)


@pytest.mark.parametrize("variant", range(3))
@pytest.mark.parametrize("lang", LANGS)
@pytest.mark.parametrize("place", [CAFE, LIFT, HALL], ids=lambda p: p.id)
def test_fast_place_templates_every_variant(variant, lang, place):
    emotion, phrases = place_reply(PlaceIntent("where_is", place, lang), _Nth(variant))
    assert emotion == "happy" and phrases[-1] in OFFERS[lang]
    assert phrases[-1].endswith("?")  # на «да» сервер сразу провожает (_remember_offer)
    if lang != "en":
        assert "экран" in phrases[0].lower()
    emotion, [guide] = place_reply(PlaceIntent("guide_to", place, lang), _Nth(variant))
    assert emotion == "excited"
    for text in [*phrases, guide]:
        assert not FEMININE.search(text) and not FILLERS.search(text), text
        assert len(split_phrases(text)[0].split()) <= 9, text


def test_where_is_template_is_masculine():
    _, phrases = place_reply(PlaceIntent("where_is", CAFE, "ru"), _Nth(0))
    assert phrases[0] == "Показал на экране, где кафе!"


@pytest.mark.parametrize(
    ("text", "yes"),
    [("хочу", True), ("да, хочу", True), ("хочу в кафе", False), ("не хочу", False)],
)
def test_consent_to_want_offer(text, yes):
    assert is_consent(text) is yes


# ---------- набор характера для реальной модели (eval/persona_questions.yaml) ----------
PERSONA_SET = Path(BACKEND_DIR / "eval" / "persona_questions.yaml")


def _persona_set() -> dict:
    return yaml.safe_load(PERSONA_SET.read_text(encoding="utf-8"))


def test_persona_set_is_well_formed():
    data = _persona_set()
    questions = data["questions"]
    assert 20 <= len(questions) <= 25
    assert len({q["id"] for q in questions}) == len(questions)
    by_lang = {lang: sum(q["lang"] == lang for q in questions) for lang in LANGS}
    assert by_lang["kk"] >= 5 and by_lang["en"] >= 4
    for q in questions:
        assert {"id", "lang", "question", "expect_found", "tone"} <= set(q), q["id"]
        assert q["question"] or q.get("event") == "visitor_arrived", q["id"]
        assert set(q.get("expect_actions", [])) <= {"where_is", "guide_to", "look"}
        for pattern in q.get("forbid", []):
            re.compile(pattern)
    for pattern in data["forbid_all"]:
        re.compile(pattern)
    ids = " ".join(q["id"] for q in questions)
    for kind in (
        "greet",
        "smalltalk",
        "fact",
        "where",
        "guide",
        "unknown",
        "general",
        "lost",
        "sad",
    ):
        assert kind in ids, kind
    assert "injection" in ids


def test_persona_checks_catch_feminine_remarks_and_wrong_action():
    rules = {"forbid_all": _persona_set()["forbid_all"], "first_phrase_max_words": 10}
    q = {"id": "x", "lang": "ru", "question": "Где кафе?", "expect_found": True}
    q["expect_actions"] = ["where_is"]

    def reasons(answer: str, actions: list[str]) -> list[str]:
        res = {
            "answer": answer,
            "phrases": split_phrases(answer),
            "actions": actions,
            "found": True,
            "lang": "ru",
            "sources": [],
        }
        return check_question(q, 200, res, 1, rules)["reasons"]

    assert reasons("Показал на экране, куда идти. Проводить вас?", ["where_is"]) == []
    assert reasons("Показала на экране, куда идти.", ["where_is"])
    assert reasons("*смеётся* Кафе справа.", ["where_is"])
    assert reasons("Хм, кафе справа.", ["where_is"])
    assert reasons("Кафе справа.", ["guide_to"]) == ["actions=['guide_to']"]
    long_first = "Кафе находится справа от вас примерно в пятнадцати метрах от входа в павильон."
    assert reasons(long_first, ["where_is"]) == ["first phrase 13 words"]
