#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repository_root="$(cd "${script_dir}/.." && pwd)"
compose_file="${repository_root}/backend/compose.e2e.yaml"
core_api_dir="${repository_root}/backend/services/core-api"
core_api_pid=""
planner_pid=""

cleanup() {
    if [[ -n "${core_api_pid}" ]] && kill -0 "${core_api_pid}" 2>/dev/null; then
        kill "${core_api_pid}"
        wait "${core_api_pid}" 2>/dev/null || true
    fi
    if [[ -n "${planner_pid}" ]]; then
        kill "${planner_pid}" 2>/dev/null || true
        wait "${planner_pid}" 2>/dev/null || true
    fi
    docker compose -p lifeagent-weekend3-e2e -f "${compose_file}" down --volumes
}
trap cleanup EXIT

docker compose -p lifeagent-weekend3-e2e -f "${compose_file}" up -d --wait

cd "${repository_root}/backend/services/goal-planner"
GOAL_PLANNER_PROVIDER=fake PROGRESS_PARSER_PROVIDER=fake ADVISOR_PROVIDER=fake \
REPLANNER_PROVIDER=fake COACH_PROVIDER=fake \
    uv run uvicorn life_agent_goal_planner.main:app --host 127.0.0.1 --port 18001 &
planner_pid=$!
for _ in {1..20}; do
    kill -0 "${planner_pid}"
    if curl --fail --silent http://127.0.0.1:18001/health >/dev/null; then break; fi
    sleep 1
done
curl --fail --silent http://127.0.0.1:18001/health >/dev/null

cd "${core_api_dir}"
DATABASE_URL=postgresql+psycopg://lifeagent:lifeagent_e2e@127.0.0.1:15432/lifeagent \
    uv run alembic upgrade head
DATABASE_URL=postgresql+psycopg://lifeagent:lifeagent_e2e@127.0.0.1:15432/lifeagent \
GOAL_PLANNER_BACKEND=http \
GOAL_PLANNER_URL=http://127.0.0.1:18001 \
PROGRESS_PARSER_BACKEND=http ADVISOR_BACKEND=http REPLANNER_BACKEND=http COACH_BACKEND=http \
AUTH_BACKEND=fake \
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
    uv run pytest ../../../tests/e2e/test_services.py
