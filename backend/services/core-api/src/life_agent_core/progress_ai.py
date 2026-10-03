"""Progress parsing and advice have separate ports; neither can write to the database."""

import re
from decimal import Decimal
from typing import Annotated, Protocol

from pydantic import BaseModel, ConfigDict, Field, model_validator

from life_agent_core.schemas import GoalResponse, ProgressMetricUpdateRequest


class AIUnavailableError(Exception):
    pass


class ProgressPreviewRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)
    body: str = Field(min_length=1, max_length=2000)


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


class ProgressParser(Protocol):
    def parse(self, context: GoalResponse, body: str) -> ProgressPreview: ...


class Advisor(Protocol):
    def advise(self, context: GoalResponse) -> Advice: ...


def validate_candidates(result: ProgressPreview, context: GoalResponse) -> ProgressPreview:
    metrics = {m.id: m for m in context.metrics}
    for item in result.metric_updates:
        metric = metrics.get(item.metric_id)
        if metric is None or (
            Decimal(str(metric.current_value)) + Decimal(str(item.value))
            > Decimal(str(metric.target_value))
        ):
            raise ValueError("Invalid metric or value exceeds target")
    return result


class FakeProgressParser:
    def parse(self, context: GoalResponse, body: str) -> ProgressPreview:
        # Intentionally conservative: this fake is a deterministic demo, not an NLP engine.
        updates = []
        ambiguous = re.search(r"合計|累計|予定|つもり|ない|なかった|目標|約|くらい", body)
        if not ambiguous:
            for metric in context.metrics:
                if sum(m.unit == metric.unit for m in context.metrics) != 1:
                    continue
                matches = re.findall(
                    r"(?<![\d.\-])([0-9]+(?:\.[0-9]{1,2})?)\s*" + re.escape(metric.unit), body
                )
                if len(matches) == 1 and re.search(r"読|完成|作|トレーニング|実施|進", body):
                    value = float(matches[0])
                    if 0 < value <= metric.target_value - metric.current_value:
                        updates.append(
                            ProgressMetricUpdateRequest(metric_id=metric.id, value=value)
                        )
        return ProgressPreview(
            metric_updates=updates,
            warnings=[]
            if updates
            else ["今回の増加量を特定できませんでした。Metricを手入力してください。"],
        )


class FakeAdvisor:
    def advise(self, context: GoalResponse) -> Advice:
        remaining = [m for m in context.metrics if m.current_value < m.target_value]
        if not remaining:
            return Advice(
                summary="すべてのMetricが目標に到達しました。",
                next_actions=["進捗履歴を振り返り、今回できたことを整理しましょう。"],
            )
        metric = remaining[0]
        return Advice(
            summary=f"「{context.title}」の保存済み進捗を確認しました。",
            next_actions=[
                f"次は「{metric.name}」に取り組む時間を決めましょう。",
                "無理のない量を進めて、終わったら今回の増加量を記録しましょう。",
            ],
        )
