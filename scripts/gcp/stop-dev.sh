#!/usr/bin/env bash
set -euo pipefail

project_id="lifeagent-505614"
region="asia-northeast1"
sql_instance="lifeagent-dev-db"
run_service="lifeagent-core-api"
planner_service="lifeagent-goal-planner"

activation_policy="$(gcloud sql instances describe "${sql_instance}" \
    --project="${project_id}" \
    --format='value(settings.activationPolicy)')"

if [[ "${activation_policy}" != "NEVER" ]]; then
    echo "Stopping Cloud SQL ${sql_instance}..."
    if ! gcloud sql instances patch "${sql_instance}" \
        --activation-policy=NEVER \
        --project="${project_id}" \
        --quiet; then
        echo "The update is still being checked because gcloud may time out before Cloud SQL finishes."
    fi
else
    echo "Cloud SQL ${sql_instance} is already configured to stop."
fi

for _ in {1..60}; do
    state="$(gcloud sql instances describe "${sql_instance}" \
        --project="${project_id}" \
        --format='value(state)')"
    if [[ "${state}" == "STOPPED" ]]; then
        break
    fi
    sleep 5
done

state="$(gcloud sql instances describe "${sql_instance}" \
    --project="${project_id}" \
    --format='value(state)')"
if [[ "${state}" != "STOPPED" ]]; then
    echo "Cloud SQL did not become STOPPED within 5 minutes." >&2
    exit 1
fi

max_scale="$(gcloud run services describe "${run_service}" \
    --region="${region}" \
    --project="${project_id}" \
    --format='value(spec.template.metadata.annotations."autoscaling.knative.dev/maxScale")')"
if [[ -z "${max_scale}" ]]; then
    max_scale="$(gcloud run services describe "${run_service}" \
        --region="${region}" \
        --project="${project_id}" \
        --format='value(metadata.annotations."run.googleapis.com/maxScale")')"
fi
min_scale="$(gcloud run services describe "${run_service}" \
    --region="${region}" \
    --project="${project_id}" \
    --format='value(spec.template.metadata.annotations."autoscaling.knative.dev/minScale")')"

if [[ "${min_scale:-0}" != "0" || "${max_scale}" != "1" ]]; then
    echo "Unexpected Cloud Run scaling: min=${min_scale:-0}, service max=${max_scale:-not-set}" >&2
    exit 1
fi

echo "Cloud SQL is stopped. Cloud Run remains request-driven with min=0 and service max=1."

if gcloud run services describe "${planner_service}" \
    --region="${region}" \
    --project="${project_id}" >/dev/null 2>&1; then
    planner_min_scale="$(gcloud run services describe "${planner_service}" \
        --region="${region}" \
        --project="${project_id}" \
        --format='value(spec.template.metadata.annotations."autoscaling.knative.dev/minScale")')"
    planner_max_scale="$(gcloud run services describe "${planner_service}" \
        --region="${region}" \
        --project="${project_id}" \
        --format='value(spec.template.metadata.annotations."autoscaling.knative.dev/maxScale")')"
    if [[ -z "${planner_max_scale}" ]]; then
        planner_max_scale="$(gcloud run services describe "${planner_service}" \
            --region="${region}" \
            --project="${project_id}" \
            --format='value(metadata.annotations."run.googleapis.com/maxScale")')"
    fi
    if [[ "${planner_min_scale:-0}" != "0" || "${planner_max_scale}" != "1" ]]; then
        echo "Unexpected Goal Planner scaling: min=${planner_min_scale:-0}, max=${planner_max_scale:-not-set}" >&2
        exit 1
    fi
    echo "Goal Planner remains request-driven with min=0 and max=1."
fi
