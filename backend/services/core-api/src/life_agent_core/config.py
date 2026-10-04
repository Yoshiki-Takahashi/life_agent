from functools import lru_cache
from typing import Literal

from pydantic import SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict
from sqlalchemy import URL


class Settings(BaseSettings):
    database_url: str = "postgresql+psycopg://lifeagent:lifeagent_local@localhost:5432/lifeagent"
    cloud_sql_connection_name: str | None = None
    db_user: str = "lifeagent_app"
    db_password: SecretStr | None = None
    db_name: str = "lifeagent"
    goal_planner_backend: Literal["fake", "http"] = "fake"
    goal_planner_url: str = "http://127.0.0.1:8001"
    goal_planner_id_token_audience: str | None = None
    # Must exceed the Goal Planner's provider timeout (20 seconds by default).
    goal_planner_timeout_seconds: float = 25.0
    firebase_project_id: str = "life-agent-local"
    progress_parser_backend: Literal["fake", "http", "openai"] = "fake"
    advisor_backend: Literal["fake", "http", "openai"] = "fake"
    auth_backend: Literal["firebase", "fake"] = "firebase"

    model_config = SettingsConfigDict(
        env_file=("../../.env", ".env"),
        env_file_encoding="utf-8",
        extra="ignore",
    )

    @property
    def database_connection_url(self) -> str | URL:
        if self.cloud_sql_connection_name is None:
            return self.database_url
        if self.db_password is None:
            raise ValueError("DB_PASSWORD is required when CLOUD_SQL_CONNECTION_NAME is set")

        return URL.create(
            drivername="postgresql+psycopg",
            username=self.db_user,
            password=self.db_password.get_secret_value(),
            database=self.db_name,
            query={"host": f"/cloudsql/{self.cloud_sql_connection_name}"},
        )


@lru_cache
def get_settings() -> Settings:
    return Settings()
