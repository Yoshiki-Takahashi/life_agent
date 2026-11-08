from concurrent.futures import ThreadPoolExecutor
from datetime import UTC, date, datetime, timedelta

import pytest

from life_agent_core.database import decode


def make_goal(client):
    deadline = str(date.today() + timedelta(days=90))
    result = client.post(
        "/api/v1/goals/confirm",
        json=dict(
            title="日々の目標",
            target_date=deadline,
            metrics=[
                dict(name="ページ", unit="ページ", target_value=100),
                dict(name="ジム", unit="日", target_value=10, kind="occurrence"),
                dict(name="体脂肪率", unit="%", target_value=15, kind="measurement"),
                dict(
                    name="旅行準備",
                    unit="項目",
                    target_value=2,
                    kind="checklist",
                    checklist_items=["日付を決める", "宿を予約する"],
                ),
            ],
            milestones=[dict(title="振り返る", target_date=deadline)],
        ),
    )
    assert result.status_code == 201, result.text
    return result.json()


def save(client, goal, request_id, updates):
    for update in updates:
        update.setdefault(
            "kind", next(m["kind"] for m in goal["metrics"] if m["id"] == update["metric_id"])
        )
    return client.post(
        f"/api/v1/goals/{goal['id']}/progress",
        json=dict(body="実績を確認", client_request_id=request_id, metric_updates=updates),
    )


def test_mixed_types_preserve_meaning_and_retries(client):
    goal = make_goal(client)
    ids = [m["id"] for m in goal["metrics"]]
    day = str(datetime.now(UTC).date())
    updates = [
        dict(metric_id=ids[0], value=2),
        dict(metric_id=ids[1], value=1, activity_date=day),
        dict(metric_id=ids[2], value=25),
        dict(
            metric_id=ids[3], value=1, completed_items=["宿を予約する"], expected_completed_items=[]
        ),
    ]
    first = save(client, goal, "first", updates)
    assert first.status_code == 200, first.text
    retry = save(client, goal, "first", updates)
    assert retry.status_code == 200
    assert len(retry.json()["progress_logs"]) == 1
    assert [m["current_value"] for m in retry.json()["metrics"]] == [2, 1, 25, 1]
    second = save(
        client,
        goal,
        "second",
        [
            dict(metric_id=ids[2], value=20),
            dict(
                metric_id=ids[3],
                value=0,
                completed_items=[],
                expected_completed_items=["宿を予約する"],
            ),
        ],
    )
    assert second.status_code == 200, second.text
    saved = client.get(f"/api/v1/goals/{goal['id']}").json()
    assert [m["current_value"] for m in saved["metrics"]] == [2, 1, 20, 0]
    assert saved["metrics"][3]["completed_items"] == []
    assert saved["progress_logs"][0]["metric_updates"][0]["kind"] == "measurement"
    assert saved["progress_logs"][1]["metric_updates"][1]["activity_date"] == day
    assert save(client, goal, "zero", [dict(metric_id=ids[2], value=0)]).status_code == 200
    # Duplicate activity rolls back even other updates in the same request.
    duplicate = save(client, goal, "duplicate-day", [dict(metric_id=ids[0], value=1), updates[1]])
    assert duplicate.status_code == 409
    assert client.get(f"/api/v1/goals/{goal['id']}").json()["metrics"][0]["current_value"] == 2
    assert (
        client.get(
            f"/api/v1/goals/{goal['id']}", headers={"Authorization": "Bearer other"}
        ).status_code
        == 404
    )
    assert client.post(f"/api/v1/goals/{goal['id']}/complete").status_code == 200
    assert save(client, goal, "first", updates).status_code == 200
    assert save(client, goal, "closed", [dict(metric_id=ids[2], value=21)]).status_code == 409


@pytest.mark.parametrize(
    "invalid",
    [
        "unknown-item",
        "wrong-count",
        "duplicate-item",
        "wrong-type",
        "day-missing",
        "fractional-occurrence",
    ],
)
def test_invalid_updates_are_never_partially_saved(client, invalid):
    goal = make_goal(client)
    checklist, occurrence = goal["metrics"][3]["id"], goal["metrics"][1]["id"]
    bad = {
        "unknown-item": dict(
            metric_id=checklist, value=1, completed_items=["架空"], expected_completed_items=[]
        ),
        "wrong-count": dict(
            metric_id=checklist,
            value=2,
            completed_items=["宿を予約する"],
            expected_completed_items=[],
        ),
        "duplicate-item": dict(
            metric_id=checklist,
            value=2,
            completed_items=["宿を予約する"] * 2,
            expected_completed_items=[],
        ),
        "wrong-type": dict(metric_id=goal["metrics"][2]["id"], value=20, completed_items=[]),
        "day-missing": dict(metric_id=occurrence, value=1),
        "fractional-occurrence": dict(
            metric_id=occurrence, value=0.5, activity_date=str(date.today())
        ),
    }[invalid]
    result = save(client, goal, "invalid", [dict(metric_id=goal["metrics"][0]["id"], value=1), bad])
    assert result.status_code == 422, result.text
    current = client.get(f"/api/v1/goals/{goal['id']}").json()
    assert current["progress_logs"] == []
    assert all(m["current_value"] == 0 for m in current["metrics"])


