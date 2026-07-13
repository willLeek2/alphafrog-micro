#!/usr/bin/env bash
# T5 contract harness: validates exact candidate SHA, runs JDK17 Maven fixtures
# + Python tests on the current checkout, produces machine-readable JSON summary.
# REQUIRED failures -> non-zero exit. SUPPORTING_ONLY cases are informational.
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
echo "HEAD=$(git rev-parse HEAD)"

if ! git cat-file -t "$CANDIDATE_SHA^{commit}" >/dev/null 2>&1; then
  echo "{\"error\":\"CANDIDATE_SHA $CANDIDATE_SHA is not a valid commit\"}" > "$SUMMARY_FILE"
  exit 1
fi
HEAD_SHA=$(git rev-parse HEAD)
if [ "$(git rev-parse "$CANDIDATE_SHA^{commit}")" != "$HEAD_SHA" ]; then
  echo "{\"error\":\"CANDIDATE_SHA $CANDIDATE_SHA is not HEAD ($HEAD_SHA)\"}" > "$SUMMARY_FILE"
  exit 1
fi

DIRTY=$(git status --porcelain --untracked-files=all | grep -v 'harness_result.json' || true)
if [ -n "$DIRTY" ]; then
  echo "{\"error\":\"working tree not clean at HEAD $HEAD_SHA\"}" > "$SUMMARY_FILE"
  exit 1
fi

BASE_SHA="9fe4fbdd7b233d6bc7b74bba8128ea2769ae0647"
if ! git merge-base --is-ancestor "$BASE_SHA" "$HEAD_SHA" 2>/dev/null; then
  echo "{\"error\":\"HEAD $HEAD_SHA is not a descendant of BASE $BASE_SHA\"}" > "$SUMMARY_FILE"
  exit 1
fi

RESULTS="{}"

record_required_failure() {
  local name="$1" note="$2"
  echo "--- FAIL ($name): $note ---"
  RESULTS=$(echo "$RESULTS" | jq --arg n "$name" --arg d "$note" \
    '.[$n] = {pass: false, required: true, note: $d}')
}

run_maven() {
  local name="$1" classes="$2" required="${3:-true}"
  echo "--- $name ---"
  local start_ts=$(date +%s)
  local ec=0
  mvn test -pl agentLangchainService -am -Dtest="$classes" \
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

# ---- Unified Python environment setup (cached, isolated, strict) ----
PYTHON_DIR="${PROJECT_ROOT}/pythonSandboxService"
REQUIREMENTS_FILE="${PYTHON_DIR}/requirements.txt"
PYTHON_BIN=""
PYTHON_OK=false
PYTHON_SETUP_ATTEMPTED=false
VENV_DIR=""

cleanup_venv() {
  if [ -n "$VENV_DIR" ] && [ -d "$VENV_DIR" ]; then
    rm -rf "$VENV_DIR"
  fi
}

setup_python_env() {
  # Already attempted — return cached result
  if [ "$PYTHON_SETUP_ATTEMPTED" = "true" ]; then
    [ "$PYTHON_OK" = "true" ] && return 0 || return 1
  fi
  PYTHON_SETUP_ATTEMPTED=true

  # Try ambient python3: must have pydantic + pytest + pandas ALL present
  if python3 -c "import pydantic, pytest, pandas" 2>/dev/null; then
    PYTHON_BIN="python3"
    PYTHON_OK=true
    return 0
  fi

  # Build isolated venv with unique path (safe for parallel runners)
  if ! command -v mktemp >/dev/null 2>&1; then
    return 1
  fi
  VENV_DIR=$(mktemp -d "${TMPDIR:-/tmp}/t5-harness-venv.XXXXXX")
  trap cleanup_venv EXIT

  echo "--- Python env: building venv at $VENV_DIR ---"
  if ! python3 -m venv "$VENV_DIR" 2>/dev/null; then
    return 1
  fi

  # Strict: requirements.txt must exist and install must succeed
  if [ ! -f "$REQUIREMENTS_FILE" ]; then
    echo "--- Python env: ERROR — $REQUIREMENTS_FILE not found ---"
    return 1
  fi
  if ! "$VENV_DIR/bin/python" -m pip install -r "$REQUIREMENTS_FILE" pytest pandas -q 2>/dev/null; then
    echo "--- Python env: ERROR — pip install failed ---"
    return 1
  fi

  # Final three-import check
  if "$VENV_DIR/bin/python" -c "import pydantic, pytest, pandas" 2>/dev/null; then
    PYTHON_BIN="$VENV_DIR/bin/python"
    PYTHON_OK=true
    return 0
  fi
  return 1
}

# === REQUIRED Java suites ===
run_maven "P001_FastPath" \
  "PythonSandboxToolsP001FastPathTest" true
run_maven "P005_CancelRepair" \
  "LangchainRunControlServiceTest,ToolJobReconcilerP005ReverseTest" true
run_maven "P009_ResultFetchRepair" \
  "ToolJobReconcilerP009ReverseTest,ToolJobReconcilerP009ForwardTest" true
run_maven "PipelineResume" \
  "LangchainLinearRunPipelineResumeTest,LangchainLinearWorkflowResumeTest" true
run_maven "T5_FaultFixtures" \
  "ToolJobFinalizerP001Test,ToolJobFinalizerP002Test,ToolJobReconcilerP004Test,ToolJobFinalizerP006Test" true

# === Python: retry classification (REQUIRED) ===
PYTHON_ERROR_MSG=""
if [ ! -f "${PYTHON_DIR}/tests/test_retry_classification.py" ]; then
  PYTHON_ERROR_MSG="test file not found"
fi
if [ -z "$PYTHON_ERROR_MSG" ] && ! setup_python_env; then
  PYTHON_ERROR_MSG="unified Python env unavailable (need pydantic + pytest + pandas)"
fi
if [ -n "$PYTHON_ERROR_MSG" ]; then
  record_required_failure "Python_RetryClassification" "$PYTHON_ERROR_MSG"
else
  echo "--- Python_RetryClassification ---"
  pushd "$PYTHON_DIR" > /dev/null
  run_python "Python_RetryClassification" "tests/test_retry_classification.py" true
  popd > /dev/null
fi

# === Python: benchmark tools (REQUIRED) ===
BENCHMARK_TEST="${PROJECT_ROOT}/test_scripts/data_intense/p0/benchmarks/test_benchmark_tools.py"
BENCHMARK_ERROR_MSG=""
if [ ! -f "$BENCHMARK_TEST" ]; then
  BENCHMARK_ERROR_MSG="test file not found"
fi
if [ -z "$BENCHMARK_ERROR_MSG" ] && ! setup_python_env; then
  BENCHMARK_ERROR_MSG="unified Python env unavailable (need pydantic + pytest + pandas)"
fi
if [ -n "$BENCHMARK_ERROR_MSG" ]; then
  record_required_failure "Python_BenchmarkTools" "$BENCHMARK_ERROR_MSG"
else
  echo "--- Python_BenchmarkTools ---"
  export PYTHONPATH="${PYTHON_DIR}:${PROJECT_ROOT}"
  pushd "$PROJECT_ROOT" > /dev/null
  run_python "Python_BenchmarkTools" "$BENCHMARK_TEST" true
  popd > /dev/null
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
