"""260818 non-root simplification: the sandbox container runs as an
unprivileged user selected at CONTAINER CREATION time.

Two surfaces are pinned here:

1. ``app.config.load_config`` reads ``AF_SANDBOX_CHILD_USER`` into
   ``SandboxConfig.container_user`` (default ``alphafrog-sandbox``;
   empty/whitespace values are rejected fail-fast).
2. ``app.sandbox_runner.create_sandbox_session`` forwards that user into the
   container ``runtime_configs`` (docker ``--user`` semantics), so nothing
   inside a sandbox container ever runs as root.  No llm-sandbox / Docker
   dependency: the session class is patched out.
"""

from __future__ import annotations

import os
import sys
import types
import unittest
from types import SimpleNamespace
from unittest import mock

# Host-runnable llm_sandbox stub (same pattern as test_bounded_wrapper_wiring:
# these tests never touch Docker).
if "llm_sandbox" not in sys.modules:
    _llm_sandbox = types.ModuleType("llm_sandbox")
    _llm_sandbox.SandboxSession = object
    sys.modules["llm_sandbox"] = _llm_sandbox
    _llm_sandbox_exceptions = types.ModuleType("llm_sandbox.exceptions")
    _llm_sandbox_exceptions.SandboxTimeoutError = TimeoutError
    sys.modules["llm_sandbox.exceptions"] = _llm_sandbox_exceptions

from app.config import load_config  # noqa: E402
from app.sandbox_runner import create_sandbox_session  # noqa: E402

GOOD_LOCAL_ID = "sha256:" + "ab" * 32


class _EnvIsolation(unittest.TestCase):
    """Isolate the whole AF_SANDBOX_IMAGE* family + the child-user key.

    Under full-suite discovery many sibling tests mutate these keys; a
    leaked verify-mode/image combination would fail load_config for
    reasons unrelated to this suite.
    """

    def setUp(self):
        self._keys = [
            k for k in os.environ
            if k.startswith("AF_SANDBOX_IMAGE") or k == "AF_SANDBOX_CHILD_USER"
        ]
        self._saved = {k: os.environ.get(k) for k in self._keys}
        for k in self._keys:
            os.environ.pop(k, None)
        os.environ["AF_SANDBOX_IMAGE"] = GOOD_LOCAL_ID

    def tearDown(self):
        # Tests SET the child-user key mid-test, so it may be absent from
        # the setUp snapshot; always clear it along with the tracked keys,
        # then replay the snapshot.
        for key in set(self._keys) | {"AF_SANDBOX_CHILD_USER"}:
            os.environ.pop(key, None)
        for key in self._keys:
            value = self._saved.get(key)
            if value is not None:
                os.environ[key] = value


class ContainerUserConfigTest(_EnvIsolation):
    pass
class _ContractFakeSession:
    """Open-session fake satisfying the post-open non-root contract:
    open()/close() bookkeeping, a guardable container proxy (put_archive
    + exec_run), and an in-container ``id`` that reports uid/gid 10000/
    10001.  ``uid``/``gid``/``container`` let tests break the contract
    on purpose."""

    def __init__(self, *args, **kwargs):
        self.constructor_kwargs = kwargs
        self.closed = 0
        self._uid = 10000
        self._gid = 10001
        self.container = SimpleNamespace(
            put_archive=lambda *a, **k: None,
            exec_run=lambda *a, **k: SimpleNamespace(exit_code=0),
        )

    def open(self):
        return None

    def close(self):
        self.closed += 1

    def execute_command(self, command: str):
        flag = command.split()[-1]
        out = {"-u": str(self._uid), "-g": str(self._gid)}.get(flag, "")
        return SimpleNamespace(exit_code=0, stdout=out, stderr="")


class CreateSessionUserPassthroughTest(_EnvIsolation):
    def _created_session(self, config):
        session = _ContractFakeSession()

        def factory(*args, **kwargs):
            session.constructor_kwargs = kwargs
            return session

        with mock.patch("app.sandbox_runner.SandboxSession", side_effect=factory):
            create_sandbox_session(config)
        return session

class TaskControlRootDefaultTest(unittest.TestCase):
    """grace review MUST-FIX 1 on 874b77ad: the D11 cancel-marker control
    root defaulted to ``/run/alphafrog-task-control``, which a non-root
    container user can never create (/run is root-owned).  Both sides of
    the contract — the runner (host side, creates the dir) and the
    wrapper (container side, reads markers under it) — must default to
    the SAME path, and that path must live under the container-writable
    /sandbox tree, never under /run or any other root-owned root.
    """

if __name__ == "__main__":
    unittest.main()
