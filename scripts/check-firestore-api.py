"""Explicit Cloud Run Job acceptance check; never changes serving traffic or real users."""

import asyncio
import json
import os
import time
from datetime import UTC, datetime, timedelta
from uuid import uuid4

import httpx

from life_agent_core.auth import FakeTokenVerifier, get_token_verifier
from life_agent_core.database import get_db
from life_agent_core.main import app


async def main():
    if not os.getenv("CLOUD_RUN_JOB") or os.getenv("FIRESTORE_EMULATOR_HOST"):
        raise RuntimeError("Run only as an explicitly dispatched Cloud Run verification Job")
    uid = f"firestore-check-{uuid4()}"
    refs = []
    db = get_db()
    start = time.monotonic()
    # This override exists only inside the non-public verification Job process.
    # The serving revision continues to verify real Firebase ID Tokens.
    app.dependency_overrides[get_token_verifier] = FakeTokenVerifier
    try:
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app),
            base_url="http://check",
            headers={"Authorization": f"Bearer {uid}"},
            timeout=60,
        ) as client:
            assert (
                await client.get("/api/v1/goals", headers={"Authorization": ""})
            ).status_code == 401
            assert (await client.get("/api/v1/goals")).json() == []
            plan = (
                await client.post(
                    "/api/v1/goals/preview",
                    json={
                        "title": "読書の結合確認",
                        "target_date": str(datetime.now(UTC).date() + timedelta(days=90)),
                    },
                )
            ).json()
            response = await client.post("/api/v1/goals/confirm", json=plan)
            assert response.status_code == 201, response.text
            goal = response.json()
            refs.append(db.collection("goals").document(goal["id"]))
            path = f"/api/v1/goals/{goal['id']}"
            assert (
                await client.get(path, headers={"Authorization": "Bearer check-other"})
            ).status_code == 404

            async def save(request_id, value=1):
                return await client.post(
                    path + "/progress",
                    json={
                        "body": "確認用の実績",
                        "client_request_id": request_id,
                        "metric_updates": [{"metric_id": goal["metrics"][0]["id"], "value": value}],
                    },
                )

            results = await asyncio.gather(*(save("同じ/保存ID") for _ in range(6)))
            assert all(r.status_code == 200 for r in results), [r.status_code for r in results]
            saved = (await client.get(path)).json()
            assert saved["metrics"][0]["current_value"] == 1 and len(saved["progress_logs"]) == 1
            results = await asyncio.gather(*(save(str(i), 0.1) for i in range(6)))
            assert all(r.status_code == 200 for r in results), [r.status_code for r in results]
            saved = (await client.get(path)).json()
            assert saved["metrics"][0]["current_value"] == 1.6 and len(saved["progress_logs"]) == 7
            results = await asyncio.gather(*(save(f"limit-{i}", 3) for i in range(6)))
            assert sorted(r.status_code for r in results) == [200] * 3 + [422] * 3
            saved = (await client.get(path)).json()
            assert (
                saved["metrics"][0]["current_value"] == 10.6 and len(saved["progress_logs"]) == 10
            )
            assert (await client.post(path + "/advice")).status_code == 200
            proposal = (await client.post(path + "/replan/preview", json={"reason": "調整"})).json()
            results = await asyncio.gather(
                *(
                    client.post(
                        path + "/replan/apply",
                        json={
                            "proposal_id": proposal["proposal_id"],
                        },
                    )
                    for _ in range(2)
                )
            )
            assert sorted(r.status_code for r in results) == [200, 409], [
                r.status_code for r in results
            ]
            reply = await client.post("/api/v1/coach/messages", json={"body": "全目標を相談"})
            assert reply.status_code == 200, reply.text
            assert goal["title"] in reply.json()["reply"]
        print(
            json.dumps(
                {"firestore_acceptance": "passed", "seconds": round(time.monotonic() - start, 2)}
            )
        )
    finally:
        for ref in refs:
            snapshot = ref.get()
            if snapshot.exists and snapshot.to_dict()["owner_id"] == uid:
                db.recursive_delete(ref)
        app.dependency_overrides.clear()
        db.close()


if __name__ == "__main__":
    asyncio.run(main())
