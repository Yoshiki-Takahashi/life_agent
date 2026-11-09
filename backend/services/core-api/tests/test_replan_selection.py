"""Selecting proposed changes saves only approved edits and preserves Metric history."""

from datetime import timedelta
from unittest.mock import Mock

import pytest

from life_agent_core.main import app
from life_agent_core.replan import FakeReplanner, get_replanner
from life_agent_core.schemas import GoalPlan


def preview(client, goal, edit):
    def propose(context, reason):
        candidate = FakeReplanner().propose(context, reason)
        plan = {
            "title": context.title,
            "description": context.description,
            "target_date": context.target_date,
            "metrics": [
                m.model_dump(include={"name", "target_value", "unit", "kind", "checklist_items"})
                for m in context.metrics
            ],
            "milestones": [
                m.model_dump(include={"title", "target_date"}) for m in context.milestones
            ],
        }
        edit(plan)
        candidate.proposed_plan = GoalPlan.model_validate(plan)
        # The Fake's deliberately unrelated prose must not define selectable changes.
        return candidate

    app.dependency_overrides[get_replanner] = lambda: Mock(propose=propose)
    response = client.post(f"/api/v1/goals/{goal['id']}/replan/preview", json={"reason": "見直す"})
    assert response.status_code == 200, response.text
    return response.json()


def apply(client, goal, proposal, excluded=()):
    return client.post(
        f"/api/v1/goals/{goal['id']}/replan/apply",
        json={"proposal_id": proposal["proposal_id"], "excluded_change_ids": list(excluded)},
    )


def test_actual_diff_and_partial_rejection_preserve_plan_and_history(client, create_goal):
    goal = create_goal(extra_metric=True)
    path = f"/api/v1/goals/{goal['id']}"
    assert (
        client.post(
            path + "/progress",
            json={
                "body": "実績",
                "client_request_id": "one",
                "metric_updates": [{"metric_id": goal["metrics"][1]["id"], "value": 2}],
            },
        ).status_code
        == 200
    )
    before = client.get(path).json()

    def edit(plan):
        plan["metrics"][0]["target_value"] = 20
        plan["metrics"].pop()
        plan["milestones"][0]["title"] = "新しい一歩"
        plan["milestones"].pop()

    proposal = preview(client, goal, edit)
    assert {(d["change_id"], d["change_type"], d["target_type"]) for d in proposal["diff"]} == {
        ("metric:0", "update", "metric"),
        ("metric:1", "remove", "metric"),
        ("milestone:0", "update", "milestone"),
        ("milestone:2", "remove", "milestone"),
    }
    assert client.get(path).json() == before
    response = apply(client, goal, proposal, ["metric:0", "metric:1", "milestone:2"])
    assert response.status_code == 200, response.text
    saved = response.json()
    assert saved["metrics"] == before["metrics"]
    assert saved["progress_logs"] == before["progress_logs"]
    assert saved["milestones"][0]["title"] == "新しい一歩"
    assert saved["milestones"][1:] == before["milestones"][1:]
    assert apply(client, goal, proposal).status_code == 409


def test_excluding_first_addition_keeps_second_addition_without_reusing_archived_id(
    client, create_goal
):
    goal = create_goal(extra_metric=True)
    removed_id = goal["metrics"][1]["id"]
    first = preview(client, goal, lambda p: p["metrics"].pop())
    response = apply(client, goal, first)
    assert response.status_code == 200, response.text
    current = response.json()

    def add(plan):
        plan["metrics"] += [
            dict(name="追加A", target_value=4, unit="回"),
            dict(name="追加B", target_value=8, unit="分"),
        ]
        plan["milestones"] += [
            dict(title="追加の節目A", target_date=plan["target_date"]),
            dict(title="追加の節目B", target_date=plan["target_date"]),
        ]

    proposal = preview(client, current, add)
    assert all(d["change_type"] == "add" for d in proposal["diff"])
    response = apply(client, current, proposal, ["metric:1", "milestone:3"])
    assert response.status_code == 200, response.text
    saved = response.json()
    assert [m["name"] for m in saved["metrics"]] == [goal["metrics"][0]["name"], "追加B"]
    assert saved["metrics"][0]["id"] == goal["metrics"][0]["id"]
    assert saved["metrics"][1]["id"] != removed_id
    assert saved["milestones"][-1]["title"] == "追加の節目B"
    assert [m["position"] for m in saved["metrics"]] == [0, 1]
    assert [m["position"] for m in saved["milestones"]] == [0, 1, 2, 3]


