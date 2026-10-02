#!/usr/bin/env bash
set -euo pipefail

project_id="${PROJECT_ID:-lifeagent-505614}"
secret_name="${OPENAI_SECRET_NAME:-lifeagent-openai-api-key}"
key_file="${OPENAI_API_KEY_FILE:-.secrets/openai-api-key.txt}"

if [[ ! -s "${key_file}" ]]; then
    echo "OpenAI API key file is missing or empty: ${key_file}" >&2
    exit 1
fi

if gcloud secrets describe "${secret_name}" --project="${project_id}" >/dev/null 2>&1; then
    LC_ALL=C tr -d '\r\n' < "${key_file}" | gcloud secrets versions add "${secret_name}" \
        --project="${project_id}" \
        --data-file=-
else
    LC_ALL=C tr -d '\r\n' < "${key_file}" | gcloud secrets create "${secret_name}" \
        --project="${project_id}" \
        --data-file=-
fi

./scripts/gcp/setup-dev-deploy.sh

echo "OpenAI API key was registered in Secret Manager without trailing newlines."
