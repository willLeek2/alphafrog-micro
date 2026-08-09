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


if __name__ == "__main__":
    unittest.main()
