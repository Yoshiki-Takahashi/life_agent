from datetime import UTC, datetime, timedelta
from types import SimpleNamespace
from unittest.mock import MagicMock, Mock

import pytest
from openai import APITimeoutError

from evaluations.progress_cases import goal
from life_agent_goal_planner.config import Settings
from life_agent_goal_planner.progress import (
    AIUnavailableError,
    OpenAIAdvisor,
    OpenAIProgressParser,
    advice_context,
)
from life_agent_goal_planner.schemas import Advice, ProgressPreview


@pytest.mark.parametrize(
    "adapter,schema,method",
    [
        (OpenAIProgressParser, ProgressPreview, "parse"),
        (OpenAIAdvisor, Advice, "advise"),
    ],
)
def test_no_parsed_output_is_retryable(adapter, schema, method):
    context = goal("本を読む", "習慣を作る", [("読了冊数", "冊", 0, 12)])
    factory = MagicMock()
    factory.return_value.responses.parse.return_value = SimpleNamespace(output_parsed=None)
    provider = adapter(Settings(openai_api_key="test-key"), factory)
    args = (context, "1冊読んだ") if method == "parse" else (context,)

    with pytest.raises(AIUnavailableError):
        getattr(provider, method)(*args)

    call = factory.return_value.responses.parse.call_args
    assert call.kwargs["text_format"] is schema
    assert call.kwargs["store"] is False
    assert "owner_id" not in call.kwargs["input"][1]["content"]
    assert factory.call_args.kwargs["max_retries"] == 0


@pytest.mark.parametrize(
    "adapter,method", [(OpenAIProgressParser, "parse"), (OpenAIAdvisor, "advise")]
)
def test_missing_key_does_not_call_provider(adapter, method):
    context = goal("本を読む", "習慣を作る", [("読了冊数", "冊", 0, 12)])
    factory = MagicMock()
    provider = adapter(Settings(openai_api_key=None), factory)

    with pytest.raises(AIUnavailableError):
        getattr(provider, method)(*((context, "1冊読んだ") if method == "parse" else (context,)))

    factory.assert_not_called()


@pytest.mark.parametrize(
    "adapter,method", [(OpenAIProgressParser, "parse"), (OpenAIAdvisor, "advise")]
)
def test_timeout_is_retryable(adapter, method):
    context = goal("本を読む", "習慣を作る", [("読了冊数", "冊", 0, 12)])
    factory = MagicMock()
    factory.return_value.responses.parse.side_effect = APITimeoutError(request=Mock())
    provider = adapter(Settings(openai_api_key="test-key"), factory)

    with pytest.raises(AIUnavailableError, match="AI generation failed"):
        getattr(provider, method)(*((context, "1冊読んだ") if method == "parse" else (context,)))


def test_advice_context_supplies_dates_and_canonical_values():
    context = goal(
        "読む",
        "1日10分だけ",
        [("読了冊数", "冊", 1, 12)],
        [("本文には2冊とあるが確認後に1冊を保存", 21, 0, 1)],
        2,
    )
    today = datetime.now(UTC).date()
    payload = advice_context(context, today)

    assert payload["days_until_target"] == 2
    assert payload["days_since_latest_log"] == 21
    assert payload["metrics"][0]["current_value"] == 1
    assert payload["metrics"][0]["remaining"] == 11
    assert payload["progress_logs"][0]["metric_updates"][0]["value"] == 1
    overdue = advice_context(context, today + timedelta(days=3))
    assert overdue["days_until_target"] == -1
    assert "owner_id" not in str(payload)
    assert str(context.id) not in str(payload)


def test_advice_context_bounds_and_sorts_history():
    context = goal(
        "読む",
        "",
        [("読了冊数", "冊", 12, 20)],
        [(f"{index}日前", index, 0, 1) for index in range(11, -1, -1)],
    )
    payload = advice_context(context, datetime.now(UTC).date())

    assert len(payload["progress_logs"]) == 10
    assert payload["progress_logs"][0]["body"] == "0日前"
    assert payload["progress_logs"][-1]["body"] == "9日前"
    assert payload["days_since_latest_log"] == 0
    empty = advice_context(
        context.model_copy(update={"progress_logs": []}), datetime.now(UTC).date()
    )
    assert empty["days_since_latest_log"] is None
