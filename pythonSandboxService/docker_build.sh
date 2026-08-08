#!/bin/bash
# AlphaFrog pythonSandboxService image build orchestration.
#
# MethodSpec V5 work package H (Spec §12) wiring for the `runtime` target
# (round-2 flow, R2-1/R2-2):
#   1. lockDigest = sha256 of requirements-image.lock (the four runtime
#      packages moved verbatim out of the old Dockerfile inline literal);
#   2. canonical method-spec inputs gate: work package A's canonical
#      generated JSON (resolver-catalog.json + the three method specs) must
#      exist at METHOD_SPEC_CANONICAL_DIR -- a hard build material, missing
#      inputs fail CLOSED with no dev-switch escape; then
#      runtime/scripts/generate_method_bindings.py runs BEFORE the build and
#      writes runtime/src/alphafrog_finance/_generated/method_specs.json
#      (hand-copied method triples are forbidden, Spec §6); a generator
#      failure aborts the build;
#   3. PHASE 1 `docker build --target runtime-install --iidfile`: installs
#      the locked library set, then pip-installs the REAL alphafrog_finance
#      distribution from runtime/ (with the generated bindings);
#   4. SMOKE GATE (R2-1): scripts/smoke_runtime_image.py runs via `docker run`
#      against the phase-1 image under BOTH the system python and the compat
#      venv (import + version/apiVersion + the three frozen bindings
#      VERBATIM); any failure aborts;
#   5. INVENTORY GATE (R2-2): scripts/runtime_image_inventory.py queries the
#      image's ACTUAL executing interpreter via `docker run` (PEP 503 names +
#      real versions of every installed package + alphafrog_finance
#      apiVersion) and the result is verified fail-closed against the
#      expected set (lock pins + alphafrog_finance). The round-1 design of
#      inferring library-set.json from the lockfile is REMOVED;
#   6. first invocation of scripts/build_runtime_manifest.py writes
#      library-set.json from the VERIFIED ACTUAL inventory and yields
#      librarySetDigest;
#   7. PHASE 2 `docker build --iidfile` FROM the phase-1 image ID bakes the
#      verified library-set.json and sets the OCI labels (labels are static
#      metadata, so librarySetDigest can only be set after verification);
#   8. AFTER the final image ID is known, a second invocation writes the
#      external imageDigest -> digests mapping OUTSIDE the image (never
#      written back into image labels -- self-reference prohibition, §12);
#      the mapping entry binds the immutable image ID (deploy target binding,
#      R2-3);
#   9. SBOM generation is a documented OPTIONAL hook: syft is not required to
#      install, but a build without a verified SBOM is NOT releasable (see
#      the release gate below); agents never fabricate digests.
#
# RELEASE GATE (Spec §12 hardening, fail-closed):
#   The runtime build DEFAULT-FAILS when any release input is missing or a
#   REPLACE_WITH_... placeholder: BASE_IMAGE_DIGEST (verified base-image
#   digest pinned by frog), METHOD_SPEC_INDEX_DIGEST (sha256:<64hex> digest
#   of the MethodSpec index) or the SBOM input (syft available). The ONLY
#   escape hatch is the explicit, independent dev switch
#   AF_SANDBOX_ALLOW_INCOMPLETE_DEV_BUILD=true/1 (never implicit): it lets a
#   structural dev build proceed, but the external digest mapping is then
#   marked releasable=false and deploy_latest.sh refuses to deploy it unless
#   the same explicit switch is set there too.
#
# Runtime target inputs (never invented here):
#   BASE_IMAGE_DIGEST        verified base-image digest, sha256:<64 lowercase
#                            hex>, pinned by frog; defaults to the explicit
#                            REPLACE_WITH_... placeholder token, which fails
#                            the release gate until frog pins the real value.
#   METHOD_SPEC_INDEX_DIGEST sha256:<64hex> digest of the MethodSpec index.
#   METHOD_SPEC_CANONICAL_DIR
#                            directory holding work package A's canonical
#                            generated method-spec JSON (resolver-catalog.json
#                            + one spec file per frozen method). Defaults to
#                            agentToolsShared/target/generated-resources/
#                            finance/method-specs/v1 (A's build output).
#                            Missing inputs fail CLOSED: this is a hard build
#                            material (the distribution cannot install without
#                            generated bindings), not a release-time
#                            placeholder -- the dev switch does NOT admit it.
#
# Artifacts are written to pythonSandboxService/.runtime-build/.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
BUILD_TARGET="${1:-${PYTHON_SANDBOX_BUILD_TARGET:-all}}"

