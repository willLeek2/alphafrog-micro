#!/bin/bash
# Data Intense P0 Release Checklist - T5
# =========================================
# NOTE: This is a release CHECKLIST, not an automated deploy script.
# The codebase has no feature flags for data-analysis (alphafrog.data-analysis.*.enabled).
# Release control is via docker-compose env vars and capacity properties.
# All steps marked MANUAL require human operator with production access.
set -euo pipefail

echo "=== Data Intense P0 Release Checklist ==="
echo ""

echo "[1/7] Verify baseline"
echo "  P0_INTEGRATION_BASE_SHA=9fe4fbdd7b233d6bc7b74bba8128ea2769ae0647"
echo "  Verify: git log --oneline -1 == 9fe4fbdd"
echo "  Verify: JDK17 full regression 992/992 PASS"
echo "  VERIFY: MANUAL"
echo ""

echo "[2/7] Deploy additive proto"
echo "  Proto fields 19-23 are additive (no field number reuse)"
echo "  SandboxResourceUsage proto backward-compatible"
echo "  Command: JAVA_HOME=\$(/usr/libexec/java_home -v 17) mvn -pl pythonSandboxApi -am compile"
echo "  EXECUTE: MANUAL"
echo ""

echo "[3/7] Verify sandbox container config"
echo "  docker-compose.yml must have:"
echo "    AF_SANDBOX_POOL_ENABLED=false   (§8.8 P0)"
echo "    AF_SANDBOX_CONTAINER_MAX_CONCURRENCY=1  (§8.8 P0)"
echo "    AF_SANDBOX_TASK_STORE_PATH=/data/sandbox_tasks/state.json"
echo "  Bind mount: ./data/sandbox_tasks:/data/sandbox_tasks (directory, not single file)"
echo "  VERIFY: MANUAL (docker compose config)"
echo ""

echo "[4/7] Verify capacity configuration"
echo "  Properties (alphafrog.data-analysis.capacity.*):"
echo "    maxUnits=4, maxActive=2, maxHeavyActive=1"
echo "    maxRowsPerTask=600000, maxBytesPerTask=512MiB"
echo "    standardRowsMax=200000, standardBytesMax=32MiB"
echo "    standardMemoryLimitBytes=512MiB, heavyMemoryLimitBytes=1536MiB"
echo "  VERIFY: MANUAL (check application.yml or env overrides)"
echo ""

echo "[5/7] Small traffic smoke test"
echo "  - Create single run with small data script"
echo "  - Verify: run completes, observability shows data_analysis_observability"
echo "  - Verify: evicted_keys=0 (Redis INFO stats)"
echo "  - Verify: admissionState=OPEN (not DEGRADED)"
echo "  - Verify: no WAITING_TOOL_JOB runs stuck"
echo "  EXECUTE: MANUAL"
echo ""

echo "[6/7] Run P0 fault harness"
echo "  Command: bash test_scripts/data_intense/p0/faults/harness.sh"
echo "  Verify: P0-01 fast-path automated cases PASS"
echo "  Verify: MANUAL cases have documented results"
echo "  EXECUTE: MANUAL"
echo ""

echo "[7/7] Final confirmation"
echo "  All automated cases: PASS"
echo "  All manual cases: documented with evidence"
echo "  No origin push (frog_wch decides when to push)"
echo "  STATUS: PENDING human approval"
echo ""

echo "=== Release checklist complete ==="
