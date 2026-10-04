import uuid
from datetime import date, datetime
from decimal import Decimal
from enum import StrEnum

from sqlalchemy import (
    JSON,
    Date,
    DateTime,
    Enum,
    ForeignKey,
    Integer,
    Numeric,
    String,
    Text,
    UniqueConstraint,
    func,
)
from sqlalchemy.orm import Mapped, mapped_column, relationship

from life_agent_core.database import Base


class GoalStatus(StrEnum):
    ACTIVE = "active"


class ReplanProposalStatus(StrEnum):
    PENDING = "pending"
    APPLIED = "applied"


class Goal(Base):
    __tablename__ = "goals"

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    owner_id: Mapped[str] = mapped_column(String(128), index=True)
    title: Mapped[str] = mapped_column(String(120))
    description: Mapped[str | None] = mapped_column(Text, nullable=True)
    target_date: Mapped[date] = mapped_column(Date)
    plan_revision: Mapped[int] = mapped_column(Integer, default=1)
    status: Mapped[GoalStatus] = mapped_column(
        Enum(
            GoalStatus,
            name="goal_status",
            values_callable=lambda values: [item.value for item in values],
        ),
        default=GoalStatus.ACTIVE,
    )
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now()
    )
    metrics: Mapped[list["Metric"]] = relationship(
        back_populates="goal", cascade="all, delete-orphan", order_by="Metric.position"
    )
    milestones: Mapped[list["Milestone"]] = relationship(
        back_populates="goal", cascade="all, delete-orphan", order_by="Milestone.position"
    )
    progress_logs: Mapped[list["ProgressLog"]] = relationship(
        back_populates="goal",
        cascade="all, delete-orphan",
        order_by=lambda: (ProgressLog.recorded_at.desc(), ProgressLog.id.desc()),
    )
    replan_proposals: Mapped[list["ReplanProposal"]] = relationship(
        back_populates="goal", cascade="all, delete-orphan"
    )


class Metric(Base):
    __tablename__ = "metrics"
    __table_args__ = (UniqueConstraint("goal_id", "position", name="uq_metrics_goal_position"),)

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    goal_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("goals.id", ondelete="CASCADE"))
    name: Mapped[str] = mapped_column(String(100))
    target_value: Mapped[Decimal] = mapped_column(Numeric(12, 2))
    current_value: Mapped[Decimal] = mapped_column(Numeric(12, 2), default=Decimal("0"))
    unit: Mapped[str] = mapped_column(String(30))
    position: Mapped[int] = mapped_column(Integer)
    archived_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    goal: Mapped[Goal] = relationship(back_populates="metrics")
    progress_updates: Mapped[list["ProgressMetricUpdate"]] = relationship(back_populates="metric")


class Milestone(Base):
    __tablename__ = "milestones"
    __table_args__ = (UniqueConstraint("goal_id", "position", name="uq_milestones_goal_position"),)

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    goal_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("goals.id", ondelete="CASCADE"))
    title: Mapped[str] = mapped_column(String(120))
    target_date: Mapped[date] = mapped_column(Date)
    position: Mapped[int] = mapped_column(Integer)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    goal: Mapped[Goal] = relationship(back_populates="milestones")


class ProgressLog(Base):
    __tablename__ = "progress_logs"
    __table_args__ = (
        UniqueConstraint("goal_id", "client_request_id", name="uq_progress_logs_goal_request"),
    )

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    goal_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("goals.id", ondelete="CASCADE"))
    body: Mapped[str] = mapped_column(Text)
    client_request_id: Mapped[str] = mapped_column(String(64))
    recorded_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    goal: Mapped[Goal] = relationship(back_populates="progress_logs")
    metric_updates: Mapped[list["ProgressMetricUpdate"]] = relationship(
        back_populates="progress_log", cascade="all, delete-orphan"
    )


class ProgressMetricUpdate(Base):
    __tablename__ = "progress_metric_updates"
    __table_args__ = (
        UniqueConstraint(
            "progress_log_id", "metric_id", name="uq_progress_metric_updates_log_metric"
        ),
    )

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    progress_log_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("progress_logs.id", ondelete="CASCADE")
    )
    metric_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("metrics.id", ondelete="CASCADE"))
    value: Mapped[Decimal] = mapped_column(Numeric(12, 2))
    progress_log: Mapped[ProgressLog] = relationship(back_populates="metric_updates")
    metric: Mapped[Metric] = relationship(back_populates="progress_updates")

    @property
    def metric_name(self) -> str:
        return self.metric.name

    @property
    def unit(self) -> str:
        return self.metric.unit


class ReplanProposal(Base):
    __tablename__ = "replan_proposals"

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    goal_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("goals.id", ondelete="CASCADE"))
    owner_id: Mapped[str] = mapped_column(String(128), index=True)
    base_plan_revision: Mapped[int] = mapped_column(Integer)
    reason: Mapped[str] = mapped_column(Text)
    proposed_plan: Mapped[dict] = mapped_column(JSON)
    diff: Mapped[list] = mapped_column(JSON)
    status: Mapped[ReplanProposalStatus] = mapped_column(
        Enum(
            ReplanProposalStatus,
            name="replan_proposal_status",
            values_callable=lambda values: [item.value for item in values],
        ),
        default=ReplanProposalStatus.PENDING,
    )
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    applied_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    goal: Mapped[Goal] = relationship(back_populates="replan_proposals")
