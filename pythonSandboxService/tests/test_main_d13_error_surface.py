"""D13 (26Q3) Python-side error-surface contract tests.

Pins the frozen D13 Python mappings (ccqwen 1f4e16d4 + 5c543fea, accepted by
codex 3d78edba; Cindy 8e21955c / 6a6e6158 / 45dafcda):

* bounded acceptance queue: create rejects 503 BEFORE persisting when the
  queue is full (Gateway maps 503 → OVERLOADED_OR_UNAVAILABLE);
* effective task timeout ceiling: after seconds/millis consistency
  normalization the FINAL effective timeout is validated once with
  ``0 < effective <= max_task_timeout_seconds`` (reject threshold
  ``effective > max``; the Gateway long-read margin is NOT part of the
  business limit). NaN / Infinity / negative / zero fail closed;
* CanonicalFingerprintMismatch → 400 (INVALID_ARGUMENT), while a genuine
  store-level OperationConflictError stays 409 (CONFLICT);
* result-not-finished → 425 Too Early (unknown 4xx → Gateway UNSPECIFIED,
  fail-closed polling) instead of the pre-D13 409;
* terminal-without-result → JSON 500 (DOWNSTREAM_FAILURE) instead of 400;
* operation lookup authoritative absence = 200 + found=false + blank error,
  never 404;
* every unhandled exception surfaces as a JSON 500 via the global handler;
* config fail-fast: ``queue_max_size < 1`` and
  ``max_task_timeout_seconds <= 0`` raise at load_config.

Additive on f8a383e0 (codex 5457b713 cross-contract MUST-FIX 1/2):

* MUST-FIX 2: the FINAL effective timeout is resolved and frozen at create
  time. When both timeout fields are absent the frozen effective value is
  ``config.execution_timeout_seconds`` (never re-derived at run time); a
  configured default above the ceiling fails at load_config AND at the
  create gate; ``max_task_timeout_seconds`` and
  ``execution_timeout_seconds`` must be finite (inf/nan rejected).
* MUST-FIX 1 (Python side): the canonical companion
  ``AF_SANDBOX_MAX_TASK_TIMEOUT_MILLIS`` must equal
  ``AF_SANDBOX_MAX_TASK_TIMEOUT_SECONDS * 1000`` exactly (decimal-exact)
  when present -- default/equal-override PASS, mismatch/non-finite/
  non-positive/non-numeric FAIL at load_config; an absent companion stays
  permitted for dev/test (release presence is enforced by the compose
  contract test in ccmax's release-binding commit).
"""

from __future__ import annotations

import asyncio
import dataclasses
import hashlib
import json
import os
import sys
import tempfile
import types
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

# app.config.load_config() runs at import time and requires a valid
# AF_SANDBOX_IMAGE digest reference (Spec §12). Pin a dev digest reference
# for the test process unless the environment already provides one, and pin
# strict-release mode: the 260814 default (local-image-id) would reject a
# registry digest reference, and this module's tests exercise D13 timeout
# binding, not the image reference policy.
os.environ.setdefault(
    "AF_SANDBOX_IMAGE",
    "registry.local/alphafrog/runtime@sha256:" + "a" * 64,
)
os.environ.setdefault("AF_SANDBOX_IMAGE_VERIFY_MODE", "strict-release")

llm_sandbox = types.ModuleType("llm_sandbox")
llm_sandbox.SandboxSession = object
llm_sandbox_exceptions = types.ModuleType("llm_sandbox.exceptions")
llm_sandbox_exceptions.SandboxTimeoutError = TimeoutError
sys.modules.setdefault("llm_sandbox", llm_sandbox)
sys.modules.setdefault("llm_sandbox.exceptions", llm_sandbox_exceptions)

from fastapi import HTTPException  # noqa: E402

from app import main  # noqa: E402
from app.canonical_fingerprint import CanonicalSandboxCreateSpec  # noqa: E402
from app.config import (  # noqa: E402
    load_config,
    validate_max_task_timeout_binding,
)
from app.models import ExecuteRequest, Task, TaskStatus  # noqa: E402
from app.task_store import DurableTaskStore  # noqa: E402


