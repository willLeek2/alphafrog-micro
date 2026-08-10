# === work-package-C (ccqwen) ===
"""P0-4 (codex 03b4d034 / d384119d): UID privilege separation test tiers.

Four tiers, cheapest first:

1. ``ChildIdentityParseTest`` — the PURE accept/reject matrix of
   ``app.child_identity.parse_child_spec`` (username form resolved via pwd,
   ``uid:gid`` numeric form, every malformed/insecure shape rejected with a
   categorical §18 message that never echoes the raw spec).
2. ``RunnerChildIdentityGateTest`` — the host-side runner gate
   ``app.sandbox_runner._validate_runner_child_identity`` (root without a
   usable identity refuses fail-closed; non-root unset keeps dev-mode
   same-UID; garbage — including uid OR gid zero — always fails closed;
   username specs pass on syntax alone: NO host-side passwd lookup, codex
   087da672).  Plus ``WrapperCommandExportTest``: the runner exports the
   spec VERBATIM into the container exec command.
3. ``CaptureReadbackFromFdsTest`` — the FD-pinned readback entry point
   ``app.capture_reader.read_capture_files_from_fds`` (caps/joint budget
   enforced BEFORE the first read byte, unknown names / non-int fds / dead
   fds / non-regular fds rejected fail-closed, fds stay caller-owned).
4. ``WrapperIdentityGateDevTest`` — the wrapper subprocess gate in DEV mode
   (non-root host): a malformed identity fails closed with NO child spawned,
   a usable identity (the current user) runs to a normal envelope.  The
   root-side refusal and the actual privilege drop need a root wrapper and
   are covered by the docker-gated class below.
5. ``PreexecDropDisciplineTest`` — the preexec privilege-drop discipline,
   pinned in-process (codex 02953ca7): the EXACT syscall order
   ``setgroups([]) -> prctl(PR_SET_NO_NEW_PRIVS,1) -> prctl(PR_CAP_AMBIENT,
   CLEAR_ALL) -> setgid -> setuid -> capset(empty) -> /proc/self/status
   verification``, capset failure fails closed BEFORE verification, and a
   residual-capability verification fails closed too.

Docker-gated tier (``AF_RUN_DOCKER_TESTS=1``, image ``AF_SANDBOX_IMAGE``):
``UidIsolationIntegrationTest`` drives the REAL root wrapper in-container —
(a) root with the identity unset fails the task closed before anything runs;
(b-e) with ``AF_SANDBOX_CHILD_USER=nobody`` the child runs unprivileged
(write attempts on root-owned state and on the parent process are denied,
euid/egid/groups/PR_GET_NO_NEW_PRIVS report a non-root locked-down child)
and the fd-pinned readback survives the child renaming/destroying the
capture directory.  ``ProcessTreeCleanupIntegrationTest`` additionally keeps
the live setsid + closed-fds grandchild regression: only a successful
pre-spawn ``PR_SET_CHILD_SUBREAPER`` keeps such an escapee enumerable, so
the sweep must still kill it (codex 02953ca7).  Same gating convention as
``tests.test_sandbox_output_limits_integration`` (spec §16.2).
"""

from __future__ import annotations

import base64
import contextlib
import importlib
import json
import os
import pwd
import shlex
import subprocess
import sys
import tempfile
import time
import types
import unittest
from pathlib import Path
from unittest import mock

SERVICE_ROOT = Path(__file__).resolve().parents[1]

DOCKER_TESTS_ENABLED = os.environ.get("AF_RUN_DOCKER_TESTS") == "1"

if not DOCKER_TESTS_ENABLED:
    # Host-runnable llm_sandbox stub (repo convention, mirrors the other
    # non-docker test modules).  Skipped when the docker tier is enabled so
    # the docker-gated classes below import the REAL stack in setUp.
    _llm_sandbox = types.ModuleType("llm_sandbox")
    _llm_sandbox.SandboxSession = object
    _llm_sandbox_exceptions = types.ModuleType("llm_sandbox.exceptions")
    _llm_sandbox_exceptions.SandboxTimeoutError = TimeoutError
    sys.modules.setdefault("llm_sandbox", _llm_sandbox)
    sys.modules.setdefault("llm_sandbox.exceptions", _llm_sandbox_exceptions)

# Shape mirrors the §7.1 effectiveOutputLimits example (values are test-local).
DEV_LIMITS = {
    "stdoutMaxBytes": 1024 * 1024,
    "stderrMaxBytes": 256 * 1024,
    "recordChannelMaxBytes": 256 * 1024,
    "recordChannelMaxRecords": 128,
}


