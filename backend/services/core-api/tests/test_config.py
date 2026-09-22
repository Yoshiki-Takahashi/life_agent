import pytest

from life_agent_core.config import Settings


def test_database_connection_url_uses_local_url_by_default() -> None:
    settings = Settings(database_url="postgresql+psycopg://local:test@localhost/lifeagent")

    assert settings.database_connection_url == settings.database_url


def test_database_connection_url_uses_cloud_sql_socket() -> None:
    settings = Settings(
        cloud_sql_connection_name="project:region:instance",
        db_user="app-user",
        db_password="p@ss/word",
        db_name="app-db",
    )

    url = settings.database_connection_url

    assert url.drivername == "postgresql+psycopg"
    assert url.username == "app-user"
    assert url.password == "p@ss/word"
    assert url.database == "app-db"
    assert url.query == {"host": "/cloudsql/project:region:instance"}
    assert "***" in url.render_as_string()
    assert "p@ss/word" not in url.render_as_string()


def test_cloud_sql_connection_requires_password() -> None:
    settings = Settings(cloud_sql_connection_name="project:region:instance")

    with pytest.raises(ValueError, match="DB_PASSWORD is required"):
        _ = settings.database_connection_url
