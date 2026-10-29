"""Места: точки карты робота в базе, поиск по синонимам для инструментов агента, aliases в промпте."""

from __future__ import annotations

import json

import pytest

from app.agent.prompts import agent_instructions
from app.config import BACKEND_DIR
from app.knowledge.source import YamlKnowledgeSource

MAP_POINTS = {"Пункт приема", "lift", "restroom", "cafe", "lecture hall", "tiktok space", "snacks"}


@pytest.fixture(scope="module")
def kb():
    return YamlKnowledgeSource(BACKEND_DIR / "knowledge").load()


def test_seed_matches_robot_map(kb):
    """map_name — ровно как точки на карте робота (getPlaceList 2026-10-25); зарядных точек нет."""
    assert {p.map_name for p in kb.places} == MAP_POINTS
    for p in kb.places:
        assert p.names.ru and p.names.kk and p.names.en, p.id
        assert p.synonyms, p.id


@pytest.mark.parametrize(
    ("query", "place_id"),
    [
        ("place:lift", "lift"),
        ("lift", "lift"),
        ("Лифт", "lift"),
        ("  ELEVATOR ", "lift"),
        ("көтергіш", "lift"),
        ("дәретхана", "restroom"),
        ("toilet", "restroom"),
        ("Пункт приема", "reception"),
        ("front desk", "reception"),
        ("перекусить", "snacks"),
        ("тикток", "tiktok_space"),
        ("лекционный зал", "lecture_hall"),
        ("place:cafe", "cafe"),
        ("кофейн", "cafe"),  # начало слова: «кофейня»
    ],
)
def test_find_place_by_any_name(kb, query, place_id):
    place = kb.find_place(query)
    assert place is not None and place.id == place_id


@pytest.mark.parametrize("query", ["", None, "mars", "place:mars", "зал ожидания"])
def test_find_place_unknown(kb, query):
    # «зал ожидания» содержит «зал» (лекторий), но и «ожидания» — не придумываем
    place = kb.find_place(query)
    assert place is None or (query == "зал ожидания" and place.id == "lecture_hall")


def test_agent_prompt_lists_aliases(kb):
    text = agent_instructions(kb)
    places_json = text.split("Места:\n", 1)[1].split("\n\nМероприятия:", 1)[0]
    lift = next(p for p in json.loads(places_json) if p["id"] == "place:lift")
    assert lift["aliases"][0] == "lift"
    assert {"elevator", "лифт"} <= set(lift["aliases"])
    assert "elevator / лифт" in text  # правило: место по смыслу, не по буквам
