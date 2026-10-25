from datetime import date, timedelta
from typing import Any

from fastapi.testclient import TestClient

from life_agent_goal_planner.planner import PlannerUnavailableError
from life_agent_goal_planner.progress import AIUnavailableError
from life_agent_goal_planner.schemas import GeneratedReplan, GoalPlan, GoalPlanRequest


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


def goal_context() -> dict[str, Any]:
    return {
        "id": "11111111-1111-1111-1111-111111111111",
        "title": "読書習慣を作る",
        "description": "毎週読む",
        "target_date": (date.today() + timedelta(days=90)).isoformat(),
        "plan_revision": 1,
        "status": "active",
        "metrics": [
            {
                "id": "22222222-2222-2222-2222-222222222222",
                "name": "読了冊数",
                "unit": "冊",
                "current_value": 2,
                "target_value": 12,
                "position": 0,
                "created_at": "2026-10-01T00:00:00Z",
            }
        ],
        "milestones": [
            {
                "id": "33333333-3333-3333-3333-333333333333",
                "title": "本を決める",
                "target_date": (date.today() + timedelta(days=30)).isoformat(),
                "position": 0,
                "created_at": "2026-10-01T00:00:00Z",
            },
            {
                "id": "33333333-3333-3333-3333-333333333334",
                "title": "6冊読む",
                "target_date": (date.today() + timedelta(days=60)).isoformat(),
                "position": 1,
                "created_at": "2026-10-01T00:00:00Z",
            },
            {
                "id": "33333333-3333-3333-3333-333333333335",
                "title": "12冊読む",
                "target_date": (date.today() + timedelta(days=90)).isoformat(),
                "position": 2,
                "created_at": "2026-10-01T00:00:00Z",
            },
        ],
        "progress_logs": [],
        "created_at": "2026-10-01T00:00:00Z",
        "updated_at": "2026-10-01T00:00:00Z",
    }


def test_create_replan_returns_contract(client: TestClient, override_replanner) -> None:
    class Replanner:
        def propose(self, context, reason: str) -> GeneratedReplan:
            return GeneratedReplan(
                proposed_plan={
                    "title": context.title,
                    "description": context.description,
                    "target_date": context.target_date,
                    "metrics": [{"name": "読了冊数", "target_value": 8, "unit": "冊"}],
                    "milestones": [
                        {"title": "4冊読む", "target_date": date.today() + timedelta(days=30)},
                        {"title": "6冊読む", "target_date": date.today() + timedelta(days=60)},
                        {"title": "8冊読む", "target_date": context.target_date},
                    ],
                },
                diff=[
                    {
                        "change_type": "update",
                        "target_type": "metric",
                        "target_label": "読了冊数",
                        "before": "12 冊",
                        "after": "8 冊",
                        "rationale": "進捗に合わせます。",
                    }
                ],
            )

    override_replanner(Replanner())
    response = client.post(
        "/internal/v1/replan",
        json={"goal": goal_context(), "reason": "今の計画が厳しい"},
    )

    assert response.status_code == 200
    assert response.json()["proposed_plan"]["metrics"][0]["target_value"] == 8
    assert response.json()["diff"][0]["target_type"] == "metric"


def test_unavailable_replanner_returns_retryable_error(
    client: TestClient, override_replanner
) -> None:
    class Replanner:
        def propose(self, context, reason: str) -> GeneratedReplan:
            raise AIUnavailableError

    override_replanner(Replanner())
    response = client.post(
        "/internal/v1/replan",
        json={"goal": goal_context(), "reason": "今の計画が厳しい"},
    )

    assert response.status_code == 503
    assert "再試行" in response.json()["detail"]
