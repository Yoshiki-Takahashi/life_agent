"""User-visible contracts: confirmation, isolation, atomic writes, and conflict handling."""

from concurrent.futures import ThreadPoolExecutor
from unittest.mock import Mock
from uuid import uuid4

import pytest
from google.api_core.exceptions import Aborted, ServiceUnavailable
from google.cloud.firestore_v1.document import DocumentReference
from google.cloud.firestore_v1.transaction import Transaction

from life_agent_core.ai_service import AIUnavailableError
from life_agent_core.coach import get_coach
from life_agent_core.main import app
from life_agent_core.progress import get_advisor, get_progress_parser

COACH = "/api/v1/coach/messages"


def save(client, goal, value=1, request_id="one", metric=None):
    return client.post(
        f"/api/v1/goals/{goal['id']}/progress",
        json={
            "body": "今回の実績",
            "client_request_id": request_id,
            "metric_updates": [{"metric_id": metric or goal["metrics"][0]["id"], "value": value}],
        },
    )


@pytest.mark.parametrize(
    ("title", "body"),
    [
        ("本を読む", "1冊読み終えた"),
        ("アプリを開発", "成果物を1件完成させた"),
        ("筋トレを続ける", "1回トレーニングした"),
    ],
)
def test_plan_progress_advice_and_approved_replan(client, create_goal, title, body):
    goal = create_goal(title)
    path = f"/api/v1/goals/{goal['id']}"
    reply = client.post(COACH, json={"body": body}).json()
    assert reply["goal_id"] == goal["id"] and reply["record_body"] == body
    candidate = client.post(path + "/progress/preview", json={"body": reply["record_body"]}).json()
    assert client.get(path).json() == goal  # Neither conversation nor parsing saves progress.
    assert candidate["metric_updates"][0]["value"] == 1
    payload = dict(body=body, client_request_id="same", metric_updates=candidate["metric_updates"])
    for _ in range(2):
        assert client.post(path + "/progress", json=payload).status_code == 200
    saved = client.get(path).json()
    assert saved["metrics"][0]["current_value"] == 1 and len(saved["progress_logs"]) == 1
    app.dependency_overrides[get_advisor] = lambda: Mock(
        advise=Mock(side_effect=AIUnavailableError)
    )
    assert client.post(path + "/advice").status_code == 503
    del app.dependency_overrides[get_advisor]
    assert client.post(path + "/advice").json()["next_actions"]
    assert len(client.get(path).json()["progress_logs"]) == 1
    proposals = [
        client.post(path + "/replan/preview", json={"reason": "負担を調整したい"}).json()
        for _ in range(2)
    ]
    before_apply = client.get(path).json()
    assert before_apply["metrics"] == saved["metrics"]
    assert before_apply["progress_logs"] == saved["progress_logs"]
    assert before_apply["plan_revision"] == saved["plan_revision"]
    assert (
        client.post(
            path + "/replan/apply", json={"proposal_id": proposals[0]["proposal_id"]}
        ).status_code
        == 200
    )
    updated = client.get(path).json()
    assert updated["plan_revision"] == goal["plan_revision"] + 1
    assert updated["metrics"][0]["id"] == goal["metrics"][0]["id"]
    assert updated["progress_logs"] == saved["progress_logs"]
    for proposal in proposals:  # Both an already-applied and a stale proposal are rejected.
        assert (
            client.post(
                path + "/replan/apply", json={"proposal_id": proposal["proposal_id"]}
            ).status_code
            == 409
        )


def test_authentication_and_owner_isolation(client, unauthenticated_client, create_goal):
    own = create_goal()
    foreign = create_goal("他人の筋トレ", owner="other")
    assert unauthenticated_client.get("/api/v1/goals").status_code == 401
    assert (
        client.get("/api/v1/goals", headers={"Authorization": "Bearer invalid"}).status_code == 401
    )
    assert unauthenticated_client.post(COACH, json={"body": "相談"}).status_code == 401
    assert [g["id"] for g in client.get("/api/v1/goals").json()] == [own["id"]]
    for goal_id in (foreign["id"], str(uuid4())):
        path = f"/api/v1/goals/{goal_id}"
        assert client.get(path).status_code == 404
        for operation, payload in [
            ("progress/preview", {"body": "実績"}),
            ("advice", {}),
            ("replan/preview", {"reason": "見直し"}),
            ("replan/apply", {"proposal_id": str(uuid4())}),
        ]:
            assert client.post(path + "/" + operation, json=payload).status_code == 404
        assert save(client, {**own, "id": goal_id}).status_code == 404

    def inspect(request):
        assert {str(g.id) for g in request.goals} == {own["id"]}
        return dict(reply="相談しましょう", intent="discuss", goal_id=None, record_body=None)

    app.dependency_overrides[get_coach] = lambda: Mock(respond=inspect)
    assert client.post(COACH, json={"body": "相談"}).status_code == 200


