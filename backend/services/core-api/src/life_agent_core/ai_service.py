import logging
from typing import Any

import httpx
from google.auth.exceptions import GoogleAuthError
from google.auth.transport.requests import Request
from google.oauth2.id_token import fetch_id_token
from pydantic import BaseModel

from life_agent_core.config import Settings
from life_agent_core.schemas import (
    Advice,
    CoachAIRequest,
    CoachReply,
    GoalResponse,
    ProgressPreview,
    ReplanCandidate,
)

logger = logging.getLogger(__name__)


class AIUnavailableError(Exception):
    """The AI result could not be obtained or validated."""


class InternalAIClient:
    def __init__(self, settings: Settings):
        self.url = settings.goal_planner_url.rstrip("/") + "/internal/v1/"
        self.timeout = settings.goal_planner_timeout_seconds
        self.audience = settings.goal_planner_id_token_audience

    def generate[T: BaseModel](self, path: str, payload: dict[str, Any], schema: type[T]) -> T:
        try:
            headers = None
            if self.audience:
                headers = {"Authorization": f"Bearer {fetch_id_token(Request(), self.audience)}"}
            response = httpx.post(
                self.url + path, json=payload, headers=headers, timeout=self.timeout
            )
            response.raise_for_status()
            return schema.model_validate(response.json())
        except (GoogleAuthError, httpx.HTTPError, ValueError, TypeError) as error:
            logger.warning("Internal AI request failed (%s)", type(error).__name__)
            raise AIUnavailableError("Internal AI result unavailable") from error


class HttpProgressParser:
    def __init__(self, settings: Settings):
        self.client = InternalAIClient(settings)

    def parse(self, context: GoalResponse, body: str) -> ProgressPreview:
        return self.client.generate(
            "progress-preview",
            {"goal": context.model_dump(mode="json"), "body": body},
            ProgressPreview,
        )


class HttpAdvisor:
    def __init__(self, settings: Settings):
        self.client = InternalAIClient(settings)

    def advise(self, context: GoalResponse) -> Advice:
        return self.client.generate("advice", {"goal": context.model_dump(mode="json")}, Advice)


class HttpReplanner:
    def __init__(self, settings: Settings):
        self.client = InternalAIClient(settings)

    def propose(self, context: GoalResponse, reason: str) -> ReplanCandidate:
        return self.client.generate(
            "replan", {"goal": context.model_dump(mode="json"), "reason": reason}, ReplanCandidate
        )


class HttpCoach:
    def __init__(self, settings: Settings):
        self.client = InternalAIClient(settings)

    def respond(self, request: CoachAIRequest) -> CoachReply:
        return self.client.generate("coach", request.model_dump(mode="json"), CoachReply)