# Shared digest validation (single source of truth for the shell entry
# points; identical semantics to app/config.py and build_runtime_manifest.py,
# pinned by tests/digest_reference_vectors.py).
source "$SCRIPT_DIR/scripts/af_digest_reference.sh"

usage() {
  cat <<'EOF'
Usage:
  bash docker_build.sh            # build runtime and service images
  bash docker_build.sh all
  bash docker_build.sh runtime    # build alphafrog-sandbox-runtime:latest only
  bash docker_build.sh service    # build alphafrog-python-sandbox:latest only

Runtime target (Spec §12) environment inputs:
  BASE_IMAGE_DIGEST          verified base-image digest, sha256:<64 lowercase
                             hex>, pinned by frog (defaults to the
                             REPLACE_WITH_... placeholder; the release gate
                             fails until frog pins the verified value)
  METHOD_SPEC_INDEX_DIGEST   sha256:<64hex> digest of the MethodSpec index
  METHOD_SPEC_CANONICAL_DIR  work package A's canonical generated method-spec
                             JSON directory (default: agentToolsShared/target/
                             generated-resources/finance/method-specs/v1).
                             Missing inputs fail CLOSED (hard build material;
                             the dev switch does not admit it)

Release gate (fail-closed): missing/placeholder release inputs abort the
runtime build. The ONLY escape hatch is the explicit dev switch
  AF_SANDBOX_ALLOW_INCOMPLETE_DEV_BUILD=true|1
which allows a structural dev build but marks it releasable=false
(deploy_latest.sh refuses non-releasable builds unless the same switch is
set). The switch is never implicit.

Build order: canonical inputs -> bindings generator -> pip install (phase 1)
-> smoke gate (system python + compat venv) -> actual-inventory query +
fail-closed compare -> bake library-set.json + labels (phase 2) -> SBOM ->
external mapping -> final release gate.

Runtime artifacts (outside the image):
  .runtime-build/library-set.json      (verified ACTUAL inventory)
  .runtime-build/verified-packages.json
  .runtime-build/image-inventory.json
  .runtime-build/image-id
  .runtime-build/image-digest-mapping.json
  .runtime-build/sbom.json   (only when syft is installed)
EOF
}

case "$BUILD_TARGET" in
  all|runtime|service) ;;
  -h|--help)
    usage
    exit 0
    ;;
  *)
    echo "[pythonSandbox] Unknown build target: $BUILD_TARGET" >&2
    usage >&2
    exit 1
    ;;
esac

# BuildKit 才支持构建阶段 --network=host（ECS 上代理常只监听 127.0.0.1）
export DOCKER_BUILDKIT=1

USE_PROXY=${USE_PROXY:-1}

NETWORK_ARGS=()
HOST_ARGS=()
PROXY_ARGS=()

