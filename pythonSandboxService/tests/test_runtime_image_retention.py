"""Tests for pythonSandboxService/scripts/prune_runtime_images.sh (spec §12).

Work package H (金融MethodSpec-V5 §12): runtime image retention. The script
must:

* default to plan mode (print the plan, remove nothing); only ``--apply``
  deletes images;
* treat only images labeled ``com.alphafrog.runtime=true`` as candidates;
* never remove the protection set: the current production image, the previous
  generation, images referenced (via the sandbox_task_store_v2 per-task key
  ``runtime_image_ref``) by QUEUED/RUNNING tasks in ``state.json``, and images
  in use by running containers. Refs referenced ONLY by terminal tasks
  (SUCCEEDED/FAILED/CANCELED) are NOT protected; sandbox_task_store_v1 records
  (no ``runtime_image_ref``) are tolerated and gain no extra protection;
* never touch unknown images (no ``com.alphafrog.runtime`` label, or inspect
  failure);
* use explicit machine-readable docker flags only (``--no-trunc`` /
  ``--format`` / ``inspect --type=...``), never parsing table output;
* normalize every identifier (tags, registry digests, bare IDs) to one
  canonical image ID via ``docker inspect`` before protection arithmetic;
* be fail-closed in APPLY mode: any failure while gathering protection or
  classifying candidates aborts with exit code 3 and zero ``rmi`` calls.
  PLAN mode instead prints an "unknown/partial" warning and exits 0.
  ``AF_STATE_FILE`` unset is normal; only set-but-unusable is a failure.

Fake docker CLI contract: the stub logs the FULL argv of every invocation
(one call per line, space-joined) to ``$DOCKER_CALL_LOG`` so tests can assert
the exact flags the script passes. Fixture env hooks:

* ``FAKE_DOCKER_IMAGES_FILE``      canonical image IDs, one per line
                                   (served by ``docker images``);
* ``FAKE_DOCKER_ALIASES_FILE``     ``<ref> <canonical-id>`` lines: tags and
                                   registry digests resolving to image IDs;
* ``FAKE_DOCKER_LABELS_FILE``      ``<canonical-id> <labels-json>`` lines;
* ``FAKE_DOCKER_PS_FILE``          running container IDs, one per line
                                   (served by ``docker ps``);
* ``FAKE_DOCKER_CONTAINERS_FILE``  ``<container-id> <image-id>`` lines
                                   (served by ``inspect --type=container``);
* ``FAKE_DOCKER_FAIL``             ``images`` | ``ps`` | ``inspect`` makes
                                   that subcommand fail with exit 1.
* ``FAKE_DOCKER_FAIL_INSPECT_REF`` ``docker inspect`` fails (exit 1) for
                                   exactly this ONE ref while every other
                                   inspect succeeds. Set it to a candidate
                                   image ID to hit the candidate-classification
                                   branch while protection-set resolution
                                   still succeeds (``FAKE_DOCKER_FAIL=inspect``
                                   fires earlier, at protection resolution,
                                   and never reaches that branch).
* ``FAKE_DOCKER_BUILD_IMAGE_ID``   image ID written by the fake
                                   ``docker build --iidfile`` emulation used
                                   by the docker_build.sh gate tests (BOTH
                                   build phases write the same ID);
* ``FAKE_DOCKER_FAIL_RUNTIME_FINAL_BUILD`` set to ``1`` fails only the
                                   phase-2 build, after the temporary local
                                   bridge tag exists, so cleanup is testable;
* ``FAKE_DOCKER_SMOKE_EXIT``       exit code of the fake ``docker run``
                                   smoke-gate invocations (default 0);
* ``FAKE_DOCKER_INVENTORY_FILE``   inventory JSON document printed by the
                                   fake ``docker run`` inventory query
                                   (round-2 R2-2 gate tests).

The stub also emulates just enough of ``docker compose`` (version/up/ps -q)
and ``docker inspect -f`` health probes for deploy_latest.sh --deploy-only
end-to-end gate tests, a ``docker build`` that writes the iidfile, and a
``docker run`` that serves the round-2 smoke/inventory gates (R2-1/R2-2):
``FAKE_DOCKER_FAIL=run`` makes every ``docker run`` fail.

Fake syft CLI contract (SBOM immutable-ID regressions, Spec §12 immutable
same-origin): the stub written by ``write_fake_syft`` /
``write_fake_syft_recording_target`` logs the FULL argv of every invocation
(one call per line, space-joined) to ``$SYFT_CALL_LOG`` so tests can assert
the EXACT scan target. docker_build.sh must scan the immutable iidfile ID
(``syft "docker:<iidfile-ID>"``), never the mutable :latest tag -- even when
the tag is retargeted at a different image between phase 2 and the SBOM
read (drift simulated via the alias file).

Tests run via ``python3 -m unittest`` using only the standard library +
subprocess.
"""

from __future__ import annotations

import hashlib
import json
import os
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SANDBOX_SERVICE_ROOT = Path(__file__).resolve().parents[1]
PRUNE_SCRIPT = SANDBOX_SERVICE_ROOT / "scripts" / "prune_runtime_images.sh"
REPO_ROOT = SANDBOX_SERVICE_ROOT.parent
DEPLOY_SCRIPT = REPO_ROOT / "deploy_latest.sh"
DOCKER_BUILD_SCRIPT = SANDBOX_SERVICE_ROOT / "docker_build.sh"
RUNTIME_BUILD_DIR = SANDBOX_SERVICE_ROOT / ".runtime-build"
MAPPING_FILE = RUNTIME_BUILD_DIR / "image-digest-mapping.json"
IIDFILE = RUNTIME_BUILD_DIR / "image-id"
LIBRARY_SET_FILE = RUNTIME_BUILD_DIR / "library-set.json"
OCI_LIBRARY_SET_LABEL = "com.alphafrog.librarySetDigest"

# Shared accept/reject vectors (single source of truth pinning identical
# semantics at every digest-validation entry point, Spec §12 hardening).
sys.path.insert(0, str(Path(__file__).resolve().parent))
from digest_reference_vectors import (  # noqa: E402
    ACCEPT_REFS,
    MALFORMED_UNDER_DEV_REFS,
    REJECT_REFS,
    SHA256_ACCEPT,
    SHA256_REJECT,
    SHARED_SHELL_VALIDATOR,
    VALID_DEV_REFERENCES,
)

RUNTIME_LABEL = "com.alphafrog.runtime"

# Documented exit-code contract of the script.
EXIT_OK = 0
EXIT_USAGE_ERROR = 2
EXIT_FAIL_CLOSED = 3

# Fixed fixture image IDs (full 64-hex sha256 image IDs, canonical form).
IMAGE_CURRENT = "sha256:" + "a1" * 32       # current production image
IMAGE_PREVIOUS = "sha256:" + "b2" * 32      # previous generation
IMAGE_TASK_QUEUED = "sha256:" + "c3" * 32   # referenced by QUEUED task
IMAGE_TASK_RUNNING = "sha256:" + "d4" * 32  # referenced by RUNNING task
IMAGE_CONTAINER = "sha256:" + "e5" * 32     # in use by a running container
IMAGE_OLD_RUNTIME = "sha256:" + "f6" * 32   # labeled runtime, unprotected
IMAGE_UNKNOWN = "sha256:" + "07" * 32       # no runtime label -> untouchable
# Referenced ONLY by terminal tasks (FAILED/CANCELED) -> NOT protected.
IMAGE_TASK_TERMINAL_ONLY = "sha256:" + "a7" * 32