@pytest.mark.parametrize("kind", ["occurrence", "checklist"])
def test_concurrent_activity_and_checklist_cannot_overwrite(client, kind):
    goal = make_goal(client)
    metric = next(m for m in goal["metrics"] if m["kind"] == kind)

    def record(index):
        update = dict(metric_id=metric["id"], value=1)
        if kind == "occurrence":
            update["activity_date"] = str(date.today())
        else:
            update.update(
                completed_items=[metric["checklist_items"][index]], expected_completed_items=[]
            )
        return save(client, goal, f"parallel-{index}", [update])

    with ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(record, [0, 1]))
    assert sorted(r.status_code for r in results) == [200, 409]
    current = client.get(f"/api/v1/goals/{goal['id']}").json()
    assert next(m for m in current["metrics"] if m["id"] == metric["id"])["current_value"] == 1
    assert len(current["progress_logs"]) == 1


def test_old_documents_remain_cumulative(client, create_goal, db_client):
    goal = create_goal()
    ref = db_client.collection("goals").document(goal["id"])
    stored = ref.get().to_dict()
    stored["schema_version"] = 1
    for m in stored["metrics"]:
        for key in ("kind", "checklist_items", "completed_items", "last_recorded_at"):
            m.pop(key)
    ref.set(stored)
    result = save(client, goal, "legacy", [dict(metric_id=goal["metrics"][0]["id"], value=1)])
    assert result.status_code == 200
    assert result.json()["metrics"][0]["kind"] == "cumulative"
    assert decode(ref.get().to_dict())["schema_version"] == 2


def test_replanning_cannot_reinterpret_history(client):
    from life_agent_core.main import app
    from life_agent_core.replan import get_replanner
    from life_agent_core.schemas import ReplanCandidate

    goal = make_goal(client)
    path = f"/api/v1/goals/{goal['id']}"
    saved = save(client, goal, "measured", [dict(metric_id=goal["metrics"][2]["id"], value=25)])
    assert saved.status_code == 200

    class Replanner:
        def __init__(self, change_kind):
            self.change_kind = change_kind

        def propose(self, context, reason):
            fields = ("name", "target_value", "unit", "kind", "checklist_items")
            metrics = [{key: m[key] for key in fields} for m in saved.json()["metrics"]]
            metrics[2]["target_value"] = 10  # Lower observation target is valid.
            if self.change_kind:
                metrics[2]["kind"] = "cumulative"
            return ReplanCandidate(
                proposed_plan=dict(
                    title=goal["title"],
                    description=None,
                    target_date=goal["target_date"],
                    metrics=metrics,
                    milestones=[dict(title="次の振り返り", target_date=goal["target_date"])],
                ),
                diff=[
                    dict(
                        change_type="update",
                        target_type="metric",
                        target_label="測定値の目標",
                        before="15",
                        after="10",
                        rationale="本人の希望",
                    )
                ],
            )

    app.dependency_overrides[get_replanner] = lambda: Replanner(True)
    assert client.post(path + "/replan/preview", json=dict(reason="調整")).status_code == 503
    app.dependency_overrides[get_replanner] = lambda: Replanner(False)
    proposal = client.post(path + "/replan/preview", json=dict(reason="調整"))
    assert proposal.status_code == 200, proposal.text
    applied = client.post(
        path + "/replan/apply", json=dict(proposal_id=proposal.json()["proposal_id"])
    )
    assert applied.status_code == 200, applied.text
    assert applied.json()["metrics"][2]["current_value"] == 25
    assert applied.json()["metrics"][2]["target_value"] == 10
    assert applied.json()["progress_logs"][0]["metric_updates"][0]["kind"] == "measurement"


def test_old_clients_only_generate_cumulative_and_cannot_write_new_kinds(client):
    request = dict(title="体脂肪率を測る", target_date=str(date.today() + timedelta(days=90)))
    old = client.post("/api/v1/goals/preview", json=request)
    assert old.status_code == 200
    assert all(m["kind"] == "cumulative" for m in old.json()["metrics"])
    new = client.post("/api/v1/goals/preview", json={**request, "metric_types_enabled": True})
    assert new.status_code == 200
    assert new.json()["metrics"][0]["kind"] == "measurement"
    goal = client.post("/api/v1/goals/confirm", json=new.json()).json()
    result = client.post(
        f"/api/v1/goals/{goal['id']}/progress",
        json=dict(
            body="旧アプリからの値",
            client_request_id="legacy-write",
            metric_updates=[dict(metric_id=goal["metrics"][0]["id"], value=1)],
        ),
    )
    assert result.status_code == 422
    assert client.get(f"/api/v1/goals/{goal['id']}").json()["progress_logs"] == []
