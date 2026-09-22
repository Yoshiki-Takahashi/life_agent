#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repository_root="$(cd "${script_dir}/../.." && pwd)"
config_file="${1:-${repository_root}/infra/gcp/budget-alerts.json}"

if ! command -v jq >/dev/null 2>&1; then
    echo "jq is required to read ${config_file}." >&2
    exit 1
fi

project_id="$(jq -er '.projectId' "${config_file}")"
currency="$(jq -er '.currency' "${config_file}")"

if [[ "${project_id}" != "lifeagent-505614" ]]; then
    echo "Unexpected project ID in ${config_file}: ${project_id}" >&2
    exit 1
fi

billing_account="$(gcloud billing projects describe "${project_id}" \
    --format='value(billingAccountName)')"
project_number="$(gcloud projects describe "${project_id}" \
    --format='value(projectNumber)')"
if [[ -z "${billing_account}" ]]; then
    echo "Project ${project_id} is not linked to a billing account." >&2
    exit 1
fi

existing_budgets="$(gcloud billing budgets list \
    --billing-account="${billing_account}" \
    --format=json)"

while IFS= read -r budget; do
    display_name="$(jq -r '.displayName' <<<"${budget}")"
    existing_name="$(jq -r --arg display_name "${display_name}" \
        '.[] | select(.displayName == $display_name) | .name' \
        <<<"${existing_budgets}" | head -n 1)"

    if [[ -n "${existing_name}" ]]; then
        expected="$(jq -c \
            --arg currency "${currency}" \
            --arg project "projects/${project_number}" \
            '{
                amount: .amount,
                currency: $currency,
                projects: [$project],
                services: (.services | sort),
                thresholds: ([.thresholds[] | {
                    percent,
                    basis
                }] | sort_by(.percent, .basis))
            }' <<<"${budget}")"
        actual="$(jq -c \
            --arg display_name "${display_name}" \
            '[.[] | select(.displayName == $display_name)][0] | {
                amount: (.amount.specifiedAmount.units | tonumber),
                currency: .amount.specifiedAmount.currencyCode,
                projects: (.budgetFilter.projects | sort),
                services: ((.budgetFilter.services // []) | sort),
                thresholds: ([.thresholdRules[] | {
                    percent: .thresholdPercent,
                    basis: (.spendBasis | ascii_downcase | gsub("_"; "-"))
                }] | sort_by(.percent, .basis))
            }' <<<"${existing_budgets}")"

        if [[ "${actual}" != "${expected}" ]]; then
            echo "Existing budget differs from ${config_file}: ${display_name}" >&2
            echo "Refusing to create a duplicate. Reconcile or rename the existing budget." >&2
            exit 1
        fi

        echo "Budget already matches configuration: ${display_name}"
        continue
    fi

    amount="$(jq -r '.amount' <<<"${budget}")"
    services="$(jq -r '.services | join(",")' <<<"${budget}")"
    command=(
        gcloud billing budgets create
        "--billing-account=${billing_account}"
        "--display-name=${display_name}"
        "--budget-amount=${amount}${currency}"
        "--calendar-period=month"
        "--filter-projects=projects/${project_id}"
    )

    if [[ -n "${services}" ]]; then
        command+=("--filter-services=${services}")
    fi

    while IFS=$'\t' read -r percent basis; do
        command+=("--threshold-rule=percent=${percent},basis=${basis}")
    done < <(jq -r '.thresholds[] | [.percent, .basis] | @tsv' <<<"${budget}")

    echo "Creating alerts-only budget: ${display_name}"
    "${command[@]}"
done < <(jq -c '.alertsOnlyBudgets[]' "${config_file}")

gcloud billing budgets list \
    --billing-account="${billing_account}" \
    --format='table(displayName,amount.specifiedAmount.currencyCode,amount.specifiedAmount.units)'
