import json
from datetime import date, timedelta
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker

from life_agent_goal_planner.schemas import GoalPlan, GoalPlanRequest

CONTRACTS = Path(__file__).resolve().parents[4] / "contracts" / "schemas"


def load_contract(name: str) -> dict[str, object]:
    return json.loads((CONTRACTS / name).read_text())


def request() -> GoalPlanRequest:
    return GoalPlanRequest(
        title="12冊の本を読む",
        description=None,
        target_date=date.today() + timedelta(days=90),
    )


def plan() -> GoalPlan:
    return GoalPlan(
        **request().model_dump(),
        metrics=[{"name": "読了冊数", "target_value": 12, "unit": "冊"}],
        milestones=[
            {"title": "本を決める", "target_date": date.today() + timedelta(days=30)},
            {"title": "6冊読む", "target_date": date.today() + timedelta(days=60)},
            {"title": "12冊読む", "target_date": date.today() + timedelta(days=90)},
        ],
    )


def test_request_matches_shared_json_schema() -> None:
    Draft202012Validator(
        load_contract("goal-plan-request.schema.json"), format_checker=FormatChecker()
    ).validate(request().model_dump(mode="json"))


def test_response_matches_shared_json_schema() -> None:
    Draft202012Validator(
        load_contract("goal-plan.schema.json"), format_checker=FormatChecker()
    ).validate(plan().model_dump(mode="json"))
