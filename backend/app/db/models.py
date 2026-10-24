"""SQLAlchemy-модели всех таблиц. Источник истины по схеме — docs/db-schema.md.

Схема создаётся и меняется только миграциями Alembic (backend/migrations/); эти модели —
для запросов и для проверки, что миграции и модели совпадают (tests/test_migrations.py).
"""

from __future__ import annotations

from datetime import datetime
from typing import Any

from pgvector.sqlalchemy import Vector
from sqlalchemy import (
    BigInteger,
    Boolean,
    CheckConstraint,
    Computed,
    DateTime,
    ForeignKey,
    Identity,
    Index,
    Integer,
    MetaData,
    SmallInteger,
    String,
    Text,
    false,
    func,
    text,
)
from sqlalchemy.dialects.postgresql import ARRAY, CHAR, JSONB, TSVECTOR
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column

# Размерность эмбеддингов (docs/db-schema.md, kb_chunks.embedding); смена — новой миграцией
EMBEDDING_DIM = 768

NAMING = {
    "ix": "ix_%(table_name)s_%(column_0_N_name)s",
    "uq": "uq_%(table_name)s_%(column_0_N_name)s",
    "ck": "ck_%(table_name)s_%(constraint_name)s",
    "fk": "fk_%(table_name)s_%(column_0_name)s_%(referred_table_name)s",
    "pk": "pk_%(table_name)s",
}


class Base(DeclarativeBase):
    metadata = MetaData(naming_convention=NAMING)


def _now() -> Any:
    return mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now())


def _active() -> Any:
    return mapped_column(Boolean, nullable=False, server_default=text("true"))


def _text_array() -> Any:
    return mapped_column(ARRAY(Text), nullable=False, server_default=text("'{}'::text[]"))


def _kb_source_check() -> CheckConstraint:
    return CheckConstraint("source IN ('sheet', 'yaml', 'admin')", name="source")


# ---------------------------------------------------------------- 1. Знания


class FactRow(Base):
    __tablename__ = "facts"
    __table_args__ = (
        CheckConstraint("num_nonnulls(answer_ru, answer_kk, answer_en) > 0", name="answer"),
        _kb_source_check(),
    )

    id: Mapped[str] = mapped_column(Text, primary_key=True)
    tags: Mapped[list[str]] = _text_array()
    keywords: Mapped[list[str]] = _text_array()
    question_ru: Mapped[str | None] = mapped_column(Text)
    question_kk: Mapped[str | None] = mapped_column(Text)
    question_en: Mapped[str | None] = mapped_column(Text)
    answer_ru: Mapped[str | None] = mapped_column(Text)
    answer_kk: Mapped[str | None] = mapped_column(Text)
    answer_en: Mapped[str | None] = mapped_column(Text)
    is_active: Mapped[bool] = _active()
    source: Mapped[str] = mapped_column(Text, nullable=False)
    updated_at: Mapped[datetime] = _now()


class PlaceRow(Base):
    __tablename__ = "places"
    __table_args__ = (
        # map_name уникален среди активных точек: выключенная строка не мешает завести новую
        # точку с тем же именем на карте (отклонение от «map_name UNIQUE» в db-schema.md)
        Index(
            "uq_places_map_name_active",
            "map_name",
            unique=True,
            postgresql_where=text("is_active"),
        ),
        _kb_source_check(),
    )

    id: Mapped[str] = mapped_column(Text, primary_key=True)
    map_name: Mapped[str] = mapped_column(Text, nullable=False)
    name_ru: Mapped[str | None] = mapped_column(Text)
    name_kk: Mapped[str | None] = mapped_column(Text)
    name_en: Mapped[str | None] = mapped_column(Text)
    synonyms: Mapped[list[str]] = _text_array()
    description_ru: Mapped[str | None] = mapped_column(Text)
    description_kk: Mapped[str | None] = mapped_column(Text)
    description_en: Mapped[str | None] = mapped_column(Text)
    visual_hints: Mapped[str | None] = mapped_column(Text)
    # Эталонные фото (колонка photos в таблице/YAML; нет в db-schema.md v1)
    photos: Mapped[list[str]] = _text_array()
    floor: Mapped[int | None] = mapped_column(SmallInteger)
    is_active: Mapped[bool] = _active()
    source: Mapped[str] = mapped_column(Text, nullable=False)
    updated_at: Mapped[datetime] = _now()


class QuickQuestionRow(Base):
    __tablename__ = "quick_questions"
    __table_args__ = (_kb_source_check(),)

    id: Mapped[str] = mapped_column(Text, primary_key=True)
    text_ru: Mapped[str | None] = mapped_column(Text)
    text_kk: Mapped[str | None] = mapped_column(Text)
    text_en: Mapped[str | None] = mapped_column(Text)
    sort_order: Mapped[int] = mapped_column(Integer, nullable=False, server_default=text("0"))
    is_active: Mapped[bool] = _active()
    source: Mapped[str] = mapped_column(Text, nullable=False)
    updated_at: Mapped[datetime] = _now()


