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
"""

from __future__ import annotations

import asyncio
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
# for the test process unless the environment already provides one.
os.environ.setdefault(
    "AF_SANDBOX_IMAGE",
    "registry.local/alphafrog/runtime@sha256:" + "a" * 64,
)

llm_sandbox = types.ModuleType("llm_sandbox")
llm_sandbox.SandboxSession = object
llm_sandbox_exceptions = types.ModuleType("llm_sandbox.exceptions")
llm_sandbox_exceptions.SandboxTimeoutError = TimeoutError
sys.modules.setdefault("llm_sandbox", llm_sandbox)
sys.modules.setdefault("llm_sandbox.exceptions", llm_sandbox_exceptions)

from fastapi import HTTPException  # noqa: E402

from app import main  # noqa: E402
from app.canonical_fingerprint import CanonicalSandboxCreateSpec  # noqa: E402
from app.config import load_config  # noqa: E402
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

    async def asyncTearDown(self) -> None:
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

    async def test_queue_full_rejects_create_with_503_before_persist(self) -> None:
        bounded: asyncio.Queue = asyncio.Queue(maxsize=1)
        await bounded.put("placeholder-task")
        with patch.object(main, "task_queue", bounded):
            with self.assertRaises(HTTPException) as raised:
                await main.create_task(self.request())

        self.assertEqual(raised.exception.status_code, 503)
        # Nothing was persisted: the pre-create full() check fires first.
        self.assertEqual(len(self.store.tasks), 0)
        self.assertEqual(bounded.qsize(), 1)

    def test_module_level_queue_is_bounded_by_config(self) -> None:
        self.assertEqual(
            self.original_queue.maxsize, main.config.queue_max_size
        )

    # --- effective timeout ceiling (Cindy 8e21955c regression set) ---------

    def max_seconds(self) -> float:
        return main.config.max_task_timeout_seconds

    async def test_only_millis_over_max_is_invalid_argument(self) -> None:
        request = self.request(
            timeout_millis=int(self.max_seconds() * 1000) + 1
        )
        with self.assertRaises(HTTPException) as raised:
            await main.create_task(request)
        self.assertEqual(raised.exception.status_code, 400)
        self.assertIn("effective task timeout", raised.exception.detail)
        self.assertEqual(self.queue.qsize(), 0)

    async def test_only_seconds_over_max_is_invalid_argument(self) -> None:
        # Legacy seconds-only request: keyless (the canonical spec requires
        # timeout_millis, so a keyed seconds-only create cannot be valid).
        request = self.request(
            operation_id=None,
            timeout_millis=None,
            timeout_seconds=self.max_seconds() + 1,
        )
        with self.assertRaises(HTTPException) as raised:
            await main.create_task(request)
        self.assertEqual(raised.exception.status_code, 400)
        self.assertIn("effective task timeout", raised.exception.detail)

    async def test_both_fields_agree_over_max_is_invalid_argument(self) -> None:
        over_millis = int(self.max_seconds() * 1000) + 1
        request = self.request(
            timeout_millis=over_millis,
            timeout_seconds=over_millis / 1000.0,
        )
        with self.assertRaises(HTTPException) as raised:
            await main.create_task(request)
        self.assertEqual(raised.exception.status_code, 400)
        self.assertIn("effective task timeout", raised.exception.detail)

    async def test_both_fields_conflict_stays_invalid_argument(self) -> None:
        request = self.request(timeout_millis=60_000, timeout_seconds=70.0)
        with self.assertRaises(HTTPException) as raised:
            await main.create_task(request)
        self.assertEqual(raised.exception.status_code, 400)
        self.assertIn("conflicts", raised.exception.detail)

    async def test_exactly_max_is_accepted(self) -> None:
        request = self.request(timeout_millis=int(self.max_seconds() * 1000))
        response = await main.create_task(request)
        self.assertFalse(response.existing)
        self.assertEqual(self.queue.qsize(), 1)

    async def test_fractional_seconds_max_plus_epsilon_is_rejected(self) -> None:
        # Mirror of Cindy 1b29792d MF2: a fractional effective timeout just
        # above the ceiling must NOT slip through truncation. Seconds-domain
        # float comparison rejects 1800.0009 > 1800.0 directly.
        request = self.request(
            operation_id=None,
            timeout_millis=None,
            timeout_seconds=self.max_seconds() + 0.0009,
        )
        with self.assertRaises(HTTPException) as raised:
            await main.create_task(request)
        self.assertEqual(raised.exception.status_code, 400)
        self.assertIn("effective task timeout", raised.exception.detail)

    async def test_nan_seconds_fail_closed(self) -> None:
        request = self.request(
            operation_id=None, timeout_millis=None, timeout_seconds=float("nan")
        )
        with self.assertRaises(HTTPException) as raised:
            await main.create_task(request)
        self.assertEqual(raised.exception.status_code, 400)
        self.assertIn("effective task timeout", raised.exception.detail)

    async def test_infinity_seconds_fail_closed(self) -> None:
        request = self.request(
            operation_id=None, timeout_millis=None, timeout_seconds=float("inf")
        )
        with self.assertRaises(HTTPException) as raised:
            await main.create_task(request)
        self.assertEqual(raised.exception.status_code, 400)
        self.assertIn("effective task timeout", raised.exception.detail)

    async def test_negative_seconds_fail_closed(self) -> None:
        request = self.request(
            operation_id=None, timeout_millis=None, timeout_seconds=-1.0
        )
        with self.assertRaises(HTTPException) as raised:
            await main.create_task(request)
        self.assertEqual(raised.exception.status_code, 400)
        self.assertIn("effective task timeout", raised.exception.detail)

    async def test_zero_seconds_fail_closed(self) -> None:
        request = self.request(
            operation_id=None, timeout_millis=None, timeout_seconds=0.0
        )
        with self.assertRaises(HTTPException) as raised:
            await main.create_task(request)
        self.assertEqual(raised.exception.status_code, 400)
        self.assertIn("effective task timeout", raised.exception.detail)

    # --- fingerprint mismatch 400 vs store conflict 409 --------------------

    async def test_store_conflict_same_operation_id_stays_409(self) -> None:
        first = self.request(code="print(1)")
        await main.create_task(first)
        # Same operation_id, different payload, its OWN correct fingerprint:
        # a genuine state conflict → 409 → CONFLICT (unchanged by D13).
        second = self.request(code="print(2)")
        with self.assertRaises(HTTPException) as raised:
            await main.create_task(second)
        self.assertEqual(raised.exception.status_code, 409)
        self.assertEqual(self.queue.qsize(), 1)

    # --- result endpoint surface -------------------------------------------

    async def test_result_not_finished_is_425(self) -> None:
        for status in (TaskStatus.QUEUED, TaskStatus.RUNNING):
            task = Task(
                task_id=f"task-{status.value.lower()}",
                status=status,
                request=ExecuteRequest(dataset_id="dataset-1", code="print(1)"),
            )
            self.store.save(task)
            with self.assertRaises(HTTPException) as raised:
                await main.get_task_result(task.task_id)
            self.assertEqual(raised.exception.status_code, 425)

    async def test_failed_terminal_without_result_is_json_500(self) -> None:
        task = Task(
            task_id="task-failed-no-result",
            status=TaskStatus.FAILED,
            request=ExecuteRequest(dataset_id="dataset-1", code="print(1)"),
            error="restart-recovery gap",
        )
        self.store.save(task)
        with self.assertRaises(HTTPException) as raised:
            await main.get_task_result(task.task_id)
        self.assertEqual(raised.exception.status_code, 500)
        self.assertIn("restart-recovery gap", raised.exception.detail)

    # --- operation lookup authoritative absence (hard rule) ----------------

    async def test_operation_lookup_unknown_is_authoritative_absence(self) -> None:
        response = await main.get_task_by_operation_id("never-seen")
        self.assertFalse(response.found)
        self.assertIsNone(response.task_id)
        # error stays blank/None — absence is authoritative, never a 404.
        self.assertFalse(response.error)

    # --- global unhandled-exception handler (JSON 500) ----------------------

    async def test_unhandled_exception_handler_is_registered_and_json(self) -> None:
        self.assertIn(Exception, main.app.exception_handlers)
        fake_request = SimpleNamespace(
            method="GET", url=SimpleNamespace(path="/tasks/boom")
        )
        response = await main.unhandled_exception_handler(
            fake_request, RuntimeError("persist layer exploded")
        )
        self.assertEqual(response.status_code, 500)
        body = json.loads(response.body)
        self.assertIn("persist layer exploded", body["detail"])
        self.assertIn("RuntimeError", body["detail"])

    # --- config fail-fast ----------------------------------------------------

    def test_queue_max_size_below_one_fails_config_load(self) -> None:
        with patch.dict(os.environ, {"AF_SANDBOX_QUEUE_MAX_SIZE": "0"}):
            with self.assertRaises(ValueError):
                load_config()

    def test_max_task_timeout_seconds_non_positive_fails_config_load(self) -> None:
        with patch.dict(os.environ, {"AF_SANDBOX_MAX_TASK_TIMEOUT_SECONDS": "0"}):
            with self.assertRaises(ValueError):
                load_config()


if __name__ == "__main__":
    unittest.main()
