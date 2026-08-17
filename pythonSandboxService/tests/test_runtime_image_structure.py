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
  AF_SANDBOX_CHILD_USER contract identity for the work package C wrapper
  (accepts username or uid:gid); compose + env example carry the entry.
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

    def test_install_image_arg_is_global_and_unique(self):
        lines = _dockerfile_lines()
        first_from = next(i for i, l in enumerate(lines) if re.match(r"^FROM\s", l))
        declarations = [
            i
            for i, l in enumerate(lines)
            if re.match(r"^ARG\s+AF_RUNTIME_INSTALL_IMAGE=", l)
        ]
        self.assertEqual(
            len(declarations),
            1,
            "AF_RUNTIME_INSTALL_IMAGE must be declared EXACTLY once (global scope)",
        )
        self.assertLess(
            declarations[0],
            first_from,
            "AF_RUNTIME_INSTALL_IMAGE must be declared BEFORE the first FROM "
            "(a stage-scoped ARG expands to empty in FROM)",
        )
        # Loud-fail placeholder property preserved.
        self.assertIn(_PLACEHOLDER_INSTALL_IMAGE, lines[declarations[0]])

    def test_base_image_arg_is_global(self):
        lines = _dockerfile_lines()
        first_from = next(i for i, l in enumerate(lines) if re.match(r"^FROM\s", l))
        declarations = [
            i
            for i, l in enumerate(lines)
            if re.match(r"^ARG\s+RUNTIME_BASE_IMAGE_REF=", l)
        ]
        self.assertEqual(len(declarations), 1)
        self.assertLess(declarations[0], first_from)
        self.assertIn(_PLACEHOLDER_BASE_IMAGE, lines[declarations[0]])

    def test_exactly_two_froms_in_documented_phase_order(self):
        froms = [l for l in _dockerfile_lines() if re.match(r"^FROM\s", l)]
        self.assertEqual(len(froms), 2, f"expected exactly two FROMs, got {froms!r}")
        self.assertEqual(froms[0], "FROM ${RUNTIME_BASE_IMAGE_REF} AS runtime-install")
        self.assertEqual(froms[1], "FROM ${AF_RUNTIME_INSTALL_IMAGE}")


class TestIndexDigestGate(unittest.TestCase):
    """ITEM C: five-file canonical gate + index digest computed from bytes."""

    def test_fixture_index_digest_pinned_verbatim(self):
        index_path = FIXTURE_CANONICAL_DIR / "index.json"
        digest = hashlib.sha256(index_path.read_bytes()).hexdigest()
        self.assertEqual(digest, FIXTURE_INDEX_SHA256)

    def test_docker_build_gates_on_all_five_canonical_files(self):
        text = DOCKER_BUILD_SH.read_text(encoding="utf-8")
        for filename in CANONICAL_FILES:
            self.assertIn(
                filename,
                text,
                f"docker_build.sh canonical gate must name {filename}",
            )
        # The gate loop lists index.json explicitly.
        self.assertRegex(text, r"for canonical_file in[^;]*index\.json")

    def test_digest_is_computed_from_index_bytes_not_trusted(self):
        text = DOCKER_BUILD_SH.read_text(encoding="utf-8")
        self.assertIn('file_sha256 "$canonical_dir/index.json"', text)

    def test_dockerfile_rehashes_baked_index_before_manifest(self):
        text = DOCKERFILE_RUNTIME.read_text(encoding="utf-8")
        self.assertIn(".runtime-build/index.json", text)
        self.assertIn("sha256sum /opt/alphafrog/runtime/index.json", text)
        self.assertIn("${AF_METHOD_SPEC_INDEX_DIGEST}", text)
        # The re-hash RUN must precede the baked manifest COPY.
        self.assertLess(
            text.index("sha256sum /opt/alphafrog/runtime/index.json"),
            text.index("COPY .runtime-build/library-set.json"),
        )


