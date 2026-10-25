"""Explicit smoke test against a deployed development project with real Firebase and AI."""

import json
import os
import secrets
import uuid
from datetime import date, timedelta
from pathlib import Path

import httpx


def main() -> None:
    base_url = os.environ["CORE_API_URL"].rstrip("/")
    api_key = os.environ["FIREBASE_API_KEY"]
    with httpx.Client(timeout=40) as client:
        signup = client.post(
            "https://identitytoolkit.googleapis.com/v1/accounts:signUp",
            params={"key": api_key},
            json={
                "email": f"weekend8-{uuid.uuid4().hex}@example.com",
                "password": secrets.token_urlsafe(32),
                "returnSecureToken": True,
            },
        )
        assert signup.status_code == 200, f"Firebase signup: HTTP {signup.status_code}"
        token = signup.json()["idToken"]
        headers = {"Authorization": f"Bearer {token}"}

        def request(method: str, path: str, payload=None, expected=200):
            response = client.request(method, base_url + path, headers=headers, json=payload)
            assert response.status_code == expected, (
                f"{method} {path}: expected {expected}, got {response.status_code}"
            )
            return response.json()

        today = date.today()
        plan = {
            "title": "12冊の本を読む（Weekend 8クラウド検証）",
            "description": "合成データによる実AI接続確認",
            "target_date": str(today + timedelta(days=90)),
            "metrics": [{"name": "読了冊数", "target_value": 12, "unit": "冊"}],
            "milestones": [
                {"title": title, "target_date": str(today + timedelta(days=days))}
                for title, days in [("4冊読む", 30), ("8冊読む", 60), ("12冊読む", 90)]
            ],
        }
        goal = request("POST", "/api/v1/goals/confirm", plan, expected=201)
        path = f"/api/v1/goals/{goal['id']}"
        parsed = request("POST", path + "/progress/preview", {"body": "今日は2冊読み終えた"})
        assert parsed["metric_updates"] == [{"metric_id": goal["metrics"][0]["id"], "value": 2}]
        saved = request("POST", path + "/progress", {
            "body": "今日は2冊読み終えた",
            "client_request_id": str(uuid.uuid4()),
            "metric_updates": parsed["metric_updates"],
        })
        assert saved["metrics"][0]["current_value"] == 2
        proposal = request("POST", path + "/replan/preview", {
            "reason": "忙しくなったので、読了冊数を6冊に減らし、期限を14日延長したい。",
        })
        unchanged = request("GET", path)
        assert unchanged == saved, "Preview changed canonical goal"
        second = request("POST", path + "/replan/preview", {
            "reason": "忙しくなったので、期限を14日延長したい。",
        })
        applied = request("POST", path + "/replan/apply", {
            "proposal_id": proposal["proposal_id"],
        })
        assert applied["plan_revision"] == saved["plan_revision"] + 1
        assert applied["progress_logs"] == saved["progress_logs"], "Progress history changed"
        assert applied["metrics"][0]["current_value"] == 2
        assert applied["target_date"] == proposal["proposed_plan"]["target_date"]
        request("POST", path + "/replan/apply", {"proposal_id": second["proposal_id"]}, 409)
        assert request("GET", path) == applied, "Stale proposal changed goal"
        advice = request("POST", path + "/advice")
        assert advice["summary"] and advice["next_actions"]
        report = {
            "goal_id": goal["id"],
            "proposal": proposal,
            "applied_goal": applied,
            "advice": advice,
            "checks": ["firebase_auth", "real_progress_parser", "preview_no_mutation",
                       "real_replanner", "approved_apply", "history_preserved",
                       "stale_rejected_409", "real_advisor"],
        }
        output = Path(os.environ.get("CLOUD_EVALUATION_REPORT", "/tmp/weekend8-cloud.json"))
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2))
        print(json.dumps({"checks": report["checks"], "report": str(output)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
