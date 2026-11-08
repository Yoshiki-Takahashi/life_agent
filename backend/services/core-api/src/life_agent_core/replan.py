import uuid
from datetime import UTC, date, datetime, timedelta
from decimal import Decimal
from functools import lru_cache
from typing import Annotated, Protocol

from fastapi import APIRouter, Depends, HTTPException

from life_agent_core.ai_service import (
    AIUnavailableError,
    HttpReplanner,
)
from life_agent_core.auth import CurrentUserId
from life_agent_core.config import get_settings
from life_agent_core.database import (
    Database,
    decode,
    document,
    read_in_transaction,
    run_transaction,
)
from life_agent_core.goals import goal_response, load_goal, load_goal_with_details, require_active
from life_agent_core.models import Goal, Metric, Milestone, ReplanProposalStatus
from life_agent_core.models import ReplanProposal as ReplanProposalModel
from life_agent_core.schemas import (
    GoalPlan,
    GoalResponse,
    ReplanApplyRequest,
    ReplanCandidate,
    ReplanDiffItem,
    ReplanProposal,
    ReplanRequest,
)

router = APIRouter()


class Replanner(Protocol):
    def propose(self, context: GoalResponse, reason: str) -> ReplanCandidate: ...


def validate_replan(result: ReplanCandidate, context: GoalResponse) -> ReplanCandidate:
    current_by_position = {metric.position: metric for metric in context.metrics}
    for index, item in enumerate(result.proposed_plan.metrics):
        current = current_by_position.get(index)
        if current is not None and (
            item.kind != current.kind
            or item.checklist_items != current.checklist_items
            or item.unit != current.unit
        ):
            raise ValueError("既存Metricの種別・単位・チェック項目は変更できません")
        if (
            current is not None
            and current.kind == "cumulative"
            and Decimal(str(item.target_value)) < Decimal(str(current.current_value))
        ):
            raise ValueError("Proposed metric target is below saved progress")
    return result


