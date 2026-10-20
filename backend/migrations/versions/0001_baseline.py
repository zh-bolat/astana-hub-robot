"""baseline: все таблицы docs/db-schema.md v1 + pgvector

Подхватывает существующую таблицу question_log (создавалась до Alembic через create_all
и ручной ALTER): добавляет category, если её нет, id → bigint, sources text → jsonb, индексы.

Revision ID: 0001_baseline
Revises:
Create Date: 2026-10-20
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op
from pgvector.sqlalchemy import Vector
from sqlalchemy.dialects import postgresql as pg

revision: str = "0001_baseline"
down_revision: str | None = None
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None

EMBEDDING_DIM = 768  # не импортируем из app: миграция фиксирует схему на момент написания

TZ = sa.DateTime(timezone=True)


def _now(name: str, nullable: bool = False) -> sa.Column:
    return sa.Column(name, TZ, nullable=nullable, server_default=sa.text("now()"))


def _active() -> sa.Column:
    return sa.Column("is_active", sa.Boolean(), nullable=False, server_default=sa.text("true"))


def _arr(name: str) -> sa.Column:
    return sa.Column(
        name, pg.ARRAY(sa.Text()), nullable=False, server_default=sa.text("'{}'::text[]")
    )


def _loc(prefix: str) -> list[sa.Column]:
    return [sa.Column(f"{prefix}_{lang}", sa.Text(), nullable=True) for lang in ("ru", "kk", "en")]


def _kb_source(table: str) -> sa.CheckConstraint:
    return sa.CheckConstraint("source IN ('sheet', 'yaml', 'admin')", name=f"ck_{table}_source")


def _id_identity() -> sa.Column:
    return sa.Column("id", sa.BigInteger(), sa.Identity(), primary_key=True)


QUESTION_LOG_INDEXES = ("created_at", "request_id", "session_id", "found", "category")


def upgrade() -> None:
    op.execute("CREATE EXTENSION IF NOT EXISTS vector")

    # ---------- 1. Знания
    op.create_table(
        "facts",
        sa.Column("id", sa.Text(), primary_key=True),
        _arr("tags"),
        _arr("keywords"),
        *_loc("question"),
        *_loc("answer"),
        _active(),
        sa.Column("source", sa.Text(), nullable=False),
        _now("updated_at"),
        sa.CheckConstraint(
            "num_nonnulls(answer_ru, answer_kk, answer_en) > 0", name="ck_facts_answer"
        ),
        _kb_source("facts"),
        sa.PrimaryKeyConstraint("id", name="pk_facts"),
    )
    op.create_table(
        "places",
        sa.Column("id", sa.Text(), primary_key=True),
        sa.Column("map_name", sa.Text(), nullable=False),
        *_loc("name"),
        _arr("synonyms"),
        *_loc("description"),
        sa.Column("visual_hints", sa.Text(), nullable=True),
        _arr("photos"),
        sa.Column("floor", sa.SmallInteger(), nullable=True),
        _active(),
        sa.Column("source", sa.Text(), nullable=False),
        _now("updated_at"),
        _kb_source("places"),
        sa.PrimaryKeyConstraint("id", name="pk_places"),
    )
    op.create_index(
        "uq_places_map_name_active",
        "places",
        ["map_name"],
        unique=True,
        postgresql_where=sa.text("is_active"),
    )
    op.create_table(
        "quick_questions",
        sa.Column("id", sa.Text(), primary_key=True),
        *_loc("text"),
        sa.Column("sort_order", sa.Integer(), nullable=False, server_default=sa.text("0")),
        _active(),
        sa.Column("source", sa.Text(), nullable=False),
        _now("updated_at"),
        _kb_source("quick_questions"),
        sa.PrimaryKeyConstraint("id", name="pk_quick_questions"),
    )
    op.create_table(
        "events",
        sa.Column("id", sa.Text(), primary_key=True),
        *_loc("title"),
        *_loc("description"),
        sa.Column("starts_at", TZ, nullable=False),
        sa.Column("ends_at", TZ, nullable=True),
        sa.Column(
            "place_id",
            sa.Text(),
            sa.ForeignKey("places.id", ondelete="SET NULL", name="fk_events_place_id_places"),
            nullable=True,
        ),
        _active(),
        sa.Column("source", sa.Text(), nullable=False),
        _now("updated_at"),
        _kb_source("events"),
        sa.PrimaryKeyConstraint("id", name="pk_events"),
    )
    op.create_index("ix_events_starts_at", "events", ["starts_at"])
    op.create_table(
        "documents",
        _id_identity(),
        sa.Column("title", sa.Text(), nullable=False),
        sa.Column("source_url", sa.Text(), nullable=True),
        sa.Column("lang", pg.CHAR(2), nullable=True),
        sa.Column("content", sa.Text(), nullable=False),
        sa.Column("content_hash", sa.Text(), nullable=False),
        _active(),
        _now("updated_at"),
        sa.PrimaryKeyConstraint("id", name="pk_documents"),
    )
    op.create_table(
        "kb_sync",
        _id_identity(),
        sa.Column("source", sa.Text(), nullable=False),
        _now("started_at"),
        sa.Column("finished_at", TZ, nullable=True),
        sa.Column("status", sa.Text(), nullable=False),
        sa.Column("version", sa.Text(), nullable=True),
        sa.Column("counts", pg.JSONB(), nullable=False, server_default=sa.text("'{}'::jsonb")),
        sa.Column("error", sa.Text(), nullable=True),
        sa.CheckConstraint(
            "source IN ('sheet', 'yaml', 'drive', 'admin')", name="ck_kb_sync_source"
        ),
        sa.CheckConstraint("status IN ('ok', 'error', 'skipped')", name="ck_kb_sync_status"),
        sa.PrimaryKeyConstraint("id", name="pk_kb_sync"),
    )
    op.create_index("ix_kb_sync_status_id", "kb_sync", ["status", "id"])

    # ---------- 2. Векторный поиск
    op.create_table(
        "kb_chunks",
        _id_identity(),
        sa.Column("source_type", sa.Text(), nullable=False),
        sa.Column("source_id", sa.Text(), nullable=False),
        sa.Column("lang", pg.CHAR(2), nullable=True),
        sa.Column("content", sa.Text(), nullable=False),
        sa.Column("content_hash", sa.Text(), nullable=False),
        sa.Column("embedding", Vector(EMBEDDING_DIM), nullable=True),
        sa.Column("embedding_model", sa.Text(), nullable=True),
        sa.Column(
            "tsv",
            pg.TSVECTOR(),
            sa.Computed("to_tsvector('simple'::regconfig, content)", persisted=True),
            nullable=True,
        ),
        _now("updated_at"),
        sa.CheckConstraint(
            "source_type IN ('fact', 'place', 'event', 'document')",
            name="ck_kb_chunks_source_type",
        ),
        sa.PrimaryKeyConstraint("id", name="pk_kb_chunks"),
    )
    op.create_index(
        "ix_kb_chunks_embedding_hnsw",
        "kb_chunks",
        ["embedding"],
        postgresql_using="hnsw",
        postgresql_ops={"embedding": "vector_cosine_ops"},
    )
    op.create_index("ix_kb_chunks_tsv_gin", "kb_chunks", ["tsv"], postgresql_using="gin")
    op.create_index("ix_kb_chunks_source", "kb_chunks", ["source_type", "source_id"])
    op.create_index("ix_kb_chunks_lang", "kb_chunks", ["lang"])

    # ---------- 3. Разговоры и аналитика
    op.create_table(
        "robots",
        sa.Column("id", sa.Text(), primary_key=True),
        sa.Column("name", sa.Text(), nullable=False),
        sa.Column("token_hash", sa.Text(), nullable=False),
        _active(),
        _now("created_at"),
        sa.Column("last_seen_at", TZ, nullable=True),
        sa.PrimaryKeyConstraint("id", name="pk_robots"),
        sa.UniqueConstraint("token_hash", name="uq_robots_token_hash"),
    )
    op.create_table(
        "sessions",
        sa.Column("id", sa.Text(), primary_key=True),
        sa.Column(
            "robot_id",
            sa.Text(),
            sa.ForeignKey("robots.id", ondelete="SET NULL", name="fk_sessions_robot_id_robots"),
            nullable=True,
        ),
        sa.Column("lang", sa.String(8), nullable=True),
        _now("started_at"),
        _now("last_seen_at"),
        sa.Column("ended_at", TZ, nullable=True),
        sa.PrimaryKeyConstraint("id", name="pk_sessions"),
    )
    op.create_index("ix_sessions_last_seen_at", "sessions", ["last_seen_at"])
    op.create_table(
        "messages",
        _id_identity(),
        sa.Column(
            "session_id",
            sa.Text(),
            sa.ForeignKey(
                "sessions.id", ondelete="CASCADE", name="fk_messages_session_id_sessions"
            ),
            nullable=False,
        ),
        sa.Column("role", sa.Text(), nullable=False),
        sa.Column("content", sa.Text(), nullable=False),
        sa.Column("tool_name", sa.Text(), nullable=True),
        sa.Column("tool_args", pg.JSONB(), nullable=True),
        _now("created_at"),
        sa.CheckConstraint("role IN ('visitor', 'assistant', 'tool')", name="ck_messages_role"),
        sa.PrimaryKeyConstraint("id", name="pk_messages"),
    )
    op.create_index("ix_messages_session_id_created_at", "messages", ["session_id", "created_at"])

    _question_log()

    op.create_table(
        "feedback",
        _id_identity(),
        sa.Column(
            "question_log_id",
            sa.BigInteger(),
            sa.ForeignKey(
                "question_log.id",
                ondelete="CASCADE",
                name="fk_feedback_question_log_id_question_log",
            ),
            nullable=False,
        ),
        sa.Column("rating", sa.SmallInteger(), nullable=False),
        sa.Column("comment", sa.Text(), nullable=True),
        sa.Column("created_by", sa.Text(), nullable=False),
        _now("created_at"),
        sa.CheckConstraint("rating IN (-1, 1)", name="ck_feedback_rating"),
        sa.PrimaryKeyConstraint("id", name="pk_feedback"),
    )
    op.create_index("ix_feedback_question_log_id", "feedback", ["question_log_id"])


def _question_log() -> None:
    bind = op.get_bind()
    if not sa.inspect(bind).has_table("question_log"):
        op.create_table(
            "question_log",
            sa.Column("id", sa.BigInteger(), primary_key=True, autoincrement=True),
            _now("created_at"),
            sa.Column("request_id", sa.String(64), nullable=False),
            sa.Column("session_id", sa.String(64), nullable=False),
            sa.Column("endpoint", sa.String(16), nullable=False),
            sa.Column("question", sa.Text(), nullable=False),
            sa.Column("lang", sa.String(8), nullable=False),
            sa.Column("place_id", sa.String(64), nullable=True),
            sa.Column("found", sa.Boolean(), nullable=False),
            sa.Column("category", sa.String(16), nullable=True),
            sa.Column("answer", sa.Text(), nullable=False),
            sa.Column("sources", pg.JSONB(), nullable=False, server_default=sa.text("'[]'::jsonb")),
            sa.Column("provider", sa.String(64), nullable=False),
            sa.Column("latency_ms", sa.Integer(), nullable=False),
            sa.PrimaryKeyConstraint("id", name="pk_question_log"),
        )
        for col in QUESTION_LOG_INDEXES:
            op.create_index(f"ix_question_log_{col}", "question_log", [col])
        return

    # Таблица из времён до Alembic (create_all + ALTER ADD COLUMN category): приводим к схеме
    op.execute("ALTER TABLE question_log ADD COLUMN IF NOT EXISTS category VARCHAR(16)")
    op.execute("ALTER TABLE question_log ALTER COLUMN id TYPE bigint")
    op.execute(
        """
        DO $$
        DECLARE seq text := pg_get_serial_sequence('question_log', 'id');
        BEGIN
            IF seq IS NOT NULL THEN
                EXECUTE format('ALTER SEQUENCE %s AS bigint', seq);
            END IF;
        END $$
        """
    )
    op.execute(
        """
        DO $$
        BEGIN
            IF (SELECT data_type FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name = 'question_log'
                  AND column_name = 'sources') <> 'jsonb' THEN
                ALTER TABLE question_log ALTER COLUMN sources DROP DEFAULT;
                ALTER TABLE question_log ALTER COLUMN sources TYPE jsonb USING (
                    CASE WHEN sources IS NULL OR btrim(sources) = '' THEN '[]'
                         ELSE sources END
                )::jsonb;
            END IF;
        END $$
        """
    )
    op.execute("UPDATE question_log SET sources = '[]'::jsonb WHERE sources IS NULL")
    op.execute("ALTER TABLE question_log ALTER COLUMN sources SET DEFAULT '[]'::jsonb")
    op.execute("ALTER TABLE question_log ALTER COLUMN sources SET NOT NULL")
    op.execute("ALTER TABLE question_log ALTER COLUMN created_at SET DEFAULT now()")
    # Имя первичного ключа create_all (question_log_pkey) → по соглашению моделей
    op.execute(
        """
        DO $$
        BEGIN
            IF EXISTS (SELECT 1 FROM pg_constraint
                       WHERE conname = 'question_log_pkey'
                         AND conrelid = 'question_log'::regclass) THEN
                ALTER TABLE question_log RENAME CONSTRAINT question_log_pkey TO pk_question_log;
            END IF;
        END $$
        """
    )
    for col in QUESTION_LOG_INDEXES:
        op.execute(f"CREATE INDEX IF NOT EXISTS ix_question_log_{col} ON question_log ({col})")


def downgrade() -> None:
    # question_log не удаляем: в нём данные, которые были до Alembic
    op.drop_table("feedback")
    op.drop_table("messages")
    op.drop_table("sessions")
    op.drop_table("robots")
    op.drop_table("kb_chunks")
    op.drop_table("kb_sync")
    op.drop_table("documents")
    op.drop_table("events")
    op.drop_table("quick_questions")
    op.drop_table("places")
    op.drop_table("facts")
