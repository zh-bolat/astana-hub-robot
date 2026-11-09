"""Поиск робота Курултая (app/kurultai/rag): kurultai_chunks — фрагменты снимка quryltai.kz и материалов админки
с эмбеддингом (pgvector) и полнотекстом. Отдельно от kb_chunks (база Astana Hub). У Astana Hub таблица пустая.

Revision ID: 0006_kurultai_chunks
Revises: 0005_kurultai_materials
Create Date: 2026-11-10
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op
from pgvector.sqlalchemy import Vector
from sqlalchemy.dialects import postgresql

revision: str = "0006_kurultai_chunks"
down_revision: str | None = "0005_kurultai_materials"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None

TSV = "to_tsvector('simple'::regconfig, ((COALESCE(title, ''::text) || ' '::text) || content))"


def upgrade() -> None:
    op.create_table(
        "kurultai_chunks",
        sa.Column("id", sa.BigInteger(), sa.Identity(), primary_key=True),
        sa.Column("source", sa.Text(), nullable=False),
        sa.Column("source_id", sa.Text(), nullable=False),
        sa.Column("chunk_no", sa.Integer(), server_default=sa.text("0"), nullable=False),
        sa.Column("title", sa.Text(), nullable=True),
        sa.Column("content", sa.Text(), nullable=False),
        sa.Column("content_hash", sa.Text(), nullable=False),
        sa.Column("embedding", Vector(768), nullable=True),
        sa.Column("embedding_model", sa.Text(), nullable=True),
        sa.Column("tsv", postgresql.TSVECTOR(), sa.Computed(TSV, persisted=True), nullable=True),
        sa.Column(
            "updated_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False
        ),
        sa.CheckConstraint(
            "source IN ('bill', 'request', 'committee', 'plan', 'material')",
            name=op.f("ck_kurultai_chunks_source"),
        ),
        sa.UniqueConstraint(
            "source",
            "source_id",
            "chunk_no",
            name=op.f("uq_kurultai_chunks_source_source_id_chunk_no"),
        ),
    )
    op.create_index(
        "ix_kurultai_chunks_tsv_gin", "kurultai_chunks", ["tsv"], postgresql_using="gin"
    )
    op.create_index("ix_kurultai_chunks_source", "kurultai_chunks", ["source", "source_id"])


def downgrade() -> None:
    op.drop_index("ix_kurultai_chunks_source", table_name="kurultai_chunks")
    op.drop_index("ix_kurultai_chunks_tsv_gin", table_name="kurultai_chunks")
    op.drop_table("kurultai_chunks")
