from datetime import UTC, date, datetime, timedelta
from types import SimpleNamespace
from unittest.mock import MagicMock, Mock
from uuid import uuid4

import pytest
from openai import APITimeoutError

from evaluations.progress_cases import goal
from life_agent_goal_planner.coach import FakeCoach, OpenAICoach
from life_agent_goal_planner.config import Settings
from life_agent_goal_planner.main import (
    app,
    get_advisor,
    get_coach,
    get_planner,
    get_progress_parser,
    get_replanner,
)
from life_agent_goal_planner.planner import FakeGoalPlanner
from life_agent_goal_planner.progress import FakeAdvisor, FakeProgressParser, advice_context
from life_agent_goal_planner.provider import AIUnavailableError, generate
from life_agent_goal_planner.replan import FakeReplanner
from life_agent_goal_planner.schemas import Advice, CoachAIRequest, CoachReply


@pytest.mark.parametrize(
    ("title", "name", "unit", "body"),
    [
        ("読書", "読了冊数", "冊", "1冊読み終えた"),
        ("アプリ開発", "成果物", "件", "成果物を1件完成させた"),
        ("筋トレ", "回数", "回", "1回トレーニングした"),
    ],
)
def test_fixed_contracts_through_internal_endpoints(client, title, name, unit, body):
    app.dependency_overrides[get_planner] = FakeGoalPlanner
    context = goal(title, "無理なく続ける", [(name, unit, 0, 12)])
    plan = client.post(
        "/internal/v1/goal-plans",
        json={"title": title, "target_date": str(date.today() + timedelta(days=90))},
    )
    assert plan.status_code == 200 and 1 <= len(plan.json()["metrics"]) <= 3
    for dependency, provider in [
        (get_progress_parser, FakeProgressParser),
        (get_advisor, FakeAdvisor),
        (get_replanner, FakeReplanner),
        (get_coach, FakeCoach),
    ]:
        app.dependency_overrides[dependency] = provider
    payload = {"goal": context.model_dump(mode="json")}
    preview = client.post("/internal/v1/progress-preview", json={**payload, "body": body})
    assert preview.status_code == 200 and preview.json()["metric_updates"][0]["value"] == 1
    assert client.post("/internal/v1/advice", json=payload).json()["next_actions"]
    replan = client.post("/internal/v1/replan", json={**payload, "reason": "負担を調整したい"})
    assert replan.status_code == 200 and replan.json()["diff"]
    coach = client.post(
        "/internal/v1/coach",
        json=dict(
            body="相談",
            history=[],
            as_of_date=str(date.today()),
            goals=[
                dict(
                    id=str(context.id),
                    title=title,
                    target_date=str(context.target_date),
                    metrics=[dict(name=name, unit=unit, current_value=0, target_value=12)],
                )
            ],
        ),
    )
    assert coach.status_code == 200 and title in coach.json()["reply"]


def test_shared_provider_uses_structured_private_bounded_calls():
    factory = MagicMock()
    settings = Settings(openai_api_key="unit-test-secret")
    assert "unit-test-secret" not in repr(settings)
    expected = Advice(summary="次の一歩", next_actions=["5分読む"])
    factory.return_value.responses.parse.return_value = SimpleNamespace(output_parsed=expected)
    assert generate(settings, factory, Advice, "prompt", {"owned": "context"}) == expected
    call = factory.return_value.responses.parse.call_args.kwargs
    assert call["text_format"] is Advice and call["store"] is False
    assert factory.call_args.kwargs["max_retries"] == 0
    assert factory.call_args.kwargs["timeout"] == 20


@pytest.mark.parametrize("value", [0, 13])
def test_progress_candidates_can_be_zero_or_exceed_an_already_reached_target(client, value):
    context = goal("読書", "", [("読了冊数", "冊", 12, 12)])
    app.dependency_overrides[get_progress_parser] = FakeProgressParser
    result = client.post(
        "/internal/v1/progress-preview",
        json={"goal": context.model_dump(mode="json"), "body": f"{value}冊読み終えた"},
    )
    assert result.status_code == 200
    assert result.json()["metric_updates"] == [
        dict(metric_id=str(context.metrics[0].id), value=value)
    ]


