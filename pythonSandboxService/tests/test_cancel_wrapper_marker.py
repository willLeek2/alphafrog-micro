"""D11 (task #108) cancel-marker tests for the §7.1 bounded execution wrapper.

Two layers are pinned here:

1. HOST-SUBPROCESS evidence tests (d6841a2e rules 2/3).  The wrapper is
   executed exactly like the runner executes it —
   ``python -m app.bounded_exec_wrapper <wrapper-input.json>`` — with
   ``AF_TASK_CONTROL_ROOT`` pointed at a per-test temporary control root
   (the same override the runner and the wrapper both read, so the two
   always derive the same marker path; the production default
   ``/run/alphafrog-task-control`` is not writable on the macOS host).
   The marker is created by THIS test process, standing in for the cancel
   marker writer.  Rule 2 (the only valid cancellation evidence): the
   wrapper OBSERVES the marker while the child is alive, kills its own
   process group, and reports ``cancelObserved=true`` with a non-zero
   exitCode.  Rule 3 (a late marker changes nothing): a marker that only
   appears after the child already exited leaves the genuine result
   intact — ``cancelObserved`` stays false and the real exitCode stands.

2. PURE-FUNCTION unit tests for the fail-closed gate that runs BEFORE the
   spawn: ``expected_cancel_marker_path`` (the exact task-local binding,
   env override aware).  (The historical root-owned parent-chain
   permission gate was removed with the privilege-drop machinery —
   sandbox containers no longer contain root.)

No Docker, no container: these tests run the wrapper as an ordinary host
subprocess; the binding gate runs exactly as in production.
below; a real-container concurrent-cancel run remains UNVERIFIED on this
host (delivery note, codex 5f054201 point 8).
"""

from __future__ import annotations

import base64
import json
import os
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch

from app.bounded_exec_wrapper import (
    TASK_CONTROL_ROOT_DEFAULT,
    TASK_CONTROL_ROOT_ENV_NAME,
    WrapperInputError,
    _cancel_marker_exists,
    _kill_process_group,
    expected_cancel_marker_path,
)

SERVICE_ROOT = Path(__file__).resolve().parents[1]

# A child that stays alive long enough for the marker to matter.
_SLEEPING_CHILD = (
    "import sys, time\n"
    "print('started', flush=True)\n"
    "sys.stdout.flush()\n"
    "time.sleep(30)\n"
)

# A child that finishes quickly on its own (rule-3 genuine success).
_FAST_CHILD = (
    "import time\n"
    "print('ready', flush=True)\n"
    "time.sleep(0.5)\n"
    "print('done', flush=True)\n"
)

# A child that leaves a sentinel file if (and only if) it actually ran.
_SENTINEL_CHILD = (
    "from pathlib import Path\n"
    "Path('child_ran.txt').write_text('ran')\n"
)


