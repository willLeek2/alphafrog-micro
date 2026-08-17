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

    def test_marker_present_before_spawn_cancels_the_run(self) -> None:
        """Marker already in place when the run starts: the very first
        deadline-loop turn observes it while the child is alive, kills the
        child group, and reports cancelObserved=true with a non-zero exit."""
        script = self.make_task("task-precancel", _SLEEPING_CHILD)
        self.touch_marker("task-precancel")
        input_path = self.write_wrapper_input(
            script, self.marker_path_for("task-precancel")
        )

        proc, elapsed = self.run_wrapper(input_path)

        self.assertEqual(proc.returncode, 0, proc.stderr.decode())
        # The kill must come from the marker, not the 30 s timeout.
        self.assertLess(elapsed, 10.0)
        summary = self.decode_summary(proc)
        self.assertTrue(summary["cancelObserved"])
        self.assertNotEqual(summary["exitCode"], 0)
        # NOTE: no stdout assertion here — the marker exists BEFORE the
        # spawn, so the wrapper kills the child on its very first loop
        # turn, before the child has printed anything.  The mid-run test
        # below is the one that pins "output emitted before the kill".

    def test_marker_created_mid_run_cancels_the_run(self) -> None:
        """The production shape: the cancel writer touches the marker while
        the child is running; the wrapper kills its own process group and
        finishes promptly with cancelObserved=true."""
        script = self.make_task("task-midcancel", _SLEEPING_CHILD)
        input_path = self.write_wrapper_input(
            script, self.marker_path_for("task-midcancel")
        )

        start = time.monotonic()
        proc = subprocess.Popen(
            [sys.executable, "-m", "app.bounded_exec_wrapper", str(input_path)],
            cwd=str(SERVICE_ROOT),
            env=self.wrapper_env(),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        try:
            time.sleep(1.0)  # the child is alive and has printed "started"
            self.touch_marker("task-midcancel")
            stdout, stderr = proc.communicate(timeout=20.0)
        finally:
            if proc.poll() is None:
                proc.kill()
                proc.wait(timeout=10)
        elapsed = time.monotonic() - start

        completed = subprocess.CompletedProcess(proc.args, proc.returncode, stdout, stderr)
        self.assertEqual(proc.returncode, 0, stderr.decode())
        # Cancellation evidence, not the 30 s timeout, ended the run.
        self.assertLess(elapsed, 15.0)
        summary = self.decode_summary(completed)
        self.assertTrue(summary["cancelObserved"])
        self.assertNotEqual(summary["exitCode"], 0)
        self.assertIn(b"started", base64.b64decode(
            self.decode_envelope(completed)["files"]["stdout.bin"]
        ))

    # --- rule 3: a late marker never rewrites a genuine result -------------

    def test_late_marker_after_child_exit_keeps_genuine_success(self) -> None:
        """A marker that appears only after the child exited normally must
        not change anything: the run stays a genuine success (exitCode 0,
        cancelObserved false).  The wrapper only consults the marker inside
        its deadline loop, and a completed child ends that loop with the
        true exit result; by the time this test touches the marker the
        child is long dead (≥1 s after its normal exit), so whatever the
        exact internal interleaving is, the OBSERVABLE contract is pinned:
        late marker → genuine result untouched."""
        script = self.make_task("task-latecancel", _FAST_CHILD)
        input_path = self.write_wrapper_input(
            script, self.marker_path_for("task-latecancel")
        )

        start = time.monotonic()
        proc = subprocess.Popen(
            [sys.executable, "-m", "app.bounded_exec_wrapper", str(input_path)],
            cwd=str(SERVICE_ROOT),
            env=self.wrapper_env(),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        try:
            # The fast child exits ~0.5 s after spawn; waiting 2.5 s makes
            # its normal exit a certainty before the marker appears.
            time.sleep(2.5)
            self.touch_marker("task-latecancel")
            stdout, stderr = proc.communicate(timeout=25.0)
        finally:
            if proc.poll() is None:
                proc.kill()
                proc.wait(timeout=10)
        elapsed = time.monotonic() - start

        completed = subprocess.CompletedProcess(proc.args, proc.returncode, stdout, stderr)
        self.assertEqual(proc.returncode, 0, stderr.decode())
        self.assertLess(elapsed, 20.0)
        summary = self.decode_summary(completed)
        self.assertFalse(summary["cancelObserved"])
        self.assertEqual(summary["exitCode"], 0)
        stdout_bytes = base64.b64decode(
            self.decode_envelope(completed)["files"]["stdout.bin"]
        )
        self.assertIn(b"ready", stdout_bytes)
        self.assertIn(b"done", stdout_bytes)

    # --- fail-closed binding gate ------------------------------------------

    def test_binding_mismatch_rejected_before_spawn(self) -> None:
        """A cancelMarkerPath that is not the task-local derivation
        (<control_root>/<taskId>/cancel from scriptPath) is rejected:
        exit 2, a short diagnostic, no envelope, and NO child ever runs."""
        task_id = "task-bind"
        script = self.make_task(task_id, _SENTINEL_CHILD)
        wrong_marker = str(self.control_root / "other-task" / "cancel")
        input_path = self.write_wrapper_input(script, wrong_marker)

        proc, _ = self.run_wrapper(input_path)

        self.assertEqual(proc.returncode, 2)
        self.assertEqual(proc.stdout, b"")  # no envelope on the reject path
        self.assertIn(
            b"cancelMarkerPath does not match", proc.stderr
        )
        # The binding gate runs BEFORE the spawn: the child never ran.
        self.assertFalse((self.work / task_id / "child_ran.txt").exists())
        # ...and no summary was written.
        self.assertFalse(
            (self.input_dir / "capture" / "capture-result.json").exists()
        )

    # --- backward compatibility ---------------------------------------------

    def test_input_without_marker_path_still_runs(self) -> None:
        """Pre-D11 wrapper inputs (no cancelMarkerPath) keep their exact
        historical semantics: a plain successful run, cancelObserved false."""
        script = self.make_task(
            "task-nomarker", "print('hello', flush=True)\n"
        )
        input_path = self.write_wrapper_input(script, None)

        proc, _ = self.run_wrapper(input_path)

        self.assertEqual(proc.returncode, 0, proc.stderr.decode())
        summary = self.decode_summary(proc)
        self.assertFalse(summary["cancelObserved"])
        self.assertEqual(summary["exitCode"], 0)
        self.assertIn(b"hello", self.decode_stdout(proc))


class ExpectedCancelMarkerPathTest(unittest.TestCase):
    """The exact task-local marker derivation (env override aware)."""

    def test_env_override_is_used(self) -> None:
        with patch.dict(
            os.environ, {TASK_CONTROL_ROOT_ENV_NAME: "/tmp/ctl"}
        ):
            self.assertEqual(
                "/tmp/ctl/task-123/cancel",
                expected_cancel_marker_path(
                    "/sandbox/tasks/task-123/user_script.py"
                ),
            )

    def test_env_override_trailing_slash_is_stripped(self) -> None:
        with patch.dict(
            os.environ, {TASK_CONTROL_ROOT_ENV_NAME: "/tmp/ctl/"}
        ):
            self.assertEqual(
                "/tmp/ctl/task-123/cancel",
                expected_cancel_marker_path("/work/task-123/main.py"),
            )

    def test_blank_env_override_falls_back_to_default(self) -> None:
        with patch.dict(os.environ, {TASK_CONTROL_ROOT_ENV_NAME: "   "}):
            self.assertEqual(
                f"{TASK_CONTROL_ROOT_DEFAULT}/task-123/cancel",
                expected_cancel_marker_path("/work/task-123/main.py"),
            )

    def test_default_control_root_without_env(self) -> None:
        env = dict(os.environ)
        env.pop(TASK_CONTROL_ROOT_ENV_NAME, None)
        with patch.dict(os.environ, env, clear=True):
            self.assertEqual(
                "/run/alphafrog-task-control/task-9/cancel",
                expected_cancel_marker_path("/sandbox/ws/task-9/user_script.py"),
            )

    def test_different_tasks_get_different_marker_paths(self) -> None:
        with patch.dict(
            os.environ, {TASK_CONTROL_ROOT_ENV_NAME: "/tmp/ctl"}
        ):
            first = expected_cancel_marker_path("/ws/task-a/user_script.py")
            second = expected_cancel_marker_path("/ws/task-b/user_script.py")
            self.assertNotEqual(first, second)
            self.assertEqual("/tmp/ctl/task-a/cancel", first)
            self.assertEqual("/tmp/ctl/task-b/cancel", second)




class CancelMarkerExistsHelperTest(unittest.TestCase):
    """_cancel_marker_exists: fail-observe semantics (errors = no marker)."""

    def test_present_marker_is_true(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            marker = Path(tmp) / "cancel"
            marker.touch()
            self.assertTrue(_cancel_marker_exists(str(marker)))

    def test_absent_marker_is_false(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            self.assertFalse(
                _cancel_marker_exists(str(Path(tmp) / "nope" / "cancel"))
            )


class WrapperKillRaceUnitTest(unittest.TestCase):
    """codex c6c49248: the poll()→killpg() window must not produce a false
    cancelObserved.  When the child exits between the two calls the killpg
    raises ProcessLookupError — canceled must stay False."""

    @patch("os.killpg")
    def test_process_lookup_error_means_child_exited_on_its_own(self, mock_killpg):
        mock_killpg.side_effect = ProcessLookupError
        result = _kill_process_group(99999)
        self.assertFalse(result)
        mock_killpg.assert_called_once_with(99999, signal.SIGKILL)

    @patch("os.killpg")
    def test_successful_kill_returns_true(self, mock_killpg):
        mock_killpg.return_value = None
        result = _kill_process_group(99999)
        self.assertTrue(result)

    @patch("os.killpg")
    def test_generic_os_error_also_returns_false(self, mock_killpg):
        mock_killpg.side_effect = OSError("permission denied")
        result = _kill_process_group(99999)
        self.assertFalse(result)


if __name__ == "__main__":
    unittest.main()
