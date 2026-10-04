from functools import lru_cache
from typing import Literal

from pydantic import field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    goal_planner_provider: Literal["openai", "fake"] = "openai"
    progress_parser_provider: Literal["openai", "fake"] = "fake"
    advisor_provider: Literal["openai", "fake"] = "fake"
    openai_api_key: str | None = None
    openai_model: str = "gpt-5.6-luna"
    openai_timeout_seconds: float = 20.0

    @field_validator("openai_api_key")
    @classmethod
    def strip_openai_api_key(cls, value: str | None) -> str | None:
        if value is None:
            return None
        stripped = value.strip()
        return stripped or None

    model_config = SettingsConfigDict(
        env_file=("../../.env", ".env"),
        env_file_encoding="utf-8",
        extra="ignore",
    )


@lru_cache
def get_settings() -> Settings:
    return Settings()
