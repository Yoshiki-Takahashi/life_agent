"""add replan proposals

Revision ID: 20261004_05
Revises: 20261002_04
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "20261004_05"
down_revision: str | None = "20261002_04"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column(
        "goals",
        sa.Column("plan_revision", sa.Integer(), server_default="1", nullable=False),
    )
    op.add_column("metrics", sa.Column("archived_at", sa.DateTime(timezone=True), nullable=True))
    op.create_table(
        "replan_proposals",
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column("goal_id", sa.Uuid(), nullable=False),
        sa.Column("owner_id", sa.String(length=128), nullable=False),
        sa.Column("base_plan_revision", sa.Integer(), nullable=False),
        sa.Column("reason", sa.Text(), nullable=False),
        sa.Column("proposed_plan", sa.JSON(), nullable=False),
        sa.Column("diff", sa.JSON(), nullable=False),
        sa.Column(
            "status",
            sa.Enum("pending", "applied", name="replan_proposal_status"),
            nullable=False,
        ),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column("applied_at", sa.DateTime(timezone=True), nullable=True),
        sa.ForeignKeyConstraint(["goal_id"], ["goals.id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index("ix_replan_proposals_owner_id", "replan_proposals", ["owner_id"])


def downgrade() -> None:
    op.drop_index("ix_replan_proposals_owner_id", table_name="replan_proposals")
    op.drop_table("replan_proposals")
    op.drop_column("metrics", "archived_at")
    op.drop_column("goals", "plan_revision")
    op.execute("DROP TYPE IF EXISTS replan_proposal_status")
