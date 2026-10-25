#!/usr/bin/env bash
set -euo pipefail

# Start Cloud SQL and its local Auth Proxy before running this script.
repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
project_id="${GCP_PROJECT_ID:-lifeagent-505614}"
export DB_PASSWORD="$(gcloud secrets versions access latest \
    --secret=lifeagent-db-password --project="${project_id}")"
test -n "${DB_PASSWORD}"
export MIGRATION_PROXY_PORT="${MIGRATION_PROXY_PORT:-15438}"
cd "${repository_root}/backend/services/core-api"
uv run --frozen python - <<'PY'
import os

from alembic import command
from alembic.config import Config
from sqlalchemy import URL

os.environ["DATABASE_URL"] = URL.create(
    "postgresql+psycopg",
    username="lifeagent_app",
    password=os.environ["DB_PASSWORD"],
    host="127.0.0.1",
    port=int(os.environ["MIGRATION_PROXY_PORT"]),
    database="lifeagent",
).render_as_string(hide_password=False)
try:
    config = Config("alembic.ini")
    command.upgrade(config, "head")
    command.current(config)
except Exception as error:
    print(f"Migration failed ({type(error).__name__}); deployment must stop.")
    raise SystemExit(1) from None
PY
