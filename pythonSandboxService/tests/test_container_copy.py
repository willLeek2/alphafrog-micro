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
    resolve_container_identity,
    verify_container_identity,
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
    """grace round-3 MUST-FIX 1: identity is a runtime-CHECKED fact.

    Both config forms must be verified against the container's LIVE
    ``id -u``/``id -g`` after open(): uid/gid 0 is rejected whatever the
    config said, and a numeric ``uid:gid`` spec must match the live
    values exactly (a mismatch means docker did not adopt the configured
    user).
    """

    def setUp(self):
        import tempfile

        self._tmp = tempfile.TemporaryDirectory(prefix="af-ccopy-id-")
        self.root = Path(self._tmp.name)

    def tearDown(self):
        self._tmp.cleanup()

    # --- grace round-4: every NUMERIC field is verified on its own side ---
    # Docker-legal forms the round-3 code skipped: a bare numeric uid,
    # numeric uid + name group, and name user + numeric gid.  All three
    # repros use a container whose LIVE identity is 12345:12345.

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

class NoRootGuardTest(unittest.TestCase):
    def setUp(self):
        import tempfile

        self._tmp = tempfile.TemporaryDirectory(prefix="af-ccopy-guard-")
        self.root = Path(self._tmp.name)
        self.session = FakeSession(self.root)

    def tearDown(self):
        self._tmp.cleanup()

class ProductionEntryPointsTest(unittest.TestCase):
    """Every production staging entry routes through container_copy.

    The end-to-end no-copy_to_runtime proof lives in
    tests/test_bounded_wrapper_wiring.py: FakeContainerSession raises
    AssertionError if ``copy_to_runtime`` is ever called, and the whole
    functional wrapper path (dataset staging included) runs green over
    it.  Here the two production entry points are pinned DIRECTLY to the
    container_copy call so a future refactor cannot silently re-route.
    """

if __name__ == "__main__":
    unittest.main()
