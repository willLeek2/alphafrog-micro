#!/usr/bin/env bash
# === work-package-H (ccqwen) ===
#
# Runtime image retention for the AlphaFrog finance sandbox (spec §12).
#
# Default is PLAN mode: it prints what it would do and NEVER invokes
# `docker rmi`. Only with --apply does it actually delete images.
#
# Only images labeled com.alphafrog.runtime=true are ever deletion
# candidates. The protection set is never removed:
#   - $AF_CURRENT_RUNTIME_IMAGE  (current production image)
#   - $AF_PREVIOUS_RUNTIME_IMAGE (previous generation)
#   - runtimeImageDigest of every QUEUED/RUNNING task in $AF_STATE_FILE
#   - images in use by running containers (per `docker ps`)
#
# Images without the runtime label (or whose `docker inspect` fails) are
# unknown and therefore untouchable: they never enter the deletion set.
#
# JSON extraction uses python3 (stdlib only); no jq / external deps.
set -u

RUNTIME_LABEL="com.alphafrog.runtime"
RUNTIME_LABEL_KV="${RUNTIME_LABEL}=true"

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

# --- protection set (newline-delimited digests) ---------------------------
PROTECTED_LIST=$'\n'

add_protected() {
  local d="$1"
  [ -n "$d" ] || return 0
  case "$PROTECTED_LIST" in
    *$'\n'"$d"$'\n'*) ;;                                   # already present
    *) PROTECTED_LIST="${PROTECTED_LIST}${d}"$'\n' ;;
  esac
}

is_protected() {
  case "$PROTECTED_LIST" in
    *$'\n'"$1"$'\n'*) return 0 ;;
    *) return 1 ;;
  esac
}

# 1) current + previous production images
add_protected "${AF_CURRENT_RUNTIME_IMAGE:-}"
add_protected "${AF_PREVIOUS_RUNTIME_IMAGE:-}"

# 2) runtimeImageDigest of QUEUED/RUNNING tasks in state.json
if [ -n "${AF_STATE_FILE:-}" ] && [ -f "${AF_STATE_FILE:-}" ]; then
  task_digests="$(python3 - "$AF_STATE_FILE" <<'PY'
import json, sys
path = sys.argv[1]
try:
    with open(path, "r", encoding="utf-8") as fh:
        state = json.load(fh)
except Exception:
    sys.exit(0)
tasks = state.get("tasks") or {}
for task in tasks.values():
    status = task.get("status")
    digest = task.get("runtimeImageDigest")
    if status in ("QUEUED", "RUNNING") and digest:
        print(digest)
PY
)"
  while IFS= read -r d; do
    add_protected "$d"
  done <<< "$task_digests"
fi

# 3) images in use by running containers (second column of `docker ps`)
ps_output="$(docker ps)"
while read -r _container d _rest; do
  add_protected "${d:-}"
done <<< "$ps_output"

# --- classify images: only runtime-labeled ones are candidates ------------
RUNTIME_CANDIDATES=$'\n'

in_candidates() {
  case "$RUNTIME_CANDIDATES" in
    *$'\n'"$1"$'\n'*) return 0 ;;
    *) return 1 ;;
  esac
}

has_runtime_label() {
  python3 -c '
import json, sys
try:
    data = json.load(sys.stdin)
except Exception:
    print("none"); sys.exit(0)
if not isinstance(data, list) or not data:
    print("none"); sys.exit(0)
config = data[0].get("Config") or {}
labels = config.get("Labels") or {}
if labels.get("com.alphafrog.runtime") == "true":
    print("runtime")
else:
    print("none")
'
}

images_output="$(docker images)"
while IFS= read -r line; do
  [ -n "$line" ] || continue
  digest="${line%% *}"
  [ -n "$digest" ] || continue
  if ! inspect_json="$(docker inspect "$digest" 2>/dev/null)"; then
    # inspect failed -> unknown -> untouchable
    continue
  fi
  verdict="$(printf '%s' "$inspect_json" | has_runtime_label)"
  if [ "$verdict" = "runtime" ]; then
    if ! in_candidates "$digest"; then
      RUNTIME_CANDIDATES="${RUNTIME_CANDIDATES}${digest}"$'\n'
    fi
  fi
done <<< "$images_output"

# --- deletion set = runtime candidates minus protection set ---------------
DELETE_LIST=$'\n'
while IFS= read -r d; do
  [ -n "$d" ] || continue
  if is_protected "$d"; then
    continue
  fi
  case "$DELETE_LIST" in
    *$'\n'"$d"$'\n'*) ;;                                   # already present
    *) DELETE_LIST="${DELETE_LIST}${d}"$'\n' ;;
  esac
done <<< "$RUNTIME_CANDIDATES"

mode="PLAN"
if [ "$APPLY" -eq 1 ]; then
  mode="APPLY"
fi
echo "[prune_runtime_images] mode=${mode} runtime_label=${RUNTIME_LABEL_KV}"

while IFS= read -r d; do
  [ -n "$d" ] || continue
  echo "[prune_runtime_images] protected: ${d}"
done <<< "$PROTECTED_LIST"

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

exit 0
