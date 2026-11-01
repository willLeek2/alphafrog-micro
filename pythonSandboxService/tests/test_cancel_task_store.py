"""D11 (task #108) cancellation-semantics tests for app/task_store.py.

Unit under test: DurableTaskStore (single state.json file, one RLock,
schema v3).  These tests pin the cancel_by_task_id / cancel_by_operation
contracts, the durable cancelRequestId binding (replay and conflict), the
pre-create tombstone and its adoption, the completion-evidence rules,
restart recovery, admission rollback under QueueFull, and the synthetic
CANCELED result shape.  The store API is synchronous, so plain
unittest.TestCase is sufficient.  app/task_store.py only imports app.models
(pydantic), so no llm_sandbox stubbing is needed.
"""

from __future__ import annotations

import asyncio
import hashlib
import json
import tempfile
import threading
import unittest
from pathlib import Path

from app.models import (
    CancelOutcome,
    CancellationEvidence,
    EffectiveOutputLimits,
    ExecuteRequest,
    ExecuteResult,
    SandboxResourceUsage,
    Task,
    TaskStatus,
)
from app.task_store import (
    SCHEMA_VERSION_V3,
    SCHEMA_VERSION_V5,
    SYNTHETIC_EXIT_CODE,
    CancelRequestBindingError,
    CompletionCandidate,
    DurableTaskStore,
    OperationConflictError,
    _MISSING_MEASUREMENT_FIELDS,
    build_canceled_result,
    request_payload_digest,
)


FINGERPRINT_A = "sha256:" + "a" * 64
FINGERPRINT_B = "sha256:" + "b" * 64
OPERATION_ID = "run-1:call-1:1"
IMAGE_REF = "registry.local/alphafrog/runtime@sha256:" + "f" * 64


def plain_request(code: str = "print(1)") -> ExecuteRequest:
    """A request without operation identity (no idempotency validation)."""
    return ExecuteRequest(dataset_id="dataset-1", code=code)


def identified_request(
    operation_id: str = OPERATION_ID,
    fingerprint: str = FINGERPRINT_A,
    code: str = "print(1)",
) -> ExecuteRequest:
    """A request carrying full canonical create-spec identity.

    models.ExecuteRequest.validate_idempotency_identity requires operation_id
    and request_fingerprint together PLUS the six canonical spec fields.
    """
    return ExecuteRequest(
        dataset_id="dataset-1",
        code=code,
        operation_id=operation_id,
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


def sample_limits() -> EffectiveOutputLimits:
    return EffectiveOutputLimits(
        stdoutMaxBytes=1024,
        stderrMaxBytes=2048,
        recordChannelMaxBytes=4096,
        recordChannelMaxRecords=32,
        sourceRevision="rev-test-1",
    )


class _TaskStoreTestBase(unittest.TestCase):
    """Shared scaffolding: a fresh temp dir + DurableTaskStore per test."""

    def setUp(self) -> None:
        self._temp_dir = tempfile.TemporaryDirectory()
        self.state_path = Path(self._temp_dir.name) / "state.json"
        self.store = DurableTaskStore(self.state_path)

    def tearDown(self) -> None:
        self._temp_dir.cleanup()

    def reload_store(self) -> DurableTaskStore:
        """Simulate a service restart: a second store on the same file."""
        return DurableTaskStore(self.state_path)

    def add_queued_task(
        self, task_id: str, request: ExecuteRequest | None = None
    ) -> Task:
        task = Task(
            task_id=task_id,
            status=TaskStatus.QUEUED,
            request=request if request is not None else plain_request(),
        )
        self.store.create_with_admission(task)
        return task


class CancelByTaskIdTests(_TaskStoreTestBase):
    pass
class CancelBindingDurabilityTests(_TaskStoreTestBase):
    pass
class CancelByOperationTests(_TaskStoreTestBase):
    pass
class TombstoneAdoptionTests(_TaskStoreTestBase):
    pass
class ExecutionTransitionTests(_TaskStoreTestBase):
    pass
class RecoveryTests(_TaskStoreTestBase):
    pass
class _SecondPersistFailsStore(DurableTaskStore):
    """Test double: fails from the Nth _persist_locked call onward."""

    def __init__(self, state_path: Path) -> None:
        self.persist_call_count = 0
        self.fail_from_call: int | None = None
        super().__init__(state_path)

    def _persist_locked(self) -> None:
        self.persist_call_count += 1
        if (
            self.fail_from_call is not None
            and self.persist_call_count >= self.fail_from_call
        ):
            raise OSError(
                f"injected persist failure on call {self.persist_call_count}"
            )
        super()._persist_locked()


class AdmissionRollbackTests(_TaskStoreTestBase):
    pass
class SyntheticResultShapeTests(unittest.TestCase):
    pass
if __name__ == "__main__":
    unittest.main()
