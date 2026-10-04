import logging
from typing import Any

import httpx
from google.auth.exceptions import GoogleAuthError
from google.auth.transport.requests import Request
from google.oauth2.id_token import fetch_id_token
from pydantic import ValidationError

from life_agent_core.config import Settings
from life_agent_core.progress_ai import Advice, AIUnavailableError, ProgressPreview
from life_agent_core.schemas import GoalResponse

logger = logging.getLogger(__name__)


class InternalAIClient:
    """HTTP client for stateless internal AI candidates.

    Core API remains responsible for ownership, canonical data, validation, user confirmation,
    and database writes. This client only requests candidate outputs from the internal AI service.
    """

    def __init__(self, settings: Settings) -> None:
        base_url = settings.goal_planner_url.rstrip("/")
        self.progress_preview_url = f"{base_url}/internal/v1/progress-preview"
        self.advice_url = f"{base_url}/internal/v1/advice"
        self.timeout = settings.goal_planner_timeout_seconds
        self.id_token_audience = settings.goal_planner_id_token_audience

    def parse_progress(self, context: GoalResponse, body: str) -> ProgressPreview:
        payload = {"goal": context.model_dump(mode="json"), "body": body}
        try:
            return ProgressPreview.model_validate(self._post(self.progress_preview_url, payload))
        except ValidationError as error:
            logger.warning("Internal AI progress contract failed validation")
            raise AIUnavailableError("Internal AI service returned invalid progress") from error

    def advise(self, context: GoalResponse) -> Advice:
        payload = {"goal": context.model_dump(mode="json")}
        try:
            return Advice.model_validate(self._post(self.advice_url, payload))
        except ValidationError as error:
            logger.warning("Internal AI advice contract failed validation")
            raise AIUnavailableError("Internal AI service returned invalid advice") from error

    def _post(self, url: str, payload: dict[str, Any]) -> Any:
        try:
            response = httpx.post(
                url,
                json=payload,
                headers=self._headers(),
                timeout=self.timeout,
            )
            response.raise_for_status()
            return response.json()
        except (
            GoogleAuthError,
            httpx.HTTPError,
            ValidationError,
            ValueError,
            TypeError,
        ) as error:
            logger.warning("Internal AI service request failed (%s)", type(error).__name__)
            raise AIUnavailableError("Internal AI service request failed") from error

    def _headers(self) -> dict[str, str] | None:
        if not self.id_token_audience:
            return None
        token = fetch_id_token(Request(), self.id_token_audience)
        return {"Authorization": f"Bearer {token}"}


class HttpProgressParser:
    def __init__(self, settings: Settings) -> None:
        self.client = InternalAIClient(settings)

    def parse(self, context: GoalResponse, body: str) -> ProgressPreview:
        return self.client.parse_progress(context, body)


class HttpAdvisor:
    def __init__(self, settings: Settings) -> None:
        self.client = InternalAIClient(settings)

    def advise(self, context: GoalResponse) -> Advice:
        return self.client.advise(context)
