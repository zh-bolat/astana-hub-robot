"""База знаний в Postgres: сид, upsert, деактивация, чтение с кешем по kb_sync.version."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta

import pytest
from sqlalchemy import text
from sqlalchemy.exc import IntegrityError

from app.config import BACKEND_DIR
from app.knowledge.models import Event, LocalizedText
from app.knowledge.pg import DbKnowledgeSource, KnowledgeRepo, canonical_kb
from app.knowledge.source import KnowledgeStore, YamlKnowledgeSource, build_kb
from app.knowledge.sync import seed_yaml, seed_yaml_if_empty

YAML = YamlKnowledgeSource(BACKEND_DIR / "knowledge")


def _source(db, tmp_path) -> DbKnowledgeSource:
    return DbKnowledgeSource(KnowledgeRepo(db), YAML, tmp_path / "snap.json")


def test_seed_roundtrip_equals_yaml(db):
    repo = KnowledgeRepo(db)
    result = seed_yaml(repo, YAML)
    assert result.status == "ok"
    assert result.counts["facts"]["inserted"] == 13
    loaded = repo.load_active()
    expected = canonical_kb(YAML.load())
    assert loaded.model_dump() == expected.model_dump()
    assert loaded.version == result.version == repo.latest_version()
    # порядок кнопок — как в источнике
    assert [q.id for q in loaded.quick_questions] == [q.id for q in YAML.load().quick_questions]


def test_seed_twice_is_skipped_and_does_not_touch_rows(db):
    repo = KnowledgeRepo(db)
    seed_yaml(repo, YAML)
    with db.connect() as conn:
        before = conn.execute(text("SELECT max(updated_at) FROM facts")).scalar()
    result = seed_yaml(repo, YAML)
    assert result.status == "skipped"
    assert all(c == {"inserted": 0, "updated": 0, "deactivated": 0} for c in result.counts.values())
    with db.connect() as conn:
        assert conn.execute(text("SELECT max(updated_at) FROM facts")).scalar() == before


def test_seed_if_empty_only_once(db):
    repo = KnowledgeRepo(db)
    assert seed_yaml_if_empty(repo, YAML) is True
    assert seed_yaml_if_empty(repo, YAML) is False
    assert len(repo.sync_log()) == 1


def test_update_changes_only_changed_rows(db):
    repo = KnowledgeRepo(db)
    seed_yaml(repo, YAML)
    kb = YAML.load()
    kb.facts[0].answer.ru = "Новый ответ."
    kb.places[0].synonyms.append("новый синоним")
    result = repo.apply(kb, "yaml")
    assert result.counts["facts"] == {"inserted": 0, "updated": 1, "deactivated": 0}
    assert result.counts["places"] == {"inserted": 0, "updated": 1, "deactivated": 0}
    # первый синк таблицы поверх сида: все строки переходят во владение таблицы
    result = repo.apply(kb, "sheet")
    assert result.counts["facts"]["updated"] == 13
    with db.connect() as conn:
        assert set(conn.execute(text("SELECT DISTINCT source FROM facts")).scalars()) == {"sheet"}


def test_admin_rows_survive_sync(db):
    repo = KnowledgeRepo(db)
    seed_yaml(repo, YAML)
    with db.begin() as conn:
        conn.execute(
            text("INSERT INTO facts (id, answer_ru, source) VALUES ('admin_fact', 'Да.', 'admin')")
        )
    kb = YAML.load()
    kb.facts = kb.facts[:2]
    result = repo.apply(kb, "sheet")
    assert result.counts["facts"]["deactivated"] == 11
    active = {f.id for f in repo.load_active().facts}
    assert active == {kb.facts[0].id, kb.facts[1].id, "admin_fact"}


def test_renamed_place_frees_map_name(db):
    """Точку завели под новым id с тем же map_name: старая выключается, конфликта нет."""
    repo = KnowledgeRepo(db)
    seed_yaml(repo, YAML)
    kb = YAML.load()
    cafe = next(p for p in kb.places if p.id == "cafe")
    cafe.id = "cafe_new"
    repo.apply(kb, "sheet")
    with db.connect() as conn:
        rows = dict(
            conn.execute(text("SELECT id, is_active FROM places WHERE map_name = 'Кафе'")).all()
        )
    assert rows == {"cafe": False, "cafe_new": True}


def test_fact_without_answer_violates_check(db):
    with pytest.raises(IntegrityError), db.begin() as conn:
        conn.execute(text("INSERT INTO facts (id, source) VALUES ('x', 'yaml')"))


def test_events_roundtrip(db):
    repo = KnowledgeRepo(db)
    seed_yaml(repo, YAML)
    kb = YAML.load()
    start = datetime(2026, 11, 1, 10, tzinfo=UTC)
    kb.events = [
        Event(
            id="demo-day",
            title=LocalizedText(ru="Демо-день", en="Demo day"),
            starts_at=start,
            ends_at=start + timedelta(hours=3),
            place_id="cafe",
        )
    ]
    result = repo.apply(kb, "yaml", replace_events=True)
    assert result.counts["events"]["inserted"] == 1
    loaded = repo.load_active()
    assert [e.id for e in loaded.events] == ["demo-day"]
    assert loaded.events[0].starts_at == start
    assert loaded.events[0].title.kk == ""
    # синк без мероприятий их не трогает
    assert repo.apply(YAML.load(), "yaml").status == "skipped"
    assert len(repo.load_active().events) == 1


def test_db_source_cache_invalidated_by_kb_sync_version(db, tmp_path, monkeypatch):
    repo = KnowledgeRepo(db)
    source = _source(db, tmp_path)
    store = KnowledgeStore(source, reload_interval_s=0)
    # пустая БД → запасной YAML
    assert source.refresh() is False
    assert source.from_db is False and len(store.get().facts) == 13

    seed_yaml(repo, YAML)
    assert source.refresh() is True
    assert source.from_db is True
    version = store.get().version

    loads = []
    original = repo.load_active
    monkeypatch.setattr(source.repo, "load_active", lambda *a: loads.append(1) or original(*a))
    assert source.refresh() is False  # версия та же — БД не перечитывается
    seed_yaml(repo, YAML)  # skipped — версия та же
    assert source.refresh() is False
    assert loads == []

    kb = YAML.load()
    kb.facts = kb.facts[:1]
    repo.apply(kb, "sheet")
    assert source.refresh() is True
    assert loads == [1]
    assert len(store.get().facts) == 1
    assert store.get().version != version


def test_db_source_keeps_last_version_when_db_fails(db, tmp_path, monkeypatch):
    seed_yaml(KnowledgeRepo(db), YAML)
    source = _source(db, tmp_path)
    source.refresh()
    store = KnowledgeStore(source, reload_interval_s=0)
    before = store.get().version

    def down():
        raise ConnectionError("db down")

    monkeypatch.setattr(source.repo, "latest_version", down)
    assert source.try_refresh() is False
    assert source.last_error and "db down" in source.last_error
    assert store.get().version == before

    # новый процесс при недоступной БД стартует со снимка
    fresh = _source(db, tmp_path)
    monkeypatch.setattr(fresh.repo, "latest_version", down)
    assert fresh.try_refresh() is False
    assert fresh.load_snapshot() is True
    assert fresh.load().version == before


def test_duplicate_ids_rejected_before_db():
    kb = YAML.load()
    with pytest.raises(ValueError, match="duplicate"):
        build_kb(kb.facts, [*kb.places, kb.places[0]], kb.quick_questions)
