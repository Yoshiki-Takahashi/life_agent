from datetime import date, datetime
from typing import Annotated
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator


class GoalPlanRequest(BaseModel):
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


class MetricDraft(BaseModel):
    model_config = ConfigDict(extra="forbid")

    name: str = Field(min_length=1, max_length=100)
    target_value: float = Field(gt=0, le=1_000_000_000)
    unit: str = Field(min_length=1, max_length=30)

    @field_validator("name", "unit", mode="before")
    @classmethod
    def normalize_text(cls, value: object) -> object:
        return value.strip() if isinstance(value, str) else value


class MilestoneDraft(BaseModel):
    model_config = ConfigDict(extra="forbid")

    title: str = Field(min_length=1, max_length=120)
    target_date: date

    @field_validator("title", mode="before")
    @classmethod
    def normalize_title(cls, value: object) -> object:
        return value.strip() if isinstance(value, str) else value


class GeneratedPlan(BaseModel):
    model_config = ConfigDict(extra="forbid")

    metrics: list[MetricDraft] = Field(min_length=1, max_length=3)
    milestones: list[MilestoneDraft] = Field(min_length=3, max_length=5)


class GoalPlan(GoalPlanRequest):
    metrics: list[MetricDraft] = Field(min_length=1, max_length=3)
    milestones: list[MilestoneDraft] = Field(min_length=3, max_length=5)

    @model_validator(mode="after")
    def validate_milestone_dates(self) -> "GoalPlan":
        previous = date.today()
        for milestone in self.milestones:
            if milestone.target_date < date.today() or milestone.target_date > self.target_date:
                raise ValueError("Milestone期限は今日からGoal期限までにしてください")
            if milestone.target_date < previous:
                raise ValueError("Milestone期限は表示順にしてください")
            previous = milestone.target_date
        return self


class ReplanDiffItem(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)

    change_type: str = Field(pattern="^(add|update|remove)$")
    target_type: str = Field(pattern="^(goal|metric|milestone)$")
    target_label: str = Field(min_length=1, max_length=120)
    before: str | None = Field(default=None, max_length=300)
    after: str | None = Field(default=None, max_length=300)
    rationale: str = Field(min_length=1, max_length=300)


class GeneratedReplan(BaseModel):
    model_config = ConfigDict(extra="forbid")

    proposed_plan: GoalPlan
    diff: list[ReplanDiffItem] = Field(min_length=1, max_length=12)


class ProgressMetricUpdateRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    metric_id: UUID
    value: float = Field(gt=0, le=1_000_000_000, allow_inf_nan=False, multiple_of=0.01)


class ProgressPreview(BaseModel):
    model_config = ConfigDict(extra="forbid")

    metric_updates: list[ProgressMetricUpdateRequest] = Field(max_length=3)
    warnings: list[Annotated[str, Field(min_length=1, max_length=300)]] = Field(max_length=3)

    @model_validator(mode="after")
    def unique_metrics(self) -> "ProgressPreview":
        ids = [item.metric_id for item in self.metric_updates]
        if len(ids) != len(set(ids)):
            raise ValueError("Duplicate metric")
        return self


class Advice(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)

    summary: str = Field(min_length=1, max_length=500)
    next_actions: list[Annotated[str, Field(min_length=1, max_length=300)]] = Field(
        min_length=1,
        max_length=3,
    )


class MetricResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    id: UUID
    name: str
    unit: str
    current_value: float
    target_value: float
    position: int
    created_at: datetime


class MilestoneResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    id: UUID
    title: str
    target_date: date
    position: int
    created_at: datetime


class ProgressMetricUpdateResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    metric_id: UUID
    metric_name: str
    value: float
    unit: str


class ProgressLogResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    id: UUID
    body: str
    recorded_at: datetime
    created_at: datetime
    metric_updates: list[ProgressMetricUpdateResponse]


class GoalResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    id: UUID
    title: str
    description: str | None
    target_date: date
    plan_revision: int = 1
    status: str
    metrics: list[MetricResponse]
    milestones: list[MilestoneResponse] = []
    progress_logs: list[ProgressLogResponse] = []
    created_at: datetime
    updated_at: datetime


class ProgressPreviewAIRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    goal: GoalResponse
    body: str = Field(min_length=1, max_length=2000)


class AdviceAIRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    goal: GoalResponse


class ReplanAIRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)

    goal: GoalResponse
    reason: str = Field(min_length=1, max_length=2000)
