"""Быстрый путь мест (app/agent/fastpath.py): что считается явным запросом места и как звучит ответ."""

from __future__ import annotations

import random
from pathlib import Path

import pytest

from app.agent.fastpath import (
    _kk_dative,
    _ru_genitive,
    is_consent,
    match_place_intent,
    place_reply,
)
from app.config import BACKEND_DIR
from app.knowledge.models import KnowledgeBase, LocalizedText, Place
from app.knowledge.source import YamlKnowledgeSource


@pytest.fixture(scope="module")
def kb() -> KnowledgeBase:
    return YamlKnowledgeSource(Path(BACKEND_DIR / "knowledge")).load()


@pytest.mark.parametrize(
    ("text", "kind", "place", "lang"),
    [
        ("Где кафе?", "where_is", "cafe", "ru"),
        ("Где здесь туалет?", "where_is", "restroom", "ru"),
        ("Здравствуйте! Подскажите, пожалуйста, где находится лифт?", "where_is", "lift", "ru"),
        ("Где можно поесть?", "where_is", "cafe", "ru"),
        ("Как пройти к лекторию?", "where_is", "lecture_hall", "ru"),
        ("А кафе где?", "where_is", "cafe", "ru"),
        ("Проводите меня до кафе", "guide_to", "cafe", "ru"),
        ("Проводи в лекторий", "guide_to", "lecture_hall", "ru"),
        ("Можете проводить меня к лифту?", "guide_to", "lift", "ru"),
        ("Отведите меня на ресепшен", "guide_to", "reception", "ru"),
        ("Покажите дорогу до туалета", "guide_to", "restroom", "ru"),
        ("Проводите меня в TikTok-пространство", "guide_to", "tiktok_space", "ru"),
        ("Дәретхана қайда?", "where_is", "restroom", "kk"),
        ("Кафе қай жерде орналасқан?", "where_is", "cafe", "kk"),
        ("Асхана қайда?", "where_is", "cafe", "kk"),
        ("Мені кафеге апарыңызшы", "guide_to", "cafe", "kk"),
        ("Лифтке апарып салыңыз", "guide_to", "lift", "kk"),
        ("Дәріс залына дейін апарыңыз", "guide_to", "lecture_hall", "kk"),
        ("TikTok-ке апарыңыз", "guide_to", "tiktok_space", "kk"),
        ("Where is the cafe?", "where_is", "cafe", "en"),
        ("Where's the restroom?", "where_is", "restroom", "en"),
        ("Where are the toilets?", "where_is", "restroom", "en"),
        ("How do I get to the lecture hall?", "where_is", "lecture_hall", "en"),
        ("Take me to the elevator please", "guide_to", "lift", "en"),
        ("Can you take me to reception?", "guide_to", "reception", "en"),
    ],
)
def test_place_requests_are_recognized(kb, text, kind, place, lang):
    intent = match_place_intent(text, kb)
    assert intent is not None, text
    assert (intent.kind, intent.place.id, intent.lang) == (kind, place, lang)


@pytest.mark.parametrize(
    "text",
    [
        "Где кафе и сколько стоит кофе?",  # второй вопрос
        "Где туалет на втором этаже?",
        "Проводите меня туда",  # «туда» — из разговора, решает агент
        "Где он?",
        "Где проходит лекция?",
        "Where is the theatre?",  # «eat» внутри слова — не кафе
        "Где кафе? И где туалет?",
        "Что такое Astana Hub?",
        "Я хочу в кафе",
        "Где зал ожидания?",
        "",
    ],
)
def test_doubtful_requests_go_to_agent(kb, text):
    assert match_place_intent(text, kb) is None


def test_ambiguous_alias_goes_to_agent():
    kb = KnowledgeBase(
        places=[
            Place(id="a", map_name="a", names=LocalizedText(ru="Зал А"), synonyms=["зал"]),
            Place(id="b", map_name="b", names=LocalizedText(ru="Зал Б"), synonyms=["зал"]),
        ]
    )
    assert match_place_intent("Где зал?", kb) is None


def test_replies_are_short_and_in_the_language_of_the_request(kb):
    rng = random.Random(0)
    for text, lang, expected in [
        ("Где кафе?", "ru", "Проводить вас?"),
        ("Дәретхана қайда?", "kk", "Апарып салайын ба?"),
        ("Where is the cafe?", "en", "Want me to take you there?"),
    ]:
        intent = match_place_intent(text, kb)
        assert intent is not None and intent.lang == lang
        emotion, phrases = place_reply(intent, rng)
        assert emotion == "happy" and phrases[-1] == expected
        assert len(phrases[0].split()) <= 9  # первая фраза короткая: TTS начинает сразу
        assert not any(m in " ".join(phrases) for m in ("…", "...", "ммм", "Хм"))
    intent = match_place_intent("Проводите меня до лифта", kb)
    assert intent is not None
    emotion, [phrase] = place_reply(intent, rng)
    assert emotion == "excited" and "до лифта!" in phrase
    intent = match_place_intent("Мені кафеге апарыңызшы", kb)
    assert intent is not None
    assert "кафеге" in place_reply(intent, rng)[1][0]


def test_where_is_uses_direction_from_description():
    place = Place(
        id="cafe",
        map_name="cafe",
        names=LocalizedText(ru="Кафе", kk="Кафе", en="Cafe"),
        description=LocalizedText(
            ru="Кафе на первом этаже, справа от входа. Кофе и выпечка.",
            en="The cafe is on the first floor, to the right of the entrance.",
        ),
    )
    kb = KnowledgeBase(places=[place])
    intent = match_place_intent("Где кафе?", kb)
    assert intent is not None
    assert place_reply(intent)[1] == ["Кафе на первом этаже, справа от входа.", "Проводить вас?"]
    lift = Place(id="lift", map_name="lift", names=LocalizedText(ru="Лифт"), floor=2)
    intent = match_place_intent("Где лифт?", KnowledgeBase(places=[lift]))
    assert intent is not None
    assert place_reply(intent)[1][0] == "Лифт на втором этаже."


@pytest.mark.parametrize(
    ("name", "gen"),
    [
        ("лифт", "лифта"),
        ("лекторий", "лектория"),
        ("кафе", "кафе"),
        ("ресепшен", "ресепшена"),
        ("TikTok-пространство", "TikTok-пространства"),
        ("Снеки", None),
        ("лекционный зал", None),
    ],
)
def test_ru_genitive(name, gen):
    assert _ru_genitive(name) == gen


@pytest.mark.parametrize(
    ("name", "dat"),
    [
        ("кафе", "кафеге"),
        ("лифт", "лифтке"),
        ("дәретхана", "дәретханаға"),
        ("қабылдау", "қабылдауға"),
        ("дәріс залы", "дәріс залына"),
        ("TikTok кеңістігі", "TikTok кеңістігіне"),
        ("TikTok", "TikTok-қа"),
    ],
)
def test_kk_dative(name, dat):
    assert _kk_dative(name) == dat


@pytest.mark.parametrize(
    ("text", "yes"),
    [
        ("да", True),
        ("Да, проводите меня", True),
        ("давайте", True),
        ("иә", True),
        ("yes please", True),
        ("let's go", True),
        ("нет", False),
        ("да нет", False),
        ("да, а где туалет", False),
        ("не надо", False),
    ],
)
def test_consent(text, yes):
    assert is_consent(text) is yes
