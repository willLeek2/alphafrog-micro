#!/usr/bin/env bash
# T5 deploy checklist: validates candidate SHA matches current HEAD,
# runs contract harness, produces deployment readiness report.
# Usage: ./deploy.sh [CANDIDATE_SHA]
#   CANDIDATE_SHA must equal current HEAD (harness only tests the checkout).
#   If omitted, defaults to HEAD.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../../.." && pwd)"
HARNESS="${SCRIPT_DIR}/../faults/harness.sh"
REPORT="${SCRIPT_DIR}/deploy_readiness.json"

cd "$PROJECT_ROOT"
HEAD_SHA=$(git rev-parse HEAD)
CANDIDATE_SHA="${1:-$HEAD_SHA}"

echo "=== T5 Deploy Readiness Checklist ==="
echo "CANDIDATE_SHA=$CANDIDATE_SHA"
echo "HEAD=$HEAD_SHA"
echo "PROJECT_ROOT=$PROJECT_ROOT"

CHECKS="{}"

check() {
  local name="$1" pass="$2" note="${3:-}"
  CHECKS=$(echo "$CHECKS" | jq --arg n "$name" --argjson p "$pass" --arg d "$note" \
    '.[$n] = {pass: $p, note: $d}')
}

# 1. CANDIDATE_SHA is a valid commit
if git cat-file -t "$CANDIDATE_SHA^{commit}" >/dev/null 2>&1; then
  check "valid_commit" true ""
else
  check "valid_commit" false "CANDIDATE_SHA is not a valid commit object"
  echo "$CHECKS" | jq '.' > "$REPORT"; exit 1
fi

# 2. CANDIDATE_SHA equals HEAD (harness tests the checkout)
if [ "$(git rev-parse "$CANDIDATE_SHA^{commit}")" = "$HEAD_SHA" ]; then
  check "matches_head" true "CANDIDATE_SHA equals HEAD"
else
  check "matches_head" false "CANDIDATE_SHA ($CANDIDATE_SHA) != HEAD ($HEAD_SHA)"
  echo "$CHECKS" | jq '.' > "$REPORT"; exit 1
fi

# 3. BASE ancestry
BASE_SHA="9fe4fbdd7b233d6bc7b74bba8128ea2769ae0647"
if git merge-base --is-ancestor "$BASE_SHA" "$HEAD_SHA" 2>/dev/null; then
  check "base_ancestor" true "BASE $BASE_SHA is ancestor"
else
  check "base_ancestor" false "HEAD is not a descendant of BASE"
fi

# 4. Tree clean (allow harness_result.json and deploy_readiness.json)
DIRTY=$(git status --porcelain --untracked-files=all | grep -vE '(harness_result|deploy_readiness)\.json' || true)
if [ -z "$DIRTY" ]; then
  check "tree_clean" true ""
else
  check "tree_clean" false "unexpected dirty files"
fi

# 5. diff-check
if git diff --check "$BASE_SHA".."$HEAD_SHA" >/dev/null 2>&1; then
  check "diff_check" true ""
else
  check "diff_check" false "whitespace errors or conflict markers"
fi

# 6. Docker Compose config
if [ -f docker-compose.yml ]; then
  if docker compose config --quiet 2>/dev/null; then
    check "docker_compose_config" true ""
  else
    check "docker_compose_config" false "validation failed or Docker not available"
  fi
else
  check "docker_compose_config" false "docker-compose.yml not found"
fi

# 7. Contract harness
if [ -x "$HARNESS" ]; then
  if "$HARNESS" "$HEAD_SHA" > /dev/null 2>&1; then
    check "contract_harness" true "harness PASS"
  else
    check "contract_harness" false "harness FAILED"
  fi
else
  check "contract_harness" false "harness.sh not found or not executable"
fi

# Summary
ALL_OK=$(echo "$CHECKS" | jq 'all(.[]; .pass == true)')
echo "$CHECKS" | jq '.' > "$REPORT"
echo "$CHECKS" | jq '.'

if [ "$ALL_OK" = "true" ]; then
  echo "=== ALL CHECKS PASS — ready for operator deployment ==="
  exit 0
else
  echo "=== SOME CHECKS FAILED — review $REPORT before deploying ==="
  exit 1
fi
