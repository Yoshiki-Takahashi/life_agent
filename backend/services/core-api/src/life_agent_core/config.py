from functools import lru_cache
from typing import Literal

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    database_url: str = "postgresql+psycopg://lifeagent:lifeagent_local@localhost:5432/lifeagent"
    goal_planner_backend: Literal["fake", "http"] = "fake"
    goal_planner_url: str = "http://127.0.0.1:8001"
    # Must exceed the Goal Planner's provider timeout (20 seconds by default).
    goal_planner_timeout_seconds: float = 25.0
    firebase_project_id: str = "life-agent-local"
    auth_backend: Literal["firebase", "fake"] = "firebase"

    model_config = SettingsConfigDict(
        env_file=("../../.env", ".env"),
        env_file_encoding="utf-8",
        extra="ignore",
    )


@lru_cache
def get_settings() -> Settings:
    return Settings()
