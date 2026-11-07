"""Диагностика робота Курултая → журнал robot_diag (app/kurultai/diag.py)."""

from __future__ import annotations

import logging

import pytest
from pydantic import SecretStr

from app.kurultai.diag import MAX_STR, clean
from tests.conftest import AUTH

K_TOKEN = "kurultai-diag-token"
K_AUTH = {"Authorization": f"Bearer {K_TOKEN}"}


@pytest.fixture
def kclient(make_client):
    # приложение настраивает журнал при создании (снимает обработчики) — создаём его до caplog
    return make_client(kurultai_robot_token=SecretStr(K_TOKEN))


def test_diag_logged_for_kurultai(kclient, caplog):
    c = kclient
    body = {
        "version": "5.50-abc",
        "faces_max": 2,
        "api_ready": True,
        "last_motion": "x" * 1000,
        "nested": {"a": 1},
        "list": [1, 2],
    }
    with caplog.at_level(logging.DEBUG):
        r = c.post("/v1/robot/diag", json=body, headers=K_AUTH)
    assert r.status_code == 204
    rec = next(r for r in caplog.records if r.getMessage() == "robot_diag")
    assert rec.diag == {
        "version": "5.50-abc",
        "faces_max": 2,
        "api_ready": True,
        "last_motion": "x" * MAX_STR,
    }


def test_diag_only_kurultai_and_with_token(kclient):
    c = kclient
    assert c.post("/v1/robot/diag", json={}).status_code == 401
    assert c.post("/v1/robot/diag", json={}, headers=AUTH).status_code == 404  # токен Astana Hub
    assert c.post("/v1/robot/diag", content=b"not json", headers=K_AUTH).status_code == 400
    assert c.post("/v1/robot/diag", json={"x": "y" * 5000}, headers=K_AUTH).status_code == 413


def test_clean():
    assert clean([1, 2]) == {}
    assert clean({1: "a", "ok": None, "f": 1.5}) == {"ok": None, "f": 1.5}
    assert len(clean({f"k{i}": i for i in range(100)})) == 40
