"""Материалы аппарата Курултая для робота (админка /app/admin, app/kurultai/materials/).

- kurultai_materials: загруженный файл или текст → извлечённый текст → структурированная справка (правит сотрудник)
  → опубликовано (идёт в промпт робота Курултая, пока не истёк срок valid_until);
- kurultai_material_events: журнал действий в админке (кто/когда загрузил, правил, опубликовал, удалил).
У Astana Hub таблицы пустые и не используются.

Revision ID: 0005_kurultai_materials
Revises: 0004_rag_chunks
Create Date: 2026-11-06
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision: str = "0005_kurultai_materials"
down_revision: str | None = "0004_rag_chunks"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None

KINDS = "kind IN ('meeting', 'agenda', 'bill', 'reference', 'contacts', 'other')"
STATUSES = "status IN ('processing', 'draft', 'published', 'error')"


def upgrade() -> None:
    op.create_table(
        "kurultai_materials",
        sa.Column("id", sa.BigInteger(), sa.Identity(), primary_key=True),
        sa.Column("title", sa.Text(), nullable=False),
        sa.Column("kind", sa.Text(), server_default=sa.text("'other'"), nullable=False),
        sa.Column("status", sa.Text(), server_default=sa.text("'processing'"), nullable=False),
        sa.Column("filename", sa.Text(), nullable=True),
        sa.Column("mime", sa.Text(), nullable=True),
        sa.Column("file_sha256", sa.Text(), nullable=True),
        sa.Column("file_bytes", postgresql.BYTEA(), nullable=True),
        sa.Column("source_text", sa.Text(), server_default=sa.text("''"), nullable=False),
        sa.Column("brief", sa.Text(), server_default=sa.text("''"), nullable=False),
        sa.Column(
            "warnings",
            postgresql.JSONB(astext_type=sa.Text()),
            server_default=sa.text("'[]'::jsonb"),
            nullable=False,
        ),
        sa.Column("error", sa.Text(), nullable=True),
        sa.Column("event_date", sa.Date(), nullable=True),
        sa.Column("valid_until", sa.DateTime(timezone=True), nullable=True),
        sa.Column("published_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column(
            "created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False
        ),
        sa.Column(
            "updated_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False
        ),
        sa.CheckConstraint(KINDS, name=op.f("ck_kurultai_materials_kind")),
        sa.CheckConstraint(STATUSES, name=op.f("ck_kurultai_materials_status")),
    )
    op.create_index("ix_kurultai_materials_status", "kurultai_materials", ["status"])
    op.create_table(
        "kurultai_material_events",
        sa.Column("id", sa.BigInteger(), sa.Identity(), primary_key=True),
        sa.Column("ts", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column(
            "material_id",
            sa.BigInteger(),
            sa.ForeignKey(
                "kurultai_materials.id",
                ondelete="SET NULL",
                name=op.f("fk_kurultai_material_events_material_id_kurultai_materials"),
            ),
            nullable=True,
        ),
        sa.Column("action", sa.Text(), nullable=False),
        sa.Column("title", sa.Text(), nullable=True),
        sa.Column("client_ip", sa.Text(), nullable=True),
        sa.Column(
            "detail",
            postgresql.JSONB(astext_type=sa.Text()),
            server_default=sa.text("'{}'::jsonb"),
            nullable=False,
        ),
    )
    op.create_index("ix_kurultai_material_events_ts", "kurultai_material_events", ["ts"])


def downgrade() -> None:
    op.drop_index("ix_kurultai_material_events_ts", table_name="kurultai_material_events")
    op.drop_table("kurultai_material_events")
    op.drop_index("ix_kurultai_materials_status", table_name="kurultai_materials")
    op.drop_table("kurultai_materials")
