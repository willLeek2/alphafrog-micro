#!/bin/bash
# Data Intense P0 Fault Harness - T5
# Real fault injection harness using docker exec + curl + jq
# Base: P0_INTEGRATION_BASE_SHA=9fe4fbdd7b233d6bc7b74bba8128ea2769ae0647
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RESULTS_DIR="${SCRIPT_DIR}/results"
mkdir -p "$RESULTS_DIR"
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
RESULTS_FILE="${RESULTS_DIR}/p0-fault-matrix-${TIMESTAMP}.json"

# --------------- Config from docker-compose.yml ---------------
PG_HOST="${PG_HOST:-host.docker.internal}"
PG_PORT="${PG_PORT:-5432}"
PG_USER="${PG_USER:-postgres}"
PG_DB="${PG_DB:-alphafrog_agent}"
REDIS_CONTAINER="${REDIS_CONTAINER:-alphafrog-redis}"
SANDBOX_SERVICE="${SANDBOX_SERVICE:-python-sandbox-service}"
FRONTEND_URL="${FRONTEND_URL:-http://localhost:8090}"
AGENT_LANGCHAIN="${AGENT_LANGCHAIN:-alphafrog-agent-langchain-service}"

# --------------- Helpers ---------------
pg_query() {
  docker exec "$PG_HOST" psql -U "$PG_USER" -d "$PG_DB" -t -A -c "$1" 2>/dev/null || echo "PG_ERROR"
}

redis_cli() {
  docker exec "$REDIS_CONTAINER" redis-cli "$@" 2>/dev/null || echo "REDIS_ERROR"
}

api_create_run() {
  local user_id=$1 message=$2
  curl -s -X POST "${FRONTEND_URL}/api/agent/runs" \
    -H "Content-Type: application/json" \
    -d "{\"userId\":\"${user_id}\",\"message\":\"${message}\"}" 2>/dev/null || echo '{"id":""}'
}

api_get_status() {
  local run_id=$1
  curl -s "${FRONTEND_URL}/api/agent/runs/${run_id}/status?userId=p0-harness" 2>/dev/null || echo '{}'
}

api_get_result() {
  local run_id=$1
  curl -s "${FRONTEND_URL}/api/agent/runs/${run_id}/result?userId=p0-harness" 2>/dev/null || echo '{}'
}

api_pause_run() {
  local run_id=$1
  curl -s -X POST "${FRONTEND_URL}/api/agent/runs/${run_id}:pause?userId=p0-harness" 2>/dev/null || echo '{}'
}

api_cancel_run() {
  local run_id=$1
  curl -s -X POST "${FRONTEND_URL}/api/agent/runs/${run_id}:cancel?userId=p0-harness" 2>/dev/null || echo '{}'
}

# Anchor queries using real ToolJobAnchor field names
get_anchor_field() {
  local run_id=$1 field=$2
  pg_query "SELECT tool_job_anchor_json->>'${field}' FROM alphafrog_agent_run WHERE id='${run_id}';"
}

get_anchor_nested() {
  local run_id=$1 path=$2
  pg_query "SELECT tool_job_anchor_json#>>'${path}' FROM alphafrog_agent_run WHERE id='${run_id}';"
}

get_reservation_field() {
  local run_id=$1 field=$2
  pg_query "SELECT (tool_job_anchor_json->'reservationJson')::jsonb->>'${field}' FROM alphafrog_agent_run WHERE id='${run_id}';"
}

get_run_status() {
  local run_id=$1
  pg_query "SELECT status FROM alphafrog_agent_run WHERE id='${run_id}';"
}

count_events() {
  local run_id=$1 event_type=$2
  pg_query "SELECT COUNT(*) FROM alphafrog_agent_run_event WHERE run_id='${run_id}' AND event_type='${event_type}';"
}

# Active units: sum capacityUnits from reservationJson in non-CONSUMED anchors
get_active_units() {
  pg_query "SELECT COALESCE(SUM(((tool_job_anchor_json->'reservationJson')::jsonb->>'capacityUnits')::int), 0) FROM alphafrog_agent_run WHERE tool_job_anchor_json->>'anchorState' IN ('ATTACHED','PENDING','TERMINAL','FINALIZING') AND tool_job_anchor_json->>'operationId' IS NOT NULL;"
}

pool_enabled() {
  docker exec "$AGENT_LANGCHAIN" sh -c 'echo $AF_SANDBOX_POOL_ENABLED' 2>/dev/null || echo "false"
}

