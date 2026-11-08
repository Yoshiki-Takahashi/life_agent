import uuid
from datetime import UTC, date, datetime
from decimal import Decimal
from enum import StrEnum
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


def now() -> datetime:
    return datetime.now(UTC)


class GoalStatus(StrEnum):
    ACTIVE = "active"
    COMPLETED = "completed"


class ReplanProposalStatus(StrEnum):
    PENDING = "pending"
    APPLIED = "applied"


class StoredModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class Metric(StoredModel):
    id: uuid.UUID = Field(default_factory=uuid.uuid4)
    name: str
    target_value: Decimal
    current_value: Decimal = Decimal("0")
    unit: str
    kind: Literal["cumulative", "occurrence", "measurement", "checklist"] = "cumulative"
    checklist_items: list[str] = Field(default_factory=list)
    completed_items: list[str] = Field(default_factory=list)
    last_recorded_at: datetime | None = None
    position: int
    archived_at: datetime | None = None
    created_at: datetime = Field(default_factory=now)


class Milestone(StoredModel):
    id: uuid.UUID = Field(default_factory=uuid.uuid4)
    title: str
    target_date: date
    position: int
    created_at: datetime = Field(default_factory=now)


class ProgressMetricUpdate(StoredModel):
    metric_id: uuid.UUID
    value: Decimal
    kind: Literal["cumulative", "occurrence", "measurement", "checklist"] = "cumulative"
    completed_items: list[str] = Field(default_factory=list)
    activity_date: date | None = None
    # Resolved from the current (possibly archived) Metric definition when reading.
    metric_name: str = Field(default="", exclude=True)
    unit: str = Field(default="", exclude=True)


class ActivityDay(StoredModel):
    schema_version: Literal[1] = 1
    log_id: uuid.UUID


class ProgressLog(StoredModel):
    schema_version: Literal[1, 2] = 2
    id: uuid.UUID = Field(default_factory=uuid.uuid4)
    body: str
    client_request_id: str
    recorded_at: datetime = Field(default_factory=now)
    created_at: datetime = Field(default_factory=now)
    metric_updates: list[ProgressMetricUpdate]


class Goal(StoredModel):
    schema_version: Literal[1, 2] = 2
    id: uuid.UUID = Field(default_factory=uuid.uuid4)
    owner_id: str
    title: str
    description: str | None
    target_date: date
    plan_revision: int = 1
    status: GoalStatus = GoalStatus.ACTIVE
    created_at: datetime = Field(default_factory=now)
    updated_at: datetime = Field(default_factory=now)
    metrics: list[Metric]
    milestones: list[Milestone]
    progress_logs: list[ProgressLog] = Field(default_factory=list, exclude=True)


class ReplanProposal(StoredModel):
    schema_version: Literal[1] = 1
    id: uuid.UUID = Field(default_factory=uuid.uuid4)
    owner_id: str
    base_plan_revision: int
    reason: str
    proposed_plan: dict
    diff: list[dict]
    status: ReplanProposalStatus = ReplanProposalStatus.PENDING
    created_at: datetime = Field(default_factory=now)
    applied_at: datetime | None = None
