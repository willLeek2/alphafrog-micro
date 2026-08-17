#!/bin/bash
# AlphaFrog pythonSandboxService image build orchestration.
#
# MethodSpec V5 work package H (Spec §12) wiring for the `runtime` target
# (round-2 flow, R2-1/R2-2):
#   1. lockDigest = sha256 of requirements-image.lock (the four runtime
#      packages moved verbatim out of the old Dockerfile inline literal);
#   2. canonical method-spec inputs gate: work package A's canonical
#      generated JSON (index.json + resolver-catalog.json + the three method
#      specs -- FIVE files) must exist at METHOD_SPEC_CANONICAL_DIR -- a hard
#      build material, missing inputs fail CLOSED with no dev-switch escape;
#      methodSpecIndexDigest is COMPUTED here from the canonical index.json
#      BYTES (never taken on trust); an optionally supplied
#      METHOD_SPEC_INDEX_DIGEST env value is a cross-check only and must
#      equal the computed digest or the build fails closed; then
#      runtime/scripts/generate_method_bindings.py runs BEFORE the build and
#      writes runtime/src/alphafrog_finance/_generated/{method_specs.json,
#      docstrings.py,call_samples.py} with --package-version extracted from
#      alphafrog_finance.__version__ (hand-copied method triples are
#      forbidden, Spec §6); a generator failure aborts the build;
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
#      canonical index.json (re-hashed INSIDE the image against the
#      host-computed methodSpecIndexDigest -- mismatch fails closed) plus the
#      verified library-set.json, and sets the OCI labels (labels are static
#      metadata, so librarySetDigest can only be set after verification);
#   8. AFTER the final image ID is known, a second invocation writes the
#      external imageDigest -> digests mapping OUTSIDE the image (never
#      written back into image labels -- self-reference prohibition, §12);
#      the mapping entry binds the immutable image ID (deploy target binding,
#      R2-3);
#   9. SBOM generation is a documented OPTIONAL hook: syft is not required to
#      install, but a build without a verified SBOM is NOT releasable (see
#      the release gate below); agents never fabricate digests. When syft
#      runs, it scans EXACTLY THIS build's immutable iidfile image ID
#      (`syft "docker:${image_digest}"`) -- NEVER the mutable :latest tag:
#      between phase 2 and the SBOM read the tag can be retargeted by
#      another build, so a latest-based scan could attribute a DIFFERENT
#      image's SBOM to this build's exact imageDigest (Spec §12 immutable
#      same-origin proof). The `-t ...:latest` tag on phase 2 is a
#      NON-evidence alias for local convenience ONLY: it never enters the
#      SBOM, the external mapping, or the deploy chain.
#
# RELEASE GATE (Spec §12 hardening, fail-closed):
#   The runtime build DEFAULT-FAILS when any release input is missing or a
#   REPLACE_WITH_... placeholder: BASE_IMAGE_DIGEST (verified base-image
#   digest pinned by frog) or the SBOM input (syft available). The ONLY
#   escape hatch is the explicit, independent dev switch
#   AF_SANDBOX_ALLOW_INCOMPLETE_DEV_BUILD=true/1 (never implicit): it lets a
#   structural dev build proceed, but the external digest mapping is then
#   marked releasable=false and deploy_latest.sh refuses to deploy it unless
#   the same explicit switch is set there too.
#   methodSpecIndexDigest is NOT a release input: it is computed from the
#   canonical index.json bytes (a HARD build material below), so it can never
#   be missing once the canonical gate passes.
#
# Runtime target inputs (never invented here):
#   BASE_IMAGE_DIGEST        verified base-image digest, sha256:<64 lowercase
#                            hex>, pinned by frog; defaults to the explicit
#                            REPLACE_WITH_... placeholder token, which fails
#                            the release gate until frog pins the real value.
#   METHOD_SPEC_INDEX_DIGEST OPTIONAL cross-check only: when set (non-empty,
#                            not a REPLACE_WITH_... placeholder) it must be
#                            EXACTLY the sha256 computed from the canonical
#                            index.json bytes or the build fails closed. When
#                            unset, the computed digest is used directly.
#   METHOD_SPEC_CANONICAL_DIR
#                            directory holding work package A's canonical
#                            generated method-spec JSON: FIVE files --
#                            index.json, resolver-catalog.json and one spec
#                            file per frozen method. Defaults to
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
  bash docker_build.sh runtime    # build the runtime image only
  bash docker_build.sh service    # build alphafrog-python-sandbox:latest only

