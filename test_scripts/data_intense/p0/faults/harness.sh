#!/usr/bin/env bash
# T5 contract harness: validates exact candidate SHA, runs JDK17 Maven fixtures
# + Python tests on the current checkout, produces machine-readable JSON summary.
# REQUIRED failures -> non-zero exit. SUPPORTING_ONLY cases are informational.
#
# Python suites use the ambient python3 interpreter; they require pydantic + pandas.
# The test files use unittest.TestCase and are executed via `python -m unittest`.
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

run_python_unittest() {
  local name="$1" module="$2" required="${3:-true}"
  echo "--- $name ---"
  local start_ts=$(date +%s)
  local ec=0
  ${PYTHON_BIN:-python3} -m unittest "$module" -q 2>&1 || ec=$?
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

# ---- Python env check (cached) ----
# Uses ambient python3; requires pydantic + pandas (not pytest — tests use unittest).
PYTHON_DIR="${PROJECT_ROOT}/pythonSandboxService"
PYTHON_BIN=""
PYTHON_OK=false
PYTHON_CHECKED=false

check_python_env() {
  if [ "$PYTHON_CHECKED" = "true" ]; then
    [ "$PYTHON_OK" = "true" ] && return 0 || return 1
  fi
  PYTHON_CHECKED=true

  # Resolve interpreter (respect PYTHON_BIN override from caller)
  local py="${PYTHON_BIN:-python3}"
  if ! command -v "$py" >/dev/null 2>&1; then
    return 1
  fi
  if ! "$py" -c "import pydantic, pandas" 2>/dev/null; then
    return 1
  fi
  PYTHON_BIN="$py"
  PYTHON_OK=true
  return 0
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
PYTHON_ERR=""
if [ ! -f "${PYTHON_DIR}/tests/test_retry_classification.py" ]; then
  PYTHON_ERR="test file not found"
elif ! check_python_env; then
  PYTHON_ERR="python env missing pydantic or pandas"
fi
if [ -n "$PYTHON_ERR" ]; then
  record_required_failure "Python_RetryClassification" "$PYTHON_ERR"
else
  echo "--- Python_RetryClassification ---"
  pushd "$PYTHON_DIR" > /dev/null
  run_python_unittest "Python_RetryClassification" "tests.test_retry_classification" true
  popd > /dev/null
fi

# === Python: benchmark tools (REQUIRED) ===
BENCHMARK_TEST="${PROJECT_ROOT}/test_scripts/data_intense/p0/benchmarks/test_benchmark_tools.py"
BENCHMARK_ERR=""
if [ ! -f "$BENCHMARK_TEST" ]; then
  BENCHMARK_ERR="test file not found"
elif ! check_python_env; then
  BENCHMARK_ERR="python env missing pydantic or pandas"
fi
if [ -n "$BENCHMARK_ERR" ]; then
  record_required_failure "Python_BenchmarkTools" "$BENCHMARK_ERR"
else
  echo "--- Python_BenchmarkTools ---"
  export PYTHONPATH="${PYTHON_DIR}:${PROJECT_ROOT}"
  pushd "$PROJECT_ROOT" > /dev/null
  run_python_unittest "Python_BenchmarkTools" "test_scripts.data_intense.p0.benchmarks.test_benchmark_tools" true
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
