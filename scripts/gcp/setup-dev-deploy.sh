#!/usr/bin/env bash
set -euo pipefail

project_id="${PROJECT_ID:-lifeagent-505614}"
region="${REGION:-asia-northeast1}"
repository="${ARTIFACT_REGISTRY_REPOSITORY:-lifeagent-dev}"
github_repo="${GITHUB_REPOSITORY:-Yoshiki-Takahashi/life_agent}"
pool_id="${WIF_POOL_ID:-github-actions}"
provider_id="${WIF_PROVIDER_ID:-lifeagent}"
deployer_account_id="${DEPLOYER_ACCOUNT_ID:-lifeagent-github-deployer}"
core_runtime_account="lifeagent-core-api@${project_id}.iam.gserviceaccount.com"
planner_runtime_account_id="${GOAL_PLANNER_RUNTIME_ACCOUNT_ID:-lifeagent-goal-planner}"
planner_runtime_account="${planner_runtime_account_id}@${project_id}.iam.gserviceaccount.com"
core_service="${CORE_API_SERVICE:-lifeagent-core-api}"
planner_service="${GOAL_PLANNER_SERVICE:-lifeagent-goal-planner}"
sql_connection_name="${CLOUD_SQL_CONNECTION_NAME:-lifeagent-505614:asia-northeast1:lifeagent-dev-db}"
openai_secret_name="${OPENAI_SECRET_NAME:-lifeagent-openai-api-key}"
db_secret_name="${DB_SECRET_NAME:-lifeagent-db-password}"

project_number="$(gcloud projects describe "${project_id}" --format='value(projectNumber)')"
deployer_account="${deployer_account_id}@${project_id}.iam.gserviceaccount.com"
provider_resource="projects/${project_number}/locations/global/workloadIdentityPools/${pool_id}/providers/${provider_id}"
principal_set="principalSet://iam.googleapis.com/projects/${project_number}/locations/global/workloadIdentityPools/${pool_id}/attribute.repository/${github_repo}"

ensure_service_account() {
    local account_id="$1"
    local display_name="$2"
    local email="${account_id}@${project_id}.iam.gserviceaccount.com"

    if gcloud iam service-accounts describe "${email}" --project="${project_id}" >/dev/null 2>&1; then
        echo "Service account exists: ${email}"
        return
    fi

    gcloud iam service-accounts create "${account_id}" \
        --project="${project_id}" \
        --display-name="${display_name}"
}

add_project_role() {
    local member="$1"
    local role="$2"
    gcloud projects add-iam-policy-binding "${project_id}" \
        --member="${member}" \
        --role="${role}" \
        --condition=None \
        --quiet >/dev/null
}

add_service_account_user() {
    local runtime_account="$1"
    gcloud iam service-accounts add-iam-policy-binding "${runtime_account}" \
        --project="${project_id}" \
        --member="serviceAccount:${deployer_account}" \
        --role="roles/iam.serviceAccountUser" \
        --quiet >/dev/null
}

add_secret_accessor_if_exists() {
    local secret_name="$1"
    local service_account="$2"

    if ! gcloud secrets describe "${secret_name}" --project="${project_id}" >/dev/null 2>&1; then
        echo "Secret not found, skipping IAM binding: ${secret_name}"
        return
    fi

    gcloud secrets add-iam-policy-binding "${secret_name}" \
        --project="${project_id}" \
        --member="serviceAccount:${service_account}" \
        --role="roles/secretmanager.secretAccessor" \
        --quiet >/dev/null
}

set_github_variable() {
    local name="$1"
    local value="$2"

    if ! command -v gh >/dev/null 2>&1; then
        echo "gh is not installed; skipped GitHub variable ${name}"
        return
    fi

    gh api \
        --method PUT \
        "repos/${github_repo}/environments/development" >/dev/null

    gh variable set "${name}" \
        --repo "${github_repo}" \
        --env development \
        --body "${value}" >/dev/null
}

gcloud services enable \
    artifactregistry.googleapis.com \
    iam.googleapis.com \
    iamcredentials.googleapis.com \
    run.googleapis.com \
    secretmanager.googleapis.com \
    sts.googleapis.com \
    --project="${project_id}"

ensure_service_account "${deployer_account_id}" "LifeAgent GitHub Actions Deployer"
ensure_service_account "${planner_runtime_account_id}" "LifeAgent Goal Planner"

add_project_role "serviceAccount:${deployer_account}" "roles/artifactregistry.writer"
add_project_role "serviceAccount:${deployer_account}" "roles/cloudsql.admin"
add_project_role "serviceAccount:${deployer_account}" "roles/run.admin"
add_project_role "serviceAccount:${deployer_account}" "roles/secretmanager.viewer"
add_service_account_user "${core_runtime_account}"
add_service_account_user "${planner_runtime_account}"

add_secret_accessor_if_exists "${db_secret_name}" "${core_runtime_account}"
add_secret_accessor_if_exists "${openai_secret_name}" "${planner_runtime_account}"

if ! gcloud iam workload-identity-pools describe "${pool_id}" \
    --project="${project_id}" \
    --location=global >/dev/null 2>&1; then
    gcloud iam workload-identity-pools create "${pool_id}" \
        --project="${project_id}" \
        --location=global \
        --display-name="GitHub Actions"
fi

if ! gcloud iam workload-identity-pools providers describe "${provider_id}" \
    --project="${project_id}" \
    --location=global \
    --workload-identity-pool="${pool_id}" >/dev/null 2>&1; then
    gcloud iam workload-identity-pools providers create-oidc "${provider_id}" \
        --project="${project_id}" \
        --location=global \
        --workload-identity-pool="${pool_id}" \
        --display-name="LifeAgent GitHub Actions" \
        --issuer-uri="https://token.actions.githubusercontent.com" \
        --attribute-mapping="google.subject=assertion.sub,attribute.repository=assertion.repository,attribute.ref=assertion.ref,attribute.environment=assertion.environment" \
        --attribute-condition="assertion.repository == '${github_repo}'"
fi

gcloud iam service-accounts add-iam-policy-binding "${deployer_account}" \
    --project="${project_id}" \
    --member="${principal_set}" \
    --role="roles/iam.workloadIdentityUser" \
    --quiet >/dev/null

set_github_variable "GCP_PROJECT_ID" "${project_id}"
set_github_variable "GCP_REGION" "${region}"
set_github_variable "ARTIFACT_REGISTRY_REPOSITORY" "${repository}"
set_github_variable "GCP_WORKLOAD_IDENTITY_PROVIDER" "${provider_resource}"
set_github_variable "GCP_DEPLOYER_SERVICE_ACCOUNT" "${deployer_account}"
set_github_variable "CORE_API_SERVICE" "${core_service}"
set_github_variable "CORE_API_RUNTIME_SERVICE_ACCOUNT" "${core_runtime_account}"
set_github_variable "GOAL_PLANNER_SERVICE" "${planner_service}"
set_github_variable "GOAL_PLANNER_RUNTIME_SERVICE_ACCOUNT" "${planner_runtime_account}"
set_github_variable "CLOUD_SQL_CONNECTION_NAME" "${sql_connection_name}"
set_github_variable "OPENAI_SECRET_NAME" "${openai_secret_name}"

echo "Development deploy identity is configured."
echo "Workload Identity Provider: ${provider_resource}"
echo "Deployer service account: ${deployer_account}"
echo "Goal Planner runtime service account: ${planner_runtime_account}"
