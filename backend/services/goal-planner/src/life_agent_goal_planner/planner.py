import json
import logging
from collections.abc import Callable
from datetime import date, timedelta
from typing import Protocol

from openai import OpenAI, OpenAIError
from pydantic import ValidationError

from life_agent_goal_planner.config import Settings
from life_agent_goal_planner.schemas import GeneratedPlan, GoalPlan, GoalPlanRequest

logger = logging.getLogger(__name__)

SYSTEM_PROMPT = """You create practical initial plans for personal goals.
Return one to three measurable metrics and three to five milestones.
Every metric needs a concise name, a positive numeric target_value, and a unit.
Milestone dates must be between today's date and the goal target date, in nondecreasing order.
Use the same language as the user's goal. Do not add fields outside the response schema.
"""


class PlannerUnavailableError(Exception):
    """The plan could not be generated safely and may be retried."""


class GoalPlanner(Protocol):
    def generate(self, payload: GoalPlanRequest) -> GoalPlan: ...


class FakeGoalPlanner:
    """Deterministic provider used only for keyless service-boundary tests."""

    def generate(self, payload: GoalPlanRequest) -> GoalPlan:
        today = date.today()
        days = max((payload.target_date - today).days, 0)
        milestone_dates = (
            today + timedelta(days=days // 3),
            today + timedelta(days=(days * 2) // 3),
            payload.target_date,
        )
        return GoalPlan(
            **payload.model_dump(),
            metrics=[{"name": "独立Planner達成率", "target_value": 100, "unit": "%"}],
            milestones=[
                {"title": "実行内容を決める", "target_date": milestone_dates[0]},
                {"title": "中間目標を達成する", "target_date": milestone_dates[1]},
                {"title": "Goalを達成する", "target_date": milestone_dates[2]},
            ],
        )


class OpenAIGoalPlanner:
    def __init__(
        self,
        settings: Settings,
        client_factory: Callable[..., OpenAI] = OpenAI,
    ) -> None:
        self.settings = settings
        self.client_factory = client_factory

    def generate(self, payload: GoalPlanRequest) -> GoalPlan:
        if not self.settings.openai_api_key:
            raise PlannerUnavailableError("OpenAI API key is not configured")

        try:
            client = self.client_factory(
                api_key=self.settings.openai_api_key,
                timeout=self.settings.openai_timeout_seconds,
            )
            response = client.responses.parse(
                model=self.settings.openai_model,
                input=[
                    {"role": "system", "content": SYSTEM_PROMPT},
                    {
                        "role": "user",
                        "content": json.dumps(
                            {
                                "today": date.today().isoformat(),
                                **payload.model_dump(mode="json"),
                            },
                            ensure_ascii=False,
                        ),
                    },
                ],
                text_format=GeneratedPlan,
            )
            generated = response.output_parsed
            if generated is None:
                raise PlannerUnavailableError("OpenAI returned no parsed plan")
            return GoalPlan(**payload.model_dump(), **generated.model_dump())
        except PlannerUnavailableError:
            raise
        except (OpenAIError, ValidationError, ValueError, TypeError) as error:
            logger.warning("Goal plan generation failed: %s", type(error).__name__)
            raise PlannerUnavailableError("OpenAI plan generation failed") from error
