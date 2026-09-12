from collections.abc import Generator

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import Session, sessionmaker
from sqlalchemy.pool import StaticPool

from life_agent_core.auth import get_token_verifier
from life_agent_core.database import Base, get_db
from life_agent_core.main import app


class FakeTokenVerifier:
    def verify(self, token: str) -> str:
        if token == "invalid":
            raise ValueError("invalid token")
        return token


@pytest.fixture
def db_session() -> Generator[Session]:
    engine = create_engine(
        "sqlite://",
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    Base.metadata.create_all(engine)
    session_factory = sessionmaker(bind=engine, expire_on_commit=False)
    with session_factory() as session:
        yield session
    Base.metadata.drop_all(engine)


@pytest.fixture
def client(db_session: Session) -> Generator[TestClient]:

    def override_get_db() -> Generator[Session]:
        yield db_session

    app.dependency_overrides[get_db] = override_get_db
    app.dependency_overrides[get_token_verifier] = FakeTokenVerifier
    with TestClient(app, headers={"Authorization": "Bearer user-a"}) as test_client:
        yield test_client
    app.dependency_overrides.clear()


@pytest.fixture
def unauthenticated_client(db_session: Session) -> Generator[TestClient]:
    def override_get_db() -> Generator[Session]:
        yield db_session

    app.dependency_overrides[get_db] = override_get_db
    app.dependency_overrides[get_token_verifier] = FakeTokenVerifier
    with TestClient(app) as test_client:
        yield test_client
    app.dependency_overrides.clear()
