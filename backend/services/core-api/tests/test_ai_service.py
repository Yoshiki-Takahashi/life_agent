from unittest.mock import Mock

import httpx
import pytest
from test_goals import preview

from life_agent_core.ai_service import HttpAdvisor, HttpProgressParser, HttpReplanner
from life_agent_core.config import Settings
from life_agent_core.progress_ai import AIUnavailableError
from life_agent_core.schemas import GoalResponse


def settings() -> Settings:
    return Settings(
        progress_parser_backend="http",
        advisor_backend="http",
        goal_planner_url="http://planner:8001/",
        goal_planner_timeout_seconds=2,
    )


def goal(client) -> GoalResponse:
    response = client.post("/api/v1/goals/confirm", json=preview(client))
    return GoalResponse.model_validate(response.json())


def test_http_progress_parser_returns_validated_contract(client, monkeypatch: pytest.MonkeyPatch):
    context = goal(client)
    payload = {
        "metric_updates": [{"metric_id": str(context.metrics[0].id), "value": 1}],
        "warnings": [],
    }
    response = httpx.Response(
        200,
        json=payload,
        request=httpx.Request("POST", "http://planner:8001/internal/v1/progress-preview"),
    )
    post = Mock(return_value=response)
    monkeypatch.setattr(httpx, "post", post)

    result = HttpProgressParser(settings()).parse(context, "1冊読んだ")

    assert result.metric_updates[0].metric_id == context.metrics[0].id
    post.assert_called_once()
    assert post.call_args.args == ("http://planner:8001/internal/v1/progress-preview",)
    assert post.call_args.kwargs["json"]["body"] == "1冊読んだ"
    assert post.call_args.kwargs["json"]["goal"]["id"] == str(context.id)
    assert post.call_args.kwargs["headers"] is None
    assert post.call_args.kwargs["timeout"] == 2.0


def test_http_advisor_returns_validated_contract(client, monkeypatch: pytest.MonkeyPatch):
    context = goal(client)
    response = httpx.Response(
        200,
        json={"summary": "保存済み進捗を確認しました。", "next_actions": ["5分だけ読む"]},
        request=httpx.Request("POST", "http://planner:8001/internal/v1/advice"),
    )
    post = Mock(return_value=response)
    monkeypatch.setattr(httpx, "post", post)

    result = HttpAdvisor(settings()).advise(context)

    assert result.next_actions == ["5分だけ読む"]
    assert post.call_args.args == ("http://planner:8001/internal/v1/advice",)
    assert post.call_args.kwargs["json"]["goal"]["id"] == str(context.id)


def test_http_replanner_returns_validated_contract(client, monkeypatch: pytest.MonkeyPatch):
    context = goal(client)
    response = httpx.Response(
        200,
        json={
            "proposed_plan": {
                "title": context.title,
                "description": context.description,
                "target_date": str(context.target_date),
                "metrics": [{"name": "読了冊数", "target_value": 8, "unit": "冊"}],
                "milestones": [
                    {"title": "4冊読む", "target_date": str(context.milestones[0].target_date)},
                    {"title": "6冊読む", "target_date": str(context.milestones[1].target_date)},
                    {"title": "8冊読む", "target_date": str(context.milestones[2].target_date)},
                ],
            },
            "diff": [
                {
                    "change_type": "update",
                    "target_type": "metric",
                    "target_label": "読了冊数",
                    "before": "12 冊",
                    "after": "8 冊",
                    "rationale": "進捗に合わせます。",
                }
            ],
        },
        request=httpx.Request("POST", "http://planner:8001/internal/v1/replan"),
    )
    post = Mock(return_value=response)
    monkeypatch.setattr(httpx, "post", post)

    result = HttpReplanner(settings()).propose(context, "今の計画が厳しい")

    assert result.diff[0].target_type == "metric"
    assert post.call_args.args == ("http://planner:8001/internal/v1/replan",)
    assert post.call_args.kwargs["json"]["reason"] == "今の計画が厳しい"
    assert post.call_args.kwargs["json"]["goal"]["id"] == str(context.id)


def test_internal_ai_adds_id_token_when_audience_is_configured(
    client, monkeypatch: pytest.MonkeyPatch
):
    context = goal(client)
    response = httpx.Response(
        200,
        json={"summary": "保存済み進捗を確認しました。", "next_actions": ["5分だけ読む"]},
        request=httpx.Request("POST", "https://planner.run.app/internal/v1/advice"),
    )
    post = Mock(return_value=response)
    monkeypatch.setattr(httpx, "post", post)
    monkeypatch.setattr("life_agent_core.ai_service.fetch_id_token", Mock(return_value="token"))

    HttpAdvisor(
        Settings(
            advisor_backend="http",
            goal_planner_url="https://planner.run.app",
            goal_planner_id_token_audience="https://planner.run.app",
        )
    ).advise(context)

    assert post.call_args.kwargs["headers"] == {"Authorization": "Bearer token"}


@pytest.mark.parametrize("status_code", [429, 500, 503])
def test_internal_ai_maps_service_errors_to_retryable_error(
    client, monkeypatch: pytest.MonkeyPatch, status_code: int
):
    context = goal(client)
    response = httpx.Response(
        status_code,
        request=httpx.Request("POST", "http://planner:8001/internal/v1/advice"),
    )
    monkeypatch.setattr(httpx, "post", Mock(return_value=response))

    with pytest.raises(AIUnavailableError):
        HttpAdvisor(settings()).advise(context)


def test_internal_ai_rejects_invalid_contract(client, monkeypatch: pytest.MonkeyPatch):
    context = goal(client)
    response = httpx.Response(
        200,
        json={"summary": "", "next_actions": []},
        request=httpx.Request("POST", "http://planner:8001/internal/v1/advice"),
    )
    monkeypatch.setattr(httpx, "post", Mock(return_value=response))

    with pytest.raises(AIUnavailableError):
        HttpAdvisor(settings()).advise(context)
