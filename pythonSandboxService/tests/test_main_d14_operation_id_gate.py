"""D14 (Q-14): production refuse create without operation_id.

Default config.allow_create_without_operation_id is False. Explicit
AF_SANDBOX_ALLOW_CREATE_WITHOUT_OPERATION_ID=true is non-production only
(no operation index / no idempotent recovery).
"""

from __future__ import annotations

import asyncio
import dataclasses
import hashlib
import os
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest.mock import patch

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
from app.models import ExecuteRequest  # noqa: E402
from app.task_store import DurableTaskStore  # noqa: E402


class MainD14OperationIdGateTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.store = DurableTaskStore(Path(self.temp_dir.name) / "state.json")
        self.queue: asyncio.Queue = asyncio.Queue()
        self.store_patch = patch.object(main, "task_store", self.store)
        self.tasks_patch = patch.object(main, "tasks", self.store.tasks)
        self.queue_patch = patch.object(main, "task_queue", self.queue)
        # Production default for this suite.
        self.config_patch = patch.object(
            main,
            "config",
            dataclasses.replace(main.config, allow_create_without_operation_id=False),
        )
        self.store_patch.start()
        self.tasks_patch.start()
        self.queue_patch.start()
        self.config_patch.start()

    async def asyncTearDown(self) -> None:
        self.config_patch.stop()
        self.queue_patch.stop()
        self.tasks_patch.stop()
        self.store_patch.stop()
        self.temp_dir.cleanup()

    def keyed_request(self, code: str = "print(1)") -> ExecuteRequest:
        code_hash = "sha256:" + hashlib.sha256(code.encode("utf-8")).hexdigest()
        operation_id = "run-1:call-1:1"
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
        return ExecuteRequest(
            dataset_id="dataset-1",
            code=code,
            operation_id=operation_id,
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

    def keyless_request(self) -> ExecuteRequest:
        return ExecuteRequest(
            dataset_id="dataset-1",
            code="print(1)",
            resource_class="STANDARD",
            capacity_units=1,
            memory_limit_bytes=512 * 1024 * 1024,
            timeout_millis=60_000,
        )

    async def test_production_rejects_create_without_operation_id(self) -> None:
        with self.assertRaises(HTTPException) as raised:
            await main.create_task(self.keyless_request())
        self.assertEqual(raised.exception.status_code, 400)
        self.assertIn("operation_id is required", str(raised.exception.detail))
        self.assertEqual(len(self.store.tasks), 0)

    async def test_production_rejects_whitespace_only_operation_id(self) -> None:
        request = self.keyless_request()
        request.operation_id = "   "
        with self.assertRaises(HTTPException) as raised:
            await main.create_task(request)
        self.assertEqual(raised.exception.status_code, 400)
        self.assertIn("operation_id is required", str(raised.exception.detail))
        self.assertEqual(len(self.store.tasks), 0)

    async def test_keyed_create_still_succeeds_and_indexes_operation(self) -> None:
        response = await main.create_task(self.keyed_request())
        self.assertTrue(response.task_id)
        lookup = await main.get_task_by_operation_id("run-1:call-1:1")
        self.assertTrue(lookup.found)
        self.assertEqual(lookup.task_id, response.task_id)

    async def test_compat_flag_does_not_bypass_keyed_units_validation(self) -> None:
        """Compat switch only admits keyless creates; keyed still validates."""
        request = self.keyed_request()
        request.capacity_units = 99  # STANDARD expects 1
        with patch.object(
            main,
            "config",
            dataclasses.replace(main.config, allow_create_without_operation_id=True),
        ):
            with self.assertRaises(HTTPException) as raised:
                await main.create_task(request)
        self.assertEqual(raised.exception.status_code, 400)
        self.assertIn("capacity_units", str(raised.exception.detail))
        self.assertEqual(len(self.store.tasks), 0)

    async def test_explicit_non_production_flag_allows_keyless_create(self) -> None:
        with patch.object(
            main,
            "config",
            dataclasses.replace(main.config, allow_create_without_operation_id=True),
        ):
            response = await main.create_task(self.keyless_request())
        self.assertTrue(response.task_id)
        # Keyless create must NOT write an operation index entry.
        lookup = await main.get_task_by_operation_id("run-1:call-1:1")
        self.assertFalse(lookup.found)


if __name__ == "__main__":
    unittest.main()
