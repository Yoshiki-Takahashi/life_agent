import uuid
from typing import Annotated

from fastapi import Depends, FastAPI, HTTPException, status
from sqlalchemy import select
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session, selectinload

from life_agent_core.database import get_db
from life_agent_core.models import Goal, Metric, Milestone
from life_agent_core.planner import create_fake_plan
from life_agent_core.schemas import GoalConfirmRequest, GoalPlan, GoalPreviewRequest, GoalResponse

app = FastAPI(title="LifeAgent Core API", version="0.1.0")
DatabaseSession = Annotated[Session, Depends(get_db)]


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/api/v1/goals/preview", response_model=GoalPlan)
def preview_goal(payload: GoalPreviewRequest) -> GoalPlan:
    return create_fake_plan(payload)


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