# Image ID the fake `docker build` records in --iidfile (build gate tests).
# BOTH build phases (runtime-install stage and final bake) write this same ID.
FAKE_BUILD_IMAGE_ID = "sha256:" + "de" * 32
# A DIFFERENT immutable image ID for target-binding mismatch regressions.
OTHER_IMAGE_ID = "sha256:" + "d3" * 32
# Where the mutable :latest tag is retargeted in the SBOM drift regressions:
# between phase-2 completion and the syft read a concurrent/manual build can
# re-tag latest at a DIFFERENT image (Spec §12 immutable same-origin).
DRIFTED_IMAGE_ID = "sha256:" + "ee" * 32

# Work package A canonical generated-resources fixture shipped with the
# work-package-B runtime suite: the FIVE canonical files (index.json +
# resolver-catalog.json + the three frozen method specs). The build-wiring
# tests point METHOD_SPEC_CANONICAL_DIR here.
CANONICAL_FIXTURES_DIR = (
    SANDBOX_SERVICE_ROOT / "runtime" / "tests" / "fixtures" / "a-generated-resources-v1"
)


def canonical_index_digest() -> str:
    """The sha256 computed from the canonical index.json BYTES — exactly the
    value docker_build.sh computes host-side (Spec §12; never taken on
    trust)."""
    payload = (CANONICAL_FIXTURES_DIR / "index.json").read_bytes()
    return "sha256:" + hashlib.sha256(payload).hexdigest()

# Default fabricated ACTUAL image inventory served by the fake `docker run`
# inventory query (round-2 R2-2): the four lock pins VERBATIM + the real
# alphafrog-finance distribution (apiVersion "1.0") + transitive/base
# packages outside the managed namespace. This inventory VERIFIES CLEANLY
# against requirements-image.lock + runtime/src/alphafrog_finance/__init__.py.
DEFAULT_INVENTORY = {
    "packages": [
        {"name": "alphafrog-finance", "version": "1.0.0"},
        {"name": "matplotlib", "version": "3.10.8"},
        {"name": "numpy", "version": "2.4.1"},
        {"name": "pandas", "version": "2.3.3"},
        {"name": "pip", "version": "24.0"},
        {"name": "python-dateutil", "version": "2.9.0"},
        {"name": "pytz", "version": "2024.2"},
        {"name": "scipy", "version": "1.17.0"},
    ],
    "apiVersion": "1.0",
    "duplicateNames": [],
}

# Legal non-placeholder digests for fabricated deploy mapping entries
# (all five fields deploy_latest.sh R2-3 checks before admitting a deploy).
LEGAL_ENTRY_DIGESTS = {
    "baseImageDigest": "sha256:" + "b0" * 32,
    "lockDigest": "sha256:" + "c0" * 32,
    "librarySetDigest": "sha256:" + "d0" * 32,
    "sbomDigest": "sha256:" + "e0" * 32,
    "methodSpecIndexDigest": "sha256:" + "f0" * 32,
}

# Tags (one per image) -- the "tag" identifier namespace.
TAGS = {
    IMAGE_CURRENT: "registry.local/alphafrog/runtime:current",
    IMAGE_PREVIOUS: "registry.local/alphafrog/runtime:previous",
    IMAGE_TASK_QUEUED: "registry.local/alphafrog/runtime:gen-n-queued",
    IMAGE_TASK_RUNNING: "registry.local/alphafrog/runtime:gen-n-running",
    IMAGE_CONTAINER: "registry.local/alphafrog/runtime:gen-n-inuse",
    IMAGE_OLD_RUNTIME: "registry.local/alphafrog/runtime:gen-old",
    IMAGE_UNKNOWN: "registry.local/somebody/other:v9",
    IMAGE_TASK_TERMINAL_ONLY: "registry.local/alphafrog/runtime:gen-terminal-only",
}
RUNTIME_LABELED = {
    IMAGE_CURRENT,
    IMAGE_PREVIOUS,
    IMAGE_TASK_QUEUED,
    IMAGE_TASK_RUNNING,
    IMAGE_CONTAINER,
    IMAGE_OLD_RUNTIME,
    IMAGE_TASK_TERMINAL_ONLY,
}

# Extra aliases for IMAGE_OLD_RUNTIME in the OTHER identifier namespaces:
# a tag and a registry digest (repo@sha256:...) distinct from its bare ID.
TAG_OLD_RUNTIME_ALT = "registry.local/alphafrog/runtime:gen-old-tag"
REGISTRY_DIGEST_OLD_RUNTIME = (
    "registry.local/alphafrog/runtime@sha256:" + "9d" * 32
)

CONTAINER_ID = "container-0001"

