#!/bin/bash
# Data Intense P0 Rollback Checklist - T5
# =======================================
# NOTE: This is a rollback CHECKLIST, not an automated script.
# The codebase has no feature flags. Rollback is via config revert + restart.
# All steps marked MANUAL require human operator.
set -euo pipefail

echo "=== Data Intense P0 Rollback Checklist ==="
echo ""

echo "[Pre-flight] Check pending WAITING_TOOL_JOB runs"
echo "  SQL: SELECT COUNT(*) FROM alphafrog_agent_run WHERE status='WAITING_TOOL_JOB';"
echo "  If >0: runs must complete or be canceled before rollback"
echo "  WARNING: rolling back status enum while runs are WAITING_TOOL_JOB is unsafe"
echo "  CHECK: MANUAL"
echo ""

echo "[1/6] Revert sandbox container config"
echo "  If new behavior causes issues, revert docker-compose.yml:"
echo "    AF_SANDBOX_POOL_ENABLED=true   (previous default)"
echo "    AF_SANDBOX_CONTAINER_MAX_CONCURRENCY=5  (previous default)"
echo "  Effect: returns to shared-container pool mode"
echo "  NOTE: pending sandbox tasks with operationId will still complete"
echo "  EXECUTE: MANUAL (edit docker-compose.yml + docker compose up -d)"
echo ""

echo "[2/6] Revert capacity properties"
echo "  Set alphafrog.data-analysis.capacity.maxUnits to high value (e.g. 100)"
echo "  Effect: effectively disables capacity gating"
echo "  EXECUTE: MANUAL (env override or application.yml)"
echo ""

echo "[3/6] Verify capacity ledger clean"
echo "  SQL: SELECT COUNT(*) FROM alphafrog_agent_run"
echo "       WHERE tool_job_anchor_json->>'anchorState' IN ('ATTACHED','PENDING','FINALIZING')"
echo "       AND tool_job_anchor_json->>'operationId' IS NOT NULL;"
echo "  Expected: 0 (all anchors consumed/cleared)"
echo "  CHECK: MANUAL"
echo ""

echo "[4/6] Verify no zombie anchors"
echo "  SQL: SELECT id, status, tool_job_anchor_json->>'anchorState' as state"
echo "       FROM alphafrog_agent_run"
echo "       WHERE tool_job_anchor_json IS NOT NULL"
echo "       AND tool_job_anchor_json <> '{}'::jsonb"
echo "       AND status IN ('COMPLETED','FAILED','CANCELED','EXPIRED');"
echo "  Expected: 0 rows (terminal runs should have cleared anchors)"
echo "  CHECK: MANUAL"
echo ""

echo "[5/6] Restart agent-langchain-service"
echo "  Command: docker compose restart agent-langchain-service"
echo "  Verify: admission=OPEN after restart"
echo "  Verify: no RECOVERING or DEGRADED state"
echo "  EXECUTE: MANUAL"
echo ""

echo "[6/6] Final confirmation"
echo "  - No pending WAITING_TOOL_JOB runs"
echo "  - Capacity ledger: 0 active units"
echo "  - No zombie anchors on terminal runs"
echo "  - Agent service: OPEN admission"
echo "  - Old config restored"
echo "  - No dirty files deleted/overwritten"
echo "  - No origin push"
echo "  STATUS: PENDING human confirmation"
echo ""

echo "=== Rollback checklist complete ==="
