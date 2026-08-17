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
    def test_default_is_alphafrog_sandbox(self):
        self.assertEqual(load_config().container_user, "alphafrog-sandbox")

    def test_username_form_passes_through_verbatim(self):
        os.environ["AF_SANDBOX_CHILD_USER"] = "alphafrog-sandbox"
        self.assertEqual(load_config().container_user, "alphafrog-sandbox")

    def test_numeric_uid_gid_form_passes_through_verbatim(self):
        os.environ["AF_SANDBOX_CHILD_USER"] = "10000:10001"
        self.assertEqual(load_config().container_user, "10000:10001")

    def test_empty_value_is_rejected_fail_fast(self):
        os.environ["AF_SANDBOX_CHILD_USER"] = ""
        with self.assertRaises(ValueError) as raised:
            load_config()
        self.assertIn("AF_SANDBOX_CHILD_USER", str(raised.exception))

    def test_whitespace_value_is_rejected_fail_fast(self):
        os.environ["AF_SANDBOX_CHILD_USER"] = "   "
        with self.assertRaises(ValueError):
            load_config()

    def test_root_user_is_rejected_fail_fast(self):
        # Never allow the container to come back as root through this knob.
        os.environ["AF_SANDBOX_CHILD_USER"] = "root"
        with self.assertRaises(ValueError) as raised:
            load_config()
        self.assertIn("AF_SANDBOX_CHILD_USER", str(raised.exception))

    def test_numeric_root_uid_is_rejected_fail_fast(self):
        os.environ["AF_SANDBOX_CHILD_USER"] = "0:0"
        with self.assertRaises(ValueError):
            load_config()


class CreateSessionUserPassthroughTest(_EnvIsolation):
    def test_runtime_configs_carry_container_user(self):
        config = load_config()
        with mock.patch("app.sandbox_runner.SandboxSession") as session_cls:
            create_sandbox_session(config)
        kwargs = session_cls.call_args.kwargs
        self.assertEqual(
            kwargs["runtime_configs"].get("user"),
            config.container_user,
            "the container must be created as the unprivileged user "
            "(docker --user), replacing the removed privilege-drop machinery",
        )


if __name__ == "__main__":
    unittest.main()
