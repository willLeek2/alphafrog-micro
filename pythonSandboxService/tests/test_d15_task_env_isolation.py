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

class SitecustomizeWriteRemovedTest(unittest.TestCase):
    """D15 §6 red line 3: _prepare_task_workspace no longer writes the
    global /sandbox/sitecustomize.py per task."""

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

class BootstrapPermissionAndRunpySemanticsTest(unittest.TestCase):
    """D15 §4.2.3 round-3 (codex c9fee2f9 MUST-FIX #1 + #2).

    Round-2 (d0c67439) closed the stale-sitecustomize core bug but missed
    two production realities that host tests (same-UID) couldn't surface:

    1. **Permission gap** (historical round-3 record): round-2 set the
       ``_bootstrap`` dir to 0o700, which the then-unprivileged child
       couldn't traverse. Round-3 fix: dir 0o755 + file 0o444.  The
       privilege-drop machinery is gone since the 260818 non-root
       simplification (container created AS the unprivileged user); the
       modes are kept as-is.

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

class PayloadContractRound4Test(unittest.TestCase):
    """D15 §4.2.3 round-4 (codex 56976668 MUST-FIX #1 + #2 + #3).

    Round-3 closed bootstrap permission + sibling import but left three
    security gaps that host tests (same-UID, fresh temp dir) could not
    surface:

    1. **taskEnvironment had no whitelist** — caller could smuggle
       ``PYTHONPATH``/``PYTHONHOME``/``PYTHONSTARTUP`` into the user
       child's env, re-activating the stale-sitecustomize attack vector
       the round-2 bootstrap mode just closed. codex 56976668 MUST-FIX #1.
    2. **containment anchored on an unverified base** — taskWorkspace
       had no anchor to wrapper-input.json's parent dir, so ``/``,
       ``..``, parent dir, or a symlink to an external target could all
       pass as "the workspace"; scriptPath/loaderPythonPath/_bootstrap
       had no type/existence check; a pre-planted ``_bootstrap`` symlink
       could let the root wrapper chmod/write an attacker-chosen external
       target. codex 56976668 MUST-FIX #2.
    3. **model vs parser semantics could drift** — required field count
       aligned but the model could still construct objects the runtime
       would reject. codex 56976668 MUST-FIX #3.

    Round-4 closes all three by extracting a single
    ``validate_payload_contract`` function in ``app.payload_contract``;
    both the runtime parser (with wrapper_input_path) and the pydantic
    model (without — no fs context at HTTP validation time) call it.
    The wrapper adds filesystem evidence on top (scriptPath regular file,
    loaderPythonPath existing directory, ``_bootstrap`` symlink rejection).
    """

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory(prefix="af-d15-r4-")
        self.root = Path(self._tmp.name).resolve()
        # The "task workspace" is a subdir of root; the wrapper-input.json
        # is written INSIDE the workspace (matches production runner's
        # {task_workspace}/wrapper-input.json layout) so the workspace
        # anchor check passes for the base payload.
        self.task_workspace = self.root / "task_ws"
        self.task_workspace.mkdir()
        (self.task_workspace / "artifacts").mkdir()
        (self.task_workspace / "tmp").mkdir()
        (self.task_workspace / "metrics").mkdir()
        self.loader_path = self.root / "loader"
        self.loader_path.mkdir()
        self.script = self.task_workspace / "user_script.py"
        self.script.write_text("print('user child ran')\n", encoding="utf-8")
        # Marker the user child would create if it ran.
        self.ran_marker = self.root / "child_ran.marker"

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def _base_env(self) -> dict[str, str]:
        return _make_task_env(str(self.task_workspace))

    def _write_input(
        self,
        *,
        input_path: Path | None = None,
        task_workspace: str | None = None,
        task_env: dict[str, str] | None = None,
        script_path: str | None = None,
        loader_python_path: str | None = None,
    ) -> Path:
        """Stage a wrapper-input.json. Defaults match a valid round-4
        payload; tests override one field at a time to introduce the
        violation under test."""
        if input_path is None:
            input_path = self.task_workspace / "wrapper-input.json"
        if self.ran_marker.exists():
            self.ran_marker.unlink()
        # Rewrite the user script to drop a marker we can check.
        self.script.write_text(
            f"open({str(self.ran_marker)!r}, 'w').close()\n"
            "import sys\n"
            "sys.exit(0)\n",
            encoding="utf-8",
        )
        _write_wrapper_input(
            input_path,
            script_path=Path(script_path) if script_path else self.script,
            task_workspace=task_workspace or str(self.task_workspace),
            task_env=task_env if task_env is not None else self._base_env(),
            loader_python_path=loader_python_path or str(self.loader_path),
        )
        return input_path

    # === codex 56976668 MUST-FIX #1: taskEnvironment whitelist ===

    # === codex 56976668 MUST-FIX #2: containment anchor ===

    # === codex 56976668 MUST-FIX #2: scriptPath type + containment ===

    # === codex 56976668 MUST-FIX #2: loaderPythonPath type ===

    # === codex 56976668 MUST-FIX #2: AF sub-path boundary ===

    # === codex 56976668 MUST-FIX #2: _bootstrap symlink rejection ===

    # === codex 56976668 MUST-FIX #3: model/parser contract alignment ===

if __name__ == "__main__":
    unittest.main()
