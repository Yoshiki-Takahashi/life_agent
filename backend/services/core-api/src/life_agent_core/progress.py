import re
import uuid
from datetime import UTC, datetime
from decimal import Decimal
from functools import lru_cache
from typing import Annotated, Protocol

from fastapi import APIRouter, Depends, HTTPException

from life_agent_core.ai_service import (
    AIUnavailableError,
    HttpAdvisor,
    HttpProgressParser,
)
from life_agent_core.auth import CurrentUserId
from life_agent_core.config import get_settings
from life_agent_core.database import Database, document, request_key, run_transaction
from life_agent_core.goals import goal_response, load_goal, load_goal_with_details
from life_agent_core.models import (
    ProgressLog,
    ProgressMetricUpdate,
)
from life_agent_core.schemas import (
    Advice,
    GoalResponse,
    ProgressLogCreateRequest,
    ProgressMetricUpdateRequest,
    ProgressPreview,
    ProgressPreviewRequest,
)

router = APIRouter()


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


@lru_cache
def get_progress_parser() -> ProgressParser:
    settings = get_settings()
    if settings.progress_parser_backend in ("http", "openai"):
        return HttpProgressParser(settings)
    return FakeProgressParser()


@lru_cache
def get_advisor() -> Advisor:
    settings = get_settings()
    if settings.advisor_backend in ("http", "openai"):
        return HttpAdvisor(settings)
    return FakeAdvisor()


@router.post("/api/v1/goals/{goal_id}/progress", response_model=GoalResponse)
def record_progress(
    goal_id: uuid.UUID,
    payload: ProgressLogCreateRequest,
    db: Database,
    user_id: CurrentUserId,
) -> GoalResponse:
    log = ProgressLog(
        body=payload.body,
        client_request_id=payload.client_request_id,
        metric_updates=[
            ProgressMetricUpdate(metric_id=item.metric_id, value=Decimal(str(item.value)))
            for item in payload.metric_updates
        ],
    )
    goal_ref = db.collection("goals").document(str(goal_id))
    log_ref = goal_ref.collection("progress_logs").document(request_key(payload.client_request_id))

    def persist(transaction):
        goal = load_goal(goal_id, db, user_id, transaction)
        if log_ref.get(transaction=transaction).exists:
            return
        metrics = {m.id: m for m in goal.metrics if m.archived_at is None}
        for update in log.metric_updates:
            metric = metrics.get(update.metric_id)
            if metric is None:
                raise HTTPException(
                    status_code=422, detail="Goalに含まれないMetricは更新できません"
                )
            if metric.current_value + update.value > metric.target_value:
                raise HTTPException(
                    status_code=422, detail="Metricの目標値を超える進捗は保存できません"
                )
            metric.current_value += update.value
        goal.updated_at = datetime.now(UTC)
        transaction.set(goal_ref, document(goal))
        transaction.create(log_ref, document(log))

    run_transaction(db, persist)
    return goal_response(load_goal_with_details(goal_id, db, user_id))


@router.post("/api/v1/goals/{goal_id}/progress/preview", response_model=ProgressPreview)
def preview_progress(
    goal_id: uuid.UUID,
    payload: ProgressPreviewRequest,
    db: Database,
    user_id: CurrentUserId,
    parser: Annotated[ProgressParser, Depends(get_progress_parser)],
) -> ProgressPreview:
    context = goal_response(load_goal(goal_id, db, user_id))
    try:
        result = ProgressPreview.model_validate(parser.parse(context, payload.body))
        return validate_candidates(result, context)
    except (AIUnavailableError, ValueError, TypeError) as error:
        raise HTTPException(
            status_code=503, detail="解析できませんでした。再試行または手入力してください"
        ) from error


@router.post("/api/v1/goals/{goal_id}/advice", response_model=Advice)
def generate_advice(
    goal_id: uuid.UUID,
    db: Database,
    user_id: CurrentUserId,
    advisor: Annotated[Advisor, Depends(get_advisor)],
) -> Advice:
    context = goal_response(load_goal_with_details(goal_id, db, user_id, history_limit=10))
    try:
        return Advice.model_validate(advisor.advise(context))
    except (AIUnavailableError, ValueError, TypeError) as error:
        raise HTTPException(
            status_code=503, detail="助言を取得できませんでした。助言だけ再試行できます"
        ) from error
