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

# D15 release-binding (codex a1b749ad) made AF_SANDBOX_IMAGE required at
# config load (no implicit default, no silent 'latest' fallback). Set a
# valid digest reference before any app.main import; same pattern as
# test_main_d14_operation_id_gate.
os.environ.setdefault(
    "AF_SANDBOX_IMAGE",
    "registry.local/alphafrog/runtime@sha256:" + "a" * 64,
)
# The registry-form digest above only validates under strict-release; in a
# full discovery run test_cancel_endpoint's module import sets this first,
# but a standalone run of THIS file needs it too (default is local-image-id).
os.environ.setdefault("AF_SANDBOX_IMAGE_VERIFY_MODE", "strict-release")

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
    _create_task_control_dir,
    _exec_checked_argv,
    _exec_checked_script,
    _log_in_container,
    _read_capture_from_container,
    _read_runtime_size,
    _resolve_wrapper_interpreter,
    _run_bounded_wrapper_path,
    _sh_script,
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


class HostArchiveContainer:
    """Host-backed stand-in for the docker container's archive API.

    ``put_archive(dest_dir, tar_bytes)`` extracts each tar member into the
    literal host directory (fakes use host paths as container paths) and
    records ``(dest_dir, name, uid, gid, mode, size)`` per entry so tests
    can assert the non-root ownership contract of app.container_copy.
    """

    def __init__(self) -> None:
        self.archive_entries: list = []

    def put_archive(self, dest_dir: str, data: bytes) -> None:
        import io as _io
        import tarfile as _tarfile

        entries = []
        with _tarfile.open(fileobj=_io.BytesIO(data)) as tar:
            for member in tar.getmembers():
                entries.append(
                    (dest_dir, member.name, member.uid, member.gid,
                     member.mode, member.size)
                )
                # Host extraction runs as the dev uid; keep the recorded
                # uid/gid as the assertion evidence and extract the bytes.
                member.uid, member.gid = os.getuid(), os.getgid()
                member.uname = member.gname = ""
                tar.extract(member, dest_dir)
        self.archive_entries.extend(entries)


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
        # Non-root copy path (grace review): a host-backed stand-in for the
        # docker container object — put_archive extracts the staged tar into
        # the literal host dir and RECORDS each entry's uid/gid/mode so tests
        # can assert the ownership contract of app.container_copy.
        self.container = HostArchiveContainer()
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
        # Non-root contract (grace review): production staging must use the
        # put_archive path exclusively; reaching the llm-sandbox copy API
        # here means a regression back to its root-chown copy path.
        raise AssertionError(
            "session.copy_to_runtime must never be called: staging goes "
            "through app.container_copy (no-root contract)"
        )

    def execute_command(self, command: str, workdir=None):
        self.executed_commands.append(command)
        # 260818 (stress batch 20260818-152331): mirror the REAL execution
        # semantics — llm-sandbox hands the string to docker exec_run and
        # docker-py shlex.splits it into argv with NO shell.  The previous
        # host-shell fake (subprocess shell=True) happily interpreted `&&`
        # and masked the D11 mkdir-chaining bug for 8 days.
        #
        # 260818 grace round-3: the audit is STRUCTURAL, not a character
        # blacklist (blacklists keep missing shell syntax and reject
        # legitimate literal args like a quoted `*.csv`).  Production now
        # goes through the structured helpers, so the only invariant left
        # to police here is the shell-script form: a `sh -lc`/`sh -c`
        # command must be EXACTLY three tokens (the one quoted script).
        # Everything else is plain argv semantics and executes without a
        # shell — a confused command (e.g. argv[0]="cd") then fails
        # LOUDLY (exit 127), exactly like docker, instead of silently
        # doing the wrong thing.
        import shlex as _shlex

        tokens = _shlex.split(command)
        if (
            len(tokens) >= 2
            and tokens[0] == "sh"
            and tokens[1] in ("-lc", "-c")
            and len(tokens) != 3
        ):
            raise AssertionError(
                f"`sh {tokens[1]}` command must carry the WHOLE script as "
                f"ONE quoted argument (got {len(tokens)} tokens): "
                f"{command!r} — production must use _exec_checked_script"
            )
        env = dict(os.environ)
        env["PATH"] = f"{self.bin_dir}{os.pathsep}{env.get('PATH', '')}"
        try:
            completed = subprocess.run(
                tokens,
                capture_output=True,
                text=True,
                env=env,
                timeout=90,
            )
        except FileNotFoundError:
            # docker returns 126/127 for a missing program; mirror that so
            # shell-builtins ("cd") and env-prefix argv ("FOO=bar cmd")
            # surface as loud exec failures, not host exceptions.
            return SimpleNamespace(exit_code=127, stdout="", stderr="")
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
        # D11 (task #108): the host-backed fake session cannot create /run
        # (read-only host root).  Point the task control root at a writable
        # dir under this test's root; runner and wrapper read the SAME env
        # (AF_TASK_CONTROL_ROOT), and the wrapper subprocess inherits it.
        self._saved_control_root = os.environ.get("AF_TASK_CONTROL_ROOT")
        os.environ["AF_TASK_CONTROL_ROOT"] = str(self.root / "task-control")

    def tearDown(self):
        if self._saved_control_root is None:
            os.environ.pop("AF_TASK_CONTROL_ROOT", None)
        else:
            os.environ["AF_TASK_CONTROL_ROOT"] = self._saved_control_root
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

