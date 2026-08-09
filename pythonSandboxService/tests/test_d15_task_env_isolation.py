# === D15 §4.2 (Scenario B): task-isolated AF_TASK_* env injection ===
"""D15 Scenario B (W3 task #103, branch ccmax/260809-26q3-stage1-w3-d15-b).

Cross-task env isolation: AF_TASK_WORKSPACE / AF_TASK_ARTIFACT_DIR /
AF_TASK_TMP_DIR / AF_TASK_METRICS_PATH travel inside the per-task
wrapper-input.json (already task-local at ``{task_workspace}/wrapper-input.json``)
and the wrapper injects them into the user child via ``Popen(env=...)``. The
global ``/sandbox/sitecustomize.py`` is no longer written per task, so a
cleanup failure or pool reuse can never let task B see task A's env.

Three contract surfaces pinned here:

1. **Cross-task isolation** — two wrapper runs in the SAME process (one
   Python interpreter, like a single sandbox container) with DIFFERENT
   taskEnvironment values cannot cross-contaminate: each user child only
   ever sees its own AF_TASK_WORKSPACE. The legacy sitecustomize regime
   could not guarantee this because the same global file was rewritten
   per task.
2. **Fail-closed on missing task env** — if the wrapper-input lacks any of
   the four required AF_TASK_* keys or taskWorkspace, the wrapper exits
   non-zero BEFORE spawning the child and does NOT silently fall back to
   writing a global sitecustomize.py (D15 §6 red line 4).
3. **sitecustomize write path removed** — ``_prepare_task_workspace`` no
   longer calls ``copy_to_runtime`` for ``sitecustomize.py``; AF_TASK_*
   propagates exclusively via wrapper-input.json's taskEnvironment field
   (D15 §6 red line 3).

Run from pythonSandboxService/::

    AF_SANDBOX_IMAGE=alphafrog-sandbox-runtime:latest \\
    AF_SANDBOX_IMAGE_ALLOW_DEV_TAG=true \\
    python3 -m unittest tests.test_d15_task_env_isolation -v
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
import stat
import tempfile
import types
import unittest
from pathlib import Path

# Host-runnable llm_sandbox stub (no Docker in this suite, matching the
# convention in tests.test_bounded_wrapper_wiring).
_llm_sandbox = types.ModuleType("llm_sandbox")
_llm_sandbox.SandboxSession = object
_llm_sandbox_exceptions = types.ModuleType("llm_sandbox.exceptions")
_llm_sandbox_exceptions.SandboxTimeoutError = TimeoutError
sys.modules.setdefault("llm_sandbox", _llm_sandbox)
sys.modules.setdefault("llm_sandbox.exceptions", _llm_sandbox_exceptions)

_SERVICE_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _SERVICE_ROOT not in sys.path:
    sys.path.insert(0, _SERVICE_ROOT)

from app import sandbox_runner  # noqa: E402

_DEFAULT_LIMITS = {
    "stdoutMaxBytes": 1048576,
    "stderrMaxBytes": 262144,
    "recordChannelMaxBytes": 262144,
    "recordChannelMaxRecords": 128,
}


def _make_task_env(task_workspace: str) -> dict[str, str]:
    """Build the four AF_TASK_* variables for a given task workspace path."""
    return {
        "AF_TASK_WORKSPACE": task_workspace,
        "AF_TASK_ARTIFACT_DIR": f"{task_workspace}/artifacts",
        "AF_TASK_TMP_DIR": f"{task_workspace}/tmp",
        "AF_TASK_METRICS_PATH": f"{task_workspace}/metrics/loader.jsonl",
    }


def _write_wrapper_input(
    input_path: Path,
    *,
    script_path: Path,
    task_workspace: str,
    task_env: dict[str, str],
    timeout_seconds: int = 30,
    limits: dict | None = None,
    loader_python_path: str | None = None,
    runtime_env_path: Path | None = None,
) -> None:
    """Stage a wrapper-input.json payload matching the new D15 schema."""
    if runtime_env_path is None:
        runtime_env_path = script_path.parent / "runtime-environment.json"
    if not runtime_env_path.exists():
        runtime_env_path.write_text("{}", encoding="utf-8")
    payload = {
        "scriptPath": str(script_path),
        "timeoutSeconds": timeout_seconds,
        "effectiveOutputLimits": dict(limits or _DEFAULT_LIMITS),
        "runtimeEnvironmentPath": str(runtime_env_path),
        "taskWorkspace": task_workspace,
        "taskEnvironment": dict(task_env),
        "loaderPythonPath": loader_python_path or task_workspace,
    }
    input_path.write_text(json.dumps(payload), encoding="utf-8")


def _run_wrapper(input_path: Path) -> subprocess.CompletedProcess:
    """Run the REAL wrapper subprocess (no Docker); host Python is fine."""
    return subprocess.run(
        [sys.executable, "-m", "app.bounded_exec_wrapper", str(input_path)],
        cwd=_SERVICE_ROOT,
        capture_output=True,
        timeout=60,
    )


class CrossTaskEnvIsolationTest(unittest.TestCase):
    """Two tasks in the same interpreter cannot see each other's AF_TASK_*."""

    def setUp(self) -> None:
        self._tmp_a = tempfile.TemporaryDirectory(prefix="af-d15-task-a-")
        self._tmp_b = tempfile.TemporaryDirectory(prefix="af-d15-task-b-")
        self.task_a = Path(self._tmp_a.name).resolve()
        self.task_b = Path(self._tmp_b.name).resolve()

    def tearDown(self) -> None:
        self._tmp_a.cleanup()
        self._tmp_b.cleanup()

    def _write_user_script(self, task_dir: Path) -> Path:
        """User code prints every AF_TASK_* it sees; if any is missing it
        prints ``<missing>`` for that key so the parent can detect leaks
        or holes."""
        script = task_dir / "user_script.py"
        script.write_text(
            "import os\n"
            "for key in (\n"
            "    'AF_TASK_WORKSPACE',\n"
            "    'AF_TASK_ARTIFACT_DIR',\n"
            "    'AF_TASK_TMP_DIR',\n"
            "    'AF_TASK_METRICS_PATH',\n"
            "):\n"
            "    print(f'{key}=' + os.environ.get(key, '<missing>'))\n",
            encoding="utf-8",
        )
        return script

    def test_each_task_sees_only_its_own_af_task_env(self):
        """Task A and Task B run in the same host interpreter (mirroring a
        single sandbox container). Each gets its OWN taskEnvironment via
        wrapper-input.json. The user child of A must report A's variables;
        the user child of B must report B's variables. No leak in either
        direction."""
        script_a = self._write_user_script(self.task_a)
        script_b = self._write_user_script(self.task_b)

        input_a = self.task_a / "wrapper-input.json"
        input_b = self.task_b / "wrapper-input.json"
        _write_wrapper_input(
            input_a,
            script_path=script_a,
            task_workspace=str(self.task_a),
            task_env=_make_task_env(str(self.task_a)),
        )
        _write_wrapper_input(
            input_b,
            script_path=script_b,
            task_workspace=str(self.task_b),
            task_env=_make_task_env(str(self.task_b)),
        )

        # Run task A first, then task B in the SAME interpreter session is
        # not directly controllable from the host; but each wrapper run is
        # itself a fresh subprocess, so the relevant invariant is: the
        # wrapper process inherits NOTHING from the previous task because
        # AF_TASK_* is set ONLY on the user child's env via Popen(env=...),
        # never on the wrapper's own env. We verify by inspecting stdout.
        completed_a = _run_wrapper(input_a)
        completed_b = _run_wrapper(input_b)

        self.assertEqual(
            completed_a.returncode, 0,
            f"wrapper A failed: {completed_a.stderr[:512]!r}",
        )
        self.assertEqual(
            completed_b.returncode, 0,
            f"wrapper B failed: {completed_b.stderr[:512]!r}",
        )

        envelope_a = json.loads(completed_a.stdout.decode("utf-8"))
        envelope_b = json.loads(completed_b.stdout.decode("utf-8"))
        # capture_reader writes decoded bytes? No — wrapper-tail emits RAW
        # base64'd bytes in the "files" object. We need to decode them.
        import base64
        stdout_a = base64.b64decode(envelope_a["files"]["stdout.bin"]).decode("utf-8")
        stdout_b = base64.b64decode(envelope_b["files"]["stdout.bin"]).decode("utf-8")

        # Task A sees its own paths, never B's.
        self.assertIn(f"AF_TASK_WORKSPACE={self.task_a}", stdout_a)
        self.assertIn(f"AF_TASK_ARTIFACT_DIR={self.task_a}/artifacts", stdout_a)
        self.assertIn(f"AF_TASK_TMP_DIR={self.task_a}/tmp", stdout_a)
        self.assertIn(
            f"AF_TASK_METRICS_PATH={self.task_a}/metrics/loader.jsonl", stdout_a
        )
        self.assertNotIn(str(self.task_b), stdout_a)

        # Task B sees its own paths, never A's.
        self.assertIn(f"AF_TASK_WORKSPACE={self.task_b}", stdout_b)
        self.assertIn(f"AF_TASK_ARTIFACT_DIR={self.task_b}/artifacts", stdout_b)
        self.assertIn(f"AF_TASK_TMP_DIR={self.task_b}/tmp", stdout_b)
        self.assertIn(
            f"AF_TASK_METRICS_PATH={self.task_b}/metrics/loader.jsonl", stdout_b
        )
        self.assertNotIn(str(self.task_a), stdout_b)

    def test_user_child_inherits_makedirs_and_chdir_from_wrapper(self):
        """D15 §4.2 design: makedirs / chdir / sys.path setup that the old
        sitecustomize used to do is now done by the wrapper pre-spawn. The
        user child sees AF_TASK_ARTIFACT_DIR / AF_TASK_TMP_DIR already
        created and os.getcwd() == task_workspace."""
        script = self.task_a / "user_script.py"
        script.write_text(
            "import os\n"
            "print('cwd=' + os.getcwd())\n"
            "print('artifact_dir_exists=' + str(os.path.isdir("
            "os.environ['AF_TASK_ARTIFACT_DIR'])))\n"
            "print('tmp_dir_exists=' + str(os.path.isdir("
            "os.environ['AF_TASK_TMP_DIR'])))\n",
            encoding="utf-8",
        )
        input_path = self.task_a / "wrapper-input.json"
        _write_wrapper_input(
            input_path,
            script_path=script,
            task_workspace=str(self.task_a),
            task_env=_make_task_env(str(self.task_a)),
        )
        completed = _run_wrapper(input_path)
        self.assertEqual(completed.returncode, 0, repr(completed.stderr[:512]))
        import base64
        envelope = json.loads(completed.stdout.decode("utf-8"))
        stdout = base64.b64decode(envelope["files"]["stdout.bin"]).decode("utf-8")
        self.assertIn(f"cwd={self.task_a}", stdout)
        self.assertIn("artifact_dir_exists=True", stdout)
        self.assertIn("tmp_dir_exists=True", stdout)


