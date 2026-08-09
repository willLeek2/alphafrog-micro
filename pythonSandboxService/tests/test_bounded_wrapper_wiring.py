# === work-package-C (ccqwen) ===
"""Production wiring tests for the §7.1 bounded execution wrapper (codex
c72db8f6 item 1): create_task snapshot freeze -> runner wrapper path ->
container-side capture -> readback -> §5.1 channel.

Wrapper-tail model (P0 fix): the trusted wrapper imports ``capture_reader``
BEFORE spawning the user child and, after the child exits, performs the
bounded readback IN MEMORY and emits EXACTLY ONE envelope JSON document on
its own stdout; the host parses the envelope out of that SAME wrapper run
and NEVER executes anything from the user-writable task workspace after
user code exits.  The retired reader CLI (wrong-arg-count / non-integer
exit-2 contracts) is therefore no longer tested here; the read discipline
(symlink/FIFO/oversize/joint/line-count/envelope-ceiling) is pinned against
the module API directly, plus wrapper-level stdout-discipline and
reader-overwrite regression tests.

The functional tier drives ``run_in_open_session`` END TO END with the REAL
staged wrapper running as a host subprocess behind a host-backed
FakeSession: container paths are literal host paths under a per-test root,
``copy_to_runtime`` copies bytes, and ``execute_command`` runs the exact
``sh -lc`` scripts production builds.  A PATH shim makes ``python`` resolve
to ``sys.executable`` (production containers have it on PATH; hosts may
not).  No Docker required.

Run from pythonSandboxService/:

    python3 -m unittest tests.test_bounded_wrapper_wiring -v

(app.sandbox_runner transitively imports pydantic via app.models, so this
suite needs the service requirements like the other runner/main suites.)
"""
from __future__ import annotations

import base64
import hashlib
import json
import os
import shutil
import stat
import subprocess
import sys
import tempfile
import types
import unittest
from dataclasses import replace
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

# Host-runnable llm_sandbox stub (tests never touch Docker here).
llm_sandbox = types.ModuleType("llm_sandbox")
llm_sandbox.SandboxSession = object
llm_sandbox_exceptions = types.ModuleType("llm_sandbox.exceptions")
llm_sandbox_exceptions.SandboxTimeoutError = TimeoutError
sys.modules.setdefault("llm_sandbox", llm_sandbox)
sys.modules.setdefault("llm_sandbox.exceptions", llm_sandbox_exceptions)

_SERVICE_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _SERVICE_ROOT not in sys.path:
    sys.path.insert(0, _SERVICE_ROOT)

from app import sandbox_runner  # noqa: E402
from app.bounded_exec_wrapper import (  # noqa: E402
    CAPTURE_RESULT_FILE_NAME,
    RECORDS_FILE_NAME,
    STDERR_FILE_NAME,
    STDOUT_FILE_NAME,
    UNKNOWN_MARKER_AUDIT_FILE_NAME,
)
from app.capture_reader import (  # noqa: E402
    CAPTURE_FILE_NAMES,
    CAPTURE_SUMMARY_MAX_BYTES,
    _envelope_ceiling,
    read_capture_files,
)
from app.config import SandboxConfig  # noqa: E402
from app.models import BoundedExecRequest, EffectiveOutputLimits  # noqa: E402
from app.output_capture import MARKER_V1_PREFIX, record_batch_digest  # noqa: E402
from app.sandbox_runner import (  # noqa: E402
    _read_capture_from_container,
    _resolve_wrapper_interpreter,
    _run_bounded_wrapper_path,
    _stage_bounded_wrapper,
    run_in_open_session,
    validate_effective_output_limits,
)

_LIMIT_KEYS = (
    "stdoutMaxBytes",
    "stderrMaxBytes",
    "recordChannelMaxBytes",
    "recordChannelMaxRecords",
)
_LIMITS = {
    "stdoutMaxBytes": 1048576,
    "stderrMaxBytes": 262144,
    "recordChannelMaxBytes": 262144,
    "recordChannelMaxRecords": 128,
    "sourceRevision": "static-default",
}
_RECORD_PAYLOAD = '{"schemaVersion":"1","methodId":"cagr","value":0.123}'


def _test_config(root: Path, *, skip_environment_setup: bool) -> SandboxConfig:
    return SandboxConfig(
        data_dir=root / "data",
        max_concurrency=1,
        execution_timeout_seconds=30.0,
        memory_limit="512m",
        memswap_limit="512m",
        docker_backend="docker",
        workdir=f"{root}/sandbox",
        log_level="INFO",
        sandbox_image="alphafrog-sandbox-runtime:latest",
        skip_environment_setup=skip_environment_setup,
        preinstalled_libraries=frozenset(),
        container_max_concurrency=1,
        pool_enabled=False,
        pool_min_size=0,
        pool_max_size=1,
        pool_acquire_timeout_seconds=30.0,
        pool_idle_timeout_seconds=None,
        pool_max_container_uses=None,
        workspace_root=f"{root}/sandbox/runs",
        compat_input_path_enabled=True,
    )


class FakeContainerSession:
    """Host-backed stand-in for llm_sandbox.SandboxSession.

    Container paths are literal host paths under the test root; the staged
    wrapper and capture reader therefore execute EXACTLY as they would in a
    container (same ``sh -lc`` scripts, same file layout).
    """

    using_existing_container = False
    container_id = "fake-container-wiring"

    def __init__(self, root: Path, *, skip_environment_setup: bool) -> None:
        self.root = Path(root)
        self.installed_libraries: list = []
        self.executed_commands: list = []
        # PATH shim: production containers have `python`; hosts may not.
        # The shim is a tiny exec SCRIPT rather than a symlink: CPython
        # resolves pyvenv.cfg next to the executed file, so a bare symlink
        # to the venv python would drop the venv site-packages (pandas...).
        self.bin_dir = self.root / "bin"
        self.bin_dir.mkdir(parents=True, exist_ok=True)
        self._write_python_shim(self.bin_dir / "python")
        self.skip_environment_setup = skip_environment_setup
        self.python_executable_path = (
            f"{self.root}/sandbox/.sandbox-venv/bin/python"
        )
        if not skip_environment_setup:
            venv_python = Path(self.python_executable_path)
            venv_python.parent.mkdir(parents=True, exist_ok=True)
            self._write_python_shim(venv_python)

    @staticmethod
    def _write_python_shim(path: Path) -> None:
        path.write_text(
            f"#!/bin/sh\nexec {sys.executable} \"$@\"\n", encoding="utf-8"
        )
        path.chmod(0o755)

    def copy_to_runtime(self, source: str, dest_path: str) -> None:
        dest = Path(dest_path)
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, dest)

    def execute_command(self, command: str, workdir=None):
        self.executed_commands.append(command)
        env = dict(os.environ)
        env["PATH"] = f"{self.bin_dir}{os.pathsep}{env.get('PATH', '')}"
        completed = subprocess.run(
            command,
            shell=True,
            capture_output=True,
            text=True,
            env=env,
            timeout=90,
        )
        return SimpleNamespace(
            exit_code=completed.returncode,
            stdout=completed.stdout,
            stderr=completed.stderr,
        )

    def install(self, libraries) -> None:
        if self.skip_environment_setup:
            raise RuntimeError(
                "library installation unsupported with skip_environment_setup"
            )
        self.installed_libraries.extend(libraries)

    def run(self, code, libraries=None, timeout=None):
        raise AssertionError(
            "legacy session.run must not be called on the wrapper path"
        )

    def close(self) -> None:
        pass


class RecordingFakeContainerSession(FakeContainerSession):
    """FakeContainerSession that also records the wrapper-run outputs.

    The wrapper-tail envelope rides the wrapper run's OWN stdout; tests that
    pin the stdout discipline (PIN 2) or the no-flood regression need the raw
    wrapper-run output, so every ``run_wrapper.py`` execution is captured.
    """

    def __init__(self, root: Path, *, skip_environment_setup: bool) -> None:
        super().__init__(root, skip_environment_setup=skip_environment_setup)
        self.wrapper_outputs: list = []

    def execute_command(self, command: str, workdir=None):
        output = super().execute_command(command, workdir)
        if sandbox_runner.WRAPPER_BOOTSTRAP_NAME in command:
            self.wrapper_outputs.append(output)
        return output


def _make_dataset(root: Path) -> None:
    dataset_dir = root / "data" / "ds1"
    dataset_dir.mkdir(parents=True, exist_ok=True)
    (dataset_dir / "ds1.csv").write_text("x\n1\n", encoding="utf-8")


def _user_code_happy() -> str:
    return (
        "import os\n"
        "import sys\n"
        "\n"
        "print('ordinary-line-1')\n"
        f"sys.stdout.write({MARKER_V1_PREFIX!r} + {_RECORD_PAYLOAD!r} + '\\n')\n"
        "sys.stdout.flush()\n"
        "print('ordinary-line-2')\n"
        "sys.stderr.write('warning-line\\n')\n"
        "print('AF_TASK_WORKSPACE=' + os.environ.get('AF_TASK_WORKSPACE', '<missing>'))\n"
        "print('cwd=' + os.getcwd())\n"
    )