# Fake docker CLI stub. Logs the full argv of every invocation to
# $DOCKER_CALL_LOG (one call per line), then serves canned, machine-readable
# output per subcommand. See module docstring for the fixture contract.
# bash 3.2 compatible.
DOCKER_STUB = r"""#!/usr/bin/env bash
set -u
printf '%s\n' "$*" >> "${DOCKER_CALL_LOG:?DOCKER_CALL_LOG not set}"
cmd="${1:-}"
if [ "$#" -gt 0 ]; then shift; fi

case "$cmd" in
  images)
    if [ "${FAKE_DOCKER_FAIL:-}" = "images" ]; then
      echo "fake docker: images failed" >&2
      exit 1
    fi
    cat "${FAKE_DOCKER_IMAGES_FILE:?}"
    ;;
  build)
    # docker_build.sh gate tests: emulate `docker build --iidfile ...`.
    if [ "${FAKE_DOCKER_FAIL:-}" = "build" ]; then
      echo "fake docker: build failed" >&2
      exit 1
    fi
    if [ "${FAKE_DOCKER_FAIL_RUNTIME_FINAL_BUILD:-}" = "1" ] && \
       grep -q '^alphafrog-runtime-install:' "${FAKE_DOCKER_ALIASES_FILE:?}"; then
      echo "fake docker: final runtime build failed" >&2
      exit 1
    fi
    iid=""
    labels_json="{}"
    while [ "$#" -gt 0 ]; do
      arg="$1"
      shift
      case "$arg" in
        --iidfile=*) iid="${arg#--iidfile=}" ;;
        --iidfile)
          if [ "$#" -gt 0 ]; then iid="$1"; shift; fi
          ;;
        --build-arg=*)
          barg="${arg#--build-arg=}"
          if [ "${barg%%=*}" = "AF_LIBRARY_SET_DIGEST" ]; then
            labels_json="$(LABEL_KEY="com.alphafrog.librarySetDigest" LABEL_VAL="${barg#*=}" LABEL_JSON="$labels_json" python3 -c 'import json,os; d=json.loads(os.environ["LABEL_JSON"]); d[os.environ["LABEL_KEY"]]=os.environ["LABEL_VAL"]; print(json.dumps(d,separators=(",",":")))')"
          fi
          ;;
        --build-arg)
          if [ "$#" -gt 0 ]; then
            barg="$1"; shift
            if [ "${barg%%=*}" = "AF_LIBRARY_SET_DIGEST" ]; then
              labels_json="$(LABEL_KEY="com.alphafrog.librarySetDigest" LABEL_VAL="${barg#*=}" LABEL_JSON="$labels_json" python3 -c 'import json,os; d=json.loads(os.environ["LABEL_JSON"]); d[os.environ["LABEL_KEY"]]=os.environ["LABEL_VAL"]; print(json.dumps(d,separators=(",",":")))')"
            fi
          fi
          ;;
        --label=*)
          lab="${arg#--label=}"
          key="${lab%%=*}"
          val="${lab#*=}"
          labels_json="$(LABEL_KEY="$key" LABEL_VAL="$val" LABEL_JSON="$labels_json" python3 -c 'import json,os; d=json.loads(os.environ["LABEL_JSON"]); d[os.environ["LABEL_KEY"]]=os.environ["LABEL_VAL"]; print(json.dumps(d,separators=(",",":")))')"
          ;;
        --label)
          if [ "$#" -gt 0 ]; then
            lab="$1"; shift
            key="${lab%%=*}"
            val="${lab#*=}"
            labels_json="$(LABEL_KEY="$key" LABEL_VAL="$val" LABEL_JSON="$labels_json" python3 -c 'import json,os; d=json.loads(os.environ["LABEL_JSON"]); d[os.environ["LABEL_KEY"]]=os.environ["LABEL_VAL"]; print(json.dumps(d,separators=(",",":")))')"
          fi
          ;;
      esac
    done
    image_id="${FAKE_DOCKER_BUILD_IMAGE_ID:-sha256:deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef}"
    if [ -n "$iid" ]; then
      printf '%s\n' "$image_id" > "$iid"
    fi
    if [ -n "${FAKE_DOCKER_IMAGES_FILE:-}" ]; then
      if ! grep -qxF "$image_id" "$FAKE_DOCKER_IMAGES_FILE" 2>/dev/null; then
        printf '%s\n' "$image_id" >> "$FAKE_DOCKER_IMAGES_FILE"
      fi
    fi
    if [ -n "${FAKE_DOCKER_ALIASES_FILE:-}" ]; then
      printf '%s %s\n' "$image_id" "$image_id" >> "$FAKE_DOCKER_ALIASES_FILE"
    fi
    # Persist OCI labels for subsequent `docker image inspect` (Tier2a gate).
    if [ -n "${FAKE_DOCKER_LABELS_FILE:-}" ] && [ "$labels_json" != "{}" ]; then
      FAKE_DOCKER_LABELS_FILE="$FAKE_DOCKER_LABELS_FILE" BUILD_ID="$image_id" BUILD_LABELS="$labels_json" python3 -c '
import json, os
path = os.environ["FAKE_DOCKER_LABELS_FILE"]
image_id = os.environ["BUILD_ID"]
new_labels = json.loads(os.environ["BUILD_LABELS"])
rows = []
found = False
if os.path.exists(path):
    for line in open(path, encoding="utf-8"):
        line = line.rstrip("\n")
        if not line.strip():
            continue
        parts = line.split(" ", 1)
        if parts[0] == image_id:
            existing = json.loads(parts[1]) if len(parts) > 1 else {}
            existing.update(new_labels)
            rows.append(image_id + " " + json.dumps(existing, separators=(",", ":")))
            found = True
        else:
            rows.append(line)
if not found:
    rows.append(image_id + " " + json.dumps(new_labels, separators=(",", ":")))
open(path, "w", encoding="utf-8").write("\n".join(rows) + "\n")
'
    fi
    ;;
  tag)
    # docker_build.sh uses a temporary, process-unique tag to bridge the
    # phase-1 local image into BuildKit's phase-2 FROM. Record that alias so
    # the immediate `docker image inspect` equality check can resolve it.
    source_ref="${1:-}"
    target_ref="${2:-}"
    if [ -z "$source_ref" ] || [ -z "$target_ref" ]; then
      echo "fake docker: tag requires source and target" >&2
      exit 1
    fi
    canon=""
    while read -r alias id; do
      if [ "$alias" = "$source_ref" ]; then canon="$id"; break; fi
    done < "${FAKE_DOCKER_ALIASES_FILE:?}"
    if [ -z "$canon" ]; then
      while IFS= read -r id; do
        if [ "$id" = "$source_ref" ]; then canon="$id"; break; fi
      done < "${FAKE_DOCKER_IMAGES_FILE:?}"
    fi
    if [ -z "$canon" ]; then
      echo "fake docker: no such source image $source_ref" >&2
      exit 1
    fi
    printf '%s %s\n' "$target_ref" "$canon" >> "${FAKE_DOCKER_ALIASES_FILE:?}"
    ;;
  run)
    # docker_build.sh round-2 gate tests: serve the smoke gate (R2-1) and the
    # actual-inventory query (R2-2) that run against the phase-1 image.
    if [ "${FAKE_DOCKER_FAIL:-}" = "run" ]; then
      echo "fake docker: run failed" >&2
      exit 1
    fi
    case " $* " in
      *smoke_runtime_image.py*)
        echo "smoke OK [fake interpreter]"
        exit "${FAKE_DOCKER_SMOKE_EXIT:-0}"
        ;;
      *runtime_image_inventory.py*)
        if [ -z "${FAKE_DOCKER_INVENTORY_FILE:-}" ]; then
          echo "fake docker: FAKE_DOCKER_INVENTORY_FILE not set" >&2
          exit 1
        fi
        cat "${FAKE_DOCKER_INVENTORY_FILE}"
        exit 0
        ;;
    esac
    exit 0
    ;;
  compose)
    # Minimal `docker compose` emulation for deploy_latest.sh --deploy-only
    # gate tests: answer version/ps -q so the deploy wait-loop resolves fast.
    case "${1:-}" in
      version)
        echo "Docker Compose version v2.0.0-fake"
        ;;
      ps)
        if [ "${2:-}" = "-q" ]; then
          echo "fakecid-${3:-svc}"
        fi
        ;;
    esac
    exit 0
    ;;
  ps)
    if [ "${FAKE_DOCKER_FAIL:-}" = "ps" ]; then
      echo "fake docker: ps failed" >&2
      exit 1
    fi
    cat "${FAKE_DOCKER_PS_FILE:?}"
    ;;
  image)
    case "${1:-}" in
      inspect)
        # Used both by the phase-bridge equality check and the D15 Tier2a OCI
        # label probe. Fall through into the shared inspect implementation.
        shift
        set -- inspect "$@"
        cmd="inspect"
        ;;
      rm)
        # Removing a tag must remove only that alias, not the underlying
        # immutable image ID. The shell stub uses Python for literal matching.
        shift
        for ref in "$@"; do
          FAKE_ALIAS_REF="$ref" python3 -c '
import os
path = os.environ["FAKE_DOCKER_ALIASES_FILE"]
ref = os.environ["FAKE_ALIAS_REF"]
rows = []
if os.path.exists(path):
    for line in open(path, encoding="utf-8"):
        if line.rstrip("\n").split(" ", 1)[0] != ref:
            rows.append(line)
open(path, "w", encoding="utf-8").writelines(rows)
'
          echo "Untagged: $ref"
        done
        exit 0
        ;;
      *)
        exit 0
        ;;
    esac
    ;;
esac
# Shared inspect path (also reached after rewriting `image inspect`).
if [ "$cmd" = "inspect" ]; then
    if [ "${FAKE_DOCKER_FAIL:-}" = "inspect" ]; then
      echo "fake docker: inspect failed" >&2
      exit 1
    fi
    typ=""
    fmt=""
    refs=""
    while [ "$#" -gt 0 ]; do
      arg="$1"
      shift
      case "$arg" in
        inspect) ;;
        --type=*) typ="${arg#--type=}" ;;
        --type)
          if [ "$#" -gt 0 ]; then typ="$1"; shift; fi
          ;;
        --format=*) fmt="${arg#--format=}" ;;
        --format)
          if [ "$#" -gt 0 ]; then fmt="$1"; shift; fi
          ;;
        -f)
          # deploy_latest.sh wait-loop uses the short form `inspect -f`.
          if [ "$#" -gt 0 ]; then fmt="$1"; shift; fi
          ;;
        -*) ;;
        *) refs="${refs}${arg}"$'\n' ;;
      esac
    done
    status=0
    while IFS= read -r ref; do
      [ -n "$ref" ] || continue
      if [ -n "${FAKE_DOCKER_FAIL_INSPECT_REF:-}" ] && [ "$ref" = "$FAKE_DOCKER_FAIL_INSPECT_REF" ]; then
        echo "fake docker: inspect failed for $ref" >&2
        status=1
        continue
      fi
      # deploy_latest.sh wait-loop probes (short-form `-f`) are answered
      # directly; the fake compose container IDs are not resolvable images.
      case "$fmt" in
        *State.Health*) echo "healthy"; continue ;;
        *State.Running*) echo "true"; continue ;;
      esac
      canon=""
      if [ "$typ" = "container" ]; then
        while read -r cid iid; do
          if [ "$cid" = "$ref" ]; then canon="$iid"; break; fi
        done < "${FAKE_DOCKER_CONTAINERS_FILE:?}"
      else
        while read -r alias id; do
          if [ "$alias" = "$ref" ]; then canon="$id"; break; fi
        done < "${FAKE_DOCKER_ALIASES_FILE:?}"
        if [ -z "$canon" ]; then
          while IFS= read -r id; do
            if [ "$id" = "$ref" ]; then canon="$id"; break; fi
          done < "${FAKE_DOCKER_IMAGES_FILE:?}"
        fi
      fi
      if [ -z "$canon" ]; then
        echo "Error response from daemon: No such object: $ref" >&2
        status=1
        continue
      fi
      case "$fmt" in
        *librarySetDigest*)
          labels="{}"
          while read -r id lab; do
            if [ "$id" = "$canon" ]; then labels="$lab"; break; fi
          done < "${FAKE_DOCKER_LABELS_FILE:?}"
          FAKE_LABELS_JSON="$labels" python3 -c 'import json,os; d=json.loads(os.environ["FAKE_LABELS_JSON"] or "{}"); print(d.get("com.alphafrog.librarySetDigest",""))'
          ;;
        *Labels*)
          labels="null"
          while read -r id lab; do
            if [ "$id" = "$canon" ]; then labels="$lab"; break; fi
          done < "${FAKE_DOCKER_LABELS_FILE:?}"
          echo "$canon $labels"
          ;;
        *)
          echo "$canon"
          ;;
      esac
    done <<< "$refs"
    exit "$status"
fi
case "$cmd" in
  rmi)
    for ref in "$@"; do
      echo "Untagged: $ref"
    done
    ;;
  *)
    exit 0
    ;;
esac
exit 0
"""


