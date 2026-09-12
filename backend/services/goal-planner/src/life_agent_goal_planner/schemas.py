from datetime import date

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
