#!/usr/bin/env bash
set -euo pipefail

project_id="${PROJECT_ID:-lifeagent-505614}"
database_id="(default)"
settings="$(gcloud firestore databases describe --project="${project_id}" \
    --database="${database_id}" --format=json)"
indexes="$(gcloud firestore indexes composite list --project="${project_id}" \
    --database="${database_id}" --format=json)"
export FIRESTORE_CHECK_SETTINGS="${settings}" FIRESTORE_CHECK_INDEXES="${indexes}"
uv run --no-project python - <<'PY'
import json
import os

settings = json.loads(os.environ["FIRESTORE_CHECK_SETTINGS"])
assert settings["databaseEdition"] == "STANDARD", "Expected Standard edition"
assert settings["type"] == "FIRESTORE_NATIVE", "Expected Native mode"
assert settings["locationId"] == "asia-northeast1", "Expected Tokyo"
assert settings.get("freeTier") is True, "Expected a free-tier eligible database"
assert settings["pointInTimeRecoveryEnablement"] == "POINT_IN_TIME_RECOVERY_DISABLED"
indexes = json.loads(os.environ["FIRESTORE_CHECK_INDEXES"])
expected = {
    ("goals", (("owner_id", "ASCENDING"), ("updated_at", "DESCENDING"), ("id", "DESCENDING"))),
    ("goals", (("owner_id", "ASCENDING"), ("created_at", "ASCENDING"), ("id", "ASCENDING"))),
    ("progress_logs", (("recorded_at", "DESCENDING"), ("id", "DESCENDING"))),
}
ready = set()
for index in indexes:
    if index["state"] != "READY" or index["queryScope"] != "COLLECTION":
        continue
    fields = tuple((f["fieldPath"], f.get("order")) for f in index["fields"]
                   if f["fieldPath"] != "__name__")
    ready.add((index["name"].split("/collectionGroups/")[1].split("/")[0], fields))
assert expected <= ready, f"Required indexes are not ready: {expected - ready}"
print("Firestore Standard / Tokyo / free tier / indexes: ready")
PY
