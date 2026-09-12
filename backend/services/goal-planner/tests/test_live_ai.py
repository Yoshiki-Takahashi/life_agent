import os
from datetime import date, timedelta

import pytest

from life_agent_goal_planner.config import Settings
from life_agent_goal_planner.planner import OpenAIGoalPlanner
from life_agent_goal_planner.schemas import GoalPlanRequest


@pytest.mark.live_ai
@pytest.mark.skipif(not os.getenv("OPENAI_API_KEY"), reason="OPENAI_API_KEY is not configured")
@pytest.mark.parametrize("title", ["12冊の本を読む", "小さなアプリを開発する", "週3回筋トレする"])
def test_live_goal_categories_produce_relevant_valid_plans(title: str) -> None:
    planner = OpenAIGoalPlanner(Settings())
    plan = planner.generate(
        GoalPlanRequest(
            title=title,
            description="90日で達成したい",
            target_date=date.today() + timedelta(days=90),
        )
    )

    assert 1 <= len(plan.metrics) <= 3
    assert 3 <= len(plan.milestones) <= 5
    assert all(metric.target_value > 0 for metric in plan.metrics)
    assert all(
        date.today() <= milestone.target_date <= plan.target_date for milestone in plan.milestones
    )
