"""Переключатели вкладки «Робот и настройки» робота Курултая (docs/kurultai/robot-settings.md): движок live
(realtime / pipeline) и режим зала (normal / noisy) хранятся на сервере — переживают переустановку APK.
Нет строки — значение по умолчанию из .env. У Astana Hub таблица пустая.

Revision ID: 0007_kurultai_runtime_settings
Revises: 0006_kurultai_chunks
Create Date: 2026-11-10
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision: str = "0007_kurultai_runtime_settings"
down_revision: str | None = "0006_kurultai_chunks"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "kurultai_runtime_settings",
        sa.Column("key", sa.Text(), primary_key=True),
        sa.Column("value", postgresql.JSONB(), nullable=False),
        sa.Column(
            "updated_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False
        ),
        sa.Column("updated_by", sa.Text(), nullable=True),
    )


def downgrade() -> None:
    op.drop_table("kurultai_runtime_settings")