class FailClosedOnMissingTaskEnvTest(unittest.TestCase):
    """D15 §4.2.5 / §6 red line 4: missing taskWorkspace or any required
    AF_TASK_* key must fail-closed — the wrapper exits non-zero BEFORE
    spawning the user child AND never writes a global sitecustomize.py as
    a fallback."""

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory(prefix="af-d15-fail-")
        self.task_dir = Path(self._tmp.name).resolve()
        # A sentinel user script; if it ever runs we know the wrapper failed
        # to fail-closed.
        self.script = self.task_dir / "user_script.py"
        self.script.write_text(
            "import os, sys\n"
            "sys.stderr.write('USER_CHILD_RAN\\n')\n"
            "sys.exit(0)\n",
            encoding="utf-8",
        )
        # Sentinel that the user child would create if it ran.
        self.ran_marker = self.task_dir / "child_ran.marker"

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def _write_input(self, payload: dict) -> Path:
        input_path = self.task_dir / "wrapper-input.json"
        input_path.write_text(json.dumps(payload), encoding="utf-8")
        # Rewrite the user script to also drop a marker file we can check.
        self.script.write_text(
            "import os, sys\n"
            f"open({str(self.ran_marker)!r}, 'w').close()\n"
            "sys.exit(0)\n",
            encoding="utf-8",
        )
        return input_path

    def _base_payload(self) -> dict:
        return {
            "scriptPath": str(self.script),
            "timeoutSeconds": 30,
            "effectiveOutputLimits": dict(_DEFAULT_LIMITS),
            "runtimeEnvironmentPath": str(self.task_dir / "runtime-environment.json"),
            "taskWorkspace": str(self.task_dir),
            "taskEnvironment": _make_task_env(str(self.task_dir)),
            "loaderPythonPath": str(self.task_dir),
        }

    def test_missing_task_workspace_fails_closed_without_spawning(self):
        payload = self._base_payload()
        payload.pop("taskWorkspace")
        input_path = self._write_input(payload)
        completed = _run_wrapper(input_path)
        self.assertNotEqual(completed.returncode, 0)
        self.assertIn(b"taskWorkspace", completed.stderr)
        self.assertFalse(
            self.ran_marker.exists(),
            "wrapper spawned the user child despite missing taskWorkspace",
        )

    def test_missing_required_env_key_fails_closed_without_spawning(self):
        for missing_key in (
            "AF_TASK_WORKSPACE",
            "AF_TASK_ARTIFACT_DIR",
            "AF_TASK_TMP_DIR",
            "AF_TASK_METRICS_PATH",
        ):
            with self.subTest(missing_key=missing_key):
                # Fresh marker per subtest.
                if self.ran_marker.exists():
                    self.ran_marker.unlink()
                payload = self._base_payload()
                payload["taskEnvironment"] = dict(payload["taskEnvironment"])
                payload["taskEnvironment"].pop(missing_key)
                input_path = self._write_input(payload)
                completed = _run_wrapper(input_path)
                self.assertNotEqual(completed.returncode, 0)
                self.assertIn(b"taskEnvironment", completed.stderr)
                self.assertFalse(
                    self.ran_marker.exists(),
                    f"wrapper spawned user child despite missing {missing_key}",
                )

    def test_blank_required_env_value_fails_closed(self):
        payload = self._base_payload()
        payload["taskEnvironment"] = dict(payload["taskEnvironment"])
        payload["taskEnvironment"]["AF_TASK_WORKSPACE"] = ""
        input_path = self._write_input(payload)
        completed = _run_wrapper(input_path)
        self.assertNotEqual(completed.returncode, 0)
        self.assertFalse(
            self.ran_marker.exists(),
            "wrapper spawned user child despite blank AF_TASK_WORKSPACE",
        )

    def test_no_global_sitecustomize_written_on_failure(self):
        """D15 §6 red line 4: a fail-closed exit must NOT leave a global
        sitecustomize.py behind as a 'fallback'. The wrapper writes no
        files outside {task_workspace}/capture/."""
        payload = self._base_payload()
        payload.pop("taskWorkspace")
        input_path = self._write_input(payload)
        # Production would have /sandbox as workdir; here we check that the
        # task dir (which is what the wrapper could plausibly touch) does
        # not gain a sitecustomize.py.
        sitecustomize_path = self.task_dir / "sitecustomize.py"
        completed = _run_wrapper(input_path)
        self.assertNotEqual(completed.returncode, 0)
        self.assertFalse(
            sitecustomize_path.exists(),
            "wrapper wrote a global sitecustomize.py as a fallback "
            "(D15 §6 red line 4 violated)",
        )