class RuntimeImageRetentionTestBase(unittest.TestCase):
    """Shared fixture plumbing: fake docker CLI on PATH + fixture state.json."""

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory(prefix="af-prune-test-")
        tmp = Path(self._tmp.name)
        self.addCleanup(self._tmp.cleanup)

        # Fake docker CLI prepended to PATH.
        self.stub_dir = tmp
        stub_path = tmp / "docker"
        stub_path.write_text(DOCKER_STUB, encoding="utf-8")
        stub_path.chmod(stub_path.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP)
        self.call_log = tmp / "docker-calls.log"
        self.call_log.write_text("", encoding="utf-8")

        # Canned `docker images --no-trunc --format '{{.ID}}'` output:
        # canonical image IDs, one per line.
        images_file = tmp / "docker-images.txt"
        images_file.write_text(
            "\n".join(sorted(TAGS)) + "\n", encoding="utf-8"
        )

        # Alias map: tags + registry digests -> canonical image IDs.
        # NB: TAGS maps image-id -> tag; the alias file is "<ref> <image-id>".
        alias_pairs = [(tag, image_id) for image_id, tag in TAGS.items()]
        alias_pairs.append((TAG_OLD_RUNTIME_ALT, IMAGE_OLD_RUNTIME))
        alias_pairs.append((REGISTRY_DIGEST_OLD_RUNTIME, IMAGE_OLD_RUNTIME))
        aliases_file = tmp / "docker-aliases.txt"
        aliases_file.write_text(
            "\n".join(f"{ref} {image_id}" for ref, image_id in alias_pairs)
            + "\n",
            encoding="utf-8",
        )

        # Labels per canonical image ID (labels decide candidacy).
        labels_file = tmp / "docker-labels.txt"
        labels_lines = []
        for image_id in TAGS:
            labels = {}
            if image_id in RUNTIME_LABELED:
                labels[RUNTIME_LABEL] = "true"
            labels_lines.append(f"{image_id} {json.dumps(labels)}")
        labels_file.write_text("\n".join(labels_lines) + "\n", encoding="utf-8")

        # Canned `docker ps --no-trunc --format '{{.ID}}'` output: container
        # IDs; the container->image map is a separate fixture.
        ps_file = tmp / "docker-ps.txt"
        ps_file.write_text(CONTAINER_ID + "\n", encoding="utf-8")
        containers_file = tmp / "docker-containers.txt"
        containers_file.write_text(
            f"{CONTAINER_ID} {IMAGE_CONTAINER}\n", encoding="utf-8"
        )

        # Fixture state.json (durable schema sandbox_task_store_v2: the
        # per-task image reference is the snake_case `runtime_image_ref`).
        # QUEUED/RUNNING refs are protected; refs referenced only by terminal
        # tasks (SUCCEEDED/FAILED/CANCELED) are NOT protected.
        self.state_file = tmp / "state.json"
        self.state_file.write_text(
            json.dumps(
                {
                    "schema_version": "sandbox_task_store_v2",
                    "tasks": {
                        "task-queued": {
                            "status": "QUEUED",
                            "runtime_image_ref": IMAGE_TASK_QUEUED,
                        },
                        "task-running": {
                            "status": "RUNNING",
                            "runtime_image_ref": IMAGE_TASK_RUNNING,
                        },
                        "task-done": {
                            "status": "SUCCEEDED",
                            "runtime_image_ref": IMAGE_OLD_RUNTIME,
                        },
                        "task-failed-terminal": {
                            "status": "FAILED",
                            "runtime_image_ref": IMAGE_TASK_TERMINAL_ONLY,
                        },
                        "task-canceled-terminal": {
                            "status": "CANCELED",
                            "runtime_image_ref": IMAGE_TASK_TERMINAL_ONLY,
                        },
                    },
                }
            ),
            encoding="utf-8",
        )

        # Default fabricated ACTUAL image inventory for the round-2 inventory
        # gate (R2-2): served by the fake `docker run` inventory query. Tests
        # overwrite this file (or the env hook) to inject drift.
        self.inventory_file = tmp / "image-inventory.json"
        self.inventory_file.write_text(
            json.dumps(DEFAULT_INVENTORY), encoding="utf-8"
        )

        self.env = dict(os.environ)
        self.env["PATH"] = str(tmp) + os.pathsep + self.env.get("PATH", "")
        self.env["DOCKER_CALL_LOG"] = str(self.call_log)
        self.env["FAKE_DOCKER_IMAGES_FILE"] = str(images_file)
        self.env["FAKE_DOCKER_ALIASES_FILE"] = str(aliases_file)
        self.env["FAKE_DOCKER_LABELS_FILE"] = str(labels_file)
        self.env["FAKE_DOCKER_PS_FILE"] = str(ps_file)
        self.env["FAKE_DOCKER_CONTAINERS_FILE"] = str(containers_file)
        self.env["FAKE_DOCKER_INVENTORY_FILE"] = str(self.inventory_file)
        self.env["AF_STATE_FILE"] = str(self.state_file)
        self.env["AF_CURRENT_RUNTIME_IMAGE"] = IMAGE_CURRENT
        self.env["AF_PREVIOUS_RUNTIME_IMAGE"] = IMAGE_PREVIOUS

    def add_alias(self, ref: str, image_id: str) -> None:
        """Make the fake ``docker inspect`` resolve ``ref`` to ``image_id``."""
        with open(self.env["FAKE_DOCKER_ALIASES_FILE"], "a", encoding="utf-8") as fh:
            fh.write(f"{ref} {image_id}\n")

    def register_image_id(self, image_id: str) -> None:
        """Ensure bare immutable image IDs resolve in fake ``docker inspect``."""
        images_path = Path(self.env["FAKE_DOCKER_IMAGES_FILE"])
        existing = images_path.read_text(encoding="utf-8")
        if image_id not in existing.splitlines():
            images_path.write_text(existing.rstrip("\n") + "\n" + image_id + "\n", encoding="utf-8")
        self.add_alias(image_id, image_id)

    def set_image_labels(self, image_id: str, labels: dict) -> None:
        """Merge ``labels`` into the fake docker Labels fixture for ``image_id``."""
        path = Path(self.env["FAKE_DOCKER_LABELS_FILE"])
        rows: list[str] = []
        found = False
        if path.exists():
            for line in path.read_text(encoding="utf-8").splitlines():
                if not line.strip():
                    continue
                parts = line.split(" ", 1)
                if parts[0] == image_id:
                    existing = json.loads(parts[1]) if len(parts) > 1 else {}
                    existing.update(labels)
                    rows.append(image_id + " " + json.dumps(existing, separators=(",", ":")))
                    found = True
                else:
                    rows.append(line)
        if not found:
            rows.append(image_id + " " + json.dumps(labels, separators=(",", ":")))
        path.write_text("\n".join(rows) + "\n", encoding="utf-8")

    def write_library_set_file(self, library_set_digest: str = LEGAL_ENTRY_DIGESTS["librarySetDigest"]) -> None:
        RUNTIME_BUILD_DIR.mkdir(parents=True, exist_ok=True)
        LIBRARY_SET_FILE.write_text(
            json.dumps(
                {
                    "apiVersion": "1.0",
                    "librarySetDigest": library_set_digest,
                    "packages": [],
                }
            ),
            encoding="utf-8",
        )

    def write_iidfile(self, image_id: str = FAKE_BUILD_IMAGE_ID) -> None:
        RUNTIME_BUILD_DIR.mkdir(parents=True, exist_ok=True)
        IIDFILE.write_text(image_id + "\n", encoding="utf-8")

    def write_mapping_file(self, mapping) -> None:
        """Write the build-artifact image-digest-mapping.json consumed by the
        deploy_latest.sh R2-3 target-binding gate (str == raw bytes, dict ==
        JSON-serialized). Removes the build dir again during cleanup."""
        RUNTIME_BUILD_DIR.mkdir(parents=True, exist_ok=True)
        if isinstance(mapping, str):
            MAPPING_FILE.write_text(mapping, encoding="utf-8")
        else:
            MAPPING_FILE.write_text(json.dumps(mapping), encoding="utf-8")
        self.addCleanup(shutil.rmtree, str(RUNTIME_BUILD_DIR), True)

    def write_deploy_build_artifacts(
        self,
        mapping=None,
        *,
        image_id: str = FAKE_BUILD_IMAGE_ID,
        library_set_digest: str | None = None,
        install_oci_label: bool = True,
    ) -> None:
        """Write mapping + iidfile + library-set.json and optional OCI label.

        D15-A deploy gates require all three build artifacts before the
        historical mapping/releasable assertions can fire.
        """
        if mapping is None:
            mapping = self.bound_mapping(image_id=image_id)
        if library_set_digest is None:
            if isinstance(mapping, dict):
                entry = mapping.get("images", {}).get(image_id) or {}
                library_set_digest = entry.get(
                    "librarySetDigest", LEGAL_ENTRY_DIGESTS["librarySetDigest"]
                )
            else:
                library_set_digest = LEGAL_ENTRY_DIGESTS["librarySetDigest"]
        self.write_mapping_file(mapping)
        self.write_iidfile(image_id)
        self.write_library_set_file(library_set_digest)
        self.register_image_id(image_id)
        if install_oci_label:
            self.set_image_labels(
                image_id, {OCI_LIBRARY_SET_LABEL: library_set_digest}
            )

    def bound_mapping(self, image_id: str = FAKE_BUILD_IMAGE_ID, **entry_overrides) -> dict:
        """A single-entry mapping whose entry BINDS ``image_id`` (its key) and
        carries all-legal non-placeholder digests; ``entry_overrides`` mutates
        individual entry fields (e.g. releasable=False, placeholder digests)."""
        entry = dict(LEGAL_ENTRY_DIGESTS)
        entry["buildRevision"] = "git:" + "00" * 20
        entry["incompleteInputs"] = []
        entry["releasable"] = True
        entry.update(entry_overrides)
        return {"schemaVersion": "1", "images": {image_id: entry}}

    def run_prune(self, *args: str) -> subprocess.CompletedProcess:
        if not PRUNE_SCRIPT.is_file():
            self.fail(f"pending implementation: {PRUNE_SCRIPT} does not exist yet (spec §12)")
        return subprocess.run(
            ["bash", str(PRUNE_SCRIPT), *args],
            env=self.env,
            capture_output=True,
            text=True,
            timeout=60,
            check=False,
        )

    def docker_calls(self) -> list[list[str]]:
        """Full argv of every fake-docker invocation, one list per call."""
        calls: list[list[str]] = []
        for line in self.call_log.read_text(encoding="utf-8").splitlines():
            if line.strip():
                calls.append(line.split())
        return calls

    def calls_for(self, subcommand: str) -> list[list[str]]:
        return [call for call in self.docker_calls() if call and call[0] == subcommand]

    def rmi_targets(self) -> list[str]:
        targets: list[str] = []
        for parts in self.calls_for("rmi"):
            targets.extend(parts[1:])
        return targets

    def assert_rmi_succeeds_cleanly(self, result: subprocess.CompletedProcess) -> None:
        self.assertEqual(result.returncode, 0, f"stdout={result.stdout}\nstderr={result.stderr}")

    def assert_fail_closed(self, result: subprocess.CompletedProcess) -> None:
        """APPLY-mode failure: zero rmi invocations, documented abort exit 3."""
        self.assertEqual(
            self.rmi_targets(),
            [],
            f"fail-closed violation: rmi ran despite a protection failure\n"
            f"stdout={result.stdout}\nstderr={result.stderr}",
        )
        self.assertEqual(
            result.returncode,
            EXIT_FAIL_CLOSED,
            f"expected fail-closed exit {EXIT_FAIL_CLOSED}, got {result.returncode}\n"
            f"stdout={result.stdout}\nstderr={result.stderr}",
        )

    def assert_plan_warns_unknown_and_exits_zero(self, result: subprocess.CompletedProcess) -> None:
        """PLAN-mode failure: no rmi, exit 0, unknown/partial state reported."""
        self.assertEqual(self.rmi_targets(), [], "plan mode must not delete anything")
        self.assertEqual(
            result.returncode, 0, f"stdout={result.stdout}\nstderr={result.stderr}"
        )
        combined = (result.stdout + result.stderr).lower()
        self.assertIn(
            "unknown",
            combined,
            f"plan mode must report the unknown/partial state\n"
            f"stdout={result.stdout}\nstderr={result.stderr}",
        )


