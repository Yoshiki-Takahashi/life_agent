from functools import lru_cache
from typing import Annotated

from fastapi import Depends, FastAPI, HTTPException, status

from life_agent_goal_planner.config import get_settings
from life_agent_goal_planner.planner import (
    FakeGoalPlanner,
    GoalPlanner,
    OpenAIGoalPlanner,
    PlannerUnavailableError,
)
from life_agent_goal_planner.schemas import GoalPlan, GoalPlanRequest

app = FastAPI(title="LifeAgent Goal Planner", version="0.1.0")


@lru_cache
def get_planner() -> GoalPlanner:
    settings = get_settings()
    if settings.goal_planner_provider == "fake":
        return FakeGoalPlanner()
    return OpenAIGoalPlanner(settings)


Planner = Annotated[GoalPlanner, Depends(get_planner)]


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/internal/v1/goal-plans", response_model=GoalPlan)
def create_goal_plan(payload: GoalPlanRequest, planner: Planner) -> GoalPlan:
    try:
        return planner.generate(payload)
    except PlannerUnavailableError as error:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="計画を生成できませんでした。再試行してください",
        ) from error