class SitecustomizeWriteRemovedTest(unittest.TestCase):
    """D15 §6 red line 3: _prepare_task_workspace no longer writes the
    global /sandbox/sitecustomize.py per task."""

    def test_prepare_task_workspace_does_not_copy_sitecustomize(self):
        """Stage a fake session and run _prepare_task_workspace; assert
        that NO copy_to_runtime call targets sitecustomize.py."""
        captured_writes: list[tuple[str, str]] = []

        class _FakeOutput:
            exit_code = 0
            stdout = ""
            stderr = ""

        class _FakeSession:
            container_id = "fake-d15-container"

            def __init__(self) -> None:
                self.copy_to_runtime_calls: list[tuple[str, str]] = []

            def execute_command(self, command: str) -> _FakeOutput:
                return _FakeOutput()

            def copy_to_runtime(self, source: str, dest_path: str) -> None:
                self.copy_to_runtime_calls.append((source, dest_path))
                captured_writes.append((source, dest_path))

        from dataclasses import replace
        from app.config import SandboxConfig

        base = SandboxConfig(
            data_dir=Path("/tmp/nonexistent-data"),
            max_concurrency=1,
            execution_timeout_seconds=5.0,
            memory_limit="512m",
            memswap_limit="512m",
            docker_backend="docker",
            workdir="/sandbox",
            log_level="INFO",
            sandbox_image="alphafrog-sandbox-runtime:latest",
            skip_environment_setup=True,
            preinstalled_libraries=frozenset(),
            container_max_concurrency=1,
            pool_enabled=False,
            pool_min_size=0,
            pool_max_size=1,
            pool_acquire_timeout_seconds=30.0,
            pool_idle_timeout_seconds=None,
            pool_max_container_uses=None,
            workspace_root="/sandbox/runs",
            compat_input_path_enabled=False,
        )
        session = _FakeSession()
        sandbox_runner._prepare_task_workspace(
            session,
            "task-d15-no-sitecustomize",
            base,
            [],  # dataset_id_list
            [],  # files
            paths_dataset_csv=None,
            path_manifest_csv=None,
            copy_loader_modules=False,
        )
        sitecustomize_writes = [
            (src, dest) for src, dest in captured_writes
            if "sitecustomize" in dest
        ]
        self.assertEqual(
            [], sitecustomize_writes,
            "D15 §6 red line 3 violated: _prepare_task_workspace wrote a "
            f"sitecustomize file: {sitecustomize_writes}",
        )

    def test_runner_source_has_no_sitecustomize_write_call(self):
        """AST-level guarantee: no call to a copy_to_runtime helper has a
        destination argument whose value contains 'sitecustomize'. The
        cleanup rm block (which contains the literal as a deletion target)
        is NOT a call and is allowed by D15 §4.2.3."""
        import ast
        runner_path = Path(sandbox_runner.__file__)
        tree = ast.parse(runner_path.read_text(encoding="utf-8"))
        forbidden_calls = {
            "_copy_text_to_runtime",
            "_atomic_copy_text_to_runtime",
            "_copy_via_csv_source_paths",
        }
        for node in ast.walk(tree):
            if not isinstance(node, ast.Call):
                continue
            func = node.func
            name = None
            if isinstance(func, ast.Name):
                name = func.id
            elif isinstance(func, ast.Attribute):
                name = func.attr
            if name not in forbidden_calls:
                continue
            # Inspect every argument; if any string-typed argument (literal
            # or f-string/Joined-str) contains 'sitecustomize', fail.
            for arg in node.args:
                text = None
                if isinstance(arg, ast.Constant) and isinstance(arg.value, str):
                    text = arg.value
                elif isinstance(arg, ast.JoinedStr):
                    # Concatenate the literal parts only (ignore FormattedValue).
                    text = "".join(
                        part.value for part in arg.values
                        if isinstance(part, ast.Constant)
                        and isinstance(part.value, str)
                    )
                if text and "sitecustomize" in text:
                    self.fail(
                        f"D15 §6 red line 3: {name}() at line "
                        f"{node.lineno} of sandbox_runner.py targets "
                        f"sitecustomize.py (arg text: {text!r}). "
                        "AF_TASK_* must travel via wrapper-input.json only."
                    )


