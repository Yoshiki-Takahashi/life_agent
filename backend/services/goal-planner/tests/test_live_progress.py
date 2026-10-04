"""Bounded Progress Parser / Advisor provider calls, explicitly enabled."""

import os

import pytest

from evaluations.progress_cases import goal
from life_agent_goal_planner.config import Settings
from life_agent_goal_planner.progress import OpenAIAdvisor, OpenAIProgressParser

pytestmark = [
    pytest.mark.live_ai,
    pytest.mark.skipif(
        os.getenv("RUN_LIVE_AI") != "1",
        reason="Set RUN_LIVE_AI=1 to explicitly enable live evaluation",
    ),
]


@pytest.mark.parametrize(
    "title,body,value,metric",
    [
        ("12冊の本を読む", "今日は2冊読み終えた", 2, ("読了冊数", "冊", 0, 12)),
        ("アプリを開発する", "成果物を1件完成させた", 1, ("完成した成果物", "件", 0, 1)),
        ("筋トレを続ける", "今日は2回トレーニングした", 2, ("トレーニング回数", "回", 0, 12)),
    ],
)
def test_live_parse_and_advice(title, body, value, metric):
    settings = Settings()
    assert settings.openai_api_key, "Configure OPENAI_API_KEY for explicit live evaluation"
    context = goal(title, "90日で達成したい", [metric])

    result = OpenAIProgressParser(settings).parse(context, body)

    assert len(result.metric_updates) == 1
    assert result.metric_updates[0].metric_id == context.metrics[0].id
    assert result.metric_updates[0].value == value
    advice = OpenAIAdvisor(settings).advise(context)
    assert advice.summary.strip()
    assert all(action.strip() for action in advice.next_actions)