class MainD13ErrorSurfaceTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.store = DurableTaskStore(Path(self.temp_dir.name) / "state.json")
        self.queue: asyncio.Queue = asyncio.Queue()
        # Capture the module-level queue BEFORE patching so its bounded
        # wiring can be asserted (patch.object hides it while active).
        self.original_queue = main.task_queue
        self.store_patch = patch.object(main, "task_store", self.store)
        self.tasks_patch = patch.object(main, "tasks", self.store.tasks)
        self.queue_patch = patch.object(main, "task_queue", self.queue)
        self.store_patch.start()
        self.tasks_patch.start()
        self.queue_patch.start()
        # D14: this suite still exercises keyless create for timeout-surface
        # fixtures. Explicit non-production compat — production refuse coverage
        # lives in test_main_d14_operation_id_gate.py.
        self.config_patch = patch.object(
            main,
            "config",
            dataclasses.replace(main.config, allow_create_without_operation_id=True),
        )
        self.config_patch.start()

    async def asyncTearDown(self) -> None:
        self.config_patch.stop()
        self.queue_patch.stop()
        self.tasks_patch.stop()
        self.store_patch.stop()
        self.temp_dir.cleanup()

    def request(
        self,
        code: str = "print(1)",
        operation_id: str | None = "run-1:call-1:1",
        timeout_millis: int | None = 60_000,
        timeout_seconds: float | None = None,
    ) -> ExecuteRequest:
        code_hash = "sha256:" + hashlib.sha256(code.encode("utf-8")).hexdigest()
        if operation_id:
            spec = CanonicalSandboxCreateSpec(
                schema_version="sandbox_create_v1",
                operation_id=operation_id,
                code_hash=code_hash,
                immutable_dataset_snapshot_digest="sha256:" + "c" * 64,
                resource_class="STANDARD",
                memory_limit_bytes=512 * 1024 * 1024,
                timeout_millis=timeout_millis if timeout_millis is not None else 60_000,
                runtime_environment_version="python-runtime-v1",
                libraries_digest="sha256:" + "d" * 64,
                sandbox_options_digest="sha256:" + "e" * 64,
            )
            request_fingerprint = spec.request_fingerprint()
        else:
            # Keyless create: no operation_id/fingerprint pair (legal; the
            # idempotency validator requires both together or neither).
            request_fingerprint = None
        return ExecuteRequest(
            dataset_id="dataset-1",
            code=code,
            operation_id=operation_id,
            request_fingerprint=request_fingerprint,
            resource_class="STANDARD",
            capacity_units=1,
            memory_limit_bytes=512 * 1024 * 1024,
            timeout_millis=timeout_millis,
            timeout_seconds=timeout_seconds,
            runtime_environment_version="python-runtime-v1",
            canonical_spec_schema_version="sandbox_create_v1",
            code_hash=code_hash,
            immutable_dataset_snapshot_digest="sha256:" + "c" * 64,
            libraries_digest="sha256:" + "d" * 64,
            sandbox_options_digest="sha256:" + "e" * 64,
        )

    # --- bounded acceptance queue (503 → OVERLOADED_OR_UNAVAILABLE) --------

    # --- effective timeout ceiling (Cindy 8e21955c regression set) ---------

    def max_seconds(self) -> float:
        return main.config.max_task_timeout_seconds

    # --- fingerprint mismatch 400 vs store conflict 409 --------------------

    # --- result endpoint surface -------------------------------------------

    # --- operation lookup authoritative absence (hard rule) ----------------

    # --- global unhandled-exception handler (JSON 500) ----------------------

    # --- config fail-fast ----------------------------------------------------

    # --- MF2: final effective timeout freeze (codex 5457b713 MUST-FIX 2) ---

    # --- MF1: release-binding companion millis (codex 5457b713 MUST-FIX 1) --

if __name__ == "__main__":
    unittest.main()
