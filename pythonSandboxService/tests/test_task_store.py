from __future__ import annotations

import tempfile
import unittest
import hashlib
from pathlib import Path

from app.models import ExecuteRequest, Task, TaskStatus
from app.task_store import DurableTaskStore, OperationConflictError


FINGERPRINT_A = "sha256:" + "a" * 64
FINGERPRINT_B = "sha256:" + "b" * 64


def _request(code: str = "print(1)", fingerprint: str = FINGERPRINT_A) -> ExecuteRequest:
    return ExecuteRequest(
        dataset_id="dataset-1",
        code=code,
        operation_id="run-1:call-1:1",
        request_fingerprint=fingerprint,
        resource_class="STANDARD",
        memory_limit_bytes=512 * 1024 * 1024,
        timeout_millis=60_000,
        runtime_environment_version="python-runtime-v1",
        canonical_spec_schema_version="sandbox_create_v1",
        code_hash="sha256:" + hashlib.sha256(code.encode("utf-8")).hexdigest(),
        immutable_dataset_snapshot_digest="sha256:" + "c" * 64,
        libraries_digest="sha256:" + "d" * 64,
        sandbox_options_digest="sha256:" + "e" * 64,
    )


class DurableTaskStoreTest(unittest.TestCase):
    def test_same_operation_fingerprint_and_payload_returns_same_task(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = DurableTaskStore(Path(temp_dir) / "state.json")
            first = store.create(Task(task_id="task-1", status=TaskStatus.QUEUED, request=_request()))
            duplicate = store.create(Task(task_id="task-2", status=TaskStatus.QUEUED, request=_request()))

            self.assertFalse(first.existing)
            self.assertTrue(duplicate.existing)
            self.assertEqual(duplicate.task.task_id, "task-1")
            self.assertEqual(len(store.tasks), 1)

    def test_same_supplied_fingerprint_cannot_mask_a_different_payload(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = DurableTaskStore(Path(temp_dir) / "state.json")
            store.create(Task(task_id="task-1", status=TaskStatus.QUEUED, request=_request()))

            with self.assertRaises(OperationConflictError):
                store.create(Task(task_id="task-2", status=TaskStatus.QUEUED, request=_request(code="print(2)")))

    def test_different_fingerprint_conflicts_and_restart_query_is_durable(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            state_path = Path(temp_dir) / "state.json"
            store = DurableTaskStore(state_path)
            store.create(Task(task_id="task-1", status=TaskStatus.QUEUED, request=_request()))
            with self.assertRaises(OperationConflictError):
                store.create(Task(
                    task_id="task-2",
                    status=TaskStatus.QUEUED,
                    request=_request(fingerprint=FINGERPRINT_B),
                ))

            restored = DurableTaskStore(state_path)
            task = restored.get_by_operation_id("run-1:call-1:1")
            self.assertIsNotNone(task)
            self.assertEqual(task.task_id, "task-1")
            operation = restored.operations["run-1:call-1:1"]
            self.assertEqual(operation["request_fingerprint"], FINGERPRINT_A)
            self.assertTrue(operation["payload_digest"].startswith("sha256:"))

    def test_restart_terminalizes_abandoned_running_task_without_losing_operation_index(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            state_path = Path(temp_dir) / "state.json"
            store = DurableTaskStore(state_path)
            task = Task(task_id="task-1", status=TaskStatus.RUNNING, request=_request())
            store.create(task)

            restored = DurableTaskStore(state_path)
            queued = restored.recover_after_restart()
            recovered_task = restored.get_by_operation_id("run-1:call-1:1")

            self.assertEqual(queued, [])
            self.assertEqual(recovered_task.status, TaskStatus.FAILED)
            self.assertIn("restarted", recovered_task.error)
            self.assertIsNotNone(recovered_task.finished_at)


if __name__ == "__main__":
    unittest.main()
