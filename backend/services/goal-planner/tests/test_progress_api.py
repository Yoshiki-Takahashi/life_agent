from datetime import date, timedelta

from fastapi.testclient import TestClient

from life_agent_goal_planner.main import app


def goal_payload():
    today = date.today()
    return {
        "id": "00000000-0000-0000-0000-000000000999",
        "title": "12冊の本を読む",
        "description": "寝る前に読む",
        "target_date": (today + timedelta(days=30)).isoformat(),
        "status": "active",
        "metrics": [
            {
                "id": "00000000-0000-0000-0000-000000000001",
                "name": "読了冊数",
                "unit": "冊",
                "current_value": 1,
                "target_value": 12,
                "position": 0,
                "created_at": "2026-10-01T00:00:00Z",
            }
        ],
        "milestones": [],
        "progress_logs": [],
        "created_at": "2026-10-01T00:00:00Z",
        "updated_at": "2026-10-01T00:00:00Z",
    }


def test_fake_progress_preview_endpoint_returns_candidates():
    with TestClient(app) as client:
        response = client.post(
            "/internal/v1/progress-preview",
            json={"goal": goal_payload(), "body": "今日は2冊読み終えた"},
        )

    assert response.status_code == 200
    assert response.json()["metric_updates"] == [
        {"metric_id": "00000000-0000-0000-0000-000000000001", "value": 2.0}
    ]


def test_fake_advice_endpoint_returns_advice():
    with TestClient(app) as client:
        response = client.post("/internal/v1/advice", json={"goal": goal_payload()})

    assert response.status_code == 200
    assert response.json()["summary"]
    assert response.json()["next_actions"]
