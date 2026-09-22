#!/usr/bin/env bash
set -euo pipefail

project_id="lifeagent-505614"
region="asia-northeast1"
sql_instance="lifeagent-dev-db"
run_service="lifeagent-core-api"

activation_policy="$(gcloud sql instances describe "${sql_instance}" \
    --project="${project_id}" \
    --format='value(settings.activationPolicy)')"

if [[ "${activation_policy}" != "ALWAYS" ]]; then
    echo "Starting Cloud SQL ${sql_instance}..."
    if ! gcloud sql instances patch "${sql_instance}" \
        --activation-policy=ALWAYS \
        --project="${project_id}" \
        --quiet; then
        echo "The update is still being checked because gcloud may time out before Cloud SQL finishes."
    fi
else
    echo "Cloud SQL ${sql_instance} is already configured to run."
fi

for _ in {1..60}; do
    state="$(gcloud sql instances describe "${sql_instance}" \
        --project="${project_id}" \
        --format='value(state)')"
    if [[ "${state}" == "RUNNABLE" ]]; then
        service_url="$(gcloud run services describe "${run_service}" \
            --region="${region}" \
            --project="${project_id}" \
            --format='value(status.url)')"
        curl --fail-with-body --silent --show-error --max-time 30 "${service_url}/health"
        echo
        echo "Development environment is ready."
        exit 0
    fi
    sleep 5
done

echo "Cloud SQL did not become RUNNABLE within 5 minutes." >&2
exit 1
