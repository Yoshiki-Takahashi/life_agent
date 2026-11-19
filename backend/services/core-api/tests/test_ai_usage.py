from concurrent.futures import ThreadPoolExecutor
from datetime import UTC, date, datetime, timedelta
from unittest.mock import Mock

import httpx
from google.api_core.exceptions import ServiceUnavailable
from google.cloud.firestore_v1.transaction import Transaction

from life_agent_core.ai_service import AIUnavailableError
from life_agent_core.ai_usage import AI_USAGE_COLLECTION
from life_agent_core.database import request_key
from life_agent_core.goals import HttpGoalPlanner, get_goal_planner
from life_agent_core.main import app
from life_agent_core.progress import get_advisor

QUESTIONS = "/api/v1/goals/questions"
COACH = "/api/v1/coach/messages"


def test_ai_quota_is_shared_atomic_and_resets_at_japan_midnight(
    client, create_goal, db_client, monkeypatch
):
    before_midnight = datetime(2026, 11, 19, 14, 59, 59, tzinfo=UTC)
    monkeypatch.setattr("life_agent_core.ai_usage.now", lambda: before_midnight)
    goal = create_goal()
    path = f"/api/v1/goals/{goal['id']}"
    goal_input = {"title": "本を読む", "target_date": str(date.today() + timedelta(days=30))}
    requests = [
        (QUESTIONS, goal_input, 200),
        ("/api/v1/goals/preview", goal_input, 200),
        (path + "/progress/preview", {"body": "1冊読んだ"}, 200),
        (path + "/advice", None, 503),
        (path + "/replan/questions", {"reason": "計画を調整したい"}, 200),
        (path + "/replan/preview", {"reason": "計画を調整したい"}, 200),
        (COACH, {"body": "進め方を相談したい"}, 200),
    ]
    advisor = Mock(advise=Mock(side_effect=AIUnavailableError))
    app.dependency_overrides[get_advisor] = lambda: advisor
    for url, payload, status in requests:
        assert client.post(url, json=payload).status_code == status
    for _ in range(11):
        assert client.post(QUESTIONS, json=goal_input).status_code == 200

    with ThreadPoolExecutor(max_workers=2) as pool:
        questions = pool.submit(client.post, QUESTIONS, json=goal_input)
        coach = pool.submit(client.post, COACH, json={"body": "相談"})
        assert sorted([questions.result().status_code, coach.result().status_code]) == [200, 429]
    for url, payload, _ in requests:
        response = client.post(url, json=payload)
        assert response.status_code == 429
        assert response.headers["Retry-After"] == "Thu, 19 Nov 2026 15:00:00 GMT"
    advisor.advise.assert_called_once()

    saved = client.post(
        path + "/progress",
        json={
            "body": "手入力の実績",
            "client_request_id": "manual",
            "metric_updates": [{"metric_id": goal["metrics"][0]["id"], "value": 1}],
        },
    )
    assert saved.status_code == 200 and len(saved.json()["progress_logs"]) == 1
    other_user = client.post(QUESTIONS, json=goal_input, headers={"Authorization": "Bearer user/b"})
    assert other_user.status_code == 200

    monkeypatch.setattr(
        "life_agent_core.ai_usage.now", lambda: before_midnight + timedelta(seconds=1)
    )
    assert client.post(QUESTIONS, json=goal_input).status_code == 200
    counts = {
        (doc["owner_key"], doc["local_date"]): doc["count"]
        for snapshot in db_client.collection(AI_USAGE_COLLECTION).stream()
        if (doc := snapshot.to_dict())
    }
    assert counts == {
        (request_key("user-a"), "2026-11-19"): 20,
        (request_key("user/b"), "2026-11-19"): 1,
        (request_key("user-a"), "2026-11-20"): 1,
    }


def test_failed_quota_commit_never_calls_ai(client, db_client, monkeypatch):
    planner = Mock()
    app.dependency_overrides[get_goal_planner] = lambda: planner
    monkeypatch.setattr(Transaction, "_commit", Mock(side_effect=ServiceUnavailable("private")))
    response = client.post(
        QUESTIONS, json={"title": "読書", "target_date": str(date.today() + timedelta(days=30))}
    )
    assert response.status_code == 503
    assert "private" not in response.text
    planner.questions.assert_not_called()
    assert list(db_client.collection(AI_USAGE_COLLECTION).stream()) == []


def test_oversized_internal_input_returns_public_413(client, monkeypatch):
    from life_agent_core.config import Settings

    app.dependency_overrides[get_goal_planner] = lambda: HttpGoalPlanner(
        Settings(goal_planner_url="http://ai")
    )
    monkeypatch.setattr(httpx, "post", Mock(return_value=httpx.Response(413)))
    response = client.post(
        QUESTIONS, json={"title": "読書", "target_date": str(date.today() + timedelta(days=30))}
    )
    assert response.status_code == 413