if [ "$USE_PROXY" = "1" ] || [ "$USE_PROXY" = "true" ]; then
  # 优先使用系统环境变量中的代理地址，不再硬编码 127.0.0.1:7890
  if [ -n "${http_proxy:-}" ]; then
    PROXY_URL="$http_proxy"
  elif [ -n "${https_proxy:-}" ]; then
    PROXY_URL="$https_proxy"
  else
    # 回退：仍可通过 DOCKER_PROXY_HOST / DOCKER_PROXY_PORT 覆盖
    DOCKER_PROXY_PORT=${DOCKER_PROXY_PORT:-7890}
    if [ "$(uname -s)" = "Linux" ]; then
      DOCKER_PROXY_HOST=${DOCKER_PROXY_HOST:-127.0.0.1}
    else
      DOCKER_PROXY_HOST=${DOCKER_PROXY_HOST:-host.docker.internal}
    fi
    PROXY_URL="http://${DOCKER_PROXY_HOST}:${DOCKER_PROXY_PORT}"
  fi

  # 代理地址指向 loopback 且是 Linux 时，docker build 须 --network=host
  if [ "$(uname -s)" = "Linux" ] && echo "$PROXY_URL" | grep -qE '://(127\.0\.0\.1|localhost)[:/]'; then
    NETWORK_ARGS=(--network=host)
    echo "[pythonSandbox] proxy=${PROXY_URL}, --network=host (auto-detected loopback proxy)"
  else
    echo "[pythonSandbox] proxy=${PROXY_URL}"
  fi

  export https_proxy="$PROXY_URL" http_proxy="$PROXY_URL"
  PROXY_ARGS=(
    --build-arg "http_proxy=${PROXY_URL}"
    --build-arg "https_proxy=${PROXY_URL}"
  )

  # 探活检查（curl 替代 /dev/tcp），失败只警告不退出
  if ! curl -s --max-time 3 "$PROXY_URL" >/dev/null 2>&1; then
    echo "[pythonSandbox] WARNING: 代理 ${PROXY_URL} 不可达，构建可能失败。" >&2
    echo "  跳过代理可: USE_PROXY=0 PIP_INDEX_URL=https://mirrors.aliyun.com/pypi/simple/ bash $0" >&2
  fi
else
  unset https_proxy http_proxy all_proxy
  echo "[pythonSandbox] USE_PROXY=0（直连或仅用 PIP_INDEX_URL 镜像）"
fi

PIP_ARGS=()
if [ -n "${PIP_INDEX_URL:-}" ]; then
  PIP_ARGS+=(--build-arg "PIP_INDEX_URL=${PIP_INDEX_URL}")
fi
if [ -n "${PIP_EXTRA_INDEX_URL:-}" ]; then
  PIP_ARGS+=(--build-arg "PIP_EXTRA_INDEX_URL=${PIP_EXTRA_INDEX_URL}")
fi

run_docker_build() {
  local docker_args=(docker build)
  if [ "${#NETWORK_ARGS[@]}" -gt 0 ]; then
    docker_args+=("${NETWORK_ARGS[@]}")
  fi
  if [ "${#HOST_ARGS[@]}" -gt 0 ]; then
    docker_args+=("${HOST_ARGS[@]}")
  fi
  if [ "${#PROXY_ARGS[@]}" -gt 0 ]; then
    docker_args+=("${PROXY_ARGS[@]}")
  fi
  if [ "${#PIP_ARGS[@]}" -gt 0 ]; then
    docker_args+=("${PIP_ARGS[@]}")
  fi
  docker_args+=("$@")
  "${docker_args[@]}"
}

# --- work package H (Spec §12): runtime manifest helpers --------------------

# sha256 of a file rendered as "sha256:<hex>"; macOS ships shasum, Linux sha256sum.
file_sha256() {
  local path="$1"
  if command -v sha256sum >/dev/null 2>&1; then
    printf 'sha256:%s' "$(sha256sum "$path" | awk '{print $1}')"
  elif command -v shasum >/dev/null 2>&1; then
    printf 'sha256:%s' "$(shasum -a 256 "$path" | awk '{print $1}')"
  else
    echo "[pythonSandbox] ERROR: neither sha256sum nor shasum found; cannot digest $path" >&2
    exit 1
  fi
}

# Succeeds (return 0) iff the value is missing (empty) or an explicit
# REPLACE_WITH_... placeholder token. Placeholder tokens are never real
# values: frog pins the verified value at release time (Spec §12).
is_missing_or_placeholder() {
  local value="$1"
  [ -n "$value" ] || return 0
  case "$value" in
    REPLACE_WITH_*) return 0 ;;
  esac
  return 1
}