class PlanModeTest(RuntimeImageRetentionTestBase):
    pass
class DockerFlagUsageTest(RuntimeImageRetentionTestBase):
    """The script must use machine-readable docker flags, never table output."""

class ApplyModeCandidacyTest(RuntimeImageRetentionTestBase):
    pass
class ProtectionSetTest(RuntimeImageRetentionTestBase):
    pass
class CrossNamespaceProtectionTest(RuntimeImageRetentionTestBase):
    """A tag, a registry digest and a bare ID that all point at one image
    must each protect it: all identifiers are normalized to the canonical
    image ID via docker inspect before protection arithmetic."""

    def apply_with_current(self, ref: str) -> subprocess.CompletedProcess:
        # Pin IMAGE_OLD_RUNTIME as the current production image through the
        # identifier form under test; demote the old current to previous.
        self.env["AF_CURRENT_RUNTIME_IMAGE"] = ref
        self.env["AF_PREVIOUS_RUNTIME_IMAGE"] = IMAGE_CURRENT
        return self.run_prune("--apply")

    def assert_old_runtime_protected(self, result: subprocess.CompletedProcess) -> None:
        self.assertEqual(
            result.returncode, 0, f"stdout={result.stdout}\nstderr={result.stderr}"
        )
        self.assertNotIn(
            IMAGE_OLD_RUNTIME,
            self.rmi_targets(),
            f"protection via the tested identifier form missed the image\n"
            f"rmi_targets={self.rmi_targets()}\nstdout={result.stdout}",
        )
        # The canonical ID of the protected image must be reported protected.
        self.assertIn(f"protected: {IMAGE_OLD_RUNTIME}", result.stdout)

