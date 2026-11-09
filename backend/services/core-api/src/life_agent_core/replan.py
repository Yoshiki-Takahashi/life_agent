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
    MetricDraft,
    MilestoneDraft,
    ReplanApplyRequest,
    ReplanCandidate,
    ReplanChange,
    ReplanDiffItem,
    ReplanProposal,
    ReplanRequest,
)

router = APIRouter()


class Replanner(Protocol):
    def propose(self, context: GoalResponse, reason: str) -> ReplanCandidate: ...


def validate_replan(result: ReplanCandidate, context: GoalResponse) -> ReplanCandidate:
    current_by_position = dict(enumerate(context.metrics))
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
        changes = plan_changes(context, candidate.proposed_plan)
        if not changes:
            raise ValueError("計画に変更がありません")
    except (AIUnavailableError, ValueError, TypeError) as error:
        raise HTTPException(
            status_code=503, detail="再計画候補を生成できませんでした。再試行してください"
        ) from error

    proposal = ReplanProposalModel(
        owner_id=user_id,
        base_plan_revision=context.plan_revision,
        reason=payload.reason,
        proposed_plan=candidate.proposed_plan.model_dump(mode="json"),
        diff=[item.model_dump(mode="json") for item in changes],
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
        changes = plan_changes(goal_response(goal), proposed_plan)
        excluded = set(payload.excluded_change_ids)
        change_ids = {item.change_id for item in changes}
        if not excluded <= change_ids or excluded == change_ids:
            raise HTTPException(status_code=422, detail="保存する変更を1件以上選んでください")
        apply_plan(goal, proposed_plan, excluded)
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
        diff=[ReplanChange.model_validate(item) for item in proposal.diff],
        created_at=proposal.created_at,
    )


def plan_changes(context: GoalResponse, proposed: GoalPlan) -> list[ReplanChange]:
    """The plan, rather than AI prose, defines each selectable change by position."""
    changes = []
    for field, label in (("title", "目標名"), ("description", "説明"), ("target_date", "目標期限")):
        before, after = getattr(context, field), getattr(proposed, field)
        if before != after:
            changes.append(
                ReplanChange(
                    change_id=f"goal:{field}",
                    change_type="update",
                    target_type="goal",
                    target_label=label,
                    before=str(before) if before is not None else None,
                    after=str(after) if after is not None else None,
                    rationale="目標の内容を変更します。",
                )
            )
    for target, old, new, draft_type in (
        ("metric", context.metrics, proposed.metrics, MetricDraft),
        ("milestone", context.milestones, proposed.milestones, MilestoneDraft),
    ):
        for index in range(max(len(old), len(new))):
            before = (
                draft_type.model_validate(
                    old[index].model_dump(include=set(draft_type.model_fields))
                )
                if index < len(old)
                else None
            )
            after = new[index] if index < len(new) else None
            if before == after:
                continue
            item = after or before
            changes.append(
                ReplanChange(
                    change_id=f"{target}:{index}",
                    change_type="add"
                    if before is None
                    else "remove"
                    if after is None
                    else "update",
                    target_type=target,
                    target_label=item.name if target == "metric" else item.title,
                    before=describe_item(before),
                    after=describe_item(after),
                    rationale="確認した変更だけを保存します。",
                )
            )
    return changes


def describe_item(item: MetricDraft | MilestoneDraft | None) -> str | None:
    if item is None:
        return None
    if isinstance(item, MetricDraft):
        kinds = {
            "cumulative": "累計",
            "occurrence": "実行日",
            "measurement": "測定値",
            "checklist": "チェックリスト",
        }
        text = f"{item.name}（{kinds[item.kind]}） {item.target_value:g} {item.unit}"
        if item.checklist_items:
            text += " / " + "、".join(item.checklist_items)
    else:
        text = f"{item.title} / {item.target_date}"
    return text


def apply_plan(goal: Goal, proposed: GoalPlan, excluded: set[str]) -> None:
    now = datetime.now(UTC)
    selected_metrics = []
    selected_milestones = []
    active = sorted((m for m in goal.metrics if m.archived_at is None), key=lambda m: m.position)
    milestones = sorted(goal.milestones, key=lambda m: m.position)
    # Keep source objects alongside drafts so excluding an earlier addition/removal never
    # assigns another Metric's saved progress or history to the remaining position.
    for target, current, candidates, draft_type, selected in (
        ("metric", active, proposed.metrics, MetricDraft, selected_metrics),
        ("milestone", milestones, proposed.milestones, MilestoneDraft, selected_milestones),
    ):
        for index in range(max(len(current), len(candidates))):
            original = current[index] if index < len(current) else None
            if f"{target}:{index}" in excluded:
                draft = (
                    draft_type.model_validate(
                        original.model_dump(include=set(draft_type.model_fields))
                    )
                    if original
                    else None
                )
            else:
                draft = candidates[index] if index < len(candidates) else None
            if draft is not None:
                selected.append((draft, original))
    fields = {
        field: getattr(goal if f"goal:{field}" in excluded else proposed, field)
        for field in ("title", "description", "target_date")
    }
    try:
        GoalPlan(
            **fields,
            metrics=[d for d, _ in selected_metrics],
            milestones=[d for d, _ in selected_milestones],
        )
    except ValueError as error:
        raise HTTPException(
            status_code=422,
            detail="残した変更の期限・件数が計画と合いません。棄却を戻すか候補を作り直してください",
        ) from error
    for draft, original in selected_metrics:
        if original and (
            draft.kind != original.kind
            or draft.unit != original.unit
            or draft.checklist_items != original.checklist_items
        ):
            raise HTTPException(
                status_code=422, detail="既存Metricの種別・単位・チェック項目は変更できません"
            )
        if (
            original
            and original.kind == "cumulative"
            and Decimal(str(draft.target_value)) < original.current_value
        ):
            raise HTTPException(
                status_code=422, detail="現在値を下回るMetric目標には変更できません"
            )
    for field, value in fields.items():
        setattr(goal, field, value)
    metrics = []
    for position, (draft, original) in enumerate(selected_metrics):
        metric = original or Metric(**draft.model_dump(), position=position)
        metric.name = draft.name
        metric.target_value = Decimal(str(draft.target_value))
        metric.position = position
        metrics.append(metric)
    retained_ids = {m.id for m in metrics}
    for metric in goal.metrics:
        if metric.id not in retained_ids:
            metric.archived_at = metric.archived_at or now
            metric.position = len(metrics)
            metrics.append(metric)
    goal.metrics = metrics
    goal.milestones = []
    for position, (draft, original) in enumerate(selected_milestones):
        milestone = original or Milestone(**draft.model_dump(), position=position)
        milestone.title = draft.title
        milestone.target_date = draft.target_date
        milestone.position = position
        goal.milestones.append(milestone)
