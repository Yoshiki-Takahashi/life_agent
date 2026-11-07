#!/usr/bin/env bash
set -euo pipefail

project_id="${PROJECT_ID:-lifeagent-505614}"
secret_name="${FEEDBACK_GITHUB_SECRET_NAME:-lifeagent-feedback-github-token}"
token_file="${FEEDBACK_GITHUB_TOKEN_FILE:-.secrets/feedback-github-token.txt}"
github_repo="${FEEDBACK_GITHUB_REPOSITORY:-Yoshiki-Takahashi/life_agent}"
core_account="${CORE_API_RUNTIME_SERVICE_ACCOUNT:-lifeagent-core-api@${project_id}.iam.gserviceaccount.com}"

if [[ ! -s "${token_file}" ]]; then
    echo "Feedback GitHub token file is missing or empty: ${token_file}" >&2
    exit 1
fi

# Use the developer's gh login to prepare labels; never copy that token to runtime.
gh label create feedback --repo "${github_repo}" --color 0E8A16 --description 'アプリからの改善メモ' --force
gh label create needs-triage --repo "${github_repo}" --color FBCA04 --description '開発開始時に整理するメモ' --force

if gcloud secrets describe "${secret_name}" --project="${project_id}" >/dev/null 2>&1; then
    LC_ALL=C tr -d '\r\n' < "${token_file}" | gcloud secrets versions add "${secret_name}" \
        --project="${project_id}" --data-file=-
else
    LC_ALL=C tr -d '\r\n' < "${token_file}" | gcloud secrets create "${secret_name}" \
        --project="${project_id}" --data-file=-
fi
gcloud secrets add-iam-policy-binding "${secret_name}" --project="${project_id}" \
    --member="serviceAccount:${core_account}" --role=roles/secretmanager.secretAccessor --quiet >/dev/null
gh variable set FEEDBACK_GITHUB_REPOSITORY --repo "${github_repo}" --env development --body "${github_repo}"
gh variable set FEEDBACK_GITHUB_SECRET_NAME --repo "${github_repo}" --env development --body "${secret_name}"
echo "Feedback token registered. The next main deployment will enable feedback delivery."
