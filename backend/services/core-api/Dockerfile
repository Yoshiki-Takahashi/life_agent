FROM python:3.13-slim

WORKDIR /app
RUN apt-get update \
    && apt-get install --no-install-recommends -y ca-certificates \
    && rm -rf /var/lib/apt/lists/*
COPY --from=ghcr.io/astral-sh/uv:0.8.22 /uv /uvx /bin/
COPY pyproject.toml uv.lock alembic.ini ./
COPY migrations ./migrations
COPY src ./src
RUN uv sync --frozen --no-dev

ENV PORT=8080
EXPOSE 8080
CMD ["sh", "-c", "exec uv run --no-sync uvicorn life_agent_core.main:app --host 0.0.0.0 --port ${PORT}"]
