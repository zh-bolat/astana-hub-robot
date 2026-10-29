"""Поиск по базе (RAG): стабильный ключ документа, номер/заголовок/активность фрагмента, уникальный ключ

- documents: `slug` (имя файла без .md, уникален), `source` ('yaml' по умолчанию, как у фактов);
- kb_chunks: `is_active`, `chunk_no`, `title`, `lang` NOT NULL, UNIQUE (source_type, source_id, lang, chunk_no);
  `tsv` считается по заголовку и тексту фрагмента (раньше — только по тексту).
Фрагменты — производные данные (их пересобирает `kb index` / сервер), до этой миграции их никто не писал:
таблица очищается, чтобы новые ограничения встали на любую БД.

Revision ID: 0004_rag_chunks
Revises: 0003_latency_events
Create Date: 2026-10-26
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql as pg

revision: str = "0004_rag_chunks"
down_revision: str | None = "0003_latency_events"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None

CHUNK_KEY = ["source_type", "source_id", "lang", "chunk_no"]
TSV_OLD = "to_tsvector('simple'::regconfig, content)"
TSV_NEW = "to_tsvector('simple'::regconfig, ((COALESCE(title, ''::text) || ' '::text) || content))"


def _tsv(expr: str) -> None:
    op.drop_index("ix_kb_chunks_tsv_gin", table_name="kb_chunks")
    op.drop_column("kb_chunks", "tsv")
    op.add_column(
        "kb_chunks",
        sa.Column("tsv", pg.TSVECTOR(), sa.Computed(expr, persisted=True), nullable=True),
    )
    op.create_index("ix_kb_chunks_tsv_gin", "kb_chunks", ["tsv"], postgresql_using="gin")


def upgrade() -> None:
    # ---------- documents
    op.add_column("documents", sa.Column("slug", sa.Text(), nullable=True))
    op.execute("UPDATE documents SET slug = 'doc_' || id WHERE slug IS NULL")
    op.alter_column("documents", "slug", nullable=False)
    op.create_unique_constraint("uq_documents_slug", "documents", ["slug"])
    op.add_column(
        "documents",
        sa.Column("source", sa.Text(), nullable=False, server_default=sa.text("'yaml'")),
    )
    op.create_check_constraint(
        "ck_documents_source", "documents", "source IN ('sheet', 'yaml', 'drive', 'admin')"
    )

    # ---------- kb_chunks
    op.execute("DELETE FROM kb_chunks")
    op.add_column(
        "kb_chunks",
        sa.Column("is_active", sa.Boolean(), nullable=False, server_default=sa.text("true")),
    )
    op.add_column(
        "kb_chunks",
        sa.Column("chunk_no", sa.Integer(), nullable=False, server_default=sa.text("0")),
    )
    op.add_column("kb_chunks", sa.Column("title", sa.Text(), nullable=True))
    op.alter_column("kb_chunks", "lang", nullable=False)
    op.create_unique_constraint(
        "uq_kb_chunks_source_type_source_id_lang_chunk_no", "kb_chunks", CHUNK_KEY
    )
    _tsv(TSV_NEW)


def downgrade() -> None:
    _tsv(TSV_OLD)
    op.drop_constraint(
        "uq_kb_chunks_source_type_source_id_lang_chunk_no", "kb_chunks", type_="unique"
    )
    op.alter_column("kb_chunks", "lang", nullable=True)
    op.drop_column("kb_chunks", "title")
    op.drop_column("kb_chunks", "chunk_no")
    op.drop_column("kb_chunks", "is_active")
    op.drop_constraint("ck_documents_source", "documents", type_="check")
    op.drop_column("documents", "source")
    op.drop_constraint("uq_documents_slug", "documents", type_="unique")
    op.drop_column("documents", "slug")