class StaleSitecustomizeBootstrapIsolationTest(unittest.TestCase):
    """D15 §4.2.3 + §6 red line 3 reverse (codex fe54d9f0 MUST-FIX core bug).

    The round-1 test suite ran every wrapper invocation in a clean temp
    directory: the loader workdir (which doubles as PYTHONPATH under the
    round-1 wiring) contained NO sitecustomize.py, so the site-init
    auto-import race was never exercised. codex fe54d9f0 demonstrated the
    bug independently: if the loader workdir contains a stale
    sitecustomize.py from a previous task (e.g. cleanup failed), Python
    auto-imports it DURING site init, BEFORE the user script runs, and
    that stale sitecustomize can overwrite AF_TASK_* back to the previous
    task's values.

    The round-2 fix (loader bootstrap mode) makes the loader workdir
    enter sys.path only AFTER site init finishes, by staging a per-task
    ``loader_bootstrap.py`` under ``{task_workspace}/_bootstrap/`` and
    running the user script THROUGH that bootstrap via runpy. The stale
    sitecustomize is therefore never auto-imported.

    This test class exercises the reverse scenario the round-1 suite
    missed: pre-place a stale sitecustomize.py in the loader workdir that
    attempts to overwrite AF_TASK_* + drop a sentinel file, then verify
    the user child still sees TASK B's values and the sentinel is NEVER
    created.
    """

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory(prefix="af-d15-stale-")
        self.root = Path(self._tmp.name).resolve()
        # The "stale loader" path simulates a CONTAINER-GLOBAL loader
        # workdir (production: /sandbox) that still hosts a previous task's
        # sitecustomize.py because cleanup failed. The wrapper must NOT
        # auto-import this file at child startup.
        self.stale_loader_path = self.root / "stale_loader"
        self.stale_loader_path.mkdir()
        # Sentinel the stale sitecustomize would create if it ran. Use a
        # path UNDER self.root so the test never touches /tmp directly
        # (keeps the suite hermetic and CI-friendly).
        self.sentinel = self.root / "STALE_A_sentinel"
        # Build the stale sitecustomize body by plain string formatting.
        # All AF_TASK_* values point at clearly-named STALE_A_* paths that
        # the assertions can look for in the user child's stdout; the
        # sentinel write proves whether the stale sitecustomize ran.
        stale_workspace = str(self.root / "STALE_A_workspace")
        stale_artifacts = str(self.root / "STALE_A_artifacts")
        stale_tmp = str(self.root / "STALE_A_tmp")
        stale_metrics = str(self.root / "STALE_A_metrics.jsonl")
        stale_body = (
            "import os\n"
            f"os.environ['AF_TASK_WORKSPACE'] = {stale_workspace!r}\n"
            f"os.environ['AF_TASK_ARTIFACT_DIR'] = {stale_artifacts!r}\n"
            f"os.environ['AF_TASK_TMP_DIR'] = {stale_tmp!r}\n"
            f"os.environ['AF_TASK_METRICS_PATH'] = {stale_metrics!r}\n"
            f"with open({str(self.sentinel)!r}, 'w') as _f:\n"
            "    _f.write('stale sitecustomize executed')\n"
        )
        (self.stale_loader_path / "sitecustomize.py").write_text(
            stale_body, encoding="utf-8",
        )
        # Clean any pre-existing sentinel from a prior run.
        if self.sentinel.exists():
            self.sentinel.unlink()

        # Fresh task_workspace for "task B" — its AF_TASK_* must win.
        self.task_b_workspace = self.root / "task_B_ws"
        self.task_b_workspace.mkdir()
        (self.task_b_workspace / "artifacts").mkdir()
        (self.task_b_workspace / "tmp").mkdir()
        (self.task_b_workspace / "metrics").mkdir()

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def _write_user_script(self) -> Path:
        """User script prints every AF_TASK_* it sees; if any is missing it
        prints ``<missing>`` so the parent can detect leaks or holes."""
        script = self.task_b_workspace / "user_script.py"
        script.write_text(
            "import os\n"
            "for key in (\n"
            "    'AF_TASK_WORKSPACE',\n"
            "    'AF_TASK_ARTIFACT_DIR',\n"
            "    'AF_TASK_TMP_DIR',\n"
            "    'AF_TASK_METRICS_PATH',\n"
            "):\n"
            "    print(f'{key}=' + os.environ.get(key, '<missing>'))\n",
            encoding="utf-8",
        )
        return script

    def test_stale_global_sitecustomize_does_not_override_task_env(self):
        """The headline reverse test (codex fe54d9f0 core bug).

        Pre-conditions:
          * A stale sitecustomize.py lives in the loader workdir that tries
            to overwrite all four AF_TASK_* vars to STALE_A values AND
            drops a sentinel file at a known path.
          * Task B's wrapper-input.json carries the correct TASK B values
            and points loaderPythonPath at the stale loader workdir.

        Expected (after round-2 fix):
          * The user child prints TASK B's AF_TASK_WORKSPACE / ARTIFACT_DIR.
          * The user child does NOT print any STALE_A value.
          * The sentinel file is NEVER created — the stale sitecustomize
            was never auto-imported at Python startup.
          * Wrapper exit code is 0.
        """
        script = self._write_user_script()
        input_path = self.task_b_workspace / "wrapper-input.json"
        _write_wrapper_input(
            input_path,
            script_path=script,
            task_workspace=str(self.task_b_workspace),
            task_env=_make_task_env(str(self.task_b_workspace)),
            loader_python_path=str(self.stale_loader_path),
        )

        completed = _run_wrapper(input_path)
        self.assertEqual(
            completed.returncode, 0,
            f"wrapper failed: stderr={completed.stderr[:1024]!r}",
        )

        import base64
        envelope = json.loads(completed.stdout.decode("utf-8"))
        stdout = base64.b64decode(envelope["files"]["stdout.bin"]).decode("utf-8")

        # Task B's AF_TASK_WORKSPACE / ARTIFACT_DIR must be visible.
        self.assertIn(
            f"AF_TASK_WORKSPACE={self.task_b_workspace}", stdout,
            "task B workspace not visible to user child",
        )
        self.assertIn(
            f"AF_TASK_ARTIFACT_DIR={self.task_b_workspace}/artifacts", stdout,
            "task B artifact dir not visible to user child",
        )

        # No STALE_A value may leak through.
        self.assertNotIn(
            "STALE_A_workspace", stdout,
            "stale sitecustomize overrode AF_TASK_WORKSPACE — bootstrap "
            "did not prevent site-init auto-import of the loader workdir's "
            "sitecustomize.py (D15 §4.2.3 round-2 core bug NOT fixed)",
        )
        self.assertNotIn(
            "STALE_A_artifacts", stdout,
            "stale sitecustomize overrode AF_TASK_ARTIFACT_DIR",
        )
        self.assertNotIn(
            "STALE_A_tmp", stdout,
            "stale sitecustomize overrode AF_TASK_TMP_DIR",
        )
        self.assertNotIn(
            "STALE_A_metrics", stdout,
            "stale sitecustomize overrode AF_TASK_METRICS_PATH",
        )

        # The sentinel file must NOT exist — the stale sitecustomize must
        # never have run. This is the most direct proof that the bootstrap
        # blocked the auto-import path.
        self.assertFalse(
            self.sentinel.exists(),
            "stale sitecustomize.py ran during site init and dropped its "
            "sentinel file — the loader bootstrap mode did NOT prevent "
            "auto-import (D15 §4.2.3 round-2 core bug NOT fixed)",
        )

    def test_loader_bootstrap_is_actually_staged_under_task_workspace(self):
        """Round-2 contract: the wrapper writes loader_bootstrap.py to
        {task_workspace}/_bootstrap/loader_bootstrap.py before Popen. After
        a successful run that file must exist (proving the new path is in
        use, not the round-1 direct-invocation path)."""
        script = self._write_user_script()
        input_path = self.task_b_workspace / "wrapper-input.json"
        _write_wrapper_input(
            input_path,
            script_path=script,
            task_workspace=str(self.task_b_workspace),
            task_env=_make_task_env(str(self.task_b_workspace)),
            loader_python_path=str(self.stale_loader_path),
        )
        completed = _run_wrapper(input_path)
        self.assertEqual(completed.returncode, 0, repr(completed.stderr[:512]))

        bootstrap_path = (
            self.task_b_workspace / "_bootstrap" / "loader_bootstrap.py"
        )
        self.assertTrue(
            bootstrap_path.is_file(),
            "loader_bootstrap.py was not staged under "
            "{task_workspace}/_bootstrap/ — round-2 bootstrap path is "
            "not in use",
        )
        body = bootstrap_path.read_text(encoding="utf-8")
        # The bootstrap must insert the loader path into sys.path AFTER
        # site init (i.e. contain a sys.path.insert call) and run the user
        # script via runpy.run_path. Pinning these textually makes future
        # regressions visible even if the file is silently re-located.
        self.assertIn("sys.path.insert(0, LOADER_PATH)", body)
        self.assertIn('runpy.run_path(USER_SCRIPT, run_name="__main__")', body)
        self.assertIn(str(self.stale_loader_path), body)


