"""Bounded Re-planner provider calls, explicitly enabled."""

import os

import pytest

from evaluations.progress_cases import goal
from life_agent_goal_planner.config import Settings
from life_agent_goal_planner.replan import OpenAIReplanner

pytestmark = [
    pytest.mark.live_ai,
    pytest.mark.skipif(
        os.getenv("RUN_LIVE_AI") != "1",
        reason="Set RUN_LIVE_AI=1 to explicitly enable live evaluation",
    ),
]


@pytest.mark.parametrize(
    "title,metric,reason",
    [
        ("12冊の本を読む", ("読了冊数", "冊", 2, 12), "予定より読書時間が少なく、冊数を見直したい"),
        (
            "アプリを開発する",
            ("完成した成果物", "件", 0, 2),
            "認証まわりで詰まり、期限とMilestoneを現実的にしたい",
        ),
        ("筋トレを続ける", ("トレーニング回数", "回", 3, 12), "膝に痛みがあり回数目標を調整したい"),
    ],
)
def test_live_replan_categories_produce_reviewable_candidates(title, metric, reason):
    settings = Settings()
    assert settings.openai_api_key, "Configure OPENAI_API_KEY for explicit live evaluation"
    context = goal(title, "90日で達成したい", [metric], deadline_days=45)

    result = OpenAIReplanner(settings).propose(context, reason)

    assert result.diff
    assert 1 <= len(result.proposed_plan.metrics) <= 3
    assert 3 <= len(result.proposed_plan.milestones) <= 5
    assert result.proposed_plan.target_date >= result.proposed_plan.milestones[-1].target_date
    assert result.proposed_plan.metrics[0].target_value >= context.metrics[0].current_value
