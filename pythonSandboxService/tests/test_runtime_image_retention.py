"""TDD skeletons for pythonSandboxService/scripts/prune_runtime_images.sh (spec §12).

Work package H (金融MethodSpec-V5 §12): runtime image retention. The future
shell script must:

* default to plan mode (print the plan, remove nothing); only ``--apply``
  deletes images;
* treat only images labeled ``com.alphafrog.runtime=true`` as candidates;
* never remove the protection set: the current production image, the previous
  generation, images referenced by QUEUED/RUNNING tasks in ``state.json``, and
  images in use by running containers;
* never touch unknown images (no ``com.alphafrog.runtime`` label).

NOTE: stub contract pending final script interface. The fake ``docker`` CLI
contract (subcommands, env hooks) and the env vars used to point the script at
fixtures are provisional; adjust them when the real script lands. Tests run
via ``python3 -m unittest`` using only the standard library + subprocess.
Until the script exists, every test here fails (expected, TDD).
"""

from __future__ import annotations

import json
import os
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path

SANDBOX_SERVICE_ROOT = Path(__file__).resolve().parents[1]
PRUNE_SCRIPT = SANDBOX_SERVICE_ROOT / "scripts" / "prune_runtime_images.sh"

RUNTIME_LABEL = "com.alphafrog.runtime"

# Fixed fixture image digests (full 64-hex sha256 references).
IMAGE_CURRENT = "sha256:" + "a1" * 32       # current production image
IMAGE_PREVIOUS = "sha256:" + "b2" * 32      # previous generation
IMAGE_TASK_QUEUED = "sha256:" + "c3" * 32   # referenced by QUEUED task
IMAGE_TASK_RUNNING = "sha256:" + "d4" * 32  # referenced by RUNNING task
IMAGE_CONTAINER = "sha256:" + "e5" * 32     # in use by a running container
IMAGE_OLD_RUNTIME = "sha256:" + "f6" * 32   # labeled runtime, unprotected
IMAGE_UNKNOWN = "sha256:" + "07" * 32       # no runtime label -> untouchable

ALL_IMAGES = {
    IMAGE_CURRENT: "registry.local/alphafrog/runtime:current",
    IMAGE_PREVIOUS: "registry.local/alphafrog/runtime:previous",
    IMAGE_TASK_QUEUED: "registry.local/alphafrog/runtime:gen-n-queued",
    IMAGE_TASK_RUNNING: "registry.local/alphafrog/runtime:gen-n-running",
    IMAGE_CONTAINER: "registry.local/alphafrog/runtime:gen-n-inuse",
    IMAGE_OLD_RUNTIME: "registry.local/alphafrog/runtime:gen-old",
    IMAGE_UNKNOWN: "registry.local/somebody/other:v9",
}
RUNTIME_LABELED = {
    IMAGE_CURRENT,
    IMAGE_PREVIOUS,
    IMAGE_TASK_QUEUED,
    IMAGE_TASK_RUNNING,
    IMAGE_CONTAINER,
    IMAGE_OLD_RUNTIME,
}

