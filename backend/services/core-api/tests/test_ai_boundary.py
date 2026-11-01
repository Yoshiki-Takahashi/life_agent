from unittest.mock import Mock

import httpx
import pytest
from google.auth.exceptions import GoogleAuthError

from life_agent_core.ai_service import AIUnavailableError, InternalAIClient
from life_agent_core.config import Settings
from life_agent_core.schemas import Advice


def test_internal_http_uses_iam_timeout_and_validated_output(monkeypatch):
    post = Mock(
        return_value=httpx.Response(
            200,
            json={"summary": "次の一歩", "next_actions": ["5分読む"]},
            request=httpx.Request("POST", "http://ai/internal/v1/advice"),
        )
    )
    monkeypatch.setattr(httpx, "post", post)
    monkeypatch.setattr("life_agent_core.ai_service.fetch_id_token", lambda *args: "internal-token")
    client = InternalAIClient(
        Settings(
            goal_planner_url="http://ai",
            goal_planner_id_token_audience="http://ai",
            goal_planner_timeout_seconds=2,
        )
    )
    assert client.generate("advice", {"goal": "owned"}, Advice).next_actions == ["5分読む"]
    assert post.call_args.args == ("http://ai/internal/v1/advice",)
    assert post.call_args.kwargs["headers"] == {"Authorization": "Bearer internal-token"}
    assert post.call_args.kwargs["timeout"] == 2


@pytest.mark.parametrize("failure", ["network", "status", "schema", "iam"])
def test_internal_failures_do_not_expose_provider_data(monkeypatch, failure):
    response = httpx.Response(
        503 if failure == "status" else 200, json={}, request=httpx.Request("POST", "http://ai")
    )
    post = (
        Mock(side_effect=httpx.ConnectError("private detail"))
        if failure == "network"
        else Mock(return_value=response)
    )
    monkeypatch.setattr(httpx, "post", post)
    monkeypatch.setattr(
        "life_agent_core.ai_service.fetch_id_token",
        Mock(side_effect=GoogleAuthError("private detail")),
    )
    client = InternalAIClient(
        Settings(goal_planner_id_token_audience="iam" if failure == "iam" else None)
    )
    with pytest.raises(AIUnavailableError) as error:
        client.generate("advice", {}, Advice)
    assert "private detail" not in str(error.value)
