import os
import signal
import socket
import subprocess
import time
from uuid import uuid4

import httpx
import pytest
from fastapi.testclient import TestClient
from google.cloud import firestore

from life_agent_core.auth import get_token_verifier
from life_agent_core.config import get_settings
from life_agent_core.database import get_db
from life_agent_core.main import app


class FakeTokenVerifier:
    def verify(self, token: str) -> str:
        if token == "invalid":
            raise ValueError("invalid token")
        return token


@pytest.fixture(scope="session")
def emulator(tmp_path_factory):
    host = os.getenv("FIRESTORE_EMULATOR_HOST")
    if host:
        if not host.startswith(("127.0.0.1:", "localhost:")):
            pytest.fail("Tests only connect to a loopback Firestore emulator")
        yield host
        return
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        port = sock.getsockname()[1]
    host = f"127.0.0.1:{port}"
    log_path = tmp_path_factory.mktemp("firestore") / "emulator.log"
    with log_path.open("w+") as log:
        process = subprocess.Popen(
            [
                "gcloud",
                "emulators",
                "firestore",
                "start",
                f"--host-port={host}",
                "--project=demo-lifeagent-tests",
                "--quiet",
            ],
            stdout=log,
            stderr=subprocess.STDOUT,
            start_new_session=True,
        )
        try:
            for _ in range(120):
                if process.poll() is not None:
                    log.seek(0)
                    pytest.fail(f"Firestore emulator failed: {log.read()}")
                try:
                    httpx.get(f"http://{host}/", timeout=0.2)
                    break
                except httpx.HTTPError:
                    time.sleep(0.25)
            else:
                pytest.fail(f"Firestore emulator did not start; see {log_path}")
            yield host
        finally:
            if process.poll() is None:
                os.killpg(process.pid, signal.SIGTERM)
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait()


@pytest.fixture
def db_client(emulator, monkeypatch):
    monkeypatch.setenv("FIRESTORE_EMULATOR_HOST", emulator)
    for key in (
        "GOAL_PLANNER_BACKEND",
        "PROGRESS_PARSER_BACKEND",
        "ADVISOR_BACKEND",
        "REPLANNER_BACKEND",
        "COACH_BACKEND",
    ):
        monkeypatch.setenv(key, "fake")
    monkeypatch.setenv("MAINTENANCE_MODE", "false")
    get_settings.cache_clear()
    db = firestore.Client(project=f"demo-test-{uuid4().hex[:16]}")
    yield db
    db.close()
    app.dependency_overrides.clear()
    get_settings.cache_clear()


@pytest.fixture
def client(db_client):
    app.dependency_overrides[get_db] = lambda: db_client
    app.dependency_overrides[get_token_verifier] = FakeTokenVerifier
    with TestClient(app, headers={"Authorization": "Bearer user-a"}) as test_client:
        yield test_client


@pytest.fixture
def unauthenticated_client(client):
    with TestClient(app) as test_client:
        yield test_client


@pytest.fixture
def create_goal(client):
    from datetime import date, timedelta

    def create(title="本を読む", owner="user-a", extra_metric=False):
        plan = client.post(
            "/api/v1/goals/preview",
            json={"title": title, "target_date": str(date.today() + timedelta(days=90))},
        ).json()
        if extra_metric:
            plan["metrics"].append({"name": "補助の成果", "target_value": 3, "unit": "件"})
        result = client.post(
            "/api/v1/goals/confirm", json=plan, headers={"Authorization": f"Bearer {owner}"}
        )
        assert result.status_code == 201
        return result.json()

    return create
