import uuid
from functools import lru_cache
from typing import Annotated

from fastapi import Depends, FastAPI, HTTPException, status
from sqlalchemy import select
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session, selectinload

from life_agent_core.config import get_settings
from life_agent_core.database import get_db
from life_agent_core.models import Goal, Metric, Milestone
from life_agent_core.planner import (
    FakeGoalPlanner,
    GoalPlanner,
    HttpGoalPlanner,
    PlannerUnavailableError,
)
from life_agent_core.schemas import GoalConfirmRequest, GoalPlan, GoalPreviewRequest, GoalResponse

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
def preview_goal(payload: GoalPreviewRequest, planner: Planner) -> GoalPlan:
    try:
        return planner.generate(payload)
    except PlannerUnavailableError as error:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="計画を生成できませんでした。入力内容を保持して再試行してください",
        ) from error


@app.post("/api/v1/goals/confirm", response_model=GoalResponse, status_code=status.HTTP_201_CREATED)
def confirm_goal(payload: GoalConfirmRequest, db: DatabaseSession) -> Goal:
    goal = Goal(
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
    return goal


@app.get("/api/v1/goals/{goal_id}", response_model=GoalResponse)
def get_goal(goal_id: uuid.UUID, db: DatabaseSession) -> Goal:
    goal = db.scalar(
        select(Goal)
        .options(selectinload(Goal.metrics), selectinload(Goal.milestones))
        .where(Goal.id == goal_id)
    )
    if goal is None:
        raise HTTPException(status_code=404, detail="Goalが見つかりません")
    return goal