class ChildIdentityParseTest(unittest.TestCase):
    """The pure accept/reject matrix of ``parse_child_spec`` (P0-4)."""

    def setUp(self) -> None:
        self.child_identity = importlib.import_module("app.child_identity")

    def _reject(self, spec) -> None:
        with self.assertRaises(self.child_identity.ChildIdentityError):
            self.child_identity.parse_child_spec(spec)

    # ------------------------------------------------------------------
    # accepted forms
    # ------------------------------------------------------------------

    def test_numeric_uid_gid_form_is_accepted(self) -> None:
        parse = self.child_identity.parse_child_spec
        self.assertEqual(parse("1000:1000"), (1000, 1000))
        self.assertEqual(parse("65534:65534"), (65534, 65534))
        self.assertEqual(parse("1:2"), (1, 2))

    def test_username_form_resolves_against_passwd(self) -> None:
        # The CURRENT user exists on every host this suite runs on and is
        # non-root in CI/dev, so this stays portable without fixtures.
        entry = pwd.getpwuid(os.getuid())
        self.assertNotEqual(entry.pw_uid, 0)
        parsed = self.child_identity.parse_child_spec(entry.pw_name)
        self.assertEqual(parsed, (entry.pw_uid, entry.pw_gid))

    # ------------------------------------------------------------------
    # rejected forms
    # ------------------------------------------------------------------

    def test_non_string_and_empty_specs_are_rejected(self) -> None:
        for spec in (None, 1000, b"1000:1000", ["1000:1000"], ""):
            with self.subTest(spec=spec):
                self._reject(spec)

    def test_whitespace_specs_are_rejected(self) -> None:
        for spec in ("   ", "\t", " 1000:1000", "1000:1000 ", " nobody"):
            with self.subTest(spec=spec):
                self._reject(spec)

    def test_control_characters_are_rejected(self) -> None:
        for spec in ("1000:100\n0", "nob\x7fody", "a\tb"):
            with self.subTest(spec=spec):
                self._reject(spec)

    def test_malformed_numeric_specs_are_rejected(self) -> None:
        for spec in (
            ":",
            "1000:",
            ":1000",
            "1:2:3",
            "1a:2",
            "-1:2",
            "+1:2",
            "1.0:2",
            "0x10:2",
            "١٠٠٠:١٠٠٠",  # unicode digits are not passwd digits
        ):
            with self.subTest(spec=spec):
                self._reject(spec)

    def test_uid_zero_is_rejected_in_both_forms(self) -> None:
        self._reject("0:1000")
        self._reject("0:0")
        # "root" exists with uid 0 on every POSIX host this suite targets.
        self._reject("root")

    def test_gid_zero_is_rejected_in_both_forms(self) -> None:
        # codex 691341d2: a fixed non-privileged identity needs BOTH a
        # nonzero uid AND a nonzero primary gid (gid 0 is root's group).
        for spec in ("1000:0", "1:0", "65534:0"):
            with self.subTest(spec=spec):
                self._reject(spec)
        # Username form: a passwd entry whose primary gid is 0 is rejected
        # even though the uid itself is non-root.
        fake_entry = pwd.struct_passwd(
            ("af_gid0_user", "x", 1000, 0, "", "/nonexistent", "/bin/sh")
        )
        with mock.patch.object(
            self.child_identity.pwd, "getpwnam", return_value=fake_entry
        ):
            self._reject("af_gid0_user")

    def test_nonexistent_username_is_rejected(self) -> None:
        self._reject("af_no_such_user_zz_424242")

    def test_error_message_never_echoes_the_raw_spec(self) -> None:
        # §18: the spec is untrusted input; diagnostics are categorical.
        secret = "ZZ-SECRET-SPEC-9f3k"
        with self.assertRaises(
            self.child_identity.ChildIdentityError
        ) as caught:
            self.child_identity.parse_child_spec(secret)
        self.assertNotIn(secret, str(caught.exception))
        self.assertIn("AF_SANDBOX_CHILD_USER rejected", str(caught.exception))


class RunnerChildIdentityGateTest(unittest.TestCase):
    """The host-side gate ``_validate_runner_child_identity`` (P0-4).

    SYNTAX-ONLY validation: NO host OS lookups — the target container's
    passwd database is authoritative and resolves the spec twice in-container
    (chown snippet + wrapper pre-spawn gate, codex 087da672).  Takes an
    explicit ``euid`` so the root branch is testable without root.
    """

    def setUp(self) -> None:
        self.runner = importlib.import_module("app.sandbox_runner")

    def test_root_without_identity_refuses_fail_closed(self) -> None:
        with self.assertRaises(RuntimeError) as caught:
            self.runner._validate_runner_child_identity(None, 0)
        self.assertIn("AF_SANDBOX_CHILD_USER", str(caught.exception))

    def test_non_root_without_identity_keeps_dev_mode_same_uid(self) -> None:
        self.assertIsNone(
            self.runner._validate_runner_child_identity(None, 501)
        )

    def test_valid_numeric_spec_passes_the_gate_for_any_euid(self) -> None:
        # The gate validates and returns nothing — the ORIGINAL spec travels
        # verbatim into the container, where it is resolved.
        self.assertIsNone(
            self.runner._validate_runner_child_identity("1000:1000", 501)
        )
        self.assertIsNone(
            self.runner._validate_runner_child_identity("1000:1000", 0)
        )

    def test_garbage_spec_fails_closed_even_non_root(self) -> None:
        # "1000:0" / "0:1000": uid OR gid zero both fail closed (691341d2).
        for spec in ("  bad  ", "1:2:3", "0:0", "0:1000", "1000:0"):
            with self.subTest(spec=spec):
                with self.assertRaises(RuntimeError):
                    self.runner._validate_runner_child_identity(spec, 501)

    def test_username_spec_passes_without_any_host_passwd_lookup(self) -> None:
        # codex 087da672 regression: the service runs in a DIFFERENT
        # username namespace than the target container, so the host gate
        # must NOT consult the host's passwd — a username that exists only
        # in the target image still passes here and is resolved in the
        # container (the lookup below would raise if it were attempted).
        child_identity = importlib.import_module("app.child_identity")

        def _forbidden_lookup(name):
            raise AssertionError("host-side passwd lookup is forbidden")

        with mock.patch.object(
            child_identity.pwd, "getpwnam", side_effect=_forbidden_lookup
        ):
            self.assertIsNone(
                self.runner._validate_runner_child_identity(
                    "af_target_only_user", 501
                )
            )
            self.assertIsNone(
                self.runner._validate_runner_child_identity(
                    "af_target_only_user", 0
                )
            )


class WrapperCommandExportTest(unittest.TestCase):
    """P0-4 config chain: the runner exports ``AF_SANDBOX_CHILD_USER``
    VERBATIM into the in-container exec command — the exact bytes the
    service received are the exact bytes the wrapper's gate sees."""

    def setUp(self) -> None:
        self.runner = importlib.import_module("app.sandbox_runner")

    def test_child_user_exported_verbatim_and_shell_quoted(self) -> None:
        config = types.SimpleNamespace(workdir="/sandbox")
        for spec in ("nobody", "10000:10001"):
            with self.subTest(spec=spec):
                command = self.runner._wrapper_run_command(
                    config,
                    "/sandbox/runs/task-1",
                    "/sandbox/.sandbox-venv/bin/python",
                    child_spec=spec,
                )
                # The whole script is shell-quoted into `sh -lc`; unquote it.
                script = shlex.split(command)[2]
                export_lines = [
                    line
                    for line in script.splitlines()
                    if line.startswith("export AF_SANDBOX_CHILD_USER=")
                ]
                self.assertEqual(len(export_lines), 1)
                # Round-trip: unquoting the export yields the ORIGINAL spec.
                value = shlex.split(export_lines[0])[1].split("=", 1)[1]
                self.assertEqual(value, spec)

    def test_no_export_without_an_identity(self) -> None:
        config = types.SimpleNamespace(workdir="/sandbox")
        command = self.runner._wrapper_run_command(
            config,
            "/sandbox/runs/task-1",
            "/sandbox/.sandbox-venv/bin/python",
            child_spec=None,
        )
        self.assertNotIn("AF_SANDBOX_CHILD_USER", command)


