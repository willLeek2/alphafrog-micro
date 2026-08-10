"""Unit tests for the D11 cancel endpoint (POST /tasks/cancel) in app.main.

260809-26Q3-stage1-w2 D11 (task #108): covers the endpoint's body validation
(the deliberate 400 surface that replaces FastAPI's 422), the five business
outcomes (CANCELED / CANCEL_INTENT_RECORDED / ALREADY_TERMINAL / NOT_FOUND
via 200 bodies), the durable cancelRequestId binding (same-target replay and
different-target 409), the by_operation pre-create tombstone plus its
adoption by a late create_task (golden path), and the codex 4334bc9d
constraint-1 QueueFull rollback that shares ONE store critical section.

The endpoint functions are called DIRECTLY (no TestClient / HTTP layer), the
durable store and the bounded acceptance queue are patched onto app.main
exactly like tests/test_main_idempotency.py, and the llm_sandbox module is
stubbed before app.main is imported (the sandbox runner imports it and it is
not installed in the test environment).
"""

from __future__ import annotations

import asyncio
import hashlib
import queue
import sys
import tempfile
import threading
import types
import unittest
from concurrent.futures import Future, ThreadPoolExecutor
from pathlib import Path
from unittest.mock import patch

from fastapi.testclient import TestClient

llm_sandbox = types.ModuleType("llm_sandbox")
llm_sandbox.SandboxSession = object
llm_sandbox_exceptions = types.ModuleType("llm_sandbox.exceptions")
llm_sandbox_exceptions.SandboxTimeoutError = TimeoutError
sys.modules.setdefault("llm_sandbox", llm_sandbox)
sys.modules.setdefault("llm_sandbox.exceptions", llm_sandbox_exceptions)

from fastapi import HTTPException  # noqa: E402

from app import cancel_registry as cancel_registry_module  # noqa: E402
from app import main  # noqa: E402
from app.canonical_fingerprint import CanonicalSandboxCreateSpec  # noqa: E402
from app.models import (  # noqa: E402
    CancelOutcome,
    CancelTaskRequest,
    CancelTaskResponse,
    CancellationEvidence,
    ExecuteRequest,
    OperationCancelTarget,
    Task,
    TaskIdCancelTarget,
    TaskStatus,
)
from app.task_store import DurableTaskStore  # noqa: E402


class _QueueStub:
    """Deterministic stand-in for the bounded acceptance queue.

    create_task only ever consults ``full()`` and admits via ``put_nowait``;
    this stub controls both independently so the admission race (queue fills
    BETWEEN the full() check and the put) and the genuinely-full case can be
    tested without any real queue capacity juggling.
    """

    def __init__(self, full: bool) -> None:
        self._full = full
        self.put_nowait_calls: list = []

    def full(self) -> bool:
        return self._full

    def qsize(self) -> int:
        return len(self.put_nowait_calls)

    def put_nowait(self, item: object) -> None:
        self.put_nowait_calls.append(item)
        raise asyncio.QueueFull


class _CapturingExecutor:
    """A ThreadPoolExecutor that captures every submitted Future in a queue.

    Used by the marker-retry tests so they can wait for the COMPLETE dispatch
    cycle — writer execution, exception handling, AND the outer finally that
    resets ``_marker_dispatch_idle`` — before asserting on the handle state.
    """

    def __init__(self, max_workers: int = 2) -> None:
        self._pool = ThreadPoolExecutor(
            max_workers=max_workers, thread_name_prefix="af-test-marker"
        )
        self._queue: queue.Queue[Future] = queue.Queue()

    def submit(self, fn, *args, **kwargs) -> Future:
        future: Future = self._pool.submit(fn, *args, **kwargs)
        self._queue.put(future)
        return future

    def captured(self) -> Future:
        """Block until a Future is available, then return it."""
        return self._queue.get(timeout=5.0)

    def captured_nowait(self) -> Future | None:
        """Return a Future if one was submitted, else None immediately."""
        try:
            return self._queue.get(timeout=0.1)
        except queue.Empty:
            return None

    def shutdown(self, wait: bool = True) -> None:
        self._pool.shutdown(wait=wait)


class CancelEndpointTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.store = DurableTaskStore(Path(self.temp_dir.name) / "state.json")
        self.queue: asyncio.Queue = asyncio.Queue()
        self.store_patch = patch.object(main, "task_store", self.store)
        self.tasks_patch = patch.object(main, "tasks", self.store.tasks)
        self.queue_patch = patch.object(main, "task_queue", self.queue)
        self.store_patch.start()
        self.tasks_patch.start()
        self.queue_patch.start()
        # Every registry handle a test registers MUST be unregistered again:
        # the registry is a module-level singleton shared across tests.
        self.registered_task_ids: list = []

    async def asyncTearDown(self) -> None:
        for task_id in self.registered_task_ids:
            cancel_registry_module.registry.unregister(task_id)
        self.registered_task_ids.clear()
        self.queue_patch.stop()
        self.tasks_patch.stop()
        self.store_patch.stop()
        self.temp_dir.cleanup()

    # ------------------------------------------------------------------ #
    # helpers
    # ------------------------------------------------------------------ #

    def fingerprint_for(self, operation_id: str, code: str = "print(1)") -> str:
        """Build the REAL canonical fingerprint for an operation identity.

        Mirrors the CanonicalSandboxCreateSpec construction used by
        tests/test_main_idempotency.py so verify_request_fingerprint accepts
        the requests built by ``request()`` below.
        """
        code_hash = "sha256:" + hashlib.sha256(code.encode("utf-8")).hexdigest()
        spec = CanonicalSandboxCreateSpec(
            schema_version="sandbox_create_v1",
            operation_id=operation_id,
            code_hash=code_hash,
            immutable_dataset_snapshot_digest="sha256:" + "c" * 64,
            resource_class="STANDARD",
            memory_limit_bytes=512 * 1024 * 1024,
            timeout_millis=60_000,
            runtime_environment_version="python-runtime-v1",
            libraries_digest="sha256:" + "d" * 64,
            sandbox_options_digest="sha256:" + "e" * 64,
        )
        return spec.request_fingerprint()

    def request(
        self, code: str = "print(1)", operation_id: str = "run-1:call-1:1"
    ) -> ExecuteRequest:
        """A valid keyed ExecuteRequest whose fingerprint genuinely matches."""
        code_hash = "sha256:" + hashlib.sha256(code.encode("utf-8")).hexdigest()
        return ExecuteRequest(
            dataset_id="dataset-1",
            code=code,
            operation_id=operation_id,
            request_fingerprint=self.fingerprint_for(operation_id, code),
            resource_class="STANDARD",
            capacity_units=1,
            memory_limit_bytes=512 * 1024 * 1024,
            timeout_millis=60_000,
            runtime_environment_version="python-runtime-v1",
            canonical_spec_schema_version="sandbox_create_v1",
            code_hash=code_hash,
            immutable_dataset_snapshot_digest="sha256:" + "c" * 64,
            libraries_digest="sha256:" + "d" * 64,
            sandbox_options_digest="sha256:" + "e" * 64,
        )

    def save_task(self, task_id: str, status: TaskStatus) -> Task:
        """Persist a bare task in the given status (no queue entry)."""
        task = Task(
            task_id=task_id,
            status=status,
            request=ExecuteRequest(dataset_id="dataset-1", code="print(1)"),
        )
        self.store.save(task)
        return task

    def assert_store_untouched(self) -> None:
        """A rejected cancel must never reach the durable store."""
        self.assertEqual(len(self.store.tasks), 0)
        self.assertEqual(self.store.cancel_requests, {})

    async def assert_cancel_rejected(
        self, request: CancelTaskRequest, expected_status: int
    ) -> None:
        with self.assertRaises(HTTPException) as raised:
            await main.cancel_task(request)
        self.assertEqual(raised.exception.status_code, expected_status)

    # ------------------------------------------------------------------ #
    # body validation: every defect answers 400 BEFORE any store call
    # ------------------------------------------------------------------ #

    async def test_cancel_request_id_missing_or_blank_is_rejected(self) -> None:
        for bad_id in (None, "   "):
            with self.assertRaises(HTTPException) as raised:
                await main.cancel_task(
                    CancelTaskRequest(
                        by_task_id=TaskIdCancelTarget(task_id="task-x"),
                        cancel_request_id=bad_id,
                    )
                )
            self.assertEqual(raised.exception.status_code, 400)
        self.assert_store_untouched()

    async def test_cancel_with_both_targets_set_is_rejected(self) -> None:
        await self.assert_cancel_rejected(
            CancelTaskRequest(
                by_task_id=TaskIdCancelTarget(task_id="task-x"),
                by_operation=OperationCancelTarget(
                    operation_id="run-1:call-1:1",
                    request_fingerprint="sha256:" + "a" * 64,
                ),
                cancel_request_id="cr-validation",
            ),
            400,
        )
        self.assert_store_untouched()

    async def test_cancel_with_no_target_is_rejected(self) -> None:
        await self.assert_cancel_rejected(
            CancelTaskRequest(cancel_request_id="cr-validation"),
            400,
        )
        self.assert_store_untouched()

    async def test_cancel_by_task_id_with_blank_task_id_is_rejected(self) -> None:
        await self.assert_cancel_rejected(
            CancelTaskRequest(
                by_task_id=TaskIdCancelTarget(task_id="   "),
                cancel_request_id="cr-validation",
            ),
            400,
        )
        self.assert_store_untouched()

    async def test_cancel_by_operation_with_blank_operation_id_is_rejected(self) -> None:
        await self.assert_cancel_rejected(
            CancelTaskRequest(
                by_operation=OperationCancelTarget(
                    operation_id="",
                    request_fingerprint="sha256:" + "a" * 64,
                ),
                cancel_request_id="cr-validation",
            ),
            400,
        )
        self.assert_store_untouched()

    async def test_cancel_by_operation_with_blank_fingerprint_is_rejected(self) -> None:
        await self.assert_cancel_rejected(
            CancelTaskRequest(
                by_operation=OperationCancelTarget(
                    operation_id="run-1:call-1:1",
                    request_fingerprint=None,
                ),
                cancel_request_id="cr-validation",
            ),
            400,
        )
        self.assert_store_untouched()

    # ------------------------------------------------------------------ #
    # by_task_id happy paths
    # ------------------------------------------------------------------ #

    async def test_cancel_queued_task_terminalizes_and_records_reason(self) -> None:
        self.save_task("task-queued", TaskStatus.QUEUED)

        response = await main.cancel_task(
            CancelTaskRequest(
                by_task_id=TaskIdCancelTarget(task_id="task-queued"),
                cancel_request_id="cr-queued",
                reason="user requested",
            )
        )

        self.assertIsInstance(response, CancelTaskResponse)
        self.assertEqual(response.outcome, CancelOutcome.CANCELED)
        self.assertEqual(response.status, TaskStatus.CANCELED)
        self.assertEqual(response.task_id, "task-queued")
        self.assertIsNone(response.error)
        persisted = self.store.get("task-queued")
        self.assertEqual(persisted.status, TaskStatus.CANCELED)
        self.assertEqual(
            persisted.cancellation_evidence, CancellationEvidence.QUEUED_CANCEL
        )
        self.assertIs(persisted.retryable, False)
        self.assertEqual(persisted.cancel_reason, "user requested")
        self.assertIsNotNone(persisted.result)
        self.assertIs(persisted.result.retryable, False)
        self.assertEqual(persisted.result.exit_code, -1)
        self.assertEqual(persisted.result.resource_usage.exit_reason, "CANCELED")

    async def test_cancel_running_task_dispatches_stop_to_registered_handle(self) -> None:
        self.save_task("task-running", TaskStatus.RUNNING)
        # The execution path normally registers the handle; emulate it here so
        # the endpoint's registry dispatch has somewhere to land.
        handle = cancel_registry_module.registry.register("task-running")
        self.registered_task_ids.append("task-running")

        response = await main.cancel_task(
            CancelTaskRequest(
                by_task_id=TaskIdCancelTarget(task_id="task-running"),
                cancel_request_id="cr-running",
            )
        )

        self.assertEqual(response.outcome, CancelOutcome.CANCEL_INTENT_RECORDED)
        self.assertEqual(response.status, TaskStatus.RUNNING)
        self.assertEqual(response.task_id, "task-running")
        persisted = self.store.get("task-running")
        self.assertEqual(persisted.status, TaskStatus.RUNNING)
        self.assertTrue(persisted.cancel_requested)
        self.assertIsNone(persisted.cancel_reason)
        # The endpoint dispatched the actual stop signal through the registry.
        self.assertIs(handle.stop_requested(), True)

    async def test_cancel_running_task_without_handle_records_intent_safely(self) -> None:
        self.save_task("task-running-orphan", TaskStatus.RUNNING)

        response = await main.cancel_task(
            CancelTaskRequest(
                by_task_id=TaskIdCancelTarget(task_id="task-running-orphan"),
                cancel_request_id="cr-orphan",
            )
        )

        self.assertEqual(response.outcome, CancelOutcome.CANCEL_INTENT_RECORDED)
        self.assertEqual(response.status, TaskStatus.RUNNING)
        self.assertEqual(response.task_id, "task-running-orphan")
        persisted = self.store.get("task-running-orphan")
        self.assertEqual(persisted.status, TaskStatus.RUNNING)
        self.assertTrue(persisted.cancel_requested)
        # request_stop on an unknown task must be a silent no-op, and the
        # endpoint must not have created a handle as a side effect.
        self.assertIsNone(cancel_registry_module.registry.get("task-running-orphan"))

    async def test_same_id_same_target_replay_returns_first_outcome_live_status(self) -> None:
        self.save_task("task-replay", TaskStatus.QUEUED)

        first = await main.cancel_task(
            CancelTaskRequest(
                by_task_id=TaskIdCancelTarget(task_id="task-replay"),
                cancel_request_id="cr-1",
            )
        )
        replay = await main.cancel_task(
            CancelTaskRequest(
                by_task_id=TaskIdCancelTarget(task_id="task-replay"),
                cancel_request_id="cr-1",
            )
        )

        self.assertEqual(first.outcome, CancelOutcome.CANCELED)
        # Replay returns the FIRST recorded outcome with a LIVE status lookup.
        self.assertEqual(replay.outcome, CancelOutcome.CANCELED)
        self.assertEqual(replay.status, TaskStatus.CANCELED)
        self.assertEqual(replay.task_id, "task-replay")

    async def test_same_id_different_task_rebind_is_conflict(self) -> None:
        self.save_task("task-a", TaskStatus.QUEUED)
        self.save_task("task-b", TaskStatus.QUEUED)
        await main.cancel_task(
            CancelTaskRequest(
                by_task_id=TaskIdCancelTarget(task_id="task-a"),
                cancel_request_id="cr-2",
            )
        )

        with self.assertRaises(HTTPException) as raised:
            await main.cancel_task(
                CancelTaskRequest(
                    by_task_id=TaskIdCancelTarget(task_id="task-b"),
                    cancel_request_id="cr-2",
                )
            )
        self.assertEqual(raised.exception.status_code, 409)
        # The rebind attempt must not have altered task-b's state.
        self.assertEqual(self.store.get("task-b").status, TaskStatus.QUEUED)

    async def test_cancel_unknown_task_id_is_business_not_found(self) -> None:
        response = await main.cancel_task(
            CancelTaskRequest(
                by_task_id=TaskIdCancelTarget(task_id="task-missing"),
                cancel_request_id="cr-not-found",
            )
        )

        self.assertEqual(response.outcome, CancelOutcome.NOT_FOUND)
        self.assertIsNone(response.task_id)
        self.assertIsNone(response.status)
        # The binding is still recorded so a same-key replay stays stable.
        self.assertIn("cr-not-found", self.store.cancel_requests)

    async def test_cancel_terminal_task_is_already_terminal(self) -> None:
        self.save_task("task-failed", TaskStatus.FAILED)

        response = await main.cancel_task(
            CancelTaskRequest(
                by_task_id=TaskIdCancelTarget(task_id="task-failed"),
                cancel_request_id="cr-terminal",
            )
        )

        self.assertEqual(response.outcome, CancelOutcome.ALREADY_TERMINAL)
        self.assertEqual(response.status, TaskStatus.FAILED)
        self.assertEqual(response.task_id, "task-failed")
        self.assertEqual(self.store.get("task-failed").status, TaskStatus.FAILED)

    # ------------------------------------------------------------------ #
    # by_operation paths (tombstone, adoption, conflicts)
    # ------------------------------------------------------------------ #

    async def test_cancel_by_operation_unknown_operation_creates_tombstone(self) -> None:
        operation_id = "run-9:call-9:1"

        response = await main.cancel_task(
            CancelTaskRequest(
                by_operation=OperationCancelTarget(
                    operation_id=operation_id,
                    request_fingerprint=self.fingerprint_for(operation_id),
                ),
                cancel_request_id="cr-pre-create",
            )
        )

        self.assertEqual(response.outcome, CancelOutcome.CANCELED)
        self.assertEqual(response.status, TaskStatus.CANCELED)
        self.assertIsNotNone(response.task_id)
        tombstone = self.store.get(response.task_id)
        self.assertIsNotNone(tombstone)
        self.assertEqual(tombstone.status, TaskStatus.CANCELED)
        self.assertIsNone(tombstone.request)
        self.assertEqual(
            tombstone.cancellation_evidence, CancellationEvidence.PRE_CREATE_CANCEL
        )
        entry = self.store.operations[operation_id]
        self.assertEqual(entry["task_id"], response.task_id)
        self.assertIsNone(entry["payload_digest"])

    async def test_create_after_pre_create_cancel_adopts_tombstone(self) -> None:
        # GOLDEN PATH: a by_operation cancel arrives BEFORE the create; the
        # late create adopts the tombstone instead of ever running.
        operation_id = "run-9:call-9:1"
        cancel_response = await main.cancel_task(
            CancelTaskRequest(
                by_operation=OperationCancelTarget(
                    operation_id=operation_id,
                    request_fingerprint=self.fingerprint_for(operation_id),
                ),
                cancel_request_id="cr-golden",
            )
        )
        self.assertEqual(cancel_response.outcome, CancelOutcome.CANCELED)

        create_response = await main.create_task(
            self.request(operation_id=operation_id)
        )

        self.assertTrue(create_response.existing)
        self.assertEqual(create_response.task_id, cancel_response.task_id)
        self.assertEqual(create_response.status, TaskStatus.CANCELED)
        # Adoption needs no queue slot: the bounded queue stayed empty.
        self.assertEqual(self.queue.qsize(), 0)
        adopted = self.store.get(cancel_response.task_id)
        self.assertIsNotNone(adopted.request)
        self.assertEqual(adopted.status, TaskStatus.CANCELED)
        self.assertIs(adopted.retryable, False)

    async def test_cancel_by_operation_wrong_fingerprint_is_conflict(self) -> None:
        operation_id = "run-3:call-3:1"
        await main.create_task(self.request(operation_id=operation_id))
        wrong_fingerprint = "sha256:" + "f" * 64

        with self.assertRaises(HTTPException) as raised:
            await main.cancel_task(
                CancelTaskRequest(
                    by_operation=OperationCancelTarget(
                        operation_id=operation_id,
                        request_fingerprint=wrong_fingerprint,
                    ),
                    cancel_request_id="cr-mismatch",
                )
            )
        self.assertEqual(raised.exception.status_code, 409)
        # The mismatched cancel must not have touched the existing task.
        self.assertEqual(
            self.store.get_by_operation_id(operation_id).status, TaskStatus.QUEUED
        )

    async def test_cancel_by_operation_malformed_operation_id_is_invalid(self) -> None:
        await self.assert_cancel_rejected(
            CancelTaskRequest(
                by_operation=OperationCancelTarget(
                    operation_id="no-colons-here",
                    request_fingerprint="sha256:" + "a" * 64,
                ),
                cancel_request_id="cr-bad-format",
            ),
            400,
        )
        # The store's ValueError surfaced as 400 before any record was made.
        self.assertEqual(self.store.operations, {})

    # ------------------------------------------------------------------ #
    # constraint 1 (codex 4334bc9d): QueueFull rollback shares ONE store
    # critical section with the dedup re-check, insert and persist
    # ------------------------------------------------------------------ #

    async def test_admission_race_rollback_leaves_no_durable_trace(self) -> None:
        # The queue reports NOT full, then the admission put raises QueueFull:
        # the queue filled between the full() check and the put.
        stub = _QueueStub(full=False)
        operation_id = "run-18:call-18:1"

        with patch.object(main, "task_queue", stub):
            with self.assertRaises(HTTPException) as raised:
                await main.create_task(self.request(operation_id=operation_id))

        self.assertEqual(raised.exception.status_code, 503)
        self.assertEqual(len(stub.put_nowait_calls), 1)
        # A rejected create leaves no durable trace: the rollback removed the
        # task AND the operation binding under the same store lock.
        self.assertEqual(len(self.store.tasks), 0)
        self.assertEqual(self.store.operations, {})

    async def test_queue_full_unknown_operation_is_rejected_without_trace(self) -> None:
        stub = _QueueStub(full=True)
        operation_id = "run-19:call-19:1"

        with patch.object(main, "task_queue", stub):
            with self.assertRaises(HTTPException) as raised:
                await main.create_task(self.request(operation_id=operation_id))

        self.assertEqual(raised.exception.status_code, 503)
        self.assertEqual(stub.put_nowait_calls, [])
        self.assertEqual(len(self.store.tasks), 0)
        self.assertEqual(self.store.operations, {})

    async def test_queue_full_still_adopts_existing_tombstone(self) -> None:
        # v4-4 re-check #2: a tombstone adoption needs no queue slot, so a
        # full queue must NOT reject a create whose operation was already
        # pre-canceled.
        operation_id = "run-20:call-20:1"
        cancel_response = await main.cancel_task(
            CancelTaskRequest(
                by_operation=OperationCancelTarget(
                    operation_id=operation_id,
                    request_fingerprint=self.fingerprint_for(operation_id),
                ),
                cancel_request_id="cr-full-queue",
            )
        )
        self.assertEqual(cancel_response.outcome, CancelOutcome.CANCELED)

        stub = _QueueStub(full=True)
        with patch.object(main, "task_queue", stub):
            create_response = await main.create_task(
                self.request(operation_id=operation_id)
            )

        self.assertTrue(create_response.existing)
        self.assertEqual(create_response.task_id, cancel_response.task_id)
        self.assertEqual(create_response.status, TaskStatus.CANCELED)
        # The queue was never touched: adoption bypasses admission entirely.
        self.assertEqual(stub.put_nowait_calls, [])

    # ------------------------------------------------------------------ #
    # codex c6c49248: cancel replay must RE-DISPATCH the marker after a
    # first write finishes (success or failure).  Writer failures on the
    # first attempt must not block retry through same-key replay or a
    # fresh cancelRequestId.
    # ------------------------------------------------------------------ #

    async def test_writer_failure_then_same_key_replay_re_dispatches(self) -> None:
        """First writer raises → outer finally resets idle → same-key replay
        dispatches again.  Synced via captured Future, not writer signals."""
        self.save_task("task-replay-fail", TaskStatus.RUNNING)
        handle = cancel_registry_module.registry.register("task-replay-fail")
        self.registered_task_ids.append("task-replay-fail")
        executor = _CapturingExecutor(max_workers=2)
        dispatch_log: list[str] = []
        fail_next: list[bool] = [True]

        def writer():
            if fail_next[0]:
                fail_next[0] = False
                dispatch_log.append("fail")
                raise RuntimeError("simulated marker write failure")
            dispatch_log.append("success")

        handle.set_marker_writer(writer)

        try:
            with patch.object(
                cancel_registry_module, "_get_write_pool", return_value=executor
            ):
                resp1 = await main.cancel_task(
                    CancelTaskRequest(
                        by_task_id=TaskIdCancelTarget(task_id="task-replay-fail"),
                        cancel_request_id="cr-fail",
                    )
                )
                self.assertEqual(resp1.outcome, CancelOutcome.CANCEL_INTENT_RECORDED)
                # Await the complete dispatch cycle — writer ran, exception
                # caught, outer finally reset _marker_dispatch_idle.
                await asyncio.to_thread(executor.captured().result, 5.0)
                self.assertEqual(dispatch_log, ["fail"])

                # Same-key replay: idle is True now, dispatches again.
                replay = await main.cancel_task(
                    CancelTaskRequest(
                        by_task_id=TaskIdCancelTarget(task_id="task-replay-fail"),
                        cancel_request_id="cr-fail",
                    )
                )
                self.assertEqual(replay.outcome, CancelOutcome.CANCEL_INTENT_RECORDED)
                await asyncio.to_thread(executor.captured().result, 5.0)
                self.assertEqual(dispatch_log, ["fail", "success"])
        finally:
            executor.shutdown(wait=True)

    async def test_different_cancel_request_id_re_dispatches_marker(self) -> None:
        """Fresh cancelRequestId on same RUNNING task re-dispatches after
        the first complete cycle finishes.  Precise count == 2."""
        self.save_task("task-replay-diff", TaskStatus.RUNNING)
        handle = cancel_registry_module.registry.register("task-replay-diff")
        self.registered_task_ids.append("task-replay-diff")
        executor = _CapturingExecutor(max_workers=2)
        dispatch_counter: list[int] = [0]

        def writer():
            dispatch_counter[0] += 1

        handle.set_marker_writer(writer)

        try:
            with patch.object(
                cancel_registry_module, "_get_write_pool", return_value=executor
            ):
                resp1 = await main.cancel_task(
                    CancelTaskRequest(
                        by_task_id=TaskIdCancelTarget(task_id="task-replay-diff"),
                        cancel_request_id="cr-a",
                    )
                )
                self.assertEqual(resp1.outcome, CancelOutcome.CANCEL_INTENT_RECORDED)
                await asyncio.to_thread(executor.captured().result, 5.0)
                self.assertEqual(dispatch_counter[0], 1)

                resp2 = await main.cancel_task(
                    CancelTaskRequest(
                        by_task_id=TaskIdCancelTarget(task_id="task-replay-diff"),
                        cancel_request_id="cr-b",
                    )
                )
                self.assertEqual(resp2.outcome, CancelOutcome.CANCEL_INTENT_RECORDED)
                await asyncio.to_thread(executor.captured().result, 5.0)
                self.assertEqual(dispatch_counter[0], 2)
        finally:
            executor.shutdown(wait=True)

    async def test_concurrent_replays_coalesce_to_one_inflight_write(self) -> None:
        """While a writer is blocked, concurrent stops coalesce.  After release
        and the captured Future proves the full cycle ended, the next stop
        dispatches exactly once more.  Zero sleeps."""
        self.save_task("task-coalesce", TaskStatus.RUNNING)
        handle = cancel_registry_module.registry.register("task-coalesce")
        self.registered_task_ids.append("task-coalesce")
        executor = _CapturingExecutor(max_workers=2)
        dispatch_counter: list[int] = [0]
        entered: threading.Event = threading.Event()
        release: threading.Event = threading.Event()

        def blocking_writer():
            dispatch_counter[0] += 1
            entered.set()
            release.wait()  # test controls when this writer finishes

        handle.set_marker_writer(blocking_writer)

        try:
            with patch.object(
                cancel_registry_module, "_get_write_pool", return_value=executor
            ):
                # First cancel — the writer blocks inside the pool thread.
                await main.cancel_task(
                    CancelTaskRequest(
                        by_task_id=TaskIdCancelTarget(task_id="task-coalesce"),
                        cancel_request_id="cr-blocked",
                    )
                )
                # Snapshot the in-flight Future so we can wait for it later.
                in_flight = executor.captured()
                self.assertTrue(await asyncio.to_thread(entered.wait, 5.0))
                self.assertEqual(dispatch_counter[0], 1)

                # Fire more stops while the writer is blocked.
                for i in range(3):
                    await main.cancel_task(
                        CancelTaskRequest(
                            by_task_id=TaskIdCancelTarget(task_id="task-coalesce"),
                            cancel_request_id=f"cr-coalesce-{i}",
                        )
                    )
                # None submitted a second Future — all coalesced.
                self.assertIsNone(executor.captured_nowait())
                self.assertEqual(dispatch_counter[0], 1)

                # Release the blocked writer and wait for the full cycle.
                release.set()
                await asyncio.to_thread(in_flight.result, 5.0)
                self.assertEqual(dispatch_counter[0], 1)

                # Now idle is True; a further stop dispatches once more.
                await main.cancel_task(
                    CancelTaskRequest(
                        by_task_id=TaskIdCancelTarget(task_id="task-coalesce"),
                        cancel_request_id="cr-after-unblock",
                    )
                )
                await asyncio.to_thread(executor.captured().result, 5.0)
                self.assertEqual(dispatch_counter[0], 2)

                # No stray third Future either.
                self.assertIsNone(executor.captured_nowait())
        finally:
            executor.shutdown(wait=True)


