#!/usr/bin/env bash
set -euo pipefail

: "${CORE_API_IMAGE:?Set the immutable candidate image digest}"
project_id="${PROJECT_ID:-lifeagent-505614}"
region="${REGION:-asia-northeast1}"
root="$(cd "$(dirname "$0")/../.." && pwd)"
settings="$(mktemp)"
trap 'rm -f "${settings}"' EXIT
uv run --no-project python - "${root}/scripts/check-firestore-api.py" "${settings}" "${project_id}" <<'PY'
import base64
import json
import pathlib
import sys

script, target, project = sys.argv[1:]
env = {
    "CHECK_SCRIPT": base64.b64encode(pathlib.Path(script).read_bytes()).decode(),
    "FIRESTORE_PROJECT_ID": project,
    "FIRESTORE_DATABASE_ID": "(default)",
    "AUTH_BACKEND": "firebase",
    "GOAL_PLANNER_BACKEND": "fake",
    "PROGRESS_PARSER_BACKEND": "fake",
    "ADVISOR_BACKEND": "fake",
    "REPLANNER_BACKEND": "fake",
    "COACH_BACKEND": "fake",
    "MAINTENANCE_MODE": "false",
}
pathlib.Path(target).write_text(json.dumps(env))
PY
gcloud run jobs deploy lifeagent-firestore-check \
  --project="${project_id}" --region="${region}" \
  --image="${CORE_API_IMAGE}" \
  --service-account="lifeagent-core-api@${project_id}.iam.gserviceaccount.com" \
  --tasks=1 --parallelism=1 --max-retries=0 --task-timeout=180s \
  --command=uv \
  --args="^|^run|--no-sync|python|-c|import base64,os;exec(compile(base64.b64decode(os.environ['CHECK_SCRIPT']),'check-firestore-api.py','exec'))" \
  --env-vars-file="${settings}" --quiet
gcloud run jobs execute lifeagent-firestore-check \
  --project="${project_id}" --region="${region}" --wait
