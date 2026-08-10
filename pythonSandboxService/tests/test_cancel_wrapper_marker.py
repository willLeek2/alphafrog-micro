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

2. PURE-FUNCTION unit tests for the fail-closed gates that run BEFORE the
   spawn: ``expected_cancel_marker_path`` (the exact task-local binding,
   env override aware) and ``verify_control_path_permissions`` (codex
   4334bc9d constraint 2 — lstat-based parent-chain verification with the
   child's write path judged by its actual uid/gid; exercised with
   synthetic stat results through the injected ``stat_fn``).

No Docker, no container: these tests run the wrapper as an ordinary host
subprocess in dev mode (non-root wrapper), where the OS permission gate
is skipped by design and the binding gate still runs.  The in-container
root-path permission gate is exercised by the synthetic-stat unit tests
below; a real-container concurrent-cancel run remains UNVERIFIED on this
host (delivery note, codex 5f054201 point 8).
"""

from __future__ import annotations

import base64
import json
import os
import stat
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from app.bounded_exec_wrapper import (
    TASK_CONTROL_ROOT_DEFAULT,
    TASK_CONTROL_ROOT_ENV_NAME,
    WrapperInputError,
    _cancel_marker_exists,
    expected_cancel_marker_path,
    verify_control_path_permissions,
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
        payload = {
            "scriptPath": str(script),
            "timeoutSeconds": 30,
            "effectiveOutputLimits": {
                "stdoutMaxBytes": 65536,
                "stderrMaxBytes": 65536,
                "recordChannelMaxBytes": 65536,
                "recordChannelMaxRecords": 100,
            },
        }
        if marker_path is not None:
            payload["cancelMarkerPath"] = marker_path
        input_path = self.input_dir / "wrapper-input.json"
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


def _fake_stat(mode: int, uid: int = 0, gid: int = 0):
    return SimpleNamespace(st_mode=mode, st_uid=uid, st_gid=gid)


_DIR_ROOT_0700 = stat.S_IFDIR | 0o700   # root-owned, only root can write
_DIR_ROOT_0755 = stat.S_IFDIR | 0o755   # root-owned, world-readable
_MARKER_ROOT_0600 = stat.S_IFREG | 0o600


class VerifyControlPathPermissionsTest(unittest.TestCase):
    """codex 4334bc9d constraint 2: the lstat-based parent-chain gate.

    Every case injects synthetic stat results through ``stat_fn`` so the
    in-container root-path shape (which the non-root macOS host cannot
    create) is exercised deterministically.  The marker path layout is
    ``/ctl/<taskId>/cancel``: task dir ``/ctl/<taskId>``, control root
    ``/ctl``, grand parent ``/``.
    """

    def setUp(self) -> None:
        self.marker = "/ctl/task-1/cancel"
        self.levels = {
            "/ctl/task-1": _fake_stat(_DIR_ROOT_0700),
            "/ctl": _fake_stat(_DIR_ROOT_0700),
            "/": _fake_stat(_DIR_ROOT_0755),
        }

    def stat_fn(self, path: str):
        # Any path not present in ``levels`` — including the marker by
        # default — is reported absent (the writer creates it on demand).
        try:
            return self.levels[path]
        except KeyError:
            raise FileNotFoundError(path) from None

    def verify(self, child_uid: int = 1000, child_gid: int = 1000) -> None:
        verify_control_path_permissions(
            self.marker, child_uid, child_gid, stat_fn=self.stat_fn
        )

    # --- happy paths --------------------------------------------------------

    def test_happy_path_with_absent_marker(self) -> None:
        self.verify()  # no exception: absent marker is the normal state

    def test_happy_path_with_root_owned_regular_marker(self) -> None:
        self.levels[self.marker] = _fake_stat(_MARKER_ROOT_0600)
        self.verify()

    def test_grand_parent_may_be_non_root_owned(self) -> None:
        # Only the task dir and the control root must be root-owned; the
        # control root's own parent just has to be a real directory the
        # child cannot write (owner 2000 != child uid 1000, no group/world
        # write bits).
        self.levels["/"] = _fake_stat(_DIR_ROOT_0755, uid=2000)
        self.verify()

    # --- child identity refusals ---------------------------------------------

    def test_root_child_identity_is_refused(self) -> None:
        with self.assertRaises(WrapperInputError) as raised:
            self.verify(child_uid=0)
        self.assertIn("root child identity", str(raised.exception))

    # --- directory chain refusals --------------------------------------------

    def test_symlinked_level_is_rejected(self) -> None:
        # lstat sees the link itself: S_ISDIR is false → reject, never follow.
        self.levels["/ctl/task-1"] = _fake_stat(stat.S_IFLNK | 0o700)
        with self.assertRaises(WrapperInputError) as raised:
            self.verify()
        self.assertIn("not a real directory", str(raised.exception))

    def test_missing_level_is_rejected(self) -> None:
        del self.levels["/ctl"]
        with self.assertRaises(WrapperInputError) as raised:
            self.verify()
        self.assertIn("cannot be lstat'ed", str(raised.exception))

    def test_non_directory_level_is_rejected(self) -> None:
        self.levels["/ctl"] = _fake_stat(stat.S_IFREG | 0o700)
        with self.assertRaises(WrapperInputError):
            self.verify()

    def test_control_root_must_be_root_owned(self) -> None:
        self.levels["/ctl"] = _fake_stat(_DIR_ROOT_0700, uid=1000)
        with self.assertRaises(WrapperInputError) as raised:
            self.verify()
        self.assertIn("owned by root", str(raised.exception))

    def test_task_dir_must_be_root_owned(self) -> None:
        self.levels["/ctl/task-1"] = _fake_stat(_DIR_ROOT_0700, uid=1000)
        with self.assertRaises(WrapperInputError):
            self.verify()

    def test_group_writable_level_is_rejected(self) -> None:
        self.levels["/ctl"] = _fake_stat(stat.S_IFDIR | 0o770)
        with self.assertRaises(WrapperInputError) as raised:
            self.verify()
        self.assertIn("group/world", str(raised.exception))

    def test_world_writable_level_is_rejected(self) -> None:
        self.levels["/ctl/task-1"] = _fake_stat(stat.S_IFDIR | 0o707)
        with self.assertRaises(WrapperInputError):
            self.verify()

    def test_child_owned_task_dir_is_rejected_by_root_ownership_first(self) -> None:
        # A task dir owned by the child uid is rejected by the
        # root-ownership requirement, which fires BEFORE the child-write
        # check for the two root-required levels (task dir, control root).
        # The child-write-path check itself is reachable only on the
        # grand parent (covered by
        # test_child_writable_grand_parent_is_rejected).
        self.levels["/ctl/task-1"] = _fake_stat(
            stat.S_IFDIR | 0o700, uid=1000
        )
        with self.assertRaises(WrapperInputError) as raised:
            self.verify(child_uid=1000)
        self.assertIn("owned by root", str(raised.exception))

    def test_child_writable_grand_parent_is_rejected(self) -> None:
        # No root-ownership requirement on the grand parent, but the child
        # write path check still applies to it.
        self.levels["/"] = _fake_stat(stat.S_IFDIR | 0o700, uid=1000)
        with self.assertRaises(WrapperInputError) as raised:
            self.verify(child_uid=1000)
        self.assertIn("writable by the child uid", str(raised.exception))

    # --- marker file refusals -------------------------------------------------

    def test_marker_symlink_is_rejected(self) -> None:
        self.levels[self.marker] = _fake_stat(stat.S_IFLNK | 0o600)
        with self.assertRaises(WrapperInputError) as raised:
            self.verify()
        self.assertIn("regular file", str(raised.exception))

    def test_marker_must_be_root_owned(self) -> None:
        self.levels[self.marker] = _fake_stat(_MARKER_ROOT_0600, uid=1000)
        with self.assertRaises(WrapperInputError) as raised:
            self.verify()
        self.assertIn("owned by root", str(raised.exception))

    def test_marker_group_writable_is_rejected(self) -> None:
        self.levels[self.marker] = _fake_stat(stat.S_IFREG | 0o660)
        with self.assertRaises(WrapperInputError):
            self.verify()

    def test_marker_child_owned_is_rejected_by_root_ownership_first(self) -> None:
        # The marker must be root-owned, and that check fires BEFORE the
        # child-write check; since the marker owner must be uid 0 while the
        # child uid is always non-zero, the marker child-write branch is
        # only reachable as a defense-in-depth backstop.  Assert the
        # ordering that actually triggers.
        self.levels[self.marker] = _fake_stat(
            stat.S_IFREG | 0o600, uid=1000
        )
        with self.assertRaises(WrapperInputError) as raised:
            self.verify(child_uid=1000)
        self.assertIn("owned by root", str(raised.exception))

    def test_marker_generic_os_error_is_rejected(self) -> None:
        marker = self.marker

        def stat_fn(path: str):
            if path == marker:
                raise PermissionError("stat blocked")
            return self.levels[path]

        with self.assertRaises(WrapperInputError) as raised:
            verify_control_path_permissions(
                marker, 1000, 1000, stat_fn=stat_fn
            )
        self.assertIn("cannot be lstat'ed", str(raised.exception))


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


if __name__ == "__main__":
    unittest.main()
