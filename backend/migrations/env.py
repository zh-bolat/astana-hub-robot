"""Alembic env: синхронный, только PostgreSQL.

URL: `sqlalchemy.url` из конфигурации (его задаёт app.db.migrate) или DATABASE_URL.
Миграции идут под advisory-lock: несколько контейнеров api, стартующих одновременно,
применяют их по очереди (второй увидит, что всё уже на head).
"""

from __future__ import annotations

from alembic import context
from sqlalchemy import create_engine, pool, text

from app.db.migrate import MIGRATION_LOCK_KEY
from app.db.models import Base

config = context.config
target_metadata = Base.metadata


def _url() -> str:
    url = config.get_main_option("sqlalchemy.url")
    if url:
        return url
    from app.config import Settings

    return Settings().database_url


def run_migrations_offline() -> None:
    context.configure(url=_url(), target_metadata=target_metadata, literal_binds=True)
    with context.begin_transaction():
        context.run_migrations()


def run_migrations_online() -> None:
    # Без statement_timeout/lock_timeout приложения: миграция большой таблицы и ожидание
    # блокировки соседнего контейнера могут длиться дольше 2 с.
    engine = create_engine(_url(), poolclass=pool.NullPool, connect_args={"connect_timeout": 10})
    try:
        with engine.connect() as connection:
            context.configure(connection=connection, target_metadata=target_metadata)
            with context.begin_transaction():
                # Блокировка до конца транзакции: миграции (DDL) в PG транзакционны
                connection.execute(
                    text("SELECT pg_advisory_xact_lock(:k)"), {"k": MIGRATION_LOCK_KEY}
                )
                context.run_migrations()
    finally:
        engine.dispose()


if context.is_offline_mode():
    run_migrations_offline()
else:
    run_migrations_online()