class ApplyFailClosedTest(RuntimeImageRetentionTestBase):
    """APPLY mode must abort (exit 3, zero rmi) on ANY protection failure."""

class PlanModePartialKnowledgeTest(RuntimeImageRetentionTestBase):
    """PLAN mode may continue on failures but must report unknown state."""

class TaskStoreRetentionSeamTest(RuntimeImageRetentionTestBase):
    """Item 2 seam tests over fabricated state.json fixtures.

    The durable state.json schema ``sandbox_task_store_v2`` freezes the
    per-task image reference as the snake_case key ``runtime_image_ref``:
    QUEUED/RUNNING refs are ALWAYS protected; refs referenced only by
    terminal tasks (SUCCEEDED/FAILED/CANCELED) are NOT protected; v1 records
    (no ``runtime_image_ref``) are tolerated and gain no extra protection.
    """

    def write_state(self, state: dict) -> None:
        self.state_file.write_text(json.dumps(state), encoding="utf-8")

class ShellDigestReferenceSharedVectorTest(RuntimeImageRetentionTestBase):
    """Item 1: the shared shell validator must implement the SAME anchored,
    lowercase-only semantics as the Python entry points, pinned by the exact
    shared vectors (single source of truth)."""

    def shell_verdict(self, function: str, value: str) -> bool:
        self.assertTrue(
            SHARED_SHELL_VALIDATOR.is_file(),
            f"shared shell validator missing: {SHARED_SHELL_VALIDATOR}",
        )
        proc = subprocess.run(
            [
                "bash",
                "-c",
                f'source "$1"; {function} "$2"',
                "_",
                str(SHARED_SHELL_VALIDATOR),
                value,
            ],
            capture_output=True,
            text=True,
            timeout=30,
            check=False,
        )
        self.assertIn(
            proc.returncode,
            (0, 1),
            f"validator errored for {value!r}: rc={proc.returncode}\n"
            f"stdout={proc.stdout}\nstderr={proc.stderr}",
        )
        return proc.returncode == 0

class DeployLatestImageGateTest(RuntimeImageRetentionTestBase):
    """Item 1 at the deploy entry point: deploy_latest.sh must enforce the
    anchored, lowercase-only digest-reference semantics (same shared vectors)
    and keep the explicit dev-tag allow switch explicit-only (round-2 R2-4:
    the dev switch admits ONLY syntactically valid bare references).

    Success-path tests additionally satisfy the R2-3 target-binding gate:
    the fake inspect resolves every reference under test to the one image ID
    bound by a releasable mapping entry (setUp)."""

    def setUp(self) -> None:
        super().setUp()
        refs = [ACCEPT_REFS[0], "alphafrog-sandbox-runtime:latest", *VALID_DEV_REFERENCES]
        for ref in refs:
            self.add_alias(ref, FAKE_BUILD_IMAGE_ID)
        self.write_deploy_build_artifacts(self.bound_mapping())

    def deploy_env(self, **overrides: str) -> dict:
        env = dict(self.env)
        for key in (
            "AF_SANDBOX_IMAGE",
            "AF_SANDBOX_IMAGE_ALLOW_DEV_TAG",
            "AF_SANDBOX_ALLOW_INCOMPLETE_DEV_BUILD",
            "AF_SANDBOX_IMAGE_VERIFY_MODE",
            "AF_SANDBOX_IMAGE_TAG_CHECK",
        ):
            env.pop(key, None)
        # These deploy tests pin the Spec §12 strict-release path; the
        # 260814 default (local-image-id) is covered by
        # DeployLocalImageIdModeTest below.
        env["AF_SANDBOX_IMAGE_VERIFY_MODE"] = "strict-release"
        env.update(overrides)
        return env

    def run_deploy(self, env: dict, *args: str) -> subprocess.CompletedProcess:
        self.assertTrue(DEPLOY_SCRIPT.is_file(), f"missing: {DEPLOY_SCRIPT}")
        return subprocess.run(
            ["bash", str(DEPLOY_SCRIPT), *args],
            env=env,
            capture_output=True,
            text=True,
            timeout=180,
            check=False,
        )

