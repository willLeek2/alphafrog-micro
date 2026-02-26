#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8090}"
TOKEN="${TOKEN:-}"

if [[ -z "${TOKEN}" ]]; then
  echo "TOKEN is required" >&2
  exit 1
fi

AUTH_HEADER="Authorization: Bearer ${TOKEN}"

resp_default=$(curl -sS "${BASE_URL}/api/v1/market/news/today?limit=6" -H "${AUTH_HEADER}")
count_default=$(echo "${resp_default}" | jq '.data.data | length')
if [[ "${count_default}" -lt 2 ]]; then
  echo "Expected aggregated result from >=2 profile items, got ${count_default}" >&2
  exit 1
fi

resp_exa=$(curl -sS "${BASE_URL}/api/v1/market/news/today?provider=exa&limit=4" -H "${AUTH_HEADER}")
provider_exa=$(echo "${resp_exa}" | jq -r '.data.provider // ""')
if [[ "${provider_exa}" != "exa" ]]; then
  echo "Expected provider=exa response, got '${provider_exa}'" >&2
  exit 1
fi

resp_pplx=$(curl -sS "${BASE_URL}/api/v1/market/news/today?provider=perplexity&limit=4" -H "${AUTH_HEADER}")
provider_pplx=$(echo "${resp_pplx}" | jq -r '.data.provider // ""')
if [[ "${provider_pplx}" != "perplexity" ]]; then
  echo "Expected provider=perplexity response, got '${provider_pplx}'" >&2
  exit 1
fi

echo "market news profile aggregation/provider switch acceptance passed"
