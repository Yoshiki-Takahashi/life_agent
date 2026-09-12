#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repository_root="$(cd "${script_dir}/.." && pwd)"
compose_file="${repository_root}/backend/compose.e2e.yaml"
core_api_dir="${repository_root}/backend/services/core-api"
core_api_pid=""

cleanup() {
    if [[ -n "${core_api_pid}" ]] && kill -0 "${core_api_pid}" 2>/dev/null; then
        kill "${core_api_pid}"
        wait "${core_api_pid}" 2>/dev/null || true
    fi
    docker compose -p lifeagent-weekend3-e2e -f "${compose_file}" down --volumes
}
trap cleanup EXIT

docker compose -p lifeagent-weekend3-e2e -f "${compose_file}" up -d --build --wait

cd "${core_api_dir}"
DATABASE_URL=postgresql+psycopg://lifeagent:lifeagent_e2e@127.0.0.1:15432/lifeagent \
    uv run alembic upgrade head
DATABASE_URL=postgresql+psycopg://lifeagent:lifeagent_e2e@127.0.0.1:15432/lifeagent \
GOAL_PLANNER_BACKEND=http \
GOAL_PLANNER_URL=http://127.0.0.1:18001 \
    uv run uvicorn life_agent_core.main:app --host 127.0.0.1 --port 18000 &
core_api_pid=$!

for _ in {1..20}; do
    if curl --fail --silent http://127.0.0.1:18000/health >/dev/null; then
        break
    fi
    sleep 1
done
curl --fail --silent http://127.0.0.1:18000/health >/dev/null

CORE_API_URL=http://127.0.0.1:18000 \
GOAL_PLANNER_URL=http://127.0.0.1:18001 \
    uv run pytest ../../../tests/e2e/test_weekend_3.py