# Fail-closed digest-value check (Spec §12 hardening): the value must be
# EXACTLY sha256:<64 LOWERCASE hex> -- anchored at both ends, lowercase only
# (shared validator scripts/af_digest_reference.sh). Uppercase hex, 63/65-hex
# values, or any extra leading/trailing content are hard errors even with the
# dev switch set: malformed values are never a valid input.
require_sha256_value() {
  local name="$1" value="$2"
  if ! af_is_sha256_digest "$value"; then
    echo "[pythonSandbox] ERROR: ${name} is not a valid sha256:<64 lowercase hex> digest (check failed: exact anchored lowercase sha256 value)." >&2
    echo "[pythonSandbox]   Refusing to proceed with a malformed release input (Spec §12 fail-closed)." >&2
    exit 1
  fi
}

# NOTE (round-2 R2-2): the round-1 host-side inference of library-set.json
# content from the lockfile (packages_json_from_lock) and its "future lockfile
# optional apiVersion" placeholder design are REMOVED. The baked library set
# is the image's VERIFIED ACTUAL inventory: scripts/runtime_image_inventory.py
# queries the image's actual executing interpreter via `docker run`, the
# result is compared fail-closed against the expected set (lock pins +
# alphafrog_finance version/apiVersion from the runtime source), and ONLY the
# verified inventory enters library-set.json, the OCI librarySetDigest label
# and the external mapping.