class WrapperPathFunctionalTest(unittest.TestCase):
    """End-to-end run_in_open_session over the real wrapper, no Docker."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory(prefix="af-wiring-test-")
        # resolve(): macOS tmp dirs symlink /var -> /private/var and the
        # child's os.getcwd() reports the real path.
        self.root = Path(self._tmp.name).resolve()
        _make_dataset(self.root)

    def tearDown(self):
        self._tmp.cleanup()

    def _run(self, code, *, limits=None, timeout_seconds=30, task_id="task-wire"):
        config = _test_config(self.root, skip_environment_setup=False)
        session = FakeContainerSession(
            self.root, skip_environment_setup=False
        )
        result = run_in_open_session(
            config,
            session,
            task_id,
            "ds1",
            None,
            code,
            None,
            None,
            timeout_seconds,
            effective_output_limits=limits if limits is not None else dict(_LIMITS),
        )
        return config, session, result

    def test_happy_path_reassembles_stdout_and_builds_channel(self):
        config, session, result = self._run(_user_code_happy())
        task_workspace = f"{config.workspace_root}/task-wire"

        self.assertEqual(result["exit_code"], 0)
        channel = result["finance_record_channel"]
        self.assertIsNotNone(channel)
        payload_bytes = _RECORD_PAYLOAD.encode("utf-8")
        self.assertEqual(
            channel,
            {
                "emitted_record_count": 1,
                "emitted_record_bytes": len(payload_bytes),
                "record_set_complete": True,
                "drop_reason": "",
                "record_digest": record_batch_digest([payload_bytes]),
                "stdout_truncated": False,
                "stderr_truncated": False,
            },
        )
        # §4.2 reassembly: bounded ordinary stdout FIRST (original order),
        # then the complete re-marked record line.
        self.assertEqual(
            result["stdout"],
            "ordinary-line-1\n"
            "ordinary-line-2\n"
            f"AF_TASK_WORKSPACE={task_workspace}\n"
            f"cwd={task_workspace}\n"
            f"{MARKER_V1_PREFIX}{_RECORD_PAYLOAD}\n",
        )
        self.assertEqual(result["stderr"], "warning-line\n")
        # sitecustomize equivalence: AF_TASK_* env + chdir reached the child.
        self.assertIn(f"AF_TASK_WORKSPACE={task_workspace}", result["stdout"])
        self.assertIn(f"cwd={task_workspace}", result["stdout"])
        # Production timings added by the wrapper path.
        for key in ("wrapper_stage_ms", "wrapper_exec_ms", "capture_read_ms"):
            self.assertIn(key, result["timings"])
        # The wrapper path never touches the legacy session.run API.
        self.assertEqual(session.installed_libraries, [])

    def test_nonzero_exit_still_reports_channel(self):
        code = "import sys\nprint('before')\nsys.exit(3)\n"
        _, _, result = self._run(code)
        self.assertEqual(result["exit_code"], 3)
        channel = result["finance_record_channel"]
        self.assertEqual(channel["emitted_record_count"], 0)
        self.assertEqual(
            channel["record_digest"],
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        )
        self.assertTrue(channel["record_set_complete"])
        self.assertEqual(result["stdout"], "before\n")

    def test_timeout_is_carried_by_nonzero_exit_code(self):
        # §7.1 实施方式 6: the wrapper SIGKILLs the process group on timeout;
        # the fact is carried by the non-zero exitCode, capture still written.
        code = "import time\nprint('starting', flush=True)\ntime.sleep(10)\n"
        _, _, result = self._run(code, timeout_seconds=1)
        self.assertNotEqual(result["exit_code"], 0)
        self.assertIsNotNone(result["finance_record_channel"])
        self.assertEqual(result["stdout"], "starting\n")

    def test_frozen_stdout_limit_reaches_the_wrapper(self):
        limits = dict(_LIMITS)
        limits["stdoutMaxBytes"] = 64
        code = "print('x' * 200)\nprint('tail-marker')\n"
        _, _, result = self._run(code, limits=limits)
        channel = result["finance_record_channel"]
        self.assertTrue(channel["stdout_truncated"])
        # Ordinary stdout was bounded by the FROZEN per-task limit.
        self.assertLessEqual(len(result["stdout"].encode("utf-8")), 64 + 4096)

    def test_legacy_path_is_untouched_without_limits(self):
        config = _test_config(self.root, skip_environment_setup=False)

        class LegacySession(FakeContainerSession):
            def run(self, code, libraries=None, timeout=None):
                return SimpleNamespace(exit_code=0, stdout="legacy\n", stderr="")

        session = LegacySession(self.root, skip_environment_setup=False)
        result = run_in_open_session(
            config, session, "task-legacy", "ds1", None,
            "print('legacy')", None, None, 30,
        )
        self.assertEqual(result["stdout"], "legacy\n")
        self.assertIsNone(result["finance_record_channel"])


class WrapperStagingTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory(prefix="af-wiring-stage-")
        self.root = Path(self._tmp.name).resolve()
        self.config = _test_config(self.root, skip_environment_setup=False)
        self.session = FakeContainerSession(
            self.root, skip_environment_setup=False
        )
        self.task_workspace = f"{self.config.workspace_root}/task-stage"
        Path(self.task_workspace).mkdir(parents=True, exist_ok=True)

    def tearDown(self):
        self._tmp.cleanup()

    def test_wrapper_input_matches_frozen_bounded_exec_request_payload(self):
        _stage_bounded_wrapper(
            self.session,
            self.config,
            "task-stage",
            self.task_workspace,
            "print('hi')",
            30.0,
            dict(_LIMITS),
        )
        staged = json.loads(
            Path(self.task_workspace, "wrapper-input.json").read_text(
                encoding="utf-8"
            )
        )
        expected = BoundedExecRequest(
            scriptPath=f"{self.task_workspace}/user_script.py",
            timeoutSeconds=30.0,
            effectiveOutputLimits=EffectiveOutputLimits(
                **{key: _LIMITS[key] for key in _LIMIT_KEYS}
            ),
            runtimeEnvironmentPath=(
                f"{self.config.workdir.rstrip('/')}/runtime-environment.json"
            ),
            # D15 §4.2 (Scenario B): the four AF_TASK_* env vars now travel
            # in the task-local wrapper-input.json instead of the shared
            # global sitecustomize.py.
            taskWorkspace=self.task_workspace,
            taskEnvironment={
                "AF_TASK_WORKSPACE": self.task_workspace,
                "AF_TASK_ARTIFACT_DIR": f"{self.task_workspace}/artifacts",
                "AF_TASK_TMP_DIR": f"{self.task_workspace}/tmp",
                "AF_TASK_METRICS_PATH": f"{self.task_workspace}/metrics/loader_metrics.jsonl",
            },
            loaderPythonPath=self.config.workdir.rstrip("/"),
        ).wrapper_input_payload()
        self.assertEqual(staged, expected)
        # sourceRevision is Task metadata, never part of the wrapper input.
        self.assertNotIn("sourceRevision", staged["effectiveOutputLimits"])
        # The user script landed verbatim.
        self.assertEqual(
            Path(self.task_workspace, "user_script.py").read_text(encoding="utf-8"),
            "print('hi')",
        )
        # The task-local wrapper package is complete.
        pkg_dir = Path(self.task_workspace, "bounded-wrapper", "app")
        for name in sandbox_runner.WRAPPER_MODULE_FILES:
            self.assertTrue((pkg_dir / name).is_file(), name)
        self.assertTrue(
            Path(self.task_workspace, "bounded-wrapper", "run_wrapper.py").is_file()
        )

    def test_interpreter_resolution_mirrors_llm_sandbox(self):
        # skip_environment_setup=True -> plain `python`, no existence probe.
        config_skip = replace(self.config, skip_environment_setup=True)
        session_skip = FakeContainerSession(
            self.root, skip_environment_setup=True
        )
        self.assertEqual(
            _resolve_wrapper_interpreter(session_skip, config_skip, "t"), "python"
        )
        self.assertEqual(session_skip.executed_commands, [])

        # skip_environment_setup=False -> the venv interpreter, probed.
        interpreter = _resolve_wrapper_interpreter(
            self.session, self.config, "t"
        )
        self.assertEqual(interpreter, self.session.python_executable_path)
        self.assertTrue(
            any("test -x" in cmd for cmd in self.session.executed_commands)
        )

        # Missing venv interpreter -> fail closed.
        os.unlink(self.session.python_executable_path)
        with self.assertRaises(RuntimeError):
            _resolve_wrapper_interpreter(self.session, self.config, "t")


class CaptureReadbackTest(unittest.TestCase):
    """Host-side guards around the wrapper-run readback (fail-closed).

    The envelope rides the wrapper run's OWN stdout (wrapper-tail model);
    these tests script that run output directly — no second in-container
    execution exists anymore.
    """

    def _read(self, output):
        return _read_capture_from_container(output, "task-rb", dict(_LIMITS))

    def test_nonzero_wrapper_exit_raises(self):
        with self.assertRaisesRegex(RuntimeError, "capture readback failed"):
            self._read(SimpleNamespace(exit_code=1, stdout="", stderr="boom"))

    def test_invalid_json_raises(self):
        with self.assertRaisesRegex(RuntimeError, "invalid JSON"):
            self._read(SimpleNamespace(exit_code=0, stdout="{not json", stderr=""))

    def test_missing_files_object_raises(self):
        with self.assertRaisesRegex(RuntimeError, "files object"):
            self._read(SimpleNamespace(exit_code=0, stdout="{}", stderr=""))

    def test_unknown_artifact_name_raises(self):
        document = json.dumps({"files": {"evil.sh": base64.b64encode(b"x").decode()}})
        with self.assertRaisesRegex(RuntimeError, "unknown artifact"):
            self._read(SimpleNamespace(exit_code=0, stdout=document, stderr=""))

    def test_invalid_base64_raises(self):
        document = json.dumps({"files": {"stdout.bin": "@@@not-base64@@@"}})
        with self.assertRaisesRegex(RuntimeError, "not valid base64"):
            self._read(SimpleNamespace(exit_code=0, stdout=document, stderr=""))

    def test_inconsistent_capture_fails_the_task(self):
        # Summary declares 99 ordinary bytes but stdout.bin holds 5: the
        # fail-closed host reader must reject the whole capture (codex
        # c72db8f6 item 3) instead of reporting a half-formed channel.
        summary = {
            "exitCode": 0,
            "ordinaryStdoutBytes": 99,
            "stderrBytes": 0,
            "stdoutTruncated": False,
            "stderrTruncated": False,
            "emittedRecordCount": 0,
            "emittedRecordBytes": 0,
            "recordSetComplete": True,
            "dropReason": "",
            "recordDigest": (
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
            ),
            "unknownMarkerLines": 0,
            "unknownMarkerBytes": 0,
            "unknownMarkerTruncated": False,
        }
        document = json.dumps(
            {
                "files": {
                    "capture-result.json": base64.b64encode(
                        json.dumps(summary).encode()
                    ).decode(),
                    "stdout.bin": base64.b64encode(b"hello").decode(),
                    "stderr.bin": base64.b64encode(b"").decode(),
                    "finance-records.jsonl": base64.b64encode(b"").decode(),
                }
            }
        )
        with self.assertRaises(ValueError):
            self._read(SimpleNamespace(exit_code=0, stdout=document, stderr=""))


class CaptureReaderModuleTest(unittest.TestCase):
    def test_whitelist_matches_wrapper_constants(self):
        self.assertEqual(
            set(CAPTURE_FILE_NAMES),
            {
                CAPTURE_RESULT_FILE_NAME,
                STDOUT_FILE_NAME,
                STDERR_FILE_NAME,
                RECORDS_FILE_NAME,
                UNKNOWN_MARKER_AUDIT_FILE_NAME,
            },
        )

    def test_presence_semantics_via_module_api(self):
        # The reader CLI is retired (wrapper-tail model): the read discipline
        # is pinned against the module API the wrapper calls in memory.
        with tempfile.TemporaryDirectory(prefix="af-reader-test-") as tmp:
            capture = Path(tmp) / "capture"
            capture.mkdir()
            (capture / "capture-result.json").write_text("{}", encoding="utf-8")
            (capture / "stdout.bin").write_bytes(b"abc")
            document = read_capture_files(
                capture,
                stdout_max_bytes=1048576,
                stderr_max_bytes=262144,
                record_channel_max_bytes=262144,
                record_channel_max_records=128,
            )
            self.assertEqual(
                set(document["files"]), {"capture-result.json", "stdout.bin"}
            )
            self.assertEqual(
                base64.b64decode(document["files"]["stdout.bin"]), b"abc"
            )
            # Missing capture dir -> ValueError; the wrapper exits non-zero
            # and the host fails the task.
            with self.assertRaisesRegex(ValueError, "capture directory missing"):
                read_capture_files(
                    capture / "nope",
                    stdout_max_bytes=1048576,
                    stderr_max_bytes=262144,
                    record_channel_max_bytes=262144,
                    record_channel_max_records=128,
                )

    def test_cli_is_retired_no_main_no_dunder_main(self):
        # Nothing in-container ever executes capture_reader.py as a process
        # again: the module exposes no main() entry point.
        import app.capture_reader as reader_module

        self.assertFalse(hasattr(reader_module, "main"))

    def test_wrapper_binds_reader_module_at_import_time_pin1(self):
        # PIN 1: bounded_exec_wrapper binds capture_reader at module import
        # time — in the container that happens at wrapper process start,
        # BEFORE the user child is spawned.  A top-level module attribute
        # pins the import to module-load time (no lazy post-exit import).
        import app.bounded_exec_wrapper as wrapper_module
        import app.capture_reader as reader_module
        import app.child_identity as child_identity_module

        self.assertIs(wrapper_module.capture_reader, reader_module)
        self.assertTrue(callable(wrapper_module.capture_reader.read_capture_files))
        # The production fd-pinned entry point AND the child-identity parser
        # (P0-4) are top-level bindings too — held as function objects from
        # module-load time, never re-imported after the child exits.
        self.assertTrue(
            callable(wrapper_module.capture_reader.read_capture_files_from_fds)
        )
        self.assertIs(
            wrapper_module.parse_child_spec, child_identity_module.parse_child_spec
        )


# --- spawn-time wiring facts (b3b28d1f item 2) ----------------------------
# The capture files are pre-opened BEFORE the spawn (fd-pinned readback) and
# must stay root-only: dir 0700 / files 0600.  The child may inherit ONLY
# the capture pipes (stdin/stdout/stderr) — never the capture file fds
# (no pass_fds leak).


class CaptureSpawnWiringTest(unittest.TestCase):
    """Capture permission modes + child fd inheritance wiring."""

    def setUp(self):
        import app.bounded_exec_wrapper as wrapper_module

        self.wrapper = wrapper_module
        self._tmp = tempfile.TemporaryDirectory(prefix="af-spawn-wiring-")
        self.task_dir = Path(self._tmp.name)
        self.script = self.task_dir / "user_code.py"
        self.script.write_text("print('wiring-probe')\n", encoding="utf-8")

    def tearDown(self):
        self._tmp.cleanup()

    def _limits(self):
        return {key: _LIMITS[key] for key in _LIMIT_KEYS}

    def test_capture_dir_is_0700_and_files_are_0600(self):
        capture_dir = self.task_dir / "capture"
        _summary, capture_files, sweep_ok = self.wrapper.run_bounded_capture(
            script_path=str(self.script),
            timeout_seconds=30,
            limits=self._limits(),
            capture_dir=capture_dir,
            child_identity=None,
            # D15 §4.2.3 round-2: bootstrap mode requires a task-local
            # task_workspace + loader path. These wiring tests target
            # capture-dir/pipe inheritance, not task isolation, but the
            # spawn path now hard-requires them.
            task_workspace=str(self.task_dir),
            task_environment={
                "AF_TASK_WORKSPACE": str(self.task_dir),
                "AF_TASK_ARTIFACT_DIR": f"{self.task_dir}/artifacts",
                "AF_TASK_TMP_DIR": f"{self.task_dir}/tmp",
                "AF_TASK_METRICS_PATH": f"{self.task_dir}/metrics/loader.jsonl",
            },
            workdir_for_pythonpath=str(self.task_dir),
        )
        try:
            self.assertTrue(sweep_ok)
            self.assertEqual(
                stat.S_IMODE(capture_dir.stat().st_mode),
                0o700,
                "capture dir must be owner-only so the child cannot enter",
            )
            for name, handle in capture_files.items():
                mode = stat.S_IMODE(os.fstat(handle.fileno()).st_mode)
                self.assertEqual(mode, 0o600, f"{name} must be 0600")
        finally:
            for handle in capture_files.values():
                handle.close()

    def test_child_inherits_only_capture_pipes_no_pass_fds(self):
        captured_kwargs = {}
        real_popen = subprocess.Popen

        class RecordingPopen:
            def __init__(self, *args, **kwargs):
                # Record ONLY the user-child spawn — it is the only Popen
                # that carries preexec_fn (the sweep's lsof utility helper
                # also calls Popen and must not overwrite the evidence).
                if "preexec_fn" in kwargs:
                    captured_kwargs.update(kwargs)
                self._proc = real_popen(*args, **kwargs)

            def __getattr__(self, name):
                return getattr(self._proc, name)

        capture_dir = self.task_dir / "capture"
        with mock.patch.object(subprocess, "Popen", RecordingPopen):
            _summary, capture_files, sweep_ok = self.wrapper.run_bounded_capture(
                script_path=str(self.script),
                timeout_seconds=30,
                limits=self._limits(),
                capture_dir=capture_dir,
                child_identity=None,
                # D15 §4.2.3 round-2: bootstrap mode requires a task-local
                # task_workspace + loader path; see the sibling test above.
                task_workspace=str(self.task_dir),
                task_environment={
                    "AF_TASK_WORKSPACE": str(self.task_dir),
                    "AF_TASK_ARTIFACT_DIR": f"{self.task_dir}/artifacts",
                    "AF_TASK_TMP_DIR": f"{self.task_dir}/tmp",
                    "AF_TASK_METRICS_PATH": f"{self.task_dir}/metrics/loader.jsonl",
                },
                workdir_for_pythonpath=str(self.task_dir),
            )
        try:
            self.assertTrue(sweep_ok)
            # The child gets ONLY the capture pipes: stdin null, stdout/
            # stderr the capture pipes.  No pass_fds leak of the pre-opened
            # capture file fds (close_fds default stays untouched).
            self.assertFalse(
                captured_kwargs.get("pass_fds"),
                "capture file fds must never be inherited via pass_fds",
            )
            self.assertEqual(captured_kwargs.get("stdin"), subprocess.DEVNULL)
            self.assertEqual(captured_kwargs.get("stdout"), subprocess.PIPE)
            self.assertEqual(captured_kwargs.get("stderr"), subprocess.PIPE)
            self.assertTrue(captured_kwargs.get("start_new_session"))
        finally:
            for handle in capture_files.values():
                handle.close()


class SubreaperHardGateTest(unittest.TestCase):
    """codex 02953ca7: ``PR_SET_CHILD_SUBREAPER`` is a HARD spawn gate.

    Without subreaper, a setsid grandchild that also closes the inherited
    capture pipes is invisible to BOTH sweep signals (descendant ppid
    enumeration AND pipe EOF), so the Linux/container path refuses to run
    at all when the prctl fails: no child, no summary, no envelope.
    """

    def setUp(self):
        import app.bounded_exec_wrapper as wrapper_module

        self.wrapper = wrapper_module
        self._tmp = tempfile.TemporaryDirectory(prefix="af-subreaper-gate-")
        self.task_dir = Path(self._tmp.name)
        self.marker = self.task_dir / "child_ran.flag"
        self.script = self.task_dir / "user_code.py"
        self.script.write_text(
            "from pathlib import Path\n"
            f"Path({str(self.marker)!r}).write_text('ran')\n",
            encoding="utf-8",
        )

    def tearDown(self):
        self._tmp.cleanup()

    def _limits(self):
        return {key: _LIMITS[key] for key in _LIMIT_KEYS}

    def _assert_gate_closed(self, capture_dir):
        # The child never ran, and with an active identity a failed spawn
        # leaves NO summary: the pre-opened summary file stays empty.
        self.assertFalse(
            self.marker.exists(), "the child must never be spawned"
        )
        result_path = capture_dir / CAPTURE_RESULT_FILE_NAME
        self.assertTrue(result_path.exists())
        self.assertEqual(
            result_path.stat().st_size,
            0,
            "a subreaper-gate failure must leave no summary",
        )

    def test_prctl_failure_aborts_before_any_spawn(self):
        class FailingPrctlLibc:
            def prctl(self, *_args):
                return -1

            def capset(self, *_args):
                return -1

        capture_dir = self.task_dir / "capture"
        with mock.patch.object(self.wrapper.sys, "platform", "linux"), \
                mock.patch.object(
                    self.wrapper, "_libc", return_value=FailingPrctlLibc()
                ):
            with self.assertRaises(OSError):
                self.wrapper.run_bounded_capture(
                    script_path=str(self.script),
                    timeout_seconds=30,
                    limits=self._limits(),
                    capture_dir=capture_dir,
                    child_identity=(1000, 10001),
                )
        self._assert_gate_closed(capture_dir)

    def test_libc_unavailable_aborts_before_any_spawn(self):
        capture_dir = self.task_dir / "capture"
        with mock.patch.object(self.wrapper.sys, "platform", "linux"), \
                mock.patch.object(self.wrapper, "_libc", return_value=None):
            with self.assertRaises(OSError):
                self.wrapper.run_bounded_capture(
                    script_path=str(self.script),
                    timeout_seconds=30,
                    limits=self._limits(),
                    capture_dir=capture_dir,
                    child_identity=(1000, 10001),
                )
        self._assert_gate_closed(capture_dir)


# --- reader hardening: malicious capture entries must fail closed ---------
# §7.1 stop condition (codex f86c66f5 / e083e181): the user script runs with
# cwd = the task workspace, so MALICIOUS user code can rewrite, replace or
# symlink the capture files while it runs; the wrapper-tail readback happens
# after it exits.  Every attack below must be rejected BEFORE any content is
# forwarded, and diagnostics must never carry artifact content (§18).

_READER_CAPS = {
    "stdout_max_bytes": 1024,
    "stderr_max_bytes": 1024,
    "record_channel_max_bytes": 1024,
    "record_channel_max_records": 8,
}


def _read_capture(capture, **overrides):
    caps = dict(_READER_CAPS)
    caps.update(overrides)
    return read_capture_files(capture, **caps)


class CaptureReaderHardeningTest(unittest.TestCase):
    """Direct reader tier: tampered capture entries fail closed."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory(prefix="af-reader-hard-")
        self.root = Path(self._tmp.name).resolve()
        self.capture = self.root / "capture"
        self.capture.mkdir()

    def tearDown(self):
        self._tmp.cleanup()

    def _write(self, name, data):
        (self.capture / name).write_bytes(data)
        return self.capture / name

    def test_symlinked_stdout_bin_rejected(self):
        outside = self.root / "outside.bin"
        outside.write_bytes(b"outside-file-bytes")
        self._write("capture-result.json", b"{}")
        os.symlink(str(outside), str(self.capture / "stdout.bin"))
        with self.assertRaises(ValueError) as ctx:
            _read_capture(self.capture)
        self.assertIn("symlink", str(ctx.exception))
        # §18: the diagnostic carries names/modes only, never artifact bytes.
        self.assertNotIn("outside-file-bytes", str(ctx.exception))

    def test_oversized_stdout_bin_rejected_without_content_leak(self):
        payload = b"EVIL-STDOUT-PAYLOAD" * 200  # 3800 bytes > cap 1024
        self._write("capture-result.json", b"{}")
        self._write("stdout.bin", payload)
        with self.assertRaises(ValueError) as ctx:
            _read_capture(self.capture)
        self.assertIn("stdout.bin", str(ctx.exception))
        # §18: the diagnostic carries type/message only, never artifact bytes.
        self.assertNotIn("EVIL-STDOUT-PAYLOAD", str(ctx.exception))

    def test_record_channel_joint_bytes_rejected(self):
        self._write("capture-result.json", b"{}")
        # Each file individually small; JOINTLY over the budget.
        self._write("finance-records.jsonl", b'{"v":1}\n')  # 8 bytes
        self._write(
            "finance-records-unknown-marker.jsonl", b"x" * 1020 + b"\n"
        )  # 1021 bytes
        with self.assertRaisesRegex(ValueError, "jointly"):
            _read_capture(self.capture, record_channel_max_bytes=1024)

    def test_record_line_count_rejected(self):
        self._write("capture-result.json", b"{}")
        lines = b"".join(b'{"v":%d}\n' % index for index in range(20))
        self._write("finance-records.jsonl", lines)
        with self.assertRaisesRegex(ValueError, "record_channel_max_records"):
            _read_capture(self.capture)

    def test_oversized_capture_summary_rejected(self):
        big_summary = b"{" + b" " * CAPTURE_SUMMARY_MAX_BYTES + b"}"
        self._write("capture-result.json", big_summary)
        with self.assertRaisesRegex(ValueError, "capture-result.json"):
            _read_capture(self.capture)

    def test_directory_in_place_of_stdout_bin_rejected(self):
        self._write("capture-result.json", b"{}")
        (self.capture / "stdout.bin").mkdir()
        with self.assertRaisesRegex(ValueError, "regular file"):
            _read_capture(self.capture)

    def test_fifo_in_place_of_stdout_bin_rejected(self):
        self._write("capture-result.json", b"{}")
        os.mkfifo(str(self.capture / "stdout.bin"))
        with self.assertRaisesRegex(ValueError, "regular file"):
            _read_capture(self.capture)

    def test_non_integer_or_negative_limits_rejected_at_api(self):
        # CLI limit parsing is retired; the module API itself rejects
        # malformed limits fail-closed (the wrapper passes the frozen §13
        # ints validated at the runner boundary).
        self._write("capture-result.json", b"{}")
        for bad_kwargs in (
            {"stdout_max_bytes": "1024"},
            {"stdout_max_bytes": True},
            {"stderr_max_bytes": -1},
            {"record_channel_max_bytes": 1.5},
            {"record_channel_max_records": None},
        ):
            with self.subTest(bad_kwargs=bad_kwargs):
                with self.assertRaises(ValueError):
                    _read_capture(self.capture, **bad_kwargs)

    def test_envelope_ceiling_monotonic_and_bounds_documents(self):
        base = _envelope_ceiling(1024, 512, 256)
        self.assertLessEqual(base, _envelope_ceiling(2048, 512, 256))
        self.assertLessEqual(base, _envelope_ceiling(1024, 1024, 256))
        self.assertLessEqual(base, _envelope_ceiling(1024, 512, 512))
        # The happy-path document fits under the ceiling.
        self._write("capture-result.json", b'{"exitCode": 0}')
        self._write("stdout.bin", b"ordinary-line\n")
        self._write("stderr.bin", b"")
        document = _read_capture(self.capture)
        self.assertLessEqual(
            len(json.dumps(document)),
            _envelope_ceiling(
                _READER_CAPS["stdout_max_bytes"],
                _READER_CAPS["stderr_max_bytes"],
                _READER_CAPS["record_channel_max_bytes"],
            ),
        )


