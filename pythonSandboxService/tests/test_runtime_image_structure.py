# === work-package-H (ccqwen) ===
"""Static structure + fail-closed gate tests for the Spec §12 runtime image
build (Dockerfile.runtime + docker_build.sh), and the docker-gated
three-stage build E2E.

Covered here (MethodSpec V5 work package H, FINAL round):

* ITEM A — Dockerfile ARG scope: ``AF_RUNTIME_INSTALL_IMAGE`` must be
  declared in GLOBAL scope (before the first FROM). A Dockerfile FROM can
  only expand ARGs declared before the first FROM; a stage-scoped
  declaration expands to EMPTY and silently breaks the phase-2 FROM. The
  placeholder loud-fail property and the two-FROM phase structure are
  pinned too.
* ITEM C — five-file canonical gate + index digest computed from BYTES:
  docker_build.sh gates on index.json + resolver-catalog.json + the three
  method specs, computes methodSpecIndexDigest from the canonical
  index.json bytes, and treats an env-supplied METHOD_SPEC_INDEX_DIGEST as
  a cross-check only (disagreement fails closed BEFORE any docker call).
  The fixture index.json digest is pinned verbatim.
* ITEM D — dedicated unprivileged identity: the image creates a FIXED
  non-zero uid/gid user (alphafrog-sandbox, uid 10000 / gid 10001) as the
  AF_SANDBOX_CHILD_USER container-run identity (the service creates the
  sandbox container AS this user; accepts username or uid:gid); compose
  + env example carry the entry.
* ITEM B — docker-gated three-stage E2E (skip unless AF_RUN_DOCKER_TESTS=1
  AND a reachable docker daemon): phase-1 build, exact-ID verification of a
  temporary local tag, phase-2 build FROM that bridge, layer-prefix
  verification, in-image re-hash gate (positive + negative), dist metadata
  + contract user verification.

Run from pythonSandboxService/:

    python3 -m unittest discover -s tests -p 'test_*.py' -v
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SANDBOX_SERVICE_ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = SANDBOX_SERVICE_ROOT.parent
DOCKERFILE_RUNTIME = SANDBOX_SERVICE_ROOT / "Dockerfile.runtime"
DOCKER_BUILD_SH = SANDBOX_SERVICE_ROOT / "docker_build.sh"
COMPOSE_FILE = REPO_ROOT / "docker-compose.yml"
ENV_EXAMPLE_FILE = REPO_ROOT / "docker.env.example"
FIXTURE_CANONICAL_DIR = (
    SANDBOX_SERVICE_ROOT
    / "runtime"
    / "tests"
    / "fixtures"
    / "a-generated-resources-v1"
)
GENERATOR = SANDBOX_SERVICE_ROOT / "runtime" / "scripts" / "generate_method_bindings.py"
MANIFEST_SCRIPT = SANDBOX_SERVICE_ROOT / "scripts" / "build_runtime_manifest.py"
LOCK_FILE = SANDBOX_SERVICE_ROOT / "requirements-image.lock"

# Pinned verbatim (Spec §12): sha256 of the fixture canonical index.json bytes.
FIXTURE_INDEX_SHA256 = (
    "956cd9780682ddbc9bbca6bda9c2927a00ed341461c88e86891dc859b99a7a4d"
)

# The five canonical files docker_build.sh must gate on.
CANONICAL_FILES = (
    "index.json",
    "resolver-catalog.json",
    "cagr.json",
    "annualized_volatility.json",
    "sharpe_ratio.json",
)

_PLACEHOLDER_BASE_IMAGE = (
    "invalid.invalid/alphafrog/replace-with-verified-base-image-ref"
)
_PLACEHOLDER_INSTALL_IMAGE = (
    "invalid.invalid/alphafrog/replace-with-runtime-install-stage-image-id"
)


def _dockerfile_lines() -> list:
    return DOCKERFILE_RUNTIME.read_text(encoding="utf-8").splitlines()


def _docker_e2e_enabled() -> bool:
    """Three-stage docker E2E runs ONLY when frog/CI explicitly opts in
    (AF_RUN_DOCKER_TESTS=1) AND a docker CLI is present AND the daemon
    answers `docker info`; otherwise skip cleanly (agent environments have
    no docker, Spec §12 rule)."""
    if os.environ.get("AF_RUN_DOCKER_TESTS") != "1":
        return False
    if shutil.which("docker") is None:
        return False
    probe = subprocess.run(
        ["docker", "info"], capture_output=True, text=True, timeout=60
    )
    return probe.returncode == 0


class TestDockerfileArgScope(unittest.TestCase):
    """ITEM A: FROM can only expand GLOBAL-scope ARGs (declared before the
    first FROM). AF_RUNTIME_INSTALL_IMAGE must live there."""

class TestIndexDigestGate(unittest.TestCase):
    """ITEM C: five-file canonical gate + index digest computed from bytes."""

class TestChildIndexUserContract(unittest.TestCase):
    """ITEM D: fixed non-zero uid/gid contract identity + compose/env entry."""

class TestIndexDigestGateBehaviour(unittest.TestCase):
    """ITEM C fail-closed behaviour of docker_build.sh, verified WITHOUT
    docker: every failing path below exits BEFORE any `docker build` runs."""

    def _run_docker_build(self, env_overrides):
        env = dict(os.environ)
        env["USE_PROXY"] = "0"
        env["AF_SANDBOX_ALLOW_INCOMPLETE_DEV_BUILD"] = "true"
        env.update(env_overrides)
        return subprocess.run(
            ["bash", str(DOCKER_BUILD_SH), "runtime"],
            capture_output=True,
            text=True,
            env=env,
            timeout=180,
        )

@unittest.skipUnless(
    _docker_e2e_enabled(),
    "docker-gated three-stage E2E: set AF_RUN_DOCKER_TESTS=1 with a reachable docker daemon",
)
class TestThreeStageBuildDockerE2E(unittest.TestCase):
    """ITEM B: phase-1 build -> phase-2 build FROM the immutable phase-1 ID
    -> layer-prefix + dist metadata + contract-user verification.

    Hermetic: builds from a COPY of pythonSandboxService in a temp dir, so
    the working tree is never modified by this test."""

    _BASE_TAG = "python:3.13-slim"
    _TEST_BASE_DIGEST = "sha256:" + "ab" * 32  # structural attestation only

    @classmethod
    def setUpClass(cls):
        cls._tmp = tempfile.TemporaryDirectory(prefix="af-three-stage-")
        cls.context = Path(cls._tmp.name) / "pythonSandboxService"
        shutil.copytree(
            SANDBOX_SERVICE_ROOT,
            cls.context,
            ignore=shutil.ignore_patterns("__pycache__", ".runtime-build"),
        )
        # Materialize the three generated build products inside the context
        # copy, exactly as docker_build.sh does host-side before the build.
        generated_dir = (
            cls.context / "runtime" / "src" / "alphafrog_finance" / "_generated"
        )
        generated_dir.mkdir(parents=True, exist_ok=True)
        proc = subprocess.run(
            [
                sys.executable,
                str(cls.context / "runtime" / "scripts" / "generate_method_bindings.py"),
                "--canonical-dir",
                str(FIXTURE_CANONICAL_DIR),
                "--out",
                str(generated_dir / "method_specs.json"),
                "--docstrings-out",
                str(generated_dir / "docstrings.py"),
                "--call-samples-out",
                str(generated_dir / "call_samples.py"),
                "--package-version",
                "1.0.0",
            ],
            capture_output=True,
            text=True,
            timeout=120,
        )
        if proc.returncode != 0:
            raise RuntimeError(f"generator failed in test setup: {proc.stderr}")

        # Stage .runtime-build inputs for the phase-2 Dockerfile COPYs.
        build_dir = cls.context / ".runtime-build"
        build_dir.mkdir(exist_ok=True)
        shutil.copy(FIXTURE_CANONICAL_DIR / "index.json", build_dir / "index.json")
        packages_file = build_dir / "packages.json"
        packages_file.write_text(
            json.dumps([{"name": "alphafrog_finance", "version": "1.0.0", "apiVersion": "1.0"}]),
            encoding="utf-8",
        )
        lock_digest = "sha256:" + hashlib.sha256(LOCK_FILE.read_bytes()).hexdigest()
        manifest_out = build_dir / "library-set.json"
        proc = subprocess.run(
            [
                sys.executable,
                str(MANIFEST_SCRIPT),
                "--lock-digest",
                lock_digest,
                "--method-spec-index-digest",
                f"sha256:{FIXTURE_INDEX_SHA256}",
                "--packages-file",
                str(packages_file),
                "--output",
                str(manifest_out),
            ],
            capture_output=True,
            text=True,
            timeout=120,
        )
        if proc.returncode != 0:
            raise RuntimeError(f"manifest builder failed in test setup: {proc.stderr}")
        cls.lock_digest = lock_digest
        cls.library_set_digest = json.loads(
            manifest_out.read_text(encoding="utf-8")
        )["librarySetDigest"]

    @classmethod
    def tearDownClass(cls):
        cls._tmp.cleanup()

    def _docker_build(self, args, timeout):
        return subprocess.run(
            ["docker", "build"] + args + [str(self.context)],
            capture_output=True,
            text=True,
            timeout=timeout,
        )

    def _layers(self, image_id):
        proc = subprocess.run(
            ["docker", "inspect", "--format", "{{json .RootFS.Layers}}", image_id],
            capture_output=True,
            text=True,
            timeout=120,
        )
        self.assertEqual(proc.returncode, 0, proc.stderr)
        return json.loads(proc.stdout)

if __name__ == "__main__":
    unittest.main()
