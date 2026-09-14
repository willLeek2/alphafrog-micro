#!/usr/bin/env bash
# === work-package-H (ccqwen) ===
#
# Runtime image retention for the AlphaFrog finance sandbox (spec §12).
#
# MODES
#   default (PLAN)  Prints what it would do. NEVER invokes `docker rmi`.
#                   Errors while gathering protection or classifying
#                   candidates are reported as "unknown/partial" warnings and
#                   the plan continues; exit code 0.
#   --apply (APPLY) Actually deletes. FAIL-CLOSED: ANY failure while
#                   gathering the protection set or classifying candidates
#                   aborts BEFORE any deletion, with zero `docker rmi`
#                   invocations and EXIT CODE 3:
#                     * `docker ps` failure
#                     * `docker images` failure
#                     * `docker inspect` failure while resolving a protection
#                       entry (including container -> image resolution)
#                     * `docker inspect` failure while classifying a single
#                       candidate image (including empty output or an
#                       unparseable label verdict)
#                     * $AF_STATE_FILE set but unreadable/corrupt
#                   ($AF_STATE_FILE UNSET is normal operation, not a failure;
#                   only a set-but-unusable state file is a failure.)
#
# EXIT CODES
#   0  success (plan or apply; plan also exits 0 when it issued warnings)
#   2  usage error (unknown CLI argument)
#   3  fail-closed abort in APPLY mode: protection/classification was
#      incomplete, so nothing was removed
#
# MACHINE-READABLE DOCKER OUTPUT ONLY
#   Every docker query uses explicit formatting flags; human-readable table
#   output is never parsed:
#     docker images  --no-trunc --format '{{.ID}}'
#     docker ps      --no-trunc --format '{{.ID}}'
#     docker inspect --type=container --format '{{.Image}}'   <container-id>
#     docker inspect --type=image --format '{{.Id}}'          <reference>
#     docker inspect --type=image --format '{{.Id}} {{json .Config.Labels}}'
#
# CANONICAL IDENTIFIERS (single namespace for protection arithmetic)
#   Every identifier -- deletion candidates, $AF_CURRENT_RUNTIME_IMAGE,
#   $AF_PREVIOUS_RUNTIME_IMAGE, runtime_image_ref entries from
#   $AF_STATE_FILE, and images of running containers -- is normalized to ONE
#   canonical form before candidates-minus-protection is computed: the full
#   image ID `sha256:<64hex>` resolved via `docker inspect --type=image
#   --format '{{.Id}}'`. A tag, a registry digest (repo@sha256:...) and a
#   bare image ID that all point to the same image resolve to the same
#   canonical ID and therefore protect it. A protection entry that already
#   IS a full image ID is still passed through `docker inspect` (it resolves
#   to itself) so that a stale entry pointing at a gone image is detected
#   instead of silently trusted.
#
# CANDIDACY / PROTECTION
#   Only images labeled com.alphafrog.runtime=true are deletion candidates.
#   Images whose `docker inspect` fails during classification are unknown and
#   therefore untouchable: they never enter the deletion set. In PLAN mode
#   such an image is skipped with an "unknown/partial" warning; in APPLY mode
#   it is a fail-closed abort (exit 3, zero `docker rmi`) because the
#   candidate set could not be fully classified. The protection set is never
#   removed:
#     - $AF_CURRENT_RUNTIME_IMAGE  (current production image)
#     - $AF_PREVIOUS_RUNTIME_IMAGE (previous generation)
#     - runtime_image_ref of every QUEUED/RUNNING task in $AF_STATE_FILE
#       (refs referenced ONLY by terminal tasks -- SUCCEEDED/FAILED/CANCELED --
#       are NOT protected)
#     - images in use by running containers (per `docker ps`)
#
# JSON extraction uses python3 (stdlib only); no jq / external deps.
# bash 3.2 compatible (macOS default): no associative arrays, no mapfile,
# no ${var,,}.
set -u

RUNTIME_LABEL="com.alphafrog.runtime"
RUNTIME_LABEL_KV="${RUNTIME_LABEL}=true"
ABORT_EXIT=3

APPLY=0
for arg in "$@"; do
  case "$arg" in
    --apply)
      APPLY=1
      ;;
    -h|--help)
      echo "usage: prune_runtime_images.sh [--apply]"
      exit 0
      ;;
    *)
      echo "unknown argument: $arg" >&2
      exit 2
      ;;
  esac
done

PLAN_PROBLEMS=0