class EffectiveOutputLimitsValidatorTest(unittest.TestCase):
    """§13/codex f86c66f5: the runner never indexes an unvalidated dict."""

    def test_valid_payload_with_source_revision(self):
        result = validate_effective_output_limits(dict(_LIMITS))
        self.assertEqual(set(result), set(_LIMIT_KEYS))
        for key in _LIMIT_KEYS:
            self.assertEqual(result[key], _LIMITS[key])
        self.assertNotIn("sourceRevision", result)

    def test_valid_payload_without_source_revision(self):
        payload = {key: _LIMITS[key] for key in _LIMIT_KEYS}
        result = validate_effective_output_limits(payload)
        self.assertEqual(result, payload)
        self.assertIsNot(result, payload)  # a FRESH dict

    def test_missing_limit_key_raises(self):
        payload = {key: _LIMITS[key] for key in _LIMIT_KEYS}
        del payload["recordChannelMaxRecords"]
        with self.assertRaisesRegex(ValueError, "recordChannelMaxRecords"):
            validate_effective_output_limits(payload)

    def test_unknown_extra_key_raises(self):
        payload = dict(_LIMITS)
        payload["surprise"] = 1
        with self.assertRaisesRegex(ValueError, "surprise"):
            validate_effective_output_limits(payload)

    def test_non_dict_payload_raises(self):
        for payload in (None, [1, 2], "limits", 42):
            with self.subTest(payload=payload):
                with self.assertRaises(ValueError):
                    validate_effective_output_limits(payload)

    def test_invalid_limit_values_raise(self):
        cases = {
            "bool": True,
            "negative": -1,
            "float": 1.5,
            "string": "1024",
            "none": None,
        }
        for name, value in cases.items():
            with self.subTest(case=name):
                payload = {key: _LIMITS[key] for key in _LIMIT_KEYS}
                payload["stdoutMaxBytes"] = value
                with self.assertRaisesRegex(ValueError, "stdoutMaxBytes"):
                    validate_effective_output_limits(payload)

    def test_non_string_source_revision_raises(self):
        payload = dict(_LIMITS)
        payload["sourceRevision"] = 42
        with self.assertRaisesRegex(ValueError, "sourceRevision"):
            validate_effective_output_limits(payload)

    def test_wrapper_path_validates_before_any_session_use(self):
        config = _test_config(
            Path(tempfile.gettempdir()), skip_environment_setup=True
        )
        malformed = dict(_LIMITS)
        malformed["stdoutMaxBytes"] = -5
        # session=None + a non-empty install list: validation must raise
        # BEFORE any session interaction (install/interpreter/exec).
        with self.assertRaisesRegex(ValueError, "stdoutMaxBytes"):
            _run_bounded_wrapper_path(
                None,
                config,
                "task-validate",
                f"{config.workspace_root}/task-validate",
                "print(1)",
                ["pandas"],
                30.0,
                malformed,
            )


