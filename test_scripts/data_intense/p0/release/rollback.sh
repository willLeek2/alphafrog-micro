#!/usr/bin/env bash
# T5 rollback checklist: safe rollback requiring explicit TARGET_SHA and
# operator acknowledgements. Preserves safety invariants.
# Usage: ./rollback.sh TARGET_SHA
set -euo pipefail

if [ $# -lt 1 ]; then
  echo "Usage: $0 TARGET_SHA" >&2
  echo "  TARGET_SHA  commit to roll back to (must be a valid commit, ancestor of HEAD)" >&2
  exit 2
fi

TARGET_SHA="$1"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../../.." && pwd)"
REPORT="${SCRIPT_DIR}/rollback_readiness.json"

echo "=== T5 Rollback Readiness Checklist ==="
echo "TARGET_SHA=$TARGET_SHA"
echo "PROJECT_ROOT=$PROJECT_ROOT"
cd "$PROJECT_ROOT"

HEAD_SHA=$(git rev-parse HEAD)
CHECKS="{}"

check() {
  local name="$1" pass="$2" note="${3:-}"
  CHECKS=$(echo "$CHECKS" | jq --arg n "$name" --argjson p "$pass" --arg d "$note" \
    '.[$n] = {pass: $p, note: $d}')
}

# 1. TARGET_SHA is a valid commit
if git cat-file -t "$TARGET_SHA^{commit}" >/dev/null 2>&1; then
  TARGET_RESOLVED=$(git rev-parse "$TARGET_SHA^{commit}")
  check "valid_commit" true "TARGET_SHA=$TARGET_RESOLVED"
else
  check "valid_commit" false "TARGET_SHA is not a valid commit object"
  echo "$CHECKS" | jq '.' > "$REPORT"; exit 1
fi

# 2. TARGET_SHA is an ancestor of HEAD
if git merge-base --is-ancestor "$TARGET_RESOLVED" "$HEAD_SHA" 2>/dev/null; then
  check "target_is_ancestor" true "TARGET is ancestor of HEAD ($HEAD_SHA)"
else
  check "target_is_ancestor" false "TARGET $TARGET_RESOLVED is NOT an ancestor of HEAD $HEAD_SHA"
fi

# 3. Safety invariants (operator must verify before rollback)
SAFETY_OK=true
SAFETY_ISSUES=""

POOL_ENABLED="${AF_SANDBOX_POOL_ENABLED:-false}"
if [ "$POOL_ENABLED" != "false" ]; then
  SAFETY_OK=false
  SAFETY_ISSUES="$SAFETY_ISSUES; AF_SANDBOX_POOL_ENABLED must be 'false', got '$POOL_ENABLED'"
fi

MAX_CONC="${AF_SANDBOX_CONTAINER_MAX_CONCURRENCY:-1}"
if [ "$MAX_CONC" != "1" ]; then
  SAFETY_OK=false
  SAFETY_ISSUES="$SAFETY_ISSUES; container max concurrency must be 1, got $MAX_CONC"
fi

check "safety_invariants" "$SAFETY_OK" "${SAFETY_ISSUES#; }"

# 4. Manual operator checks (must be explicitly acknowledged)
# These are guarded by environment variables; if not set, the check fails.
ADMISSION_CLOSED="${T5_ROLLBACK_ADMISSION_CLOSED:-false}"
if [ "$ADMISSION_CLOSED" = "true" ]; then
  check "admission_closed" true "operator confirmed: new admission stopped"
else
  check "admission_closed" false "set T5_ROLLBACK_ADMISSION_CLOSED=true after stopping admission"
fi

DRAIN_COMPLETE="${T5_ROLLBACK_DRAIN_COMPLETE:-false}"
if [ "$DRAIN_COMPLETE" = "true" ]; then
  check "drain_complete" true "operator confirmed: WAITING_TOOL_JOB runs drained"
else
  check "drain_complete" false "set T5_ROLLBACK_DRAIN_COMPLETE=true after draining pending runs"
fi

RESERVATION_AUDIT_OK="${T5_ROLLBACK_RESERVATION_AUDIT_OK:-false}"
if [ "$RESERVATION_AUDIT_OK" = "true" ]; then
  check "reservation_audit" true "operator confirmed: no active PENDING_TRANSFERRED reservations"
else
  check "reservation_audit" false \
    "set T5_ROLLBACK_RESERVATION_AUDIT_OK=true after verifying no active reservations (CANCELED audit anchors are safe)"
fi

DURABLE_MOUNT_OK="${T5_ROLLBACK_DURABLE_MOUNT_OK:-false}"
if [ "$DURABLE_MOUNT_OK" = "true" ]; then
  check "durable_mount" true "operator confirmed: task-store durable mount retained"
else
  check "durable_mount" false "set T5_ROLLBACK_DURABLE_MOUNT_OK=true after verifying durable mount"
fi

# Rollback procedure (informational)
echo ""
echo "=== Rollback Procedure (operator must execute) ==="
echo "1. git checkout $TARGET_RESOLVED"
echo "2. Rebuild and redeploy application artifact"
echo "3. Restore capacity ledger from durable anchor state"
echo "4. Verify recovery: startup recovery.onReady() completes"
echo "5. Re-enable admission gate when healthy"
echo ""

# Post-rollback verification
echo "=== Post-Rollback Verification ==="
echo "Run: mvn test -pl agentLangchainService -Dtest=\"ToolJobStartupDispatchRecoveryTest\""
echo "Check: admissionState=OPEN, active reservations match DB, no zombie anchors"
echo ""

# Summary
ALL_MANUAL=$(echo "$CHECKS" | jq '[.[] | select(.note | startswith("set T5_ROLLBACK_"))] | all(.pass == true)')
ALL_AUTO=$(echo "$CHECKS" | jq '[.[] | select(.note | startswith("set T5_ROLLBACK_") | not)] | all(.pass == true)')
echo "$CHECKS" | jq '.' > "$REPORT"
echo "$CHECKS" | jq '.'

if [ "$ALL_AUTO" = "true" ] && [ "$ALL_MANUAL" = "true" ]; then
  echo "=== ALL CHECKS PASS — ready for operator rollback ==="
  exit 0
elif [ "$ALL_AUTO" != "true" ]; then
  echo "=== AUTOMATED CHECKS FAILED — cannot proceed ==="
  exit 1
else
  echo "=== MANUAL OPERATOR CHECKS REQUIRED — set T5_ROLLBACK_* env vars ==="
  exit 1
fi