class CaptureReadbackFromFdsTest(unittest.TestCase):
    """FD-pinned readback: ``read_capture_files_from_fds`` (P0-4 / P0-03b4d034).

    The wrapper pre-opens the capture files before the child spawns; this
    entry point must enforce every cap BEFORE the first read byte and must
    never close the caller-owned fds.
    """

    def setUp(self) -> None:
        self.reader = importlib.import_module("app.capture_reader")
        self._tmp = tempfile.TemporaryDirectory(prefix="af-fd-readback-")
        self.dir = Path(self._tmp.name)
        self._fds: list[int] = []

    def tearDown(self) -> None:
        for fd in self._fds:
            try:
                os.close(fd)
            except OSError:
                pass
        self._tmp.cleanup()

    def _fd(self, name: str, content: bytes) -> int:
        path = self.dir / name
        path.write_bytes(content)
        fd = os.open(str(path), os.O_RDWR)
        self._fds.append(fd)
        return fd

    def _limits(self, **overrides):
        limits = {
            "stdout_max_bytes": 1024,
            "stderr_max_bytes": 1024,
            "record_channel_max_bytes": 512,
            "record_channel_max_records": 8,
        }
        limits.update(overrides)
        return limits

    def test_happy_path_roundtrips_present_files_only(self) -> None:
        summary = json.dumps({"exitCode": 0}).encode("utf-8")
        fds = {
            "capture-result.json": self._fd("capture-result.json", summary),
            "stdout.bin": self._fd("stdout.bin", b"hello\n"),
            "finance-records.jsonl": self._fd(
                "finance-records.jsonl", b'{"a":1}\n'
            ),
        }
        envelope = self.reader.read_capture_files_from_fds(
            fds, **self._limits()
        )
        self.assertEqual(
            set(envelope["files"]),
            {"capture-result.json", "stdout.bin", "finance-records.jsonl"},
        )
        self.assertEqual(
            base64.b64decode(envelope["files"]["stdout.bin"]), b"hello\n"
        )
        self.assertEqual(
            base64.b64decode(envelope["files"]["capture-result.json"]),
            summary,
        )
        # Caller-owned fds: the readback must NOT have closed them.
        for fd in fds.values():
            os.fstat(fd)  # raises EBADF if the reader closed it

    def test_unknown_name_rejected_without_echoing_it(self) -> None:
        fd = self._fd("stdout.bin", b"x")
        evil = "evil-exfil.json"
        with self.assertRaises(ValueError) as caught:
            self.reader.read_capture_files_from_fds(
                {evil: fd}, **self._limits()
            )
        self.assertNotIn(evil, str(caught.exception))  # §18

    def test_non_integer_fd_rejected(self) -> None:
        fd = self._fd("stdout.bin", b"x")
        for bad in ("3", None, 3.5, True):
            with self.subTest(bad=bad):
                with self.assertRaises(ValueError):
                    self.reader.read_capture_files_from_fds(
                        {"stdout.bin": bad}, **self._limits()
                    )
        os.fstat(fd)  # rejected calls must not have closed the good fd

    def test_dead_fd_rejected(self) -> None:
        path = self.dir / "stdout.bin"
        path.write_bytes(b"x")
        fd = os.open(str(path), os.O_RDWR)
        os.close(fd)
        with self.assertRaises(ValueError):
            self.reader.read_capture_files_from_fds(
                {"stdout.bin": fd}, **self._limits()
            )

    def test_non_regular_fd_rejected(self) -> None:
        read_fd, write_fd = os.pipe()
        self._fds.extend((read_fd, write_fd))
        with self.assertRaises(ValueError):
            self.reader.read_capture_files_from_fds(
                {"stdout.bin": read_fd}, **self._limits()
            )

    def test_cap_exceeded_fails_closed(self) -> None:
        fds = {"stdout.bin": self._fd("stdout.bin", b"A" * 2048)}
        with self.assertRaises(ValueError) as caught:
            self.reader.read_capture_files_from_fds(
                fds, **self._limits(stdout_max_bytes=1024)
            )
        message = str(caught.exception)
        self.assertIn("stdout.bin", message)
        self.assertIn("1024", message)

    def test_joint_record_channel_budget_enforced(self) -> None:
        # 300 + 300 > 512 joint budget -> rejected.
        fds = {
            "finance-records.jsonl": self._fd(
                "finance-records.jsonl", b"y" * 300
            ),
            "finance-records-unknown-marker.jsonl": self._fd(
                "finance-records-unknown-marker.jsonl", b"z" * 300
            ),
        }
        with self.assertRaises(ValueError):
            self.reader.read_capture_files_from_fds(fds, **self._limits())

    def test_record_count_limit_enforced(self) -> None:
        lines = b"".join(b'{"i":%d}\n' % i for i in range(9))
        fds = {"finance-records.jsonl": self._fd("finance-records.jsonl", lines)}
        with self.assertRaises(ValueError):
            self.reader.read_capture_files_from_fds(
                fds, **self._limits(record_channel_max_records=8)
            )

    def test_limits_are_validated(self) -> None:
        fd = self._fd("stdout.bin", b"x")
        for bad_limits in (
            {"stdout_max_bytes": -1},
            {"stderr_max_bytes": True},
            {"record_channel_max_bytes": "512"},
            {"record_channel_max_records": None},
        ):
            with self.subTest(bad_limits=bad_limits):
                with self.assertRaises(ValueError):
                    self.reader.read_capture_files_from_fds(
                        {"stdout.bin": fd}, **self._limits(**bad_limits)
                    )

    def test_fd_map_must_be_a_dict(self) -> None:
        with self.assertRaises(ValueError):
            self.reader.read_capture_files_from_fds(
                [("stdout.bin", 3)], **self._limits()
            )


