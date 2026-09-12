from functools import lru_cache
from typing import Annotated, Protocol

import firebase_admin
from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from firebase_admin import auth

from life_agent_core.config import get_settings


class TokenVerifier(Protocol):
    def verify(self, token: str) -> str: ...


class FirebaseTokenVerifier:
    def __init__(self, project_id: str) -> None:
        try:
            firebase_admin.get_app()
        except ValueError:
            firebase_admin.initialize_app(options={"projectId": project_id})

    def verify(self, token: str) -> str:
        decoded = auth.verify_id_token(token)
        uid = decoded.get("uid")
        if not isinstance(uid, str) or not uid:
            raise ValueError("Firebase ID Tokenにuidがありません")
        return uid


class FakeTokenVerifier:
    def verify(self, token: str) -> str:
        if not token:
            raise ValueError("Tokenがありません")
        return token


@lru_cache
def get_token_verifier() -> TokenVerifier:
    settings = get_settings()
    if settings.auth_backend == "fake":
        return FakeTokenVerifier()
    return FirebaseTokenVerifier(settings.firebase_project_id)


bearer = HTTPBearer(auto_error=False)


def get_current_user_id(
    credentials: Annotated[HTTPAuthorizationCredentials | None, Depends(bearer)],
    verifier: Annotated[TokenVerifier, Depends(get_token_verifier)],
) -> str:
    if credentials is None or credentials.scheme.lower() != "bearer":
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="ログインが必要です",
            headers={"WWW-Authenticate": "Bearer"},
        )
    try:
        return verifier.verify(credentials.credentials)
    except Exception as error:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="認証情報が無効です。もう一度ログインしてください",
            headers={"WWW-Authenticate": "Bearer"},
        ) from error


CurrentUserId = Annotated[str, Depends(get_current_user_id)]
