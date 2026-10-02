"""add progress logs

Revision ID: 20261002_04
Revises: 20260912_03
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "20261002_04"
down_revision: str | None = "20260912_03"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column(
        "metrics",
        sa.Column(
            "current_value",
            sa.Numeric(precision=12, scale=2),
            server_default="0",
            nullable=False,
        ),
    )
    op.create_table(
        "progress_logs",
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column("goal_id", sa.Uuid(), nullable=False),
        sa.Column("body", sa.Text(), nullable=False),
        sa.Column("client_request_id", sa.String(length=64), nullable=False),
        sa.Column(
            "recorded_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.ForeignKeyConstraint(["goal_id"], ["goals.id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("goal_id", "client_request_id", name="uq_progress_logs_goal_request"),
    )
    op.create_table(
        "progress_metric_updates",
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column("progress_log_id", sa.Uuid(), nullable=False),
        sa.Column("metric_id", sa.Uuid(), nullable=False),
        sa.Column("value", sa.Numeric(precision=12, scale=2), nullable=False),
        sa.ForeignKeyConstraint(["metric_id"], ["metrics.id"], ondelete="CASCADE"),
        sa.ForeignKeyConstraint(["progress_log_id"], ["progress_logs.id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint(
            "progress_log_id",
            "metric_id",
            name="uq_progress_metric_updates_log_metric",
        ),
    )


def downgrade() -> None:
    op.drop_table("progress_metric_updates")
    op.drop_table("progress_logs")
    op.drop_column("metrics", "current_value")