The runtime image carries the local convenience tag
alphafrog-sandbox-runtime:latest, but that tag is a NON-evidence alias:
SBOM, external mapping and deploy gates bind ONLY the immutable --iidfile
image ID (Spec §12 immutable same-origin).

Runtime target (Spec §12) environment inputs:
  BASE_IMAGE_DIGEST          verified base-image digest, sha256:<64 lowercase
                             hex>, pinned by frog (defaults to the
                             REPLACE_WITH_... placeholder; the release gate
                             fails until frog pins the verified value)
  METHOD_SPEC_INDEX_DIGEST   OPTIONAL cross-check: when set, must equal the
                             sha256 computed from the canonical index.json
                             bytes or the build fails closed (unset => the
                             computed digest is used directly)
  METHOD_SPEC_CANONICAL_DIR  work package A's canonical generated method-spec
                             JSON directory (default: agentToolsShared/target/
                             generated-resources/finance/method-specs/v1);
                             FIVE files required: index.json,
                             resolver-catalog.json + the three method specs.
                             Missing inputs fail CLOSED (hard build material;
                             the dev switch does not admit it)

Release gate (fail-closed): missing/placeholder release inputs abort the
runtime build. The ONLY escape hatch is the explicit dev switch
  AF_SANDBOX_ALLOW_INCOMPLETE_DEV_BUILD=true|1
which allows a structural dev build but marks it releasable=false
(deploy_latest.sh refuses non-releasable builds unless the same switch is
set). The switch is never implicit.

