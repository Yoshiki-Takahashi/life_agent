import uuid

import pytest
from fastapi.testclient import TestClient
from test_goals import preview

from life_agent_core.main import app, get_advisor, get_progress_parser
from life_agent_core.progress_ai import AIUnavailableError


@pytest.mark.parametrize(
    ("title", "body", "value"),
    [
        ("本を読む", "2冊読み終えた", 2),
        ("アプリを開発", "成果物を1件完成させた", 1),
        ("筋トレを続ける", "2回トレーニングした", 2),
    ],
)
def test_preview_save_advice(client: TestClient, title, body, value):
    goal = client.post("/api/v1/goals/confirm", json=preview(client, title)).json()
    url = f"/api/v1/goals/{goal['id']}"
    result = client.post(url + "/progress/preview", json={"body": body})
    assert result.status_code == 200
    assert result.json()["metric_updates"][0]["value"] == value
    assert client.get(url).json() == goal
    saved = client.post(
        url + "/progress",
        json={
            "body": body,
            "client_request_id": "one",
            "metric_updates": result.json()["metric_updates"],
        },
    ).json()
    advice = client.post(url + "/advice")
    assert advice.status_code == 200
    assert advice.json()["next_actions"]
    after = client.get(url).json()
    after["updated_at"] = after["updated_at"].removesuffix("Z")
    saved["updated_at"] = saved["updated_at"].removesuffix("Z")
    assert after == saved


@pytest.mark.parametrize(
    "body", ["少し進んだ", "合計5冊になった", "2冊読む予定", "2冊読んでいない"]
)
def test_ambiguous_report_has_no_candidates(client, body):
    goal = client.post("/api/v1/goals/confirm", json=preview(client)).json()
    result = client.post(f"/api/v1/goals/{goal['id']}/progress/preview", json={"body": body})
    assert result.json()["metric_updates"] == []
    assert result.json()["warnings"]


@pytest.mark.parametrize("endpoint", ["/progress/preview", "/advice"])
def test_ai_requires_ownership(client, endpoint):
    goal = client.post("/api/v1/goals/confirm", json=preview(client)).json()
    result = client.post(
        f"/api/v1/goals/{goal['id']}" + endpoint,
        json={"body": "2冊読んだ"},
        headers={"Authorization": "Bearer other"},
    )
    assert result.status_code == 404


@pytest.mark.parametrize(
    "updates",
    [
        [{"metric_id": str(uuid.uuid4()), "value": 1}],
        [{"metric_id": "own", "value": 999}],
        [{"metric_id": "own", "value": 1}, {"metric_id": "own", "value": 1}],
        [{"metric_id": "own", "value": 0.001}],
    ],
)
def test_invalid_parser_output_does_not_mutate(client, updates):
    goal = client.post("/api/v1/goals/confirm", json=preview(client)).json()
    for item in updates:
        if item["metric_id"] == "own":
            item["metric_id"] = goal["metrics"][0]["id"]

    class BadParser:
        def parse(self, context, body):
            return {"metric_updates": updates, "warnings": []}

    app.dependency_overrides[get_progress_parser] = BadParser
    url = f"/api/v1/goals/{goal['id']}"
    assert client.post(url + "/progress/preview", json={"body": "2冊読んだ"}).status_code == 503
    assert client.get(url).json() == goal


def test_advice_failure_keeps_progress(client):
    goal = client.post("/api/v1/goals/confirm", json=preview(client)).json()
    url = f"/api/v1/goals/{goal['id']}"
    saved = client.post(
        url + "/progress",
        json={
            "body": "1冊読んだ",
            "client_request_id": "one",
            "metric_updates": [{"metric_id": goal["metrics"][0]["id"], "value": 1}],
        },
    ).json()

    class BrokenAdvisor:
        def advise(self, context):
            raise AIUnavailableError

    app.dependency_overrides[get_advisor] = BrokenAdvisor
    assert client.post(url + "/advice").status_code == 503
    after = client.get(url).json()
    after["updated_at"] = after["updated_at"].removesuffix("Z")
    saved["updated_at"] = saved["updated_at"].removesuffix("Z")
    assert after == saved
    del app.dependency_overrides[get_advisor]
    assert client.post(url + "/advice").status_code == 200