class EventRow(Base):
    __tablename__ = "events"
    __table_args__ = (_kb_source_check(),)

    id: Mapped[str] = mapped_column(Text, primary_key=True)
    title_ru: Mapped[str | None] = mapped_column(Text)
    title_kk: Mapped[str | None] = mapped_column(Text)
    title_en: Mapped[str | None] = mapped_column(Text)
    description_ru: Mapped[str | None] = mapped_column(Text)
    description_kk: Mapped[str | None] = mapped_column(Text)
    description_en: Mapped[str | None] = mapped_column(Text)
    starts_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, index=True)
    ends_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    place_id: Mapped[str | None] = mapped_column(Text, ForeignKey("places.id", ondelete="SET NULL"))
    is_active: Mapped[bool] = _active()
    source: Mapped[str] = mapped_column(Text, nullable=False)
    updated_at: Mapped[datetime] = _now()


class DocumentRow(Base):
    __tablename__ = "documents"

    id: Mapped[int] = mapped_column(BigInteger, Identity(), primary_key=True)
    title: Mapped[str] = mapped_column(Text, nullable=False)
    source_url: Mapped[str | None] = mapped_column(Text)
    lang: Mapped[str | None] = mapped_column(CHAR(2))
    content: Mapped[str] = mapped_column(Text, nullable=False)
    content_hash: Mapped[str] = mapped_column(Text, nullable=False)
    is_active: Mapped[bool] = _active()
    updated_at: Mapped[datetime] = _now()


class KbSyncRow(Base):
    __tablename__ = "kb_sync"
    __table_args__ = (
        CheckConstraint("source IN ('sheet', 'yaml', 'drive', 'admin')", name="source"),
        CheckConstraint("status IN ('ok', 'error', 'skipped')", name="status"),
        Index("ix_kb_sync_status_id", "status", "id"),
    )

    id: Mapped[int] = mapped_column(BigInteger, Identity(), primary_key=True)
    source: Mapped[str] = mapped_column(Text, nullable=False)
    started_at: Mapped[datetime] = _now()
    finished_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    status: Mapped[str] = mapped_column(Text, nullable=False)
    version: Mapped[str | None] = mapped_column(Text)
    counts: Mapped[dict] = mapped_column(JSONB, nullable=False, server_default=text("'{}'::jsonb"))
    error: Mapped[str | None] = mapped_column(Text)


# ---------------------------------------------------------------- 2. Векторный поиск


class KbChunkRow(Base):
    __tablename__ = "kb_chunks"
    __table_args__ = (
        CheckConstraint(
            "source_type IN ('fact', 'place', 'event', 'document')", name="source_type"
        ),
        Index(
            "ix_kb_chunks_embedding_hnsw",
            "embedding",
            postgresql_using="hnsw",
            postgresql_ops={"embedding": "vector_cosine_ops"},
        ),
        Index("ix_kb_chunks_tsv_gin", "tsv", postgresql_using="gin"),
        Index("ix_kb_chunks_source", "source_type", "source_id"),
    )

    id: Mapped[int] = mapped_column(BigInteger, Identity(), primary_key=True)
    source_type: Mapped[str] = mapped_column(Text, nullable=False)
    source_id: Mapped[str] = mapped_column(Text, nullable=False)
    lang: Mapped[str | None] = mapped_column(CHAR(2), index=True)
    content: Mapped[str] = mapped_column(Text, nullable=False)
    content_hash: Mapped[str] = mapped_column(Text, nullable=False)
    embedding: Mapped[Any] = mapped_column(Vector(EMBEDDING_DIM), nullable=True)
    embedding_model: Mapped[str | None] = mapped_column(Text)
    tsv: Mapped[Any] = mapped_column(
        TSVECTOR, Computed("to_tsvector('simple'::regconfig, content)", persisted=True)
    )
    updated_at: Mapped[datetime] = _now()


# ---------------------------------------------------------------- 3. Разговоры и аналитика


class RobotRow(Base):
    __tablename__ = "robots"

    id: Mapped[str] = mapped_column(Text, primary_key=True)
    name: Mapped[str] = mapped_column(Text, nullable=False)
    token_hash: Mapped[str] = mapped_column(Text, nullable=False, unique=True)
    is_active: Mapped[bool] = _active()
    created_at: Mapped[datetime] = _now()
    last_seen_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))


class SessionRow(Base):
    __tablename__ = "sessions"

    id: Mapped[str] = mapped_column(Text, primary_key=True)
    robot_id: Mapped[str | None] = mapped_column(Text, ForeignKey("robots.id", ondelete="SET NULL"))
    lang: Mapped[str | None] = mapped_column(String(8))
    started_at: Mapped[datetime] = _now()
    last_seen_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now(), index=True
    )
    ended_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))


