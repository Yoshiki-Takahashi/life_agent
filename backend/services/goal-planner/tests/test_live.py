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
    context = goal(title, "無理なく", [(name, unit, 0, 12)])
    assert OpenAIProgressParser(settings).parse(context, body).metric_updates[0].value == 1
    assert OpenAIAdvisor(settings).advise(context).next_actions
    assert OpenAIReplanner(settings).propose(context, "期限を延ばして負担を減らしたい").diff
