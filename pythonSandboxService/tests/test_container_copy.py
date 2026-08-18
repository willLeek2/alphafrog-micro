# === work-package-C (ccqwen) ===
"""Tests for app.container_copy — the non-root staging path (260818).

grace review on 874b77ad required the replacement copy path to PROVE, in
tests, that:

1. both config forms (username ``alphafrog-sandbox`` and numeric
   ``uid:gid``) resolve to a REAL non-zero ``(uid, gid)`` pair;
2. staging lands the file owned by the container user with the source
   file's permission bits, and re-staging over an existing file REPLACES
   it (never appends or degrades);
3. there is NO root exec anywhere on the path (the llm-sandbox
   ``_ensure_ownership`` chown-as-root is guarded into a loud failure);
4. every production copy entry point routes through this module instead
   of ``session.copy_to_runtime`` (whose library implementation execs
   ``chown -R`` as root — see the module docstring of
   app/container_copy.py).

The docker ``put_archive`` call is faked host-side: the tar bytes are
parsed directly for the ownership/mode assertions and extracted onto the
host filesystem for the overwrite-behavior assertion.  No Docker
required.

Run from pythonSandboxService/:

    python3 -m unittest tests.test_container_copy -v
"""

from __future__ import annotations

import io
import os
import sys
import tarfile
import types
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

# Host-runnable llm_sandbox stub (same pattern as the other suites).
if "llm_sandbox" not in sys.modules:
    _llm_sandbox = types.ModuleType("llm_sandbox")
    _llm_sandbox.SandboxSession = object
    sys.modules["llm_sandbox"] = _llm_sandbox
    _llm_sandbox_exceptions = types.ModuleType("llm_sandbox.exceptions")
    _llm_sandbox_exceptions.SandboxTimeoutError = TimeoutError
    sys.modules["llm_sandbox.exceptions"] = _llm_sandbox_exceptions

from app import container_copy  # noqa: E402
from app.container_copy import (  # noqa: E402
    CONTAINER_IDENTITY_ATTR,
    NonRootContractError,
    copy_file_to_container,
    install_no_root_guards,
    prime_container_identity,
    resolve_container_identity,
)


class FakePutArchiveContainer:
    """Host-backed docker container proxy: ``put_archive`` extracts the
    tar into a literal host directory (so overwrite semantics are real
    filesystem semantics) and records every archive for assertions."""

    def __init__(self, root: Path) -> None:
        self.root = root
        self.archives: list = []
        self.exec_run_calls: list = []
        self.exec_run_impl = None  # installed by guard tests

    def put_archive(self, dest_dir: str, data: bytes) -> None:
        self.archives.append((dest_dir, data))
        dest = Path(dest_dir)
        dest.mkdir(parents=True, exist_ok=True)
        with tarfile.open(fileobj=io.BytesIO(data)) as tar:
            for member in tar.getmembers():
                # Extract as the dev uid; the tar entry's recorded
                # uid/gid/mode stay intact for the assertions.
                member.uid, member.gid = os.getuid(), os.getgid()
                member.uname = member.gname = ""
                tar.extract(member, dest)

    def exec_run(self, *args, **kwargs):
        self.exec_run_calls.append((args, kwargs))
        if self.exec_run_impl is not None:
            return self.exec_run_impl(*args, **kwargs)
        return SimpleNamespace(exit_code=0, stdout=b"", stderr=b"")


class FakeSession:
    """Host-backed llm-sandbox session: execute_command and a container
    proxy with put_archive, exactly the surface container_copy touches."""

    def __init__(self, root: Path, *, uid: int = 10000, gid: int = 10001) -> None:
        self.root = root
        self.container = FakePutArchiveContainer(root)
        self.executed_commands: list = []
        self._uid, self._gid = uid, gid
        # The library's root-chown helper, as it exists on real sessions.
        self._ensure_ownership_ran = False

        def _ensure_ownership(paths):
            self._ensure_ownership_ran = True

        self._ensure_ownership = _ensure_ownership

    def execute_command(self, command, workdir=None):
        self.executed_commands.append(command)
        if command == "id -u":
            out, code = str(self._uid), 0
        elif command == "id -g":
            out, code = str(self._gid), 0
        else:
            out, code = "", 0  # mkdir -p etc.
        return SimpleNamespace(exit_code=code, stdout=out, stderr="")


