from datetime import date, timedelta
from typing import Any

from fastapi.testclient import TestClient

from life_agent_goal_planner.planner import PlannerUnavailableError
from life_agent_goal_planner.schemas import GoalPlan, GoalPlanRequest


def payload() -> dict[str, Any]:
    return {
        "title": "読書習慣を作る",
        "description": "毎週読む",
        "target_date": (date.today() + timedelta(days=90)).isoformat(),
    }


def valid_plan(request: GoalPlanRequest) -> GoalPlan:
    return GoalPlan(
        **request.model_dump(),
        metrics=[{"name": "読了冊数", "target_value": 12, "unit": "冊"}],
        milestones=[
            {"title": "本を決める", "target_date": date.today() + timedelta(days=30)},
            {"title": "6冊読む", "target_date": date.today() + timedelta(days=60)},
            {"title": "12冊読む", "target_date": request.target_date},
        ],
    )


def test_health_does_not_require_api_key(client: TestClient) -> None:
    assert client.get("/health").json() == {"status": "ok"}


def test_create_goal_plan_returns_contract(client: TestClient, override_planner) -> None:
    class Planner:
        def generate(self, request: GoalPlanRequest) -> GoalPlan:
            return valid_plan(request)

    override_planner(Planner())
    response = client.post("/internal/v1/goal-plans", json=payload())

    assert response.status_code == 200
    assert response.json()["metrics"][0]["name"] == "読了冊数"
    assert len(response.json()["milestones"]) == 3


def test_unavailable_planner_returns_retryable_error(client: TestClient, override_planner) -> None:
    class Planner:
        def generate(self, request: GoalPlanRequest) -> GoalPlan:
            raise PlannerUnavailableError

    override_planner(Planner())
    response = client.post("/internal/v1/goal-plans", json=payload())

    assert response.status_code == 503
    assert "再試行" in response.json()["detail"]


def test_invalid_request_is_rejected_before_planner(client: TestClient) -> None:
    request = payload()
    request["target_date"] = (date.today() - timedelta(days=1)).isoformat()

    assert client.post("/internal/v1/goal-plans", json=request).status_code == 422
