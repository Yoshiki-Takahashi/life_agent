from functools import lru_cache
from typing import Literal

from pydantic import Field, SecretStr, field_validator, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    firestore_project_id: str = "demo-lifeagent"
    firestore_database_id: str = "(default)"
    firestore_emulator_host: str | None = None
    maintenance_mode: bool = False
    goal_planner_backend: Literal["fake", "http"] = "fake"
    goal_planner_url: str = "http://127.0.0.1:8001"
    goal_planner_id_token_audience: str | None = None
    # Must exceed the Goal Planner's provider timeout (20 seconds by default).
    goal_planner_timeout_seconds: float = 25.0
    firebase_project_id: str = "life-agent-local"
    progress_parser_backend: Literal["fake", "http", "openai"] = "fake"
    advisor_backend: Literal["fake", "http", "openai"] = "fake"
    replanner_backend: Literal["fake", "http", "openai"] = "fake"
    coach_backend: Literal["fake", "http"] = "fake"
    auth_backend: Literal["firebase", "fake"] = "firebase"
    allowed_user_id: str | None = Field(default=None, min_length=1)
    allowed_user_ids: str | None = Field(default=None, min_length=1)
    k_service: str | None = None
    feedback_github_repository: str = Field(
        default="Yoshiki-Takahashi/life_agent", pattern=r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$"
    )
    feedback_github_token: SecretStr | None = None

    @field_validator("allowed_user_id", "allowed_user_ids")
    @classmethod
    def validate_allowed_uids(cls, value: str | None) -> str | None:
        if value is None:
            return None
        uids = value.split(",")
        if any(not uid or uid != uid.strip() or any(c.isspace() for c in uid) for uid in uids):
            raise ValueError("Allowed Firebase UIDs must be nonempty and contain no whitespace")
        return value

    @field_validator("allowed_user_id")
    @classmethod
    def require_single_legacy_uid(cls, value: str | None) -> str | None:
        if value is not None and "," in value:
            raise ValueError(
                "ALLOWED_USER_ID accepts a single UID; use ALLOWED_USER_IDS for a list"
            )
        return value

    @property
    def allowed_uids(self) -> frozenset[str]:
        return frozenset(
            uid
            for value in (self.allowed_user_id, self.allowed_user_ids)
            if value is not None
            for uid in value.split(",")
        )

    @model_validator(mode="after")
    def require_cloud_access_policy(self):
        if self.k_service and (self.auth_backend != "firebase" or not self.allowed_uids):
            raise ValueError(
                "Cloud Run requires Firebase authentication and an explicit allowed UID policy"
            )
        return self

    model_config = SettingsConfigDict(
        env_file=("../../.env", ".env"),
        env_file_encoding="utf-8",
        extra="ignore",
    )


@lru_cache
def get_settings() -> Settings:
    return Settings()