class CaptureTamperingWiringTest(unittest.TestCase):
    """Production wiring tier: MALICIOUS user code tampering with the
    capture files must NOT influence the readback (P0-4 fd-pinning, codex
    03b4d034 / d384119d; the §7.1 stop condition of codex f86c66f5 /
    e083e181 is now met STRUCTURALLY instead of detectably).

    BEHAVIOR CHANGE (forced by the fd-pinning fix, documented per the
    work-package rules): under the path-based readback these tampers were
    DETECTED — the readback failed closed and the task failed.  The wrapper
    now opens every capture file BEFORE the spawn and the wrapper-tail
    readback re-reads EXACTLY those fds (zero path resolution after spawn),
    so the same tampers are INEFFECTUAL: the task succeeds with the GENUINE
    capture content and the injected payload appears nowhere host-side.
    Failing these reads closed was never the goal — bounded, genuine output
    is; fd-pinning achieves it without relying on detection.

    Same harness as WrapperPathFunctionalTest: the REAL staged wrapper runs
    as a host subprocess (its wrapper-tail readback imports capture_reader
    pre-spawn and never executes anything from the task workspace after the
    user child exits), the user script with cwd = task workspace and the
    capture dir at capture/.
    """

    _TAMPER_LIMITS = {
        "stdoutMaxBytes": 1024,
        "stderrMaxBytes": 1024,
        "recordChannelMaxBytes": 512,
        "recordChannelMaxRecords": 8,
        "sourceRevision": "static-default",
    }

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory(prefix="af-tamper-test-")
        self.root = Path(self._tmp.name).resolve()
        _make_dataset(self.root)

    def tearDown(self):
        self._tmp.cleanup()

    def _run(self, code, *, task_id="task-tamper", session_cls=None):
        config = _test_config(self.root, skip_environment_setup=False)
        if session_cls is None:
            session_cls = FakeContainerSession
        session = session_cls(self.root, skip_environment_setup=False)
        result = run_in_open_session(
            config,
            session,
            task_id,
            "ds1",
            None,
            code,
            None,
            None,
            30,
            effective_output_limits=dict(self._TAMPER_LIMITS),
        )
        return config, session, result

    def test_oversized_stdout_rewrite_is_ineffectual_fd_pinned(self):
        """fd-pinning immunity: the child replaces the stdout.bin directory
        entry with an oversized evil file; the readback re-reads the
        wrapper's PRE-OPENED fd, so the task succeeds with the genuine
        bounded output and the injected payload appears nowhere."""
        code = (
            "import os\n"
            "with open(os.path.join('capture', 'stdout.bin.evil'), 'wb') as fh:\n"
            "    fh.write(b'EVIL-INJECTED-PAYLOAD' * 200)\n"
            "os.replace(\n"
            "    os.path.join('capture', 'stdout.bin.evil'),\n"
            "    os.path.join('capture', 'stdout.bin'),\n"
            ")\n"
            "print('ordinary-line')\n"
        )
        config, session, result = self._run(
            code, session_cls=RecordingFakeContainerSession
        )
        self.assertEqual(result["exit_code"], 0)
        self.assertEqual(result["stdout"], "ordinary-line\n")
        self.assertNotIn("EVIL-INJECTED-PAYLOAD", result["stdout"])
        self.assertNotIn("EVIL-INJECTED-PAYLOAD", result["stderr"])
        (wrapper_output,) = session.wrapper_outputs
        document = json.loads(wrapper_output.stdout)
        self.assertEqual(
            base64.b64decode(document["files"]["stdout.bin"]),
            b"ordinary-line\n",
        )
        self.assertNotIn("EVIL-INJECTED-PAYLOAD", wrapper_output.stdout)

    def test_symlinked_stdout_bin_is_ineffectual_fd_pinned(self):
        """fd-pinning immunity: the child deletes stdout.bin and plants a
        symlink to a 100 KB target; the pre-opened fd is untouched, so the
        readback stays genuine and bounded."""
        code = (
            "import os\n"
            "with open('big-target.dat', 'wb') as fh:\n"
            "    fh.write(b'Z' * 100000)\n"
            "os.makedirs('capture', exist_ok=True)\n"
            "link = os.path.join('capture', 'stdout.bin')\n"
            "if os.path.lexists(link):\n"
            "    os.remove(link)\n"
            "os.symlink(os.path.abspath('big-target.dat'), link)\n"
            "print('ordinary-line')\n"
        )
        config, session, result = self._run(
            code, session_cls=RecordingFakeContainerSession
        )
        self.assertEqual(result["exit_code"], 0)
        self.assertEqual(result["stdout"], "ordinary-line\n")
        (wrapper_output,) = session.wrapper_outputs
        document = json.loads(wrapper_output.stdout)
        self.assertEqual(
            base64.b64decode(document["files"]["stdout.bin"]),
            b"ordinary-line\n",
        )
        self.assertNotIn("Z" * 64, wrapper_output.stdout)

    def test_record_channel_joint_bytes_rewrite_is_ineffectual_fd_pinned(self):
        """fd-pinning immunity: the child swaps BOTH record-channel
        directory entries for files that are jointly over
        recordChannelMaxBytes; the wrapper's pre-opened fds keep the
        genuine (empty) record channel, so the task succeeds."""
        code = (
            "import os\n"
            "with open(os.path.join('capture', 'records.evil'), 'wb') as fh:\n"
            "    fh.write(b'{\"v\":1}\\n' * 30)\n"
            "os.replace(\n"
            "    os.path.join('capture', 'records.evil'),\n"
            "    os.path.join('capture', 'finance-records.jsonl'),\n"
            ")\n"
            "with open(os.path.join('capture', 'audit.evil'), 'wb') as fh:\n"
            "    fh.write(b'y' * 300)\n"
            "os.replace(\n"
            "    os.path.join('capture', 'audit.evil'),\n"
            "    os.path.join('capture', 'finance-records-unknown-marker.jsonl'),\n"
            ")\n"
            "print('ordinary-line')\n"
        )
        config, session, result = self._run(
            code, session_cls=RecordingFakeContainerSession
        )
        self.assertEqual(result["exit_code"], 0)
        self.assertEqual(result["stdout"], "ordinary-line\n")
        channel = result["finance_record_channel"]
        self.assertIsNotNone(channel)
        self.assertTrue(channel["record_set_complete"])
        self.assertEqual(channel["emitted_record_count"], 0)
        (wrapper_output,) = session.wrapper_outputs
        document = json.loads(wrapper_output.stdout)
        self.assertNotIn("y" * 64, wrapper_output.stdout)
        # The planted audit file never enters the envelope: the wrapper
        # stored zero audit lines, so it held no audit fd.
        self.assertNotIn(
            "finance-records-unknown-marker.jsonl", document["files"]
        )

    def test_renamed_capture_dir_leaves_fd_pinned_readback_unaffected(self):
        """fd-pinning immunity: the child renames the ENTIRE capture
        directory while it runs; the wrapper's open fds travel with the
        inode, so the readback and the summary stay genuine."""
        code = (
            "import os\n"
            "os.rename('capture', 'capture.evil')\n"
            "print('ordinary-line')\n"
        )
        config, session, result = self._run(
            code, session_cls=RecordingFakeContainerSession
        )
        self.assertEqual(result["exit_code"], 0)
        self.assertEqual(result["stdout"], "ordinary-line\n")
        (wrapper_output,) = session.wrapper_outputs
        document = json.loads(wrapper_output.stdout)
        self.assertEqual(
            base64.b64decode(document["files"]["stdout.bin"]),
            b"ordinary-line\n",
        )

    # --- wrapper-tail P0 regression + stdout discipline (PIN 1 / PIN 2) ---

    def test_overwritten_capture_reader_is_never_executed_regression(self):
        """P0 regression repro: user code overwrites the staged
        bounded-wrapper/app/capture_reader.py with an unbounded-flood script
        and exits 0.  Under the OLD design the runner executed that
        attacker-controlled file after user code exited, letting it flood
        the exec stdout and forge the envelope.  Wrapper-tail model: the
        reader was imported pre-spawn (PIN 1) and is NEVER executed as a
        process after user code ran — the readback stays bounded and
        genuine, and the flood payload appears nowhere host-side.
        """
        flood_size = 200000
        code = (
            "import os\n"
            "evil_path = os.path.join('bounded-wrapper', 'app', 'capture_reader.py')\n"
            "evil_src = 'import sys\\n"
            f"sys.stdout.write(\"X\" * {flood_size})\\n"
            "sys.exit(0)\\n'\n"
            "with open(evil_path, 'w', encoding='utf-8') as fh:\n"
            "    fh.write(evil_src)\n"
            "with open(evil_path, 'r', encoding='utf-8') as fh:\n"
            "    assert fh.read() == evil_src, 'overwrite did not land'\n"
            "print('reader-overwrite-confirmed')\n"
            "print('ordinary-line')\n"
        )
        config, session, result = self._run(
            code, session_cls=RecordingFakeContainerSession
        )

        # The attack landed (verified by the attacker itself) and the task
        # still succeeds with the GENUINE capture content.
        self.assertEqual(result["exit_code"], 0)
        self.assertEqual(
            result["stdout"], "reader-overwrite-confirmed\nordinary-line\n"
        )
        self.assertIsNotNone(result["finance_record_channel"])
        # The flood payload appears NOWHERE in host-side readback.
        flood = "X" * flood_size
        self.assertNotIn(flood, result["stdout"])
        self.assertNotIn(flood, result["stderr"])
        self.assertNotIn("X" * 64, result["stdout"])
        self.assertNotIn("X" * 64, result["stderr"])

        # The wrapper-run stdout stayed bounded by the envelope ceiling —
        # no flood (the old bypass accumulated N unbounded bytes here).
        (wrapper_output,) = session.wrapper_outputs
        self.assertEqual(wrapper_output.exit_code, 0)
        ceiling = _envelope_ceiling(
            self._TAMPER_LIMITS["stdoutMaxBytes"],
            self._TAMPER_LIMITS["stderrMaxBytes"],
            self._TAMPER_LIMITS["recordChannelMaxBytes"],
        )
        self.assertLessEqual(len(wrapper_output.stdout), ceiling)
        self.assertNotIn("X" * 64, wrapper_output.stdout)
        # The single envelope parses and carries the genuine capture.
        document = json.loads(wrapper_output.stdout)
        self.assertEqual(
            base64.b64decode(document["files"]["stdout.bin"]),
            b"reader-overwrite-confirmed\nordinary-line\n",
        )

    def test_success_path_wrapper_stdout_is_exactly_one_json_document(self):
        """PIN 2 success path: the wrapper's stdout is EXACTLY ONE envelope
        JSON document — zero other bytes before or after it."""
        config, session, result = self._run(
            "print('discipline-probe')\n",
            session_cls=RecordingFakeContainerSession,
        )
        self.assertEqual(result["exit_code"], 0)
        (wrapper_output,) = session.wrapper_outputs
        self.assertEqual(wrapper_output.exit_code, 0)
        # The WHOLE stdout parses as one JSON document, and re-serializing
        # it reproduces the stdout byte-for-byte (no leading/trailing bytes).
        document = json.loads(wrapper_output.stdout)
        self.assertEqual(wrapper_output.stdout, json.dumps(document))
        self.assertIsInstance(document.get("files"), dict)
        self.assertEqual(
            base64.b64decode(document["files"]["stdout.bin"]),
            b"discipline-probe\n",
        )

    def test_failure_path_wrapper_stdout_stays_empty_with_short_stderr(self):
        """PIN 2 failure path: a wrapper-internal failure exits nonzero with
        EMPTY stdout and a SHORT stderr diagnostic that never carries
        capture content (§18).

        BEHAVIOR CHANGE (documented): the old trigger — a tampered capture
        file failing the path-based readback — is structurally unreachable
        under fd-pinning (codex d384119d): the readback re-reads pre-opened
        fds, so no user-reachable tamper can corrupt it.  The failure
        discipline is therefore pinned against a genuine internal failure:
        a planted symlink at a capture path makes the wrapper's O_NOFOLLOW
        pre-open fail closed BEFORE any spawn (exit 1, no envelope)."""
        input_dir = self.root / "broken-wrapper"
        (input_dir / "capture").mkdir(parents=True, exist_ok=True)
        # Planted symlink: the wrapper must refuse to open capture files
        # through it (O_NOFOLLOW -> ELOOP) and fail closed.
        (input_dir / "capture" / "stdout.bin").symlink_to(
            self.root / "some-target.dat"
        )
        wrapper_input = input_dir / "wrapper-input.json"
        wrapper_input.write_text(
            json.dumps(
                {
                    "scriptPath": str(input_dir / "script.py"),
                    "timeoutSeconds": 30,
                    "effectiveOutputLimits": {
                        key: self._TAMPER_LIMITS[key] for key in _LIMIT_KEYS
                    },
                    # D15 §4.2 (Scenario B): taskWorkspace/taskEnvironment are
                    # required by parse_wrapper_input, so supply valid ones
                    # so the planted-symlink path is what trips the wrapper.
                    "taskWorkspace": str(input_dir),
                    "taskEnvironment": {
                        "AF_TASK_WORKSPACE": str(input_dir),
                        "AF_TASK_ARTIFACT_DIR": f"{input_dir}/artifacts",
                        "AF_TASK_TMP_DIR": f"{input_dir}/tmp",
                        "AF_TASK_METRICS_PATH": f"{input_dir}/metrics/x.jsonl",
                    },
                    "loaderPythonPath": str(input_dir),
                }
            ),
            encoding="utf-8",
        )
        (input_dir / "script.py").write_text("print('never-runs')\n")
        completed = subprocess.run(
            [sys.executable, "-m", "app.bounded_exec_wrapper", str(wrapper_input)],
            cwd=_SERVICE_ROOT,
            capture_output=True,
            text=True,
            timeout=60,
        )
        # Fail closed BEFORE the spawn: the planted symlink survives (the
        # wrapper never opened stdout.bin), proving the child never ran.
        self.assertTrue((input_dir / "capture" / "stdout.bin").is_symlink())
        self.assertIn("internal error", completed.stderr)
        self.assertNotEqual(completed.returncode, 0)
        self.assertEqual(completed.stdout, "")  # no envelope on failure
        self.assertIn("bounded_exec_wrapper", completed.stderr)
        # §18: type/message only — never capture content; stays bounded.
        self.assertNotIn("SECRET-CAPTURE-CONTENT", completed.stderr)
        self.assertLess(len(completed.stderr), 4096)

    def test_overwritten_wrapper_module_itself_is_harmless(self):
        """Overwriting bounded_exec_wrapper.py itself while the child runs
        is harmless: the wrapper code is already in memory, and nothing from
        the task workspace is executed again after the child exits."""
        code = (
            "import os\n"
            "evil_path = os.path.join(\n"
            "    'bounded-wrapper', 'app', 'bounded_exec_wrapper.py')\n"
            "with open(evil_path, 'w', encoding='utf-8') as fh:\n"
            "    fh.write('import sys\\nsys.stdout.write(\"Y\" * 200000)\\n')\n"
            "print('still-alive')\n"
        )
        config, session, result = self._run(
            code, session_cls=RecordingFakeContainerSession
        )
        self.assertEqual(result["exit_code"], 0)
        self.assertEqual(result["stdout"], "still-alive\n")
        (wrapper_output,) = session.wrapper_outputs
        self.assertEqual(wrapper_output.exit_code, 0)
        document = json.loads(wrapper_output.stdout)
        self.assertEqual(
            base64.b64decode(document["files"]["stdout.bin"]),
            b"still-alive\n",
        )
        self.assertNotIn("Y" * 64, wrapper_output.stdout)


