import uuid
from collections.abc import Callable, Generator
from datetime import date, timedelta

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import func, select
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session

from life_agent_core.database import get_db
from life_agent_core.main import app
from life_agent_core.models import Goal


def request_payload(title: str = "毎月4冊読む") -> dict[str, object]:
    return {
        "title": title,
        "description": "習慣を作る",
        "target_date": (date.today() + timedelta(days=90)).isoformat(),
    }


def preview(client: TestClient, title: str = "毎月4冊読む") -> dict[str, object]:
    response = client.post("/api/v1/goals/preview", json=request_payload(title))
    assert response.status_code == 200
    return response.json()


@pytest.mark.parametrize(
    ("title", "metric_name"),
    [
        ("本を読む", "読了冊数"),
        ("アプリを開発", "完成した成果物"),
        ("筋トレを続ける", "トレーニング回数"),
        ("部屋を片付ける", "達成率"),
    ],
)
def test_fake_planner_presets_are_deterministic(
    client: TestClient, title: str, metric_name: str
) -> None:
    first = preview(client, title)
    second = preview(client, title)

    assert first == second
    assert first["metrics"][0]["name"] == metric_name
    assert isinstance(first["metrics"][0]["target_value"], (int, float))
    assert len(first["milestones"]) == 3


def test_preview_does_not_save_goal(client: TestClient, db_session: Session) -> None:
    preview(client)

    assert db_session.scalar(select(func.count()).select_from(Goal)) == 0


def test_preview_confirm_and_get_edited_plan(client: TestClient) -> None:
    plan = preview(client)
    plan["metrics"][0] = {"name": "読書数", "target_value": 8, "unit": "冊"}
    plan["milestones"][0]["title"] = "候補を8冊選ぶ"

    created = client.post("/api/v1/goals/confirm", json=plan)

    assert created.status_code == 201
    body = created.json()
    assert body["metrics"][0]["name"] == "読書数"
    assert body["metrics"][0]["position"] == 0
    assert body["milestones"][0]["title"] == "候補を8冊選ぶ"
    fetched = client.get(f"/api/v1/goals/{body['id']}")
    assert fetched.status_code == 200
    assert fetched.json() == body


@pytest.mark.parametrize(
    "change",
    [
        lambda plan: plan.update(metrics=[]),
        lambda plan: plan["metrics"][0].update(name="   "),
        lambda plan: plan["metrics"][0].update(target_value=0),
        lambda plan: plan.update(milestones=plan["milestones"][:2]),
        lambda plan: plan["milestones"][0].update(
            target_date=(date.today() - timedelta(days=1)).isoformat()
        ),
        lambda plan: plan["milestones"][1].update(
            target_date=(date.today() + timedelta(days=15)).isoformat()
        ),
    ],
)
def test_invalid_confirm_is_rejected(
    client: TestClient, change: Callable[[dict[str, object]], None]
) -> None:
    plan = preview(client)
    change(plan)

    assert client.post("/api/v1/goals/confirm", json=plan).status_code == 422


def test_blank_and_long_goal_fields_are_rejected(client: TestClient) -> None:
    payload = request_payload()
    payload["title"] = "   "
    assert client.post("/api/v1/goals/preview", json=payload).status_code == 422

    payload = request_payload()
    payload["description"] = "x" * 2001
    assert client.post("/api/v1/goals/preview", json=payload).status_code == 422


def test_past_goal_target_date_is_rejected(client: TestClient) -> None:
    payload = request_payload()
    payload["target_date"] = (date.today() - timedelta(days=1)).isoformat()

    assert client.post("/api/v1/goals/preview", json=payload).status_code == 422


def test_missing_and_invalid_goal_ids(client: TestClient) -> None:
    assert client.get(f"/api/v1/goals/{uuid.uuid4()}").status_code == 404
    assert client.get("/api/v1/goals/not-a-uuid").status_code == 422


def test_direct_goal_creation_is_removed(client: TestClient) -> None:
    assert client.post("/api/v1/goals", json=request_payload()).status_code == 404


def test_confirm_rolls_back_when_commit_fails(client: TestClient) -> None:
    class FailingSession:
        rolled_back = False

        def add(self, goal: Goal) -> None:
            pass

        def commit(self) -> None:
            raise SQLAlchemyError("database failed")

        def rollback(self) -> None:
            self.rolled_back = True

    session = FailingSession()

    def override_get_db() -> Generator[FailingSession]:
        yield session

    plan = preview(client)
    app.dependency_overrides[get_db] = override_get_db
    try:
        response = client.post("/api/v1/goals/confirm", json=plan)
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 500
    assert session.rolled_back
