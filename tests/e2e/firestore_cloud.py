"""Explicit candidate-only smoke check with real Firebase, HTTP, AI, and Firestore."""

import os
import secrets
import subprocess
from datetime import UTC, datetime, timedelta
from uuid import uuid4

import httpx
from google.cloud import firestore
from google.oauth2.credentials import Credentials


def main():
    url = os.environ["FIRESTORE_CANDIDATE_URL"].rstrip("/")
    api_key = os.environ["FIREBASE_API_KEY"]
    if not url.startswith("https://firestore-check---") or os.getenv("FIRESTORE_EMULATOR_HOST"):
        raise RuntimeError("Use only the explicitly staged firestore-check candidate URL")
    access_token = subprocess.check_output(
        ["gcloud", "auth", "print-access-token"], text=True
    ).strip()
    db = firestore.Client(project="lifeagent-505614", credentials=Credentials(access_token))
    users, refs = [], []
    with httpx.Client(timeout=60) as client:
        try:
            for _ in range(2):
                response = client.post(
                    "https://identitytoolkit.googleapis.com/v1/accounts:signUp",
                    params={"key": api_key},
                    json={
                        "email": f"firestore-check-{uuid4().hex}@example.com",
                        "password": secrets.token_urlsafe(32),
                        "returnSecureToken": True,
                    },
                )
                assert response.status_code == 200, f"Firebase signup HTTP {response.status_code}"
                users.append(response.json())

            def request(method, path, payload=None, *, expected=200, user=0):
                response = client.request(
                    method,
                    url + path,
                    json=payload,
                    headers={"Authorization": "Bearer " + users[user]["idToken"]},
                )
                assert response.status_code == expected, (
                    f"{method} {path}: HTTP {response.status_code}"
                )
                return response.json()

            assert client.get(url + "/api/v1/goals").status_code == 401
            assert request("GET", "/api/v1/goals") == []
            plan = request(
                "POST",
                "/api/v1/goals/preview",
                {
                    "title": "読書のFirestore切替確認",
                    "target_date": str(datetime.now(UTC).date() + timedelta(days=90)),
                },
            )
            goal = request("POST", "/api/v1/goals/confirm", plan, expected=201)
            refs.append(db.collection("goals").document(goal["id"]))
            path = "/api/v1/goals/" + goal["id"]
            request("GET", path, expected=404, user=1)
            payload = {
                "body": "今日は1冊読み終えた",
                "client_request_id": str(uuid4()),
                "metric_updates": [{"metric_id": goal["metrics"][0]["id"], "value": 1}],
            }
            first = request("POST", path + "/progress", payload)
            assert request("POST", path + "/progress", payload) == first
            assert len(first["progress_logs"]) == 1
            assert request("POST", path + "/advice")["next_actions"]
            proposal = request("POST", path + "/replan/preview", {"reason": "期限を14日延ばしたい"})
            applied = request(
                "POST", path + "/replan/apply", {"proposal_id": proposal["proposal_id"]}
            )
            assert applied["plan_revision"] == 2
            assert request("GET", path) == applied
            assert request("POST", "/api/v1/coach/messages", {"body": "今の目標を相談したい"})[
                "reply"
            ]
            print("Candidate HTTP / real Firebase / real AI / Firestore / owner isolation: passed")
        finally:
            for ref in refs:
                snapshot = ref.get()
                if snapshot.exists and snapshot.to_dict()["owner_id"] in {
                    u["localId"] for u in users
                }:
                    db.recursive_delete(ref)
            for user in users:
                response = client.post(
                    "https://identitytoolkit.googleapis.com/v1/accounts:delete",
                    params={"key": api_key},
                    json={"idToken": user["idToken"]},
                )
                assert response.status_code == 200, "Test account cleanup failed"
            db.close()


if __name__ == "__main__":
    main()
