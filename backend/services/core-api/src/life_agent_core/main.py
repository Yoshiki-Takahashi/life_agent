import uuid
from datetime import UTC, datetime
from decimal import Decimal
from functools import lru_cache
from typing import Annotated

from fastapi import Depends, FastAPI, HTTPException, status
from sqlalchemy import select
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session, selectinload

from life_agent_core.ai_service import HttpAdvisor, HttpProgressParser, HttpReplanner
from life_agent_core.auth import CurrentUserId
from life_agent_core.config import get_settings
from life_agent_core.database import get_db
from life_agent_core.models import (
    Goal,
    Metric,
    Milestone,
    ProgressLog,
    ProgressMetricUpdate,
    ReplanProposalStatus,
)
from life_agent_core.models import (
    ReplanProposal as ReplanProposalModel,
)
from life_agent_core.planner import (
    FakeGoalPlanner,
    GoalPlanner,
    HttpGoalPlanner,
    PlannerUnavailableError,
)
from life_agent_core.progress_ai import (
    Advice,
    Advisor,
    AIUnavailableError,
    FakeAdvisor,
    FakeProgressParser,
    FakeReplanner,
    ProgressParser,
    ProgressPreview,
    ProgressPreviewRequest,
    Replanner,
    validate_candidates,
    validate_replan,
)
from life_agent_core.schemas import (
    GoalConfirmRequest,
    GoalPlan,
    GoalPreviewRequest,
    GoalResponse,
    GoalSummary,
    MetricResponse,
    MilestoneResponse,
    ProgressLogCreateRequest,
    ProgressLogResponse,
    ReplanApplyRequest,
    ReplanDiffItem,
    ReplanProposal,
    ReplanRequest,
)

app = FastAPI(title="LifeAgent Core API", version="0.1.0")
DatabaseSession = Annotated[Session, Depends(get_db)]


@lru_cache
def get_goal_planner() -> GoalPlanner:
    settings = get_settings()
    if settings.goal_planner_backend == "http":
        return HttpGoalPlanner(settings)
    return FakeGoalPlanner()


Planner = Annotated[GoalPlanner, Depends(get_goal_planner)]


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/api/v1/goals/preview", response_model=GoalPlan)
def preview_goal(payload: GoalPreviewRequest, planner: Planner, user_id: CurrentUserId) -> GoalPlan:
    try:
        return planner.generate(payload)
    except PlannerUnavailableError as error:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="計画を生成できませんでした。入力内容を保持して再試行してください",
        ) from error


@app.post("/api/v1/goals/confirm", response_model=GoalResponse, status_code=status.HTTP_201_CREATED)
def confirm_goal(
    payload: GoalConfirmRequest, db: DatabaseSession, user_id: CurrentUserId
) -> GoalResponse:
    goal = Goal(
        owner_id=user_id,
        title=payload.title,
        description=payload.description,
        target_date=payload.target_date,
        metrics=[
            Metric(name=item.name, target_value=item.target_value, unit=item.unit, position=index)
            for index, item in enumerate(payload.metrics)
        ],
        milestones=[
            Milestone(title=item.title, target_date=item.target_date, position=index)
            for index, item in enumerate(payload.milestones)
        ],
    )
    db.add(goal)
    try:
        db.commit()
    except SQLAlchemyError as error:
        db.rollback()
        raise HTTPException(status_code=500, detail="Goalを保存できませんでした") from error
    db.refresh(goal)
    return goal_response(goal)