class DeployReleaseGateTest(RuntimeImageRetentionTestBase):
    """Item 3 at the deploy entry point: deploy_latest.sh must refuse to
    deploy a non-releasable build (fail-closed); the only escape hatch is the
    explicit AF_SANDBOX_ALLOW_INCOMPLETE_DEV_BUILD switch."""

    IMAGE_DIGEST = FAKE_BUILD_IMAGE_ID

    def setUp(self) -> None:
        super().setUp()
        self.assertFalse(
            RUNTIME_BUILD_DIR.exists(),
            f"unexpected pre-existing build dir: {RUNTIME_BUILD_DIR}",
        )
        # R2-3 target binding: the deploy gate resolves the chosen reference
        # via `docker inspect`; make it resolve to the immutable image ID the
        # fabricated mapping entries bind (their key).
        self.add_alias(ACCEPT_REFS[0], FAKE_BUILD_IMAGE_ID)

    def write_mapping(self, mapping) -> None:
        # Keep the historical helper name, but always install the D15-A
        # iidfile + library-set + OCI label companions so reverse tests still
        # reach their intended mapping/releasable assertions.
        library_set_digest = LEGAL_ENTRY_DIGESTS["librarySetDigest"]
        if isinstance(mapping, dict):
            entry = (mapping.get("images") or {}).get(FAKE_BUILD_IMAGE_ID) or {}
            digest = entry.get("librarySetDigest") if isinstance(entry, dict) else None
            if isinstance(digest, str) and digest.startswith("sha256:") and len(digest) == 71:
                library_set_digest = digest
        self.write_deploy_build_artifacts(
            mapping,
            image_id=FAKE_BUILD_IMAGE_ID,
            library_set_digest=library_set_digest,
        )

    def mapping(self, releasable, incomplete=()) -> dict:
        entry = {
            "baseImageDigest": "sha256:" + "b0" * 32,
            "lockDigest": "sha256:" + "c0" * 32,
            "librarySetDigest": "sha256:" + "d0" * 32,
            "sbomDigest": "sha256:" + "e0" * 32,
            "methodSpecIndexDigest": "sha256:" + "f0" * 32,
            "buildRevision": "git:" + "00" * 20,
            "incompleteInputs": list(incomplete),
        }
        if releasable is not None:
            entry["releasable"] = releasable
        return {"schemaVersion": "1", "images": {self.IMAGE_DIGEST: entry}}

    def run_deploy_with_releasable_gate(self, **extra_env: str) -> subprocess.CompletedProcess:
        env = dict(self.env)
        for key in ("AF_SANDBOX_IMAGE", "AF_SANDBOX_IMAGE_ALLOW_DEV_TAG", "AF_SANDBOX_ALLOW_INCOMPLETE_DEV_BUILD"):
            env.pop(key, None)
        # These deploy-gate tests pin the Spec §12 strict-release path.
        env["AF_SANDBOX_IMAGE_VERIFY_MODE"] = "strict-release"
        env["AF_SANDBOX_IMAGE"] = ACCEPT_REFS[0]
        env.update(extra_env)
        return subprocess.run(
            ["bash", str(DEPLOY_SCRIPT), "--deploy-only", "python-sandbox-service"],
            env=env,
            capture_output=True,
            text=True,
            timeout=180,
            check=False,
        )

    # --- round-2 R2-3 target-binding regressions -------------------------
    # The deploy target must be provably identical to EXACTLY ONE mapping
    # entry; every failure class below aborts BEFORE any deployment, and the
    # dev switches never relax target binding (only the releasable verdict).

class DockerBuildReleaseGateTest(RuntimeImageRetentionTestBase):
    """Items 1+3 at the build entry point: docker_build.sh runtime must
    fail-closed on missing/placeholder release inputs, reject malformed
    digests (anchored lowercase-only), and mark dev-switch builds
    releasable=false in the external mapping."""

    # Deliberately NOT the digest of the canonical index.json bytes: since
    # the digest is now COMPUTED host-side, this value serves only as a
    # well-formed-but-disagreeing cross-check vector.
    METHOD_DIGEST = "sha256:" + "11" * 32
    BASE_DIGEST = "sha256:" + "22" * 32

    def setUp(self) -> None:
        super().setUp()
        self.addCleanup(shutil.rmtree, str(RUNTIME_BUILD_DIR), True)
        # Full argv log of every fake-syft invocation (one call per line).
        self.syft_call_log = self.stub_dir / "syft-calls.log"
        self.syft_call_log.write_text("", encoding="utf-8")

    def build_env(self, **overrides: str) -> dict:
        env = dict(self.env)
        for key in (
            "BASE_IMAGE_DIGEST",
            "METHOD_SPEC_INDEX_DIGEST",
            "AF_SANDBOX_ALLOW_INCOMPLETE_DEV_BUILD",
            "AF_SANDBOX_IMAGE_VERIFY_MODE",
        ):
            env.pop(key, None)
        # These build-gate tests pin the Spec §12 strict-release path; the
        # 260814 default (local-image-id) is covered by
        # DockerBuildLocalImageIdModeTest below.
        env["AF_SANDBOX_IMAGE_VERIFY_MODE"] = "strict-release"
        env["USE_PROXY"] = "0"
        # Minimal PATH: stub docker + system dirs only. This keeps the
        # presence/absence of syft deterministic across machines.
        env["PATH"] = str(self.stub_dir) + os.pathsep + "/usr/bin" + os.pathsep + "/bin"
        env["FAKE_DOCKER_BUILD_IMAGE_ID"] = FAKE_BUILD_IMAGE_ID
        env["SYFT_CALL_LOG"] = str(self.syft_call_log)
        # Round-2 R2-1: the A-canonical method-spec inputs are a hard build
        # material. Point the gate at the fixture directory shipped with the
        # work-package-B runtime suite (resolver-catalog.json + the three
        # frozen method specs); the fake `docker run` serves the default
        # verified inventory (base setUp FAKE_DOCKER_INVENTORY_FILE).
        env["METHOD_SPEC_CANONICAL_DIR"] = str(CANONICAL_FIXTURES_DIR)
        env.update(overrides)
        return env

    def write_fake_syft(self, exit_code: int, output: str = '{"fake": "sbom"}') -> None:
        syft_path = self.stub_dir / "syft"
        syft_path.write_text(
            "#!/usr/bin/env bash\n"
            'printf \'%s\\n\' "$*" >> "${SYFT_CALL_LOG:?SYFT_CALL_LOG not set}"\n'
            f"printf '%s' {output!r}\n"
            f"exit {exit_code}\n",
            encoding="utf-8",
        )
        syft_path.chmod(syft_path.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP)

    def write_fake_syft_recording_target(self, exit_code: int = 0) -> None:
        """Fake syft that (like the real docker provider) records the image
        reference it was asked to scan into the SBOM source metadata, so the
        tests can assert WHICH image identity the sbomDigest evidence binds.
        Full argv is logged to $SYFT_CALL_LOG."""
        syft_path = self.stub_dir / "syft"
        syft_path.write_text(
            "#!/usr/bin/env bash\n"
            'printf \'%s\\n\' "$*" >> "${SYFT_CALL_LOG:?SYFT_CALL_LOG not set}"\n'
            'printf \'{"source": {"target": "%s"}, "artifacts": []}\' "${1:-}"\n'
            f"exit {exit_code}\n",
            encoding="utf-8",
        )
        syft_path.chmod(syft_path.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP)

    def syft_calls(self) -> list[list[str]]:
        """Full argv of every fake-syft invocation, one list per call."""
        return [
            line.split()
            for line in self.syft_call_log.read_text(encoding="utf-8").splitlines()
            if line.strip()
        ]

    def run_build(self, env: dict) -> subprocess.CompletedProcess:
        self.assertTrue(DOCKER_BUILD_SCRIPT.is_file(), f"missing: {DOCKER_BUILD_SCRIPT}")
        return subprocess.run(
            ["bash", str(DOCKER_BUILD_SCRIPT), "runtime"],
            env=env,
            capture_output=True,
            text=True,
            timeout=180,
            check=False,
        )

    def mapping_entry(self) -> dict:
        mapping = json.loads(MAPPING_FILE.read_text(encoding="utf-8"))
        return mapping["images"][FAKE_BUILD_IMAGE_ID]

    # --- round-2 R2-1: canonical inputs + bindings generator gates --------
    # The A-canonical generated JSON is a HARD build material: missing inputs
    # fail closed BEFORE any docker build, and the dev switch does NOT admit
    # them (it is not a release-time placeholder).

    # --- round-2 R2-1: smoke gate (docker run, both interpreters) ---------

    # --- round-2 R2-2: actual-inventory gate (fail-closed compare) --------

    def mutated_inventory(self, **api_overrides) -> dict:
        drift = json.loads(json.dumps(DEFAULT_INVENTORY))
        drift.update(api_overrides)
        return drift

