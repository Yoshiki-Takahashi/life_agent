#!/usr/bin/env bash
set -euo pipefail

project_id="lifeagent-505614"
region="asia-northeast1"
repository="lifeagent-dev"
sql_instance="lifeagent-dev-db"
run_service="lifeagent-core-api"
runtime_service_account="lifeagent-core-api@lifeagent-505614.iam.gserviceaccount.com"

show_targets() {
    echo "Deletion preview for project ${project_id}:"
    echo "- Cloud Run service: ${run_service} (${region})"
    echo "- Cloud SQL instance and its databases: ${sql_instance}"
    echo "- Secret Manager secret: lifeagent-db-password"
    echo "- Secret Manager secret: lifeagent-db-admin-password"
    echo "- Artifact Registry repository and images: ${repository} (${region})"
    echo "- Service account: ${runtime_service_account}"
    echo
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
gcloud sql instances delete "${sql_instance}" \
    --project="${project_id}" --quiet
gcloud secrets delete lifeagent-db-password \
    --project="${project_id}" --quiet
gcloud secrets delete lifeagent-db-admin-password \
    --project="${project_id}" --quiet
gcloud artifacts repositories delete "${repository}" \
    --location="${region}" --project="${project_id}" --quiet
gcloud iam service-accounts delete "${runtime_service_account}" \
    --project="${project_id}" --quiet

echo "LifeAgent development cloud resources were deleted."
