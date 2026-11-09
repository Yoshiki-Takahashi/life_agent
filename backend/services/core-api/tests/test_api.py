"""User-visible contracts: confirmation, isolation, atomic writes, and conflict handling."""

from concurrent.futures import ThreadPoolExecutor
from unittest.mock import Mock
from uuid import uuid4

import pytest
from google.api_core.exceptions import (
    Aborted,
    DeadlineExceeded,
    InternalServerError,
    ServiceUnavailable,
)
from google.cloud.firestore_v1.document import DocumentReference
from google.cloud.firestore_v1.query import Query
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


@pytest.mark.parametrize(
    "invalid", ["unknown", "excess", "negative", "precision", "duplicate", "empty", "nonfinite"]
)
def test_invalid_progress_never_partially_updates(client, create_goal, invalid):
    goal = create_goal()
    updates = [{"metric_id": goal["metrics"][0]["id"], "value": 1}]
    if invalid == "unknown":
        updates.append({"metric_id": str(uuid4()), "value": 1})
    elif invalid == "duplicate":
        updates *= 2
    elif invalid == "empty":
        updates = []
    else:
        updates[0]["value"] = {
            "excess": 1_000_000_001,
            "negative": -1,
            "precision": 0.001,
            "nonfinite": "NaN",
        }[invalid]
    path = f"/api/v1/goals/{goal['id']}"
    result = client.post(
        path + "/progress", json=dict(body="実績", client_request_id="x", metric_updates=updates)
    )
    assert result.status_code == 422
    assert client.get(path).json() == goal
    app.dependency_overrides[get_progress_parser] = lambda: Mock(
        parse=lambda *_: dict(metric_updates=updates, warnings=[])
    )
    assert client.post(path + "/progress/preview", json={"body": "実績"}).status_code == (
        200 if invalid == "empty" else 503
    )
    assert client.get(path).json() == goal


def test_zero_and_over_target_progress_round_trip_once(client, create_goal):
    goal = create_goal(extra_metric=True)
    path = f"/api/v1/goals/{goal['id']}"
    for request_id, values in [("mixed", [13.25, 0]), ("all-zero", [0, 0]), ("beyond", [1, 4])]:
        updates = [
            dict(metric_id=m["id"], value=v) for m, v in zip(goal["metrics"], values, strict=True)
        ]
        for _ in range(2):
            response = client.post(
                path + "/progress",
                json=dict(
                    body="確認した実績", client_request_id=request_id, metric_updates=updates
                ),
            )
            assert response.status_code == 200
    result = client.get(path).json()
    assert [m["current_value"] for m in result["metrics"]] == [14.25, 4]
    assert [[u["value"] for u in log["metric_updates"]] for log in result["progress_logs"]] == [
        [1, 4],
        [0, 0],
        [13.25, 0],
    ]


@pytest.mark.parametrize("value", [0, 13])
def test_preview_accepts_zero_and_over_target_without_saving(client, create_goal, value):
    goal = create_goal()
    path = f"/api/v1/goals/{goal['id']}"
    result = client.post(path + "/progress/preview", json={"body": f"{value}冊読み終えた"})
    assert result.status_code == 200
    assert [(u["metric_id"], u["value"]) for u in result.json()["metric_updates"]] == [
        (goal["metrics"][0]["id"], value)
    ]
    assert client.get(path).json() == goal


def test_commit_failure_rolls_back_progress(client, create_goal, monkeypatch):
    goal = create_goal()
    with monkeypatch.context() as patch:
        patch.setattr(
            Transaction, "_commit", Mock(side_effect=ServiceUnavailable("private detail"))
        )
        assert save(client, goal).status_code == 500
    assert client.get(f"/api/v1/goals/{goal['id']}").json() == goal


@pytest.mark.parametrize(
    "failure", [Aborted, DeadlineExceeded, InternalServerError, ServiceUnavailable]
)
def test_read_failure_retries_without_duplicate_progress(client, create_goal, monkeypatch, failure):
    goal = create_goal()
    original_get = DocumentReference.get
    conflicted = False
    transaction_reads = []

    def get_with_conflict(ref, *args, **kwargs):
        nonlocal conflicted
        if kwargs.get("transaction") is not None:
            transaction_reads.append(kwargs)
        if (
            kwargs.get("transaction") is not None
            and ref.parent.id == "progress_logs"
            and not conflicted
        ):
            conflicted = True
            raise failure("Transient read contention")
        return original_get(ref, *args, **kwargs)

    monkeypatch.setattr(DocumentReference, "get", get_with_conflict)
    assert save(client, goal, request_id="retry-conflict").status_code == 200
    assert conflicted
    assert all(read["retry"] is None and 0 < read["timeout"] <= 10 for read in transaction_reads)
    assert save(client, goal, request_id="retry-conflict").status_code == 200
    saved = client.get(f"/api/v1/goals/{goal['id']}").json()
    assert saved["metrics"][0]["current_value"] == 1
    assert len(saved["progress_logs"]) == 1


def test_detail_stays_consistent_when_progress_arrives_between_reads(
    client, create_goal, monkeypatch
):
    goal = create_goal()
    original_stream = Query.stream
    injected = False

    def stream_after_progress(query, *args, **kwargs):
        nonlocal injected
        if kwargs.get("read_time") is not None and not injected:
            injected = True
            assert save(client, goal).status_code == 200
        return original_stream(query, *args, **kwargs)

    monkeypatch.setattr(Query, "stream", stream_after_progress)
    path = f"/api/v1/goals/{goal['id']}"
    assert client.get(path).json() == goal
    current = client.get(path).json()
    assert current["metrics"][0]["current_value"] == 1
    assert len(current["progress_logs"]) == 1


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


