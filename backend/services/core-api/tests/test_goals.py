import uuid

from fastapi.testclient import TestClient


def test_create_and_get_goal(client: TestClient) -> None:
    created = client.post(
        "/api/v1/goals",
        json={"title": "  毎月4冊読む  ", "description": "  読書習慣を作る  "},
    )

    assert created.status_code == 201
    body = created.json()
    assert body["title"] == "毎月4冊読む"
    assert body["description"] == "読書習慣を作る"
    assert body["status"] == "active"

    fetched = client.get(f"/api/v1/goals/{body['id']}")
    assert fetched.status_code == 200
    assert fetched.json() == body


def test_blank_title_is_rejected(client: TestClient) -> None:
    response = client.post("/api/v1/goals", json={"title": "   "})

    assert response.status_code == 422


def test_non_string_title_is_rejected(client: TestClient) -> None:
    response = client.post("/api/v1/goals", json={"title": 123})

    assert response.status_code == 422


def test_long_fields_are_rejected(client: TestClient) -> None:
    assert client.post("/api/v1/goals", json={"title": "x" * 121}).status_code == 422
    assert (
        client.post("/api/v1/goals", json={"title": "Goal", "description": "x" * 2001}).status_code
        == 422
    )


def test_blank_description_becomes_null(client: TestClient) -> None:
    response = client.post("/api/v1/goals", json={"title": "Goal", "description": "  "})

    assert response.status_code == 201
    assert response.json()["description"] is None


def test_missing_goal_returns_404(client: TestClient) -> None:
    response = client.get(f"/api/v1/goals/{uuid.uuid4()}")

    assert response.status_code == 404


def test_invalid_goal_id_returns_422(client: TestClient) -> None:
    response = client.get("/api/v1/goals/not-a-uuid")

    assert response.status_code == 422