class WrapperIdentityGateDevTest(unittest.TestCase):
    """The wrapper's identity gate in DEV mode (non-root host, macOS-OK).

    A malformed identity must fail closed with NO child spawned; a usable
    identity (the current user, numeric or username form) runs to a normal
    bounded envelope.  The root-side refusal needs a root wrapper and is
    covered by the docker-gated integration class.
    """

    def setUp(self) -> None:
        importlib.import_module("app.bounded_exec_wrapper")
        self._tmp = tempfile.TemporaryDirectory(prefix="af-uid-gate-dev-")
        self.task_dir = Path(self._tmp.name).resolve()

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def _spawn_marker(self) -> Path:
        return self.task_dir / "child_spawned.flag"

    def _run_wrapper(self, child_user) -> subprocess.CompletedProcess:
        script = self.task_dir / "user_code.py"
        script.write_text(
            "from pathlib import Path\n"
            f"Path({str(self._spawn_marker())!r}).write_text('spawned')\n"
            "print('dev-child-ran')\n",
            encoding="utf-8",
        )
        runtime_env = self.task_dir / "runtime-environment.json"
        runtime_env.write_text("{}", encoding="utf-8")
        # D15 §4.2 (Scenario B): wrapper now requires taskWorkspace +
        # taskEnvironment (AF_TASK_* isolation moved out of the global
        # sitecustomize.py).
        task_workspace = str(self.task_dir)
        payload = {
            "scriptPath": str(script),
            "timeoutSeconds": 30,
            "effectiveOutputLimits": dict(DEV_LIMITS),
            "runtimeEnvironmentPath": str(runtime_env),
            "taskWorkspace": task_workspace,
            "taskEnvironment": {
                "AF_TASK_WORKSPACE": task_workspace,
                "AF_TASK_ARTIFACT_DIR": f"{task_workspace}/artifacts",
                "AF_TASK_TMP_DIR": f"{task_workspace}/tmp",
                "AF_TASK_METRICS_PATH": f"{task_workspace}/metrics/x.jsonl",
            },
            "loaderPythonPath": task_workspace,
        }
        input_path = self.task_dir / "wrapper-input.json"
        input_path.write_text(json.dumps(payload), encoding="utf-8")
        env = dict(os.environ)
        env.pop("AF_SANDBOX_CHILD_USER", None)
        if child_user is not None:
            env["AF_SANDBOX_CHILD_USER"] = child_user
        return subprocess.run(
            [sys.executable, "-m", "app.bounded_exec_wrapper", str(input_path)],
            cwd=SERVICE_ROOT,
            capture_output=True,
            env=env,
            timeout=120,
        )

    def test_unset_identity_stays_dev_mode_same_uid(self) -> None:
        completed = self._run_wrapper(None)
        self.assertEqual(completed.returncode, 0, repr(completed.stderr[:512]))
        self.assertTrue(self._spawn_marker().exists())
        envelope = json.loads(completed.stdout.decode("utf-8"))
        self.assertIn("stdout.bin", envelope["files"])

    def test_malformed_identity_fails_closed_without_spawning(self) -> None:
        for spec in ("  bad spec  ", "1:2:3", "af_no_such_user_zz_424242"):
            with self.subTest(spec=spec):
                self.tearDown()
                self.setUp()  # fresh task dir per spec
                completed = self._run_wrapper(spec)
                self.assertNotEqual(completed.returncode, 0)
                self.assertEqual(completed.stdout, b"")  # PIN 2 failure path
                self.assertLess(len(completed.stderr), 4096)  # §18 short
                self.assertIn("AF_SANDBOX_CHILD_USER", completed.stderr.decode())
                self.assertNotIn(spec, completed.stderr.decode())  # no echo
                self.assertFalse(
                    self._spawn_marker().exists(),
                    "the child must NOT be spawned when the identity is "
                    "unusable",
                )

    def test_uid_zero_identity_fails_closed_without_spawning(self) -> None:
        completed = self._run_wrapper("0:0")
        self.assertNotEqual(completed.returncode, 0)
        self.assertEqual(completed.stdout, b"")
        self.assertFalse(self._spawn_marker().exists())

    def test_numeric_same_identity_runs(self) -> None:
        spec = f"{os.getuid()}:{os.getgid()}"
        completed = self._run_wrapper(spec)
        self.assertEqual(completed.returncode, 0, repr(completed.stderr[:512]))
        self.assertTrue(self._spawn_marker().exists())

    def test_username_same_identity_runs(self) -> None:
        spec = pwd.getpwuid(os.getuid()).pw_name
        completed = self._run_wrapper(spec)
        self.assertEqual(completed.returncode, 0, repr(completed.stderr[:512]))
        self.assertTrue(self._spawn_marker().exists())


