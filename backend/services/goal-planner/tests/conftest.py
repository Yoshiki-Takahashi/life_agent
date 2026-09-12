from collections.abc import Generator

import pytest
from fastapi.testclient import TestClient

from life_agent_goal_planner.main import app, get_planner


@pytest.fixture
def client() -> Generator[TestClient]:
    with TestClient(app) as test_client:
        yield test_client
    app.dependency_overrides.clear()


@pytest.fixture
def override_planner():
    def apply(planner: object) -> None:
        app.dependency_overrides[get_planner] = lambda: planner

    return apply