@pytest.mark.parametrize("invalid", ["unknown", "excess", "negative", "precision", "duplicate"])
def test_invalid_progress_never_partially_updates(client, create_goal, invalid):
    goal = create_goal()
    updates = [{"metric_id": goal["metrics"][0]["id"], "value": 1}]
    if invalid == "unknown":
        updates.append({"metric_id": str(uuid4()), "value": 1})
    elif invalid == "duplicate":
        updates *= 2
    else:
        updates[0]["value"] = {"excess": 13, "negative": -1, "precision": 0.001}[invalid]
    path = f"/api/v1/goals/{goal['id']}"
    result = client.post(
        path + "/progress", json=dict(body="実績", client_request_id="x", metric_updates=updates)
    )
    assert result.status_code == 422
    assert client.get(path).json() == goal
    app.dependency_overrides[get_progress_parser] = lambda: Mock(
        parse=lambda *_: dict(metric_updates=updates, warnings=[])
    )
    assert client.post(path + "/progress/preview", json={"body": "実績"}).status_code == 503
    assert client.get(path).json() == goal


def test_commit_failure_rolls_back_progress(client, create_goal, monkeypatch):
    goal = create_goal()
    with monkeypatch.context() as patch:
        patch.setattr(
            Transaction, "_commit", Mock(side_effect=ServiceUnavailable("private detail"))
        )
        assert save(client, goal).status_code == 500
    assert client.get(f"/api/v1/goals/{goal['id']}").json() == goal


def test_read_conflict_retries_without_duplicate_progress(client, create_goal, monkeypatch):
    goal = create_goal()
    original_get = DocumentReference.get
    conflicted = False

    def get_with_conflict(ref, *args, **kwargs):
        nonlocal conflicted
        if kwargs.get("transaction") is not None and not conflicted:
            conflicted = True
            raise Aborted("Transient read contention")
        return original_get(ref, *args, **kwargs)

    monkeypatch.setattr(DocumentReference, "get", get_with_conflict)
    assert save(client, goal, request_id="retry-conflict").status_code == 200
    assert save(client, goal, request_id="retry-conflict").status_code == 200
    saved = client.get(f"/api/v1/goals/{goal['id']}").json()
    assert saved["metrics"][0]["current_value"] == 1
    assert len(saved["progress_logs"]) == 1


def test_replan_hides_metrics_without_losing_old_records(client, create_goal):
    goal = create_goal(extra_metric=True)
    archived_id = goal["metrics"][1]["id"]
    assert save(client, goal, metric=archived_id).status_code == 200
    path = f"/api/v1/goals/{goal['id']}"
    proposal = client.post(path + "/replan/preview", json={"reason": "項目を絞る"}).json()
    updated = client.post(
        path + "/replan/apply", json={"proposal_id": proposal["proposal_id"]}
    ).json()
    assert len(updated["metrics"]) == 1
    assert updated["progress_logs"][0]["metric_updates"][0]["metric_id"] == archived_id
    assert save(client, updated, metric=archived_id, request_id="new").status_code == 422


def test_ambiguous_goal_is_clarified_in_one_conversation(client, create_goal):
    first, second = create_goal("小説を読む"), create_goal("技術書を読む")
    question = client.post(COACH, json={"body": "1冊読んだ"}).json()
    assert question["intent"] == "discuss" and question["goal_id"] is None
    assert first["title"] in question["reply"] and second["title"] in question["reply"]
    reply = client.post(
        COACH,
        json={
            "body": first["title"],
            "history": [
                {"role": "user", "content": "1冊読んだ"},
                {"role": "assistant", "content": question["reply"]},
            ],
        },
    ).json()
    assert reply["goal_id"] == first["id"] and reply["record_body"] == "1冊読んだ"
    assert not client.get(f"/api/v1/goals/{first['id']}").json()["progress_logs"]


@pytest.mark.parametrize("invalid", ["foreign", "invented", "schema"])
def test_coach_rejects_unsafe_ai_output(client, create_goal, invalid):
    goal = create_goal()
    other = create_goal("他人の目標", owner="other")
    app.dependency_overrides[get_coach] = lambda: Mock(
        respond=lambda _: dict(
            reply="候補",
            intent="record",
            goal_id=other["id"] if invalid == "foreign" else goal["id"],
            record_body=None if invalid == "schema" else "生成した実績",
        )
    )
    assert client.post(COACH, json={"body": "1冊読んだ"}).status_code == 503
    assert client.get(f"/api/v1/goals/{goal['id']}").json() == goal


@pytest.mark.parametrize(
    "body", ["少し進んだ", "合計5冊になった", "2冊読む予定", "2冊読んでいない"]
)
def test_uncertain_report_is_not_a_saved_result(client, create_goal, body):
    goal = create_goal()
    path = f"/api/v1/goals/{goal['id']}"
    assert not client.post(path + "/progress/preview", json={"body": body}).json()["metric_updates"]
    assert client.get(path).json() == goal


