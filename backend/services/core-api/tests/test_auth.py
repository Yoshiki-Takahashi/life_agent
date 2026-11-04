"""The private development API admits only its configured Firebase account."""

from datetime import date, timedelta
from unittest.mock import MagicMock, Mock

import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from life_agent_core.auth import FirebaseTokenVerifier, get_token_verifier
from life_agent_core.config import Settings, get_settings
from life_agent_core.database import get_db
from life_agent_core.goals import get_goal_planner
from life_agent_core.main import app


@pytest.fixture
def restricted_api(monkeypatch):
    monkeypatch.setenv("ALLOWED_USER_ID", "allowed-uid")
    get_settings.cache_clear()
    db, planner = MagicMock(), Mock()
    verifier = FirebaseTokenVerifier("demo-private-api")

    def verified_claims(token):
        if token == "allowed-token":
            return {"uid": "allowed-uid"}
        if token == "other-token":
            return {"uid": "other-uid"}
        if token == "same-email-token":
            return {"uid": "other-uid", "email": "owner@example.com"}
        raise ValueError("Invalid signature")

    monkeypatch.setattr("life_agent_core.auth.auth.verify_id_token", verified_claims)
    app.dependency_overrides[get_token_verifier] = lambda: verifier
    app.dependency_overrides[get_db] = lambda: db
    app.dependency_overrides[get_goal_planner] = lambda: planner
    try:
        with TestClient(app) as client:
            yield client, db, planner
    finally:
        app.dependency_overrides.clear()
        get_settings.cache_clear()


def test_allowed_firebase_uid_can_read_its_goals(restricted_api):
    client, db, _ = restricted_api
    query = db.collection.return_value.where.return_value
    query.order_by.return_value.order_by.return_value.stream.return_value = []
    response = client.get("/api/v1/goals", headers={"Authorization": "Bearer allowed-token"})
    assert response.status_code == 200 and response.json() == []
    db.collection.assert_called_once_with("goals")


@pytest.mark.parametrize("token", ["other-token", "same-email-token"])
def test_other_uids_cannot_use_any_business_api(restricted_api, token):
    client, db, planner = restricted_api
    goal = "/api/v1/goals/00000000-0000-0000-0000-000000000001"
    operations = [
        ("GET", "/api/v1/goals"),
        ("GET", goal),
        ("POST", "/api/v1/goals/preview"),
        ("POST", "/api/v1/goals/confirm"),
        ("POST", goal + "/progress"),
        ("POST", goal + "/progress/preview"),
        ("POST", goal + "/advice"),
        ("POST", goal + "/replan/preview"),
        ("POST", goal + "/replan/apply"),
        ("POST", "/api/v1/coach/messages"),
    ]
    for method, path in operations:
        response = client.request(
            method, path, headers={"Authorization": f"Bearer {token}"}, json={}
        )
        assert response.status_code == 403, path
    db.collection.assert_not_called()
    planner.generate.assert_not_called()


def test_unsigned_claims_and_missing_tokens_stay_unauthenticated(restricted_api):
    client, db, planner = restricted_api
    payload = {"title": "読書", "target_date": str(date.today() + timedelta(days=30))}
    for headers in ({}, {"Authorization": "Bearer unsigned-allowed-uid"}):
        assert (
            client.post("/api/v1/goals/preview", json=payload, headers=headers).status_code == 401
        )
    assert client.get("/health").status_code == 200
    db.collection.assert_not_called()
    planner.generate.assert_not_called()


@pytest.mark.parametrize(
    "auth_backend,uid", [("firebase", None), ("firebase", ""), ("fake", "uid")]
)
def test_cloud_run_cannot_start_without_a_private_firebase_policy(monkeypatch, auth_backend, uid):
    monkeypatch.setenv("K_SERVICE", "core-api")
    monkeypatch.setenv("AUTH_BACKEND", auth_backend)
    monkeypatch.delenv("ALLOWED_USER_ID", raising=False)
    if uid is not None:
        monkeypatch.setenv("ALLOWED_USER_ID", uid)
    get_settings.cache_clear()
    try:
        with pytest.raises(ValidationError), TestClient(app):
            pass
    finally:
        get_settings.cache_clear()


def test_cloud_run_accepts_an_explicit_firebase_uid():
    assert Settings(k_service="core-api", allowed_user_id="owner").allowed_user_id == "owner"