def test_rejected_remove_keeps_later_metric_identity_and_progress(client, create_goal):
    goal = create_goal(extra_metric=True)

    def edit(plan):
        plan["metrics"].clear()
        plan["metrics"].append(dict(name="最初の項目", target_value=6, unit="冊"))

    proposal = preview(client, goal, edit)
    response = apply(client, goal, proposal, ["metric:1"])
    assert response.status_code == 200, response.text
    assert response.json()["metrics"][1] == goal["metrics"][1]


@pytest.mark.parametrize("selection", ["unknown", "all", "incompatible"])
def test_invalid_selection_is_atomic_and_can_be_corrected(client, create_goal, selection):
    goal = create_goal()

    def edit(plan):
        plan["target_date"] += timedelta(days=14)
        plan["milestones"][-1]["target_date"] = plan["target_date"]

    proposal = preview(client, goal, edit)
    excluded = {
        "unknown": ["metric:999"],
        "all": [d["change_id"] for d in proposal["diff"]],
        "incompatible": ["goal:target_date"],
    }[selection]
    assert apply(client, goal, proposal, excluded).status_code == 422
    assert client.get(f"/api/v1/goals/{goal['id']}").json() == goal
    assert apply(client, goal, proposal).status_code == 200


def test_rejecting_lower_target_allows_other_change_after_new_progress(client, create_goal):
    goal = create_goal()

    def edit(plan):
        plan["title"] = "新しい目標名"
        plan["description"] = "長い説明" * 400
        plan["metrics"][0]["target_value"] = 1

    proposal = preview(client, goal, edit)
    path = f"/api/v1/goals/{goal['id']}"
    client.post(
        path + "/progress",
        json={
            "body": "実績",
            "client_request_id": "later",
            "metric_updates": [{"metric_id": goal["metrics"][0]["id"], "value": 5}],
        },
    )
    assert apply(client, goal, proposal).status_code == 422
    response = apply(client, goal, proposal, ["metric:0"])
    assert response.status_code == 200, response.text
    assert response.json()["title"] == "新しい目標名"
    assert response.json()["metrics"][0]["target_value"] == goal["metrics"][0]["target_value"]
    assert response.json()["metrics"][0]["current_value"] == 5


def test_legacy_pending_proposal_still_applies_but_cannot_reinterpret_metrics(
    client, create_goal, db_client
):
    goal = create_goal()
    proposal = preview(client, goal, lambda p: p.update(title="確認した目標名"))
    ref = (
        db_client.collection("goals")
        .document(goal["id"])
        .collection("replan_proposals")
        .document(proposal["proposal_id"])
    )
    stored = ref.get().to_dict()
    for change in stored["diff"]:
        change.pop("change_id")
    # Pre-existing stored candidates did not have selection IDs.
    ref.update({"diff": stored["diff"]})
    assert apply(client, goal, proposal).status_code == 200
    current = client.get(f"/api/v1/goals/{goal['id']}").json()
    invalid = preview(client, current, lambda p: p.update(title="再変更"))
    invalid_ref = ref.parent.document(invalid["proposal_id"])
    changed_plan = invalid_ref.get().to_dict()["proposed_plan"]
    changed_plan["metrics"][0]["unit"] = "別の単位"
    invalid_ref.update({"proposed_plan": changed_plan})
    assert apply(client, current, invalid).status_code == 422
    assert client.get(f"/api/v1/goals/{goal['id']}").json() == current
