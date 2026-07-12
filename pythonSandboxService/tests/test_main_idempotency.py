from __future__ import annotations

import asyncio
import hashlib
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest.mock import patch

llm_sandbox = types.ModuleType("llm_sandbox")
llm_sandbox.SandboxSession = object
llm_sandbox_exceptions = types.ModuleType("llm_sandbox.exceptions")
llm_sandbox_exceptions.SandboxTimeoutError = TimeoutError
sys.modules.setdefault("llm_sandbox", llm_sandbox)
sys.modules.setdefault("llm_sandbox.exceptions", llm_sandbox_exceptions)

from fastapi import HTTPException  # noqa: E402

from app import main  # noqa: E402
from app.canonical_fingerprint import CanonicalSandboxCreateSpec  # noqa: E402
from app.models import ExecuteRequest  # noqa: E402
from app.task_store import DurableTaskStore  # noqa: E402


class MainIdempotencyTest(unittest.IsolatedAsyncioTestCase):
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

    async def asyncTearDown(self) -> None:
        self.queue_patch.stop()
        self.tasks_patch.stop()
        self.store_patch.stop()
        self.temp_dir.cleanup()

    def request(self, code: str = "print(1)") -> ExecuteRequest:
        code_hash = "sha256:" + hashlib.sha256(code.encode("utf-8")).hexdigest()
        spec = CanonicalSandboxCreateSpec(
            schema_version="sandbox_create_v1",
            operation_id="run-1:call-1:1",
            code_hash=code_hash,
            immutable_dataset_snapshot_digest="sha256:" + "c" * 64,
            resource_class="STANDARD",
            memory_limit_bytes=512 * 1024 * 1024,
            timeout_millis=60_000,
            runtime_environment_version="python-runtime-v1",
            libraries_digest="sha256:" + "d" * 64,
            sandbox_options_digest="sha256:" + "e" * 64,
        )
        return ExecuteRequest(
            dataset_id="dataset-1",
            code=code,
            operation_id="run-1:call-1:1",
            request_fingerprint=spec.request_fingerprint(),
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

    async def test_duplicate_create_returns_existing_without_duplicate_queue_entry(self) -> None:
        first = await main.create_task(self.request())
        duplicate = await main.create_task(self.request())

        self.assertFalse(first.existing)
        self.assertTrue(duplicate.existing)
        self.assertEqual(first.task_id, duplicate.task_id)
        self.assertEqual(self.queue.qsize(), 1)
        lookup = await main.get_task_by_operation_id("run-1:call-1:1")
        self.assertTrue(lookup.found)
        self.assertEqual(lookup.task_id, first.task_id)

    async def test_same_fingerprint_with_changed_payload_returns_conflict(self) -> None:
        original = self.request()
        await main.create_task(original)
        changed = self.request(code="print(2)")
        changed.request_fingerprint = original.request_fingerprint

        with self.assertRaises(HTTPException) as raised:
            await main.create_task(changed)

        self.assertEqual(raised.exception.status_code, 409)
        self.assertEqual(self.queue.qsize(), 1)

    async def test_resource_class_memory_mismatch_is_rejected_before_persist(self) -> None:
        request = self.request()
        request.resource_class = "HEAVY"
        request.capacity_units = 3

        with self.assertRaises(HTTPException) as raised:
            await main.create_task(request)

        self.assertEqual(raised.exception.status_code, 400)
        self.assertEqual(len(self.store.tasks), 0)


if __name__ == "__main__":
    unittest.main()
