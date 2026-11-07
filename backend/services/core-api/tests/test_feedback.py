import json
import threading
from concurrent.futures import ThreadPoolExecutor
from uuid import uuid4

import httpx
import pytest
from google.api_core.exceptions import ServiceUnavailable
from google.cloud.firestore_v1.document import DocumentReference

from life_agent_core.config import get_settings


@pytest.fixture
def github(client, monkeypatch):
    monkeypatch.setenv("FEEDBACK_GITHUB_TOKEN", "test-secret")
    get_settings.cache_clear()
    state = {"posts": [], "issues": [], "status": 201, "lose": False, "get_fails": False}

    def request(request):
        assert request.headers["Authorization"] == "Bearer test-secret"
        assert request.url.path == "/repos/Yoshiki-Takahashi/life_agent/issues"
        if request.method == "POST":
            data = json.loads(request.content)
            state["posts"].append(data)
            if state["status"] != 201:
                return httpx.Response(state["status"], json={"message": "private upstream error"})
            issue = {"number": len(state["issues"]) + 40, "body": data["body"]}
            state["issues"].append(issue)
            if state.get("entered"):
                state["entered"].set()
                assert state["release"].wait(10)
            if state["lose"]:
                raise httpx.ReadTimeout("private upstream error")
            return httpx.Response(201, json=issue)
        assert request.url.params["state"] == "all"
        if state["get_fails"]:
            return httpx.Response(503)
        return httpx.Response(200, json=state["issues"])

    real_client = httpx.Client
    monkeypatch.setattr(
        httpx,
        "Client",
        lambda **kwargs: real_client(**kwargs, transport=httpx.MockTransport(request)),
    )
    return state


def payload(**changes):
    return (
        dict(
            client_request_id=str(uuid4()),
            body="計画確認でも期限を変更したい",
            app_version="1.0 (1, debug)",
            screen="preview",
        )
        | changes
    )


def test_feedback_saves_issue_and_delivery_receipt_only_and_reuses_result(
    client, github, db_client
):
    data = payload()
    first = client.post("/api/v1/feedback", json=data)
    assert first.status_code == 200
    assert first.json() == {
        "issue_number": 40,
        "issue_url": "https://github.com/Yoshiki-Takahashi/life_agent/issues/40",
    }
    assert client.post("/api/v1/feedback", json=data).json() == first.json()
    assert len(github["posts"]) == 1
    issue = github["posts"][0]
    assert data["body"] in issue["body"]
    assert data["app_version"] in issue["body"] and "画面: preview" in issue["body"]
    assert "送信日時:" in issue["body"]
    assert issue["labels"] == ["feedback", "needs-triage"]
    receipt = list(db_client.collection("feedback_receipts").stream())[0].to_dict()
    assert receipt["status"] == "sent"
    assert data["body"] not in json.dumps(receipt, default=str, ensure_ascii=False)
    assert "body" not in receipt and "app_version" not in receipt


def test_lost_create_response_is_reconciled_even_when_issue_is_closed(client, github):
    data = payload()
    github["lose"] = True
    first = client.post("/api/v1/feedback", json=data)
    assert first.status_code == 503 and first.json()["detail"]["result_unknown"]
    assert "private upstream error" not in first.text
    github["issues"][0]["state"] = "closed"
    assert client.post("/api/v1/feedback", json=data).status_code == 200
    assert len(github["posts"]) == 1


def test_failure_to_save_receipt_after_create_does_not_duplicate_issue(client, github, monkeypatch):
    original = DocumentReference.update

    def fail_receipt(self, field_updates, *args, **kwargs):
        if field_updates.get("status") == "sent":
            raise ServiceUnavailable("receipt unavailable")
        return original(self, field_updates, *args, **kwargs)

    data = payload()
    with monkeypatch.context() as patch:
        patch.setattr(DocumentReference, "update", fail_receipt)
        assert client.post("/api/v1/feedback", json=data).status_code == 500
    assert client.post("/api/v1/feedback", json=data).status_code == 200
    assert len(github["posts"]) == 1


def test_uncertain_creation_never_reposts_while_issue_is_not_visible(client, github):
    data = payload()
    github["status"] = 500
    assert client.post("/api/v1/feedback", json=data).status_code == 503
    github["status"] = 201
    for fail_get in (False, True):
        github["get_fails"] = fail_get
        result = client.post("/api/v1/feedback", json=data)
        assert result.status_code == 503 and result.json()["detail"]["result_unknown"]
    assert len(github["posts"]) == 1


@pytest.mark.parametrize("status", [401, 403, 404, 422, 429])
def test_explicit_rejection_can_be_retried(client, github, status):
    data = payload()
    github["status"] = status
    result = client.post("/api/v1/feedback", json=data)
    assert result.status_code == 503 and not result.json()["detail"]["result_unknown"]
    github["status"] = 201
    assert client.post("/api/v1/feedback", json=data).status_code == 200
    assert len(github["issues"]) == 1


def test_concurrent_submissions_reserve_only_one_create(client, github):
    github["entered"] = threading.Event()
    github["release"] = threading.Event()
    data = payload()
    with ThreadPoolExecutor(max_workers=2) as pool:
        first = pool.submit(client.post, "/api/v1/feedback", json=data)
        assert github["entered"].wait(10)
        try:
            second = client.post("/api/v1/feedback", json=data)
            assert second.status_code == 200
        finally:
            github["release"].set()
        assert first.result().status_code == 200
    assert len(github["posts"]) == 1


def test_request_id_is_scoped_to_owner_and_cannot_change_content(client, github):
    data = payload()
    assert client.post("/api/v1/feedback", json=data).status_code == 200
    assert client.post("/api/v1/feedback", json=data | {"body": "変更"}).status_code == 409
    assert (
        client.post(
            "/api/v1/feedback", json=data, headers={"Authorization": "Bearer user-b"}
        ).status_code
        == 200
    )
    assert len(github["posts"]) == 2


@pytest.mark.parametrize(
    "changes",
    [
        {"body": " "},
        {"body": "a" * 2001},
        {"screen": "untrusted"},
        {"client_request_id": "bad"},
        {"app_version": "x\nforged"},
        {"repository": "other/repo"},
    ],
)
def test_invalid_feedback_never_reaches_github(client, github, changes):
    assert client.post("/api/v1/feedback", json=payload(**changes)).status_code == 422
    assert github["posts"] == []


def test_authentication_and_allowlist_checked_before_github(client, github, monkeypatch, db_client):
    monkeypatch.setenv("ALLOWED_USER_ID", "user-a")
    get_settings.cache_clear()
    for token, status in (("", 401), ("invalid", 401), ("user-b", 403)):
        response = client.post(
            "/api/v1/feedback", json=payload(), headers={"Authorization": f"Bearer {token}"}
        )
        assert response.status_code == status
    assert github["posts"] == []
    assert list(db_client.collection("feedback_receipts").stream()) == []


def test_missing_token_is_retryable_without_reserving_delivery(client, monkeypatch, db_client):
    monkeypatch.setenv("FEEDBACK_GITHUB_TOKEN", "")
    get_settings.cache_clear()
    result = client.post("/api/v1/feedback", json=payload())
    assert result.status_code == 503 and not result.json()["detail"]["result_unknown"]
    assert list(db_client.collection("feedback_receipts").stream()) == []
