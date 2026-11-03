from functools import lru_cache
from typing import Literal

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

    model_config = SettingsConfigDict(
        env_file=("../../.env", ".env"),
        env_file_encoding="utf-8",
        extra="ignore",
    )


@lru_cache
def get_settings() -> Settings:
    return Settings()