# Fake docker CLI stub. Stub contract pending final script interface: it logs
# every invocation ("$*") to $DOCKER_CALL_LOG and serves canned output.
DOCKER_STUB = r"""#!/usr/bin/env bash
set -u
printf '%s\n' "$*" >> "${DOCKER_CALL_LOG:?DOCKER_CALL_LOG not set}"
cmd="${1:-}"
case "$cmd" in
  images)
    cat "${FAKE_DOCKER_IMAGES_FILE:?}"
    ;;
  inspect)
    ref="${2:-}"
    f="${FAKE_DOCKER_INSPECT_DIR:?}/${ref//:/__}.json"
    if [ -f "$f" ]; then cat "$f"; else exit 1; fi
    ;;
  ps)
    cat "${FAKE_DOCKER_PS_FILE:?}"
    ;;
  rmi)
    shift
    for ref in "$@"; do printf 'Untagged: %s\n' "$ref"; done
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
        stub_path = tmp / "docker"
        stub_path.write_text(DOCKER_STUB, encoding="utf-8")
        stub_path.chmod(stub_path.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP)
        self.call_log = tmp / "docker-calls.log"
        self.call_log.write_text("", encoding="utf-8")

        # Canned `docker images` output: "<digest> <repo:tag>" per line.
        images_file = tmp / "docker-images.txt"
        images_file.write_text(
            "\n".join(f"{digest} {repo_tag}" for digest, repo_tag in ALL_IMAGES.items())
            + "\n",
            encoding="utf-8",
        )

        # Canned `docker inspect` payloads (labels decide candidacy).
        inspect_dir = tmp / "inspect"
        inspect_dir.mkdir()
        for digest in ALL_IMAGES:
            labels = {}
            if digest in RUNTIME_LABELED:
                labels[RUNTIME_LABEL] = "true"
            document = [{"Id": digest, "RepoDigests": [digest], "Config": {"Labels": labels}}]
            name = digest.replace(":", "__") + ".json"
            (inspect_dir / name).write_text(json.dumps(document), encoding="utf-8")

        # Canned `docker ps` output: one running container on IMAGE_CONTAINER.
        ps_file = tmp / "docker-ps.txt"
        ps_file.write_text(f"container-0001 {IMAGE_CONTAINER}\n", encoding="utf-8")

        # Fixture state.json: QUEUED/RUNNING tasks pin runtime image digests.
        # Shape pending final script interface.
        self.state_file = tmp / "state.json"
        self.state_file.write_text(
            json.dumps(
                {
                    "tasks": {
                        "task-queued": {
                            "status": "QUEUED",
                            "runtimeImageDigest": IMAGE_TASK_QUEUED,
                        },
                        "task-running": {
                            "status": "RUNNING",
                            "runtimeImageDigest": IMAGE_TASK_RUNNING,
                        },
                        "task-done": {
                            "status": "SUCCEEDED",
                            "runtimeImageDigest": IMAGE_OLD_RUNTIME,
                        },
                    }
                }
            ),
            encoding="utf-8",
        )

        self.env = dict(os.environ)
        self.env["PATH"] = str(tmp) + os.pathsep + self.env.get("PATH", "")
        self.env["DOCKER_CALL_LOG"] = str(self.call_log)
        self.env["FAKE_DOCKER_IMAGES_FILE"] = str(images_file)
        self.env["FAKE_DOCKER_INSPECT_DIR"] = str(inspect_dir)
        self.env["FAKE_DOCKER_PS_FILE"] = str(ps_file)
        # Provisional env hooks for the script (pending final interface).
        self.env["AF_STATE_FILE"] = str(self.state_file)
        self.env["AF_CURRENT_RUNTIME_IMAGE"] = IMAGE_CURRENT
        self.env["AF_PREVIOUS_RUNTIME_IMAGE"] = IMAGE_PREVIOUS

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

    def rmi_targets(self) -> list[str]:
        targets: list[str] = []
        for line in self.call_log.read_text(encoding="utf-8").splitlines():
            parts = line.split()
            if parts and parts[0] == "rmi":
                targets.extend(parts[1:])
        return targets

    def assert_rmi_succeeds_cleanly(self, result: subprocess.CompletedProcess) -> None:
        self.assertEqual(result.returncode, 0, f"stdout={result.stdout}\nstderr={result.stderr}")


class PlanModeTest(RuntimeImageRetentionTestBase):
    def test_plan_mode_by_default_never_invokes_rmi(self) -> None:
        result = self.run_prune()
        self.assert_rmi_succeeds_cleanly(result)
        self.assertEqual(self.rmi_targets(), [], "plan mode must not delete anything")


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
        for line in self.call_log.read_text(encoding="utf-8").splitlines():
            parts = line.split()
            if parts and parts[0] == "rmi":
                self.assertNotIn(
                    IMAGE_UNKNOWN,
                    parts[1:],
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


if __name__ == "__main__":
    unittest.main()