class CreateTaskSnapshotTest(unittest.IsolatedAsyncioTestCase):
    """§7.2/§13: create_task freezes the snapshot; it never moves afterwards."""

    async def test_snapshot_is_frozen_per_task_and_persisted(self):
        import app.main as main_module

        with tempfile.TemporaryDirectory(prefix="af-snapshot-test-") as tmp:
            state_path = Path(tmp) / "state.json"
            store = main_module.DurableTaskStore(state_path)
            dynamic = main_module.DynamicSandboxConfig(main_module.config)
            saved = (
                main_module.task_store,
                main_module.tasks,
                main_module.dynamic_config,
            )
            main_module.task_store = store
            main_module.tasks = store.tasks
            main_module.dynamic_config = dynamic
            try:
                request = main_module.ExecuteRequest(
                    dataset_id="ds1", code="print(1)"
                )
                first = await main_module.create_task(request)
                task_first = store.get(first.task_id)
                self.assertEqual(
                    task_first.effective_output_limits,
                    main_module.EffectiveOutputLimits(
                        **dynamic.output_limits_snapshot()
                    ),
                )
                self.assertEqual(
                    task_first.effective_output_limits.sourceRevision,
                    "static-default",
                )
                # §7.1 (b5a92810): the validated image ref is frozen at
                # create time and is never empty while QUEUED.
                self.assertEqual(
                    task_first.runtime_image_ref,
                    main_module.config.sandbox_image,
                )
                frozen_stdout = task_first.effective_output_limits.stdoutMaxBytes

                # A hot Nacos update changes NEW snapshots only.
                self.assertTrue(
                    dynamic.apply_dynamic_payload({"stdoutMaxBytes": 4096})
                )
                second = await main_module.create_task(request)
                task_second = store.get(second.task_id)
                self.assertNotEqual(first.task_id, second.task_id)
                self.assertEqual(
                    task_second.effective_output_limits.stdoutMaxBytes, 4096
                )
                self.assertNotEqual(
                    task_second.effective_output_limits.sourceRevision,
                    "static-default",
                )
                # The FIRST task's frozen snapshot is untouched.
                self.assertEqual(
                    store.get(first.task_id).effective_output_limits.stdoutMaxBytes,
                    frozen_stdout,
                )

                # state.json round-trip: §7.1 bumped the store format to
                # sandbox_task_store_v2 (v1 stays readable); reload restores
                # the frozen snapshots and image refs.
                document = json.loads(state_path.read_text(encoding="utf-8"))
                self.assertEqual(
                    document["schema_version"], "sandbox_task_store_v2"
                )
                reloaded = main_module.DurableTaskStore(state_path)
                self.assertEqual(
                    reloaded.get(first.task_id).effective_output_limits,
                    task_first.effective_output_limits,
                )
                self.assertEqual(
                    reloaded.get(second.task_id).effective_output_limits,
                    task_second.effective_output_limits,
                )
                self.assertEqual(
                    reloaded.get(first.task_id).runtime_image_ref,
                    main_module.config.sandbox_image,
                )
            finally:
                (
                    main_module.task_store,
                    main_module.tasks,
                    main_module.dynamic_config,
                ) = saved

    async def test_runtime_image_ref_is_frozen_and_idempotent(self):
        """§7.1 (codex b5a92810): create/duplicate/restart keep the original
        ref; a later image change affects NEW tasks only."""
        import app.main as main_module

        with tempfile.TemporaryDirectory(prefix="af-imgref-test-") as tmp:
            state_path = Path(tmp) / "state.json"
            store = main_module.DurableTaskStore(state_path)
            saved = (
                main_module.task_store,
                main_module.tasks,
                main_module.dynamic_config,
                main_module.config,
                main_module.verify_request_fingerprint,
            )
            main_module.task_store = store
            main_module.tasks = store.tasks
            main_module.dynamic_config = main_module.DynamicSandboxConfig(
                main_module.config
            )
            # This test pins store/seam semantics, not fingerprint math.
            main_module.verify_request_fingerprint = lambda request: None
            try:
                def _request(operation_id):
                    # §4.2 canonical create spec, mirroring the shared helper
                    # in tests/test_task_store.py: REAL code_hash over the
                    # code, fixed dataset/library/options digests, and the
                    # production resource/memory/timeout triple (identical
                    # across the idempotent duplicate, so payload_digest
                    # matches and the original task is returned).
                    code = "print(1)"
                    return main_module.ExecuteRequest(
                        dataset_id="ds1",
                        code=code,
                        operation_id=operation_id,
                        request_fingerprint="sha256:" + "0" * 64,
                        resource_class="STANDARD",
                        memory_limit_bytes=512 * 1024 * 1024,
                        timeout_millis=60_000,
                        runtime_environment_version="python-runtime-v1",
                        canonical_spec_schema_version="sandbox_create_v1",
                        code_hash="sha256:"
                        + hashlib.sha256(code.encode("utf-8")).hexdigest(),
                        immutable_dataset_snapshot_digest="sha256:" + "c" * 64,
                        libraries_digest="sha256:" + "d" * 64,
                        sandbox_options_digest="sha256:" + "e" * 64,
                    )

                first = await main_module.create_task(_request("run1:tool1:1"))
                self.assertFalse(first.existing)
                original_ref = store.get(first.task_id).runtime_image_ref
                self.assertTrue(original_ref)  # QUEUED is never empty
                self.assertEqual(original_ref, main_module.config.sandbox_image)

                # Idempotent re-create returns the ORIGINAL task and ref.
                duplicate = await main_module.create_task(_request("run1:tool1:1"))
                self.assertTrue(duplicate.existing)
                self.assertEqual(duplicate.task_id, first.task_id)
                self.assertEqual(
                    store.get(first.task_id).runtime_image_ref, original_ref
                )

                # A later image change affects NEW tasks only.
                main_module.config = replace(
                    main_module.config,
                    sandbox_image="alphafrog-sandbox-runtime@sha256:" + "a" * 64,
                )
                second = await main_module.create_task(_request("run1:tool1:2"))
                self.assertFalse(second.existing)
                self.assertNotEqual(second.task_id, first.task_id)
                self.assertEqual(
                    store.get(second.task_id).runtime_image_ref,
                    main_module.config.sandbox_image,
                )
                self.assertEqual(
                    store.get(first.task_id).runtime_image_ref, original_ref
                )

                # Restart round-trip: the original ref survives reload.
                reloaded = main_module.DurableTaskStore(state_path)
                self.assertEqual(
                    reloaded.get(first.task_id).runtime_image_ref, original_ref
                )
            finally:
                (
                    main_module.task_store,
                    main_module.tasks,
                    main_module.dynamic_config,
                    main_module.config,
                    main_module.verify_request_fingerprint,
                ) = saved


