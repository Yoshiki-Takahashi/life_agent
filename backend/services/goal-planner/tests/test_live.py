"""Explicit, opt-in real-provider regressions. Never run by normal CI."""

import os
from datetime import date, timedelta

import pytest

from evaluations.progress_cases import goal
from life_agent_goal_planner.config import Settings
from life_agent_goal_planner.planner import OpenAIGoalPlanner
from life_agent_goal_planner.progress import OpenAIAdvisor, OpenAIProgressParser
from life_agent_goal_planner.replan import OpenAIReplanner
from life_agent_goal_planner.schemas import GoalInput, GoalPlanRequest

pytestmark = [
    pytest.mark.live_ai,
    pytest.mark.skipif(os.getenv("RUN_LIVE_AI") != "1", reason="Explicit real AI opt-in required"),
]


@pytest.mark.parametrize(
    ("title", "name", "unit", "body"),
    [
        ("読書", "読了冊数", "冊", "1冊読み終えた"),
        ("アプリ開発", "成果物", "件", "成果物を1件完成させた"),
        ("筋トレ", "実施回数", "回", "1回トレーニングした"),
    ],
)
def test_three_domain_regression(title, name, unit, body):
    settings = Settings()
    planner = OpenAIGoalPlanner(settings)
    goal_input = GoalInput(title=title, target_date=date.today() + timedelta(days=90))
    questions = planner.questions(goal_input)
    assert 3 <= len(questions.questions) <= 8
    assert all("?" in q or "？" in q or "か" in q for q in questions.questions)
    plan = planner.generate(
        GoalPlanRequest(
            **goal_input.model_dump(),
            answers=[
                dict(question=q, answer=index % 2 == 0)
                for index, q in enumerate(questions.questions)
            ],
        )
    )
    assert plan.metrics and plan.milestones
    # Known incompatible observation/rate metrics cannot be recorded as positive increments.
    # This catches past failures, rather than replacing a review of generated content.
    for metric in plan.metrics:
        assert not any(
            token in metric.name + metric.unit
            for token in ("連続", "最大", "最高", "週あたり", "/週")
        )
    context = goal(title, "無理なく", [(name, unit, 0, 12)])
    assert OpenAIProgressParser(settings).parse(context, body).metric_updates[0].value == 1
    assert OpenAIAdvisor(settings).advise(context).next_actions
    assert OpenAIReplanner(settings).propose(context, "期限を延ばして負担を減らしたい").diff


@pytest.mark.parametrize(
    ("title", "name", "unit", "body"),
    [
        ("読書", "読了冊数", "冊", "13冊読み終えた"),
        ("アプリ開発", "成果物", "件", "成果物を13件完成させた"),
        ("筋トレ", "実施回数", "回", "13回トレーニングした"),
    ],
)
def test_zero_and_over_target_parser_regression(title, name, unit, body):
    parser = OpenAIProgressParser(Settings())
    context = goal(title, "", [(name, unit, 12, 12)])
    for report, value in [(body, 13), (f"今回の{name}は0{unit}です。0で記録してください。", 0)]:
        result = parser.parse(context, report)
        assert [(u.metric_id, u.value) for u in result.metric_updates] == [
            (context.metrics[0].id, value)
        ]
