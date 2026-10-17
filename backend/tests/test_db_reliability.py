"""БД не на критическом пути: фоновая запись, таймауты, миграции, срок хранения."""

from __future__ import annotations

import os
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import UTC, datetime, timedelta

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, inspect, text

from app.db import QuestionLog, QuestionLogRepo, engine_options, make_engine, migrate
from app.llm.fake import FakeProvider
from app.log_writer import BackgroundLogWriter
from app.main import create_app
from tests.conftest import AUTH, ask_body, logged


def _row(**over) -> dict:
    fields = dict(
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
        provider="fake:fake",
        latency_ms=1,
    )
    fields.update(over)
    return fields


class HangingRepo:
    """БД, которая «зависла»: запись блокируется, пока тест не отпустит."""

    def __init__(self) -> None:
        self.release = threading.Event()
        self.calls = 0

    def ensure_schema(self) -> None:
        self.release.wait(30)

    def add(self, **fields) -> None:
        self.calls += 1
        self.release.wait(30)


class BrokenRepo:
    def ensure_schema(self) -> None:
        raise RuntimeError("db down")

    def add(self, **fields) -> None:
        raise RuntimeError("db down")


def _client_with_repo(settings, repo) -> TestClient:
    app = create_app(settings, FakeProvider(), FakeProvider())
    writer = BackgroundLogWriter(repo, max_pending=settings.question_log_queue_max)  # type: ignore[arg-type]
    app.state.service.log_writer = writer
    app.state.log_writer = writer
    return TestClient(app)


def test_hanging_db_does_not_slow_down_ask(settings):
    repo = HangingRepo()
    client = _client_with_repo(settings, repo)
    try:
        for _ in range(3):
            t0 = time.perf_counter()
            r = client.post("/v1/ask", json=ask_body("Какой пароль от Wi-Fi?"), headers=AUTH)
            elapsed = time.perf_counter() - t0
            assert r.status_code == 200
            assert r.json()["found"] is True
            assert elapsed < 1.0  # бюджет /ask — 3 с; запись в БД его не трогает
        # SSE тоже
        r = client.post(
            "/v1/ask",
            json=ask_body("Какой пароль от Wi-Fi?"),
            headers={**AUTH, "Accept": "text/event-stream"},
        )
        assert "event: done" in r.text
        assert client.app.state.log_writer.pending >= 1
    finally:
        repo.release.set()
        client.close()


def test_broken_db_does_not_break_ask(settings):
    client = _client_with_repo(settings, BrokenRepo())
    with client:
        r = client.post("/v1/ask", json=ask_body("Какой пароль от Wi-Fi?"), headers=AUTH)
        assert r.status_code == 200
        assert client.app.state.log_writer.wait_idle(5)


def test_queue_overflow_drops_instead_of_growing():
    repo = HangingRepo()
    writer = BackgroundLogWriter(repo, max_pending=2)  # type: ignore[arg-type]
    try:
        assert writer.submit(**_row()) is True
        assert writer.submit(**_row()) is True
        assert writer.submit(**_row()) is False
        assert writer.dropped == 1
    finally:
        repo.release.set()
    assert writer.wait_idle(5)
    writer.close(1)
    assert writer.submit(**_row()) is False  # после остановки не принимаем


def test_writer_writes_rows(settings, client):
    client.post("/v1/ask", json=ask_body("Какой пароль от Wi-Fi?"), headers=AUTH)
    rows = logged(client)
    assert rows[-1].question == "Какой пароль от Wi-Fi?"


def test_engine_options_have_timeouts():
    sqlite = engine_options("sqlite:///x.db", 2.0)
    assert sqlite["connect_args"]["timeout"] == 2.0
    pg = engine_options("postgresql+psycopg://u:p@h/db", 2.0)
    assert pg["pool_pre_ping"] is True
    assert pg["connect_args"]["connect_timeout"] == 2
    assert "statement_timeout=2000" in pg["connect_args"]["options"]
    assert pg["pool_timeout"] == 2.0


def test_make_engine_does_not_connect():
    # Недоступный Postgres: создание engine мгновенное, ошибка — только при записи (в фоне)
    t0 = time.perf_counter()
    engine = make_engine("postgresql+psycopg://u:p@127.0.0.1:1/none", timeout_s=1)
    assert time.perf_counter() - t0 < 0.5
    repo = QuestionLogRepo(engine)
    with pytest.raises(Exception):  # noqa: B017 — любая ошибка драйвера
        repo.add(**_row())


# ---------- миграции ----------


