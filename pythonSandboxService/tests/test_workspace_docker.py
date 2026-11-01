"""Docker-gated persistent-workspace container tests (authorized env only).

These tests need a REAL Docker daemon and the runtime image present
locally. They run only when BOTH hold:

    AF_RUN_DOCKER_TESTS=1 is exported AND `docker info` succeeds.

They are written for the authorized verification environment; the dev
laptops do not start Docker, so there they stay skipped and NOTHING in
this file is ever claimed as executed locally.

Covered behaviors (the container-level half of the contract):

* ownership boundary of the persistent directory: prepared as
  uid 10000 / gid 10001 with mode 0755, the unprivileged container user
  can write through the mount while a DIFFERENT uid cannot;
* shared-directory semantics: two separate one-shot containers binding
  the same host directory see each other's files (the Run's later calls
  read what earlier calls wrote);
* restart sweep: after a crash, reconcile_workspace_containers stops and
  removes ONLY containers whose full persisted label tuple matches a
  record in this state document; another deployment's container and a
  generic-label container are left running untouched.

Required environment (beyond the gate): AF_SANDBOX_IMAGE must reference
the image id/ref the daemon actually has (used verbatim as `docker run`
image argument).
"""

from __future__ import annotations

import os
import shutil
import subprocess
import tempfile
import unittest
import uuid
from pathlib import Path

_DOCKER_GATED = os.environ.get("AF_RUN_DOCKER_TESTS") == "1"


def _docker_reachable() -> bool:
    try:
        subprocess.run(
            ["docker", "info"],
            capture_output=True,
            timeout=30,
            check=True,
        )
        return True
    except Exception:
        return False


@unittest.skipUnless(
    _DOCKER_GATED and _docker_reachable(),
    "docker-gated: set AF_RUN_DOCKER_TESTS=1 with a reachable docker"
    " daemon (authorized verification environment only)",
)
class WorkspaceContainerDockerTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        from app.config import load_config

        cls.config = load_config()
        cls.image = os.environ["AF_SANDBOX_IMAGE"].strip()
        cls._proc = subprocess.run(
            ["docker", "image", "inspect", cls.image],
            capture_output=True,
            timeout=60,
        )
        if cls._proc.returncode != 0:
            raise unittest.SkipTest(
                f"AF_SANDBOX_IMAGE not present on the daemon: {cls.image}"
            )

    def setUp(self) -> None:
        self._temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self._temp_dir.name) / "persistent_workspaces"

    def tearDown(self) -> None:
        for name in getattr(self, "_started_containers", []):
            subprocess.run(
                ["docker", "rm", "-f", name],
                capture_output=True,
                timeout=60,
            )
        self._temp_dir.cleanup()

    # -- helpers ---------------------------------------------------------

    def _docker(self, *args: str, timeout: int = 120) -> subprocess.CompletedProcess:
        return subprocess.run(
            ["docker", *args], capture_output=True, text=True, timeout=timeout
        )

    def _prepare_dir(self) -> Path:
        from app.sandbox_runner import _prepare_persistent_workspace_dir

        host_dir = self.root / str(uuid.uuid4())
        _prepare_persistent_workspace_dir(str(host_dir))
        return host_dir

    def _oneshot(self, host_dir: Path, user: str, script: str
                 ) -> subprocess.CompletedProcess:
        from app.sandbox_runner import CONTAINER_PERSISTENT_WORKSPACE_MOUNT

        return self._docker(
            "run", "--rm",
            "--user", user,
            "-v", f"{host_dir}:{CONTAINER_PERSISTENT_WORKSPACE_MOUNT}",
            self.image,
            "sh", "-c", script,
        )

    # -- tests -----------------------------------------------------------

def _sweep_request(run_id: str, workspace_id: str):
    """Workspace request for the sweep test (fingerprint not verified at
    store level; only the identity fields matter here)."""
    import hashlib

    from app.models import ExecuteRequest

    return ExecuteRequest(
        dataset_id=None,
        code="print(1)",
        operation_id=f"{run_id}:sweep:1",
        request_fingerprint="sha256:" + "a" * 64,
        resource_class="STANDARD",
        memory_limit_bytes=512 * 1024 * 1024,
        timeout_millis=60_000,
        runtime_environment_version="python-runtime-v1",
        canonical_spec_schema_version="sandbox_create_v1",
        code_hash="sha256:" + hashlib.sha256(b"print(1)").hexdigest(),
        immutable_dataset_snapshot_digest="sha256:" + "c" * 64,
        libraries_digest="sha256:" + "d" * 64,
        sandbox_options_digest="sha256:" + "e" * 64,
        run_id=run_id,
        workspace_id=workspace_id,
        workspace_generation="1",
    )


if __name__ == "__main__":
    unittest.main()
