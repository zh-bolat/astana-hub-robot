"""Alembic: схема с нуля, поверх старой question_log, идемпотентность, pgvector-индексы."""

from __future__ import annotations

import multiprocessing
import sqlite3
from concurrent.futures import ProcessPoolExecutor
from datetime import UTC, datetime

import pytest
from alembic import command
from alembic.autogenerate import compare_metadata
from alembic.runtime.migration import MigrationContext
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, inspect, text

from app.db import FeedbackRepo, QuestionLogRepo, make_engine
from app.db.migrate import alembic_config, current_revision, head_revision, upgrade_head
from app.db.models import Base
from app.llm.fake import FakeProvider
from app.main import create_app
from tests.conftest import AUTH, ask_body, logged, temp_database

EXPECTED_TABLES = {
    "facts",
    "places",
    "quick_questions",
    "events",
    "documents",
    "kb_sync",
    "kb_chunks",
    "robots",
    "sessions",
    "messages",
    "question_log",
    "feedback",
}

# Таблица question_log, как её создавал ручной migrate() до Alembic (create_all + ALTER)
OLD_QUESTION_LOG = """
CREATE TABLE question_log (
    id SERIAL NOT NULL PRIMARY KEY,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    endpoint VARCHAR(16) NOT NULL,
    question TEXT NOT NULL,
    lang VARCHAR(8) NOT NULL,
    place_id VARCHAR(64),
    found BOOLEAN NOT NULL,
    answer TEXT NOT NULL,
    sources TEXT NOT NULL,
    provider VARCHAR(64) NOT NULL,
    latency_ms INTEGER NOT NULL
);
CREATE INDEX ix_question_log_created_at ON question_log (created_at);
CREATE INDEX ix_question_log_found ON question_log (found);
INSERT INTO question_log (created_at, request_id, session_id, endpoint, question, lang,
    found, answer, sources, provider, latency_ms)
VALUES (now(), 'r1', 's1', 'ask', 'Где кафе?', 'ru', true, 'На первом этаже.',
        '["place:cafe"]', 'fake:fake', 12),
       (now(), 'r2', 's1', 'ask', 'Кто выиграл?', 'ru', false, 'Обратитесь на ресепшен.',
        '[]', 'fake:fake', 7);
"""


def _diff(url: str) -> list:
    engine = create_engine(url)
    try:
        with engine.connect() as conn:
            mc = MigrationContext.configure(
                conn, opts={"compare_type": True, "compare_server_default": True}
            )
            return compare_metadata(mc, Base.metadata)
    finally:
        engine.dispose()


def _scalar(url: str, sql: str):
    engine = create_engine(url)
    try:
        with engine.connect() as conn:
            return conn.execute(text(sql)).scalar()
    finally:
        engine.dispose()


def test_upgrade_from_empty_db_is_idempotent_and_matches_models():
    with temp_database() as url:
        upgrade_head(url)
        upgrade_head(url)  # повторно — без ошибок и изменений
        assert current_revision(url) == head_revision() == "0001_baseline"
        tables = set(inspect(create_engine(url)).get_table_names())
        assert tables >= EXPECTED_TABLES
        assert _diff(url) == []  # модели и миграции совпадают


def test_pgvector_extension_and_indexes(pg_url, db):
    assert _scalar(pg_url, "SELECT count(*) FROM pg_extension WHERE extname = 'vector'") == 1
    engine = create_engine(pg_url)
    with engine.connect() as conn:
        rows = dict(
            conn.execute(
                text("SELECT indexname, indexdef FROM pg_indexes WHERE tablename = 'kb_chunks'")
            ).all()
        )
    engine.dispose()
    assert "USING hnsw (embedding vector_cosine_ops)" in rows["ix_kb_chunks_embedding_hnsw"]
    assert "USING gin (tsv)" in rows["ix_kb_chunks_tsv_gin"]
    assert "(source_type, source_id)" in rows["ix_kb_chunks_source"]
    assert "(lang)" in rows["ix_kb_chunks_lang"]


def test_kb_chunks_vector_and_generated_tsv_work(db):
    vec = "[" + ",".join(["0.1"] * 768) + "]"
    with db.begin() as conn:
        conn.execute(
            text(
                "INSERT INTO kb_chunks (source_type, source_id, lang, content, content_hash, "
                "embedding, embedding_model) VALUES ('fact', 'wifi', 'kk', "
                "'Wi-Fi құпиясөзі ресепшенде', 'h', CAST(:v AS vector), 'test')"
            ),
            {"v": vec},
        )
        tsv = conn.execute(text("SELECT tsv::text FROM kb_chunks")).scalar()
        assert "'құпиясөзі'" in tsv  # 'simple': без стемминга, казахский — как есть
        hit = conn.execute(
            text(
                "SELECT source_id, embedding <=> CAST(:v AS vector) FROM kb_chunks "
                "WHERE tsv @@ plainto_tsquery('simple', 'құпиясөзі')"
            ),
            {"v": vec},
        ).one()
        assert hit[0] == "wifi" and hit[1] == pytest.approx(0, abs=1e-6)