class PreexecDropDisciplineTest(unittest.TestCase):
    """The preexec privilege-drop discipline, pinned in-process.

    Codex 02953ca7 stop conditions:

    * the drop follows the EXACT frozen syscall order — ``setgroups([]) ->
      prctl(PR_SET_NO_NEW_PRIVS, 1) -> prctl(PR_CAP_AMBIENT, CLEAR_ALL) ->
      setgid -> setuid -> capset(all-zero)`` — and only THEN reads
      ``/proc/self/status`` back (verification last, never earlier);
    * the capability drop is EXPLICIT: ``capset`` writes empty
      inheritable/permitted/effective sets after setuid instead of relying
      on setuid's implicit clearing; a capset refusal fails closed BEFORE
      any verification (no exec, no summary);
    * a verification that still sees capabilities fails closed too.

    Linux is simulated by patching ``sys.platform`` and the module's
    ``_libc`` to a recording fake; the fake ``/proc/self/status`` is served
    through a mocked ``open``.  Runs on any host (macOS dev included).
    """

    UID = 1000
    GID = 10001
    _CLEAN_STATUS = (
        "Name:\taf-child\n"
        "CapInh:\t0000000000000000\n"
        "CapPrm:\t0000000000000000\n"
        "CapEff:\t0000000000000000\n"
        "CapBnd:\t000001ffffffffff\n"
        "CapAmb:\t0000000000000000\n"
        "NoNewPrivs:\t1\n"
    )

    def setUp(self) -> None:
        self.wrapper = importlib.import_module("app.bounded_exec_wrapper")
        self.events: list = []

    def _recording_libc(self, capset_result: int = 0):
        events = self.events

        class RecordingLibc:
            def prctl(self, option, arg, *_rest):
                events.append(("prctl", option, arg))
                return 0

            def capset(self, header, data):
                events.append("capset")
                return capset_result

        return RecordingLibc()

    def _run_preexec(self, *, capset_result: int = 0, status_text=None):
        """Run the root-path preexec closure under full recording; returns
        ``(exception_or_None, open_mock)``."""
        if status_text is None:
            status_text = self._CLEAN_STATUS
        preexec = self.wrapper._make_preexec((self.UID, self.GID))
        self.assertIsNotNone(preexec)
        opened = mock.mock_open(read_data=status_text)
        wrapper = self.wrapper
        with contextlib.ExitStack() as stack:
            stack.enter_context(
                mock.patch.object(wrapper.sys, "platform", "linux")
            )
            stack.enter_context(
                mock.patch.object(
                    wrapper,
                    "_libc",
                    return_value=self._recording_libc(capset_result),
                )
            )
            # First geteuid call selects the root path, second (inside the
            # verification) must see the dropped identity.
            stack.enter_context(
                mock.patch.object(
                    wrapper.os, "geteuid", side_effect=[0, self.UID]
                )
            )
            stack.enter_context(
                mock.patch.object(
                    wrapper.os, "getegid", return_value=self.GID
                )
            )
            stack.enter_context(
                mock.patch.object(
                    wrapper.os,
                    "setgroups",
                    side_effect=lambda groups: self.events.append(
                        ("setgroups", tuple(groups))
                    ),
                )
            )
            stack.enter_context(
                mock.patch.object(
                    wrapper.os,
                    "setgid",
                    side_effect=lambda gid: self.events.append(
                        ("setgid", gid)
                    ),
                )
            )
            stack.enter_context(
                mock.patch.object(
                    wrapper.os,
                    "setuid",
                    side_effect=lambda uid: self.events.append(
                        ("setuid", uid)
                    ),
                )
            )
            stack.enter_context(mock.patch("builtins.open", opened))
            try:
                preexec()
            except Exception as exc:  # noqa: BLE001 — pinned fail-closed types
                return exc, opened
        return None, opened

    def test_preexec_drop_follows_the_exact_frozen_syscall_order(self) -> None:
        exc, opened = self._run_preexec()
        self.assertIsNone(exc, f"clean drop must not raise: {exc!r}")
        self.assertEqual(
            self.events,
            [
                ("setgroups", ()),
                ("prctl", self.wrapper._PR_SET_NO_NEW_PRIVS, 1),
                (
                    "prctl",
                    self.wrapper._PR_CAP_AMBIENT,
                    self.wrapper._PR_CAP_AMBIENT_CLEAR_ALL,
                ),
                ("setgid", self.GID),
                ("setuid", self.UID),
                "capset",
            ],
            "the privilege drop must follow the frozen order exactly, "
            "with the explicit capset AFTER setuid and the /proc "
            "verification last",
        )
        # The verification read happened exactly once, after capset.
        opened.assert_called_once_with(
            "/proc/self/status", "r", encoding="ascii"
        )

    def test_capset_failure_fails_closed_before_verification(self) -> None:
        exc, opened = self._run_preexec(capset_result=-1)
        self.assertIsInstance(exc, OSError)
        self.assertEqual(
            self.events[-1],
            "capset",
            "the drop must stop AT the failed capset — no verification, "
            "no exec",
        )
        opened.assert_not_called()

    def test_residual_capability_fails_the_verification_closed(self) -> None:
        dirty_status = self._CLEAN_STATUS.replace(
            "CapEff:\t0000000000000000", "CapEff:\t0000000000000004"
        )
        exc, _opened = self._run_preexec(status_text=dirty_status)
        self.assertIsInstance(exc, self.wrapper.ChildIdentityError)