# report_problem REASON
#   APPLY mode: abort fail-closed right now, before any deletion (exit 3).
#   PLAN mode : record an unknown/partial warning and continue (exit stays 0).
#   Called for EVERY protection-gathering and candidate-classification failure
#   in both modes; it is the single funnel that makes APPLY fail-closed.
report_problem() {
  if [ "$APPLY" -eq 1 ]; then
    echo "[prune_runtime_images] ERROR: $1" >&2
    echo "[prune_runtime_images] fail-closed: aborting with exit ${ABORT_EXIT} before any deletion; zero rmi invocations" >&2
    exit "$ABORT_EXIT"
  fi
  PLAN_PROBLEMS=$((PLAN_PROBLEMS + 1))
  echo "[prune_runtime_images] warning: $1 -- protection/classification state unknown/partial; plan may be incomplete" >&2
}

# --- newline-delimited set helpers (bash 3.2: no associative arrays) -------
PROTECTED_REFS=$'\n'      # raw protection refs as configured (tags / registry digests / IDs)
PROTECTED_IDS=$'\n'       # canonical image IDs (resolved via docker inspect)
RUNTIME_CANDIDATES=$'\n'  # canonical image IDs of runtime-labeled images
DELETE_LIST=$'\n'

add_protected_ref() {
  local d="$1"
  [ -n "$d" ] || return 0
  case "$PROTECTED_REFS" in
    *$'\n'"$d"$'\n'*) ;;                                   # already present
    *) PROTECTED_REFS="${PROTECTED_REFS}${d}"$'\n' ;;
  esac
}

add_protected_id() {
  local d="$1"
  [ -n "$d" ] || return 0
  case "$PROTECTED_IDS" in
    *$'\n'"$d"$'\n'*) ;;
    *) PROTECTED_IDS="${PROTECTED_IDS}${d}"$'\n' ;;
  esac
}

is_protected_id() {
  case "$PROTECTED_IDS" in
    *$'\n'"$1"$'\n'*) return 0 ;;
    *) return 1 ;;
  esac
}

add_candidate() {
  local d="$1"
  [ -n "$d" ] || return 0
  case "$RUNTIME_CANDIDATES" in
    *$'\n'"$d"$'\n'*) ;;
    *) RUNTIME_CANDIDATES="${RUNTIME_CANDIDATES}${d}"$'\n' ;;
  esac
}

add_delete() {
  local d="$1"
  [ -n "$d" ] || return 0
  case "$DELETE_LIST" in
    *$'\n'"$d"$'\n'*) ;;
    *) DELETE_LIST="${DELETE_LIST}${d}"$'\n' ;;
  esac
}

# --- 1) collect raw protection refs ----------------------------------------

# current + previous production images (may be tags, registry digests or IDs)
add_protected_ref "${AF_CURRENT_RUNTIME_IMAGE:-}"
add_protected_ref "${AF_PREVIOUS_RUNTIME_IMAGE:-}"

# runtime_image_ref of QUEUED/RUNNING tasks in $AF_STATE_FILE.
# AF_STATE_FILE unset is normal; set-but-unusable is a failure.
#
# Schema notes: the durable state.json schema sandbox_task_store_v2 freezes
# the per-task image reference as the snake_case `runtime_image_ref`. Older
# sandbox_task_store_v1 records carry no runtime_image_ref at all; they are
# tolerated (skipped) and must neither crash the script nor gain extra
# protections. Only QUEUED/RUNNING refs are protected: refs referenced only
# by terminal tasks (SUCCEEDED/FAILED/CANCELED) are NOT protected.
if [ -n "${AF_STATE_FILE:-}" ]; then
  if ! task_refs="$(python3 - "$AF_STATE_FILE" <<'PY'
import json, sys
path = sys.argv[1]
try:
    with open(path, "r", encoding="utf-8") as fh:
        state = json.load(fh)
except Exception as exc:
    sys.stderr.write("state file unreadable/corrupt: %s\n" % exc)
    sys.exit(1)
if not isinstance(state, dict):
    sys.stderr.write("state file root is not a JSON object\n")
    sys.exit(1)
tasks = state.get("tasks") or {}
if not isinstance(tasks, dict):
    sys.stderr.write("state file 'tasks' is not a JSON object\n")
    sys.exit(1)
for task in tasks.values():
    if not isinstance(task, dict):
        continue
    status = task.get("status")
    if status not in ("QUEUED", "RUNNING"):
        continue  # terminal tasks (SUCCEEDED/FAILED/CANCELED) never protect
    ref = task.get("runtime_image_ref")  # v2 key; absent in v1 records
    if isinstance(ref, str) and ref:
        print(ref)
PY
)"
  then
    report_problem "AF_STATE_FILE is set but unusable (${AF_STATE_FILE}); task-image protection unknown"
  else
    while IFS= read -r ref; do
      add_protected_ref "$ref"
    done <<< "$task_refs"
  fi
fi

# Images in use by running containers: container IDs via machine-readable
# `docker ps`, then each container's image ID via `docker inspect`.
if ! ps_output="$(docker ps --no-trunc --format '{{.ID}}')"; then
  report_problem "docker ps failed; images of running containers unknown"
