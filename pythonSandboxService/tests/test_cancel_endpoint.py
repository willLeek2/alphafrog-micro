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
import os
import queue
import sys
import tempfile
import threading
import types
import unittest
from concurrent.futures import Future, ThreadPoolExecutor
from pathlib import Path
from unittest.mock import patch

# D15 release-binding (codex a1b749ad) made AF_SANDBOX_IMAGE required at
# config load (no implicit default, no silent 'latest' fallback). Set a
# valid digest reference before app.main import so the cancel endpoint
# tests can load the production config. Same pattern as
# test_main_d14_operation_id_gate.
os.environ.setdefault(
    "AF_SANDBOX_IMAGE",
    "registry.local/alphafrog/runtime@sha256:" + "a" * 64,
)
# 260814 scheduler-03: 默认校验模式 local-image-id 只接受裸 sha256:<64hex>；
# 本测试用 digest 引用（registry/...@sha256:...），按 test_main_d13_error_surface
# 同款方式固定 strict-release 模式。
os.environ.setdefault("AF_SANDBOX_IMAGE_VERIFY_MODE", "strict-release")

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

    create_task consults ``full()`` (after a stale-resident purge pass)
    and admits via ``put_nowait`` with one purge-retry; this stub controls
    both independently so the admission race (queue fills BETWEEN the
    full() check and the put) and the genuinely-full case can be tested
    without any real queue capacity juggling. A "full" stub carries one
    phantom resident no store knows: the purge pass drops it as stale,
    and the endpoint still reaches the tested refusal / rollback path.
    """

    def __init__(self, full: bool) -> None:
        self._full = full
        self.put_nowait_calls: list = []
        self._residents: list = ["stub-phantom-resident"] if full else []

    def full(self) -> bool:
        return self._full and bool(self._residents)

    def empty(self) -> bool:
        return not self._residents

    def get_nowait(self):
        return self._residents.pop(0)

    def task_done(self) -> None:
        pass

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

    # ------------------------------------------------------------------ #
    # by_task_id happy paths
    # ------------------------------------------------------------------ #

    # ------------------------------------------------------------------ #
    # by_operation paths (tombstone, adoption, conflicts)
    # ------------------------------------------------------------------ #

    # ------------------------------------------------------------------ #
    # constraint 1 (codex 4334bc9d): QueueFull rollback shares ONE store
    # critical section with the dedup re-check, insert and persist
    # ------------------------------------------------------------------ #

    # ------------------------------------------------------------------ #
    # codex c6c49248: cancel replay must RE-DISPATCH the marker after a
    # first write finishes (success or failure).  Writer failures on the
    # first attempt must not block retry through same-key replay or a
    # fresh cancelRequestId.
    # ------------------------------------------------------------------ #

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

