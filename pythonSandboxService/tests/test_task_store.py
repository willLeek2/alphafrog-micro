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
    pass
if __name__ == "__main__":
    unittest.main()
