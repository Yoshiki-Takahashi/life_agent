"""Cross-process HTTP/schema coverage with an isolated Firestore emulator."""
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
        goal_input = {"title": "本を読む", "target_date": str(date.today() + timedelta(days=90))}
        questions = client.post("/api/v1/goals/questions", json=goal_input)
        assert questions.status_code == 200
        assert client.get("/api/v1/goals").json() == []
        answers = [dict(question=q, answer=True) for q in questions.json()["questions"]]
        plan = client.post("/api/v1/goals/preview", json={**goal_input, "answers": answers}).json()
        assert any(metric["unit"] == "分" for metric in plan["metrics"])
        assert len(plan["milestones"]) > 3
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
        # Both services must accept explicit zero and preserve an over-target observation.
        for value, body in [(13, "13冊読み終えた"), (0, "今回0冊読み終えた")]:
            candidate = client.post(path + "/progress/preview", json={"body": body})
            assert candidate.status_code == 200
            updates = candidate.json()["metric_updates"]
            assert [(u["metric_id"], u["value"]) for u in updates] == [(goal["metrics"][0]["id"], value)]
            payload = dict(body=body, client_request_id=f"boundary-{value}", metric_updates=updates)
            for _ in range(2):
                assert client.post(path + "/progress", json=payload).status_code == 200
        result = client.get(path).json()
        assert result["metrics"][0]["current_value"] == 14
        assert len(result["progress_logs"]) == 3
        assert result["progress_logs"][0]["metric_updates"][0]["value"] == 0


@pytest.mark.parametrize(
    ("title", "body", "kind"),
    [
        ("体脂肪率を測る", "体脂肪率25%だった", "measurement"),
        ("ジムの習慣", "ジムに行った", "occurrence"),
        ("旅行の準備", "宿を決めるを完了した", "checklist"),
    ],
)
def test_typed_plan_parser_advice_replan_boundary(title, body, kind):
    url = os.getenv("CORE_API_URL")
    if not url:
        pytest.skip("Run scripts/test-weekend-3.sh")
    with httpx.Client(
        base_url=url, headers={"Authorization": f"Bearer {uuid4()}"}, timeout=60
    ) as client:
        plan = client.post(
            "/api/v1/goals/preview",
            json=dict(
                title=title,
                target_date=str(date.today() + timedelta(days=90)),
                metric_types_enabled=True,
            ),
        ).json()
        assert plan["metrics"][0]["kind"] == kind
        goal = client.post("/api/v1/goals/confirm", json=plan).json()
        path = f"/api/v1/goals/{goal['id']}"
        result = client.post(path + "/progress/preview", json=dict(body=body))
        assert result.status_code == 200, result.text
        updates = result.json()["metric_updates"]
        assert len(updates) == 1
        updates[0]["kind"] = kind
        if kind == "occurrence":
            updates[0]["activity_date"] = str(date.today())
        if kind == "checklist":
            updates[0]["expected_completed_items"] = []
        saved = client.post(
            path + "/progress",
            json=dict(body=body, client_request_id="one", metric_updates=updates),
        )
        assert saved.status_code == 200, saved.text
        assert saved.json()["progress_logs"][0]["metric_updates"][0]["kind"] == kind
        advice = client.post(path + "/advice")
        assert advice.status_code == 200, advice.text
        proposal = client.post(path + "/replan/preview", json=dict(reason="期限を延ばしたい"))
        assert proposal.status_code == 200, proposal.text
        assert proposal.json()["proposed_plan"]["metrics"][0]["kind"] == kind
        applied = client.post(
            path + "/replan/apply",
            json=dict(proposal_id=proposal.json()["proposal_id"]),
        )
        assert applied.status_code == 200, applied.text
        assert (
            applied.json()["metrics"][0]["current_value"]
            == saved.json()["metrics"][0]["current_value"]
        )