Build order: canonical inputs (five-file gate + index digest computed from
bytes) -> bindings generator (three build products) -> pip install (phase 1)
-> smoke gate (system python + compat venv) -> actual-inventory query +
fail-closed compare -> bake index.json (in-image re-hash gate) +
library-set.json + labels (phase 2) -> SBOM -> external mapping -> final
release gate.

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

  # 260814 scheduler-03: verify-mode selection. local-image-id (default) is
  # the officially supported single-machine mode: the build still runs the
  # import checks, smoke gate and inventory gate, prints the final immutable
  # Image ID for deploy config, and does NOT require the registry release
  # inputs (base digest / SBOM / external mapping / Tier2a). strict-release
  # keeps the full Spec §12 release chain as the build success condition.
  local verify_mode="${AF_SANDBOX_IMAGE_VERIFY_MODE:-local-image-id}"
  case "$verify_mode" in
    local-image-id|strict-release) ;;
    *)
      echo "[pythonSandbox] ERROR: AF_SANDBOX_IMAGE_VERIFY_MODE must be local-image-id or strict-release; got '${verify_mode}'." >&2
      exit 1
      ;;
  esac
  echo "[pythonSandbox] verify-mode=${verify_mode}"

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
  #    260814 scheduler-03: a strict-release-only input. In local-image-id
  #    mode the base digest is not part of the single-machine contract.
  local base_image_digest="${BASE_IMAGE_DIGEST:-REPLACE_WITH_VERIFIED_BASE_IMAGE_DIGEST}"
  if [ "$verify_mode" = "strict-release" ]; then
    if is_missing_or_placeholder "$base_image_digest"; then
      incomplete_inputs+=("BASE_IMAGE_DIGEST")
      echo "[pythonSandbox] WARNING: BASE_IMAGE_DIGEST is missing or a REPLACE_WITH_... placeholder." >&2
      echo "[pythonSandbox]   frog pins the verified base-image digest at release time (Spec §12);" >&2
      echo "[pythonSandbox]   until then the build is NOT releasable." >&2
    else
      require_sha256_value "BASE_IMAGE_DIGEST" "$base_image_digest"
    fi
  else
    echo "[pythonSandbox] local-image-id mode: BASE_IMAGE_DIGEST is not a build input."
  fi

  # 3) methodSpecIndexDigest: NOT a release input (round FINAL): it is
  #    COMPUTED from the canonical index.json bytes after the five-file
  #    canonical gate below (step 4b), so it can never be missing or a
  #    placeholder once the gate passes. An optionally supplied
  #    METHOD_SPEC_INDEX_DIGEST env value is a cross-check only and must
  #    equal the computed digest or the build fails closed there.

  # SBOM input: syft availability is known before the build. Without syft the
  # SBOM digest stays a placeholder -> incomplete input (fail-closed gate).
  # 260814 scheduler-03: strict-release-only input; local-image-id builds do
  # not generate or require a SBOM.
  local syft_available=0
  if [ "$verify_mode" = "strict-release" ]; then
    if command -v syft >/dev/null 2>&1; then
      syft_available=1
    else
      incomplete_inputs+=("SBOM_DIGEST")
      echo "[pythonSandbox] WARNING: syft not installed - SBOM cannot be generated." >&2
      echo "[pythonSandbox]   sbomDigest would stay the REPLACE_WITH_... placeholder; until frog" >&2
      echo "[pythonSandbox]   produces the verified SBOM the build is NOT releasable." >&2
    fi
  else
    echo "[pythonSandbox] local-image-id mode: SBOM is not a build input."
  fi

  # RELEASE GATE: fail-closed. Incomplete inputs abort the build BEFORE any
  # `docker build` unless the explicit, independent dev switch is set.
  # 260814 scheduler-03: strict-release-only gate; local-image-id builds have
  # no registry release inputs and never enter this block.
  if [ "$verify_mode" = "strict-release" ] && [ "${#incomplete_inputs[@]}" -gt 0 ]; then
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
  #    The gate covers ALL FIVE canonical files: index.json (the authoritative
  #    manifest), resolver-catalog.json and the three frozen method specs.
  local canonical_dir="${METHOD_SPEC_CANONICAL_DIR:-$REPO_ROOT/agentToolsShared/target/generated-resources/finance/method-specs/v1}"
  local canonical_file
  if [ ! -d "$canonical_dir" ]; then
    echo "[pythonSandbox] ERROR: METHOD_SPEC_CANONICAL_DIR is not a directory: $canonical_dir" >&2
    echo "[pythonSandbox]   The runtime build requires work package A's canonical generated" >&2
    echo "[pythonSandbox]   method-spec JSON (index.json + resolver-catalog.json + the three" >&2
    echo "[pythonSandbox]   frozen method specs). Build A's generated resources first or point" >&2
    echo "[pythonSandbox]   METHOD_SPEC_CANONICAL_DIR at them (fail-closed, Spec §12 R2-1)." >&2
    exit 1
  fi
  for canonical_file in index.json resolver-catalog.json cagr.json annualized_volatility.json sharpe_ratio.json; do
    if [ ! -f "$canonical_dir/$canonical_file" ]; then
      echo "[pythonSandbox] ERROR: canonical method-spec input missing: $canonical_dir/$canonical_file" >&2
      echo "[pythonSandbox]   All FIVE A-canonical JSON files are required (fail-closed, Spec §12 R2-1)." >&2
      exit 1
    fi
  done
  echo "[pythonSandbox] canonical method specs: $canonical_dir"

  # 4b) methodSpecIndexDigest is COMPUTED from the canonical index.json BYTES
  #     (never taken on trust, never fabricated; the gate above guarantees the
  #     file). An optionally supplied METHOD_SPEC_INDEX_DIGEST env value is a
  #     cross-check ONLY: it must equal the computed digest or the build fails
  #     closed (a disagreement means the caller pinned a different index than
  #     the one actually being baked -- Spec §12 fail-closed).
  local method_spec_index_digest
  method_spec_index_digest="$(file_sha256 "$canonical_dir/index.json")"
  echo "[pythonSandbox] methodSpecIndexDigest=${method_spec_index_digest} (computed from ${canonical_dir}/index.json)"
  if ! is_missing_or_placeholder "${METHOD_SPEC_INDEX_DIGEST:-}"; then
    require_sha256_value "METHOD_SPEC_INDEX_DIGEST" "${METHOD_SPEC_INDEX_DIGEST}"
    if [ "${METHOD_SPEC_INDEX_DIGEST}" != "$method_spec_index_digest" ]; then
      echo "[pythonSandbox] ERROR: METHOD_SPEC_INDEX_DIGEST=${METHOD_SPEC_INDEX_DIGEST} does NOT match" >&2
      echo "[pythonSandbox]   the digest computed from the canonical index.json bytes" >&2
      echo "[pythonSandbox]   (${method_spec_index_digest}). The build bakes ONLY the canonical" >&2
      echo "[pythonSandbox]   index it gated on; refusing the mismatch (Spec §12 fail-closed)." >&2
      exit 1
    fi
    echo "[pythonSandbox] METHOD_SPEC_INDEX_DIGEST cross-check OK (matches the computed digest)"
  fi

  # 5) Build-time bindings generator (R2-1 + registry swap): BEFORE
  #    pip-installing the distribution, generate ALL THREE build products
  #    into runtime/src/alphafrog_finance/_generated/ (method_specs.json,
  #    docstrings.py, call_samples.py) from the A-canonical inputs. The
  #    installed package resolves ALL method identity from these products
  #    (Spec §6 registry swap; hand-maintained identity is forbidden).
  #    --package-version is extracted fail-closed from
  #    alphafrog_finance.__version__ so the generator can verify every
  #    libraryBinding apiCompatRange against the actual package version. The
  #    generator is fail-closed (non-zero exit, no partial output on ANY
  #    problem) and its failure aborts the build before `docker build` runs.
  local generator_script="$SCRIPT_DIR/runtime/scripts/generate_method_bindings.py"
  local generated_dir="$SCRIPT_DIR/runtime/src/alphafrog_finance/_generated"
  local package_init="$SCRIPT_DIR/runtime/src/alphafrog_finance/__init__.py"
  local package_version
  if ! package_version="$(python3 -c '
