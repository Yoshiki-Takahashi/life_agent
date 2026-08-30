import json
import os
from datetime import date, timedelta
from typing import Any
from urllib.request import Request, urlopen

CORE_API_URL = os.getenv("CORE_API_URL", "http://127.0.0.1:8000")


def api_request(method: str, path: str, payload: dict[str, Any] | None = None) -> Any:
    body = json.dumps(payload).encode() if payload is not None else None
    request = Request(
        f"{CORE_API_URL}{path}",
        data=body,
        headers={"Content-Type": "application/json"},
        method=method,
    )
    with urlopen(request) as response:
        return json.load(response)


def test_preview_confirm_and_get_goal() -> None:
    plan = api_request(
        "POST",
        "/api/v1/goals/preview",
        {
            "title": "読書習慣を作る",
            "description": "Weekend 2 E2E",
            "target_date": (date.today() + timedelta(days=90)).isoformat(),
        },
    )
    plan["metrics"][0] = {"name": "読了数", "target_value": 6, "unit": "冊"}
    plan["milestones"][0]["title"] = "読む本を6冊決める"

    created = api_request("POST", "/api/v1/goals/confirm", plan)
    fetched = api_request("GET", f"/api/v1/goals/{created['id']}")

    assert fetched == created
    assert fetched["metrics"][0]["name"] == "読了数"
    assert fetched["milestones"][0]["title"] == "読む本を6冊決める"