class MessageRow(Base):
    __tablename__ = "messages"
    __table_args__ = (
        CheckConstraint("role IN ('visitor', 'assistant', 'tool')", name="role"),
        Index("ix_messages_session_id_created_at", "session_id", "created_at"),
    )

    id: Mapped[int] = mapped_column(BigInteger, Identity(), primary_key=True)
    session_id: Mapped[str] = mapped_column(
        Text, ForeignKey("sessions.id", ondelete="CASCADE"), nullable=False
    )
    role: Mapped[str] = mapped_column(Text, nullable=False)
    content: Mapped[str] = mapped_column(Text, nullable=False)
    tool_name: Mapped[str | None] = mapped_column(Text)
    tool_args: Mapped[dict | None] = mapped_column(JSONB)
    created_at: Mapped[datetime] = _now()


class QuestionLog(Base):
    """Лог вопросов. Типы колонок — как в таблице, созданной до Alembic (её подхватывает
    baseline-миграция), кроме id → bigint и sources → jsonb."""

    __tablename__ = "question_log"

    # bigserial (а не identity) — как у таблицы, созданной до Alembic
    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now(), index=True
    )
    request_id: Mapped[str] = mapped_column(String(64), nullable=False, index=True)
    session_id: Mapped[str] = mapped_column(String(64), nullable=False, index=True)
    endpoint: Mapped[str] = mapped_column(String(16), nullable=False)  # ask | look
    question: Mapped[str] = mapped_column(Text, nullable=False)
    lang: Mapped[str] = mapped_column(String(8), nullable=False)
    place_id: Mapped[str | None] = mapped_column(String(64))
    found: Mapped[bool] = mapped_column(Boolean, nullable=False, index=True)
    # kb | small_talk | general | out_of_scope | blocked
    category: Mapped[str | None] = mapped_column(String(16), index=True)
    answer: Mapped[str] = mapped_column(Text, nullable=False)
    sources: Mapped[list] = mapped_column(JSONB, nullable=False, server_default=text("'[]'::jsonb"))
    provider: Mapped[str] = mapped_column(String(64), nullable=False)
    latency_ms: Mapped[int] = mapped_column(Integer, nullable=False)
    # посетитель перебил робота: answer — только фразы, успевшие уйти (SSE оборван до done)
    interrupted: Mapped[bool] = mapped_column(Boolean, nullable=False, server_default=false())


class FeedbackRow(Base):
    __tablename__ = "feedback"
    __table_args__ = (CheckConstraint("rating IN (-1, 1)", name="rating"),)

    id: Mapped[int] = mapped_column(BigInteger, Identity(), primary_key=True)
    question_log_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("question_log.id", ondelete="CASCADE"), nullable=False, index=True
    )
    rating: Mapped[int] = mapped_column(SmallInteger, nullable=False)
    comment: Mapped[str | None] = mapped_column(Text)
    created_by: Mapped[str] = mapped_column(Text, nullable=False)
    created_at: Mapped[datetime] = _now()


# ---------------------------------------------------------------- 4. Мониторинг задержек


class LatencyEvent(Base):
    """Тайминги этапов (docs/api-contract.md, «Мониторинг задержек»). Только числа и метки —
    без текстов посетителей. kind: stt | chat | ask | look | tts | robot; stage — имя этапа
    (stt_ms, llm_first_token_ms, first_phrase_ms, llm_total_ms, tts_first_byte_ms, …).
    ms = NULL — служебная строка хода (stage=turn у робота: флаги fallback/interrupted)."""

    __tablename__ = "latency_events"
    __table_args__ = (Index("ix_latency_events_kind_ts", "kind", "ts"),)

    id: Mapped[int] = mapped_column(BigInteger, Identity(), primary_key=True)
    ts: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now(), index=True
    )
    kind: Mapped[str] = mapped_column(String(16), nullable=False)
    stage: Mapped[str] = mapped_column(String(32), nullable=False)
    ms: Mapped[int | None] = mapped_column(Integer)
    lang: Mapped[str | None] = mapped_column(String(8))
    provider: Mapped[str | None] = mapped_column(String(128))
    request_id: Mapped[str | None] = mapped_column(String(64))
    session_id: Mapped[str | None] = mapped_column(String(64))
    ok: Mapped[bool] = _active()
    extra: Mapped[dict] = mapped_column(JSONB, nullable=False, server_default=text("'{}'::jsonb"))


# Таблицы, которые очищаются в тестах (порядок не важен — TRUNCATE ... CASCADE)
ALL_TABLES = [t.name for t in Base.metadata.sorted_tables]