container_max_concurrency() {
  docker exec "$AGENT_LANGCHAIN" sh -c 'echo $AF_SANDBOX_CONTAINER_MAX_CONCURRENCY' 2>/dev/null || echo "1"
}

# --------------- Fault Injection ---------------
fault_kill_agent_langchain() {
  echo "  [FAULT] Killing agent-langchain-service..."
  docker kill "$AGENT_LANGCHAIN" 2>/dev/null || true
}

fault_start_agent_langchain() {
  echo "  [FAULT] Starting agent-langchain-service..."
  docker start "$AGENT_LANGCHAIN" 2>/dev/null || true
  sleep 10  # wait for startup + recovery
}

fault_evict_redis_pending() {
  local run_id=$1
  echo "  [FAULT] Evicting Redis pending cache for run ${run_id}..."
  redis_cli DEL "agent:run:${run_id}:pending_tool_job" 2>/dev/null || true
  redis_cli ZREM "agent:tool-job:due" "agent:run:${run_id}:pending_tool_job" 2>/dev/null || true
}

fault_kill_sandbox() {
  echo "  [FAULT] Killing python-sandbox-service..."
  docker kill "$SANDBOX_SERVICE" 2>/dev/null || true
}

fault_start_sandbox() {
  echo "  [FAULT] Starting python-sandbox-service..."
  docker start "$SANDBOX_SERVICE" 2>/dev/null || true
  sleep 5
}

fault_crash_mid_operation() {
  local run_id=$1 stage=$2
  echo "  [FAULT] Simulating crash at stage=${stage} for run ${run_id}..."
  fault_kill_agent_langchain
  sleep 2
  fault_start_agent_langchain
}

# --------------- Assertions ---------------
declare -a ASSERTIONS

assert_eq() {
  local name=$1 expected=$2 observed=$3
  local pass="FAIL"
  [ "$expected" = "$observed" ] && pass="PASS"
  ASSERTIONS+=("{\"name\":\"${name}\",\"expected\":\"${expected}\",\"observed\":\"${observed}\",\"pass\":\"${pass}\"}")
  echo "    ${pass}: ${name} (expected=${expected}, observed=${observed})"
}

assert_ne() {
  local name=$1 unexpected=$2 observed=$3
  local pass="FAIL"
  [ "$unexpected" != "$observed" ] && pass="PASS"
  ASSERTIONS+=("{\"name\":\"${name}\",\"expected_not\":\"${unexpected}\",\"observed\":\"${observed}\",\"pass\":\"${pass}\"}")
  echo "    ${pass}: ${name} (expected!='${unexpected}', observed=${observed})"
}

assert_gt() {
  local name=$1 threshold=$2 observed=$3
  local pass="FAIL"
  [ "$observed" -gt "$threshold" ] 2>/dev/null && pass="PASS"
  ASSERTIONS+=("{\"name\":\"${name}\",\"expected_gt\":${threshold},\"observed\":${observed},\"pass\":\"${pass}\"}")
  echo "    ${pass}: ${name} (expected>${threshold}, observed=${observed})"
}

record_case() {
  local case_id=$1 status=$2
  local assertions_json
  assertions_json=$(IFS=,; echo "[${ASSERTIONS[*]}]")
  cat <<EOF
{
  "caseId": "${case_id}",
  "commit": "$(git -C "${SCRIPT_DIR}/../../../.." rev-parse HEAD 2>/dev/null || echo '9fe4fbdd')",
  "timestamp": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "status": "${status}",
  "assertions": ${assertions_json}
}
EOF
  ASSERTIONS=()
}

# --------------- P0 Case Implementations ---------------

case_P0_01() {
  local cid="P0-01"
  echo "=== ${cid}: fast-path Completed ==="
  local run_id run_json status_json obs
  local uid="p0-harness"

  run_json=$(api_create_run "$uid" "import pandas as pd; print(pd.DataFrame({'a':[1]}).to_json())")
  run_id=$(echo "$run_json" | jq -r '.id // empty')
  [ -z "$run_id" ] && { echo "  SKIP: cannot create run (frontend unreachable)"; record_case "$cid" "SKIP"; return; }

  sleep 30  # wait for execution to complete

  local anchor_state run_status logical_finished units
  anchor_state=$(get_anchor_field "$run_id" "anchorState")
  run_status=$(get_run_status "$run_id")
  logical_finished=$(count_events "$run_id" "TOOL_CALL_FINISHED")
  units=$(get_active_units)

  # Fast-path completed anchor is operation-fenced cleared → "{}" or CONSUMED
  assert_eq "anchorCleared" "CONSUMED" "$anchor_state"
  assert_eq "runCompleted" "COMPLETED" "$run_status"
  assert_eq "logicalFinished" "1" "$logical_finished"
  assert_eq "unitsReturned" "0" "$units"

  local all_pass="PASS"
  for a in "${ASSERTIONS[@]}"; do [[ "$a" =~ "FAIL" ]] && all_pass="FAIL"; done
  record_case "$cid" "$all_pass"
}