class FakeReplanner:
    def propose(self, context: GoalResponse, reason: str) -> ReplanCandidate:
        today = date.today()
        target_date = max(context.target_date + timedelta(days=14), today + timedelta(days=21))
        metric = context.metrics[0]
        current = Decimal(str(metric.current_value))
        current_target = Decimal(str(metric.target_value))
        proposed_target = max(current, (current_target * Decimal("0.75")).quantize(Decimal("0.01")))
        if proposed_target == current_target:
            proposed_target = current_target + Decimal("1.00")
        if metric.kind == "checklist":
            proposed_target = current_target
        if metric.kind == "occurrence":
            proposed_target = max(Decimal("1"), proposed_target.to_integral_value())
        days = max((target_date - today).days, 0)
        return ReplanCandidate(
            proposed_plan={
                "title": context.title,
                "description": context.description,
                "target_date": target_date,
                "metrics": [
                    {
                        "name": metric.name,
                        "target_value": float(proposed_target),
                        "unit": metric.unit,
                        "kind": metric.kind,
                        "checklist_items": metric.checklist_items,
                    }
                ],
                "milestones": [
                    {
                        "title": "見直し後の最初の一歩を完了する",
                        "target_date": today + timedelta(days=days // 3),
                    },
                    {
                        "title": "中間地点の進捗を確認する",
                        "target_date": today + timedelta(days=(days * 2) // 3),
                    },
                    {"title": "更新後のGoal期限を迎える", "target_date": target_date},
                ],
            },
            diff=[
                ReplanDiffItem(
                    change_type="update",
                    target_type="goal",
                    target_label="Goal期限",
                    before=context.target_date.isoformat(),
                    after=target_date.isoformat(),
                    rationale="現在の進捗と見直し理由に合わせて期限を延ばします。",
                ),
                ReplanDiffItem(
                    change_type="update",
                    target_type="metric",
                    target_label=metric.name,
                    before=f"{metric.target_value:g} {metric.unit}",
                    after=f"{proposed_target:g} {metric.unit}",
                    rationale="保存済み進捗を残したまま達成量を調整します。",
                ),
            ],
        )


@lru_cache
def get_replanner() -> Replanner:
    settings = get_settings()
    if settings.replanner_backend in ("http", "openai"):
        return HttpReplanner(settings)
    return FakeReplanner()


@router.post("/api/v1/goals/{goal_id}/replan/preview", response_model=ReplanProposal)
def preview_replan(
    goal_id: uuid.UUID,
    payload: ReplanRequest,
    db: Database,
    user_id: CurrentUserId,
    replanner: Annotated[Replanner, Depends(get_replanner)],
) -> ReplanProposal:
    goal = load_goal(goal_id, db, user_id)
    require_active(goal)
    context = goal_response(goal)
    try:
        candidate = validate_replan(replanner.propose(context, payload.reason), context)
    except (AIUnavailableError, ValueError, TypeError) as error:
        raise HTTPException(
            status_code=503, detail="再計画候補を生成できませんでした。再試行してください"
        ) from error

    proposal = ReplanProposalModel(
        owner_id=user_id,
        base_plan_revision=context.plan_revision,
        reason=payload.reason,
        proposed_plan=candidate.proposed_plan.model_dump(mode="json"),
        diff=[item.model_dump(mode="json") for item in candidate.diff],
    )
    (
        db.collection("goals")
        .document(str(goal_id))
        .collection("replan_proposals")
        .document(str(proposal.id))
        .create(document(proposal))
    )
    return replan_response(proposal)


@router.post("/api/v1/goals/{goal_id}/replan/apply", response_model=GoalResponse)
def apply_replan(
    goal_id: uuid.UUID,
    payload: ReplanApplyRequest,
    db: Database,
    user_id: CurrentUserId,
) -> GoalResponse:
    goal_ref = db.collection("goals").document(str(goal_id))
    proposal_ref = goal_ref.collection("replan_proposals").document(str(payload.proposal_id))

    def persist(transaction):
        goal = load_goal(goal_id, db, user_id, transaction)
        require_active(goal)
        snapshot = read_in_transaction(proposal_ref, transaction)
        if not snapshot.exists:
            raise HTTPException(status_code=404, detail="再計画候補が見つかりません")
        proposal = ReplanProposalModel.model_validate(decode(snapshot.to_dict()))
        if proposal.owner_id != user_id:
            raise HTTPException(status_code=404, detail="再計画候補が見つかりません")
        if proposal.status != ReplanProposalStatus.PENDING:
            raise HTTPException(status_code=409, detail="この再計画候補はすでに処理済みです")
        if goal.plan_revision != proposal.base_plan_revision:
            raise HTTPException(
                status_code=409, detail="Goalが更新されています。再計画候補を作り直してください"
            )
        proposed_plan = GoalPlan.model_validate(proposal.proposed_plan)
        validate_metric_targets(proposed_plan, goal)
        apply_plan(goal, proposed_plan)
        goal.plan_revision += 1
        goal.updated_at = datetime.now(UTC)
        proposal.status = ReplanProposalStatus.APPLIED
        proposal.applied_at = goal.updated_at
        transaction.set(goal_ref, document(goal))
        transaction.set(proposal_ref, document(proposal))

    run_transaction(db, persist)
    return goal_response(load_goal_with_details(goal_id, db, user_id))


def replan_response(proposal: ReplanProposalModel) -> ReplanProposal:
    return ReplanProposal(
        proposal_id=proposal.id,
        base_plan_revision=proposal.base_plan_revision,
        reason=proposal.reason,
        proposed_plan=GoalPlan.model_validate(proposal.proposed_plan),
        diff=[ReplanDiffItem.model_validate(item) for item in proposal.diff],
        created_at=proposal.created_at,
    )


def validate_metric_targets(proposed_plan: GoalPlan, goal: Goal) -> None:
    metrics_by_position = {metric.position: metric for metric in goal.metrics}
    for index, item in enumerate(proposed_plan.metrics):
        metric = metrics_by_position.get(index)
        if metric is not None and (
            item.kind != metric.kind
            or item.checklist_items != metric.checklist_items
            or item.unit != metric.unit
        ):
            raise HTTPException(
                status_code=422, detail="既存Metricの種別・単位・チェック項目は変更できません"
            )
        if (
            metric is not None
            and metric.kind == "cumulative"
            and Decimal(str(item.target_value)) < metric.current_value
        ):
            raise HTTPException(
                status_code=422,
                detail="現在値を下回るMetric目標には変更できません",
            )


def apply_plan(goal: Goal, proposed_plan: GoalPlan) -> None:
    now = datetime.now(UTC)
    goal.title = proposed_plan.title
    goal.description = proposed_plan.description
    goal.target_date = proposed_plan.target_date

    metrics_by_position = {metric.position: metric for metric in goal.metrics}
    for index, item in enumerate(proposed_plan.metrics):
        metric = metrics_by_position.get(index)
        if metric is None:
            goal.metrics.append(
                Metric(
                    **item.model_dump(),
                    position=index,
                )
            )
        else:
            metric.name = item.name
            metric.target_value = Decimal(str(item.target_value))
            metric.unit = item.unit
            metric.archived_at = None
    for metric in goal.metrics:
        if metric.position >= len(proposed_plan.metrics) and metric.archived_at is None:
            metric.archived_at = now

    milestones_by_position = {milestone.position: milestone for milestone in goal.milestones}
    for index, item in enumerate(proposed_plan.milestones):
        milestone = milestones_by_position.get(index)
        if milestone is None:
            goal.milestones.append(
                Milestone(title=item.title, target_date=item.target_date, position=index)
            )
        else:
            milestone.title = item.title
            milestone.target_date = item.target_date
    for milestone in list(goal.milestones):
        if milestone.position >= len(proposed_plan.milestones):
            goal.milestones.remove(milestone)
