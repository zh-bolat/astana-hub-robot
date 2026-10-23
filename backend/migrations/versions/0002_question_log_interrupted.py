"""question_log.interrupted: посетитель перебил робота (SSE /ask оборван до done)

Revision ID: 0002_question_log_interrupted
Revises: 0001_baseline
Create Date: 2026-10-23
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0002_question_log_interrupted"
down_revision: str | None = "0001_baseline"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column(
        "question_log",
        sa.Column("interrupted", sa.Boolean(), nullable=False, server_default=sa.false()),
    )


def downgrade() -> None:
    op.drop_column("question_log", "interrupted")