@unittest.skipUnless(
    DOCKER_TESTS_ENABLED,
    "real Docker integration tests; enable with AF_RUN_DOCKER_TESTS=1 (spec §16.2)",
)
class UidIsolationIntegrationTest(unittest.TestCase):
    """P0-4 (a)-(e) against the REAL root wrapper inside the container.

    The container runs the wrapper as root; ``AF_SANDBOX_CHILD_USER`` names
    the unprivileged identity the child must drop to.  Heavy imports happen
    in setUp so the module still loads — and skips cleanly — without Docker.
    """

    def setUp(self) -> None:
        from app.child_identity import CHILD_USER_ENV_NAME
        from app.config import SandboxConfig
        from app.sandbox_runner import (
            create_sandbox_session,
            get_session_container_id,
            run_in_open_session,
        )

        self._env_name = CHILD_USER_ENV_NAME
        self._create_session = create_sandbox_session
        self._container_id = get_session_container_id
        self._run = run_in_open_session

        self._tmp = tempfile.TemporaryDirectory(prefix="af-uid-isolation-it-")
        root = Path(self._tmp.name)
        data_dir = root / "data"
        (data_dir / "dataset-uid").mkdir(parents=True)
        workspace_root = root / "runs"
        workspace_root.mkdir()
        self.config = SandboxConfig(
            data_dir=data_dir,
            max_concurrency=1,
            execution_timeout_seconds=90.0,
            memory_limit="512m",
            memswap_limit="512m",
            docker_backend="docker",
            workdir="/sandbox",
            log_level="INFO",
            sandbox_image=os.environ.get(
                "AF_SANDBOX_IMAGE", "alphafrog-sandbox-runtime:latest"
            ),
            skip_environment_setup=True,
            preinstalled_libraries=frozenset(),
            container_max_concurrency=1,
            pool_enabled=False,
            pool_min_size=0,
            pool_max_size=1,
            pool_acquire_timeout_seconds=30.0,
            pool_idle_timeout_seconds=None,
            pool_max_container_uses=None,
            workspace_root=str(workspace_root),
            compat_input_path_enabled=True,
        )

    def tearDown(self) -> None:
        if hasattr(self, "_tmp"):
            self._tmp.cleanup()

    def _env_without_child_user(self) -> dict:
        env = dict(os.environ)
        env.pop(self._env_name, None)
        return env

    def _run_bounded(self, session, task_id, code, container_id):
        return self._run(
            self.config,
            session,
            task_id,
            "dataset-uid",
            None,
            code,
            None,
            None,
            60.0,
            container_id=container_id,
            pool_enabled=False,
            effective_output_limits=dict(DEV_LIMITS),
        )

    def test_a_root_without_identity_fails_the_task_closed(self) -> None:
        """(a) root wrapper + AF_SANDBOX_CHILD_USER unset: the runner gate
        refuses BEFORE any staging — the task fails closed, nothing runs."""
        with mock.patch.dict(
            os.environ, self._env_without_child_user(), clear=True
        ):
            session = self._create_session(
                self.config, execution_timeout=60.0
            )
            try:
                container_id = self._container_id(session)
                with self.assertRaises(RuntimeError) as caught:
                    self._run_bounded(
                        session,
                        "task-root-refusal",
                        "print('must never run')\n",
                        container_id,
                    )
                self.assertIn(self._env_name, str(caught.exception))
            finally:
                session.close()

    def test_b_through_e_child_runs_unprivileged_and_readback_survives(
        self,
    ) -> None:
        """(b)-(e) one bounded run as ``nobody`` probes the whole drop:
        writes on root-owned state and on the parent process are denied,
        the child reports a non-root locked-down identity, and renaming the
        capture directory cannot disturb the fd-pinned readback."""
        code = (
            "import ctypes, os, sys\n"
            "print('EUID=%d' % os.geteuid())\n"
            "print('EGID=%d' % os.getegid())\n"
            "print('GROUPS=%r' % (os.getgroups(),))\n"
            "libc = ctypes.CDLL(None, use_errno=True)\n"
            "print('NNP=%d' % libc.prctl(39))\n"  # PR_GET_NO_NEW_PRIVS
            # (b) write attempts on root-owned state / parent process ------
            "denied = 0\n"
            "for target in ('bounded-wrapper/pwn.txt', '/root/pwn.txt'):\n"
            "    try:\n"
            "        with open(target, 'w') as handle:\n"
            "            handle.write('pwned')\n"
            "    except PermissionError:\n"
            "        denied += 1\n"
            "try:\n"
            "    with open('/proc/%d/mem' % os.getppid(), 'wb') as handle:\n"
            "        handle.write(b'pwned')\n"
            "except (PermissionError, OSError):\n"
            "    denied += 1\n"
            "print('ROOT_WRITES_DENIED=%d' % denied)\n"
            # (e) caps floor (codex 76ee7296): probe the child's OWN kernel
            # truth through the capture channel — all four capability sets
            # must read zero and NoNewPrivs must be 1.
            "status = open('/proc/self/status', encoding='ascii').read()\n"
            "for line in status.splitlines():\n"
            "    if line.startswith(('CapInh:', 'CapPrm:', 'CapEff:',\n"
            "                        'CapAmb:', 'NoNewPrivs:')):\n"
            "        print(line)\n"
            # (d) rename the capture dir: fd-pinned readback must not care --
            "try:\n"
            "    os.rename('capture', 'capture.evil')\n"
            "    print('CAPTURE_DIR_RENAMED=1')\n"
            "except OSError as exc:\n"
            "    print('CAPTURE_DIR_RENAMED=0 %s' % type(exc).__name__)\n"
            "print('genuine-output-line')\n"
        )
        with mock.patch.dict(os.environ, {self._env_name: "nobody"}):
            session = self._create_session(
                self.config, execution_timeout=60.0
            )
            try:
                container_id = self._container_id(session)
                result = self._run_bounded(
                    session, "task-uid-drop", code, container_id
                )
            finally:
                session.close()

        self.assertEqual(
            result["exit_code"], 0, "the unprivileged run still succeeds"
        )
        stdout = result["stdout"]
        self.assertIn("genuine-output-line", stdout)
        # (e) locked-down non-root identity --------------------------------
        euid_line = [
            line for line in stdout.splitlines() if line.startswith("EUID=")
        ]
        self.assertTrue(euid_line, f"no identity probe in {stdout!r}")
        euid = int(euid_line[0].split("=", 1)[1])
        self.assertNotEqual(euid, 0, "child must not stay root")
        self.assertIn("GROUPS=[]", stdout, "supplementary groups not dropped")
        self.assertIn("NNP=1", stdout, "PR_SET_NO_NEW_PRIVS not applied")
        # (e) zero capabilities in ALL four sets, probed from the child's
        # own /proc/self/status through the capture channel (codex 76ee7296;
        # the wrapper additionally self-verifies this in preexec and fails
        # closed on any mismatch).
        for field in ("CapInh", "CapPrm", "CapEff", "CapAmb"):
            lines = [
                line
                for line in stdout.splitlines()
                if line.startswith(field + ":")
            ]
            self.assertTrue(lines, f"no {field} probe in {stdout!r}")
            value = lines[0].split(":", 1)[1].strip()
            self.assertEqual(int(value, 16), 0, f"{field} not zero: {value}")
        nnp_lines = [
            line
            for line in stdout.splitlines()
            if line.startswith("NoNewPrivs:")
        ]
        self.assertTrue(nnp_lines, f"no NoNewPrivs probe in {stdout!r}")
        self.assertEqual(nnp_lines[0].split(":", 1)[1].strip(), "1")
        # (b) every root-owned write attempt denied ------------------------
        denied_line = [
            line
            for line in stdout.splitlines()
            if line.startswith("ROOT_WRITES_DENIED=")
        ]
        self.assertTrue(denied_line)
        self.assertEqual(
            denied_line[0], "ROOT_WRITES_DENIED=3", "a root write succeeded"
        )

    def test_d_capture_dir_destroyed_leaves_readback_intact(self) -> None:
        """(d) focused variant: the child unlinks capture files outright;
        the pre-opened fds still read back the genuine bytes."""
        code = (
            "import os\n"
            "for name in os.listdir('capture'):\n"
            "    try:\n"
            "        os.unlink(os.path.join('capture', name))\n"
            "    except OSError:\n"
            "        pass\n"
            "print('survived-unlink')\n"
        )
        with mock.patch.dict(os.environ, {self._env_name: "nobody"}):
            session = self._create_session(
                self.config, execution_timeout=60.0
            )
            try:
                container_id = self._container_id(session)
                result = self._run_bounded(
                    session, "task-unlink-capture", code, container_id
                )
            finally:
                session.close()
        self.assertEqual(result["exit_code"], 0)
        self.assertIn("survived-unlink", result["stdout"])


