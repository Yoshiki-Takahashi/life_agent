from datetime import date, datetime
from typing import Annotated, Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator


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


class GoalQuestions(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)

    questions: list[Annotated[str, Field(min_length=1, max_length=200)]] = Field(
        min_length=3, max_length=8
    )

    @field_validator("questions")
    @classmethod
    def unique_questions(cls, value: list[str]) -> list[str]:
        if len(value) != len(set(value)):
            raise ValueError("Duplicate questions")
        return value


class GoalAnswer(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)

    question: str = Field(min_length=1, max_length=200)
    answer: bool = Field(strict=True)


class GoalPlanRequest(GoalInput):
    metric_types_enabled: bool = Field(default=False, strict=True)
    answers: list[GoalAnswer] = Field(default_factory=list, max_length=8)

    @field_validator("answers")
    @classmethod
    def unique_answers(cls, value: list[GoalAnswer]) -> list[GoalAnswer]:
        questions = [item.question for item in value]
        if len(questions) != len(set(questions)):
            raise ValueError("Duplicate answers")
        return value


MetricKind = Literal["cumulative", "occurrence", "measurement", "checklist"]
ChecklistLabel = Annotated[str, Field(min_length=1, max_length=120)]


class MetricDraft(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)

    name: str = Field(min_length=1, max_length=100)
    target_value: float = Field(gt=0, le=1_000_000_000, allow_inf_nan=False, multiple_of=0.01)
    unit: str = Field(min_length=1, max_length=30)
    kind: MetricKind = "cumulative"
    checklist_items: list[ChecklistLabel] = Field(default_factory=list, max_length=20)

    @model_validator(mode="after")
    def validate_kind(self) -> "MetricDraft":
        if len(self.checklist_items) != len(set(self.checklist_items)):
            raise ValueError("チェック項目を重複させないでください")
        if self.kind == "checklist":
            if not self.checklist_items or self.target_value != len(self.checklist_items):
                raise ValueError("チェックリストの目標値は項目数にしてください")
        elif self.checklist_items:
            raise ValueError("チェック項目はチェックリスト型だけに指定できます")
        if self.kind == "occurrence" and not self.target_value.is_integer():
            raise ValueError("実行日数の目標は整数にしてください")
        return self


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


class GoalPlan(GoalInput):
    metrics: list[MetricDraft] = Field(min_length=1, max_length=10)
    milestones: list[MilestoneDraft] = Field(min_length=1, max_length=10)

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
    kind: MetricKind | None = None
    value: float = Field(ge=0, le=1_000_000_000, allow_inf_nan=False, multiple_of=0.01)
    completed_items: list[ChecklistLabel] | None = Field(default=None, max_length=20)
    expected_completed_items: list[ChecklistLabel] | None = Field(default=None, max_length=20)
    activity_date: date | None = None


class ProgressPreview(BaseModel):
    model_config = ConfigDict(extra="forbid")

    metric_updates: list[ProgressMetricUpdateRequest] = Field(max_length=10)
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


class MetricResponse(MetricDraft):
    model_config = ConfigDict(extra="forbid")

    id: UUID
    name: str
    unit: str
    current_value: float
    target_value: float
    position: int
    created_at: datetime
    completed_items: list[str] = Field(default_factory=list)
    last_recorded_at: datetime | None = None


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
    kind: MetricKind = "cumulative"
    checklist_items: list[str] = Field(default_factory=list)
    completed_items: list[str] = Field(default_factory=list)
    activity_date: date | None = None


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


class CoachMessage(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)
    role: Literal["user", "assistant"]
    content: str = Field(min_length=1, max_length=2000)


class CoachRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)
    body: str = Field(min_length=1, max_length=2000)
    history: list[CoachMessage] = Field(default_factory=list, max_length=12)


class CoachMetric(BaseModel):
    model_config = ConfigDict(extra="forbid")
    name: str
    current_value: float
    target_value: float
    unit: str
    kind: MetricKind = "cumulative"
    checklist_items: list[str] = Field(default_factory=list)
    completed_items: list[str] = Field(default_factory=list)
    last_recorded_at: datetime | None = None


class CoachGoal(BaseModel):
    model_config = ConfigDict(extra="forbid")
    id: UUID
    title: str
    target_date: date
    metrics: list[CoachMetric]


class CoachAIRequest(CoachRequest):
    goals: list[CoachGoal]
    as_of_date: date


class CoachReply(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)
    reply: str = Field(min_length=1, max_length=2000)
    intent: Literal["discuss", "record"]
    goal_id: UUID | None
    record_body: str | None = Field(max_length=2000)

    @model_validator(mode="after")
    def coherent_action(self) -> "CoachReply":
        if self.intent == "record":
            if self.goal_id is None or not self.record_body:
                raise ValueError("Record needs an explicit target and source text")
        elif self.goal_id is not None or self.record_body is not None:
            raise ValueError("Discussion cannot carry a write target")
        return self