class CancelEndpointHttpLayerTest(unittest.TestCase):
    """codex c6c49248: real HTTP-layer tests that catch body-type 422 leaks.

    The endpoint handler validates fields inside the function body (400),
    but FastAPI's builtin request-body parsing runs BEFORE the handler and
    answers 422 for type/shape defects by default.  The exception handler
    mapped on /tasks/cancel must convert those to 400.
    """

    @classmethod
    def setUpClass(cls) -> None:
        cls.client = TestClient(main.app)

    def test_cancel_with_object_body_where_array_expected_returns_400(self) -> None:
        """by_task_id must be an object; passing an array is a schema fault."""
        resp = self.client.post(
            "/tasks/cancel",
            json={"by_task_id": [1, 2, 3], "cancel_request_id": "cr-test"},
        )
        self.assertEqual(resp.status_code, 400)

    def test_cancel_with_scalar_type_instead_of_object_returns_400(self) -> None:
        """cancel_request_id must be a string; a number is a schema fault."""
        resp = self.client.post(
            "/tasks/cancel",
            json={
                "by_task_id": {"task_id": "task-x"},
                "cancel_request_id": 42,
            },
        )
        self.assertEqual(resp.status_code, 400)

    def test_cancel_with_non_object_request_body_returns_400(self) -> None:
        """The body must be a JSON object; a bare array must answer 400."""
        resp = self.client.post("/tasks/cancel", json=[1, 2, 3])
        self.assertEqual(resp.status_code, 400)

    def test_cancel_with_malformed_json_returns_400(self) -> None:
        """An undecodable body must also be 400, not a bare 422."""
        resp = self.client.post(
            "/tasks/cancel",
            data=b"not json",
            headers={"Content-Type": "application/json"},
        )
        self.assertEqual(resp.status_code, 400)

    def test_cancel_with_nested_field_type_mismatch_returns_400(self) -> None:
        """task_id inside by_task_id is a string; a number must answer 400."""
        resp = self.client.post(
            "/tasks/cancel",
            json={
                "by_task_id": {"task_id": 123},
                "cancel_request_id": "cr-test",
            },
        )
        self.assertEqual(resp.status_code, 400)

    def test_non_cancel_route_still_returns_422(self) -> None:
        """codex 5e7f7a48: the cancel 400 handler MUST NOT change behavior
        on other routes.  POST /tasks with a broken body must still be 422."""
        resp = self.client.post("/tasks", json=[])
        self.assertEqual(resp.status_code, 422)
