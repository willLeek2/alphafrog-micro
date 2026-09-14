#!/usr/bin/env bash
# D15 Tier 2a final gate — minimal oracle implementation (v14 design).
# Caller (deploy_latest.sh) sets D15_CALLER_ID=d15_tier2a_gate.sh before invoking docker,
# so mock can distinguish gate-internal docker calls from deploy_latest.sh calls.
set -euo pipefail
export D15_CALLER_ID="d15_tier2a_gate.sh"

: "${AF_SANDBOX_IMAGE:?must be repo/name@sha256:<64hex>}"
ROOT_DIR="$(git rev-parse --show-toplevel)"
OUT_DIR="$ROOT_DIR/pythonSandboxService/.runtime-build"
IIDFILE="$OUT_DIR/image-id"
MAPPING_FILE="$OUT_DIR/image-digest-mapping.json"
LIBSET_FILE="$OUT_DIR/library-set.json"

INCOMPLETE_DEV_RAW="${AF_SANDBOX_ALLOW_INCOMPLETE_DEV_BUILD:-}"
case "$INCOMPLETE_DEV_RAW" in
  true|TRUE|True|1) INCOMPLETE_DEV=1 ;;
  *) INCOMPLETE_DEV=0 ;;
esac

# (0) Python runtime + version
command -v python3 >/dev/null 2>&1 || { echo "D15_TIER2A_FAIL: python3 not found" >&2; exit 1; }
PYBIN="$(command -v python3)"
python3 -c 'import sys; sys.exit(0 if sys.version_info >= (3, 7) else 1)' || {
  echo "D15_TIER2A_FAIL: python3 version < 3.7" >&2; exit 1
}

# (1) Syntax gate
source "$ROOT_DIR/pythonSandboxService/scripts/af_digest_reference.sh"
af_is_digest_reference "$AF_SANDBOX_IMAGE" || { echo "D15_TIER2A_FAIL: syntax-reject"; exit 1; }

# (2) iidfile
[ -s "$IIDFILE" ] || { echo "D15_TIER2A_FAIL: missing/empty iidfile"; exit 1; }
BUILD_IMAGE_ID="$(cat "$IIDFILE")"
echo "$BUILD_IMAGE_ID" | grep -qE '^sha256:[0-9a-f]{64}$' || {
  echo "D15_TIER2A_FAIL: iidfile malformed"; exit 1
}

# (3) Inspect chosen-ref → INSPECTED_ID
INSPECTED_ID="$(docker inspect --type=image --format '{{.Id}}' "$AF_SANDBOX_IMAGE")" || {
  echo "D15_TIER2A_FAIL: docker inspect failed"; exit 1
}

# (4) Same-source lock
[ "$INSPECTED_ID" = "$BUILD_IMAGE_ID" ] || {
  echo "D15_TIER2A_FAIL: registry inspect $INSPECTED_ID != build iidfile $BUILD_IMAGE_ID"; exit 1
}

# (5) Read library-set.json
LIBSET_DIGEST="$("$PYBIN" -c \
  'import json,sys,re; d=json.load(open(sys.argv[1],encoding="utf-8"))["librarySetDigest"]; \
   assert isinstance(d,str) and re.fullmatch(r"^sha256:[0-9a-f]{64}$",d), d; \
   print(d)' "$LIBSET_FILE")" || {
  echo "D15_TIER2A_FAIL: library-set.json missing/malformed"; exit 1
}

# (6) OCI label
OCI_DIGEST="$(docker image inspect \
  --format '{{ index .Config.Labels "com.alphafrog.librarySetDigest" }}' \
  "$INSPECTED_ID")"
[ "$OCI_DIGEST" = "$LIBSET_DIGEST" ] || {
  echo "D15_TIER2A_FAIL: OCI label $OCI_DIGEST != library-set.json $LIBSET_DIGEST"; exit 1
}

# (7) Mapping verdict
VERDICT="$("$PYBIN" "$ROOT_DIR/pythonSandboxService/scripts/d15_release_verify.py" \
  verify-mapping --mapping "$MAPPING_FILE" --chosen-ref "$AF_SANDBOX_IMAGE" --inspected-id "$INSPECTED_ID")"
case "$VERDICT" in
  ok) ;;
  not-releasable)
    if [ "$INCOMPLETE_DEV" = "1" ]; then
      echo "[D15] WARNING: mapping=not-releasable + AF_SANDBOX_ALLOW_INCOMPLETE_DEV_BUILD bypass (dev only)" >&2
    else
      echo "D15_TIER2A_FAIL: mapping verdict=$VERDICT"; exit 1
    fi
    ;;
  *) echo "D15_TIER2A_FAIL: mapping verdict=$VERDICT"; exit 1 ;;
esac

# (8) Binding verdict
BINDING_VERDICT="$("$PYBIN" "$ROOT_DIR/pythonSandboxService/scripts/d15_release_verify.py" \
  verify-library-set-binding --mapping "$MAPPING_FILE" --inspected-id "$INSPECTED_ID" --library-set-digest "$LIBSET_DIGEST")"
case "$BINDING_VERDICT" in
  ok) ;;
  release-incomplete)
    if [ "$INCOMPLETE_DEV" = "1" ]; then
      echo "[D15] WARNING: binding=release-incomplete + AF_SANDBOX_ALLOW_INCOMPLETE_DEV_BUILD bypass (dev only)" >&2
    else
      echo "D15_TIER2A_FAIL: binding=$BINDING_VERDICT"; exit 1
    fi
    ;;
  *) echo "D15_TIER2A_FAIL: binding=$BINDING_VERDICT"; exit 1 ;;
esac

echo "D15_TIER2A_PASS"
