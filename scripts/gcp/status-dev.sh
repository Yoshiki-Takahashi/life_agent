#!/usr/bin/env bash
set -euo pipefail

project_id="lifeagent-505614"
region="asia-northeast1"
sql_instance="lifeagent-dev-db"
run_service="lifeagent-core-api"
planner_service="lifeagent-goal-planner"

echo "Project: ${project_id}"
echo "Cloud SQL:"
gcloud sql instances describe "${sql_instance}" \
    --project="${project_id}" \
    --format='table(name,state,settings.activationPolicy,region,databaseVersion)'

echo "Cloud Run:"
gcloud run services describe "${run_service}" \
    --region="${region}" \
    --project="${project_id}" \
    --format='table(metadata.name,status.latestReadyRevisionName,status.traffic[0].percent,status.url)'

if gcloud run services describe "${planner_service}" \
    --region="${region}" \
    --project="${project_id}" >/dev/null 2>&1; then
    echo "Goal Planner Cloud Run:"
    gcloud run services describe "${planner_service}" \
        --region="${region}" \
        --project="${project_id}" \
        --format='table(metadata.name,status.latestReadyRevisionName,status.traffic[0].percent,status.url)'
else
    echo "Goal Planner Cloud Run: not created"
fi

max_scale="$(gcloud run services describe "${run_service}" \
    --region="${region}" \
    --project="${project_id}" \
    --format='value(metadata.annotations."run.googleapis.com/maxScale")')"
min_scale="$(gcloud run services describe "${run_service}" \
    --region="${region}" \
    --project="${project_id}" \
    --format='value(spec.template.metadata.annotations."autoscaling.knative.dev/minScale")')"

echo "Cloud Run scaling: min=${min_scale:-0}, service max=${max_scale:-not-set}"
