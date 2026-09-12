from datetime import date, timedelta
from unittest.mock import Mock

import httpx
import pytest

from life_agent_core.config import Settings
from life_agent_core.planner import HttpGoalPlanner, PlannerUnavailableError
from life_agent_core.schemas import GoalPreviewRequest


def request() -> GoalPreviewRequest:
    return GoalPreviewRequest(
        title="筋トレを続ける",
        description="週3回",
        target_date=date.today() + timedelta(days=90),
    )


def response_payload() -> dict[str, object]:
    return {
        **request().model_dump(mode="json"),
        "metrics": [{"name": "実施回数", "target_value": 36, "unit": "回"}],
        "milestones": [
            {
                "title": "メニューを決める",
                "target_date": (date.today() + timedelta(days=30)).isoformat(),
            },
            {
                "title": "18回実施する",
                "target_date": (date.today() + timedelta(days=60)).isoformat(),
            },
            {
                "title": "36回実施する",
                "target_date": (date.today() + timedelta(days=90)).isoformat(),
            },
        ],
    }


def planner() -> HttpGoalPlanner:
    return HttpGoalPlanner(
        Settings(
            goal_planner_backend="http",
            goal_planner_url="http://planner:8001/",
            goal_planner_timeout_seconds=2,
        )
    )


def test_http_planner_returns_validated_contract(monkeypatch: pytest.MonkeyPatch) -> None:
    response = httpx.Response(
        200,
        json=response_payload(),
        request=httpx.Request("POST", "http://planner:8001/internal/v1/goal-plans"),
    )
    post = Mock(return_value=response)
    monkeypatch.setattr(httpx, "post", post)

    result = planner().generate(request())

    assert result.metrics[0].name == "実施回数"
    post.assert_called_once_with(
        "http://planner:8001/internal/v1/goal-plans",
        json=request().model_dump(mode="json"),
        timeout=2.0,
    )


@pytest.mark.parametrize("status_code", [429, 500, 503])
def test_http_planner_maps_service_errors_to_retryable_error(
    monkeypatch: pytest.MonkeyPatch, status_code: int
) -> None:
    response = httpx.Response(
        status_code,
        request=httpx.Request("POST", "http://planner:8001/internal/v1/goal-plans"),
    )
    monkeypatch.setattr(httpx, "post", Mock(return_value=response))

    with pytest.raises(PlannerUnavailableError):
        planner().generate(request())


def test_http_planner_rejects_invalid_contract(monkeypatch: pytest.MonkeyPatch) -> None:
    invalid = response_payload()
    invalid["metrics"] = []
    response = httpx.Response(
        200,
        json=invalid,
        request=httpx.Request("POST", "http://planner:8001/internal/v1/goal-plans"),
    )
    monkeypatch.setattr(httpx, "post", Mock(return_value=response))

    with pytest.raises(PlannerUnavailableError):
        planner().generate(request())


def test_http_planner_maps_timeout_to_retryable_error(monkeypatch: pytest.MonkeyPatch) -> None:
    def timeout(*args: object, **kwargs: object) -> None:
        raise httpx.ReadTimeout("timed out")

    monkeypatch.setattr(httpx, "post", timeout)

    with pytest.raises(PlannerUnavailableError):
        planner().generate(request())
