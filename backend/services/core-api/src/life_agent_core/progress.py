import re
import uuid
from datetime import UTC, datetime, timedelta
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
from life_agent_core.database import (
    Database,
    document,
    read_in_transaction,
    request_key,
    run_transaction,
)
from life_agent_core.goals import goal_response, load_goal, load_goal_with_details, require_active
from life_agent_core.models import (
    ActivityDay,
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


def validate_update(item: ProgressMetricUpdateRequest, metric, *, preview: bool = False) -> None:
    if item.kind is not None and item.kind != metric.kind:
        raise ValueError("Metricの種別が一致しません")
    if not preview and metric.kind != "cumulative" and item.kind != metric.kind:
        raise ValueError("この種別の記録には新しいアプリを使用してください")
    value = Decimal(str(item.value))
    if metric.kind == "checklist":
        selected = item.completed_items
        if (
            selected is None
            or len(selected) != len(set(selected))
            or not set(selected) <= set(metric.checklist_items)
            or value != len(selected)
        ):
            raise ValueError("チェック項目と完了件数を確認してください")
    elif item.completed_items is not None or item.expected_completed_items is not None:
        raise ValueError("このMetricにはチェック項目を指定できません")
    if metric.kind == "occurrence":
        if value != 1 or (not preview and item.activity_date is None):
            raise ValueError("実行日は日付と1回を指定してください")
        if item.activity_date and item.activity_date > datetime.now(UTC).date() + timedelta(days=1):
            raise ValueError("未来の実行日は保存できません")
    elif item.activity_date is not None:
        raise ValueError("実行日型以外には実行日を指定できません")


def validate_candidates(result: ProgressPreview, context: GoalResponse) -> ProgressPreview:
    metrics = {m.id: m for m in context.metrics}
    for item in result.metric_updates:
        metric = metrics.get(item.metric_id)
        if metric is None:
            raise ValueError("Unknown metric")
        validate_update(item, metric, preview=True)
    return result


class FakeProgressParser:
    def parse(self, context: GoalResponse, body: str) -> ProgressPreview:
        # Intentionally conservative: this fake is a deterministic demo, not an NLP engine.
        updates = []
        ambiguous = re.search(r"合計|累計|予定|つもり|ない|なかった|目標|約|くらい", body)
        if not ambiguous:
            for metric in context.metrics:
                if metric.kind == "checklist":
                    selected = [label for label in metric.checklist_items if label in body]
                    if selected and re.search(r"完了|決めた|予約した", body):
                        completed = list(dict.fromkeys(metric.completed_items + selected))
                        updates.append(
                            {
                                "metric_id": metric.id,
                                "value": len(completed),
                                "completed_items": completed,
                            }
                        )
                    continue
                if metric.kind == "occurrence":
                    if metric.name in body and re.search(r"実施|行った|読んだ|した", body):
                        updates.append({"metric_id": metric.id, "value": 1})
                    continue
                if sum(m.unit == metric.unit for m in context.metrics) != 1:
                    continue
                matches = re.findall(
                    r"(?<![\d.\-])([0-9]+(?:\.[0-9]{1,2})?)\s*" + re.escape(metric.unit), body
                )
                if len(matches) == 1 and (
                    metric.kind == "measurement"
                    or re.search(r"読|完成|作|トレーニング|実施|進", body)
                ):
                    value = float(matches[0])
                    if 0 <= value <= 1_000_000_000:
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
        remaining = [
            m
            for m in context.metrics
            if m.kind == "measurement" or m.current_value < m.target_value
        ]
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
                "記録するときは、このMetricの種別に合う値や完了状態を確認しましょう。",
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
    goal_ref = db.collection("goals").document(str(goal_id))
    log_ref = goal_ref.collection("progress_logs").document(request_key(payload.client_request_id))

    def persist(transaction):
        goal = load_goal(goal_id, db, user_id, transaction)
        if read_in_transaction(log_ref, transaction).exists:
            return
        require_active(goal)
        metrics = {m.id: m for m in goal.metrics if m.archived_at is None}
        occurrence_refs = []
        for update in payload.metric_updates:
            metric = metrics.get(update.metric_id)
            if metric is None:
                raise HTTPException(
                    status_code=422, detail="Goalに含まれないMetricは更新できません"
                )
            try:
                validate_update(update, metric)
            except ValueError as error:
                raise HTTPException(status_code=422, detail=str(error)) from error
            if metric.kind == "checklist":
                if update.expected_completed_items is None:
                    raise HTTPException(
                        status_code=422, detail="保存前のチェック状態を指定してください"
                    )
                if set(update.expected_completed_items) != set(metric.completed_items):
                    raise HTTPException(
                        status_code=409, detail="チェック状態が更新されています。再読込してください"
                    )
            if metric.kind == "occurrence":
                ref = goal_ref.collection("activity_days").document(
                    f"{metric.id}_{update.activity_date.isoformat()}"
                )
                if read_in_transaction(ref, transaction).exists:
                    raise HTTPException(status_code=409, detail="この日の実行は記録済みです")
                occurrence_refs.append(ref)
        log = ProgressLog(
            body=payload.body,
            client_request_id=payload.client_request_id,
            metric_updates=[],
        )
        for update in payload.metric_updates:
            metric = metrics[update.metric_id]
            value = Decimal(str(update.value))
            metric.last_recorded_at = log.recorded_at
            if metric.kind in ("measurement", "checklist"):
                metric.current_value = value
            else:
                metric.current_value += value
            if metric.kind == "checklist":
                metric.completed_items = update.completed_items
            log.metric_updates.append(
                ProgressMetricUpdate(
                    metric_id=metric.id,
                    value=value,
                    kind=metric.kind,
                    completed_items=update.completed_items or [],
                    activity_date=update.activity_date,
                )
            )
        goal.updated_at = log.recorded_at
        transaction.set(goal_ref, document(goal))
        transaction.create(log_ref, document(log))
        for ref in occurrence_refs:
            transaction.create(ref, document(ActivityDay(log_id=log.id)))

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
