"""add goal owners

Revision ID: 20260912_03
Revises: 20260830_02
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "20260912_03"
down_revision: str | None = "20260830_02"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column("goals", sa.Column("owner_id", sa.String(length=128), nullable=False))
    op.create_index(op.f("ix_goals_owner_id"), "goals", ["owner_id"], unique=False)


def downgrade() -> None:
    op.drop_index(op.f("ix_goals_owner_id"), table_name="goals")
    op.drop_column("goals", "owner_id")