class ResolveIdentityTest(unittest.TestCase):
    def setUp(self):
        import tempfile

        self._tmp = tempfile.TemporaryDirectory(prefix="af-ccopy-id-")
        self.root = Path(self._tmp.name)

    def tearDown(self):
        self._tmp.cleanup()

    def test_numeric_uid_gid_resolves_without_touching_the_container(self):
        session = FakeSession(self.root)
        identity = prime_container_identity(session, "10000:10001")
        self.assertEqual(identity, (10000, 10001))
        self.assertNotEqual(identity[0], 0)
        self.assertNotEqual(identity[1], 0)
        self.assertEqual(session.executed_commands, [])
        self.assertEqual(
            getattr(session, CONTAINER_IDENTITY_ATTR), (10000, 10001)
        )

    def test_username_form_resolves_via_in_container_id_lookup(self):
        session = FakeSession(self.root, uid=10000, gid=10001)
        identity = prime_container_identity(session, "alphafrog-sandbox")
        self.assertEqual(identity, (10000, 10001))
        self.assertNotEqual(identity[0], 0)
        self.assertNotEqual(identity[1], 0)
        self.assertIn("id -u", session.executed_commands)
        self.assertIn("id -g", session.executed_commands)

    def test_identity_is_cached_after_first_resolution(self):
        session = FakeSession(self.root)
        prime_container_identity(session, "alphafrog-sandbox")
        again = resolve_container_identity(session)
        self.assertEqual(again, (10000, 10001))
        self.assertEqual(
            session.executed_commands.count("id -u"), 1,
            "resolution must run once and be cached on the session",
        )

    def test_unresolvable_user_fails_closed(self):
        class BrokenSession(FakeSession):
            def execute_command(self, command, workdir=None):
                self.executed_commands.append(command)
                return SimpleNamespace(exit_code=1, stdout="", stderr="nope")

        session = BrokenSession(self.root)
        with self.assertRaises(NonRootContractError):
            prime_container_identity(session, "alphafrog-sandbox")

    def test_non_numeric_id_output_fails_closed(self):
        class WeirdSession(FakeSession):
            def execute_command(self, command, workdir=None):
                self.executed_commands.append(command)
                return SimpleNamespace(exit_code=0, stdout="not-a-number", stderr="")

        session = WeirdSession(self.root)
        with self.assertRaises(NonRootContractError):
            resolve_container_identity(session)


class CopyFileToContainerTest(unittest.TestCase):
    def setUp(self):
        import tempfile

        self._tmp = tempfile.TemporaryDirectory(prefix="af-ccopy-file-")
        self.root = Path(self._tmp.name)
        self.session = FakeSession(self.root)
        self.source = self.root / "payload.py"
        self.source.write_text("print('staged')\n", encoding="utf-8")
        self.dest_dir = self.root / "sandbox" / "task-x"
        self.dest = f"{self.dest_dir}/payload.py"

    def tearDown(self):
        self._tmp.cleanup()

    def _single_tar_member(self):
        (dest_dir, data), = self.session.container.archives
        with tarfile.open(fileobj=io.BytesIO(data)) as tar:
            member = tar.getmembers()[0]
            payload = tar.extractfile(member).read()
        return dest_dir, member, payload

    def test_missing_container_object_fails_closed_without_fallback(self):
        session = FakeSession(self.root)
        del session.container
        with self.assertRaisesRegex(NonRootContractError, "copy_to_runtime"):
            copy_file_to_container(session, self.source, self.dest)
        # The fallback the error forbids is exactly the library path that
        # execs chown as root; nothing was staged.
        self.assertEqual(session.container if hasattr(session, "container") else None, None)

    def test_stages_tar_entry_owned_by_container_user_with_source_mode(self):
        self.source.chmod(0o755)
        prime_container_identity(self.session, "10000:10001")
        copy_file_to_container(self.session, self.source, self.dest)

        # The parent directory is created via the session's normal exec
        # (which runs as the container user), before the archive lands.
        self.assertTrue(
            any("mkdir -p" in cmd for cmd in self.session.executed_commands)
        )
        dest_dir, member, payload = self._single_tar_member()
        self.assertEqual(dest_dir, str(self.dest_dir))
        self.assertEqual(member.name, "payload.py")
        self.assertEqual(member.uid, 10000)
        self.assertEqual(member.gid, 10001)
        self.assertNotEqual(member.uid, 0)
        self.assertNotEqual(member.gid, 0)
        self.assertEqual(member.mode & 0o7777, 0o755)
        self.assertEqual(payload, b"print('staged')\n")
        # Ownership arrives via the tar entry, never via a chown exec.
        self.assertFalse(self.session._ensure_ownership_ran)

    def test_restage_replaces_the_existing_file_bytes_exactly(self):
        prime_container_identity(self.session, "10000:10001")
        copy_file_to_container(self.session, self.source, self.dest)
        first_size = (self.dest_dir / "payload.py").stat().st_size

        replacement = self.root / "replacement.py"
        replacement.write_text("x = 1\n", encoding="utf-8")
        copy_file_to_container(self.session, replacement, self.dest)

        landed = self.dest_dir / "payload.py"
        # REPLACED, not appended: the landed bytes are exactly the second
        # payload, and the size went DOWN (old content fully gone).
        self.assertEqual(landed.read_bytes(), b"x = 1\n")
        self.assertLess(landed.stat().st_size, first_size)
        self.assertEqual(len(self.session.container.archives), 2)

    def test_dest_without_file_component_is_rejected(self):
        # normpath collapses trailing slashes, so "/" is the dest whose
        # basename is empty — staging a directory root is a call bug.
        with self.assertRaises(ValueError):
            copy_file_to_container(self.session, self.source, "/")

    def test_mkdir_failure_fails_closed(self):
        class MkdirFailSession(FakeSession):
            def execute_command(self, command, workdir=None):
                self.executed_commands.append(command)
                if command.startswith("mkdir"):
                    return SimpleNamespace(exit_code=1, stdout="", stderr="ro")
                return super().execute_command(command, workdir)

        session = MkdirFailSession(self.root)
        with self.assertRaises(NonRootContractError):
            copy_file_to_container(session, self.source, self.dest)


