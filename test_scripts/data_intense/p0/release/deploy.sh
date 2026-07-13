#!/usr/bin/env bash
# T5 deploy checklist: validates candidate SHA, runs contract harness,
# and produces a deployment readiness report.
#
# Usage: ./deploy.sh [CANDIDATE_SHA]
#   CANDIDATE_SHA  SHA to deploy (default: current HEAD)
#
# This is an OPERATOR CHECKLIST, not an automatic deployer.
# It validates preconditions and runs the contract harness.
# Actual deployment must be performed by an operator.

set -euo pipefail

CANDIDATE_SHA="${1:-$(git rev-parse HEAD)}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../../.." && pwd)"
HARNESS="${SCRIPT_DIR}/../faults/harness.sh"
REPORT="${SCRIPT_DIR}/deploy_readiness.json"

echo "=== T5 Deploy Readiness Checklist ==="
echo "CANDIDATE_SHA=$CANDIDATE_SHA"
echo "PROJECT_ROOT=$PROJECT_ROOT"
cd "$PROJECT_ROOT"

BASE_SHA="9fe4fbdd7b233d6bc7b74bba8128ea2769ae0647"
CHECKS="{}"

check() {
  local name="$1" pass="$2" note="${3:-}"
  CHECKS=$(echo "$CHECKS" | jq --arg n "$name" --argjson p "$pass" --arg d "$note" \
    '.[$n] = {pass: $p, note: $d}')
}

# 1. Object existence
if git cat-file -t "$CANDIDATE_SHA" >/dev/null 2>&1; then
  check "object_exists" true ""
else
  check "object_exists" false "SHA not in object database"
  echo "$CHECKS" | jq '.' > "$REPORT"
  exit 1
fi

# 2. Base ancestry
if git merge-base --is-ancestor "$BASE_SHA" "$CANDIDATE_SHA" 2>/dev/null; then
  check "base_ancestor" true "BASE_SHA=$BASE_SHA is ancestor of CANDIDATE_SHA"
else
  check "base_ancestor" false "CANDIDATE_SHA is NOT a descendant of BASE_SHA"
fi

# 3. Tree clean at CANDIDATE_SHA
CLEAN=true
if [ -n "$(git diff --name-only "$CANDIDATE_SHA" 2>/dev/null || true)" ]; then
  CLEAN=false
fi
if [ -n "$(git diff --cached --name-only "$CANDIDATE_SHA" 2>/dev/null || true)" ]; then
  CLEAN=false
fi
check "tree_clean" "$CLEAN" "working tree clean at CANDIDATE_SHA"

# 4. diff-check (no conflict markers)
if git diff --check "$BASE_SHA".."$CANDIDATE_SHA" >/dev/null 2>&1; then
  check "diff_check" true "no whitespace errors or conflict markers"
else
  check "diff_check" false "whitespace errors or conflict markers found"
fi

# 5. Docker Compose config validation
if [ -f docker-compose.yml ]; then
  if docker compose config --quiet 2>/dev/null; then
    check "docker_compose_config" true ""
  else
    check "docker_compose_config" false "docker compose config validation failed or Docker not available"
  fi
else
  check "docker_compose_config" false "docker-compose.yml not found"
fi

# 6. Contract harness
HARNESS_OK=false
HARNESS_NOTE=""
if [ -x "$HARNESS" ]; then
  if "$HARNESS" "$CANDIDATE_SHA" > /dev/null 2>&1; then
    HARNESS_OK=true
  else
    HARNESS_NOTE="harness exit non-zero"
  fi
else
  HARNESS_NOTE="harness.sh not found or not executable"
fi
check "contract_harness" "$HARNESS_OK" "$HARNESS_NOTE"

# Summary
ALL_OK=$(echo "$CHECKS" | jq 'all(.[]; .pass == true)')
echo "$CHECKS" | jq '.' > "$REPORT"

echo "=== Deploy Readiness Report ==="
echo "$CHECKS" | jq '.'

if [ "$ALL_OK" = "true" ]; then
  echo "=== ALL CHECKS PASS — ready for operator deployment ==="
  exit 0
else
  echo "=== SOME CHECKS FAILED — review $REPORT before deploying ==="
  exit 1
fi
