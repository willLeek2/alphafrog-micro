# === work-package-C (ccqwen) ===
"""Production wiring tests for the §7.1 bounded execution wrapper (codex
c72db8f6 item 1): create_task snapshot freeze -> runner wrapper path ->
container-side capture -> readback -> §5.1 channel.

The functional tier drives ``run_in_open_session`` END TO END with the REAL
staged wrapper and capture reader running as host subprocesses behind a
host-backed FakeSession: container paths are literal host paths under a
per-test root, ``copy_to_runtime`` copies bytes, and ``execute_command``
runs the exact ``sh -lc`` scripts production builds.  A PATH shim makes
``python`` resolve to ``sys.executable`` (production containers have it on
PATH; hosts may not).  No Docker required.

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
import subprocess
import sys
import tempfile
import types
import unittest
from dataclasses import replace
from pathlib import Path
from types import SimpleNamespace

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
from app.capture_reader import CAPTURE_FILE_NAMES, read_capture_files  # noqa: E402
from app.config import SandboxConfig  # noqa: E402
from app.models import BoundedExecRequest, EffectiveOutputLimits  # noqa: E402
from app.output_capture import MARKER_V1_PREFIX, record_batch_digest  # noqa: E402
from app.sandbox_runner import (  # noqa: E402
    _read_capture_from_container,
    _resolve_wrapper_interpreter,
    _stage_bounded_wrapper,
    run_in_open_session,
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
    """Host-side guards around the container readback (fail-closed)."""

    class ScriptedSession:
        def __init__(self, output):
            self.output = output

        def execute_command(self, command, workdir=None):
            return self.output

    def setUp(self):
        self.config = _test_config(Path(tempfile.gettempdir()), skip_environment_setup=True)

    def _read(self, output):
        return _read_capture_from_container(
            self.ScriptedSession(output),
            self.config,
            "task-rb",
            "/tmp/task-rb",
            "python",
            dict(_LIMITS),
        )

    def test_nonzero_reader_exit_raises(self):
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

    def test_presence_semantics_and_subprocess_round_trip(self):
        with tempfile.TemporaryDirectory(prefix="af-reader-test-") as tmp:
            capture = Path(tmp) / "capture"
            capture.mkdir()
            (capture / "capture-result.json").write_text("{}", encoding="utf-8")
            (capture / "stdout.bin").write_bytes(b"abc")
            document = read_capture_files(capture)
            self.assertEqual(
                set(document["files"]), {"capture-result.json", "stdout.bin"}
            )
            self.assertEqual(
                base64.b64decode(document["files"]["stdout.bin"]), b"abc"
            )
            # Container invocation shape: interpreter + capture dir argument.
            reader_path = Path(sandbox_runner.APP_DIR / "capture_reader.py")
            completed = subprocess.run(
                [sys.executable, str(reader_path), str(capture)],
                capture_output=True,
                text=True,
                timeout=30,
            )
            self.assertEqual(completed.returncode, 0)
            emitted = json.loads(completed.stdout)
            self.assertEqual(emitted, document)
            # Missing capture dir -> non-zero exit (host fails the task).
            missing = subprocess.run(
                [sys.executable, str(reader_path), str(capture / "nope")],
                capture_output=True,
                text=True,
                timeout=30,
            )
            self.assertEqual(missing.returncode, 1)


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
            def execute_command(self, command, workdir=None):
                if "capture_reader.py" in command:
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

        result = self._result()
        # Today's ExecuteResult has no finance_record_channel field (D owns
        # the declaration); until then the validated channel is not attached.
        self.assertNotIn(
            "finance_record_channel", main_module.ExecuteResult.model_fields
        )
        self.assertIs(
            main_module._attach_finance_record_channel(result, {"emitted_record_count": 0}),
            result,
        )

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


if __name__ == "__main__":
    unittest.main()
