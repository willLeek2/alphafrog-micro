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
    # `docker image inspect ...` — used by D15 Tier2a OCI label probe.
    if [ "${1:-}" != "inspect" ]; then
      exit 0
    fi
    shift
    # Fall through into the shared inspect implementation below by
    # re-entering this script as `inspect ...` would (no bash-4 ;& needed).
    set -- inspect "$@"
    cmd="inspect"
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
    def test_plan_mode_by_default_never_invokes_rmi(self) -> None:
        result = self.run_prune()
        self.assert_rmi_succeeds_cleanly(result)
        self.assertEqual(self.rmi_targets(), [], "plan mode must not delete anything")


class DockerFlagUsageTest(RuntimeImageRetentionTestBase):
    """The script must use machine-readable docker flags, never table output."""

    def test_images_ps_and_inspect_use_explicit_format_flags(self) -> None:
        result = self.run_prune()
        self.assert_rmi_succeeds_cleanly(result)

        images_calls = self.calls_for("images")
        ps_calls = self.calls_for("ps")
        inspect_calls = self.calls_for("inspect")
        self.assertTrue(images_calls, "script never invoked docker images")
        self.assertTrue(ps_calls, "script never invoked docker ps")
        self.assertTrue(inspect_calls, "script never invoked docker inspect")

        for call in images_calls + ps_calls:
            self.assertIn("--no-trunc", call, f"missing --no-trunc: {call}")
            self.assertIn("--format", call, f"missing --format: {call}")
        for call in inspect_calls:
            self.assertIn("--format", call, f"inspect without --format: {call}")

        # Resolution goes through typed inspect: image refs AND containers.
        self.assertTrue(
            any("--type=image" in call for call in inspect_calls),
            f"no inspect --type=image call found: {inspect_calls}",
        )
        self.assertTrue(
            any("--type=container" in call for call in inspect_calls),
            f"no inspect --type=container call found: {inspect_calls}",
        )


class ApplyModeCandidacyTest(RuntimeImageRetentionTestBase):
    def test_apply_only_targets_runtime_labeled_images(self) -> None:
        result = self.run_prune("--apply")
        self.assert_rmi_succeeds_cleanly(result)
        targets = set(self.rmi_targets())
        self.assertTrue(
            targets <= RUNTIME_LABELED,
            f"non-runtime-labeled images became rmi candidates: {targets - RUNTIME_LABELED}",
        )
        self.assertNotIn(IMAGE_UNKNOWN, targets)
        # The one labeled, unprotected image is the only expected candidate.
        self.assertIn(IMAGE_OLD_RUNTIME, targets)

    def test_unknown_images_are_never_touched(self) -> None:
        result = self.run_prune("--apply")
        self.assert_rmi_succeeds_cleanly(result)
        self.assertNotIn(
            IMAGE_UNKNOWN,
            self.rmi_targets(),
            "unknown (unlabeled) image must never be removed",
        )


class ProtectionSetTest(RuntimeImageRetentionTestBase):
    def test_protection_set_is_never_removed(self) -> None:
        result = self.run_prune("--apply")
        self.assert_rmi_succeeds_cleanly(result)
        targets = set(self.rmi_targets())
        protected = {
            IMAGE_CURRENT,      # current production image
            IMAGE_PREVIOUS,     # previous generation
            IMAGE_TASK_QUEUED,  # QUEUED task in state.json
            IMAGE_TASK_RUNNING,  # RUNNING task in state.json
            IMAGE_CONTAINER,    # in use by a running container
        }
        self.assertEqual(
            targets & protected,
            set(),
            f"protected images were removed: {targets & protected}",
        )


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

    def test_tag_reference_protects_image(self) -> None:
        result = self.apply_with_current(TAG_OLD_RUNTIME_ALT)
        self.assert_old_runtime_protected(result)

    def test_registry_digest_reference_protects_image(self) -> None:
        result = self.apply_with_current(REGISTRY_DIGEST_OLD_RUNTIME)
        self.assert_old_runtime_protected(result)

    def test_bare_image_id_protects_image(self) -> None:
        result = self.apply_with_current(IMAGE_OLD_RUNTIME)
        self.assert_old_runtime_protected(result)


class ApplyFailClosedTest(RuntimeImageRetentionTestBase):
    """APPLY mode must abort (exit 3, zero rmi) on ANY protection failure."""

    def test_apply_ps_failure_zero_rmi_and_nonzero_exit(self) -> None:
        self.env["FAKE_DOCKER_FAIL"] = "ps"
        result = self.run_prune("--apply")
        self.assert_fail_closed(result)

    def test_apply_images_failure_zero_rmi_and_nonzero_exit(self) -> None:
        self.env["FAKE_DOCKER_FAIL"] = "images"
        result = self.run_prune("--apply")
        self.assert_fail_closed(result)

    def test_apply_corrupt_state_file_zero_rmi_and_nonzero_exit(self) -> None:
        self.state_file.write_text("{ this is not json", encoding="utf-8")
        result = self.run_prune("--apply")
        self.assert_fail_closed(result)

    def test_apply_missing_state_file_zero_rmi_and_nonzero_exit(self) -> None:
        # Set-but-unusable: AF_STATE_FILE points at a nonexistent file.
        self.env["AF_STATE_FILE"] = str(Path(self._tmp.name) / "no-such-state.json")
        result = self.run_prune("--apply")
        self.assert_fail_closed(result)

    def test_apply_unresolvable_protection_entry_zero_rmi_and_nonzero_exit(self) -> None:
        # Inspect failure while resolving a protection entry (dangling ref).
        self.env["AF_CURRENT_RUNTIME_IMAGE"] = "registry.local/alphafrog/runtime:gone"
        result = self.run_prune("--apply")
        self.assert_fail_closed(result)
        self.assertTrue(
            self.calls_for("inspect"),
            "expected the script to attempt inspect resolution of the entry",
        )

    def test_apply_global_inspect_failure_zero_rmi_and_nonzero_exit(self) -> None:
        self.env["FAKE_DOCKER_FAIL"] = "inspect"
        result = self.run_prune("--apply")
        self.assert_fail_closed(result)

    def test_apply_single_candidate_inspect_failure_zero_rmi_and_exit_3(self) -> None:
        # Coverage for the CANDIDATE-CLASSIFICATION branch, which the global
        # FAKE_DOCKER_FAIL=inspect hook never reaches (it fires earlier, at
        # protection-set resolution). Here protection-set resolution SUCCEEDS
        # and docker inspect fails for exactly ONE candidate image: APPLY must
        # fail closed with exit 3, zero rmi invocations, and the failure
        # reported via report_problem (stderr) -- not silently skipped.
        self.env["FAKE_DOCKER_FAIL_INSPECT_REF"] = IMAGE_OLD_RUNTIME
        result = self.run_prune("--apply")
        self.assert_fail_closed(result)
        # Failure reported on stderr, naming the unclassifiable candidate.
        self.assertIn(IMAGE_OLD_RUNTIME, result.stderr)
        self.assertIn("fail-closed", result.stderr)
        # Proof the abort happened at classification AFTER the protection set
        # was fully resolved: every protected ref was inspect-resolved, and
        # the failing candidate received the Labels-format classify inspect.
        inspect_calls = self.calls_for("inspect")
        for ref in (
            IMAGE_CURRENT,       # AF_CURRENT_RUNTIME_IMAGE
            IMAGE_PREVIOUS,      # AF_PREVIOUS_RUNTIME_IMAGE
            IMAGE_TASK_QUEUED,   # QUEUED task digest in state.json
            IMAGE_TASK_RUNNING,  # RUNNING task digest in state.json
            IMAGE_CONTAINER,     # image of a running container
        ):
            self.assertTrue(
                any(ref in call for call in inspect_calls),
                f"protection ref {ref} was never inspect-resolved before the "
                f"classification abort: {inspect_calls}",
            )
        classify_calls = [
            call for call in inspect_calls if IMAGE_OLD_RUNTIME in call
        ]
        self.assertTrue(
            classify_calls,
            "expected the classification inspect call for the failing candidate",
        )
        self.assertTrue(
            any(any("Labels" in token for token in call) for call in classify_calls),
            f"the failing inspect must be the Labels-format classification "
            f"inspect, got: {classify_calls}",
        )

    def test_apply_unset_state_file_is_normal_not_a_failure(self) -> None:
        # AF_STATE_FILE unset is normal operation: proceed, exit 0.
        del self.env["AF_STATE_FILE"]
        result = self.run_prune("--apply")
        self.assert_rmi_succeeds_cleanly(result)
        self.assertIn(IMAGE_OLD_RUNTIME, self.rmi_targets())