def test_parallel_progress_can_exceed_target_without_losing_history(client, create_goal):
    goal = create_goal()
    with ThreadPoolExecutor(max_workers=6) as pool:
        results = list(pool.map(lambda i: save(client, goal, value=3, request_id=str(i)), range(6)))
    assert all(result.status_code == 200 for result in results), [
        (r.status_code, r.text) for r in results
    ]
    result = client.get(f"/api/v1/goals/{goal['id']}").json()
    assert result["metrics"][0]["current_value"] == 18
    assert len(result["progress_logs"]) == 6


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
        assert progress.result().status_code == 200
        assert replan.result().status_code in (200, 422)
    result = client.get(path).json()
    metric = result["metrics"][0]
    assert metric["current_value"] == 10
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


def test_questions_refine_the_plan_without_saving(client, unauthenticated_client):
    from datetime import date, timedelta

    payload = dict(title="読書", target_date=str(date.today() + timedelta(days=90)))
    assert unauthenticated_client.post("/api/v1/goals/questions", json=payload).status_code == 401
    result = client.post("/api/v1/goals/questions", json=payload)
    assert result.status_code == 200
    questions = result.json()["questions"]
    plans = []
    for answer in (False, True):
        response = client.post(
            "/api/v1/goals/preview",
            json={**payload, "answers": [dict(question=q, answer=answer) for q in questions]},
        )
        assert response.status_code == 200
        plans.append(response.json())
    assert len(plans[0]["metrics"]) < len(plans[1]["metrics"])
    assert len(plans[0]["milestones"]) < len(plans[1]["milestones"])
    assert client.get("/api/v1/goals").json() == []
    for answers in (
        [dict(question=questions[0], answer=None)],
        [dict(question=questions[0], answer="No")],
        [dict(question=questions[0], answer=False)] * 2,
    ):
        assert (
            client.post("/api/v1/goals/preview", json={**payload, "answers": answers}).status_code
            == 422
        )


@pytest.mark.parametrize("count", [0, 1, 10, 11])
def test_edited_plan_counts_and_all_metrics_remain_recordable(client, count):
    from datetime import date, timedelta

    date_string = str(date.today() + timedelta(days=90))
    plan = dict(
        title="計画を調整する",
        target_date=date_string,
        metrics=[dict(name=f"測定項目{i}", target_value=12, unit="件") for i in range(count)],
        milestones=[dict(title=f"到達点{i}", target_date=date_string) for i in range(count)],
    )
    response = client.post("/api/v1/goals/confirm", json=plan)
    if count not in (1, 10):
        assert response.status_code == 422
        assert client.get("/api/v1/goals").json() == []
        return
    assert response.status_code == 201
    goal = response.json()
    path = f"/api/v1/goals/{goal['id']}"
    assert client.get(path).json() == goal
    assert [item["name"] for item in goal["metrics"]] == [item["name"] for item in plan["metrics"]]
    assert [item["position"] for item in goal["milestones"]] == list(range(count))
    updates = [dict(metric_id=m["id"], value=1) for m in goal["metrics"]]
    for _ in range(2):
        assert (
            client.post(
                path + "/progress",
                json=dict(body="全項目の実績", client_request_id="all", metric_updates=updates),
            ).status_code
            == 200
        )
    saved = client.get(path).json()
    assert all(m["current_value"] == 1 for m in saved["metrics"])
    assert len(saved["progress_logs"]) == 1
    # A ten-item plan must also pass the existing replan boundary unchanged.
    from life_agent_core.replan import get_replanner
    from life_agent_core.schemas import ReplanCandidate

    app.dependency_overrides[get_replanner] = lambda: Mock(
        propose=lambda *_: ReplanCandidate(
            proposed_plan={**plan, "description": "確認済みの計画"},
            diff=[
                dict(
                    change_type="update",
                    target_type="goal",
                    target_label="計画確認",
                    rationale="現状維持",
                )
            ],
        )
    )
    proposal = client.post(path + "/replan/preview", json=dict(reason="確認したい"))
    assert proposal.status_code == 200
    applied = client.post(
        path + "/replan/apply", json=dict(proposal_id=proposal.json()["proposal_id"])
    )
    assert applied.status_code == 200
    assert len(applied.json()["metrics"]) == count
    assert applied.json()["progress_logs"] == saved["progress_logs"]


def test_invalid_generated_questions_are_retryable_and_never_saved(client):
    from datetime import date

    from life_agent_core.goals import get_goal_planner

    payload = dict(title="読書", target_date=str(date.today()))
    for output in (dict(questions=[]), dict(questions=["同じ質問"] * 3)):
        app.dependency_overrides[get_goal_planner] = lambda output=output: Mock(
            questions=Mock(return_value=output)
        )
        assert client.post("/api/v1/goals/questions", json=payload).status_code == 503
    app.dependency_overrides[get_goal_planner] = lambda: Mock(
        questions=Mock(side_effect=AIUnavailableError("private detail"))
    )
    response = client.post("/api/v1/goals/questions", json=payload)
    assert response.status_code == 503 and "private detail" not in response.text
    assert client.get("/api/v1/goals").json() == []
