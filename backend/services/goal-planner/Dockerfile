FROM python:3.13-slim

WORKDIR /app
COPY --from=ghcr.io/astral-sh/uv:0.8.22 /uv /uvx /bin/
COPY pyproject.toml uv.lock ./
COPY src ./src
RUN uv sync --frozen --no-dev

EXPOSE 8001
CMD ["uv", "run", "--no-sync", "uvicorn", "life_agent_goal_planner.main:app", "--host", "0.0.0.0", "--port", "8001"]
