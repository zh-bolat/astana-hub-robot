"""Запуск миграций Alembic из кода (старт сервера, CLI, тесты)."""

from __future__ import annotations

from alembic import command
from alembic.config import Config
from alembic.runtime.migration import MigrationContext
from alembic.script import ScriptDirectory
from sqlalchemy import create_engine, pool

from app.config import BACKEND_DIR

# Ключ advisory-lock миграций (тот же, что был у ручного migrate() до Alembic)
MIGRATION_LOCK_KEY = 0x48554230  # "HUB0"


def alembic_config(url: str) -> Config:
    cfg = Config(str(BACKEND_DIR / "alembic.ini"))
    cfg.set_main_option("script_location", str(BACKEND_DIR / "migrations"))
    # ConfigParser: '%' в пароле URL нужно экранировать
    cfg.set_main_option("sqlalchemy.url", url.replace("%", "%%"))
    return cfg


def upgrade_head(url: str) -> None:
    """`alembic upgrade head` под advisory-lock (см. migrations/env.py). Идемпотентно."""
    command.upgrade(alembic_config(url), "head")


def head_revision() -> str | None:
    script = ScriptDirectory.from_config(alembic_config("postgresql://unused"))
    return script.get_current_head()


def current_revision(url: str) -> str | None:
    engine = create_engine(url, poolclass=pool.NullPool, connect_args={"connect_timeout": 10})
    try:
        with engine.connect() as conn:
            return MigrationContext.configure(conn).get_current_revision()
    finally:
        engine.dispose()
