"""The private deployment check must not run from a laptop or an emulator test."""

import asyncio
import importlib.util
from pathlib import Path

import pytest

spec = importlib.util.spec_from_file_location(
    "check_deployment", Path(__file__).parents[2] / "scripts/check-deployment.py"
)
check_deployment = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check_deployment)


@pytest.mark.parametrize("cloud_job,emulator", [(None, None), ("job", "127.0.0.1:8080")])
def test_cloud_check_rejects_local_execution(monkeypatch, cloud_job, emulator):
    for name, value in (("CLOUD_RUN_JOB", cloud_job), ("FIRESTORE_EMULATOR_HOST", emulator)):
        if value is None:
            monkeypatch.delenv(name, raising=False)
        else:
            monkeypatch.setenv(name, value)
    with pytest.raises(RuntimeError, match="private Cloud Run Job"):
        asyncio.run(check_deployment.check())


@pytest.mark.parametrize("foreign_owner", [False, True])
def test_failed_probe_cleans_only_its_unique_test_owner(monkeypatch, foreign_owner):
    from types import SimpleNamespace
    from unittest.mock import MagicMock

    import httpx

    from life_agent_core.config import get_settings
    from life_agent_core.main import app

    monkeypatch.setenv("CLOUD_RUN_JOB", "verification")
    monkeypatch.delenv("FIRESTORE_EMULATOR_HOST", raising=False)
    monkeypatch.setenv("AUTH_BACKEND", "firebase")
    monkeypatch.setenv("GOAL_PLANNER_BACKEND", "http")
    monkeypatch.setenv("CORE_CANDIDATE_URL", "https://candidate.run.app")
    monkeypatch.setattr(check_deployment, "uuid4", lambda: SimpleNamespace(hex="unique"))
    db = MagicMock()
    snapshot = MagicMock()
    snapshot.to_dict.return_value = {
        "owner_id": "real-user" if foreign_owner else "deploy-check-unique"
    }
    db.collection.return_value.where.return_value.stream.return_value = [snapshot]
    monkeypatch.setattr("life_agent_core.database.get_db", lambda: db)
    client = httpx.AsyncClient
    monkeypatch.setattr(
        httpx,
        "AsyncClient",
        lambda **kwargs: client(
            **kwargs, transport=httpx.MockTransport(lambda _: httpx.Response(503))
        ),
    )
    try:
        expected = RuntimeError if foreign_owner else AssertionError
        with pytest.raises(expected):
            asyncio.run(check_deployment.check())
        if foreign_owner:
            db.recursive_delete.assert_not_called()
        else:
            db.recursive_delete.assert_called_once_with(snapshot.reference)
        db.close.assert_called_once()
        assert not app.dependency_overrides
    finally:
        get_settings.cache_clear()
