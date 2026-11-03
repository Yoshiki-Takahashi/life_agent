import logging

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from google.api_core.exceptions import GoogleAPICallError, RetryError

from life_agent_core import coach, goals, progress, replan
from life_agent_core.config import get_settings

app = FastAPI(title="LifeAgent Core API", version="0.1.0")
for feature in (goals, progress, replan, coach):
    app.include_router(feature.router)


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.middleware("http")
async def maintenance(request: Request, call_next):
    if get_settings().maintenance_mode and request.url.path.startswith("/api/"):
        return JSONResponse(
            status_code=503,
            content={"detail": "メンテナンス中です。時間をおいて再試行してください"},
            headers={"Retry-After": "60"},
        )
    return await call_next(request)


@app.exception_handler(GoogleAPICallError)
@app.exception_handler(RetryError)
async def database_unavailable(request: Request, error: Exception):
    logging.getLogger(__name__).warning("Firestore operation failed: %s", type(error).__name__)
    return JSONResponse(
        status_code=500, content={"detail": "保存・取得できませんでした。再試行してください"}
    )
