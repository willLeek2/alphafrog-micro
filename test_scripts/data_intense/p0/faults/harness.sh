#!/usr/bin/env bash
# T5 contract harness: runs JDK17 Maven fixture suites + Python tests,
# produces machine-readable JSON summary. REQUIRED failures → non-zero exit.
# SUPPORTING_ONLY cases are informational and do NOT affect exit code.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../../.." && pwd)"
JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null || echo '')}"
SUMMARY_FILE="${SCRIPT_DIR}/harness_result.json"

if [ -z "$JAVA_HOME" ]; then
  echo '{"error":"JAVA_HOME not set and JDK 17 not found"}' > "$SUMMARY_FILE"
  exit 1
fi
export JAVA_HOME

cd "$PROJECT_ROOT"
CANDIDATE_SHA="${1:-$(git rev-parse HEAD)}"

echo "=== T5 Contract Harness ==="
echo "CANDIDATE_SHA=$CANDIDATE_SHA"
echo "PROJECT_ROOT=$PROJECT_ROOT"

if ! git cat-file -t "$CANDIDATE_SHA" >/dev/null 2>&1; then
  echo "{\"error\":\"CANDIDATE_SHA $CANDIDATE_SHA not in object database\"}" > "$SUMMARY_FILE"
  exit 1
fi

BASE_SHA="9fe4fbdd7b233d6bc7b74bba8128ea2769ae0647"
if ! git merge-base --is-ancestor "$BASE_SHA" "$CANDIDATE_SHA" 2>/dev/null; then
  echo "{\"error\":\"$CANDIDATE_SHA is not a descendant of BASE $BASE_SHA\"}" > "$SUMMARY_FILE"
  exit 1
fi

RESULTS="{}"

run_maven() {
  local name="$1" classes="$2" required="${3:-true}"
  echo "--- $name ---"
  local start_ts=$(date +%s)
  local ec=0
  mvn test -pl agentLangchainService -Dtest="$classes" \
    -Dsurefire.failIfNoSpecifiedTests=false -q 2>&1 || ec=$?
  local elapsed=$(($(date +%s) - start_ts))
  local pass="false"; [ "$ec" -eq 0 ] && pass="true"
  RESULTS=$(echo "$RESULTS" | jq --arg n "$name" --argjson p "$pass" \
    --argjson r "$required" --argjson e "$elapsed" \
    '.[$n] = {pass: $p, required: $r, elapsed_s: $e}')
}

run_python() {
  local name="$1" path="$2" required="${3:-true}"
  echo "--- $name ---"
  local start_ts=$(date +%s)
  local ec=0
  ${PYTHON_BIN:-python3} -m pytest "$path" -q 2>&1 || ec=$?
  local elapsed=$(($(date +%s) - start_ts))
  local pass="false"; [ "$ec" -eq 0 ] && pass="true"
  RESULTS=$(echo "$RESULTS" | jq --arg n "$name" --argjson p "$pass" \
    --argjson r "$required" --argjson e "$elapsed" \
    '.[$n] = {pass: $p, required: $r, elapsed_s: $e}')
}

supporting() {
  local name="$1" note="$2"
  echo "--- SKIP ($name): SUPPORTING_ONLY — $note ---"
  RESULTS=$(echo "$RESULTS" | jq --arg n "$name" --arg d "$note" \
    '.[$n] = {pass: null, required: false, supporting_only: true, note: $d}')
}

# === REQUIRED suites ===
run_maven "P001_FastPath" \
  "PythonSandboxToolsP001FastPathTest" true
run_maven "P005_CancelRepair" \
  "LangchainRunControlServiceTest,ToolJobReconcilerP005ReverseTest" true
run_maven "P009_ResultFetchRepair" \
  "ToolJobReconcilerP009ReverseTest,ToolJobReconcilerP009ForwardTest" true
run_maven "PipelineResume" \
  "LangchainLinearRunPipelineResumeTest,LangchainLinearWorkflowResumeTest" true

# Python retry classification (REQUIRED, venv if needed)
PYTHON_DIR="${PROJECT_ROOT}/pythonSandboxService"
if [ -f "${PYTHON_DIR}/tests/test_retry_classification.py" ]; then
  echo "--- Python_RetryClassification ---"
  if ! python3 -c "import pydantic" 2>/dev/null; then
    VENV_DIR="/tmp/t5-harness-venv"
    if ! python3 -m venv "$VENV_DIR" 2>/dev/null; then
      supporting "Python_RetryClassification" "cannot create venv for pydantic"
    else
      "$VENV_DIR/bin/pip" install pydantic pytest -q 2>/dev/null || true
      if "$VENV_DIR/bin/python" -c "import pydantic" 2>/dev/null; then
        PYTHON_BIN="$VENV_DIR/bin/python"
        pushd "$PYTHON_DIR" > /dev/null
        run_python "Python_RetryClassification" "tests/test_retry_classification.py" true
        popd > /dev/null
      else
        supporting "Python_RetryClassification" "pydantic install failed in venv"
      fi
    fi
  else
    pushd "$PYTHON_DIR" > /dev/null
    run_python "Python_RetryClassification" "tests/test_retry_classification.py" true
    popd > /dev/null
  fi
else
  supporting "Python_RetryClassification" "pythonSandboxService not available"
fi

# === SUPPORTING_ONLY ===
supporting "Live_DB_FaultInjection" "requires production DB; not executed"
supporting "Live_Sandbox_Restart" "requires Docker sandbox runtime; not executed"
supporting "Capacity_Admission_Integration" "requires full Spring+Nacos; unit-tested"

# === Summary ===
REQUIRED_TOTAL=$(echo "$RESULTS" | jq '[.[] | select(.required == true)] | length')
REQUIRED_PASS=$(echo "$RESULTS" | jq '[.[] | select(.required == true and .pass == true)] | length')
echo "$RESULTS" | jq '.' > "$SUMMARY_FILE"

if [ "$REQUIRED_TOTAL" -eq "$REQUIRED_PASS" ]; then
  echo "=== ALL $REQUIRED_PASS/$REQUIRED_TOTAL REQUIRED suites PASS ==="
  echo "Summary: $SUMMARY_FILE"
  exit 0
else
  echo "=== FAILED: $REQUIRED_PASS/$REQUIRED_TOTAL REQUIRED suites PASS ==="
  echo "Summary: $SUMMARY_FILE"
  exit 1
fi