class LoaderRequiredAndConsistencyInvariantsTest(unittest.TestCase):
    """D15 §4.2.3 round-2 (codex fe54d9f0 MUST-FIX #1 + #2): loaderPythonPath
    is now REQUIRED, and taskWorkspace must be self-consistent with the four
    AF_TASK_* vars (each at-or-inside taskWorkspace, AF_TASK_WORKSPACE
    exactly equal). A violating payload fails closed BEFORE the user child
    spawns."""

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory(prefix="af-d15-inv-")
        self.task_dir = Path(self._tmp.name).resolve()
        self.script = self.task_dir / "user_script.py"
        self.script.write_text(
            "import sys\n"
            "sys.stderr.write('USER_CHILD_RAN\\n')\n"
            "sys.exit(0)\n",
            encoding="utf-8",
        )
        # Marker the user child would create if it ran.
        self.ran_marker = self.task_dir / "child_ran.marker"

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def _base_payload(self) -> dict:
        return {
            "scriptPath": str(self.script),
            "timeoutSeconds": 30,
            "effectiveOutputLimits": dict(_DEFAULT_LIMITS),
            "runtimeEnvironmentPath": str(self.task_dir / "runtime-environment.json"),
            "taskWorkspace": str(self.task_dir),
            "taskEnvironment": _make_task_env(str(self.task_dir)),
            "loaderPythonPath": str(self.task_dir),
        }

    def _write_input(self, payload: dict) -> Path:
        input_path = self.task_dir / "wrapper-input.json"
        input_path.write_text(json.dumps(payload), encoding="utf-8")
        if self.ran_marker.exists():
            self.ran_marker.unlink()
        # Rewrite the user script to drop a marker we can check.
        self.script.write_text(
            f"open({str(self.ran_marker)!r}, 'w').close()\n"
            "import sys\n"
            "sys.exit(0)\n",
            encoding="utf-8",
        )
        (self.task_dir / "runtime-environment.json").write_text(
            "{}", encoding="utf-8"
        )
        return input_path

    def test_missing_loader_python_path_fails_closed(self):
        """loaderPythonPath is required — omitting it must fail closed."""
        payload = self._base_payload()
        payload.pop("loaderPythonPath")
        input_path = self._write_input(payload)
        completed = _run_wrapper(input_path)
        self.assertNotEqual(completed.returncode, 0)
        self.assertIn(b"loaderPythonPath", completed.stderr)
        self.assertFalse(
            self.ran_marker.exists(),
            "wrapper spawned user child despite missing loaderPythonPath",
        )

    def test_empty_loader_python_path_fails_closed(self):
        """loaderPythonPath must be non-empty — blank must fail closed."""
        payload = self._base_payload()
        payload["loaderPythonPath"] = ""
        input_path = self._write_input(payload)
        completed = _run_wrapper(input_path)
        self.assertNotEqual(completed.returncode, 0)
        self.assertFalse(
            self.ran_marker.exists(),
            "wrapper spawned user child despite empty loaderPythonPath",
        )

    def test_af_task_workspace_mismatch_fails_closed(self):
        """taskWorkspace and AF_TASK_WORKSPACE disagree -> fail closed.

        Without this invariant the wrapper could be fooled into a payload
        whose cwd is task B but whose env points at task A. codex
        fe54d9f0 MUST-FIX #2.
        """
        payload = self._base_payload()
        # Make AF_TASK_WORKSPACE point somewhere else (still absolute, still
        # a valid-looking path, but NOT taskWorkspace).
        payload["taskEnvironment"] = dict(payload["taskEnvironment"])
        payload["taskEnvironment"]["AF_TASK_WORKSPACE"] = str(
            self.task_dir / "evil_twin"
        )
        input_path = self._write_input(payload)
        completed = _run_wrapper(input_path)
        self.assertNotEqual(completed.returncode, 0)
        self.assertFalse(
            self.ran_marker.exists(),
            "wrapper spawned user child despite taskWorkspace / "
            "AF_TASK_WORKSPACE mismatch",
        )

    def test_af_task_artifact_dir_outside_workspace_fails_closed(self):
        """AF_TASK_ARTIFACT_DIR resolves outside taskWorkspace -> fail closed.

        A relative '..' escape or an absolute path that does not live
        beneath taskWorkspace must be rejected, otherwise a payload could
        smuggle artifact writes outside the task-local workspace.
        """
        payload = self._base_payload()
        payload["taskEnvironment"] = dict(payload["taskEnvironment"])
        # Absolute path that does NOT live beneath task_dir.
        payload["taskEnvironment"]["AF_TASK_ARTIFACT_DIR"] = "/etc"
        input_path = self._write_input(payload)
        completed = _run_wrapper(input_path)
        self.assertNotEqual(completed.returncode, 0)
        self.assertFalse(
            self.ran_marker.exists(),
            "wrapper spawned user child despite AF_TASK_ARTIFACT_DIR "
            "pointing outside taskWorkspace",
        )

    def test_af_task_tmp_dir_dotdot_escape_fails_closed(self):
        """AF_TASK_TMP_DIR uses '..' to escape taskWorkspace -> fail closed."""
        payload = self._base_payload()
        payload["taskEnvironment"] = dict(payload["taskEnvironment"])
        # '..' escape: resolves outside task_dir.
        payload["taskEnvironment"]["AF_TASK_TMP_DIR"] = (
            str(self.task_dir) + "/../escape"
        )
        input_path = self._write_input(payload)
        completed = _run_wrapper(input_path)
        self.assertNotEqual(completed.returncode, 0)
        self.assertFalse(
            self.ran_marker.exists(),
            "wrapper spawned user child despite AF_TASK_TMP_DIR using "
            "'..' to escape taskWorkspace",
        )

    def test_af_task_metrics_path_outside_workspace_fails_closed(self):
        """AF_TASK_METRICS_PATH resolves outside taskWorkspace -> fail closed."""
        payload = self._base_payload()
        payload["taskEnvironment"] = dict(payload["taskEnvironment"])
        payload["taskEnvironment"]["AF_TASK_METRICS_PATH"] = "/tmp/elsewhere.json"
        input_path = self._write_input(payload)
        completed = _run_wrapper(input_path)
        self.assertNotEqual(completed.returncode, 0)
        self.assertFalse(
            self.ran_marker.exists(),
            "wrapper spawned user child despite AF_TASK_METRICS_PATH "
            "pointing outside taskWorkspace",
        )


