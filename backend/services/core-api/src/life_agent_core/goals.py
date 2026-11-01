import uuid
from datetime import date, timedelta
from functools import lru_cache
from typing import Annotated, Protocol

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import select
from sqlalchemy.orm import Session, selectinload

from life_agent_core.ai_service import AIUnavailableError, InternalAIClient
from life_agent_core.auth import CurrentUserId
from life_agent_core.config import Settings, get_settings
from life_agent_core.database import DatabaseSession, commit
from life_agent_core.models import Goal, Metric, Milestone, ProgressLog, ProgressMetricUpdate
from life_agent_core.schemas import (
    GoalConfirmRequest,
    GoalPlan,
    GoalPreviewRequest,
    GoalResponse,
    GoalSummary,
    MetricDraft,
    MetricResponse,
    MilestoneDraft,
    MilestoneResponse,
    ProgressLogResponse,
)

router = APIRouter()


class GoalPlanner(Protocol):
    def generate(self, payload: GoalPreviewRequest) -> GoalPlan: ...


class FakeGoalPlanner:
    def generate(self, payload: GoalPreviewRequest) -> GoalPlan:
        return create_fake_plan(payload)


def create_fake_plan(payload: GoalPreviewRequest, start_date: date | None = None) -> GoalPlan:
    start = start_date or date.today()
    text = f"{payload.title} {payload.description or ''}".lower()

    if any(keyword in text for keyword in ("読書", "本", "読む", "book", "read")):
        metric = MetricDraft(name="読了冊数", target_value=12, unit="冊")
        titles = ("読む本を決める", "目標の半分を読み終える", "目標冊数を読み終える")
    elif any(keyword in text for keyword in ("アプリ", "開発", "実装", "app", "develop")):
        metric = MetricDraft(name="完成した成果物", target_value=1, unit="件")
        titles = ("要件を決める", "主要機能を実装する", "動作確認して完成させる")
    elif any(
        keyword in text for keyword in ("筋トレ", "トレーニング", "運動", "workout", "training")
    ):
        metric = MetricDraft(name="トレーニング回数", target_value=12, unit="回")
        titles = ("メニューと頻度を決める", "計画回数の半分を実施する", "目標回数を達成する")
    else:
        metric = MetricDraft(name="達成率", target_value=100, unit="%")
        titles = ("実行内容を決める", "中間目標を達成する", "Goalを達成する")

    days = max((payload.target_date - start).days, 0)
    dates = (
        start + timedelta(days=days // 3),
        start + timedelta(days=(days * 2) // 3),
        payload.target_date,
    )
    milestones = [
        MilestoneDraft(title=title, target_date=target_date)
        for title, target_date in zip(titles, dates, strict=True)
    ]
    return GoalPlan(
        title=payload.title,
        description=payload.description,
        target_date=payload.target_date,
        metrics=[metric],
        milestones=milestones,
    )


class HttpGoalPlanner:
    def __init__(self, settings: Settings):
        self.client = InternalAIClient(settings)

    def generate(self, payload: GoalPreviewRequest) -> GoalPlan:
        return self.client.generate("goal-plans", payload.model_dump(mode="json"), GoalPlan)


@lru_cache
def get_goal_planner() -> GoalPlanner:
    settings = get_settings()
    if settings.goal_planner_backend == "http":
        return HttpGoalPlanner(settings)
    return FakeGoalPlanner()


Planner = Annotated[GoalPlanner, Depends(get_goal_planner)]


@router.post("/api/v1/goals/preview", response_model=GoalPlan)
def preview_goal(payload: GoalPreviewRequest, planner: Planner, user_id: CurrentUserId) -> GoalPlan:
    try:
        return planner.generate(payload)
    except AIUnavailableError as error:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="計画を生成できませんでした。入力内容を保持して再試行してください",
        ) from error


@router.post(
    "/api/v1/goals/confirm", response_model=GoalResponse, status_code=status.HTTP_201_CREATED
)
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
    commit(db)
    db.refresh(goal)
    return goal_response(goal)


@router.get("/api/v1/goals", response_model=list[GoalSummary])
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


@router.get("/api/v1/goals/{goal_id}", response_model=GoalResponse)
def get_goal(goal_id: uuid.UUID, db: DatabaseSession, user_id: CurrentUserId) -> GoalResponse:
    return goal_response(load_goal_with_details(goal_id, db, user_id))


def load_goal_with_details(
    goal_id: uuid.UUID, db: Session, user_id: str, *, lock: bool = False
) -> Goal:
    statement = (
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
    goal = db.scalar(statement.with_for_update() if lock else statement)
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
            ProgressLogResponse.model_validate(progress_log) for progress_log in goal.progress_logs
        ],
        created_at=goal.created_at,
        updated_at=goal.updated_at,
    )