class TaskStoreSchemaV2Test(unittest.TestCase):
    """§7.1: state.json upgraded to sandbox_task_store_v2, v1 readable."""

    def _task(self):
        import app.main as main_module

        return main_module.Task(
            task_id="task-v2",
            status=main_module.TaskStatus.QUEUED,
            request=main_module.ExecuteRequest(dataset_id="ds1", code="print(1)"),
        )

    def test_writes_v2_reads_v1_rejects_unknown(self):
        import app.main as main_module

        with tempfile.TemporaryDirectory(prefix="af-store-v2-") as tmp:
            # Current writes are v2 and carry the frozen §7.2 fields.
            state_path = Path(tmp) / "state.json"
            store = main_module.DurableTaskStore(state_path)
            task = self._task()
            task.effective_output_limits = main_module.EffectiveOutputLimits(
                stdoutMaxBytes=1024,
                stderrMaxBytes=512,
                recordChannelMaxBytes=512,
                recordChannelMaxRecords=8,
                sourceRevision="rev-test",
            )
            task.runtime_image_ref = (
                "alphafrog-sandbox-runtime@sha256:" + "b" * 64
            )
            store.create(task)
            document = json.loads(state_path.read_text(encoding="utf-8"))
            self.assertEqual(document["schema_version"], "sandbox_task_store_v2")
            self.assertEqual(
                main_module.DurableTaskStore(state_path)
                .get("task-v2")
                .effective_output_limits,
                task.effective_output_limits,
            )

            # Legacy v1 documents (no frozen fields) still load.
            v1_payload = self._task().model_dump(mode="json")
            v1_path = Path(tmp) / "state-v1.json"
            v1_path.write_text(
                json.dumps(
                    {
                        "schema_version": "sandbox_task_store_v1",
                        "tasks": {"task-v1": v1_payload},
                        "operations": {},
                    }
                ),
                encoding="utf-8",
            )
            legacy_task = main_module.DurableTaskStore(v1_path).get("task-v1")
            self.assertIsNone(legacy_task.effective_output_limits)
            self.assertIsNone(legacy_task.runtime_image_ref)

            # Unknown versions fail closed (never silently migrate).
            bad_path = Path(tmp) / "state-bad.json"
            bad_path.write_text(
                json.dumps(
                    {
                        "schema_version": "sandbox_task_store_v3",
                        "tasks": {},
                        "operations": {},
                    }
                ),
                encoding="utf-8",
            )
            with self.assertRaises(RuntimeError):
                main_module.DurableTaskStore(bad_path)