class BootstrapPermissionAndRunpySemanticsTest(unittest.TestCase):
    """D15 §4.2.3 round-3 (codex c9fee2f9 MUST-FIX #1 + #2).

    Round-2 (d0c67439) closed the stale-sitecustomize core bug but missed
    two production realities that host tests (same-UID) couldn't surface:

    1. **Permission gap**: sandbox_runner chowns task_workspace to the
       unprivileged child uid BEFORE the root wrapper creates the
       ``_bootstrap`` dir + bootstrap file. Round-2 set the dir to 0o700
       (root-only). Production child (non-root after preexec_fn) couldn't
       traverse the root-owned 0o700 dir to read the bootstrap → child
       startup fails immediately. Round-3 fix: dir 0o755 (world-traversable)
       + file 0o444 (world-readable, root-writable via unlink+write).

    2. **Sibling import gap**: round-2 bootstrap inserted LOADER_PATH at
       ``sys.path[0]`` but never inserted user script's parent dir.
       Direct ``python user_script.py`` puts script parent at
       ``sys.path[0]`` enabling sibling imports like
       ``import sibling_module``. runpy.run_path does NOT do this
       automatically. Round-3 fix: bootstrap inserts user_script_dir at
       ``sys.path[0]`` AFTER inserting LOADER_PATH, so final order is
       ``[user_script_dir, LOADER_PATH, ...]`` — siblings win over
       loader modules of same name (matches direct python priority).
    """

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory(prefix="af-d15-r3-")
        self.root = Path(self._tmp.name).resolve()
        self.task_workspace = self.root / "task_ws"
        self.task_workspace.mkdir()
        (self.task_workspace / "artifacts").mkdir()
        (self.task_workspace / "tmp").mkdir()
        (self.task_workspace / "metrics").mkdir()
        # Loader path is a separate dir holding a (real) af_dataset_loader
        # stub for some tests; for the permission/sibling tests below it
        # can be empty.
        self.loader_path = self.root / "loader"
        self.loader_path.mkdir()

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def _write_input(self, script_path: Path) -> Path:
        input_path = self.task_workspace / "wrapper-input.json"
        _write_wrapper_input(
            input_path,
            script_path=script_path,
            task_workspace=str(self.task_workspace),
            task_env=_make_task_env(str(self.task_workspace)),
            loader_python_path=str(self.loader_path),
        )
        return input_path

    @staticmethod
    def _decode_stdout(completed: subprocess.CompletedProcess) -> str:
        import base64
        envelope = json.loads(completed.stdout.decode("utf-8"))
        return base64.b64decode(envelope["files"]["stdout.bin"]).decode("utf-8")

    def test_bootstrap_dir_is_world_traversable_and_file_is_world_readable(self):
        """Round-3 MUST-FIX #1: bootstrap dir MUST be 0o755 + file 0o444 so
        the production non-root child can read it after preexec_fn drops
        privileges. Round-2 used 0o700 which only the root wrapper could
        traverse — production child failed at startup, host tests missed it
        because they run same-UID."""
        script = self.task_workspace / "user_script.py"
        script.write_text("print('ok')\n", encoding="utf-8")
        input_path = self._write_input(script)

        completed = _run_wrapper(input_path)
        self.assertEqual(
            completed.returncode, 0,
            f"wrapper failed: stderr={completed.stderr[:1024]!r}",
        )

        bootstrap_dir = self.task_workspace / "_bootstrap"
        bootstrap_file = bootstrap_dir / "loader_bootstrap.py"
        self.assertTrue(bootstrap_file.is_file(), "bootstrap file not staged")

        dir_mode = stat.S_IMODE(bootstrap_dir.stat().st_mode)
        file_mode = stat.S_IMODE(bootstrap_file.stat().st_mode)

        self.assertEqual(
            dir_mode & 0o755, 0o755,
            f"bootstrap dir MUST be at least 0o755 (world-traversable) so the "
            f"non-root child can cd into it; got 0o{dir_mode:o}",
        )
        # Owner-write MUST be off so the user child cannot modify the
        # bootstrap even if same-UID (defense in depth).
        self.assertEqual(
            dir_mode & stat.S_IWGRP, 0,
            "bootstrap dir MUST NOT be group-writable (the unprivileged "
            f"child could then rename/replace the bootstrap); got 0o{dir_mode:o}",
        )
        self.assertEqual(
            dir_mode & stat.S_IWOTH, 0,
            "bootstrap dir MUST NOT be world-writable; "
            f"got 0o{dir_mode:o}",
        )

        self.assertEqual(
            file_mode & 0o444, 0o444,
            f"bootstrap file MUST be at least world-readable (0o444) so the "
            f"non-root child can read it; got 0o{file_mode:o}",
        )
        self.assertEqual(
            file_mode & stat.S_IWUSR, 0,
            "bootstrap file MUST NOT be user-writable (defense in depth: "
            f"the unprivileged child must not modify the bootstrap); "
            f"got 0o{file_mode:o}",
        )
        self.assertEqual(
            file_mode & stat.S_IWGRP, 0,
            "bootstrap file MUST NOT be group-writable; "
            f"got 0o{file_mode:o}",
        )
        self.assertEqual(
            file_mode & stat.S_IWOTH, 0,
            "bootstrap file MUST NOT be world-writable; "
            f"got 0o{file_mode:o}",
        )

    def test_user_script_sibling_imports_work_via_bootstrap(self):
        """Round-3 MUST-FIX #2: ``import sibling_module`` from a same-dir
        module MUST work. Round-2 bootstrap inserted LOADER_PATH at
        ``sys.path[0]`` but never inserted user_script's parent, so sibling
        imports failed with ModuleNotFoundError. codex independently
        reproduced this — wrapper rc=0 but child exitCode=1."""
        # Place sibling_module.py NEXT TO user_script.py (not in loader_path,
        # not in task_workspace root) so it's only findable via direct-script
        # sys.path semantics.
        script_dir = self.task_workspace / "scripts"
        script_dir.mkdir()
        sibling = script_dir / "sibling_module.py"
        sibling.write_text(
            "def hello():\n    return 'sibling import ok'\n",
            encoding="utf-8",
        )
        script = script_dir / "user_script.py"
        script.write_text(
            "import sibling_module\n"
            "print(sibling_module.hello())\n",
            encoding="utf-8",
        )
        input_path = self._write_input(script)

        completed = _run_wrapper(input_path)
        # Wrapper exit code MUST be 0. Round-2 broken behavior was wrapper
        # rc=0 but child exitCode=1; with the envelope we need to decode
        # the child's exit code separately.
        self.assertEqual(
            completed.returncode, 0,
            f"wrapper failed: stderr={completed.stderr[:1024]!r}",
        )
        stdout = self._decode_stdout(completed)
        self.assertIn(
            "sibling import ok", stdout,
            "user script could not import sibling_module from same dir — "
            "round-2 bootstrap's sys.path semantics broke direct-script "
            "sibling imports (codex c9fee2f9 MUST-FIX #2 NOT fixed). "
            f"stdout={stdout!r}",
        )

    def test_user_script_sees_main_name_correct_file_and_argv(self):
        """Round-3 invariant: ``runpy.run_path(..., run_name='__main__')``
        MUST preserve direct-script semantics for ``__name__``,
        ``__file__`` and ``sys.argv``. If a future bootstrap change breaks
        this, user scripts that use ``if __name__ == '__main__':`` idiom
        would silently no-op."""
        script = self.task_workspace / "user_script.py"
        script.write_text(
            "import sys\n"
            "print('__name__=' + __name__)\n"
            "print('__file__=' + __file__)\n"
            "print('argv0=' + sys.argv[0])\n",
            encoding="utf-8",
        )
        input_path = self._write_input(script)

        completed = _run_wrapper(input_path)
        self.assertEqual(
            completed.returncode, 0,
            f"wrapper failed: stderr={completed.stderr[:1024]!r}",
        )
        stdout = self._decode_stdout(completed)

        self.assertIn(
            "__name__=__main__", stdout,
            f"user script __name__ MUST be '__main__' (preserves "
            f"'if __name__ == \"__main__\"' idiom); stdout={stdout!r}",
        )
        # __file__ should resolve to the user script path. runpy.run_path
        # sets __file__ to whatever path was passed in — wrapper passes
        # script_path as-is from wrapper-input.json.
        script_path_str = str(script)
        self.assertIn(
            f"__file__={script_path_str}", stdout,
            f"user script __file__ MUST resolve to the script path; "
            f"stdout={stdout!r}",
        )
        self.assertIn(
            f"argv0={script_path_str}", stdout,
            f"sys.argv[0] MUST be the user script path (direct-script "
            f"semantics); stdout={stdout!r}",
        )

    def test_user_script_system_exit_propagates_through_bootstrap(self):
        """Round-3 invariant: ``SystemExit(N)`` raised by user script MUST
        propagate through ``runpy.run_path`` and the bootstrap so the
        wrapper observes a non-zero exit. This is the standard Python
        behavior for direct ``python user_script.py``; the bootstrap mode
        MUST NOT swallow it."""
        import base64
        script = self.task_workspace / "user_script.py"
        script.write_text(
            "print('about to exit')\n"
            "raise SystemExit(7)\n",
            encoding="utf-8",
        )
        input_path = self._write_input(script)

        completed = _run_wrapper(input_path)
        # Wrapper itself completes (rc=0 means wrapper ran fine); the user
        # child's SystemExit(7) is captured in the envelope's
        # capture-result.json file (base64-encoded JSON), not the wrapper's
        # returncode.
        self.assertEqual(
            completed.returncode, 0,
            f"wrapper itself failed (not the user script's SystemExit): "
            f"stderr={completed.stderr[:1024]!r}",
        )
        envelope = json.loads(completed.stdout.decode("utf-8"))
        capture_result_b64 = envelope["files"]["capture-result.json"]
        capture_result = json.loads(
            base64.b64decode(capture_result_b64).decode("utf-8"),
        )
        # The capture-result.json's exitCode field carries the user child's
        # process exit code. SystemExit(7) → child exits with code 7.
        child_exit = capture_result.get("exitCode")
        self.assertEqual(
            child_exit, 7,
            f"user child's SystemExit(7) MUST propagate as exit code 7 "
            f"through runpy.run_path + bootstrap; got {child_exit!r}. "
            f"capture-result={capture_result!r}",
        )


if __name__ == "__main__":
    unittest.main()