@unittest.skipUnless(
    DOCKER_TESTS_ENABLED,
    "real Docker integration tests; enable with AF_RUN_DOCKER_TESTS=1 (spec §16.2)",
)
class SecurityFloorIntegrationTest(unittest.TestCase):
    """P0-5 (codex 5777cda8) live variant: one-task-per-container floor.

    A bounded-path task MUST mark the container recycled regardless of its
    outcome; the caller destroys the session, and the NEXT task gets a
    DIFFERENT container that shows zero residual state from task one.
    """

    def setUp(self) -> None:
        from app.child_identity import CHILD_USER_ENV_NAME
        from app.config import SandboxConfig
        from app.sandbox_runner import (
            RECYCLE_REASON_SECURITY_FLOOR,
            create_sandbox_session,
            get_session_container_id,
            run_in_open_session,
        )

        self._env_name = CHILD_USER_ENV_NAME
        self._floor_reason = RECYCLE_REASON_SECURITY_FLOOR
        self._create_session = create_sandbox_session
        self._container_id = get_session_container_id
        self._run = run_in_open_session

        self._tmp = tempfile.TemporaryDirectory(prefix="af-sec-floor-it-")
        root = Path(self._tmp.name)
        data_dir = root / "data"
        (data_dir / "dataset-floor").mkdir(parents=True)
        workspace_root = root / "runs"
        workspace_root.mkdir()
        self.config = SandboxConfig(
            data_dir=data_dir,
            max_concurrency=1,
            execution_timeout_seconds=90.0,
            memory_limit="512m",
            memswap_limit="512m",
            docker_backend="docker",
            workdir="/sandbox",
            log_level="INFO",
            sandbox_image=os.environ.get(
                "AF_SANDBOX_IMAGE", "alphafrog-sandbox-runtime:latest"
            ),
            skip_environment_setup=True,
            preinstalled_libraries=frozenset(),
            container_max_concurrency=1,
            pool_enabled=False,
            pool_min_size=0,
            pool_max_size=1,
            pool_acquire_timeout_seconds=30.0,
            pool_idle_timeout_seconds=None,
            pool_max_container_uses=None,
            workspace_root=str(workspace_root),
            compat_input_path_enabled=True,
        )

    def tearDown(self) -> None:
        if hasattr(self, "_tmp"):
            self._tmp.cleanup()

    def test_bounded_task_always_recycles_and_next_container_is_clean(
        self,
    ) -> None:
        pollution = "/tmp/af-pollution-marker-package-c"
        task1_code = (
            "import os, subprocess, sys\n"
            f"open({pollution!r}, 'w').write('polluted')\n"
            "subprocess.Popen(\n"
            "    [sys.executable, '-c', 'import time; time.sleep(2)'],\n"
            "    start_new_session=True)\n"
            "print('task-one-ran')\n"
        )
        task2_code = (
            "import os\n"
            f"print('POLLUTION_PRESENT=%s' % os.path.exists({pollution!r}))\n"
        )
        env = dict(os.environ)
        env[self._env_name] = "nobody"
        with mock.patch.dict(os.environ, env, clear=True):
            # Task one: bounded path -> security floor recycle, whatever the
            # outcome.  (exit(0) success path: the floor is not conditional.)
            session1 = self._create_session(
                self.config, execution_timeout=60.0
            )
            try:
                container1 = self._container_id(session1)
                result1 = self._run(
                    self.config,
                    session1,
                    "task-floor-1",
                    "dataset-floor",
                    None,
                    task1_code,
                    None,
                    None,
                    60.0,
                    container_id=container1,
                    pool_enabled=False,
                    effective_output_limits=dict(DEV_LIMITS),
                )
            finally:
                session1.close()  # the caller destroys a recycled container

            self.assertEqual(result1["exit_code"], 0)
            self.assertTrue(result1["container_recycled"])
            self.assertEqual(result1["recycle_reason"], self._floor_reason)

            # Task two: a DIFFERENT container with zero residual state.
            session2 = self._create_session(
                self.config, execution_timeout=60.0
            )
            try:
                container2 = self._container_id(session2)
                self.assertNotEqual(
                    container1,
                    container2,
                    "the floor demands a fresh container for the next task",
                )
                result2 = self._run(
                    self.config,
                    session2,
                    "task-floor-2",
                    "dataset-floor",
                    None,
                    task2_code,
                    None,
                    None,
                    60.0,
                    container_id=container2,
                    pool_enabled=False,
                )
            finally:
                session2.close()
        self.assertEqual(result2["exit_code"], 0)
        self.assertIn("POLLUTION_PRESENT=False", result2["stdout"])


