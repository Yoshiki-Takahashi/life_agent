FROM python:3.13-slim

WORKDIR /app
RUN apt-get update \
    && apt-get install --no-install-recommends -y ca-certificates \
    && rm -rf /var/lib/apt/lists/*
COPY --from=ghcr.io/astral-sh/uv:0.8.22 /uv /uvx /bin/
COPY pyproject.toml uv.lock ./
COPY src ./src
ENV TIKTOKEN_CACHE_DIR=/app/.tiktoken
RUN uv sync --frozen --no-dev \
    && uv run --no-sync python -c 'import tiktoken; tiktoken.get_encoding("o200k_base")'

EXPOSE 8001
CMD ["uv", "run", "--no-sync", "uvicorn", "life_agent_goal_planner.main:app", "--host", "0.0.0.0", "--port", "8001"]
