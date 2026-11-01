from fastapi import FastAPI

from life_agent_core import coach, goals, progress, replan

app = FastAPI(title="LifeAgent Core API", version="0.1.0")
for feature in (goals, progress, replan, coach):
    app.include_router(feature.router)


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}
