#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
project_id="${PROJECT_ID:-lifeagent-505614}"
runtime_account="lifeagent-core-api@${project_id}.iam.gserviceaccount.com"
cd "${repository_root}"

gcloud services enable firestore.googleapis.com --project="${project_id}" --quiet
# Database creation is intentionally separate: inspect existing databases first.
gcloud firestore databases describe --project="${project_id}" --database='(default)' \
    --format='table(name,locationId,databaseEdition,type,freeTier)'
gcloud projects add-iam-policy-binding "${project_id}" \
    --member="serviceAccount:${runtime_account}" --role=roles/datastore.user \
    --condition="expression=resource.name==\"projects/${project_id}/databases/(default)\",title=lifeagent-firestore-default" \
    --quiet >/dev/null
firebase deploy --only firestore:rules,firestore:indexes --project="${project_id}" --non-interactive
echo 'Wait for indexes to be READY, then run scripts/gcp/check-firestore.sh.'
