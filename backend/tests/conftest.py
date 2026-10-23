from __future__ import annotations

import asyncio
import os
import shutil
import uuid
import weakref
from collections.abc import Iterator
from contextlib import contextmanager
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, text
from sqlalchemy.engine import Engine, make_url

from app.config import BACKEND_DIR, Settings
from app.db import make_engine
from app.db.migrate import upgrade_head
from app.db.models import ALL_TABLES
from app.llm.base import LLMAnswer, LLMError, LLMRequest
from app.llm.fake import FakeProvider
from app.log_writer import BackgroundLogWriter
from app.main import create_app

TOKEN = "test-token"
AUTH = {"Authorization": f"Bearer {TOKEN}"}
SESSION = "6f1c2a9e-7d4b-4c1a-9f3e-2b8d5e6a7c10"
JPEG = b"\xff\xd8\xff\xe0" + b"\x00" * 2048 + b"\xff\xd9"


class FailingProvider:
    name = "failing"
    model = "failing"

    async def answer(self, req: LLMRequest) -> LLMAnswer:
        raise LLMError("boom")

    async def describe_image(self, req: LLMRequest) -> LLMAnswer:
        raise LLMError("boom")


class SlowProvider:
    name = "slow"
    model = "slow"

    async def answer(self, req: LLMRequest) -> LLMAnswer:
        await asyncio.sleep(5)
        return LLMAnswer(found=True, answer="late")

    async def describe_image(self, req: LLMRequest) -> LLMAnswer:
        await asyncio.sleep(5)
        return LLMAnswer(found=True, answer="late")


class ScriptedProvider:
    """Возвращает заранее заданный ответ — для проверки постобработки."""

    name = "scripted"
    model = "scripted"

    def __init__(self, result: LLMAnswer) -> None:
        self.result = result
        self.requests: list[LLMRequest] = []

    async def answer(self, req: LLMRequest) -> LLMAnswer:
        self.requests.append(req)
        return self.result

    async def describe_image(self, req: LLMRequest) -> LLMAnswer:
        self.requests.append(req)
        return self.result


# Тесты идут на настоящем Postgres + pgvector. Локально:
#   docker run -d --rm --name hubtest-pg -e POSTGRES_PASSWORD=hub -e POSTGRES_USER=hub \
#     -e POSTGRES_DB=hub_test -p 127.0.0.1:55432:5432 pgvector/pgvector:pg16
PG_URL = os.environ.get(
    "TEST_POSTGRES_URL", "postgresql+psycopg://hub:hub@127.0.0.1:55432/hub_test"
)


def _admin_engine(url: str) -> Engine:
    return create_engine(
        make_url(url).set(database="postgres"),
        isolation_level="AUTOCOMMIT",
        connect_args={"connect_timeout": 5},
    )


@contextmanager
def temp_database(base_url: str = PG_URL) -> Iterator[str]:
    """Отдельная пустая БД на том же сервере (тесты миграций). Удаляется после теста."""
    name = f"hub_tmp_{uuid.uuid4().hex[:10]}"
    admin = _admin_engine(base_url)
    try:
        with admin.connect() as conn:
            conn.execute(text(f'CREATE DATABASE "{name}"'))
        yield make_url(base_url).set(database=name).render_as_string(hide_password=False)
    finally:
        with admin.connect() as conn:
            conn.execute(text(f'DROP DATABASE IF EXISTS "{name}" WITH (FORCE)'))
        admin.dispose()


@pytest.fixture(scope="session")
def pg_url() -> str:
    """Схема hub_test пересоздаётся с нуля и поднимается миграциями Alembic (один раз)."""
    db_name = make_url(PG_URL).database or ""
    if "test" not in db_name:
        pytest.fail(f"TEST_POSTGRES_URL must point to a *test* database, got {db_name!r}")
    engine = create_engine(PG_URL, connect_args={"connect_timeout": 5})
    try:
        with engine.begin() as conn:
            conn.execute(text("DROP SCHEMA public CASCADE"))
            conn.execute(text("CREATE SCHEMA public"))
    except Exception as exc:
        pytest.fail(
            f"Postgres for tests is unavailable ({type(exc).__name__}). "
            "Start it (see tests/conftest.py) or set TEST_POSTGRES_URL."
        )
    finally:
        engine.dispose()
    upgrade_head(PG_URL)
    return PG_URL