case_P0_02() {
  local cid="P0-02"
  echo "=== ${cid}: fast-path timeout → Pending ==="
  local run_id run_json uid="p0-harness"

  run_json=$(api_create_run "$uid" "import time; time.sleep(300); print('done')")
  run_id=$(echo "$run_json" | jq -r '.id // empty')
  [ -z "$run_id" ] && { echo "  SKIP: cannot create run"; record_case "$cid" "SKIP"; return; }

  sleep 5  # fast-path timeout at 1500ms, should transition to PENDING

  local anchor_state run_status units
  anchor_state=$(get_anchor_field "$run_id" "anchorState")
  run_status=$(get_run_status "$run_id")
  units=$(get_active_units)

  assert_eq "anchorState" "PENDING" "$anchor_state"
  assert_eq "runStatus" "WAITING_TOOL_JOB" "$run_status"
  assert_gt "unitsInUse" "0" "${units:-0}"

  local all_pass="PASS"
  for a in "${ASSERTIONS[@]}"; do [[ "$a" =~ "FAIL" ]] && all_pass="FAIL"; done
  record_case "$cid" "$all_pass"
}

case_P0_03() {
  local cid="P0-03"
  echo "=== ${cid}: PREPARING crash → recovery ==="
  local run_id uid="p0-harness"
  echo "  SETUP: requires manual crash injection mid-PREPARING"
  echo "  VERIFY: crash → restart → operationId resolves to existing task"
  echo "  ORACLE: createTaskCount(operationId)=1, no duplicate task"
  # This case requires timing-based crash injection; documented as MANUAL
  record_case "$cid" "MANUAL"
}

case_P0_04() {
  local cid="P0-04"
  echo "=== ${cid}: Redis eviction → DB anchor rebuild ==="
  local run_id uid="p0-harness"

  run_id="p0-04-test-run"  # use existing pending run created by P0-02 or similar

  local anchor_before=$(get_anchor_field "$run_id" "anchorState")
  [ "$anchor_before" != "PENDING" ] && { echo "  SKIP: no PENDING run available"; record_case "$cid" "SKIP"; return; }

  fault_evict_redis_pending "$run_id"
  sleep 5  # allow rebuild cycle

  local anchor_after due_rebuilt
  anchor_after=$(get_anchor_field "$run_id" "anchorState")
  due_rebuilt=$(redis_cli ZSCORE "agent:tool-job:due" "agent:run:${run_id}:pending_tool_job" 2>/dev/null || echo "")

  assert_eq "anchorPersists" "PENDING" "$anchor_after"
  assert_ne "dueRebuilt" "" "${due_rebuilt:-empty}"

  local all_pass="PASS"
  for a in "${ASSERTIONS[@]}"; do [[ "$a" =~ "FAIL" ]] && all_pass="FAIL"; done
  record_case "$cid" "$all_pass"
}

case_P0_05() {
  local cid="P0-05"
  echo "=== ${cid}: cancel with active task ==="
  local run_id uid="p0-harness"
  echo "  MANUAL: requires active sandbox task + cancel API call"
  echo "  VERIFY: disposition=CANCELED, autoResume=false, units not released early"
  record_case "$cid" "MANUAL"
}

case_P0_06() {
  local cid="P0-06"
  echo "=== ${cid}: terminal envelope saved, usage upsert crash ==="
  echo "  MANUAL: requires crash injection after terminal ENVELOPE persist, before USAGE step"
  echo "  VERIFY: anchor=FINALIZING, reservation RELEASED, usagePersisted=false"
  echo "  VERIFY: restart retries only upsert, does not re-occupy units"
  record_case "$cid" "MANUAL"
}