def test_upgrade_over_old_question_log_keeps_data():
    with temp_database() as url:
        engine = create_engine(url)
        with engine.begin() as conn:
            conn.execute(text(OLD_QUESTION_LOG))
        upgrade_head(url)
        upgrade_head(url)
        assert _diff(url) == []
        app_engine = make_engine(url)
        repo = QuestionLogRepo(app_engine)
        rows = repo.all()
        assert [r.request_id for r in rows] == ["r1", "r2"]
        assert rows[0].sources == ["place:cafe"] and rows[1].sources == []
        assert rows[0].category is None
        # новые строки продолжают последовательность id; feedback ссылается на лог
        repo.add(
            request_id="r3",
            session_id="s2",
            endpoint="look",
            question="Что это?",
            lang="ru",
            place_id=None,
            found=True,
            category="kb",
            answer="Кафе.",
            sources=["place:cafe"],
            provider="fake:fake",
            latency_ms=5,
        )
        new = repo.all()[-1]
        assert new.id == 3 and new.category == "kb"
        FeedbackRepo(app_engine).add(new.id, 1, "staff")
        assert (
            _scalar(
                url,
                "SELECT data_type FROM information_schema.columns "
                "WHERE table_name='question_log' AND column_name='id'",
            )
            == "bigint"
        )
        app_engine.dispose()
        engine.dispose()


def test_downgrade_keeps_question_log_and_upgrade_again():
    with temp_database() as url:
        upgrade_head(url)
        QuestionLogRepo(make_engine(url)).add(
            request_id="r",
            session_id="s",
            endpoint="ask",
            question="q",
            lang="ru",
            place_id=None,
            found=True,
            category="kb",
            answer="a",
            sources=[],
            provider="p",
            latency_ms=1,
        )
        command.downgrade(alembic_config(url), "base")
        assert _scalar(url, "SELECT count(*) FROM question_log") == 1
        upgrade_head(url)
        assert _scalar(url, "SELECT count(*) FROM question_log") == 1
        assert _diff(url) == []


def test_concurrent_upgrades_are_serialized():
    """Несколько контейнеров api стартуют одновременно (отдельные процессы)."""
    with temp_database() as url:
        ctx = multiprocessing.get_context("spawn")
        with ProcessPoolExecutor(4, mp_context=ctx) as pool:
            list(pool.map(upgrade_head, [url] * 4))
        assert current_revision(url) == head_revision()


def test_app_startup_migrates_and_seeds_empty_db(settings, tmp_path):
    with temp_database() as url:
        s = settings.model_copy(update={"database_url": url, "db_auto_migrate": True})
        app = create_app(s, FakeProvider(), FakeProvider())
        with TestClient(app) as c:
            assert current_revision(url) == head_revision()
            assert c.app.state.knowledge.source.from_db is True
            r = c.post("/v1/ask", json=ask_body("Какой пароль от Wi-Fi?"), headers=AUTH)
            assert r.json()["sources"] == ["fact:wifi"]
            assert logged(c)[0].sources == ["fact:wifi"]
        app.state.engine.dispose()


def test_app_startup_fails_when_migration_fails(settings):
    s = settings.model_copy(
        update={
            "database_url": "postgresql+psycopg://u:p@127.0.0.1:1/none",
            "db_auto_migrate": True,
        }
    )
    with pytest.raises(Exception):  # noqa: B017 — процесс должен упасть (ненулевой код)
        create_app(s, FakeProvider(), FakeProvider())


# ---------- CLI ----------


def test_cli_db_and_kb_commands(monkeypatch, capsys, knowledge_dir):
    from app.cli import main

    with temp_database() as url:
        monkeypatch.setenv("DATABASE_URL", url)
        monkeypatch.setenv("KNOWLEDGE_DIR", str(knowledge_dir))
        assert main(["db", "current"]) == 1  # пустая БД — не на head
        assert main(["db", "upgrade"]) == 0
        assert main(["db", "upgrade"]) == 0
        assert main(["db", "current"]) == 0
        assert main(["kb", "seed-yaml"]) == 0
        assert "ok: version=" in capsys.readouterr().out
        assert main(["kb", "seed-yaml"]) == 0
        assert "skipped: version=" in capsys.readouterr().out
        assert main(["kb", "status"]) == 0
        out = capsys.readouterr().out
        assert "facts=13" in out and "skipped" in out
        monkeypatch.setenv("KNOWLEDGE_SHEET_ID", "")
        assert main(["kb", "sync"]) == 2  # таблица не задана


def test_cli_import_sqlite_question_log(tmp_path, monkeypatch, capsys):
    from app.cli import main

    path = tmp_path / "questions.db"
    con = sqlite3.connect(path)
    con.executescript(
        """
        CREATE TABLE question_log (id INTEGER PRIMARY KEY, created_at DATETIME,
            request_id VARCHAR(64), session_id VARCHAR(64), endpoint VARCHAR(16), question TEXT,
            lang VARCHAR(8), place_id VARCHAR(64), found BOOLEAN, answer TEXT, sources TEXT,
            provider VARCHAR(64), latency_ms INTEGER);
        INSERT INTO question_log VALUES (1, '2026-10-01 10:00:00.000000', 'r1', 's', 'ask',
            'Где кафе?', 'ru', NULL, 1, 'На первом этаже.', '["place:cafe"]', 'fake', 10);
        """
    )
    con.commit()
    con.close()
    with temp_database() as url:
        upgrade_head(url)
        monkeypatch.setenv("DATABASE_URL", url)
        assert main(["db", "import-sqlite", str(path)]) == 0
        assert main(["db", "import-sqlite", str(path)]) == 0  # повторно — пропускает
        assert "imported 0 rows, skipped 1" in capsys.readouterr().out
        engine = make_engine(url)
        rows = QuestionLogRepo(engine).all()
        engine.dispose()
        assert len(rows) == 1
        assert rows[0].sources == ["place:cafe"]
        assert rows[0].created_at == datetime(2026, 10, 1, 10, tzinfo=UTC)
