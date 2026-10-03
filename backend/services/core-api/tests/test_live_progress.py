"""Six bounded provider calls, explicitly enabled; normal CI never calls OpenAI."""

import os

import pytest
from test_goals import preview

from life_agent_core.config import Settings
from life_agent_core.openai_progress import OpenAIAdvisor, OpenAIProgressParser
from life_agent_core.progress_ai import validate_candidates
from life_agent_core.schemas import GoalResponse

pytestmark = [
    pytest.mark.live_ai,
    pytest.mark.skipif(
        os.getenv("RUN_LIVE_AI") != "1",
        reason="Set RUN_LIVE_AI=1 to explicitly enable live evaluation",
    ),
]


@pytest.mark.parametrize(
    "title,body,value",
    [
        ("12冊の本を読む", "今日は2冊読み終えた", 2),
        ("アプリを開発する", "成果物を1件完成させた", 1),
        ("筋トレを続ける", "今日は2回トレーニングした", 2),
    ],
)
def test_live_parse_and_advice(client, title, body, value):
    settings = Settings()
    assert settings.openai_api_key, "Configure OPENAI_API_KEY for explicit live evaluation"
    goal = client.post("/api/v1/goals/confirm", json=preview(client, title)).json()
    context = GoalResponse.model_validate(goal)
    result = validate_candidates(OpenAIProgressParser(settings).parse(context, body), context)
    assert len(result.metric_updates) == 1
    assert result.metric_updates[0].metric_id == context.metrics[0].id
    assert result.metric_updates[0].value == value
    saved = client.post(
        f"/api/v1/goals/{goal['id']}/progress",
        json={
            "body": body,
            "client_request_id": "live-eval",
            "metric_updates": result.model_dump(mode="json")["metric_updates"],
        },
    )
    assert saved.status_code == 200
    advice = OpenAIAdvisor(settings).advise(GoalResponse.model_validate(saved.json()))
    assert advice.summary.strip()
    assert all(action.strip() for action in advice.next_actions)