else
  while IFS= read -r container_id; do
    [ -n "$container_id" ] || continue
    if ! container_image="$(docker inspect --type=container --format '{{.Image}}' "$container_id" 2>/dev/null)"; then
      report_problem "docker inspect failed for running container ${container_id}; its image is unknown"
      continue
    fi
    if [ -z "$container_image" ]; then
      report_problem "docker inspect returned no image for running container ${container_id}; unknown"
      continue
    fi
    add_protected_ref "$container_image"
  done <<< "$ps_output"
fi

# --- 2) resolve every protection ref to its canonical image ID --------------
# Tags, registry digests and bare IDs pointing at the same image all resolve
# to the same sha256:<hex> ID; protection arithmetic then compares IDs only.
while IFS= read -r ref; do
  [ -n "$ref" ] || continue
  if ! canonical="$(docker inspect --type=image --format '{{.Id}}' "$ref" 2>/dev/null)"; then
    report_problem "docker inspect cannot resolve protection reference ${ref}; unknown"
    continue
  fi
  if [ -z "$canonical" ]; then
    report_problem "docker inspect returned an empty ID for protection reference ${ref}; unknown"
    continue
  fi
  add_protected_id "$canonical"
done <<< "$PROTECTED_REFS"

# --- 3) classify candidates: only runtime-labeled images --------------------
if ! images_output="$(docker images --no-trunc --format '{{.ID}}')"; then
  report_problem "docker images failed; candidate set unknown"
  images_output=""
fi

while IFS= read -r image_id; do
  [ -n "$image_id" ] || continue
  if ! inspect_out="$(docker inspect --type=image --format '{{.Id}} {{json .Config.Labels}}' "$image_id" 2>/dev/null)"; then
    # inspect failed -> unknown -> untouchable: never a candidate. That is a
    # classification failure, so APPLY aborts fail-closed here (report_problem
    # exits 3 before any rmi); PLAN records the warning and continues.
    report_problem "docker inspect failed while classifying candidate ${image_id}; candidate classification incomplete"
    continue
  fi
  if [ -z "$inspect_out" ]; then
    report_problem "docker inspect returned no output while classifying candidate ${image_id}; candidate classification incomplete"
    continue
  fi
  if ! verdict="$(printf '%s\n' "$inspect_out" | python3 -c '
import json, sys
line = sys.stdin.read().strip()
_, sep, labels_json = line.partition(" ")
labels = {}
if sep:
    try:
        parsed = json.loads(labels_json)
    except Exception:
        parsed = None
    if isinstance(parsed, dict):
        labels = parsed
if labels.get(sys.argv[1]) == "true":
    print("runtime")
else:
    print("none")
' "$RUNTIME_LABEL")"; then
    report_problem "label verdict could not be computed for candidate ${image_id}; candidate classification incomplete"
    continue
  fi
  if [ "$verdict" != "runtime" ]; then
    continue
  fi
  # Canonical candidate ID is the Id reported by inspect (first field).
  canonical="${inspect_out%% *}"
  if [ -z "$canonical" ]; then
    continue
  fi
  add_candidate "$canonical"
done <<< "$images_output"

# --- 4) deletion set = candidates minus canonical protection set ------------
while IFS= read -r cand; do
  [ -n "$cand" ] || continue
  if is_protected_id "$cand"; then
    continue
  fi
  add_delete "$cand"
done <<< "$RUNTIME_CANDIDATES"

mode="PLAN"
if [ "$APPLY" -eq 1 ]; then
  mode="APPLY"
fi
echo "[prune_runtime_images] mode=${mode} runtime_label=${RUNTIME_LABEL_KV}"

while IFS= read -r d; do
  [ -n "$d" ] || continue
  echo "[prune_runtime_images] protected: ${d}"
done <<< "$PROTECTED_IDS"

deleted_any=0
while IFS= read -r d; do
  [ -n "$d" ] || continue
  if [ "$APPLY" -eq 1 ]; then
    echo "[prune_runtime_images] removing: ${d}"
    if ! docker rmi "$d"; then
      echo "[prune_runtime_images] warning: docker rmi failed for ${d}" >&2
    fi
    deleted_any=1
  else
    echo "[prune_runtime_images] would remove: ${d}"
  fi
done <<< "$DELETE_LIST"

if [ "$APPLY" -eq 1 ] && [ "$deleted_any" -eq 0 ]; then
  echo "[prune_runtime_images] nothing to remove"
fi

if [ "$APPLY" -eq 0 ] && [ "$PLAN_PROBLEMS" -gt 0 ]; then
  echo "[prune_runtime_images] plan completed with ${PLAN_PROBLEMS} warning(s); protection state unknown/partial"
fi

exit 0
