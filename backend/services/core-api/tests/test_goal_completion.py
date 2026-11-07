from concurrent.futures import ThreadPoolExecutor
from uuid import uuid4


def test_completion_archives_preserves_history_and_is_idempotent(client, create_goal):
    goal = create_goal()
    path = f"/api/v1/goals/{goal['id']}"
    progress = dict(
        body="読んだ",
        client_request_id="before-completion",
        metric_updates=[dict(metric_id=goal["metrics"][0]["id"], value=1)],
    )
    assert client.post(path + "/progress", json=progress).status_code == 200
    proposal = client.post(path + "/replan/preview", json={"reason": "調整したい"}).json()
    first = client.post(path + "/complete")
    assert first.status_code == 200
    completed = first.json()
    assert completed["status"] == "completed"
    assert len(completed["progress_logs"]) == 1
    assert completed["metrics"][0]["current_value"] == 1
    assert client.post(path + "/complete").json() == completed
    assert client.get(path).json() == completed
    assert client.get("/api/v1/goals?status=active").json() == []
    assert [g["id"] for g in client.get("/api/v1/goals?status=completed").json()] == [goal["id"]]
    assert client.post(path + "/progress", json=progress).status_code == 200
    assert (
        client.post(path + "/progress", json=progress | {"client_request_id": "new"}).status_code
        == 409
    )
    assert client.post(path + "/replan/preview", json={"reason": "変更"}).status_code == 409
    assert (
        client.post(
            path + "/replan/apply", json={"proposal_id": proposal["proposal_id"]}
        ).status_code
        == 409
    )
    assert client.get(path).json() == completed


def test_completion_checks_owner_and_authentication(client, create_goal):
    goal = create_goal()
    path = f"/api/v1/goals/{goal['id']}/complete"
    assert client.post(path, headers={"Authorization": "Bearer user-b"}).status_code == 404
    assert client.post(path, headers={"Authorization": "Bearer invalid"}).status_code == 401
    assert client.post(f"/api/v1/goals/{uuid4()}/complete").status_code == 404
    assert client.get(f"/api/v1/goals/{goal['id']}").json()["status"] == "active"


def test_completion_racing_progress_preserves_consistent_history(client, create_goal):
    goal = create_goal()
    path = f"/api/v1/goals/{goal['id']}"
    payload = dict(
        body="読んだ",
        client_request_id="race",
        metric_updates=[dict(metric_id=goal["metrics"][0]["id"], value=1)],
    )
    with ThreadPoolExecutor(max_workers=2) as pool:
        complete = pool.submit(client.post, path + "/complete")
        progress = pool.submit(client.post, path + "/progress", json=payload)
        assert complete.result().status_code == 200
        result = progress.result()
    assert result.status_code in (200, 409)
    saved = client.get(path).json()
    assert saved["status"] == "completed"
    expected = 1 if result.status_code == 200 else 0
    assert len(saved["progress_logs"]) == expected
    assert saved["metrics"][0]["current_value"] == expected


def test_coach_excludes_completed_goals(client, create_goal):
    from unittest.mock import Mock

    from life_agent_core.coach import get_coach
    from life_agent_core.main import app
    from life_agent_core.schemas import CoachReply

    archived = create_goal(title="完了した読書")
    active = create_goal(title="これからの読書")
    client.post(f"/api/v1/goals/{archived['id']}/complete")
    coach = Mock()
    coach.respond.return_value = CoachReply(
        intent="discuss", reply="相談への回答", goal_id=None, record_body=None
    )
    app.dependency_overrides[get_coach] = lambda: coach
    result = client.post("/api/v1/coach/messages", json={"body": "次に何をする？"})
    assert result.status_code == 200
    assert [str(g.id) for g in coach.respond.call_args.args[0].goals] == [active["id"]]