@app.post("/api/v1/goals/{goal_id}/progress", response_model=GoalResponse)
def record_progress(
    goal_id: uuid.UUID,
    payload: ProgressLogCreateRequest,
    db: DatabaseSession,
    user_id: CurrentUserId,
) -> GoalResponse:
    # Serialize writes for this Goal before loading metric values or checking replay IDs.
    locked = db.scalar(
        select(Goal.id)
        .where(
            Goal.id == goal_id,
            Goal.owner_id == user_id,
        )
        .with_for_update()
    )
    if locked is None:
        raise HTTPException(status_code=404, detail="Goalが見つかりません")
    goal = load_goal_with_details(goal_id, db, user_id)
    existing = db.scalar(
        select(ProgressLog).where(
            ProgressLog.goal_id == goal_id,
            ProgressLog.client_request_id == payload.client_request_id,
        )
    )
    if existing is not None:
        return goal_response(load_goal_with_details(goal_id, db, user_id))

    metrics_by_id = {metric.id: metric for metric in goal.metrics if metric.archived_at is None}
    missing_metric_ids = [
        item.metric_id for item in payload.metric_updates if item.metric_id not in metrics_by_id
    ]
    if missing_metric_ids:
        raise HTTPException(status_code=422, detail="Goalに含まれないMetricは更新できません")

    validated_updates: list[tuple[Metric, Decimal]] = []
    for item in payload.metric_updates:
        metric = metrics_by_id[item.metric_id]
        value = Decimal(str(item.value))
        next_value = metric.current_value + value
        if next_value > metric.target_value:
            raise HTTPException(
                status_code=422,
                detail="Metricの目標値を超える進捗は保存できません",
            )
        validated_updates.append((metric, value))

    progress_log = ProgressLog(
        goal=goal,
        body=payload.body,
        client_request_id=payload.client_request_id,
    )
    for metric, value in validated_updates:
        metric.current_value = metric.current_value + value
        progress_log.metric_updates.append(ProgressMetricUpdate(metric=metric, value=value))

    goal.updated_at = datetime.now(UTC)
    db.add(progress_log)
    try:
        db.commit()
    except SQLAlchemyError as error:
        db.rollback()
        raise HTTPException(status_code=500, detail="進捗を保存できませんでした") from error
    return goal_response(load_goal_with_details(goal_id, db, user_id))


@app.get("/api/v1/goals", response_model=list[GoalSummary])
def list_goals(db: DatabaseSession, user_id: CurrentUserId) -> list[GoalSummary]:
    goals = db.scalars(
        select(Goal)
        .options(selectinload(Goal.metrics), selectinload(Goal.milestones))
        .where(Goal.owner_id == user_id)
        .order_by(Goal.updated_at.desc(), Goal.id.desc())
    ).all()
    return [
        GoalSummary(
            id=goal.id,
            title=goal.title,
            target_date=goal.target_date,
            status=goal.status,
            metric_count=sum(1 for metric in goal.metrics if metric.archived_at is None),
            milestone_count=len(goal.milestones),
            created_at=goal.created_at,
            updated_at=goal.updated_at,
        )
        for goal in goals
    ]


@app.get("/api/v1/goals/{goal_id}", response_model=GoalResponse)
def get_goal(goal_id: uuid.UUID, db: DatabaseSession, user_id: CurrentUserId) -> GoalResponse:
    return goal_response(load_goal_with_details(goal_id, db, user_id))


def load_goal_with_details(goal_id: uuid.UUID, db: Session, user_id: str) -> Goal:
    goal = db.scalar(
        select(Goal)
        .options(
            selectinload(Goal.metrics),
            selectinload(Goal.milestones),
            selectinload(Goal.progress_logs)
            .selectinload(ProgressLog.metric_updates)
            .selectinload(ProgressMetricUpdate.metric),
        )
        .where(Goal.id == goal_id, Goal.owner_id == user_id)
    )
    if goal is None:
        raise HTTPException(status_code=404, detail="Goalが見つかりません")
    return goal