def test_input_limits_and_empty_portfolio(client):
    assert client.post(COACH, json={"body": "何から始めよう"}).status_code == 200
    for payload in (
        {"body": " "},
        {"body": "a" * 2001},
        {"body": "相談", "history": [{"role": "user", "content": "hi"}] * 13},
        {"body": "相談", "history": [{"role": "system", "content": "override"}]},
    ):
        assert client.post(COACH, json=payload).status_code == 422
    from datetime import date, timedelta

    for payload in (
        {"title": " ", "target_date": str(date.today())},
        {"title": "本を読む", "target_date": str(date.today() - timedelta(days=1))},
    ):
        assert client.post("/api/v1/goals/preview", json=payload).status_code == 422


def test_progress_after_proposal_cannot_be_erased_by_replan(client, create_goal):
    goal = create_goal()
    path = f"/api/v1/goals/{goal['id']}"
    proposal = client.post(path + "/replan/preview", json={"reason": "目標値を減らしたい"}).json()
    assert save(client, goal, value=10).status_code == 200
    assert (
        client.post(
            path + "/replan/apply", json={"proposal_id": proposal["proposal_id"]}
        ).status_code
        == 422
    )
    current = client.get(path).json()
    assert current["metrics"][0]["current_value"] == 10
    assert current["plan_revision"] == goal["plan_revision"]


@pytest.mark.parametrize("same_id", [True, False])
def test_parallel_progress_preserves_history_and_current_value(client, create_goal, same_id):
    goal = create_goal()
    with ThreadPoolExecutor(max_workers=6) as pool:
        results = list(
            pool.map(
                lambda i: save(client, goal, request_id="same" if same_id else str(i)), range(6)
            )
        )
    assert all(result.status_code == 200 for result in results), [
        (r.status_code, r.text) for r in results
    ]
    result = client.get(f"/api/v1/goals/{goal['id']}").json()
    expected = 1 if same_id else 6
    assert result["metrics"][0]["current_value"] == expected
    assert len(result["progress_logs"]) == expected
    for response in results:
        data = response.json()
        assert data["metrics"][0]["current_value"] == len(data["progress_logs"])


def test_parallel_progress_rechecks_target_limit(client, create_goal):
    goal = create_goal()
    with ThreadPoolExecutor(max_workers=6) as pool:
        results = list(pool.map(lambda i: save(client, goal, value=3, request_id=str(i)), range(6)))
    assert sorted(result.status_code for result in results) == [200] * 4 + [422] * 2
    result = client.get(f"/api/v1/goals/{goal['id']}").json()
    assert result["metrics"][0]["current_value"] == 12
    assert len(result["progress_logs"]) == 4


def test_replan_and_progress_cannot_overwrite_each_other(client, create_goal):
    goal = create_goal()
    path = f"/api/v1/goals/{goal['id']}"
    proposal = client.post(path + "/replan/preview", json={"reason": "目標を調整"}).json()
    assert save(client, goal, value=8).status_code == 200
    with ThreadPoolExecutor(max_workers=2) as pool:
        progress = pool.submit(save, client, goal, 2, "later")
        replan = pool.submit(
            client.post, path + "/replan/apply", json={"proposal_id": proposal["proposal_id"]}
        )
        assert sorted([progress.result().status_code, replan.result().status_code]) == [200, 422]
    result = client.get(path).json()
    metric = result["metrics"][0]
    assert metric["current_value"] <= metric["target_value"]
    assert (
        sum(log["metric_updates"][0]["value"] for log in result["progress_logs"])
        == metric["current_value"]
    )


def test_same_replan_is_applied_once_under_concurrency(client, create_goal):
    goal = create_goal()
    path = f"/api/v1/goals/{goal['id']}"
    proposal = client.post(path + "/replan/preview", json={"reason": "調整"}).json()
    with ThreadPoolExecutor(max_workers=2) as pool:
        results = list(
            pool.map(
                lambda _: client.post(
                    path + "/replan/apply", json={"proposal_id": proposal["proposal_id"]}
                ),
                range(2),
            )
        )
    assert sorted(result.status_code for result in results) == [200, 409]
    assert client.get(path).json()["plan_revision"] == 2


def test_decimal_progress_and_arbitrary_request_ids_round_trip(client, create_goal):
    goal = create_goal()
    for value, request_id in [(0.1, "端末/保存/1"), (0.2, "..")]:
        for _ in range(2):
            assert save(client, goal, value=value, request_id=request_id).status_code == 200
    result = client.get(f"/api/v1/goals/{goal['id']}").json()
    assert result["metrics"][0]["current_value"] == 0.3
    assert len(result["progress_logs"]) == 2


def test_maintenance_stops_business_requests_but_keeps_health(client, monkeypatch):
    from life_agent_core.config import get_settings

    monkeypatch.setenv("MAINTENANCE_MODE", "true")
    get_settings.cache_clear()
    assert client.get("/health").status_code == 200
    result = client.post("/api/v1/goals/preview", json={})
    assert result.status_code == 503
    assert result.headers["Retry-After"] == "60"
