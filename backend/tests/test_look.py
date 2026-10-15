from __future__ import annotations

import json
import logging

from app.llm.base import LLMAnswer
from tests.conftest import AUTH, JPEG, SESSION, FailingProvider, ScriptedProvider


def _meta(**extra) -> str:
    return json.dumps({"session_id": SESSION, **extra})


def _post(client, image: bytes | None = JPEG, meta: str | None = None, headers=AUTH):
    files = {}
    data = {}
    if image is not None:
        files["image"] = ("frame.jpg", image, "image/jpeg")
    if meta is not None:
        data["meta"] = meta
    return client.post("/v1/look", files=files or None, data=data or None, headers=headers)


def test_look_with_place(client):
    r = _post(client, meta=_meta(question="Что это за комната?", lang="ru", place_id="cafe"))
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["found"] is True
    assert body["lang"] == "ru"
    assert body["sources"] == ["place:cafe"]
    assert "кафе" in body["answer"].lower()
    assert body["phrases"]


def test_look_default_question_and_unknown_place(client):
    r = _post(client, meta=_meta(lang="en"))
    body = r.json()
    assert r.status_code == 200
    assert body["found"] is False
    assert "reception" in body["answer"].lower()
    row = client.app.state.log_repo.all()[-1]
    assert row.endpoint == "look"
    assert row.question == "What is this? Where am I?"
    assert row.found is False


def test_look_passes_image_and_place_to_vision(make_client):
    llm = ScriptedProvider(
        LLMAnswer(found=True, lang="ru", answer="Это коворкинг.", sources=["place:coworking_2f"])
    )
    c = make_client(vision_llm=llm)
    r = _post(c, meta=_meta(place_id="coworking_2f"))
    assert r.status_code == 200
    req = llm.requests[0]
    assert "place:coworking_2f" in req.user
    assert "длинными общими столами" in req.user  # visual hints текущего места
    assert req.image is None  # кадр отпущен после обработки
    assert r.json()["sources"] == ["place:coworking_2f"]


def test_look_requires_token(client):
    assert _post(client, meta=_meta(), headers={}).status_code == 401


def test_look_too_large(client):
    big = b"\xff\xd8" + b"\x00" * (1024 * 1024 + 10)
    r = _post(client, image=big, meta=_meta())
    assert r.status_code == 413
    assert r.json()["error"]["code"] == "payload_too_large"


def test_look_not_jpeg(client):
    r = _post(client, image=b"\x89PNG\r\n\x1a\n" + b"\x00" * 100, meta=_meta())
    assert r.status_code == 400


def test_look_missing_parts(client):
    assert _post(client, image=None, meta=_meta()).status_code == 400
    assert _post(client, meta=None).status_code == 400


def test_look_bad_meta(client):
    assert _post(client, meta="{oops").status_code == 400
    assert _post(client, meta=json.dumps({"question": "?"})).status_code == 400  # нет session_id
    assert _post(client, meta=_meta(lang="fr")).status_code == 400


def test_look_not_multipart(client):
    r = client.post("/v1/look", json={"meta": "{}"}, headers=AUTH)
    assert r.status_code == 400
    assert r.json()["error"]["code"] == "bad_request"


def test_look_llm_unavailable(make_client):
    c = make_client(vision_llm=FailingProvider())
    r = _post(c, meta=_meta(place_id="cafe"))
    assert r.status_code == 503


def test_look_image_not_in_logs(client, caplog):
    marker = b"\xff\xd8SECRET-FRAME-BYTES\xff\xd9"
    with caplog.at_level(logging.DEBUG):
        r = _post(client, image=marker, meta=_meta(place_id="cafe"))
    assert r.status_code == 200
    for record in caplog.records:
        dump = repr(record.__dict__)
        assert "SECRET-FRAME-BYTES" not in dump
