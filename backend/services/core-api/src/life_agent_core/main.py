import uuid
from typing import Annotated

from fastapi import Depends, FastAPI, HTTPException, status
from sqlalchemy.orm import Session

from life_agent_core.database import get_db
from life_agent_core.models import Goal
from life_agent_core.schemas import GoalCreate, GoalResponse

app = FastAPI(title="LifeAgent Core API", version="0.1.0")
DatabaseSession = Annotated[Session, Depends(get_db)]


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/api/v1/goals", response_model=GoalResponse, status_code=status.HTTP_201_CREATED)
def create_goal(payload: GoalCreate, db: DatabaseSession) -> Goal:
    goal = Goal(title=payload.title, description=payload.description)
    db.add(goal)
    db.commit()
    db.refresh(goal)
    return goal


@app.get("/api/v1/goals/{goal_id}", response_model=GoalResponse)
def get_goal(goal_id: uuid.UUID, db: DatabaseSession) -> Goal:
    goal = db.get(Goal, goal_id)
    if goal is None:
        raise HTTPException(status_code=404, detail="Goalが見つかりません")
    return goal