class SyftImmutableIdRegressionTest(DockerBuildReleaseGateTest):
    """Spec §12 immutable same-origin at the SBOM step (reviewer codex
    653674d9): after phase 2 obtains the iidfile's exact immutable image ID,
    syft must scan EXACTLY that ID -- never the mutable :latest tag, which a
    concurrent/manual build can retarget between phase-2 completion and the
    syft read. The SBOM/mapping evidence and the deploy gate bind ONLY the
    immutable ID; the local convenience tag stays a NON-evidence alias and
    never enters the SBOM/mapping/deploy chain -- even under tag drift."""

    LATEST_TAG = "alphafrog-sandbox-runtime:latest"

    def run_verified_build_with_drifting_latest(self) -> subprocess.CompletedProcess:
        """Full verified runtime build (releasable inputs + working syft)
        while the mutable :latest tag is retargeted at a DIFFERENT image
        between phase 2 and the SBOM read (alias-file drift)."""
        self.add_alias(self.LATEST_TAG, DRIFTED_IMAGE_ID)
        self.write_fake_syft_recording_target(0)
        env = self.build_env(
            BASE_IMAGE_DIGEST=self.BASE_DIGEST,
            METHOD_SPEC_INDEX_DIGEST=canonical_index_digest(),
        )
        result = self.run_build(env)
        self.assertEqual(
            result.returncode,
            0,
            f"stdout={result.stdout}\nstderr={result.stderr}",
        )
        return result

    def run_deploy_gate(self, image: str, **extra_env: str) -> subprocess.CompletedProcess:
        env = dict(self.env)
        for key in (
            "AF_SANDBOX_IMAGE",
            "AF_SANDBOX_IMAGE_ALLOW_DEV_TAG",
            "AF_SANDBOX_ALLOW_INCOMPLETE_DEV_BUILD",
        ):
            env.pop(key, None)
        env["AF_SANDBOX_IMAGE"] = image
        # These deploy-gate tests pin the Spec §12 strict-release path.
        env["AF_SANDBOX_IMAGE_VERIFY_MODE"] = "strict-release"
        env.update(extra_env)
        self.assertTrue(DEPLOY_SCRIPT.is_file(), f"missing: {DEPLOY_SCRIPT}")
        return subprocess.run(
            ["bash", str(DEPLOY_SCRIPT), "--deploy-only", "python-sandbox-service"],
            env=env,
            capture_output=True,
            text=True,
            timeout=180,
            check=False,
        )

class DeployLocalImageIdModeTest(RuntimeImageRetentionTestBase):
    """260814 scheduler-03: deploy_latest.sh local-image-id mode (the new
    default). The configured AF_SANDBOX_IMAGE must BE a bare local Image ID
    (sha256:<64hex>) and docker inspect must resolve to exactly that ID; tags
    and repo digests are rejected without any dev-allow escape, and the
    strict-release mapping/Tier2a chain is not required."""

    def run_deploy_local(self, **extra_env: str) -> subprocess.CompletedProcess:
        env = dict(self.env)
        for key in (
            "AF_SANDBOX_IMAGE",
            "AF_SANDBOX_IMAGE_ALLOW_DEV_TAG",
            "AF_SANDBOX_ALLOW_INCOMPLETE_DEV_BUILD",
            "AF_SANDBOX_IMAGE_VERIFY_MODE",
            "AF_SANDBOX_IMAGE_TAG_CHECK",
        ):
            env.pop(key, None)
        env.update(extra_env)
        self.assertTrue(DEPLOY_SCRIPT.is_file(), f"missing: {DEPLOY_SCRIPT}")
        return subprocess.run(
            ["bash", str(DEPLOY_SCRIPT), "--deploy-only", "python-sandbox-service"],
            env=env,
            capture_output=True,
            text=True,
            timeout=180,
            check=False,
        )

class DockerBuildLocalImageIdModeTest(RuntimeImageRetentionTestBase):
    """260814 scheduler-03: docker_build.sh in local-image-id mode (default)
    skips the strict-release inputs (base digest / SBOM / external mapping)
    but keeps the real gates (smoke + inventory) and prints the final
    immutable Image ID for deploy config."""

    def build_env_local(self, **overrides: str) -> dict:
        env = dict(self.env)
        for key in (
            "BASE_IMAGE_DIGEST",
            "METHOD_SPEC_INDEX_DIGEST",
            "AF_SANDBOX_ALLOW_INCOMPLETE_DEV_BUILD",
            "AF_SANDBOX_IMAGE_VERIFY_MODE",
        ):
            env.pop(key, None)
        # Default mode (local-image-id) is exercised by NOT setting the mode.
        env["USE_PROXY"] = "0"
        env["PATH"] = str(self.stub_dir) + os.pathsep + "/usr/bin" + os.pathsep + "/bin"
        env["FAKE_DOCKER_BUILD_IMAGE_ID"] = FAKE_BUILD_IMAGE_ID
        env["METHOD_SPEC_CANONICAL_DIR"] = str(CANONICAL_FIXTURES_DIR)
        env.update(overrides)
        return env

    def run_build_local(self, env: dict) -> subprocess.CompletedProcess:
        self.assertTrue(DOCKER_BUILD_SCRIPT.is_file(), f"missing: {DOCKER_BUILD_SCRIPT}")
        return subprocess.run(
            ["bash", str(DOCKER_BUILD_SCRIPT), "runtime"],
            env=env,
            capture_output=True,
            text=True,
            timeout=180,
            check=False,
        )

if __name__ == "__main__":
    unittest.main()