class TestChildIndexUserContract(unittest.TestCase):
    """ITEM D: fixed non-zero uid/gid contract identity + compose/env entry."""

    def test_dockerfile_creates_fixed_nonzero_uid_gid_user(self):
        text = DOCKERFILE_RUNTIME.read_text(encoding="utf-8")
        self.assertIn("groupadd --gid 10001 alphafrog-sandbox", text)
        match = re.search(
            r"useradd --uid (\d+) --gid (\d+).*?alphafrog-sandbox", text, re.DOTALL
        )
        self.assertIsNotNone(match, "useradd for alphafrog-sandbox not found")
        uid, gid = int(match.group(1)), int(match.group(2))
        self.assertNotEqual(uid, 0, "contract user must NOT be root")
        self.assertNotEqual(gid, 0, "contract group must NOT be root")
        self.assertEqual((uid, gid), (10000, 10001))
        # The image must NOT switch its default USER to the contract user:
        # privilege dropping is the work package C wrapper's job.
        self.assertIsNone(
            re.search(r"^USER\s", text, re.MULTILINE),
            "image default USER must stay root",
        )

    def test_compose_python_sandbox_service_carries_child_user_env(self):
        text = COMPOSE_FILE.read_text(encoding="utf-8")
        self.assertIn("AF_SANDBOX_CHILD_USER:", text)

    def test_env_example_documents_username_or_uid_gid_forms(self):
        text = ENV_EXAMPLE_FILE.read_text(encoding="utf-8")
        self.assertIn("AF_SANDBOX_CHILD_USER", text)
        self.assertIn("alphafrog-sandbox", text)
        self.assertIn("10000:10001", text)


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

    def test_env_digest_disagreeing_with_computed_fails_closed(self):
        proc = self._run_docker_build(
            {
                "METHOD_SPEC_CANONICAL_DIR": str(FIXTURE_CANONICAL_DIR),
                "METHOD_SPEC_INDEX_DIGEST": "sha256:" + "0" * 64,
            }
        )
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("does NOT match", proc.stderr)
        self.assertIn(FIXTURE_INDEX_SHA256, proc.stderr)

    def test_malformed_env_digest_fails_closed(self):
        proc = self._run_docker_build(
            {
                "METHOD_SPEC_CANONICAL_DIR": str(FIXTURE_CANONICAL_DIR),
                "METHOD_SPEC_INDEX_DIGEST": "sha256:ZZZ",
            }
        )
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("not a valid sha256", proc.stderr)

    def test_missing_index_json_in_canonical_dir_fails_closed(self):
        with tempfile.TemporaryDirectory(prefix="af-canonical-gap-") as tmp:
            for filename in CANONICAL_FILES:
                if filename == "index.json":
                    continue
                shutil.copy(FIXTURE_CANONICAL_DIR / filename, Path(tmp) / filename)
            proc = self._run_docker_build({"METHOD_SPEC_CANONICAL_DIR": tmp})
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("canonical method-spec input missing", proc.stderr)
        self.assertIn("index.json", proc.stderr)

    def test_missing_canonical_dir_fails_closed(self):
        proc = self._run_docker_build(
            {"METHOD_SPEC_CANONICAL_DIR": "/nonexistent-canonical-dir"}
        )
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("is not a directory", proc.stderr)


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

    def test_three_stage_build_and_verification(self):
        iid1 = Path(self._tmp.name) / "id1"
        iid2 = Path(self._tmp.name) / "id2"

        # --- phase 1: runtime-install stage ---
        phase1 = self._docker_build(
            [
                "--target",
                "runtime-install",
                "-f",
                "Dockerfile.runtime",
                "--iidfile",
                str(iid1),
                "--build-arg",
                f"RUNTIME_BASE_IMAGE_REF={self._BASE_TAG}",
                "--build-arg",
                f"AF_RUNTIME_INSTALL_IMAGE={self._BASE_TAG}",
            ],
            timeout=1800,
        )
        self.assertEqual(
            phase1.returncode,
            0,
            f"phase-1 build failed\nstdout={phase1.stdout[-2000:]}\n"
            f"stderr={phase1.stderr[-2000:]}",
        )
        install_id = iid1.read_text(encoding="utf-8").strip()

        # --- phase bridge: local tag verified against the immutable ID ---
        # BuildKit interprets a bare local sha256:<Image ID> in FROM as a
        # registry reference. Match docker_build.sh: create a unique local
        # tag and immediately prove that it resolves to the exact phase-1 ID.
        install_ref = (
            "alphafrog-runtime-install-test:"
            f"{install_id.removeprefix('sha256:')}-{os.getpid()}"
        )
        tagged = subprocess.run(
            ["docker", "tag", install_id, install_ref],
            capture_output=True,
            text=True,
            timeout=120,
        )
        self.assertEqual(tagged.returncode, 0, tagged.stderr)
        self.addCleanup(
            subprocess.run,
            ["docker", "image", "rm", install_ref],
            capture_output=True,
            text=True,
            timeout=120,
            check=False,
        )
        inspected = subprocess.run(
            ["docker", "image", "inspect", "--format", "{{.Id}}", install_ref],
            capture_output=True,
            text=True,
            timeout=120,
        )
        self.assertEqual(inspected.returncode, 0, inspected.stderr)
        self.assertEqual(inspected.stdout.strip(), install_id)

        # --- phase 2: FROM the verified local bridge to the phase-1 ID ---
        phase2_args = [
            "-f",
            "Dockerfile.runtime",
            "--iidfile",
            str(iid2),
            "--build-arg",
            f"RUNTIME_BASE_IMAGE_REF={self._BASE_TAG}",
            "--build-arg",
            f"AF_RUNTIME_INSTALL_IMAGE={install_ref}",
            "--build-arg",
            f"AF_BASE_IMAGE_DIGEST={self._TEST_BASE_DIGEST}",
            "--build-arg",
            f"AF_LOCK_DIGEST={self.lock_digest}",
            "--build-arg",
            f"AF_METHOD_SPEC_INDEX_DIGEST=sha256:{FIXTURE_INDEX_SHA256}",
            "--build-arg",
            f"AF_LIBRARY_SET_DIGEST={self.library_set_digest}",
        ]
        phase2 = self._docker_build(phase2_args, timeout=900)
        self.assertEqual(
            phase2.returncode,
            0,
            f"phase-2 build failed\nstdout={phase2.stdout[-2000:]}\n"
            f"stderr={phase2.stderr[-2000:]}",
        )
        final_id = iid2.read_text(encoding="utf-8").strip()

        # --- layer prefix: phase 2 extends phase 1, never rebuilds it ---
        layers1 = self._layers(install_id)
        layers2 = self._layers(final_id)
        self.assertGreaterEqual(len(layers2), len(layers1))
        self.assertEqual(layers2[: len(layers1)], layers1)

        # --- dist metadata: alphafrog_finance installed with frozen identity ---
        dist = subprocess.run(
            [
                "docker",
                "run",
                "--rm",
                final_id,
                "python",
                "-c",
                "import importlib.metadata as md, alphafrog_finance as af;"
                "print(md.version('alphafrog_finance'), af.__version__, af.__api_version__)",
            ],
            capture_output=True,
            text=True,
            timeout=300,
        )
        self.assertEqual(dist.returncode, 0, dist.stderr)
        self.assertEqual(dist.stdout.split(), ["1.0.0", "1.0.0", "1.0"])

        # --- smoke gate under the final image's system python ---
        smoke = subprocess.run(
            ["docker", "run", "--rm", final_id, "python", "/opt/alphafrog/build/smoke_runtime_image.py"],
            capture_output=True,
            text=True,
            timeout=300,
        )
        self.assertEqual(smoke.returncode, 0, smoke.stderr)
        self.assertIn("smoke OK", smoke.stdout)

        # --- contract user identity present with the fixed uid:gid ---
        identity = subprocess.run(
            ["docker", "run", "--rm", final_id, "id", "alphafrog-sandbox"],
            capture_output=True,
            text=True,
            timeout=300,
        )
        self.assertEqual(identity.returncode, 0, identity.stderr)
        self.assertIn("uid=10000", identity.stdout)
        self.assertIn("gid=10001", identity.stdout)

        # --- baked index.json bytes digest verified in-image ---
        baked = subprocess.run(
            ["docker", "run", "--rm", final_id, "sha256sum", "/opt/alphafrog/runtime/index.json"],
            capture_output=True,
            text=True,
            timeout=300,
        )
        self.assertEqual(baked.returncode, 0, baked.stderr)
        self.assertIn(FIXTURE_INDEX_SHA256, baked.stdout)

        # --- NEGATIVE: a disagreeing AF_METHOD_SPEC_INDEX_DIGEST must fail
        # the in-image re-hash gate (phase-1 layers stay cached, so this only
        # rebuilds the final stage).
        bad_args = [
            arg
            for arg in phase2_args
            if not arg.startswith("--iidfile") and arg != str(iid2)
        ]
        bad_args[bad_args.index(f"AF_METHOD_SPEC_INDEX_DIGEST=sha256:{FIXTURE_INDEX_SHA256}")] = (
            "AF_METHOD_SPEC_INDEX_DIGEST=sha256:" + "0" * 64
        )
        bad = self._docker_build(bad_args + ["--iidfile", str(Path(self._tmp.name) / "id3")], timeout=900)
        self.assertNotEqual(
            bad.returncode,
            0,
            "phase-2 build with a disagreeing index digest must FAIL closed",
        )
        self.assertIn("index.json digest", bad.stderr)


if __name__ == "__main__":
    unittest.main()
