#!/usr/bin/env bash
set -euo pipefail

project_id="lifeagent-505614"
region="asia-northeast1"
repository="lifeagent-dev"
database_id="(default)"
run_service="lifeagent-core-api"
planner_service="lifeagent-goal-planner"
runtime_service_account="lifeagent-core-api@lifeagent-505614.iam.gserviceaccount.com"

show_targets() {
    echo "Deletion preview for project ${project_id}:"
    echo "- Cloud Run service: ${run_service} (${region})"
    echo "- Cloud Run service: ${planner_service} (${region}), if it exists"
    echo "- Firestore database and all documents: ${database_id}"
    echo "- Artifact Registry repository and images: ${repository} (${region})"
    echo "- Service account: ${runtime_service_account}"
    echo
    echo "Legacy Cloud SQL retirement is separate; see the migration runbook."
    echo "The GCP project, Firebase project, Firebase users, and Billing Budgets are not deleted."
}

show_targets

if [[ "${1:-}" != "--execute" ]]; then
    echo "Preview only. No resources were deleted."
    echo "Run with --execute only when complete deletion is intentionally required."
    exit 0
fi

if [[ ! -t 0 ]]; then
    echo "Deletion requires an interactive terminal." >&2
    exit 1
fi

read -r -p "Type the project ID to continue: " confirmed_project
read -r -p "Type DELETE-LIFEAGENT-DEV to confirm irreversible deletion: " confirmed_action

if [[ "${confirmed_project}" != "${project_id}" || "${confirmed_action}" != "DELETE-LIFEAGENT-DEV" ]]; then
    echo "Confirmation did not match. Nothing was deleted." >&2
    exit 1
fi

gcloud run services delete "${run_service}" \
    --region="${region}" --project="${project_id}" --quiet
if gcloud run services describe "${planner_service}" \
    --region="${region}" \
    --project="${project_id}" >/dev/null 2>&1; then
    gcloud run services delete "${planner_service}" \
        --region="${region}" --project="${project_id}" --quiet
fi
gcloud firestore databases update --database="${database_id}" \
    --project="${project_id}" --no-delete-protection --quiet
gcloud firestore databases delete --database="${database_id}" \
    --project="${project_id}" --quiet
gcloud artifacts repositories delete "${repository}" \
    --location="${region}" --project="${project_id}" --quiet
gcloud iam service-accounts delete "${runtime_service_account}" \
    --project="${project_id}" --quiet

echo "LifeAgent development cloud resources were deleted."