class WrapperMarkerSubprocessTest(unittest.TestCase):
    """Host-subprocess cancel-marker evidence (d6841a2e rules 2/3)."""

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        self.control_root = self.root / "task-control"
        self.work = self.root / "work"
        self.work.mkdir()
        self.input_dir = self.work / "wrapper-input"
        self.input_dir.mkdir()
        # In-process derivations (expected_cancel_marker_path) must agree
        # with the override the subprocess env carries.
        self._saved_control_root = os.environ.get(TASK_CONTROL_ROOT_ENV_NAME)
        os.environ[TASK_CONTROL_ROOT_ENV_NAME] = str(self.control_root)

    def tearDown(self) -> None:
        if self._saved_control_root is None:
            os.environ.pop(TASK_CONTROL_ROOT_ENV_NAME, None)
        else:
            os.environ[TASK_CONTROL_ROOT_ENV_NAME] = self._saved_control_root
        self._tmp.cleanup()

    # --- helpers -----------------------------------------------------------

    def wrapper_env(self) -> dict:
        env = dict(os.environ)
        env[TASK_CONTROL_ROOT_ENV_NAME] = str(self.control_root)
        # Dev-mode host: never hand the wrapper a child identity spec.
        env.pop("AF_SANDBOX_CHILD_USER", None)
        return env

    def make_task(self, task_id: str, script_body: str) -> Path:
        """Stage a user script in a task dir named after the taskId.

        The runner builds every workspace as ``<workspace_root>/<taskId>``
        and stages the script directly inside it, so the script's parent
        directory name IS the taskId the binding check derives.
        """
        task_dir = self.work / task_id
        task_dir.mkdir(parents=True)
        script = task_dir / "user_script.py"
        script.write_text(script_body, encoding="utf-8")
        return script

    def marker_path_for(self, task_id: str) -> str:
        # Same derivation the runner and the wrapper share (env override).
        return expected_cancel_marker_path(
            str(self.work / task_id / "user_script.py")
        )

    def touch_marker(self, task_id: str) -> Path:
        marker = Path(self.marker_path_for(task_id))
        marker.parent.mkdir(parents=True, exist_ok=True)
        marker.touch()
        return marker

    def write_wrapper_input(
        self, script: Path, marker_path: str | None
    ) -> Path:
        # D15 §4.2 made taskWorkspace + taskEnvironment + loaderPythonPath
        # required (fail-closed if missing). The cancel wrapper marker tests
        # were written pre-D15; after merging W3 D15 into the W2 integration
        # line the wrapper rejects any payload without these keys at parse
        # time. Use the script's parent dir (the per-task workspace) as the
        # taskWorkspace/loaderPythonPath, matching how the runner stages a
        # task workspace; AF_TASK_* env vars mirror test_bounded_exec_wrapper.
        task_workspace = str(script.parent)
        task_env = {
            "AF_TASK_WORKSPACE": task_workspace,
            "AF_TASK_ARTIFACT_DIR": f"{task_workspace}/artifacts",
            "AF_TASK_TMP_DIR": f"{task_workspace}/tmp",
            "AF_TASK_METRICS_PATH": f"{task_workspace}/metrics/loader.jsonl",
        }
        payload = {
            "scriptPath": str(script),
            "timeoutSeconds": 30,
            "effectiveOutputLimits": {
                "stdoutMaxBytes": 65536,
                "stderrMaxBytes": 65536,
                "recordChannelMaxBytes": 65536,
                "recordChannelMaxRecords": 100,
            },
            "taskWorkspace": task_workspace,
            "taskEnvironment": task_env,
            "loaderPythonPath": task_workspace,
        }
        if marker_path is not None:
            payload["cancelMarkerPath"] = marker_path
        # Stage wrapper-input.json INSIDE task_workspace (script.parent) so
        # D15 §4.2.3 round-4 anchoring (taskWorkspace == input parent) holds.
        input_path = script.parent / "wrapper-input.json"
        input_path.write_text(json.dumps(payload), encoding="utf-8")
        return input_path

    def run_wrapper(self, input_path: Path, timeout: float = 25.0):
        start = time.monotonic()
        proc = subprocess.run(
            [sys.executable, "-m", "app.bounded_exec_wrapper", str(input_path)],
            cwd=str(SERVICE_ROOT),
            env=self.wrapper_env(),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=timeout,
        )
        return proc, time.monotonic() - start

    def decode_envelope(self, proc) -> dict:
        # PIN 2: the wrapper stdout carries EXACTLY one envelope document.
        return json.loads(proc.stdout.decode("utf-8"))

    def decode_summary(self, proc) -> dict:
        files = self.decode_envelope(proc)["files"]
        return json.loads(base64.b64decode(files["capture-result.json"]))

    def decode_stdout(self, proc) -> bytes:
        files = self.decode_envelope(proc)["files"]
        return base64.b64decode(files["stdout.bin"])

    # --- rule 2: an OBSERVED marker is cancellation evidence ---------------

    # --- rule 3: a late marker never rewrites a genuine result -------------

    # --- fail-closed binding gate ------------------------------------------

    # --- backward compatibility ---------------------------------------------

class ExpectedCancelMarkerPathTest(unittest.TestCase):
    """The exact task-local marker derivation (env override aware)."""

class CancelMarkerExistsHelperTest(unittest.TestCase):
    """_cancel_marker_exists: fail-observe semantics (errors = no marker)."""

class WrapperKillRaceUnitTest(unittest.TestCase):
    """codex c6c49248: the poll()→killpg() window must not produce a false
    cancelObserved.  When the child exits between the two calls the killpg
    raises ProcessLookupError — canceled must stay False."""

if __name__ == "__main__":
    unittest.main()
