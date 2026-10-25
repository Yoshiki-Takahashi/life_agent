import uuid
from datetime import date, datetime

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from life_agent_core.models import GoalStatus


class GoalInput(BaseModel):
    title: str = Field(min_length=1, max_length=120)
    description: str | None = Field(default=None, max_length=2000)
    target_date: date

    @field_validator("title", mode="before")
    @classmethod
    def normalize_title(cls, value: object) -> object:
        return value.strip() if isinstance(value, str) else value

    @field_validator("description", mode="before")
    @classmethod
    def normalize_description(cls, value: object) -> object:
        if isinstance(value, str):
            return value.strip() or None
        return value

    @field_validator("target_date")
    @classmethod
    def target_date_must_not_be_in_the_past(cls, value: date) -> date:
        if value < date.today():
            raise ValueError("Goal期限は今日以降にしてください")
        return value


class GoalPreviewRequest(GoalInput):
    pass


class MetricDraft(BaseModel):
    name: str = Field(min_length=1, max_length=100)
    target_value: float = Field(gt=0, le=1_000_000_000, allow_inf_nan=False, multiple_of=0.01)
    unit: str = Field(min_length=1, max_length=30)

    @field_validator("name", "unit", mode="before")
    @classmethod
    def normalize_text(cls, value: object) -> object:
        return value.strip() if isinstance(value, str) else value


class MilestoneDraft(BaseModel):
    title: str = Field(min_length=1, max_length=120)
    target_date: date

    @field_validator("title", mode="before")
    @classmethod
    def normalize_title(cls, value: object) -> object:
        return value.strip() if isinstance(value, str) else value


class GoalPlan(BaseModel):
    title: str
    description: str | None
    target_date: date
    metrics: list[MetricDraft] = Field(min_length=1, max_length=3)
    milestones: list[MilestoneDraft] = Field(min_length=3, max_length=5)

    @model_validator(mode="after")
    def validate_milestone_dates(self) -> "GoalPlan":
        validate_milestone_dates(self.milestones, self.target_date)
        return self


class ReplanRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)

    reason: str = Field(min_length=1, max_length=2000)


class ReplanDiffItem(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)

    change_type: str = Field(pattern="^(add|update|remove)$")
    target_type: str = Field(pattern="^(goal|metric|milestone)$")
    target_label: str = Field(min_length=1, max_length=120)
    before: str | None = Field(default=None, max_length=300)
    after: str | None = Field(default=None, max_length=300)
    rationale: str = Field(min_length=1, max_length=300)


class ReplanCandidate(BaseModel):
    model_config = ConfigDict(extra="forbid")

    proposed_plan: GoalPlan
    diff: list[ReplanDiffItem] = Field(min_length=1, max_length=12)


class ReplanProposal(BaseModel):
    model_config = ConfigDict(extra="forbid")

    proposal_id: uuid.UUID
    base_plan_revision: int
    reason: str
    proposed_plan: GoalPlan
    diff: list[ReplanDiffItem] = Field(min_length=1, max_length=12)
    created_at: datetime


class ReplanApplyRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    proposal_id: uuid.UUID


class GoalConfirmRequest(GoalInput):
    metrics: list[MetricDraft] = Field(min_length=1, max_length=3)
    milestones: list[MilestoneDraft] = Field(min_length=3, max_length=5)

    @model_validator(mode="after")
    def validate_milestone_dates(self) -> "GoalConfirmRequest":
        validate_milestone_dates(self.milestones, self.target_date)
        return self


def validate_milestone_dates(milestones: list[MilestoneDraft], target_date: date) -> None:
    previous = date.today()
    for milestone in milestones:
        if milestone.target_date < date.today() or milestone.target_date > target_date:
            raise ValueError("Milestone期限は今日からGoal期限までにしてください")
        if milestone.target_date < previous:
            raise ValueError("Milestone期限は表示順にしてください")
        previous = milestone.target_date


class MetricResponse(MetricDraft):
    id: uuid.UUID
    current_value: float
    position: int
    created_at: datetime

    model_config = ConfigDict(from_attributes=True)


class MilestoneResponse(MilestoneDraft):
    id: uuid.UUID
    position: int
    created_at: datetime

    model_config = ConfigDict(from_attributes=True)


class ProgressMetricUpdateRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    metric_id: uuid.UUID
    value: float = Field(gt=0, le=1_000_000_000, allow_inf_nan=False, multiple_of=0.01)


class ProgressLogCreateRequest(BaseModel):
    body: str = Field(min_length=1, max_length=2000)
    client_request_id: str = Field(min_length=1, max_length=64)
    metric_updates: list[ProgressMetricUpdateRequest] = Field(min_length=1, max_length=3)

    @field_validator("body", "client_request_id", mode="before")
    @classmethod
    def normalize_text(cls, value: object) -> object:
        return value.strip() if isinstance(value, str) else value

    @model_validator(mode="after")
    def metric_ids_must_be_unique(self) -> "ProgressLogCreateRequest":
        metric_ids = [item.metric_id for item in self.metric_updates]
        if len(metric_ids) != len(set(metric_ids)):
            raise ValueError("同じMetricを複数回更新できません")
        return self


class ProgressMetricUpdateResponse(BaseModel):
    metric_id: uuid.UUID
    metric_name: str
    value: float
    unit: str

    model_config = ConfigDict(from_attributes=True)


class ProgressLogResponse(BaseModel):
    id: uuid.UUID
    body: str
    recorded_at: datetime
    created_at: datetime
    metric_updates: list[ProgressMetricUpdateResponse]

    model_config = ConfigDict(from_attributes=True)


class GoalResponse(BaseModel):
    id: uuid.UUID
    title: str
    description: str | None
    target_date: date
    plan_revision: int
    status: GoalStatus
    metrics: list[MetricResponse]
    milestones: list[MilestoneResponse]
    progress_logs: list[ProgressLogResponse] = []
    created_at: datetime
    updated_at: datetime

    model_config = ConfigDict(from_attributes=True)


class GoalSummary(BaseModel):
    id: uuid.UUID
    title: str
    target_date: date
    status: GoalStatus
    metric_count: int
    milestone_count: int
    created_at: datetime
    updated_at: datetime