build_runtime_image() {
  local lock_file="$SCRIPT_DIR/requirements-image.lock"
  local manifest_script="$SCRIPT_DIR/scripts/build_runtime_manifest.py"
  local out_dir="$SCRIPT_DIR/.runtime-build"
  mkdir -p "$out_dir"

  echo "[pythonSandbox] === Spec §12 runtime manifest build ==="

  if [ ! -f "$lock_file" ]; then
    echo "[pythonSandbox] ERROR: lockfile not found: $lock_file" >&2
    exit 1
  fi

  # --- Release gate state (Spec §12 hardening: DEFAULT-FAIL / fail-closed) --
  # incomplete_inputs collects the release inputs that are missing or still
  # REPLACE_WITH_... placeholders. Non-empty => the build is NOT releasable;
  # without the explicit dev switch the build aborts BEFORE `docker build`.
  local incomplete_inputs=()
  local allow_incomplete_dev=0
  case "$(printf '%s' "${AF_SANDBOX_ALLOW_INCOMPLETE_DEV_BUILD:-}" | tr '[:upper:]' '[:lower:]' | tr -d '[:space:]')" in
    true|1) allow_incomplete_dev=1 ;;
  esac

  # 1) lockDigest = sha256 of the lockfile bytes (Spec §12).
  local lock_digest
  lock_digest="$(file_sha256 "$lock_file")"
  echo "[pythonSandbox] lockDigest=${lock_digest} (${lock_file})"

  # 2) baseImageDigest: verified digest pinned by frog at release time (Spec §12).
  #    The REPLACE_WITH_... placeholder makes an unverified build impossible to
  #    mistake for a real one; the release gate fails until it is replaced.
  local base_image_digest="${BASE_IMAGE_DIGEST:-REPLACE_WITH_VERIFIED_BASE_IMAGE_DIGEST}"
  if is_missing_or_placeholder "$base_image_digest"; then
    incomplete_inputs+=("BASE_IMAGE_DIGEST")
    echo "[pythonSandbox] WARNING: BASE_IMAGE_DIGEST is missing or a REPLACE_WITH_... placeholder." >&2
    echo "[pythonSandbox]   frog pins the verified base-image digest at release time (Spec §12);" >&2
    echo "[pythonSandbox]   until then the build is NOT releasable." >&2
  else
    require_sha256_value "BASE_IMAGE_DIGEST" "$base_image_digest"
  fi

  # 3) methodSpecIndexDigest: digest of the MethodSpec index (Spec §12 input
  #    sample). Produced by the MethodSpec build tooling; never invented here.
  local method_spec_index_digest="${METHOD_SPEC_INDEX_DIGEST:-}"
  if is_missing_or_placeholder "$method_spec_index_digest"; then
    incomplete_inputs+=("METHOD_SPEC_INDEX_DIGEST")
    echo "[pythonSandbox] WARNING: METHOD_SPEC_INDEX_DIGEST is missing or a REPLACE_WITH_... placeholder." >&2
    echo "[pythonSandbox]   Provide the sha256:<64hex> digest of the MethodSpec index (built by the" >&2
    echo "[pythonSandbox]   MethodSpec work package); until then the build is NOT releasable." >&2
  else
    require_sha256_value "METHOD_SPEC_INDEX_DIGEST" "$method_spec_index_digest"
  fi

  # SBOM input: syft availability is known before the build. Without syft the
  # SBOM digest stays a placeholder -> incomplete input (fail-closed gate).
  local syft_available=0
  if command -v syft >/dev/null 2>&1; then
    syft_available=1
  else
    incomplete_inputs+=("SBOM_DIGEST")
    echo "[pythonSandbox] WARNING: syft not installed - SBOM cannot be generated." >&2
    echo "[pythonSandbox]   sbomDigest would stay the REPLACE_WITH_... placeholder; until frog" >&2
    echo "[pythonSandbox]   produces the verified SBOM the build is NOT releasable." >&2
  fi

  # RELEASE GATE: fail-closed. Incomplete inputs abort the build BEFORE any
  # `docker build` unless the explicit, independent dev switch is set.
  if [ "${#incomplete_inputs[@]}" -gt 0 ]; then
    if [ "$allow_incomplete_dev" -ne 1 ]; then
      echo "[pythonSandbox] ERROR: release gate FAILED (Spec §12 fail-closed) - incomplete release inputs:" >&2
      local incomplete_name
      for incomplete_name in "${incomplete_inputs[@]}"; do
        echo "[pythonSandbox]   - ${incomplete_name} (missing or REPLACE_WITH_... placeholder)" >&2
      done
      echo "[pythonSandbox] A build with incomplete release inputs is NOT releasable and is refused by" >&2
      echo "[pythonSandbox] deploy_latest.sh; it must not proceed by default. frog pins the verified" >&2
      echo "[pythonSandbox] values at release time. For a structural DEV build only, set the explicit" >&2
      echo "[pythonSandbox] switch AF_SANDBOX_ALLOW_INCOMPLETE_DEV_BUILD=true (or 1); the artifacts are" >&2
      echo "[pythonSandbox] then marked releasable=false and remain undeployable without that switch." >&2
      exit 1
    fi
    echo "[pythonSandbox] WARNING: release gate bypassed ONLY because the explicit dev switch" >&2
    echo "[pythonSandbox] AF_SANDBOX_ALLOW_INCOMPLETE_DEV_BUILD is set. Incomplete inputs: ${incomplete_inputs[*]}." >&2
    echo "[pythonSandbox] The resulting artifacts are marked releasable=false (dev-only, never production)." >&2
  fi

  # 4) Canonical method-spec inputs gate (R2-1): work package A's canonical
  #    generated JSON is a HARD build material -- the alphafrog_finance
  #    distribution cannot install without generated bindings (hand-copied
  #    triples are forbidden, Spec §6). Missing inputs fail CLOSED here; the
  #    dev switch does NOT admit this (it is not a release-time placeholder).
  local canonical_dir="${METHOD_SPEC_CANONICAL_DIR:-$REPO_ROOT/agentToolsShared/target/generated-resources/finance/method-specs/v1}"
  local canonical_file
  if [ ! -d "$canonical_dir" ]; then
    echo "[pythonSandbox] ERROR: METHOD_SPEC_CANONICAL_DIR is not a directory: $canonical_dir" >&2
    echo "[pythonSandbox]   The runtime build requires work package A's canonical generated" >&2
    echo "[pythonSandbox]   method-spec JSON (resolver-catalog.json + the three frozen method" >&2
    echo "[pythonSandbox]   specs). Build A's generated resources first or point" >&2
    echo "[pythonSandbox]   METHOD_SPEC_CANONICAL_DIR at them (fail-closed, Spec §12 R2-1)." >&2
    exit 1
  fi
  for canonical_file in resolver-catalog.json cagr.json annualized_volatility.json sharpe_ratio.json; do
    if [ ! -f "$canonical_dir/$canonical_file" ]; then
      echo "[pythonSandbox] ERROR: canonical method-spec input missing: $canonical_dir/$canonical_file" >&2
      echo "[pythonSandbox]   All four A-canonical JSON files are required (fail-closed, Spec §12 R2-1)." >&2
      exit 1
    fi
  done
  echo "[pythonSandbox] canonical method specs: $canonical_dir"

  # 5) Build-time bindings generator (R2-1): BEFORE pip-installing the
  #    distribution, generate runtime/src/alphafrog_finance/_generated/
  #    method_specs.json from the A-canonical inputs. The generator is
  #    fail-closed (non-zero exit, no partial output on ANY problem) and its
  #    failure aborts the build before `docker build` ever runs.
  local generator_script="$SCRIPT_DIR/runtime/scripts/generate_method_bindings.py"
  local generated_bindings="$SCRIPT_DIR/runtime/src/alphafrog_finance/_generated/method_specs.json"
  if ! python3 "$generator_script" --canonical-dir "$canonical_dir" --out "$generated_bindings"; then
    echo "[pythonSandbox] ERROR: method-bindings generator FAILED (fail-closed, Spec §12 R2-1)." >&2
    echo "[pythonSandbox]   No build product was written; the build aborts before docker build." >&2
    exit 1
  fi

  # 6) buildRevision, e.g. git:<commit> (Spec §12); empty outside a git checkout.
  local build_revision=""
  if git -C "$REPO_ROOT" rev-parse --verify HEAD >/dev/null 2>&1; then
    build_revision="git:$(git -C "$REPO_ROOT" rev-parse --verify HEAD)"
  fi

  # 7) FROM ref: the verified base image pinned by digest; ONLY with the
  #    explicit dev switch and a placeholder base digest does the build fall
  #    back to the bare base tag (structural dev build, releasable=false).
  local runtime_base_image_ref
  if is_missing_or_placeholder "$base_image_digest"; then
    runtime_base_image_ref="python:${RUNTIME_BASE_IMAGE_TAG:-3.13-slim}"
    echo "[pythonSandbox] WARNING: dev structural build FROM bare base tag '${runtime_base_image_ref}'" >&2
    echo "[pythonSandbox]   (placeholder base digest + explicit dev switch). NOT releasable." >&2
  else
    runtime_base_image_ref="python:${RUNTIME_BASE_IMAGE_TAG:-3.13-slim}@${base_image_digest}"
  fi

  # 8) PHASE 1 build (runtime-install stage): installs the locked library set
  #    and pip-installs the REAL alphafrog_finance distribution from runtime/.
  local iid_install_file="$out_dir/image-install-stage-id"
  rm -f "$iid_install_file"
  run_docker_build \
    --target runtime-install \
    -f "$SCRIPT_DIR/Dockerfile.runtime" \
    --iidfile "$iid_install_file" \
    --build-arg "RUNTIME_BASE_IMAGE_REF=${runtime_base_image_ref}" \
    "$SCRIPT_DIR"
  local install_image_id
  install_image_id="$(cat "$iid_install_file")"
  echo "[pythonSandbox] install-stage image=${install_image_id} (immutable image ID via --iidfile)"

  # 9) SMOKE GATE (R2-1, fail-closed): assert inside the target interpreters
  #    -- the image's SYSTEM python AND the compat venv -- that
  #    alphafrog_finance imports with the frozen version/apiVersion and the
  #    three method bindings exist VERBATIM. Any failure aborts the build.
  if ! docker run --rm "$install_image_id" python /opt/alphafrog/build/smoke_runtime_image.py; then
    echo "[pythonSandbox] ERROR: smoke gate FAILED under the image system python (fail-closed, Spec §12 R2-1)." >&2
    exit 1
  fi
  if ! docker run --rm "$install_image_id" /sandbox/.sandbox-venv/bin/python /opt/alphafrog/build/smoke_runtime_image.py; then
    echo "[pythonSandbox] ERROR: smoke gate FAILED under the compat venv (fail-closed, Spec §12 R2-1)." >&2
    exit 1
  fi

  # 10) INVENTORY GATE (R2-2, fail-closed): query the image's ACTUAL
  #     executing interpreter for the real installed library set, then verify
  #     it against the expected set (lock pins + alphafrog_finance
  #     version/apiVersion read from the runtime source). Any missing/extra
  #     managed package, version or apiVersion mismatch aborts the build.
  local inventory_script="$SCRIPT_DIR/scripts/runtime_image_inventory.py"
  local inventory_file="$out_dir/image-inventory.json"
  if ! docker run --rm "$install_image_id" python /opt/alphafrog/build/runtime_image_inventory.py --print-json > "$inventory_file"; then
    echo "[pythonSandbox] ERROR: inventory query FAILED against the image's executing interpreter (fail-closed, Spec §12 R2-2)." >&2
    rm -f "$inventory_file"
    exit 1
  fi
  local verified_packages_file="$out_dir/verified-packages.json"
  if ! python3 "$inventory_script" \
      --verify \
      --lock "$lock_file" \
      --actual-json "$inventory_file" \
      --finance-init "$SCRIPT_DIR/runtime/src/alphafrog_finance/__init__.py" \
      --packages-out "$verified_packages_file"; then
    echo "[pythonSandbox] ERROR: image inventory does NOT match the expected library set (fail-closed, Spec §12 R2-2)." >&2
    exit 1
  fi

  # 11) First manifest invocation: library-set.json from the VERIFIED ACTUAL
  #     inventory (the same inventory the OCI label and the external mapping
  #     carry); yields librarySetDigest for the phase-2 OCI label.
  python3 "$manifest_script" \
    --lock-digest "$lock_digest" \
    --method-spec-index-digest "$method_spec_index_digest" \
    --packages-file "$verified_packages_file" \
    --output "$out_dir/library-set.json"
  local library_set_digest
  library_set_digest="$(python3 -c 'import json, sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["librarySetDigest"])' "$out_dir/library-set.json")"
  echo "[pythonSandbox] librarySetDigest=${library_set_digest} (verified actual inventory)"

  # 12) PHASE 2 build: FROM the phase-1 image by immutable ID; bakes the
  #     verified library-set.json and sets the OCI labels. --iidfile captures
  #     the FINAL immutable image ID.
  local iid_file="$out_dir/image-id"
  rm -f "$iid_file"
  run_docker_build \
    -t alphafrog-sandbox-runtime:latest \
    -f "$SCRIPT_DIR/Dockerfile.runtime" \
    --iidfile "$iid_file" \
    --build-arg "AF_RUNTIME_INSTALL_IMAGE=${install_image_id}" \
    --build-arg "AF_BASE_IMAGE_DIGEST=${base_image_digest}" \
    --build-arg "AF_LOCK_DIGEST=${lock_digest}" \
    --build-arg "AF_METHOD_SPEC_INDEX_DIGEST=${method_spec_index_digest}" \
    --build-arg "AF_LIBRARY_SET_DIGEST=${library_set_digest}" \
    "$SCRIPT_DIR"

  local image_digest
  image_digest="$(cat "$iid_file")"
  echo "[pythonSandbox] imageDigest=${image_digest} (immutable image ID via --iidfile)"

  # 13) SBOM: documented optional hook. syft need not be installed, but a
  #     build without a verified SBOM is NOT releasable (release gate). Never
  #     a fabricated digest: frog generates the verified SBOM at release time.
  local sbom_digest="REPLACE_WITH_VERIFIED_SBOM_DIGEST"
  if [ "$syft_available" -eq 1 ]; then
    echo "[pythonSandbox] syft detected: generating SBOM for the built image..."
    if syft "docker:alphafrog-sandbox-runtime:latest" -o json > "$out_dir/sbom.json"; then
      sbom_digest="$(file_sha256 "$out_dir/sbom.json")"
      echo "[pythonSandbox] sbomDigest=${sbom_digest} ($out_dir/sbom.json)"
    else
      # syft present but failed -> SBOM input incomplete (fail-closed gate at
      # the end of this function; the mapping still records releasable=false).
      incomplete_inputs+=("SBOM_DIGEST")
      echo "[pythonSandbox] WARNING: syft failed; sbomDigest stays placeholder '${sbom_digest}'." >&2
    fi
  else
    echo "[pythonSandbox] WARNING: syft not installed - SBOM NOT generated." >&2
    echo "[pythonSandbox]   sbomDigest recorded as placeholder '${sbom_digest}' in the external mapping;" >&2
    echo "[pythonSandbox]   frog generates the verified SBOM at release time (Spec §12)." >&2
  fi

  # 14) Second invocation: AFTER the image ID is known, write the external
  #     imageDigest -> digests mapping OUTSIDE the image (Spec §12). It is
  #     never written back into image labels (self-reference prohibition) and
  #     is consumed by deploy config and audit queries. Incomplete release
  #     inputs are recorded so the entry carries releasable=false (release
  #     gate). The entry BINDS the immutable image ID (its key) and records
  #     the build-time imageRef, so deploy_latest.sh can prove the deploy
  #     target identical to EXACTLY ONE mapping entry (R2-3 target binding).
  #     The mapping carries the SAME verified librarySetDigest as the baked
  #     library-set.json and the OCI label (R2-2).
  local incomplete_args=()
  local incomplete_name
  for incomplete_name in ${incomplete_inputs[@]+"${incomplete_inputs[@]}"}; do
    incomplete_args+=(--incomplete-input "$incomplete_name")
  done
  python3 "$manifest_script" \
    --lock-digest "$lock_digest" \
    --method-spec-index-digest "$method_spec_index_digest" \
    --packages-file "$verified_packages_file" \
    --output "$out_dir/library-set.json" \
    --mapping-output "$out_dir/image-digest-mapping.json" \
    --image-digest "$image_digest" \
    --image-ref "alphafrog-sandbox-runtime:latest" \
    --base-image-digest "$base_image_digest" \
    --sbom-digest "$sbom_digest" \
    --build-revision "$build_revision" \
    ${incomplete_args[@]+"${incomplete_args[@]}"}

  # FINAL RELEASE GATE: if any release input ended up incomplete (e.g. syft
  # failed during the build), the build fails closed AFTER the non-releasable
  # mapping was written for audit -- unless the explicit dev switch is set.
  if [ "${#incomplete_inputs[@]}" -gt 0 ]; then
    if [ "$allow_incomplete_dev" -ne 1 ]; then
      echo "[pythonSandbox] ERROR: release gate FAILED after build (Spec §12 fail-closed) - incomplete release inputs:" >&2
      for incomplete_name in "${incomplete_inputs[@]}"; do
        echo "[pythonSandbox]   - ${incomplete_name} (missing or REPLACE_WITH_... placeholder)" >&2
      done
      echo "[pythonSandbox] The built image stays local; the external mapping is marked releasable=false." >&2
      echo "[pythonSandbox] Set AF_SANDBOX_ALLOW_INCOMPLETE_DEV_BUILD=true (or 1) for a dev-only build." >&2
      exit 1
    fi
  fi

  echo "[pythonSandbox] Spec §12 artifacts:"
  echo "[pythonSandbox]   library-set.json (also baked into the image): $out_dir/library-set.json"
  echo "[pythonSandbox]   external digest mapping:                      $out_dir/image-digest-mapping.json"
  echo "[pythonSandbox]   image ID file:                                $iid_file"
  if [ "${#incomplete_inputs[@]}" -gt 0 ]; then
    echo "[pythonSandbox] STATUS: NOT RELEASABLE (releasable=false; incomplete inputs: ${incomplete_inputs[*]})."
  else
    echo "[pythonSandbox] STATUS: releasable=true (all release inputs verified)."
  fi
  echo "[pythonSandbox] NOTE: image release (registry push) and production config changes"
  echo "[pythonSandbox]       remain frog's final decision (Spec §12); this script never pushes."
}

if [ "$BUILD_TARGET" = "all" ] || [ "$BUILD_TARGET" = "runtime" ]; then
  echo "[pythonSandbox] Building runtime image: alphafrog-sandbox-runtime:latest"
  build_runtime_image
fi

if [ "$BUILD_TARGET" = "all" ] || [ "$BUILD_TARGET" = "service" ]; then
  echo "[pythonSandbox] Building service image: alphafrog-python-sandbox:latest"
  run_docker_build \
    -t alphafrog-python-sandbox:latest \
    "$SCRIPT_DIR"
fi