class ProcessTaskPersistenceTest(unittest.IsolatedAsyncioTestCase):
    """codex 7bcca065: production-call persistence guarantees.

    Drives the REAL ``process_task`` -> ``run_in_sandbox`` -> wrapper ->
    readback chain (Docker replaced by the host-backed FakeContainerSession)
    and asserts ① a reader consistency error FAILs the task, and ② nothing
    unbounded ever reaches ``task_store.save()`` (§7.1: stdout/stderr must
    already be bounded BEFORE any save action).
    """

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory(prefix="af-ptask-test-")
        self.root = Path(self._tmp.name).resolve()
        _make_dataset(self.root)

    def tearDown(self):
        self._tmp.cleanup()

    def _harness(self, session_cls):
        import app.main as main_module

        config = _test_config(self.root, skip_environment_setup=False)

        def fake_run_in_sandbox(
            cfg,
            task_id,
            dataset_id,
            dataset_ids,
            code,
            files,
            libraries,
            timeout_seconds,
            *,
            paths_dataset_csv=None,
            path_manifest_csv=None,
            queue_wait_ms=0,
            resource_class="STANDARD",
            memory_limit_bytes=None,
            effective_output_limits=None,
        ):
            session = session_cls(self.root, skip_environment_setup=False)
            try:
                return run_in_open_session(
                    cfg,
                    session,
                    task_id,
                    dataset_id,
                    dataset_ids,
                    code,
                    files,
                    libraries,
                    timeout_seconds,
                    paths_dataset_csv=paths_dataset_csv,
                    path_manifest_csv=path_manifest_csv,
                    queue_wait_ms=queue_wait_ms,
                    resource_class=resource_class,
                    effective_output_limits=effective_output_limits,
                )
            finally:
                session.close()

        state_path = self.root / "state.json"
        store = main_module.DurableTaskStore(state_path)
        saved = (
            main_module.task_store,
            main_module.tasks,
            main_module.dynamic_config,
            main_module.config,
            main_module.pool,
            main_module.run_in_sandbox,
        )
        main_module.task_store = store
        main_module.tasks = store.tasks
        main_module.dynamic_config = main_module.DynamicSandboxConfig(config)
        main_module.config = config
        main_module.pool = None
        main_module.run_in_sandbox = fake_run_in_sandbox
        return main_module, store, state_path, saved

    def _restore(self, main_module, saved):
        (
            main_module.task_store,
            main_module.tasks,
            main_module.dynamic_config,
            main_module.config,
            main_module.pool,
            main_module.run_in_sandbox,
        ) = saved

    def _queued_task(self, main_module, code, task_id):
        task = main_module.Task(
            task_id=task_id,
            status=main_module.TaskStatus.QUEUED,
            request=main_module.ExecuteRequest(dataset_id="ds1", code=code),
        )
        task.effective_output_limits = main_module.EffectiveOutputLimits(
            **{key: _LIMITS[key] for key in _LIMIT_KEYS}
        )
        task.runtime_image_ref = main_module.config.sandbox_image
        return task

    async def test_reader_consistency_error_fails_task_with_bounded_state(self):
        summary = {
            "exitCode": 0,
            "ordinaryStdoutBytes": 99,  # but stdout.bin holds only 5 bytes
            "stderrBytes": 0,
            "stdoutTruncated": False,
            "stderrTruncated": False,
            "emittedRecordCount": 0,
            "emittedRecordBytes": 0,
            "recordSetComplete": True,
            "dropReason": "",
            "recordDigest": (
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
            ),
            "unknownMarkerLines": 0,
            "unknownMarkerBytes": 0,
            "unknownMarkerTruncated": False,
        }
        tampered_document = json.dumps(
            {
                "files": {
                    "capture-result.json": base64.b64encode(
                        json.dumps(summary).encode()
                    ).decode(),
                    "stdout.bin": base64.b64encode(b"hello").decode(),
                    "stderr.bin": base64.b64encode(b"").decode(),
                    "finance-records.jsonl": base64.b64encode(b"").decode(),
                }
            }
        )

        class TamperedReadbackSession(FakeContainerSession):
            # Wrapper-tail model: the envelope rides the wrapper run's OWN
            # stdout, so a forged readback means a forged wrapper-run output.
            def execute_command(self, command, workdir=None):
                if sandbox_runner.WRAPPER_BOOTSTRAP_NAME in command:
                    return SimpleNamespace(
                        exit_code=0, stdout=tampered_document, stderr=""
                    )
                return super().execute_command(command, workdir)

        main_module, store, state_path, saved = self._harness(
            TamperedReadbackSession
        )
        try:
            task = self._queued_task(
                main_module, "print('leak-check-payload')", "task-ptask-bad"
            )
            store.create(task)
            await main_module.process_task(task, worker_id=1)

            self.assertEqual(task.status, main_module.TaskStatus.FAILED)
            self.assertEqual(task.result.exit_code, -1)
            # Nothing unbounded is persisted: stdout is EMPTY and the stderr
            # is only the reader's diagnostic (counts, never artifact bytes).
            self.assertEqual(task.result.stdout, "")
            self.assertNotIn("leak-check-payload", task.result.stderr)
            self.assertNotIn("hello", task.result.stderr)
            self.assertEqual(task.error, task.result.stderr)

            persisted = json.loads(state_path.read_text(encoding="utf-8"))
            self.assertEqual(persisted["schema_version"], "sandbox_task_store_v2")
            saved_result = persisted["tasks"]["task-ptask-bad"]["result"]
            self.assertEqual(saved_result["stdout"], "")
            self.assertEqual(saved_result["stderr"], task.error)
            self.assertNotIn("leak-check-payload", saved_result["stderr"])
        finally:
            self._restore(main_module, saved)

    async def test_successful_run_persists_only_bounded_outputs(self):
        main_module, store, state_path, saved = self._harness(FakeContainerSession)
        try:
            task = self._queued_task(
                main_module, _user_code_happy(), "task-ptask-ok"
            )
            store.create(task)
            await main_module.process_task(task, worker_id=1)

            self.assertEqual(task.status, main_module.TaskStatus.SUCCEEDED)
            persisted = json.loads(state_path.read_text(encoding="utf-8"))
            self.assertEqual(persisted["schema_version"], "sandbox_task_store_v2")
            saved_task = persisted["tasks"]["task-ptask-ok"]
            saved_result = saved_task["result"]
            # The persisted stdout is EXACTLY the bounded §4.2 reassembly and
            # within the frozen cap; the persisted stderr is the bounded
            # stderr. Nothing else from the container can reach state.json.
            self.assertEqual(saved_result["stdout"], task.result.stdout)
            self.assertIn("ordinary-line-1", saved_result["stdout"])
            self.assertIn(MARKER_V1_PREFIX + _RECORD_PAYLOAD, saved_result["stdout"])
            self.assertLessEqual(
                len(saved_result["stdout"].encode("utf-8")),
                _LIMITS["stdoutMaxBytes"],
            )
            self.assertEqual(saved_result["stderr"], "warning-line\n")
            # The frozen image ref rode the whole execution into state.
            self.assertEqual(
                saved_task["runtime_image_ref"], main_module.config.sandbox_image
            )
        finally:
            self._restore(main_module, saved)