def test_sqlite_migration_adds_column_and_is_idempotent(tmp_path):
    url = f"sqlite:///{tmp_path / 'old.db'}"
    raw = create_engine(url)
    with raw.begin() as conn:
        conn.execute(
            text(
                "CREATE TABLE question_log (id INTEGER PRIMARY KEY, created_at DATETIME, "
                "request_id VARCHAR(64), session_id VARCHAR(64), endpoint VARCHAR(16), "
                "question TEXT, lang VARCHAR(8), place_id VARCHAR(64), found BOOLEAN, "
                "answer TEXT, sources TEXT, provider VARCHAR(64), latency_ms INTEGER)"
            )
        )
    engine = make_engine(url)
    migrate(engine)
    migrate(engine)
    assert "category" in {c["name"] for c in inspect(engine).get_columns("question_log")}


def test_retention_purges_old_rows(tmp_path):
    repo = QuestionLogRepo(make_engine(f"sqlite:///{tmp_path / 'r.db'}"))
    now = datetime.now(UTC)
    repo.add(**_row(request_id="old"), created_at=now - timedelta(days=200))
    repo.add(**_row(request_id="fresh"), created_at=now - timedelta(days=10))
    assert repo.purge_older_than(180) == 1
    assert [r.request_id for r in repo.all()] == ["fresh"]


def test_retention_runs_in_background_on_startup(settings, tmp_path):
    repo = QuestionLogRepo(make_engine(settings.database_url))
    repo.add(**_row(request_id="old"), created_at=datetime.now(UTC) - timedelta(days=400))
    repo.add(**_row(request_id="new"))
    app = create_app(
        settings.model_copy(update={"question_log_retention_days": 180}),
        FakeProvider(),
        FakeProvider(),
    )
    with TestClient(app) as c:
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            c.app.state.log_writer.wait_idle(1)
            if [r.request_id for r in c.app.state.log_repo.all()] == ["new"]:
                break
            time.sleep(0.05)
        assert [r.request_id for r in c.app.state.log_repo.all()] == ["new"]


def test_retention_disabled_with_zero(settings):
    repo = QuestionLogRepo(make_engine(settings.database_url))
    repo.add(**_row(request_id="old"), created_at=datetime.now(UTC) - timedelta(days=4000))
    app = create_app(
        settings.model_copy(update={"question_log_retention_days": 0}),
        FakeProvider(),
        FakeProvider(),
    )
    with TestClient(app) as c:
        c.app.state.log_writer.wait_idle(5)
        assert [r.request_id for r in c.app.state.log_repo.all()] == ["old"]


# Postgres: тест запускается, только если задан TEST_POSTGRES_URL (например, временный контейнер)
PG_URL = os.environ.get("TEST_POSTGRES_URL")


@pytest.mark.skipif(not PG_URL, reason="TEST_POSTGRES_URL is not set")
def test_postgres_concurrent_migrations_and_retention():
    assert PG_URL
    engine = make_engine(PG_URL)
    with engine.begin() as conn:
        conn.execute(text("DROP TABLE IF EXISTS question_log"))
    # Таблица «старой» версии без category
    with engine.begin() as conn:
        conn.execute(
            text(
                "CREATE TABLE question_log (id SERIAL PRIMARY KEY, "
                "created_at TIMESTAMPTZ, request_id VARCHAR(64), session_id VARCHAR(64), "
                "endpoint VARCHAR(16), question TEXT, lang VARCHAR(8), place_id VARCHAR(64), "
                "found BOOLEAN, answer TEXT, sources TEXT, provider VARCHAR(64), latency_ms INTEGER)"
            )
        )
    # Два «контейнера» стартуют одновременно
    engines = [make_engine(PG_URL) for _ in range(4)]
    with ThreadPoolExecutor(4) as pool:
        list(pool.map(migrate, engines))
    assert "category" in {c["name"] for c in inspect(engine).get_columns("question_log")}
    repo = QuestionLogRepo(engine)
    repo.add(**_row(request_id="old"), created_at=datetime.now(UTC) - timedelta(days=200))
    repo.add(**_row(request_id="new"))
    assert repo.purge_older_than(180) == 1
    assert [r.request_id for r in repo.all()] == ["new"]
    # statement_timeout действует
    with pytest.raises(Exception), engine.connect() as conn:  # noqa: B017
        conn.execute(text("SELECT pg_sleep(5)"))
    with engine.begin() as conn:
        conn.execute(text("DROP TABLE question_log"))
    for e in [engine, *engines]:
        e.dispose()
    assert QuestionLog.__tablename__ == "question_log"