class PlanModePartialKnowledgeTest(RuntimeImageRetentionTestBase):
    """PLAN mode may continue on failures but must report unknown state."""

    def test_plan_ps_failure_warns_unknown_and_exits_zero(self) -> None:
        self.env["FAKE_DOCKER_FAIL"] = "ps"
        result = self.run_prune()
        self.assert_plan_warns_unknown_and_exits_zero(result)

    def test_plan_images_failure_warns_unknown_and_exits_zero(self) -> None:
        self.env["FAKE_DOCKER_FAIL"] = "images"
        result = self.run_prune()
        self.assert_plan_warns_unknown_and_exits_zero(result)

    def test_plan_corrupt_state_file_warns_unknown_and_exits_zero(self) -> None:
        self.state_file.write_text("{ this is not json", encoding="utf-8")
        result = self.run_prune()
        self.assert_plan_warns_unknown_and_exits_zero(result)

    def test_plan_unresolvable_protection_entry_warns_unknown_and_exits_zero(self) -> None:
        self.env["AF_CURRENT_RUNTIME_IMAGE"] = "registry.local/alphafrog/runtime:gone"
        result = self.run_prune()
        self.assert_plan_warns_unknown_and_exits_zero(result)

    def test_plan_single_candidate_inspect_failure_warns_unknown_and_exits_zero(self) -> None:
        # Same single-candidate inspect failure, but PLAN mode: the unknown
        # image is skipped (never planned for removal), the plan continues
        # and exits 0 with an unknown/partial warning.
        self.env["FAKE_DOCKER_FAIL_INSPECT_REF"] = IMAGE_OLD_RUNTIME
        result = self.run_prune()
        self.assert_plan_warns_unknown_and_exits_zero(result)
        self.assertIn(IMAGE_OLD_RUNTIME, result.stderr)
        self.assertNotIn(f"would remove: {IMAGE_OLD_RUNTIME}", result.stdout)


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

    def test_v2_queued_and_running_refs_always_protected(self) -> None:
        # Pin the normally-unprotected IMAGE_OLD_RUNTIME through QUEUED and
        # RUNNING tasks (one via bare ID, one via a tag alias -> both must
        # resolve to the same canonical image and protect it).
        self.write_state(
            {
                "schema_version": "sandbox_task_store_v2",
                "tasks": {
                    "t-queued": {
                        "status": "QUEUED",
                        "runtime_image_ref": IMAGE_OLD_RUNTIME,
                    },
                    "t-running": {
                        "status": "RUNNING",
                        "runtime_image_ref": TAG_OLD_RUNTIME_ALT,
                    },
                },
            }
        )
        result = self.run_prune("--apply")
        self.assert_rmi_succeeds_cleanly(result)
        self.assertNotIn(IMAGE_OLD_RUNTIME, self.rmi_targets())
        self.assertIn(f"protected: {IMAGE_OLD_RUNTIME}", result.stdout)

    def test_v2_terminal_only_refs_not_protected(self) -> None:
        self.write_state(
            {
                "schema_version": "sandbox_task_store_v2",
                "tasks": {
                    "t-ok": {"status": "SUCCEEDED", "runtime_image_ref": IMAGE_OLD_RUNTIME},
                    "t-bad": {"status": "FAILED", "runtime_image_ref": IMAGE_OLD_RUNTIME},
                    "t-off": {"status": "CANCELED", "runtime_image_ref": IMAGE_OLD_RUNTIME},
                },
            }
        )
        result = self.run_prune("--apply")
        self.assert_rmi_succeeds_cleanly(result)
        self.assertIn(IMAGE_OLD_RUNTIME, self.rmi_targets())
        self.assertNotIn(f"protected: {IMAGE_OLD_RUNTIME}", result.stdout)

    def test_v2_terminal_only_image_from_base_fixture_is_prunable(self) -> None:
        # Base fixture: IMAGE_TASK_TERMINAL_ONLY is referenced only by FAILED
        # and CANCELED tasks -> it must be a deletion candidate.
        result = self.run_prune("--apply")
        self.assert_rmi_succeeds_cleanly(result)
        self.assertIn(IMAGE_TASK_TERMINAL_ONLY, self.rmi_targets())

    def test_v1_records_tolerated_and_gain_no_extra_protection(self) -> None:
        # sandbox_task_store_v1 records carry no runtime_image_ref. The
        # script must not crash on them (including malformed records) and
        # must NOT fall back to the legacy camelCase key: the QUEUED record
        # below carries a legacy runtimeImageDigest, which must gain nothing.
        self.write_state(
            {
                "schema_version": "sandbox_task_store_v1",
                "tasks": {
                    "legacy-no-ref": {"status": "RUNNING"},
                    "legacy-camelcase": {
                        "status": "QUEUED",
                        "runtimeImageDigest": IMAGE_OLD_RUNTIME,
                    },
                    "legacy-malformed": "not-even-a-dict",
                },
            }
        )
        result = self.run_prune("--apply")
        self.assert_rmi_succeeds_cleanly(result)
        self.assertIn(IMAGE_OLD_RUNTIME, self.rmi_targets())


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

    def test_shared_validator_script_is_valid_bash(self) -> None:
        proc = subprocess.run(
            ["bash", "-n", str(SHARED_SHELL_VALIDATOR)],
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(proc.returncode, 0, proc.stderr)

    def test_shell_accept_vectors_match_python_semantics(self) -> None:
        for ref in ACCEPT_REFS:
            self.assertTrue(
                self.shell_verdict("af_is_digest_reference", ref),
                f"accept vector rejected by shell validator: {ref!r}",
            )

    def test_shell_reject_vectors_match_python_semantics(self) -> None:
        for value, reason in REJECT_REFS:
            self.assertFalse(
                self.shell_verdict("af_is_digest_reference", value),
                f"reject vector accepted by shell validator ({reason}): {value!r}",
            )

    def test_shell_sha256_accept_vectors(self) -> None:
        for value in SHA256_ACCEPT:
            self.assertTrue(
                self.shell_verdict("af_is_sha256_digest", value),
                f"sha256 accept vector rejected: {value!r}",
            )

    def test_shell_sha256_reject_vectors(self) -> None:
        for value, reason in SHA256_REJECT:
            self.assertFalse(
                self.shell_verdict("af_is_sha256_digest", value),
                f"sha256 reject vector accepted ({reason}): {value!r}",
            )

    def test_shell_dev_reference_accept_vectors(self) -> None:
        # R2-4: the ONLY shape the explicit dev-allow switch admits is a
        # syntactically VALID bare tag/reference (shared vector set).
        for ref in VALID_DEV_REFERENCES:
            self.assertTrue(
                self.shell_verdict("af_is_valid_dev_reference", ref),
                f"valid dev reference rejected by shell validator: {ref!r}",
            )

    def test_shell_dev_reference_malformed_reject_vectors(self) -> None:
        # R2-4: malformed classes are rejected by the dev-reference grammar
        # (the dev switch is NOT a blanket bypass).
        for value, reason in MALFORMED_UNDER_DEV_REFS:
            if "\x00" in value:
                continue  # NUL is unrepresentable in argv; python surfaces only
            self.assertFalse(
                self.shell_verdict("af_is_valid_dev_reference", value),
                f"malformed dev reference accepted ({reason}): {value!r}",
            )

    def test_shell_dev_reference_rejects_digest_shaped_values(self) -> None:
        # Even the ACCEPTED digest references are not valid dev references:
        # anything '@'-bearing must satisfy the anchored digest grammar and
        # never rides the dev-allow switch.
        for ref in ACCEPT_REFS:
            self.assertFalse(
                self.shell_verdict("af_is_valid_dev_reference", ref),
                f"digest-shaped value admitted by the dev grammar: {ref!r}",
            )


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

    def test_deploy_rejects_shared_reject_vectors(self) -> None:
        for value, reason in REJECT_REFS:
            env = self.deploy_env(AF_SANDBOX_IMAGE=value)
            result = self.run_deploy(env, "--deploy-only", "python-sandbox-service")
            self.assertEqual(
                result.returncode,
                1,
                f"reject vector reached deployment ({reason}): {value!r}\n"
                f"stdout={result.stdout}\nstderr={result.stderr}",
            )
            self.assertIn("AF_SANDBOX_IMAGE", result.stderr)

    def test_deploy_accepts_valid_digest_reference_full_run(self) -> None:
        env = self.deploy_env(AF_SANDBOX_IMAGE=ACCEPT_REFS[0])
        result = self.run_deploy(env, "--deploy-only", "python-sandbox-service")
        self.assertEqual(
            result.returncode,
            0,
            f"valid digest ref rejected or deploy failed\n"
            f"stdout={result.stdout}\nstderr={result.stderr}",
        )
        self.assertIn("Deployment completed", result.stdout)

    def test_deploy_rejects_bare_tag_without_dev_switch(self) -> None:
        env = self.deploy_env(AF_SANDBOX_IMAGE="alphafrog-sandbox-runtime:latest")
        result = self.run_deploy(env, "--deploy-only", "python-sandbox-service")
        self.assertEqual(result.returncode, 1)
        self.assertIn("AF_SANDBOX_IMAGE", result.stderr)

    def test_deploy_accepts_bare_tag_only_with_explicit_dev_switch(self) -> None:
        env = self.deploy_env(
            AF_SANDBOX_IMAGE="alphafrog-sandbox-runtime:latest",
            AF_SANDBOX_IMAGE_ALLOW_DEV_TAG="true",
        )
        result = self.run_deploy(env, "--deploy-only", "python-sandbox-service")
        self.assertEqual(
            result.returncode,
            0,
            f"stdout={result.stdout}\nstderr={result.stderr}",
        )

    def test_deploy_rejects_unset_image_without_dev_switch(self) -> None:
        env = self.deploy_env()
        result = self.run_deploy(env, "--deploy-only", "python-sandbox-service")
        self.assertEqual(result.returncode, 1)
        self.assertIn("AF_SANDBOX_IMAGE", result.stderr)

    def test_deploy_rejects_empty_image_even_with_dev_switch(self) -> None:
        # R2-4: empty is ALWAYS rejected -- the dev switch is not a bypass.
        env = self.deploy_env(AF_SANDBOX_IMAGE="", AF_SANDBOX_IMAGE_ALLOW_DEV_TAG="true")
        result = self.run_deploy(env, "--deploy-only", "python-sandbox-service")
        self.assertEqual(result.returncode, 1)
        self.assertIn("AF_SANDBOX_IMAGE", result.stderr)

    def test_deploy_accepts_valid_dev_reference_full_run(self) -> None:
        for ref in VALID_DEV_REFERENCES:
            env = self.deploy_env(
                AF_SANDBOX_IMAGE=ref, AF_SANDBOX_IMAGE_ALLOW_DEV_TAG="true"
            )
            result = self.run_deploy(env, "--deploy-only", "python-sandbox-service")
            self.assertEqual(
                result.returncode,
                0,
                f"valid dev reference rejected or deploy failed ({ref!r})\n"
                f"stdout={result.stdout}\nstderr={result.stderr}",
            )
            self.assertIn("Deployment completed", result.stdout)

    def test_deploy_rejects_valid_dev_reference_without_dev_switch(self) -> None:
        for ref in VALID_DEV_REFERENCES:
            env = self.deploy_env(AF_SANDBOX_IMAGE=ref)
            result = self.run_deploy(env, "--deploy-only", "python-sandbox-service")
            self.assertEqual(
                result.returncode,
                1,
                f"bare reference deployed without the dev switch: {ref!r}\n"
                f"stdout={result.stdout}\nstderr={result.stderr}",
            )

    def test_deploy_rejects_malformed_refs_under_dev_switch(self) -> None:
        # R2-4: with the dev switch ON, malformed classes (whitespace/control
        # chars, wrong digest lengths, uppercase digests, digest-shaped but
        # invalid, garbage) are STILL rejected -- never deployed.
        for value, reason in MALFORMED_UNDER_DEV_REFS:
            if "\x00" in value:
                continue  # NUL is unrepresentable in argv; python surfaces only
            env = self.deploy_env(
                AF_SANDBOX_IMAGE=value, AF_SANDBOX_IMAGE_ALLOW_DEV_TAG="true"
            )
            result = self.run_deploy(env, "--deploy-only", "python-sandbox-service")
            self.assertEqual(
                result.returncode,
                1,
                f"malformed ref deployed under the dev switch ({reason}): {value!r}\n"
                f"stdout={result.stdout}\nstderr={result.stderr}",
            )
            self.assertIn("AF_SANDBOX_IMAGE", result.stderr)

    def test_deploy_rejects_malformed_refs_without_dev_switch(self) -> None:
        for value, reason in MALFORMED_UNDER_DEV_REFS:
            if "\x00" in value:
                continue  # NUL is unrepresentable in argv; python surfaces only
            env = self.deploy_env(AF_SANDBOX_IMAGE=value)
            result = self.run_deploy(env, "--deploy-only", "python-sandbox-service")
            self.assertEqual(
                result.returncode,
                1,
                f"malformed ref deployed ({reason}): {value!r}\n"
                f"stdout={result.stdout}\nstderr={result.stderr}",
            )

    def test_digest_ref_still_runs_tier2a_when_dev_allow_permission_set(self) -> None:
        # Permission switch alone must NOT skip Tier2a for a digest publish.
        env = self.deploy_env(
            AF_SANDBOX_IMAGE=ACCEPT_REFS[0],
            AF_SANDBOX_IMAGE_ALLOW_DEV_TAG="true",
        )
        result = self.run_deploy(env, "--deploy-only", "python-sandbox-service")
        self.assertEqual(
            result.returncode,
            0,
            f"stdout={result.stdout}\nstderr={result.stderr}",
        )
        combined = result.stdout + result.stderr
        self.assertNotIn("skipping Tier2a gate", combined)
        self.assertIn("D15_TIER2A_PASS", combined)
        self.assertIn("Deployment completed", result.stdout)

    def test_digest_ref_with_dev_allow_fails_on_oci_label_mismatch(self) -> None:
        # Digest + leftover AF_SANDBOX_IMAGE_ALLOW_DEV_TAG=true must still
        # enforce OCI librarySetDigest == library-set.json.
        self.set_image_labels(
            FAKE_BUILD_IMAGE_ID,
            {OCI_LIBRARY_SET_LABEL: "sha256:" + "11" * 32},
        )
        env = self.deploy_env(
            AF_SANDBOX_IMAGE=ACCEPT_REFS[0],
            AF_SANDBOX_IMAGE_ALLOW_DEV_TAG="true",
        )
        result = self.run_deploy(env, "--deploy-only", "python-sandbox-service")
        self.assertEqual(result.returncode, 1, f"stdout={result.stdout}\nstderr={result.stderr}")
        combined = result.stdout + result.stderr
        self.assertIn("Tier2a", combined)
        self.assertIn("OCI label", combined)
        self.assertNotIn("Deployment completed", result.stdout)

    def test_bare_tag_with_dev_allow_skips_tier2a_only(self) -> None:
        env = self.deploy_env(
            AF_SANDBOX_IMAGE="alphafrog-sandbox-runtime:latest",
            AF_SANDBOX_IMAGE_ALLOW_DEV_TAG="true",
        )
        result = self.run_deploy(env, "--deploy-only", "python-sandbox-service")
        self.assertEqual(
            result.returncode,
            0,
            f"stdout={result.stdout}\nstderr={result.stderr}",
        )
        combined = result.stdout + result.stderr
        self.assertIn("skipping Tier2a gate", combined)
        self.assertIn("Deployment completed", result.stdout)


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

    def test_deploy_rejects_non_releasable_build_artifact(self) -> None:
        self.write_mapping(self.mapping(False, ["BASE_IMAGE_DIGEST", "SBOM_DIGEST"]))
        result = self.run_deploy_with_releasable_gate()
        self.assertEqual(
            result.returncode,
            1,
            f"non-releasable build was deployed\n"
            f"stdout={result.stdout}\nstderr={result.stderr}",
        )
        self.assertIn("releasable", result.stderr)
        # Refused BEFORE any compose/deployment activity.
        self.assertNotIn("Deployment completed", result.stdout)

    def test_deploy_rejects_legacy_mapping_without_releasable_key(self) -> None:
        # Fail-closed: a mapping that predates the releasable key is NOT
        # releasable by implication.
        self.write_mapping(self.mapping(None))
        result = self.run_deploy_with_releasable_gate()
        self.assertEqual(result.returncode, 1)
        self.assertIn("releasable", result.stderr)

    def test_deploy_rejects_corrupt_mapping(self) -> None:
        self.write_mapping("{ this is not json")
        result = self.run_deploy_with_releasable_gate()
        self.assertEqual(result.returncode, 1)

    def test_deploy_rejects_mapping_with_no_images(self) -> None:
        self.write_mapping({"schemaVersion": "1", "images": {}})
        result = self.run_deploy_with_releasable_gate()
        self.assertEqual(result.returncode, 1)

    def test_non_releasable_deploy_allowed_only_with_explicit_dev_switch(self) -> None:
        self.write_mapping(self.mapping(False, ["BASE_IMAGE_DIGEST"]))
        result = self.run_deploy_with_releasable_gate(
            AF_SANDBOX_ALLOW_INCOMPLETE_DEV_BUILD="true"
        )
        self.assertEqual(
            result.returncode,
            0,
            f"stdout={result.stdout}\nstderr={result.stderr}",
        )
        combined = result.stdout + result.stderr
        self.assertIn("WARNING", combined)
        self.assertIn("releasable", combined)
        self.assertIn("Deployment completed", result.stdout)

    def test_deploy_accepts_releasable_build_artifact(self) -> None:
        self.write_mapping(self.mapping(True))
        result = self.run_deploy_with_releasable_gate()
        self.assertEqual(
            result.returncode,
            0,
            f"stdout={result.stdout}\nstderr={result.stderr}",
        )
        self.assertIn("Deployment completed", result.stdout)

    # --- round-2 R2-3 target-binding regressions -------------------------
    # The deploy target must be provably identical to EXACTLY ONE mapping
    # entry; every failure class below aborts BEFORE any deployment, and the
    # dev switches never relax target binding (only the releasable verdict).

    def test_deploy_rejects_missing_mapping_file(self) -> None:
        # No build-artifact mapping at all: identity cannot be proven.
        result = self.run_deploy_with_releasable_gate()
        self.assertEqual(
            result.returncode,
            1,
            f"deploy proceeded without the build-artifact mapping\n"
            f"stdout={result.stdout}\nstderr={result.stderr}",
        )
        self.assertIn("image-digest-mapping.json", result.stderr)
        self.assertNotIn("Deployment completed", result.stdout)

    def test_deploy_rejects_unrelated_mapping_entry(self) -> None:
        # Entries exist but NONE binds the chosen target: the entry key is a
        # different immutable image ID and no imageRef matches the chosen ref.
        self.write_mapping(self.bound_mapping(image_id=OTHER_IMAGE_ID))
        result = self.run_deploy_with_releasable_gate()
        self.assertEqual(
            result.returncode,
            1,
            f"unrelated mapping entry was deployed\n"
            f"stdout={result.stdout}\nstderr={result.stderr}",
        )
        self.assertNotIn("Deployment completed", result.stdout)

    def test_deploy_rejects_multiple_entries_none_binding_the_target(self) -> None:
        # Multiple entries, none matching the chosen target -> still no-match.
        mapping = self.bound_mapping(image_id=OTHER_IMAGE_ID)
        mapping["images"]["sha256:" + "a9" * 32] = dict(
            LEGAL_ENTRY_DIGESTS,
            buildRevision="git:" + "11" * 20,
            incompleteInputs=[],
            releasable=True,
        )
        self.write_mapping(mapping)
        result = self.run_deploy_with_releasable_gate()
        self.assertEqual(result.returncode, 1)
        self.assertNotIn("Deployment completed", result.stdout)

    def test_deploy_rejects_multiple_entries_claiming_the_target(self) -> None:
        # Two entries BOTH correspond to the chosen target (one via its key ==
        # inspected ID, one via a recorded imageRef): binding must be UNIQUE.
        mapping = self.bound_mapping()
        mapping["images"][OTHER_IMAGE_ID] = dict(
            LEGAL_ENTRY_DIGESTS,
            buildRevision="git:" + "11" * 20,
            incompleteInputs=[],
            releasable=True,
            imageRef=ACCEPT_REFS[0],
        )
        self.write_mapping(mapping)
        result = self.run_deploy_with_releasable_gate()
        self.assertEqual(
            result.returncode,
            1,
            f"ambiguous target binding was deployed\n"
            f"stdout={result.stdout}\nstderr={result.stderr}",
        )
        self.assertNotIn("Deployment completed", result.stdout)

    def test_deploy_rejects_target_mismatch_recorded_id_differs_from_inspected(self) -> None:
        # The entry corresponds to the chosen ref (via imageRef) but binds a
        # DIFFERENT immutable image ID than docker inspect resolves: the
        # deploy target is NOT the built image -> fail closed.
        # D15-A HARD gate runs first and refuses because inspected_id has no
        # mapping entry (R2-3 identity); diagnostics still carry the inspected
        # immutable ID.
        self.write_mapping(
            self.bound_mapping(image_id=OTHER_IMAGE_ID, imageRef=ACCEPT_REFS[0])
        )
        # Companion artifacts bind the inspected ID; mapping identity still fails.
        self.write_iidfile(FAKE_BUILD_IMAGE_ID)
        self.write_library_set_file(LEGAL_ENTRY_DIGESTS["librarySetDigest"])
        self.set_image_labels(
            FAKE_BUILD_IMAGE_ID,
            {OCI_LIBRARY_SET_LABEL: LEGAL_ENTRY_DIGESTS["librarySetDigest"]},
        )
        result = self.run_deploy_with_releasable_gate()
        self.assertEqual(
            result.returncode,
            1,
            f"target mismatch was deployed\n"
            f"stdout={result.stdout}\nstderr={result.stderr}",
        )
        # Diagnostics carry the inspected immutable ID (§18: public image ID,
        # no secrets) and the fail-closed marker.
        self.assertIn(FAKE_BUILD_IMAGE_ID, result.stderr)
        self.assertIn("R2-3", result.stderr)
        self.assertNotIn("Deployment completed", result.stdout)

    def test_deploy_rejects_unresolvable_target_image(self) -> None:
        # docker inspect cannot resolve the chosen ref -> no immutable ID to
        # bind -> fail closed.
        self.write_deploy_build_artifacts(self.bound_mapping())
        self.env["FAKE_DOCKER_FAIL_INSPECT_REF"] = ACCEPT_REFS[0]
        result = self.run_deploy_with_releasable_gate()
        self.assertEqual(
            result.returncode,
            1,
            f"unresolvable deploy target was deployed\n"
            f"stdout={result.stdout}\nstderr={result.stderr}",
        )
        self.assertIn("docker inspect", result.stderr)
        self.assertNotIn("Deployment completed", result.stdout)

    def test_deploy_rejects_placeholder_or_malformed_entry_digests(self) -> None:
        # Every one of the five entry digests must be a LEGAL non-placeholder
        # sha256 value; otherwise the entry is not releasable (fail closed,
        # no dev switch here).
        bad_values = (
            "REPLACE_WITH_VERIFIED_BASE_IMAGE_DIGEST",  # placeholder token
            "sha256:" + "B0" * 32,                      # uppercase hex
            "sha256:" + "00" * 31,                      # 62 hex chars
        )
        for field in (
            "baseImageDigest",
            "lockDigest",
            "librarySetDigest",
            "sbomDigest",
            "methodSpecIndexDigest",
        ):
            for bad in bad_values:
                # Keep companion library-set.json legal so the failure lands on
                # mapping/HARD integrity of the mapping entry itself.
                self.write_deploy_build_artifacts(
                    self.bound_mapping(**{field: bad}),
                    library_set_digest=LEGAL_ENTRY_DIGESTS["librarySetDigest"],
                )
                result = self.run_deploy_with_releasable_gate()
                self.assertEqual(
                    result.returncode,
                    1,
                    f"entry with bad {field}={bad!r} was deployed\n"
                    f"stdout={result.stdout}\nstderr={result.stderr}",
                )
                combined = result.stderr
                self.assertTrue(
                    ("releasable" in combined)
                    or ("librarySetDigest" in combined)
                    or ("HARD" in combined)
                    or ("mismatch" in combined),
                    f"expected integrity fail-closed marker missing for {field}={bad!r}\n"
                    f"stderr={result.stderr}",
                )
                self.assertNotIn("Deployment completed", result.stdout)


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

    def test_build_fails_closed_by_default_on_placeholder_inputs(self) -> None:
        result = self.run_build(self.build_env())
        self.assertEqual(
            result.returncode,
            1,
            f"placeholder inputs must fail the release gate\n"
            f"stdout={result.stdout}\nstderr={result.stderr}",
        )
        # Diagnostics name the failed inputs (no secrets involved).
        # methodSpecIndexDigest is NOT among them: it is COMPUTED from the
        # canonical index.json bytes once the five-file gate passes, so it
        # can never be missing or a placeholder (Spec §12).
        for name in ("BASE_IMAGE_DIGEST", "SBOM_DIGEST"):
            self.assertIn(name, result.stderr)
        self.assertNotIn("METHOD_SPEC_INDEX_DIGEST", result.stderr)
        self.assertIn("AF_SANDBOX_ALLOW_INCOMPLETE_DEV_BUILD", result.stderr)
        # Fail-closed BEFORE any docker build invocation.
        self.assertEqual(self.calls_for("build"), [])
        self.assertFalse(MAPPING_FILE.exists())

    def test_malformed_base_digest_rejected_even_with_dev_switch(self) -> None:
        for value, reason in SHA256_REJECT:
            if not value:
                continue  # empty == missing -> placeholder path, covered above
            env = self.build_env(
                BASE_IMAGE_DIGEST=value,
                METHOD_SPEC_INDEX_DIGEST=canonical_index_digest(),
                AF_SANDBOX_ALLOW_INCOMPLETE_DEV_BUILD="true",
            )
            self.write_fake_syft(0)
            result = self.run_build(env)
            self.assertEqual(
                result.returncode,
                1,
                f"malformed BASE_IMAGE_DIGEST accepted ({reason}): {value!r}\n"
                f"stdout={result.stdout}\nstderr={result.stderr}",
            )
            self.assertIn("BASE_IMAGE_DIGEST", result.stderr)
            self.assertEqual(self.calls_for("build"), [])

    def test_dev_switch_allows_structural_build_marked_not_releasable(self) -> None:
        env = self.build_env(AF_SANDBOX_ALLOW_INCOMPLETE_DEV_BUILD="true")
        result = self.run_build(env)
        self.assertEqual(
            result.returncode,
            0,
            f"stdout={result.stdout}\nstderr={result.stderr}",
        )
        self.assertTrue(self.calls_for("build"), "docker build was never invoked")
        entry = self.mapping_entry()
        self.assertFalse(entry["releasable"])
        self.assertEqual(
            entry["incompleteInputs"],
            ["BASE_IMAGE_DIGEST", "SBOM_DIGEST"],
        )
        # methodSpecIndexDigest is computed from the canonical index.json
        # bytes and carried into the phase-2 build args.
        self.assertEqual(
            entry["methodSpecIndexDigest"],
            canonical_index_digest(),
        )
        self.assertIn("NOT RELEASABLE", result.stdout)

    def test_verified_inputs_with_syft_yield_releasable_mapping(self) -> None:
        self.write_fake_syft(0)
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
        entry = self.mapping_entry()
        self.assertTrue(entry["releasable"])
        self.assertEqual(entry["incompleteInputs"], [])
        self.assertIn("releasable=true", result.stdout)

    def test_syft_failure_fails_closed_without_dev_switch(self) -> None:
        self.write_fake_syft(1)
        env = self.build_env(
            BASE_IMAGE_DIGEST=self.BASE_DIGEST,
            METHOD_SPEC_INDEX_DIGEST=canonical_index_digest(),
        )
        result = self.run_build(env)
        self.assertEqual(
            result.returncode,
            1,
            f"syft failure must fail the release gate\n"
            f"stdout={result.stdout}\nstderr={result.stderr}",
        )
        self.assertIn("SBOM_DIGEST", result.stderr)
        # The non-releasable mapping is still written for audit.
        entry = self.mapping_entry()
        self.assertFalse(entry["releasable"])
        self.assertEqual(entry["incompleteInputs"], ["SBOM_DIGEST"])

    def test_syft_failure_with_dev_switch_marks_not_releasable(self) -> None:
        self.write_fake_syft(1)
        env = self.build_env(
            BASE_IMAGE_DIGEST=self.BASE_DIGEST,
            METHOD_SPEC_INDEX_DIGEST=canonical_index_digest(),
            AF_SANDBOX_ALLOW_INCOMPLETE_DEV_BUILD="1",
        )
        result = self.run_build(env)
        self.assertEqual(
            result.returncode,
            0,
            f"stdout={result.stdout}\nstderr={result.stderr}",
        )
        entry = self.mapping_entry()
        self.assertFalse(entry["releasable"])
        self.assertEqual(entry["incompleteInputs"], ["SBOM_DIGEST"])

    # --- round-2 R2-1: canonical inputs + bindings generator gates --------
    # The A-canonical generated JSON is a HARD build material: missing inputs
    # fail closed BEFORE any docker build, and the dev switch does NOT admit
    # them (it is not a release-time placeholder).

    def test_build_fails_closed_when_canonical_dir_missing_even_with_dev_switch(self) -> None:
        env = self.build_env(
            AF_SANDBOX_ALLOW_INCOMPLETE_DEV_BUILD="true",
            METHOD_SPEC_CANONICAL_DIR=str(Path(self._tmp.name) / "no-such-canonical-dir"),
        )
        result = self.run_build(env)
        self.assertEqual(
            result.returncode,
            1,
            f"build proceeded without the canonical method-spec inputs\n"
            f"stdout={result.stdout}\nstderr={result.stderr}",
        )
        self.assertIn("METHOD_SPEC_CANONICAL_DIR", result.stderr)
        self.assertEqual(self.calls_for("build"), [])
        self.assertFalse(MAPPING_FILE.exists())

    def test_build_fails_closed_when_a_canonical_file_is_missing(self) -> None:
        canonical = Path(self._tmp.name) / "canonical-incomplete"
        canonical.mkdir()
        for name in (
            "index.json",
            "resolver-catalog.json",
            "cagr.json",
            "annualized_volatility.json",
        ):
            shutil.copy(CANONICAL_FIXTURES_DIR / name, canonical / name)
        # sharpe_ratio.json deliberately missing.
        env = self.build_env(
            AF_SANDBOX_ALLOW_INCOMPLETE_DEV_BUILD="true",
            METHOD_SPEC_CANONICAL_DIR=str(canonical),
        )
        result = self.run_build(env)
        self.assertEqual(
            result.returncode,
            1,
            f"build proceeded with an incomplete canonical input set\n"
            f"stdout={result.stdout}\nstderr={result.stderr}",
        )
        self.assertIn("sharpe_ratio.json", result.stderr)
        self.assertEqual(self.calls_for("build"), [])

    def test_generator_failure_aborts_build_before_docker_build(self) -> None:
        canonical = Path(self._tmp.name) / "canonical-broken"
        canonical.mkdir()
        for name in (
            "index.json",
            "resolver-catalog.json",
            "cagr.json",
            "annualized_volatility.json",
            "sharpe_ratio.json",
        ):
            shutil.copy(CANONICAL_FIXTURES_DIR / name, canonical / name)
        (canonical / "cagr.json").write_text("{ this is not json", encoding="utf-8")
        env = self.build_env(
            AF_SANDBOX_ALLOW_INCOMPLETE_DEV_BUILD="true",
            METHOD_SPEC_CANONICAL_DIR=str(canonical),
        )
        result = self.run_build(env)
        self.assertEqual(
            result.returncode,
            1,
            f"generator failure did not abort the build\n"
            f"stdout={result.stdout}\nstderr={result.stderr}",
        )
        self.assertIn("generator FAILED", result.stderr)
        self.assertEqual(self.calls_for("build"), [])
        self.assertFalse(MAPPING_FILE.exists())

    # --- round-2 R2-1: smoke gate (docker run, both interpreters) ---------

    def test_smoke_gate_failure_aborts_build(self) -> None:
        env = self.build_env(
            AF_SANDBOX_ALLOW_INCOMPLETE_DEV_BUILD="true",
            FAKE_DOCKER_SMOKE_EXIT="1",
        )
        result = self.run_build(env)
        self.assertEqual(
            result.returncode,
            1,
            f"smoke gate failure did not abort the build\n"
            f"stdout={result.stdout}\nstderr={result.stderr}",
        )
        self.assertIn("smoke gate FAILED", result.stderr)
        # Aborted after PHASE 1, before the inventory gate and the final bake.
        self.assertEqual(len(self.calls_for("build")), 1)
        self.assertFalse(MAPPING_FILE.exists())

    # --- round-2 R2-2: actual-inventory gate (fail-closed compare) --------

    def mutated_inventory(self, **api_overrides) -> dict:
        drift = json.loads(json.dumps(DEFAULT_INVENTORY))
        drift.update(api_overrides)
        return drift

    def test_inventory_version_mismatch_fails_closed(self) -> None:
        drift = self.mutated_inventory()
        for package in drift["packages"]:
            if package["name"] == "numpy":
                package["version"] = "2.4.0"  # lock pins 2.4.1
        self.inventory_file.write_text(json.dumps(drift), encoding="utf-8")
        env = self.build_env(AF_SANDBOX_ALLOW_INCOMPLETE_DEV_BUILD="true")
        result = self.run_build(env)
        self.assertEqual(
            result.returncode,
            1,
            f"inventory version drift was not fail-closed\n"
            f"stdout={result.stdout}\nstderr={result.stderr}",
        )
        self.assertIn("inventory", result.stderr.lower())
        self.assertIn("numpy", result.stderr)
        self.assertEqual(len(self.calls_for("build")), 1)  # phase 1 only
        self.assertFalse(MAPPING_FILE.exists())

    def test_inventory_api_version_mismatch_fails_closed(self) -> None:
        drift = self.mutated_inventory(apiVersion="2.0")  # source says "1.0"
        self.inventory_file.write_text(json.dumps(drift), encoding="utf-8")
        env = self.build_env(AF_SANDBOX_ALLOW_INCOMPLETE_DEV_BUILD="true")
        result = self.run_build(env)
        self.assertEqual(
            result.returncode,
            1,
            f"apiVersion drift was not fail-closed\n"
            f"stdout={result.stdout}\nstderr={result.stderr}",
        )
        self.assertIn("apiVersion", result.stderr)
        self.assertEqual(len(self.calls_for("build")), 1)
        self.assertFalse(MAPPING_FILE.exists())

    def test_verified_inventory_is_baked_into_library_set_and_mapping(self) -> None:
        # R2-2: the baked library-set.json, the OCI librarySetDigest label
        # input and the external mapping all carry the SAME verified ACTUAL
        # inventory (never lockfile inference).
        env = self.build_env(AF_SANDBOX_ALLOW_INCOMPLETE_DEV_BUILD="true")
        result = self.run_build(env)
        self.assertEqual(
            result.returncode,
            0,
            f"stdout={result.stdout}\nstderr={result.stderr}",
        )
        expected_packages = []
        for package in sorted(DEFAULT_INVENTORY["packages"], key=lambda p: p["name"]):
            entry = {"name": package["name"], "version": package["version"]}
            if package["name"] == "alphafrog-finance":
                entry["apiVersion"] = DEFAULT_INVENTORY["apiVersion"]
            expected_packages.append(entry)

        library_set = json.loads(
            (RUNTIME_BUILD_DIR / "library-set.json").read_text(encoding="utf-8")
        )
        self.assertEqual(library_set["packages"], expected_packages)

        verified = json.loads(
            (RUNTIME_BUILD_DIR / "verified-packages.json").read_text(encoding="utf-8")
        )
        self.assertEqual(verified, expected_packages)

        entry = self.mapping_entry()
        self.assertEqual(
            entry["librarySetDigest"],
            library_set["librarySetDigest"],
            "mapping must carry the SAME verified librarySetDigest",
        )
        # R2-3 binding aid: the entry's imageRef alias is the SAME immutable
        # iidfile ID the entry binds. The mutable :latest tag is NEVER
        # recorded as evidence in the mapping (Spec §12 immutable
        # same-origin: the tag can drift to another image at any time).
        self.assertEqual(entry["imageRef"], FAKE_BUILD_IMAGE_ID)

    def test_build_wiring_two_phase_order_smoke_inventory_then_bake(self) -> None:
        # R2-1/R2-2 wiring order: phase-1 build (runtime-install) -> smoke
        # gate under BOTH interpreters -> inventory query -> phase-2 bake FROM
        # the phase-1 immutable ID with the verified librarySetDigest.
        env = self.build_env(AF_SANDBOX_ALLOW_INCOMPLETE_DEV_BUILD="true")
        result = self.run_build(env)
        self.assertEqual(
            result.returncode,
            0,
            f"stdout={result.stdout}\nstderr={result.stderr}",
        )
        build_calls = self.calls_for("build")
        self.assertEqual(len(build_calls), 2, f"expected two build phases: {build_calls}")
        self.assertIn("--target", build_calls[0])
        self.assertIn("runtime-install", build_calls[0])
        self.assertTrue(
            any(token.startswith("RUNTIME_BASE_IMAGE_REF=") for token in build_calls[0]),
            f"phase 1 missing RUNTIME_BASE_IMAGE_REF build arg: {build_calls[0]}",
        )
        phase1_base_ref = next(
            token.split("=", 1)[1]
            for token in build_calls[0]
            if token.startswith("RUNTIME_BASE_IMAGE_REF=")
        )
        self.assertIn(
            f"AF_RUNTIME_INSTALL_IMAGE={phase1_base_ref}",
            build_calls[0],
            "BuildKit parses every FROM before honoring --target, so phase 1 "
            "must override the phase-2 placeholder with a valid reference",
        )
        self.assertIn(
            f"RUNTIME_BASE_IMAGE_REF={phase1_base_ref}",
            build_calls[1],
            "BuildKit parses the phase-1 FROM during phase 2, so the second "
            "build must also carry the real base reference",
        )
        self.assertTrue(
            any(
                token == f"AF_RUNTIME_INSTALL_IMAGE={FAKE_BUILD_IMAGE_ID}"
                for token in build_calls[1]
            ),
            f"phase 2 must build FROM the phase-1 immutable image ID: {build_calls[1]}",
        )
        self.assertTrue(
            any(token.startswith("AF_LIBRARY_SET_DIGEST=sha256:") for token in build_calls[1]),
            f"phase 2 missing the verified librarySetDigest label arg: {build_calls[1]}",
        )
        # The phase-2 index digest build arg is COMPUTED from the canonical
        # index.json bytes, and the SAME bytes are staged into
        # .runtime-build/index.json for the in-image re-hash gate
        # (Dockerfile COPY .runtime-build/index.json).
        expected_index_digest = canonical_index_digest()
        self.assertIn(
            f"AF_METHOD_SPEC_INDEX_DIGEST={expected_index_digest}",
            build_calls[1],
            f"phase 2 must carry the computed index digest: {build_calls[1]}",
        )
        self.assertEqual(
            (RUNTIME_BUILD_DIR / "index.json").read_bytes(),
            (CANONICAL_FIXTURES_DIR / "index.json").read_bytes(),
            "the staged index.json must be the SAME bytes the digest was computed from",
        )

        run_calls = self.calls_for("run")
        smoke_calls = [c for c in run_calls if any("smoke_runtime_image.py" in t for t in c)]
        inventory_calls = [
            c for c in run_calls if any("runtime_image_inventory.py" in t for t in c)
        ]
        self.assertEqual(
            len(smoke_calls),
            2,
            f"smoke gate must run under both interpreters: {run_calls}",
        )
        self.assertEqual(
            len([c for c in smoke_calls if any(".sandbox-venv" in t for t in c)]),
            1,
            f"one smoke run must target the compat venv: {smoke_calls}",
        )
        self.assertEqual(len(inventory_calls), 1, f"expected one inventory query: {run_calls}")

        # Global ordering via the call log.
        calls = self.docker_calls()

        def first_index(predicate) -> int:
            for index, call in enumerate(calls):
                if predicate(call):
                    return index
            self.fail(f"call not found in docker call log: {calls}")
            return -1

        phase1 = first_index(lambda c: c[:1] == ["build"] and "runtime-install" in c)
        smoke_system = first_index(
            lambda c: c[:1] == ["run"]
            and any("smoke_runtime_image.py" in t for t in c)
            and not any(".sandbox-venv" in t for t in c)
        )
        smoke_venv = first_index(
            lambda c: c[:1] == ["run"] and any(".sandbox-venv" in t for t in c)
        )
        inventory = first_index(
            lambda c: c[:1] == ["run"] and any("runtime_image_inventory.py" in t for t in c)
        )
        phase2 = first_index(
            lambda c: c[:1] == ["build"]
            and f"AF_RUNTIME_INSTALL_IMAGE={FAKE_BUILD_IMAGE_ID}" in c
            and "runtime-install" not in c
        )
        self.assertLess(phase1, smoke_system, "smoke must run after phase 1")
        self.assertLess(phase1, smoke_venv, "venv smoke must run after phase 1")
        self.assertLess(smoke_system, inventory, "inventory gate must follow the smoke gate")
        self.assertLess(smoke_venv, inventory, "inventory gate must follow the smoke gate")
        self.assertLess(inventory, phase2, "the bake must follow the inventory gate")


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

    def test_syft_argv_is_exactly_the_iidfile_id_even_if_latest_drifts(self) -> None:
        self.run_verified_build_with_drifting_latest()
        calls = self.syft_calls()
        self.assertEqual(len(calls), 1, f"expected exactly one syft invocation: {calls}")
        # The scan target is EXACTLY the phase-2 iidfile immutable ID -- not
        # the mutable tag, and not whatever the tag currently resolves to.
        self.assertEqual(
            calls[0],
            ["docker:" + FAKE_BUILD_IMAGE_ID, "-o", "json"],
            f"syft must scan the iidfile immutable ID exclusively: {calls}",
        )

    def test_sbom_and_mapping_evidence_bind_only_the_iidfile_id(self) -> None:
        self.run_verified_build_with_drifting_latest()
        # The convenience tag is RETAINED on the phase-2 build call ...
        build_calls = self.calls_for("build")
        self.assertEqual(len(build_calls), 2, f"expected two build phases: {build_calls}")
        self.assertIn(self.LATEST_TAG, build_calls[1])
        # ... yet it NEVER enters the SBOM or the mapping evidence.
        sbom_path = RUNTIME_BUILD_DIR / "sbom.json"
        sbom_text = sbom_path.read_text(encoding="utf-8")
        self.assertIn(FAKE_BUILD_IMAGE_ID, sbom_text)
        self.assertNotIn(DRIFTED_IMAGE_ID, sbom_text)
        self.assertNotIn(self.LATEST_TAG, sbom_text)
        mapping_text = MAPPING_FILE.read_text(encoding="utf-8")
        self.assertNotIn(DRIFTED_IMAGE_ID, mapping_text)
        self.assertNotIn(self.LATEST_TAG, mapping_text)
        mapping = json.loads(mapping_text)
        self.assertEqual(list(mapping["images"]), [FAKE_BUILD_IMAGE_ID])
        entry = mapping["images"][FAKE_BUILD_IMAGE_ID]
        self.assertEqual(entry["imageRef"], FAKE_BUILD_IMAGE_ID)
        # sbomDigest is the sha256 of EXACTLY these SBOM bytes, and those
        # bytes bind the iidfile ID (never the drifted/tag identity).
        expected_sbom_digest = (
            "sha256:" + hashlib.sha256(sbom_path.read_bytes()).hexdigest()
        )
        self.assertEqual(entry["sbomDigest"], expected_sbom_digest)
        self.assertTrue(entry["releasable"])
        self.assertEqual(entry["incompleteInputs"], [])

    def test_deploy_rejects_drifted_latest_against_this_builds_mapping(self) -> None:
        # The build's mapping binds ONLY the iidfile ID. After the tag
        # drifts, deploying :latest targets the drifted image, which the
        # mapping does NOT bind -> fail closed (no evidence for that image).
        self.run_verified_build_with_drifting_latest()
        result = self.run_deploy_gate(
            self.LATEST_TAG, AF_SANDBOX_IMAGE_ALLOW_DEV_TAG="true"
        )
        self.assertEqual(
            result.returncode,
            1,
            f"drifted :latest was deployed against this build's mapping\n"
            f"stdout={result.stdout}\nstderr={result.stderr}",
        )
        self.assertNotIn("Deployment completed", result.stdout)

    def test_deploy_rejects_tag_derived_evidence_for_another_image(self) -> None:
        # Evidence derived ONLY from the mutable tag (an entry keyed by the
        # drifted ID and recorded under the tag) cannot authorize deploying
        # THIS build's image.
        self.add_alias(self.LATEST_TAG, DRIFTED_IMAGE_ID)
        self.add_alias(ACCEPT_REFS[0], FAKE_BUILD_IMAGE_ID)
        self.write_mapping_file(
            self.bound_mapping(image_id=DRIFTED_IMAGE_ID, imageRef=self.LATEST_TAG)
        )
        result = self.run_deploy_gate(ACCEPT_REFS[0])
        self.assertEqual(
            result.returncode,
            1,
            f"tag-derived evidence authorized an unrelated image\n"
            f"stdout={result.stdout}\nstderr={result.stderr}",
        )
        self.assertNotIn("Deployment completed", result.stdout)

    def test_deploy_still_accepts_this_build_via_digest_reference(self) -> None:
        # Positive control for the drift scenario: a digest reference that
        # resolves to the SAME immutable iidfile ID remains deployable --
        # the binding is the ID, never the tag.
        self.run_verified_build_with_drifting_latest()
        self.add_alias(ACCEPT_REFS[0], FAKE_BUILD_IMAGE_ID)
        result = self.run_deploy_gate(ACCEPT_REFS[0])
        self.assertEqual(
            result.returncode,
            0,
            f"stdout={result.stdout}\nstderr={result.stderr}",
        )
        self.assertIn("Deployment completed", result.stdout)

    def test_malformed_phase2_iidfile_fails_closed_before_syft_and_mapping(self) -> None:
        self.write_fake_syft(0)
        env = self.build_env(
            BASE_IMAGE_DIGEST=self.BASE_DIGEST,
            METHOD_SPEC_INDEX_DIGEST=canonical_index_digest(),
            FAKE_DOCKER_BUILD_IMAGE_ID="sha256:not-a-64-hex-id",
        )
        result = self.run_build(env)
        self.assertEqual(
            result.returncode,
            1,
            f"malformed iidfile ID entered the evidence chain\n"
            f"stdout={result.stdout}\nstderr={result.stderr}",
        )
        self.assertIn("phase-2 --iidfile image ID", result.stderr)
        self.assertEqual(self.syft_calls(), [])
        self.assertFalse(MAPPING_FILE.exists())


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

    def test_default_mode_accepts_matching_local_image_id(self) -> None:
        # IMAGE_CURRENT is served by the fake `docker images` file, so a bare
        # ID inspect resolves to itself. No verify-mode env -> default
        # local-image-id. No mapping/iidfile artifacts exist in this fixture,
        # so acceptance also proves the strict chain is not required.
        result = self.run_deploy_local(AF_SANDBOX_IMAGE=IMAGE_CURRENT)
        self.assertEqual(result.returncode, 0, f"stdout={result.stdout}\nstderr={result.stderr}")
        self.assertIn("已校验本机 Image ID", result.stdout)
        self.assertIn(IMAGE_CURRENT, result.stdout)

    def test_explicit_local_mode_matches_same(self) -> None:
        result = self.run_deploy_local(
            AF_SANDBOX_IMAGE=IMAGE_CURRENT,
            AF_SANDBOX_IMAGE_VERIFY_MODE="local-image-id",
        )
        self.assertEqual(result.returncode, 0, f"stdout={result.stdout}\nstderr={result.stderr}")

    def test_bare_tag_rejected_even_with_dev_switch(self) -> None:
        # local-image-id IS the single-machine contract; there is no
        # AF_SANDBOX_IMAGE_ALLOW_DEV_TAG escape in this mode.
        self.add_alias("alphafrog-sandbox-runtime:latest", IMAGE_CURRENT)
        result = self.run_deploy_local(
            AF_SANDBOX_IMAGE="alphafrog-sandbox-runtime:latest",
            AF_SANDBOX_IMAGE_ALLOW_DEV_TAG="true",
        )
        self.assertEqual(result.returncode, 1, f"stdout={result.stdout}\nstderr={result.stderr}")
        self.assertIn("local-image-id 模式要求", result.stderr)

    def test_repo_digest_rejected_in_local_mode(self) -> None:
        digest = "registry.local/alphafrog/runtime@sha256:" + "a1" * 32
        result = self.run_deploy_local(AF_SANDBOX_IMAGE=digest)
        self.assertEqual(result.returncode, 1, f"stdout={result.stdout}\nstderr={result.stderr}")
        self.assertIn("local-image-id 模式要求", result.stderr)

    def test_missing_image_fails_closed(self) -> None:
        # Not in the fake images file and no alias -> inspect fails -> refuse.
        missing_id = "sha256:" + "ff" * 32
        result = self.run_deploy_local(AF_SANDBOX_IMAGE=missing_id)
        self.assertEqual(result.returncode, 1, f"stdout={result.stdout}\nstderr={result.stderr}")
        self.assertIn("docker inspect", result.stderr)

    def test_mismatched_resolution_fails_closed(self) -> None:
        # Configured ID resolves (via alias) to a DIFFERENT image ID.
        self.add_alias(IMAGE_CURRENT, IMAGE_OLD_RUNTIME)
        result = self.run_deploy_local(AF_SANDBOX_IMAGE=IMAGE_CURRENT)
        self.assertEqual(result.returncode, 1, f"stdout={result.stdout}\nstderr={result.stderr}")
        self.assertIn("解析到不同镜像", result.stderr)

    def test_tag_check_matching_passes(self) -> None:
        self.add_alias("alphafrog-sandbox-runtime:latest", IMAGE_CURRENT)
        result = self.run_deploy_local(
            AF_SANDBOX_IMAGE=IMAGE_CURRENT,
            AF_SANDBOX_IMAGE_TAG_CHECK="alphafrog-sandbox-runtime:latest",
        )
        self.assertEqual(result.returncode, 0, f"stdout={result.stdout}\nstderr={result.stderr}")
        self.assertIn("标签复核通过", result.stdout)

    def test_tag_check_drift_fails_closed(self) -> None:
        self.add_alias("alphafrog-sandbox-runtime:latest", IMAGE_OLD_RUNTIME)
        result = self.run_deploy_local(
            AF_SANDBOX_IMAGE=IMAGE_CURRENT,
            AF_SANDBOX_IMAGE_TAG_CHECK="alphafrog-sandbox-runtime:latest",
        )
        self.assertEqual(result.returncode, 1, f"stdout={result.stdout}\nstderr={result.stderr}")
        self.assertIn("不一致", result.stderr)

    def test_unknown_mode_rejected(self) -> None:
        result = self.run_deploy_local(
            AF_SANDBOX_IMAGE=IMAGE_CURRENT,
            AF_SANDBOX_IMAGE_VERIFY_MODE="bogus",
        )
        self.assertEqual(result.returncode, 1, f"stdout={result.stdout}\nstderr={result.stderr}")
        self.assertIn("AF_SANDBOX_IMAGE_VERIFY_MODE", result.stderr)

    def test_strict_release_does_not_accept_local_id(self) -> None:
        # Cross-mode independence: a bare local Image ID is NOT a digest
        # reference; strict-release must keep rejecting it.
        result = self.run_deploy_local(
            AF_SANDBOX_IMAGE=IMAGE_CURRENT,
            AF_SANDBOX_IMAGE_VERIFY_MODE="strict-release",
        )
        self.assertEqual(result.returncode, 1, f"stdout={result.stdout}\nstderr={result.stderr}")


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

    def test_local_mode_skips_release_inputs_and_prints_image_id(self) -> None:
        # No BASE_IMAGE_DIGEST, no syft -> strict-release would fail closed;
        # local-image-id (default) must succeed and print the frozen ID.
        env = self.build_env_local()
        result = self.run_build_local(env)
        self.assertEqual(result.returncode, 0, f"stdout={result.stdout}\nstderr={result.stderr}")
        self.assertIn("verified local Image ID: " + FAKE_BUILD_IMAGE_ID, result.stdout)
        self.assertIn("AF_SANDBOX_IMAGE=" + FAKE_BUILD_IMAGE_ID, result.stdout)
        self.assertIn("local-image-id mode: building FROM local base tag", result.stdout)
        self.assertNotIn("placeholder base digest + explicit dev switch", result.stderr)
        # The strict-release evidence mapping is not written in local mode.
        self.assertFalse(MAPPING_FILE.exists())

    def test_unknown_mode_fails_closed(self) -> None:
        env = self.build_env_local(AF_SANDBOX_IMAGE_VERIFY_MODE="bogus")
        result = self.run_build_local(env)
        self.assertEqual(result.returncode, 1, f"stdout={result.stdout}\nstderr={result.stderr}")
        self.assertIn("AF_SANDBOX_IMAGE_VERIFY_MODE", result.stderr)


if __name__ == "__main__":
    unittest.main()
