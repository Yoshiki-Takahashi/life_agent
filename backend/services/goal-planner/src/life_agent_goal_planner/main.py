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
from life_agent_goal_planner.progress import (
    Advisor,
    AIUnavailableError,
    FakeAdvisor,
    FakeProgressParser,
    OpenAIAdvisor,
    OpenAIProgressParser,
    ProgressParser,
)
from life_agent_goal_planner.replan import FakeReplanner, OpenAIReplanner, Replanner
from life_agent_goal_planner.schemas import (
    Advice,
    AdviceAIRequest,
    GeneratedReplan,
    GoalPlan,
    GoalPlanRequest,
    ProgressPreview,
    ProgressPreviewAIRequest,
    ReplanAIRequest,
)

app = FastAPI(title="LifeAgent Goal Planner", version="0.1.0")


@lru_cache
def get_planner() -> GoalPlanner:
    settings = get_settings()
    if settings.goal_planner_provider == "fake":
        return FakeGoalPlanner()
    return OpenAIGoalPlanner(settings)


Planner = Annotated[GoalPlanner, Depends(get_planner)]


@lru_cache
def get_progress_parser() -> ProgressParser:
    settings = get_settings()
    if settings.progress_parser_provider == "fake":
        return FakeProgressParser()
    return OpenAIProgressParser(settings)


@lru_cache
def get_advisor() -> Advisor:
    settings = get_settings()
    if settings.advisor_provider == "fake":
        return FakeAdvisor()
    return OpenAIAdvisor(settings)


@lru_cache
def get_replanner() -> Replanner:
    settings = get_settings()
    if settings.replanner_provider == "fake":
        return FakeReplanner()
    return OpenAIReplanner(settings)


ProgressParserDependency = Annotated[ProgressParser, Depends(get_progress_parser)]
AdvisorDependency = Annotated[Advisor, Depends(get_advisor)]
ReplannerDependency = Annotated[Replanner, Depends(get_replanner)]


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


@app.post("/internal/v1/progress-preview", response_model=ProgressPreview)
def create_progress_preview(
    payload: ProgressPreviewAIRequest, parser: ProgressParserDependency
) -> ProgressPreview:
    try:
        return parser.parse(payload.goal, payload.body)
    except AIUnavailableError as error:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="進捗候補を生成できませんでした。再試行してください",
        ) from error


@app.post("/internal/v1/advice", response_model=Advice)
def create_advice(payload: AdviceAIRequest, advisor: AdvisorDependency) -> Advice:
    try:
        return advisor.advise(payload.goal)
    except AIUnavailableError as error:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="助言を生成できませんでした。再試行してください",
        ) from error


@app.post("/internal/v1/replan", response_model=GeneratedReplan)
def create_replan(payload: ReplanAIRequest, replanner: ReplannerDependency) -> GeneratedReplan:
    try:
        return replanner.propose(payload.goal, payload.reason)
    except AIUnavailableError as error:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="再計画候補を生成できませんでした。再試行してください",
        ) from error
