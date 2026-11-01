"""Cross-process HTTP/schema coverage; SQLite unit tests cannot replace this boundary."""
import os
from datetime import date, timedelta
from uuid import uuid4

import httpx
import pytest


def test_core_ai_and_database_boundary():
    url = os.getenv("CORE_API_URL")
    if not url:
        pytest.skip("Run scripts/test-weekend-3.sh for isolated integration")
    with httpx.Client(base_url=url, headers={"Authorization": f"Bearer {uuid4()}"}) as client:
        plan = client.post("/api/v1/goals/preview", json={"title": "本を読む",
            "target_date": str(date.today() + timedelta(days=90))}).json()
        plan["metrics"] = [dict(name="読了冊数", target_value=12, unit="冊")]
        goal = client.post("/api/v1/goals/confirm", json=plan).json()
        path = f"/api/v1/goals/{goal['id']}"
        candidate = client.post(path + "/progress/preview", json={"body": "1冊読み終えた"}).json()
        assert candidate["metric_updates"][0]["value"] == 1
        assert not client.get(path).json()["progress_logs"]
        saved = client.post(path + "/progress", json=dict(body="1冊読み終えた", client_request_id="one",
            metric_updates=candidate["metric_updates"])).json()
        assert saved["metrics"][0]["current_value"] == 1
        assert client.post(path + "/advice").json()["next_actions"]
        proposal = client.post(path + "/replan/preview", json={"reason": "無理なく続けたい"}).json()
        updated = client.post(path + "/replan/apply", json={"proposal_id": proposal["proposal_id"]}).json()
        assert updated["plan_revision"] == 2 and len(updated["progress_logs"]) == 1
        reply = client.post("/api/v1/coach/messages", json={"body": "全目標を相談"})
        assert reply.status_code == 200 and goal["title"] in reply.json()["reply"]