class AttachChannelHelperTest(unittest.TestCase):
    """Merge-safe §5.1 attach (D declares the DTO field at owner merge)."""

    def _result(self):
        import app.main as main_module

        return main_module.ExecuteResult(
            exit_code=0, stdout="ok", stderr="", dataset_dir="/d"
        )

    def test_none_channel_returns_same_instance(self):
        import app.main as main_module

        result = self._result()
        self.assertIs(
            main_module._attach_finance_record_channel(result, None), result
        )

    def test_missing_field_returns_same_instance(self):
        import app.main as main_module

        # Tolerance branch: a DTO without the field (pre-merge shape) gets the
        # result back unchanged. Owner merge 2026-08-09: the REAL ExecuteResult
        # now declares the field (D landed), so this uses a fieldless stub.
        class _NoFieldResult:
            model_fields: dict = {}

        result = _NoFieldResult()
        self.assertIs(
            main_module._attach_finance_record_channel(
                result, {"emitted_record_count": 0}, model_cls=_NoFieldResult
            ),
            result,
        )

    def test_real_execute_result_attaches_channel_post_merge(self):
        import app.main as main_module
        from app.models import FinanceRecordChannel

        # Owner merge 2026-08-09: D's frozen §5.1 field now exists on the real
        # ExecuteResult, so the validated channel attaches with no model_cls.
        self.assertIn("finance_record_channel", main_module.ExecuteResult.model_fields)
        result = self._result()
        updated = main_module._attach_finance_record_channel(
            result, {"emitted_record_count": 0}
        )
        # codex 40f2f2f2: model_copy(update=...) does not validate/coerce, so
        # the attach helper must validate explicitly — the stored value is a
        # TYPED FinanceRecordChannel, never a raw dict.
        self.assertIsInstance(updated.finance_record_channel, FinanceRecordChannel)
        self.assertEqual(updated.finance_record_channel.emitted_record_count, 0)
        self.assertTrue(updated.finance_record_channel.record_set_complete)

    def test_real_execute_result_malformed_channel_fail_closed(self):
        import app.main as main_module

        # codex 40f2f2f2: malformed channel payloads MUST raise against D's
        # frozen DTO instead of bypassing it as an unvalidated raw dict.
        result = self._result()
        with self.assertRaises(Exception) as cm:
            main_module._attach_finance_record_channel(
                result, {"emitted_record_count": "not-an-int"}
            )
        self.assertIn("emitted_record_count", str(cm.exception))
        # Original result untouched.
        self.assertIsNone(result.finance_record_channel)

    def test_present_field_attaches_channel(self):
        import app.main as main_module

        class StubResult(main_module.ExecuteResult):
            finance_record_channel: dict | None = None

        channel = {"emitted_record_count": 0}
        result = StubResult(exit_code=0, stdout="ok", stderr="", dataset_dir="/d")
        updated = main_module._attach_finance_record_channel(
            result, channel, model_cls=StubResult
        )
        self.assertEqual(updated.finance_record_channel, channel)


class SecurityFloorWiringTest(unittest.TestCase):
    """P0-5 (codex 5777cda8): one-task-per-container security floor.

    Any task that selected the bounded wrapper path is ALWAYS recycled —
    ``container_recycled=True`` with the floor reason — regardless of
    success, failure or install state.  This SUPERSEDES work package D's
    conditional recycle (which keyed recycling off cleanup failure only):
    user code leaves residual state no cleanup can fully undo, so the
    bounded path is single-use by policy.  The legacy (no-limits) path is
    deliberately NOT subject to the floor.
    """

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory(prefix="af-wiring-floor-")
        self.root = Path(self._tmp.name).resolve()
        _make_dataset(self.root)

    def tearDown(self):
        self._tmp.cleanup()

    def _run_bounded(self, session, task_id, code, root=None):
        config = _test_config(
            root if root is not None else self.root,
            skip_environment_setup=False,
        )
        return config, run_in_open_session(
            config,
            session,
            task_id,
            "ds1",
            None,
            code,
            None,
            None,
            30,
            effective_output_limits=dict(_LIMITS),
        )

    def test_successful_bounded_task_is_always_recycled(self):
        session = FakeContainerSession(
            self.root, skip_environment_setup=False
        )
        _, result = self._run_bounded(
            session, "task-floor-ok", "print('floor-ok')\n"
        )
        self.assertEqual(result["exit_code"], 0)
        self.assertTrue(
            result["container_recycled"],
            "a SUCCESSFUL bounded task must still recycle the container",
        )
        self.assertEqual(
            result["recycle_reason"],
            sandbox_runner.RECYCLE_REASON_SECURITY_FLOOR,
        )

    def test_failing_bounded_task_is_always_recycled(self):
        session = FakeContainerSession(
            self.root, skip_environment_setup=False
        )
        _, result = self._run_bounded(
            session, "task-floor-fail", "import sys\nsys.exit(3)\n"
        )
        self.assertEqual(result["exit_code"], 3)
        self.assertTrue(result["container_recycled"])
        self.assertEqual(
            result["recycle_reason"],
            sandbox_runner.RECYCLE_REASON_SECURITY_FLOOR,
        )

    def test_legacy_path_is_not_subject_to_the_floor(self):
        config = _test_config(self.root, skip_environment_setup=False)

        class LegacySession(FakeContainerSession):
            def run(self, code, libraries=None, timeout=None):
                return SimpleNamespace(exit_code=0, stdout="legacy\n", stderr="")

        session = LegacySession(self.root, skip_environment_setup=False)
        result = run_in_open_session(
            config, session, "task-legacy-floor", "ds1", None,
            "print('legacy')", None, None, 30,
        )
        self.assertEqual(result["exit_code"], 0)
        self.assertFalse(
            result["container_recycled"],
            "the floor applies to the BOUNDED path only; the legacy path "
            "keeps its historical conditional recycle",
        )

    def test_two_bounded_tasks_never_share_a_container(self):
        """Two-task regression: task one succeeds and the floor recycles;
        the caller destroys that session; task two therefore runs on a
        DIFFERENT session/container and sees none of task one's state."""
        root1 = self.root / "container-1"
        root1.mkdir(parents=True, exist_ok=True)
        _make_dataset(root1)
        session1 = FakeContainerSession(root1, skip_environment_setup=False)
        session1.container_id = "fake-container-floor-1"
        config1, result1 = self._run_bounded(
            session1,
            "task-floor-1",
            # Container-wide pollution OUTSIDE the task workspace: survives
            # workspace cleanup, dies only with the container.
            "import os\n"
            "with open(os.path.join(os.getcwd(), '..', '..', "
            "'pollution-marker.txt'), 'w') as handle:\n"
            "    handle.write('polluted-by-task-one')\n"
            "print('task-one-ran')\n",
            root=root1,
        )
        self.assertEqual(result1["exit_code"], 0)
        self.assertTrue(result1["container_recycled"])
        self.assertEqual(
            result1["recycle_reason"],
            sandbox_runner.RECYCLE_REASON_SECURITY_FLOOR,
        )
        self.assertEqual(result1["container_id"], "fake-container-floor-1")
        self.assertTrue((root1 / "sandbox" / "pollution-marker.txt").exists())
        session1.close()  # the caller destroys a recycled container

        root2 = self.root / "container-2"
        root2.mkdir(parents=True, exist_ok=True)
        _make_dataset(root2)
        session2 = FakeContainerSession(root2, skip_environment_setup=False)
        session2.container_id = "fake-container-floor-2"
        config2, result2 = self._run_bounded(
            session2,
            "task-floor-2",
            "import os\n"
            "print('POLLUTION_PRESENT=%s' % os.path.exists(\n"
            "    os.path.join(os.getcwd(), '..', '..', "
            "'pollution-marker.txt')))\n",
            root=root2,
        )
        self.assertEqual(result2["exit_code"], 0)
        self.assertNotEqual(
            result1["container_id"],
            result2["container_id"],
            "the floor demands a fresh container for the next task",
        )
        self.assertIn("POLLUTION_PRESENT=False", result2["stdout"])

    def test_pool_drains_a_recycled_container_and_serves_the_next_task(
        self,
    ):
        """Pool-level wiring: a real ``ContainerPoolScheduler`` whose
        sessions are FakeContainerSession instances — the recycled flag
        from the bounded run drains the worker, and the NEXT task runs on a
        freshly created session (different container id)."""
        from unittest.mock import patch

        from app.pool_scheduler import ContainerPoolScheduler

        created_sessions: list = []

        def fake_create(config, **kwargs):
            root = self.root / "containers" / f"container-{len(created_sessions) + 1}"
            root.mkdir(parents=True, exist_ok=True)
            session = FakeContainerSession(
                root, skip_environment_setup=False
            )
            session.container_id = f"fake-pool-container-{len(created_sessions) + 1}"
            created_sessions.append(session)
            return session

        def fake_container_id(session):
            return session.container_id

        config = replace(
            _test_config(self.root, skip_environment_setup=False),
            pool_enabled=True,
            pool_min_size=1,
            pool_max_size=2,
        )
        with patch(
            "app.pool_scheduler.create_sandbox_session",
            side_effect=fake_create,
        ), patch(
            "app.pool_scheduler.get_session_container_id",
            side_effect=fake_container_id,
        ), patch(
            "app.pool_scheduler.smoke_check_session"
        ), patch(
            "app.pool_scheduler.prepare_container_loader_modules",
            sandbox_runner.prepare_container_loader_modules,
        ):
            scheduler = ContainerPoolScheduler(config)
            scheduler.start()
            try:
                result1 = scheduler.run_task(
                    "task-pool-floor-1",
                    "ds1",
                    None,
                    "print('pool-floor-one')\n",
                    None,
                    None,
                    30,
                    effective_output_limits=dict(_LIMITS),
                )
                result2 = scheduler.run_task(
                    "task-pool-floor-2",
                    "ds1",
                    None,
                    "print('pool-floor-two')\n",
                    None,
                    None,
                    30,
                    effective_output_limits=dict(_LIMITS),
                )
            finally:
                scheduler.close()

        self.assertEqual(result1["exit_code"], 0)
        self.assertTrue(result1["container_recycled"])
        self.assertEqual(
            result1["recycle_reason"],
            sandbox_runner.RECYCLE_REASON_SECURITY_FLOOR,
        )
        self.assertEqual(result2["exit_code"], 0)
        self.assertNotEqual(
            result1["container_id"],
            result2["container_id"],
            "the drained worker must be replaced by a fresh container",
        )
        self.assertGreaterEqual(
            len(created_sessions),
            2,
            "the pool must create a replacement session after the floor "
            "recycle",
        )


if __name__ == "__main__":
    unittest.main()