class CaptureReadbackTest(unittest.TestCase):
    """Host-side guards around the wrapper-run readback (fail-closed).

    The envelope rides the wrapper run's OWN stdout (wrapper-tail model);
    these tests script that run output directly — no second in-container
    execution exists anymore.
    """

    def _read(self, output):
        return _read_capture_from_container(output, "task-rb", dict(_LIMITS))

class CaptureReaderModuleTest(unittest.TestCase):
    pass
# --- spawn-time wiring facts (b3b28d1f item 2) ----------------------------
# The capture files are pre-opened BEFORE the spawn (fd-pinned readback)
# with dir 0700 / files 0600.  Same-uid note (260818): the child shares the
# wrapper's uid, so the 0700/0600 bits are hygiene, NOT a boundary — the
# real protections are fd-pinning (defeats path replacement) plus the
# host-side byte-length/digest consistency checks (turn same-inode
# tampering into a fail-closed task error).  The child may inherit ONLY
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

class ExecCommandOperatorDisciplineTest(unittest.TestCase):
    """260818 (stress batch 20260818-152331 + grace rounds): the container
    exec path has NO shell — docker-py shlex.splits the command string
    into argv, so the D11 ``mkdir -p X && chmod ...`` chain ran mkdir with
    literal ``&&`` arguments and every task failed.  The discipline is now
    STRUCTURAL (grace round-3: a character blacklist can never prove a
    command shell-free):

    * no-shell commands go through ``_exec_checked_argv`` as argv LISTS —
      ``shlex.join`` quoting round-trips through docker's shlex.split
      EXACTLY, so ``*``/``$``/``;`` inside a list element are unambiguous
      plain arguments, and confused commands (``cd``, an env-prefix
      element) fail LOUDLY (exit 127) instead of silently misbehaving;
    * shell commands go through ``_exec_checked_script`` with ONE
      complete script — the only place ``sh -lc`` is constructed;
    * the wiring fake executes argv without a shell and rejects loose
      ``sh -lc`` strings outright.
    """

    def setUp(self):
        import shlex as _shlex

        self._shlex = _shlex
        self._tmp = tempfile.TemporaryDirectory(prefix="af-opdisc-")
        self.root = Path(self._tmp.name)

        class RecordingSession:
            def __init__(self):
                self.commands = []

            def execute_command(self, command, workdir=None):
                self.commands.append(command)
                return SimpleNamespace(exit_code=0, stdout="", stderr="")

        self.session = RecordingSession()

    def tearDown(self):
        self._tmp.cleanup()

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
        # The child never ran, and a failed spawn leaves NO summary: the
        # pre-opened summary file stays empty (no child, no summary).
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

class EffectiveOutputLimitsValidatorTest(unittest.TestCase):
    """§13/codex f86c66f5: the runner never indexes an unvalidated dict."""

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
        # D11: host fake session cannot create /run — see WrapperPathFunctionalTest.
        self._saved_control_root = os.environ.get("AF_TASK_CONTROL_ROOT")
        os.environ["AF_TASK_CONTROL_ROOT"] = str(self.root / "task-control")

    def tearDown(self):
        if self._saved_control_root is None:
            os.environ.pop("AF_TASK_CONTROL_ROOT", None)
        else:
            os.environ["AF_TASK_CONTROL_ROOT"] = self._saved_control_root
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

    # --- wrapper-tail P0 regression + stdout discipline (PIN 1 / PIN 2) ---

class CreateTaskSnapshotTest(unittest.IsolatedAsyncioTestCase):
    """§7.2/§13: create_task freezes the snapshot; it never moves afterwards."""

class TaskStoreSchemaV3Test(unittest.TestCase):
    """D11 (task #108): state.json upgraded to sandbox_task_store_v3
    (cancel_requests binding map added); v1/v2 stay readable."""

    def _task(self):
        import app.main as main_module

        return main_module.Task(
            task_id="task-v3",
            status=main_module.TaskStatus.QUEUED,
            request=main_module.ExecuteRequest(dataset_id="ds1", code="print(1)"),
        )

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
        # D11: host fake session cannot create /run — see WrapperPathFunctionalTest.
        self._saved_control_root = os.environ.get("AF_TASK_CONTROL_ROOT")
        os.environ["AF_TASK_CONTROL_ROOT"] = str(self.root / "task-control")

    def tearDown(self):
        if self._saved_control_root is None:
            os.environ.pop("AF_TASK_CONTROL_ROOT", None)
        else:
            os.environ["AF_TASK_CONTROL_ROOT"] = self._saved_control_root
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

class AttachChannelHelperTest(unittest.TestCase):
    """Merge-safe §5.1 attach (D declares the DTO field at owner merge)."""

    def _result(self):
        import app.main as main_module

        return main_module.ExecuteResult(
            exit_code=0, stdout="ok", stderr="", dataset_dir="/d"
        )

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
        # D11: host fake session cannot create /run — see WrapperPathFunctionalTest.
        self._saved_control_root = os.environ.get("AF_TASK_CONTROL_ROOT")
        os.environ["AF_TASK_CONTROL_ROOT"] = str(self.root / "task-control")

    def tearDown(self):
        if self._saved_control_root is None:
            os.environ.pop("AF_TASK_CONTROL_ROOT", None)
        else:
            os.environ["AF_TASK_CONTROL_ROOT"] = self._saved_control_root
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

if __name__ == "__main__":
    unittest.main()