import re, sys
try:
    with open(sys.argv[1], encoding="utf-8") as fh:
        source = fh.read()
except OSError:
    sys.exit(1)
match = re.search(r"^__version__\s*=\s*\"([^\"]+)\"", source, re.MULTILINE)
if match is None:
    sys.exit(1)
print(match.group(1))
' "$package_init")" || [ -z "$package_version" ]; then
    echo "[pythonSandbox] ERROR: cannot extract __version__ from $package_init (fail-closed, Spec §6)." >&2
    exit 1
  fi
  if ! python3 "$generator_script" \
      --canonical-dir "$canonical_dir" \
      --out "$generated_dir/method_specs.json" \
      --docstrings-out "$generated_dir/docstrings.py" \
      --call-samples-out "$generated_dir/call_samples.py" \
      --package-version "$package_version"; then
    echo "[pythonSandbox] ERROR: method-bindings generator FAILED (fail-closed, Spec §12 R2-1)." >&2
    echo "[pythonSandbox]   No build product was written; the build aborts before docker build." >&2
    exit 1
  fi

  # 6) buildRevision, e.g. git:<commit> (Spec §12); empty outside a git checkout.
  local build_revision=""
  if git -C "$REPO_ROOT" rev-parse --verify HEAD >/dev/null 2>&1; then
    build_revision="git:$(git -C "$REPO_ROOT" rev-parse --verify HEAD)"
  fi

  # 7) FROM ref: local-image-id mode intentionally uses the local base tag;
  #    strict-release uses the verified digest and falls back to the bare tag
  #    only behind its explicit incomplete-build development switch.
  local runtime_base_image_ref
  if is_missing_or_placeholder "$base_image_digest"; then
    runtime_base_image_ref="python:${RUNTIME_BASE_IMAGE_TAG:-3.13-slim}"
    if [ "$verify_mode" = "local-image-id" ]; then
      echo "[pythonSandbox] local-image-id mode: building FROM local base tag '${runtime_base_image_ref}'."
    else
      echo "[pythonSandbox] WARNING: dev structural build FROM bare base tag '${runtime_base_image_ref}'" >&2
      echo "[pythonSandbox]   (placeholder base digest + explicit dev switch). NOT releasable." >&2
    fi
  else
    runtime_base_image_ref="python:${RUNTIME_BASE_IMAGE_TAG:-3.13-slim}@${base_image_digest}"
  fi

  # 8) PHASE 1 build (runtime-install stage): installs the locked library set
  #    and pip-installs the REAL alphafrog_finance distribution from runtime/.
  #    BuildKit parses every FROM before honoring --target. Give the phase-2
  #    FROM a syntactically valid reference during phase 1 so the Dockerfile's
  #    loud-fail default placeholder cannot abort parsing. The runtime-install
  #    target never executes phase 2; the real phase-1 immutable ID still
  #    replaces this value in the second build below.
  local iid_install_file="$out_dir/image-install-stage-id"
  rm -f "$iid_install_file"
  run_docker_build \
    --target runtime-install \
    -f "$SCRIPT_DIR/Dockerfile.runtime" \
    --iidfile "$iid_install_file" \
    --build-arg "RUNTIME_BASE_IMAGE_REF=${runtime_base_image_ref}" \
    --build-arg "AF_RUNTIME_INSTALL_IMAGE=${runtime_base_image_ref}" \
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
  #     canonical index.json (re-hashed in-image against the computed
  #     methodSpecIndexDigest) plus the verified library-set.json and sets
  #     the OCI labels. --iidfile captures the FINAL immutable image ID.
  #     The Dockerfile COPYs .runtime-build/index.json, so stage the SAME
  #     bytes the digest was computed from (never a re-serialization).
  cp "$canonical_dir/index.json" "$out_dir/index.json"
  local iid_file="$out_dir/image-id"
  rm -f "$iid_file"
  # NOTE: the `-t alphafrog-sandbox-runtime:latest` tag is a NON-evidence
  # alias kept ONLY for local convenience (docker run by tag during dev). It
  # NEVER enters the SBOM, the external mapping, or the deploy chain: all
  # evidence below binds the immutable --iidfile image ID exclusively
  # (Spec §12 immutable same-origin; the tag can be retargeted by any
  # concurrent/manual build at any time).
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
  # Fail-closed: the phase-2 iidfile must carry EXACTLY sha256:<64 lowercase
  # hex>. Every downstream binding -- the syft SBOM scan target, the external
  # mapping key, the deploy gate -- derives from this value ALONE; a
  # malformed ID must never enter evidence (Spec §12 immutable same-origin).
  require_sha256_value "phase-2 --iidfile image ID" "$image_digest"

  # 13) SBOM: documented optional hook. syft need not be installed, but a
  #     build without a verified SBOM is NOT releasable (release gate). Never
  #     a fabricated digest: frog generates the verified SBOM at release time.
  #
  #     syft scans EXACTLY THIS build's immutable image ID read from the
  #     phase-2 --iidfile (docker:${image_digest}) -- NEVER the mutable
  #     :latest tag. Between phase-2 completion and this syft read the tag
  #     can be retargeted by another concurrent/manual build; scanning the
  #     tag could therefore write the sbomDigest of a DIFFERENT image into
  #     THIS build's exact-imageDigest mapping, breaking the Spec §12
  #     immutable same-origin proof. `docker build -t ...:latest` above is
  #     not a basis for SBOM binding either: the tag is a non-evidence
  #     local alias only.
  local sbom_digest="REPLACE_WITH_VERIFIED_SBOM_DIGEST"
  if [ "$verify_mode" = "strict-release" ]; then
    if [ "$syft_available" -eq 1 ]; then
      echo "[pythonSandbox] syft detected: generating SBOM for the immutable image ${image_digest}..."
      if syft "docker:${image_digest}" -o json > "$out_dir/sbom.json"; then
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
  else
    echo "[pythonSandbox] local-image-id mode: skipping SBOM generation (strict-release only)."
  fi

  # 14) Second invocation: AFTER the image ID is known, write the external
  #     imageDigest -> digests mapping OUTSIDE the image (Spec §12). It is
  #     never written back into image labels (self-reference prohibition) and
  #     is consumed by deploy config and audit queries. Incomplete release
  #     inputs are recorded so the entry carries releasable=false (release
  #     gate). The entry BINDS the immutable image ID (its key) and records
  #     the SAME immutable ID as its imageRef alias, so deploy_latest.sh can
  #     prove the deploy target identical to EXACTLY ONE mapping entry (R2-3
  #     target binding). The MUTABLE :latest tag is deliberately NOT
  #     recorded: a mutable tag can drift to another image between build and
  #     deploy, so it is never digest evidence (Spec §12 immutable
  #     same-origin; build_runtime_manifest.py rejects non-immutable
  #     imageRef values fail-closed). The mapping carries the SAME verified
  #     librarySetDigest as the baked library-set.json and the OCI label
  #     (R2-2).
  local incomplete_args=()
  local incomplete_name
  for incomplete_name in ${incomplete_inputs[@]+"${incomplete_inputs[@]}"}; do
    incomplete_args+=(--incomplete-input "$incomplete_name")
  done
  # 260814 scheduler-03: the external digest mapping is strict-release
  # evidence. In local-image-id mode it is not written at all -- the deploy
  # contract for local mode is the bare Image ID, verified by docker inspect.
  if [ "$verify_mode" = "strict-release" ]; then
    python3 "$manifest_script" \
      --lock-digest "$lock_digest" \
      --method-spec-index-digest "$method_spec_index_digest" \
      --packages-file "$verified_packages_file" \
      --output "$out_dir/library-set.json" \
      --mapping-output "$out_dir/image-digest-mapping.json" \
      --image-digest "$image_digest" \
      --image-ref "${image_digest}" \
      --base-image-digest "$base_image_digest" \
      --sbom-digest "$sbom_digest" \
      --build-revision "$build_revision" \
      ${incomplete_args[@]+"${incomplete_args[@]}"}
  else
    echo "[pythonSandbox] local-image-id mode: skipping external digest mapping (strict-release only)."
  fi

  # FINAL RELEASE GATE: if any release input ended up incomplete (e.g. syft
  # failed during the build), the build fails closed AFTER the non-releasable
  # mapping was written for audit -- unless the explicit dev switch is set.
  # 260814 scheduler-03: strict-release-only gate.
  if [ "$verify_mode" = "strict-release" ] && [ "${#incomplete_inputs[@]}" -gt 0 ]; then
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

  if [ "$verify_mode" = "local-image-id" ]; then
    echo "[pythonSandbox] === local-image-id mode ==="
    echo "[pythonSandbox] verified local Image ID: ${image_digest}"
    echo "[pythonSandbox] deploy config: AF_SANDBOX_IMAGE=${image_digest}"
    echo "[pythonSandbox]               AF_SANDBOX_IMAGE_VERIFY_MODE=local-image-id"
    echo "[pythonSandbox] STATUS: local single-machine build OK (strict-release chain not required in this mode)."
    echo "[pythonSandbox] NOTE: image release (registry push) and production config changes"
    echo "[pythonSandbox]       remain frog's final decision (Spec §12); this script never pushes."
    return 0
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
  echo "[pythonSandbox] Building runtime image (local alias alphafrog-sandbox-runtime:latest;"
  echo "[pythonSandbox]   SBOM/mapping/deploy evidence binds ONLY the immutable iidfile ID)"
  build_runtime_image
fi

if [ "$BUILD_TARGET" = "all" ] || [ "$BUILD_TARGET" = "service" ]; then
  echo "[pythonSandbox] Building service image: alphafrog-python-sandbox:latest"
  run_docker_build \
    -t alphafrog-python-sandbox:latest \
    "$SCRIPT_DIR"
fi
