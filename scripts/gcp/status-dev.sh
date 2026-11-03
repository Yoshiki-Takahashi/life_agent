#!/usr/bin/env bash
set -euo pipefail
project_id="${PROJECT_ID:-lifeagent-505614}"
region="${REGION:-asia-northeast1}"
gcloud firestore databases describe --project="${project_id}" --database='(default)' \
    --format='table(name,locationId,databaseEdition,type,freeTier,pointInTimeRecoveryEnablement)'
for service in lifeagent-core-api lifeagent-goal-planner; do
    gcloud run services describe "${service}" --project="${project_id}" --region="${region}" \
        --format='json(metadata.name,metadata.annotations,spec.template.metadata.annotations,status.traffic,status.latestReadyRevisionName)'
done
# Until issue #23 retirement is complete, explicitly report remaining SQL charges.
gcloud sql instances list --project="${project_id}" \
    --format='table(name,state,settings.activationPolicy)'
