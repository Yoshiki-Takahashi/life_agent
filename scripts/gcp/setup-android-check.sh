#!/usr/bin/env bash
# Explicit operator action. Requires GCP IAM and GitHub environment administration.
set -euo pipefail
project_id="${PROJECT_ID:-lifeagent-505614}"
github_repo="${GITHUB_REPOSITORY:-Yoshiki-Takahashi/life_agent}"
secret_name="${CHECK_SECRET:-lifeagent-gcp-check-credentials}"
project_number="$(gcloud projects describe "$project_id" --format='value(projectNumber)')"
repo_id="$(gh api "repos/$github_repo" --jq .id)"
pool_id=github-actions
provider_id=lifeagent-android-check
account_id=lifeagent-android-check
account="$account_id@$project_id.iam.gserviceaccount.com"
provider="projects/$project_number/locations/global/workloadIdentityPools/$pool_id/providers/$provider_id"
# Harden existing deploy federation before introducing PR federation. This is essential:
# a PR can request any existing provider/service account, irrespective of workflow inputs.
deploy_condition="assertion.repository_id == '$repo_id' && assertion.ref == 'refs/heads/main' && assertion.environment == 'development' && (assertion.workflow_ref == '$github_repo/.github/workflows/deploy-dev.yml@refs/heads/main' || assertion.workflow_ref == '$github_repo/.github/workflows/manage-dev.yml@refs/heads/main') && (assertion.event_name == 'push' || assertion.event_name == 'workflow_dispatch')"
gcloud iam workload-identity-pools providers update-oidc lifeagent \
  --project="$project_id" --location=global --workload-identity-pool="$pool_id" \
  --attribute-condition="$deploy_condition"
if ! gcloud iam service-accounts describe "$account" --project="$project_id" >/dev/null 2>&1; then
  gcloud iam service-accounts create "$account_id" --project="$project_id" --display-name='Android GCP acceptance'
fi
condition="assertion.repository_id == '$repo_id' && assertion.environment == 'android-gcp-check' && assertion.workflow_ref.startsWith('$github_repo/.github/workflows/android-gcp-check.yml@') && ((assertion.event_name == 'pull_request' && assertion.ref.startsWith('refs/pull/')) || (assertion.event_name == 'workflow_dispatch' && assertion.ref == 'refs/heads/main'))"
if gcloud iam workload-identity-pools providers describe "$provider_id" --project="$project_id" --location=global --workload-identity-pool="$pool_id" >/dev/null 2>&1; then
  gcloud iam workload-identity-pools providers update-oidc "$provider_id" --project="$project_id" \
    --location=global --workload-identity-pool="$pool_id" --attribute-condition="$condition"
else
  gcloud iam workload-identity-pools providers create-oidc "$provider_id" --project="$project_id" \
    --location=global --workload-identity-pool="$pool_id" --issuer-uri=https://token.actions.githubusercontent.com \
    --attribute-mapping='google.subject=assertion.sub,attribute.repository_id=assertion.repository_id,attribute.environment=assertion.environment' \
    --attribute-condition="$condition"
fi
# Binding uses the gated environment, rather than repository-wide federation.
gcloud iam service-accounts add-iam-policy-binding "$account" --project="$project_id" \
  --member="principalSet://iam.googleapis.com/projects/$project_number/locations/global/workloadIdentityPools/$pool_id/attribute.environment/android-gcp-check" \
  --role=roles/iam.workloadIdentityUser --quiet >/dev/null
gcloud secrets add-iam-policy-binding "$secret_name" --project="$project_id" \
  --member="serviceAccount:$account" --role=roles/secretmanager.secretAccessor --quiet >/dev/null
# No Firebase admin, datastore writer or deploy role. Cleanup has get/list/delete only.
gcloud iam roles create androidCheckCleanup --project="$project_id" \
  --title='Android acceptance cleanup' --permissions=datastore.entities.get,datastore.entities.list,datastore.entities.delete \
  --stage=GA 2>/dev/null || gcloud iam roles update androidCheckCleanup --project="$project_id" \
  --permissions=datastore.entities.get,datastore.entities.list,datastore.entities.delete --stage=GA
gcloud projects add-iam-policy-binding "$project_id" --member="serviceAccount:$account" \
  --role="projects/$project_id/roles/androidCheckCleanup" --condition=None --quiet >/dev/null
gcloud projects add-iam-policy-binding "$project_id" --member="serviceAccount:$account" \
  --role=roles/run.viewer --condition=None --quiet >/dev/null
printf 'Configure approved android-gcp-check environment: provider=%s service_account=%s\n' "$provider" "$account"
printf 'Enable ANDROID_GCP_CHECK_ENABLED only after environment required reviewers and fork rejection are verified.\n'