@pytest.mark.parametrize("failure", ["key", "empty", "invalid", "timeout"])
def test_unusable_provider_results_are_retryable(failure):
    factory = MagicMock()
    settings = Settings(openai_api_key=None if failure == "key" else "test")
    factory.return_value.responses.parse.return_value = SimpleNamespace(
        output_parsed={} if failure == "invalid" else None
    )
    if failure == "timeout":
        factory.return_value.responses.parse.side_effect = APITimeoutError(request=Mock())
    with pytest.raises(AIUnavailableError):
        generate(settings, factory, Advice, "prompt", {})
    if failure == "key":
        factory.assert_not_called()


def test_advice_uses_saved_values_dates_and_bounded_history():
    context = goal(
        "本を読む",
        "無理なく",
        [("読了冊数", "冊", 1, 12)],
        [(f"{i}日前。本文には2冊だが確認後の保存値は1冊", i, 0, 1) for i in range(12)],
    )
    today = datetime.now(UTC).date()
    payload = advice_context(context, today)
    assert payload["metrics"][0]["current_value"] == 1
    assert payload["metrics"][0]["remaining"] == 11
    assert payload["days_since_latest_log"] == 0 and len(payload["progress_logs"]) == 10
    assert "owner_id" not in str(payload)


def test_global_coach_sends_all_goals_and_preserves_user_history():
    request = CoachAIRequest(
        body="全部を相談",
        history=[dict(role="user", content="時間が少ない")],
        as_of_date=date.today(),
        goals=[
            dict(id=uuid4(), title=title, target_date=date.today(), metrics=[])
            for title in ("読書", "開発", "筋トレ")
        ],
    )
    factory = MagicMock()
    factory.return_value.responses.parse.return_value = SimpleNamespace(
        output_parsed=CoachReply(
            reply="次の一歩を選びましょう", intent="discuss", goal_id=None, record_body=None
        )
    )
    OpenAICoach(Settings(openai_api_key="test"), factory).respond(request)
    import json

    payload = json.loads(
        factory.return_value.responses.parse.call_args.kwargs["input"][1]["content"]
    )
    assert len(payload["goals"]) == 3 and payload["history"][0]["content"] == "時間が少ない"


@pytest.mark.parametrize("title", ["読書", "アプリ開発", "筋トレ"])
def test_questions_and_explicit_answers_cross_the_provider_boundary(client, title):
    import json

    from life_agent_goal_planner.planner import OpenAIGoalPlanner
    from life_agent_goal_planner.schemas import GeneratedPlan, GoalQuestions

    questions = GoalQuestions(
        questions=[
            f"{title}の時間も記録したいですか？",
            "成果を段階的に確認したいですか？",
            "経験がありますか？",
        ]
    )
    generated = GeneratedPlan(
        metrics=[dict(name="取り組んだ時間", target_value=60, unit="分")],
        milestones=[dict(title=f"段階{i}", target_date=date.today()) for i in range(3)],
    )
    factory = MagicMock()
    factory.return_value.responses.parse.side_effect = [
        SimpleNamespace(output_parsed=questions),
        SimpleNamespace(output_parsed=generated),
    ]
    provider = OpenAIGoalPlanner(Settings(openai_api_key="test"), factory)
    app.dependency_overrides[get_planner] = lambda: provider
    payload = dict(title=title, target_date=str(date.today()))
    response = client.post("/internal/v1/goal-questions", json=payload)
    assert response.status_code == 200
    answers = [dict(question=q, answer=i == 0) for i, q in enumerate(response.json()["questions"])]
    plan = client.post("/internal/v1/goal-plans", json={**payload, "answers": answers})
    assert plan.status_code == 200 and plan.json()["metrics"][0]["unit"] == "分"
    assert "answers" not in plan.json()  # Answers are generation context, not stored plan fields.
    calls = factory.return_value.responses.parse.call_args_list
    assert calls[0].kwargs["text_format"] is GoalQuestions
    actual = json.loads(calls[1].kwargs["input"][1]["content"])
    assert actual["answers"] == answers and actual["title"] == title


@pytest.mark.parametrize("questions", [[], ["duplicate"] * 3, ["x" * 201, "q2", "q3"]])
def test_bad_generated_questions_fail_closed(client, questions):
    from life_agent_goal_planner.planner import OpenAIGoalPlanner

    factory = MagicMock()
    factory.return_value.responses.parse.return_value = SimpleNamespace(
        output_parsed=dict(questions=questions)
    )
    app.dependency_overrides[get_planner] = lambda: OpenAIGoalPlanner(
        Settings(openai_api_key="test"), factory
    )
    response = client.post(
        "/internal/v1/goal-questions", json=dict(title="読書", target_date=str(date.today()))
    )
    assert response.status_code == 503
