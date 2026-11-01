"""Run explicitly against the isolated server: W7_API_URL=http://127.0.0.1:18007."""

import os
import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import UTC, datetime, timedelta

import httpx
import pytest


@pytest.mark.parametrize("same_id", [True, False])
def test_parallel_progress_is_atomic(same_id):
    url = os.environ.get("W7_API_URL")
    if not url:
        pytest.skip("Set W7_API_URL to isolated Fake API backed by PostgreSQL")
    headers = {"Authorization": f"Bearer e2e-{uuid.uuid4()}"}
    with httpx.Client(base_url=url, headers=headers) as client:
        plan = client.post(
            "/api/v1/goals/preview",
            json={
                "title": "本を読む",
                "target_date": (datetime.now(UTC).date() + timedelta(days=90)).isoformat(),
            },
        ).json()
        goal = client.post("/api/v1/goals/confirm", json=plan).json()
        path = f"/api/v1/goals/{goal['id']}"

        def save(index):
            return client.post(
                path + "/progress",
                json={
                    "body": "1冊読んだ",
                    "client_request_id": "same" if same_id else str(index),
                    "metric_updates": [
                        {"metric_id": goal["metrics"][0]["id"], "value": 1}
                    ],
                },
            )

        with ThreadPoolExecutor(max_workers=6) as pool:
            responses = list(pool.map(save, range(6)))
        assert all(r.status_code == 200 for r in responses)
        saved = client.get(path).json()
        assert saved["metrics"][0]["current_value"] == (1 if same_id else 6)
        assert len(saved["progress_logs"]) == (1 if same_id else 6)
