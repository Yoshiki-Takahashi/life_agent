"""Private deployment Job: candidate HTTP security plus Core/Planner/DB integration."""

import asyncio
import os
from datetime import date, timedelta
from uuid import uuid4

import httpx


async def check():
    if not os.getenv("CLOUD_RUN_JOB") or os.getenv("FIRESTORE_EMULATOR_HOST"):
        raise RuntimeError("Deployment verification runs only in a private Cloud Run Job")
    uid = f"deploy-check-{uuid4().hex}"
    # This test identity exists only in the private Job, never in a serving revision.
    # The Job has the candidate Core image, DB permissions and Planner configuration.
    os.environ["ALLOWED_USER_ID"] = uid
    from google.cloud.firestore_v1.base_query import FieldFilter

    from life_agent_core.auth import FakeTokenVerifier, get_token_verifier
    from life_agent_core.config import get_settings
    from life_agent_core.database import get_db
    from life_agent_core.main import app

    get_settings.cache_clear()
    settings = get_settings()
    if settings.auth_backend != "firebase" or settings.goal_planner_backend != "http":
        raise RuntimeError("Expected the candidate's Firebase and internal HTTP configuration")
    db = get_db()
    app.dependency_overrides[get_token_verifier] = FakeTokenVerifier
    try:
        # These requests go to the actual serving process of the unpromoted Core.
        async with httpx.AsyncClient(timeout=30) as network:
            url = os.environ["CORE_CANDIDATE_URL"]
            assert (await network.get(url + "/health")).status_code == 200
            for headers in ({}, {"Authorization": "Bearer invalid-deployment-check"}):
                assert (
                    await network.get(url + "/api/v1/goals", headers=headers)
                ).status_code == 401
        # Test-user authentication is substituted only for in-process business
        # requests. Candidate Planner calls use real IAM + the real OpenAI provider.
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app),
            base_url="http://check",
            headers={"Authorization": f"Bearer {uid}"},
        ) as client:
            assert (await client.get("/api/v1/goals")).json() == []
            payload = dict(
                title="読書のデプロイ確認",
                description="検証用の一時データ",
                target_date=str(date.today() + timedelta(days=90)),
            )
            questions = await client.post("/api/v1/goals/questions", json=payload)
            assert questions.status_code == 200, "Candidate Planner question generation failed"
            payload["answers"] = [
                dict(question=q, answer=False) for q in questions.json()["questions"]
            ]
            preview = await client.post("/api/v1/goals/preview", json=payload)
            assert preview.status_code == 200, "Candidate Planner plan generation failed"
            assert (await client.get("/api/v1/goals")).json() == []
            response = await client.post("/api/v1/goals/confirm", json=preview.json())
            assert response.status_code == 201, "Candidate Core could not save its plan"
            goal = response.json()
            path = f"/api/v1/goals/{goal['id']}"
            assert (await client.get(path)).json() == goal
            progress = dict(
                body="検証用の今回値",
                client_request_id="deployment-check",
                metric_updates=[dict(metric_id=goal["metrics"][0]["id"], value=0.01)],
            )
            for _ in range(2):
                assert (await client.post(path + "/progress", json=progress)).status_code == 200
            saved = (await client.get(path)).json()
            assert saved["metrics"][0]["current_value"] == 0.01
            assert len(saved["progress_logs"]) == 1
            # Exercise the same compound-index query used by the Android list.
            assert [item["id"] for item in (await client.get("/api/v1/goals")).json()] == [
                goal["id"]
            ]
    finally:
        # Query the fresh unique owner, so a failed response cannot orphan a write.
        try:
            query = db.collection("goals").where(filter=FieldFilter("owner_id", "==", uid))
            for snapshot in query.stream():
                if snapshot.to_dict()["owner_id"] != uid:
                    raise RuntimeError("Unexpected owner during test-data cleanup")
                db.recursive_delete(snapshot.reference)
        finally:
            app.dependency_overrides.clear()
            db.close()
    print("Candidate HTTP authentication, Core/Planner/OpenAI and Firestore save/retry: passed")


if __name__ == "__main__":
    asyncio.run(check())
