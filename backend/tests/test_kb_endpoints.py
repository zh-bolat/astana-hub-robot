from __future__ import annotations

import io
import time

import pytest

from app.db import QuestionLogRepo, make_engine
from app.knowledge.pg import KnowledgeRepo
from app.knowledge.source import YamlKnowledgeSource
from app.knowledge.sync import seed_yaml
from tests.conftest import AUTH, ask_body


def test_places_and_etag(client):
    r = client.get("/v1/places", headers=AUTH)
    assert r.status_code == 200
    etag = r.headers["etag"]
    places = r.json()["places"]
    cafe = next(p for p in places if p["id"] == "cafe")
    assert cafe["map_name"] == "cafe"  # ровно как точка на карте робота
    assert set(cafe["names"]) == {"ru", "kk", "en"}
    assert "кафешка" in cafe["synonyms"]
    assert set(places[0]) == {"id", "map_name", "names", "synonyms", "description"}

    r2 = client.get("/v1/places", headers={**AUTH, "If-None-Match": etag})
    assert r2.status_code == 304
    assert r2.headers["etag"] == etag
    assert r2.content == b""

    r3 = client.get("/v1/places", headers={**AUTH, "If-None-Match": '"stale"'})
    assert r3.status_code == 200


def test_quick_questions(client):
    ru = client.get("/v1/quick-questions", headers=AUTH).json()["items"]
    assert {"id": "transport", "text": "Как сюда добраться?"} in ru
    kk = client.get("/v1/quick-questions?lang=kk", headers=AUTH).json()["items"]
    assert {"id": "transport", "text": "Мұнда қалай жетуге болады?"} in kk
    r = client.get("/v1/quick-questions?lang=de", headers=AUTH)
    assert r.status_code == 400


def _wait(predicate, timeout_s: float = 5.0) -> bool:
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        if predicate():
            return True
        time.sleep(0.05)
    return predicate()


def test_knowledge_hot_reload_from_db(client, knowledge_dir, db):
    """Правка базы (YAML → kb seed-yaml / синк таблицы) подхватывается сервером без перезапуска."""
    q = ask_body("Есть ли у вас спортзал?")
    with client:  # lifespan: фоновая сверка kb_sync.version
        assert client.post("/v1/ask", json=q, headers=AUTH).json()["found"] is False
        etag_before = client.get("/v1/places", headers=AUTH).headers["etag"]

        facts = knowledge_dir / "facts.yaml"
        facts.write_text(
            facts.read_text(encoding="utf-8")
            + """
  - id: gym
    keywords: [спортзал, тренажер, gym]
    answer:
      ru: Спортзал находится на третьем этаже.
      en: The gym is on the third floor.
""",
            encoding="utf-8",
        )
        places = knowledge_dir / "places.yaml"
        places.write_text(
            places.read_text(encoding="utf-8").replace("кафешка", "кофейка"), encoding="utf-8"
        )
        result = seed_yaml(KnowledgeRepo(db), YamlKnowledgeSource(knowledge_dir))
        assert result.status == "ok"
        store = client.app.state.store
        assert _wait(lambda: store.get().version == result.version)
        body = client.post("/v1/ask", json=q, headers=AUTH).json()
        assert body["found"] is True
        assert body["sources"] == ["fact:gym"]
        assert client.get("/v1/places", headers=AUTH).headers["etag"] != etag_before


def test_broken_yaml_seed_keeps_previous_version(client, knowledge_dir, db):
    (knowledge_dir / "facts.yaml").write_text("facts: [ {broken", encoding="utf-8")
    with pytest.raises(Exception):  # noqa: B017 — ошибка YAML
        seed_yaml(KnowledgeRepo(db), YamlKnowledgeSource(knowledge_dir))
    assert KnowledgeRepo(db).sync_log(1)[0].status == "error"
    client.app.state.knowledge.source.refresh()
    body = client.post("/v1/ask", json=ask_body("Где кафе?"), headers=AUTH).json()
    assert body["found"] is True


def test_startup_seeds_empty_db_from_yaml_once(client, knowledge_dir, db):
    repo = KnowledgeRepo(db)
    assert len(repo.load_active().facts) == len(YamlKnowledgeSource(knowledge_dir).load().facts)
    assert client.app.state.knowledge.source.from_db is True
    assert [r.source for r in repo.sync_log()] == ["yaml"]


def test_export_csv(client, settings):
    client.post("/v1/ask", json=ask_body("Где кафе?"), headers=AUTH)
    client.post("/v1/ask", json=ask_body("Кто выиграл чемпионат мира?"), headers=AUTH)
    # запись лога фоновая — ждём, пока очередь допишется
    assert client.app.state.log_writer.wait_idle(5)
    repo = QuestionLogRepo(make_engine(settings.database_url))
    buf = io.StringIO()
    assert repo.export_csv(buf) == 2
    lines = buf.getvalue().strip().splitlines()
    assert lines[0].startswith("id,created_at,request_id")
    buf = io.StringIO()
    assert repo.export_csv(buf, only_unanswered=True) == 1
    assert "чемпионат" in buf.getvalue()


def test_docs_hidden_by_default(client):
    for path in ("/docs", "/redoc", "/openapi.json"):
        assert client.get(path).status_code == 404


def test_docs_exposed_with_flag(make_client):
    c = make_client(expose_docs=True)
    assert c.get("/docs").status_code == 200
    assert c.get("/openapi.json").status_code == 200


def test_openapi_matches_contract_paths(client):
    spec = client.app.openapi()  # схема генерируется и при выключенном /openapi.json
    assert set(spec["paths"]) == {
        "/v1/health",
        "/v1/profile",
        "/v1/ask",
        "/v1/chat",
        "/v1/look",
        "/v1/places",
        "/v1/quick-questions",
        "/v1/stt",
        "/v1/tts",
        "/v1/sessions/{session_id}/messages",
        "/v1/metrics/turn",
        "/v1/admin/metrics",
        "/v1/admin/metrics/page",
    }
    assert "multipart/form-data" in spec["paths"]["/v1/look"]["post"]["requestBody"]["content"]