case_P0_07() {
  local cid="P0-07"
  echo "=== ${cid}: usage written, event flag crash ==="
  echo "  MANUAL: requires crash after USAGE step, before EVENT appendOnce"
  echo "  VERIFY: dedupe key blocks double event, logical FINISHED=1"
  record_case "$cid" "MANUAL"
}

case_P0_08() {
  local cid="P0-08"
  echo "=== ${cid}: restart with old active > new maxUnits → DEGRADED ==="
  local active_units=$(get_active_units)
  local configured_max=${MAX_UNITS:-4}

  echo "  Active units: ${active_units}, Configured max: ${configured_max}"
  # If active > maxUnits after reducing config, admission should be DEGRADED
  # This requires config change + restart; documented as MANUAL for now
  record_case "$cid" "MANUAL"
}

case_P0_09() {
  local cid="P0-09"
  echo "=== ${cid}: status terminal, result unavailable → RESULT_FETCH_PENDING ==="
  echo "  MANUAL: requires sandbox status=terminal but result fetch fails temporarily"
  echo "  VERIFY: anchor=RESULT_FETCH_PENDING, capacity RELEASED"
  echo "  VERIFY: result fetch retry, eventual RESULT_LOST after deadline"
  record_case "$cid" "MANUAL"
}

case_P0_10() {
  local cid="P0-10"
  echo "=== ${cid}: terminal done, launch crash → resume ==="
  echo "  MANUAL: requires crash after terminal, before launch"
  echo "  VERIFY: resumeState=READY/LAUNCHING, planner not re-run, todo not repeated"
  record_case "$cid" "MANUAL"
}

case_P0_11() {
  local cid="P0-11"
  echo "=== ${cid}: STANDARD OOM → oomKilled, no auto-upgrade ==="
  local run_id uid="p0-harness"
  echo "  MANUAL: requires memory-intensive script in STANDARD (512MB) container"
  echo "  VERIFY: oomKilled=true, attemptUpgrade=0, logicalFinished=1, retryable=true"
  record_case "$cid" "MANUAL"
}

case_P0_12() {
  local cid="P0-12"
  echo "=== ${cid}: collector failure → attributionComplete=false ==="
  echo "  MANUAL: requires sandbox with collector error (Docker stats unavailable)"
  echo "  VERIFY: attributionComplete=false, script result preserved (not overwritten)"
  record_case "$cid" "MANUAL"
}

# --------------- Main ---------------
main() {
  echo "=== Data Intense P0 Fault Harness ==="
  echo "Started: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "Base: P0_INTEGRATION_BASE_SHA=9fe4fbdd7b233d6bc7b74bba8128ea2769ae0647"
  echo ""

  # Pre-flight: check service availability
  echo "--- Pre-flight ---"
  local frontend_ok redis_ok pg_ok
  frontend_ok=$(curl -s -o /dev/null -w "%{http_code}" "${FRONTEND_URL}/api/agent/runs?userId=test&limit=1" 2>/dev/null || echo "000")
  redis_ok=$(redis_cli PING 2>/dev/null || echo "ERROR")
  pg_ok=$(pg_query "SELECT 1;" 2>/dev/null || echo "ERROR")

  echo "  Frontend: ${frontend_ok}"
  echo "  Redis: ${redis_ok}"
  echo "  PostgreSQL: ${pg_ok}"
  echo "  Pool enabled: $(pool_enabled)"
  echo "  Container max concurrency: $(container_max_concurrency)"
  echo ""

  local all_results="["
  local first=true

  for case_fn in case_P0_01 case_P0_02 case_P0_03 case_P0_04 \
                 case_P0_05 case_P0_06 case_P0_07 case_P0_08 \
                 case_P0_09 case_P0_10 case_P0_11 case_P0_12; do
    local result
    result=$($case_fn)
    [ "$first" = true ] && first=false || all_results+=","
    all_results+="$result"
    echo "$result"
    echo ""
  done

  all_results+="]"
  echo "$all_results" | python3 -m json.tool > "$RESULTS_FILE" 2>/dev/null || echo "$all_results" > "$RESULTS_FILE"

  echo "=== Harness complete ==="
  echo "Results: $RESULTS_FILE"

  local total passed skipped manual
  total=$(echo "$all_results" | grep -c '"caseId"' || echo "0")
  passed=$(echo "$all_results" | grep -c '"PASS"' || echo "0")
  manual=$(echo "$all_results" | grep -c '"MANUAL"' || echo "0")
  echo "Total: ${total}, Automated PASS: ${passed}, Manual/Skip: ${manual}"
}

main "$@"