class NoRootGuardTest(unittest.TestCase):
    def setUp(self):
        import tempfile

        self._tmp = tempfile.TemporaryDirectory(prefix="af-ccopy-guard-")
        self.root = Path(self._tmp.name)
        self.session = FakeSession(self.root)

    def tearDown(self):
        self._tmp.cleanup()

    def test_ensure_ownership_becomes_a_loud_failure(self):
        install_no_root_guards(self.session)
        with self.assertRaisesRegex(NonRootContractError, "_ensure_ownership"):
            self.session._ensure_ownership(["/sandbox/x"])
        self.assertFalse(self.session._ensure_ownership_ran)

    def test_root_exec_run_is_rejected_and_others_pass_through(self):
        install_no_root_guards(self.session)
        for user in ("root", "0", 0, "0:0"):
            with self.subTest(user=user):
                with self.assertRaisesRegex(NonRootContractError, "root"):
                    self.session.container.exec_run("id", user=user)
        self.assertEqual(self.session.container.exec_run_calls, [])
        # Non-root executions reach the container untouched.
        self.session.container.exec_run("id", user="alphafrog-sandbox")
        self.assertEqual(len(self.session.container.exec_run_calls), 1)

    def test_guard_install_is_idempotent(self):
        install_no_root_guards(self.session)
        install_no_root_guards(self.session)
        self.session.container.exec_run("id", user="alphafrog-sandbox")
        self.assertEqual(len(self.session.container.exec_run_calls), 1)

    def test_container_without_exec_run_attribute_is_tolerated(self):
        # A container proxy exposing ONLY put_archive: the exec guard is
        # skipped while the _ensure_ownership guard still holds.
        self.session.container = SimpleNamespace(
            put_archive=self.session.container.put_archive
        )
        install_no_root_guards(self.session)  # must not raise
        with self.assertRaisesRegex(NonRootContractError, "_ensure_ownership"):
            self.session._ensure_ownership([])


class ProductionEntryPointsTest(unittest.TestCase):
    """Every production staging entry routes through container_copy.

    The end-to-end no-copy_to_runtime proof lives in
    tests/test_bounded_wrapper_wiring.py: FakeContainerSession raises
    AssertionError if ``copy_to_runtime`` is ever called, and the whole
    functional wrapper path (dataset staging included) runs green over
    it.  Here the two production entry points are pinned DIRECTLY to the
    container_copy call so a future refactor cannot silently re-route.
    """

    def test_dataset_staging_delegates_to_container_copy(self):
        import app.sandbox_runner as runner

        session = SimpleNamespace()
        source = "/tmp/ds.csv"
        dest = "/sandbox/input/ds.csv"
        with mock.patch.object(
            container_copy, "copy_file_to_container"
        ) as staged:
            runner._copy_dataset_file(session, source, dest)
        staged.assert_called_once_with(session, source, dest)

    def test_runtime_environment_staging_delegates_to_container_copy(self):
        import tempfile

        import app.runtime_environment as runtime_environment
        from app.models import ExecutionEnvironment

        env = ExecutionEnvironment(
            environment_id="sha256:" + "a" * 64,
            image_digest="sha256:" + "b" * 64,
            library_set_digest="sha256:" + "c" * 64,
        )
        with tempfile.TemporaryDirectory(prefix="af-ccopy-entry-") as tmp:
            session = SimpleNamespace()
            dest = "/sandbox/runtime-environment.json"
            with mock.patch.object(
                container_copy, "copy_file_to_container"
            ) as staged:
                landed = runtime_environment.write_runtime_environment_to_container(
                    session, env, dest
                )
            self.assertEqual(landed, dest)
            staged.assert_called_once()
            self.assertEqual(staged.call_args.args[0], session)
            self.assertEqual(staged.call_args.args[2], dest)
            # The staged tempfile was cleaned up (delete=False + unlink).
            staged_temp = staged.call_args.args[1]
            self.assertFalse(Path(staged_temp).exists())


if __name__ == "__main__":
    unittest.main()