def goal_response(goal: Goal) -> GoalResponse:
    return GoalResponse(
        id=goal.id,
        title=goal.title,
        description=goal.description,
        target_date=goal.target_date,
        plan_revision=goal.plan_revision,
        status=goal.status,
        metrics=[
            MetricResponse.model_validate(metric)
            for metric in goal.metrics
            if metric.archived_at is None
        ],
        milestones=[MilestoneResponse.model_validate(milestone) for milestone in goal.milestones],
        progress_logs=[
            ProgressLogResponse.model_validate(progress_log)
            for progress_log in goal.progress_logs
        ],
        created_at=goal.created_at,
        updated_at=goal.updated_at,
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


@lru_cache
def get_replanner() -> Replanner:
    settings = get_settings()
    if settings.replanner_backend in ("http", "openai"):
        return HttpReplanner(settings)
    return FakeReplanner()


@app.post("/api/v1/goals/{goal_id}/progress/preview", response_model=ProgressPreview)
def preview_progress(
    goal_id: uuid.UUID,
    payload: ProgressPreviewRequest,
    db: DatabaseSession,
    user_id: CurrentUserId,
    parser: Annotated[ProgressParser, Depends(get_progress_parser)],
) -> ProgressPreview:
    context = goal_response(load_goal_with_details(goal_id, db, user_id))
    db.rollback()  # Do not keep a transaction open during AI generation.
    try:
        result = ProgressPreview.model_validate(parser.parse(context, payload.body))
        return validate_candidates(result, context)
    except (AIUnavailableError, ValueError, TypeError) as error:
        raise HTTPException(
            status_code=503, detail="解析できませんでした。再試行または手入力してください"
        ) from error


@app.post("/api/v1/goals/{goal_id}/advice", response_model=Advice)
def generate_advice(
    goal_id: uuid.UUID,
    db: DatabaseSession,
    user_id: CurrentUserId,
    advisor: Annotated[Advisor, Depends(get_advisor)],
) -> Advice:
    context = goal_response(load_goal_with_details(goal_id, db, user_id))
    context.progress_logs = context.progress_logs[:10]
    db.rollback()
    try:
        return Advice.model_validate(advisor.advise(context))
    except (AIUnavailableError, ValueError, TypeError) as error:
        raise HTTPException(
            status_code=503, detail="助言を取得できませんでした。助言だけ再試行できます"
        ) from error


@app.post("/api/v1/goals/{goal_id}/replan/preview", response_model=ReplanProposal)
def preview_replan(
    goal_id: uuid.UUID,
    payload: ReplanRequest,
    db: DatabaseSession,
    user_id: CurrentUserId,
    replanner: Annotated[Replanner, Depends(get_replanner)],
) -> ReplanProposal:
    context = goal_response(load_goal_with_details(goal_id, db, user_id))
    db.rollback()
    try:
        candidate = validate_replan(replanner.propose(context, payload.reason), context)
    except (AIUnavailableError, ValueError, TypeError) as error:
        raise HTTPException(
            status_code=503, detail="再計画候補を生成できませんでした。再試行してください"
        ) from error

    proposal = ReplanProposalModel(
        goal_id=goal_id,
        owner_id=user_id,
        base_plan_revision=context.plan_revision,
        reason=payload.reason,
        proposed_plan=candidate.proposed_plan.model_dump(mode="json"),
        diff=[item.model_dump(mode="json") for item in candidate.diff],
    )
    db.add(proposal)
    try:
        db.commit()
    except SQLAlchemyError as error:
        db.rollback()
        raise HTTPException(status_code=500, detail="再計画候補を保存できませんでした") from error
    db.refresh(proposal)
    return replan_response(proposal)


@app.post("/api/v1/goals/{goal_id}/replan/apply", response_model=GoalResponse)
def apply_replan(
    goal_id: uuid.UUID,
    payload: ReplanApplyRequest,
    db: DatabaseSession,
    user_id: CurrentUserId,
) -> GoalResponse:
    locked = db.scalar(
        select(Goal.id)
        .where(
            Goal.id == goal_id,
            Goal.owner_id == user_id,
        )
        .with_for_update()
    )
    if locked is None:
        raise HTTPException(status_code=404, detail="Goalが見つかりません")
    goal = load_goal_with_details(goal_id, db, user_id)
    proposal = db.scalar(
        select(ReplanProposalModel).where(
            ReplanProposalModel.id == payload.proposal_id,
            ReplanProposalModel.goal_id == goal_id,
            ReplanProposalModel.owner_id == user_id,
        )
    )
    if proposal is None:
        raise HTTPException(status_code=404, detail="再計画候補が見つかりません")
    if proposal.status != ReplanProposalStatus.PENDING:
        raise HTTPException(status_code=409, detail="この再計画候補はすでに処理済みです")
    if goal.plan_revision != proposal.base_plan_revision:
        raise HTTPException(
            status_code=409,
            detail="Goalが更新されています。再計画候補を作り直してください",
        )

    proposed_plan = GoalPlan.model_validate(proposal.proposed_plan)
    validate_metric_targets(proposed_plan, goal)
    apply_plan(goal, proposed_plan)
    goal.plan_revision += 1
    goal.updated_at = datetime.now(UTC)
    proposal.status = ReplanProposalStatus.APPLIED
    proposal.applied_at = datetime.now(UTC)
    try:
        db.commit()
    except SQLAlchemyError as error:
        db.rollback()
        raise HTTPException(status_code=500, detail="再計画を保存できませんでした") from error
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
        if metric is not None and Decimal(str(item.target_value)) < metric.current_value:
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
                    name=item.name,
                    target_value=item.target_value,
                    unit=item.unit,
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