@pytest.fixture(scope="session")
def pg_engine(pg_url: str) -> Iterator[Engine]:
    engine = make_engine(pg_url, timeout_s=5)
    yield engine
    engine.dispose()


_WRITERS: weakref.WeakSet = weakref.WeakSet()
_orig_writer_init = BackgroundLogWriter.__init__


def _tracking_init(self, *args, **kwargs) -> None:
    _orig_writer_init(self, *args, **kwargs)
    _WRITERS.add(self)


BackgroundLogWriter.__init__ = _tracking_init  # type: ignore[method-assign]


@pytest.fixture
def db(pg_engine: Engine) -> Iterator[Engine]:
    """Чистые таблицы перед каждым тестом. После теста ждём фоновую запись лога, чтобы
    запоздавшая строка прошлого теста не попала в следующий."""
    with pg_engine.begin() as conn:
        tables = ", ".join(t for t in ALL_TABLES)
        conn.execute(text(f"TRUNCATE {tables} RESTART IDENTITY CASCADE"))
    yield pg_engine
    for writer in list(_WRITERS):
        writer.wait_idle(5)


@pytest.fixture
def knowledge_dir(tmp_path: Path) -> Path:
    dst = tmp_path / "knowledge"
    shutil.copytree(BACKEND_DIR / "knowledge", dst)
    return dst


@pytest.fixture
def settings(tmp_path: Path, knowledge_dir: Path, db: Engine, pg_url: str) -> Settings:
    return Settings(
        _env_file=None,
        robot_token=TOKEN,
        llm_provider="fake",
        vision_provider="fake",
        gemini_api_key=None,
        knowledge_dir=knowledge_dir,
        knowledge_reload_interval_s=0,
        database_url=pg_url,
        db_auto_migrate=False,  # схема уже поднята фикстурой pg_url
        kb_seed_yaml_if_empty=True,
        knowledge_snapshot_path=tmp_path / "knowledge_snapshot.json",
        rate_limit_per_min=1000,
        ask_timeout_s=2,
        look_timeout_s=2,
        stt_provider="fake",
        tts_provider="fake",
        yandex_api_key=None,
        elevenlabs_api_key=None,
        tts_cache_dir=tmp_path / "tts_cache",
        speech_rate_limit_per_min=1000,
    )


@pytest.fixture
def make_client(settings: Settings):
    clients: list[TestClient] = []

    def _make(
        text_llm=None, vision_llm=None, speech=None, chat_client=None, **overrides
    ) -> TestClient:
        s = settings.model_copy(update=overrides)
        app = create_app(
            s,
            text_llm or FakeProvider(),
            vision_llm or FakeProvider(),
            speech,
            chat_client=chat_client,
        )
        c = TestClient(app)
        clients.append(c)
        return c

    yield _make
    for c in clients:
        c.close()
        # lifespan без `with TestClient` не запускается — пул соединений закрываем сами,
        # иначе тестовый Postgres упирается в max_connections
        c.app.state.log_writer.wait_idle(5)
        c.app.state.engine.dispose()


@pytest.fixture
def client(make_client) -> TestClient:
    return make_client()


def ask_body(question: str, **extra) -> dict:
    return {"question": question, "session_id": SESSION, **extra}


def logged(client: TestClient, only_unanswered: bool = False):
    """Строки лога вопросов после того, как фоновая запись дописала очередь."""
    assert client.app.state.log_writer.wait_idle(5)
    return client.app.state.log_repo.all(only_unanswered=only_unanswered)
