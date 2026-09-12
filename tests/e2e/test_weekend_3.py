import json
import os
from datetime import date, timedelta
from typing import Any
from urllib.request import Request, urlopen

CORE_API_URL = os.getenv("CORE_API_URL", "http://127.0.0.1:18000")
GOAL_PLANNER_URL = os.getenv("GOAL_PLANNER_URL", "http://127.0.0.1:18001")


def api_request(
    base_url: str,
    method: str,
    path: str,
    payload: dict[str, Any] | None = None,
) -> Any:
    body = json.dumps(payload).encode() if payload is not None else None
    request = Request(
        f"{base_url}{path}",
        data=body,
        headers={"Content-Type": "application/json"},
        method=method,
    )
    with urlopen(request) as response:
        return json.load(response)


def test_independent_planner_preview_confirm_and_get_goal() -> None:
    assert api_request(GOAL_PLANNER_URL, "GET", "/health") == {"status": "ok"}

    plan = api_request(
        CORE_API_URL,
        "POST",
        "/api/v1/goals/preview",
        {
            "title": "Weekend 3.1 service boundary",
            "description": "Keyless multi-process E2E",
            "target_date": (date.today() + timedelta(days=90)).isoformat(),
        },
    )
    assert plan["metrics"][0]["name"] == "独立Planner達成率"

    created = api_request(CORE_API_URL, "POST", "/api/v1/goals/confirm", plan)
    fetched = api_request(CORE_API_URL, "GET", f"/api/v1/goals/{created['id']}")

    assert fetched == created
    assert fetched["metrics"][0]["name"] == "独立Planner達成率"
