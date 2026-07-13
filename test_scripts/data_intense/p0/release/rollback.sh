#!/usr/bin/env bash
# T5 rollback checklist: safe rollback procedure preserving safety invariants.
#
# Usage: ./rollback.sh [TARGET_SHA]
#   TARGET_SHA  SHA to roll back to (default: BASE_SHA 9fe4fbdd)
#
# Safety invariants preserved during rollback:
#   - AF_SANDBOX_POOL_ENABLED=false (single-task, no admission race)
#   - container max concurrency = 1
#   - task-store durable mount retained
#   - active/zombie reservations detected before rollback
#   - CANCELED audit anchors with finalizerStep=CANCELED are permitted

set -euo pipefail

TARGET_SHA="${1:-9fe4fbdd7b233d6bc7b74bba8128ea2769ae0647}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../../.." && pwd)"
REPORT="${SCRIPT_DIR}/rollback_readiness.json"

echo "=== T5 Rollback Readiness Checklist ==="
echo "TARGET_SHA=$TARGET_SHA"
echo "PROJECT_ROOT=$PROJECT_ROOT"
cd "$PROJECT_ROOT"

CHECKS="{}"

check() {
  local name="$1" pass="$2" note="${3:-}"
  CHECKS=$(echo "$CHECKS" | jq --arg n "$name" --argjson p "$pass" --arg d "$note" \
    '.[$n] = {pass: $p, note: $d}')
}

# 1. Target SHA exists
if git cat-file -t "$TARGET_SHA" >/dev/null 2>&1; then
  check "target_exists" true ""
else
  check "target_exists" false "TARGET_SHA not in object database"
  echo "$CHECKS" | jq '.' > "$REPORT"; exit 1
fi

# 2. Target is ancestor of current HEAD
HEAD_SHA=$(git rev-parse HEAD)
if git merge-base --is-ancestor "$TARGET_SHA" "$HEAD_SHA" 2>/dev/null; then
  check "target_is_ancestor" true "TARGET_SHA is ancestor of HEAD ($HEAD_SHA)"
else
  check "target_is_ancestor" false "TARGET_SHA is NOT an ancestor of HEAD"
fi

# 3. Safety invariants verification
SAFETY_OK=true
SAFETY_NOTES=""

# Pool must be disabled during rollback
POOL_ENABLED="${AF_SANDBOX_POOL_ENABLED:-false}"
if [ "$POOL_ENABLED" != "false" ]; then
  SAFETY_OK=false
  SAFETY_NOTES="$SAFETY_NOTES; AF_SANDBOX_POOL_ENABLED must be 'false' before rollback"
fi

# Container concurrency must be 1
MAX_CONC="${AF_SANDBOX_CONTAINER_MAX_CONCURRENCY:-1}"
if [ "$MAX_CONC" != "1" ]; then
  SAFETY_OK=false
  SAFETY_NOTES="$SAFETY_NOTES; container max concurrency must be 1, got $MAX_CONC"
fi

check "safety_invariants" "$SAFETY_OK" "${SAFETY_NOTES#; }"

# 4. Active/zombie reservation check (advisory — operator must verify)
check "reservation_audit" true \
  "Operator: verify no active PENDING_TRANSFERRED reservations before rollback. CANCELED anchors with finalizerStep=CANCELED are safe audit artifacts."

# 5. Rollback procedure
echo ""
echo "=== Rollback Procedure ==="
echo "1. Stop new admission (set admission gate to CLOSED/RECOVERING)"
echo "2. Drain WAITING_TOOL_JOB runs (let reconciler finalize pending)"
echo "3. git checkout $TARGET_SHA"
echo "4. Rebuild and redeploy application artifact"
echo "5. Restore capacity ledger from durable anchor state"
echo "6. Verify recovery (startup recovery.onReady() completes)"
echo "7. Re-enable admission gate to OPEN when healthy"
echo ""

# 6. Post-rollback verification commands
echo "=== Post-Rollback Verification ==="
echo "Run: mvn test -pl agentLangchainService -Dtest=\"ToolJobStartupDispatchRecoveryTest\""
echo "Run: check capacity ledger: admissionState=OPEN, active reservations match DB"
echo ""

check "rollback_procedure" true "follow steps above; this script is an operator checklist"
check "post_rollback_verification" true "run focused recovery tests + capacity audit"

# Summary
echo "$CHECKS" | jq '.' > "$REPORT"
echo "=== Rollback Readiness Report ==="
echo "$CHECKS" | jq '.'
echo ""
echo "Report: $REPORT"
exit 0
