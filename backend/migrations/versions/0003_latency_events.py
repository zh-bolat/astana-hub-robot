"""latency_events: тайминги этапов STT / агент / ask / look / TTS / робот (без текстов)

Revision ID: 0003_latency_events
Revises: 0002_question_log_interrupted
Create Date: 2026-10-24
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision: str = "0003_latency_events"
down_revision: str | None = "0002_question_log_interrupted"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "latency_events",
        sa.Column("id", sa.BigInteger(), sa.Identity(), primary_key=True),
        sa.Column("ts", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column("kind", sa.String(16), nullable=False),
        sa.Column("stage", sa.String(32), nullable=False),
        sa.Column("ms", sa.Integer(), nullable=True),
        sa.Column("lang", sa.String(8), nullable=True),
        sa.Column("provider", sa.String(128), nullable=True),
        sa.Column("request_id", sa.String(64), nullable=True),
        sa.Column("session_id", sa.String(64), nullable=True),
        sa.Column("ok", sa.Boolean(), server_default=sa.text("true"), nullable=False),
        sa.Column(
            "extra",
            postgresql.JSONB(astext_type=sa.Text()),
            server_default=sa.text("'{}'::jsonb"),
            nullable=False,
        ),
    )
    op.create_index("ix_latency_events_ts", "latency_events", ["ts"])
    op.create_index("ix_latency_events_kind_ts", "latency_events", ["kind", "ts"])


def downgrade() -> None:
    op.drop_index("ix_latency_events_kind_ts", table_name="latency_events")
    op.drop_index("ix_latency_events_ts", table_name="latency_events")
    op.drop_table("latency_events")
