from datetime import date, timedelta
from types import SimpleNamespace
from unittest.mock import Mock

import pytest
from openai import APIConnectionError

from life_agent_goal_planner.config import Settings
from life_agent_goal_planner.planner import (
    FakeGoalPlanner,
    OpenAIGoalPlanner,
    PlannerUnavailableError,
)
from life_agent_goal_planner.schemas import GeneratedPlan, GoalPlanRequest


def request() -> GoalPlanRequest:
    return GoalPlanRequest(
        title="アプリを開発する",
        description="小さく完成させる",
        target_date=date.today() + timedelta(days=90),
    )


def generated_plan() -> GeneratedPlan:
    return GeneratedPlan(
        metrics=[{"name": "完成機能数", "target_value": 3, "unit": "件"}],
        milestones=[
            {"title": "要件を決める", "target_date": date.today() + timedelta(days=30)},
            {"title": "実装する", "target_date": date.today() + timedelta(days=60)},
            {"title": "完成させる", "target_date": date.today() + timedelta(days=90)},
        ],
    )


def test_fake_planner_is_deterministic_and_identifiable() -> None:
    planner = FakeGoalPlanner()

    first = planner.generate(request())
    second = planner.generate(request())

    assert first == second
    assert first.metrics[0].name == "独立Planner達成率"
    assert len(first.milestones) == 3


def test_openai_structured_output_is_combined_with_goal_input() -> None:
    responses = Mock()
    responses.parse.return_value = SimpleNamespace(output_parsed=generated_plan())
    client_factory = Mock(return_value=SimpleNamespace(responses=responses))
    planner = OpenAIGoalPlanner(Settings(openai_api_key="test-key"), client_factory)

    result = planner.generate(request())

    assert result.title == "アプリを開発する"
    assert result.metrics[0].name == "完成機能数"
    assert responses.parse.call_args.kwargs["text_format"] is GeneratedPlan
    assert responses.parse.call_args.kwargs["model"] == "gpt-5.6-luna"
    client_factory.assert_called_once_with(api_key="test-key", timeout=20.0)


def test_missing_key_is_retryable_and_does_not_create_client() -> None:
    client_factory = Mock()
    planner = OpenAIGoalPlanner(Settings(openai_api_key=None), client_factory)

    with pytest.raises(PlannerUnavailableError):
        planner.generate(request())

    client_factory.assert_not_called()


def test_missing_parsed_output_is_retryable() -> None:
    responses = Mock()
    responses.parse.return_value = SimpleNamespace(output_parsed=None)
    planner = OpenAIGoalPlanner(
        Settings(openai_api_key="test-key"),
        Mock(return_value=SimpleNamespace(responses=responses)),
    )

    with pytest.raises(PlannerUnavailableError):
        planner.generate(request())


def test_openai_connection_error_is_retryable() -> None:
    responses = Mock()
    responses.parse.side_effect = APIConnectionError(request=Mock())
    planner = OpenAIGoalPlanner(
        Settings(openai_api_key="test-key"),
        Mock(return_value=SimpleNamespace(responses=responses)),
    )

    with pytest.raises(PlannerUnavailableError):
        planner.generate(request())


def test_domain_invalid_dates_are_retryable() -> None:
    invalid = generated_plan()
    invalid.milestones[-1].target_date = date.today() + timedelta(days=91)
    responses = Mock()
    responses.parse.return_value = SimpleNamespace(output_parsed=invalid)
    planner = OpenAIGoalPlanner(
        Settings(openai_api_key="test-key"),
        Mock(return_value=SimpleNamespace(responses=responses)),
    )

    with pytest.raises(PlannerUnavailableError):
        planner.generate(request())
