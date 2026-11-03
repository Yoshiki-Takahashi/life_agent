#!/usr/bin/env bash
set -euo pipefail
project_id="${PROJECT_ID:-lifeagent-505614}"
region="${REGION:-asia-northeast1}"
for service in lifeagent-goal-planner lifeagent-core-api; do
    gcloud run services update "${service}" --project="${project_id}" --region="${region}" \
        --scaling=0 --min-instances=0 --max-instances=1 --quiet
done
echo 'Cloud Run scaling: 0. Firestore remains available and stored data is retained.'