@unittest.skipUnless(
    DOCKER_TESTS_ENABLED,
    "real Docker integration tests; enable with AF_RUN_DOCKER_TESTS=1 (spec §16.2)",
)
class ProcessTreeCleanupIntegrationTest(unittest.TestCase):
    """P0-2 (codex b39f5e6b / 1d81ca85) live repro: a grandchild that
    outlives the child (the red baseline f319ad54: /bin/sleep holding the
    stdout pipe blocked the wrapper until the task timeout).  With the
    bounded sweep the wrapper returns PROMPTLY with the child's own exit
    code and the pre-kill output captured.
    """

    def setUp(self) -> None:
        from app.child_identity import CHILD_USER_ENV_NAME
        from app.config import SandboxConfig
        from app.sandbox_runner import (
            create_sandbox_session,
            get_session_container_id,
            run_in_open_session,
        )

        self._env_name = CHILD_USER_ENV_NAME
        self._create_session = create_sandbox_session
        self._container_id = get_session_container_id
        self._run = run_in_open_session

        self._tmp = tempfile.TemporaryDirectory(prefix="af-proctree-it-")
        root = Path(self._tmp.name)
        data_dir = root / "data"
        (data_dir / "dataset-tree").mkdir(parents=True)
        workspace_root = root / "runs"
        workspace_root.mkdir()
        self.config = SandboxConfig(
            data_dir=data_dir,
            max_concurrency=1,
            execution_timeout_seconds=90.0,
            memory_limit="512m",
            memswap_limit="512m",
            docker_backend="docker",
            workdir="/sandbox",
            log_level="INFO",
            sandbox_image=os.environ.get(
                "AF_SANDBOX_IMAGE", "alphafrog-sandbox-runtime:latest"
            ),
            skip_environment_setup=True,
            preinstalled_libraries=frozenset(),
            container_max_concurrency=1,
            pool_enabled=False,
            pool_min_size=0,
            pool_max_size=1,
            pool_acquire_timeout_seconds=30.0,
            pool_idle_timeout_seconds=None,
            pool_max_container_uses=None,
            workspace_root=str(workspace_root),
            compat_input_path_enabled=True,
        )

    def tearDown(self) -> None:
        if hasattr(self, "_tmp"):
            self._tmp.cleanup()

    def test_sleep_grandchild_returns_promptly_with_childs_exit_code(
        self,
    ) -> None:
        code = (
            "import subprocess, sys\n"
            "sys.stdout.write('before-spawn\\n')\n"
            "sys.stdout.flush()\n"
            "subprocess.Popen(['/bin/sleep', '300'])\n"
            "sys.exit(0)\n"
        )
        env = dict(os.environ)
        env[self._env_name] = "nobody"
        with mock.patch.dict(os.environ, env, clear=True):
            session = self._create_session(
                self.config, execution_timeout=60.0
            )
            try:
                container_id = self._container_id(session)
                started = time.monotonic()
                result = self._run(
                    self.config,
                    session,
                    "task-sleep-gc",
                    "dataset-tree",
                    None,
                    code,
                    None,
                    None,
                    60.0,
                    container_id=container_id,
                    pool_enabled=False,
                    effective_output_limits=dict(DEV_LIMITS),
                )
                elapsed = time.monotonic() - started
            finally:
                session.close()
        # The red baseline blocked until the 60s task timeout; the sweep
        # budget is a few seconds, so 30s is a generous ceiling.
        self.assertLess(
            elapsed,
            30.0,
            f"wrapper blocked {elapsed:.1f}s on the sleep grandchild",
        )
        self.assertEqual(result["exit_code"], 0)
        self.assertIn("before-spawn", result["stdout"])

    def test_setsid_pipe_closed_grandchild_is_still_swept(self) -> None:
        """codex 02953ca7 live regression: the hardest-to-see escapee.

        The grandchild escapes to its own session (``start_new_session``)
        AND closes every inherited capture pipe before exec — so BOTH sweep
        signals are blind to it: the drains hit EOF with the child, and no
        pipe-holder enumeration can find it.  Only the pre-spawn
        ``PR_SET_CHILD_SUBREAPER`` reparenting keeps it enumerable through
        ``/proc`` ppid chains; that is why the Linux path makes subreaper
        success a HARD spawn gate.  The run must return promptly, succeed,
        and leave NO survivor in the container.
        """
        code = (
            "import subprocess, sys\n"
            "sys.stdout.write('before-escape\\n')\n"
            "sys.stdout.flush()\n"
            "subprocess.Popen(\n"
            "    ['/bin/sh', '-c',\n"
            "     'exec 0<&- 1>&- 2>&-; exec /bin/sleep 300'],\n"
            "    start_new_session=True,\n"
            ")\n"
            "sys.exit(0)\n"
        )
        env = dict(os.environ)
        env[self._env_name] = "nobody"
        with mock.patch.dict(os.environ, env, clear=True):
            session = self._create_session(
                self.config, execution_timeout=60.0
            )
            try:
                container_id = self._container_id(session)
                started = time.monotonic()
                result = self._run(
                    self.config,
                    session,
                    "task-setsid-pipeclosed-gc",
                    "dataset-tree",
                    None,
                    code,
                    None,
                    None,
                    60.0,
                    container_id=container_id,
                    pool_enabled=False,
                    effective_output_limits=dict(DEV_LIMITS),
                )
                elapsed = time.monotonic() - started
                # Probe the container BEFORE closing the session.  The
                # bracketed pattern keeps the probe's own command line from
                # matching itself under ``pgrep -f``.
                probe = session.execute_command(
                    "sh -c 'pgrep -f \"sleep 30[0]\" >/dev/null 2>&1 "
                    "&& echo LEFTOVER || echo CLEAN'"
                )
            finally:
                session.close()
        self.assertLess(
            elapsed,
            30.0,
            f"wrapper blocked {elapsed:.1f}s on the escaped grandchild",
        )
        self.assertEqual(result["exit_code"], 0)
        self.assertIn("before-escape", result["stdout"])
        self.assertEqual(
            probe.exit_code, 0, f"probe failed: {probe.stderr!r}"
        )
        self.assertIn("CLEAN", probe.stdout)
        self.assertNotIn(
            "LEFTOVER",
            probe.stdout,
            "a setsid, pipe-closed grandchild survived the sweep — the "
            "subreaper gate or the descendant enumeration is broken",
        )


if __name__ == "__main__":
    unittest.main()
