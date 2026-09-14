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
    def test_cancel_queued_task_terminalizes_with_honest_result(self) -> None:
        # Contract: canceling a QUEUED task durably terminalizes it to
        # CANCELED with the honest synthetic result (nothing ever ran).
        task = self.add_queued_task("task-q")

        decision = self.store.cancel_by_task_id("cr-q1", "task-q", "USER_REQUEST")

        self.assertEqual(decision.outcome, CancelOutcome.CANCELED.value)
        self.assertEqual(decision.task_id, "task-q")
        self.assertEqual(decision.status, TaskStatus.CANCELED)
        self.assertFalse(decision.replayed)

        self.assertEqual(task.status, TaskStatus.CANCELED)
        self.assertEqual(
            task.cancellation_evidence, CancellationEvidence.QUEUED_CANCEL
        )
        self.assertTrue(task.cancel_requested)
        self.assertEqual(task.cancel_reason, "USER_REQUEST")
        self.assertIsNotNone(task.finished_at)
        self.assertFalse(task.retryable)

        result = task.result
        self.assertIsNotNone(result)
        self.assertEqual(result.exit_code, SYNTHETIC_EXIT_CODE)
        self.assertEqual(result.resource_usage.exit_reason, "CANCELED")
        self.assertFalse(result.retryable)
        self.assertEqual(result.resource_usage.resource_class, "STANDARD")

        # Durable: a restarted store sees the same terminal state.
        reloaded = self.reload_store()
        self.assertEqual(reloaded.get("task-q").status, TaskStatus.CANCELED)

    def test_cancel_running_task_records_intent_only(self) -> None:
        # Contract: canceling a RUNNING task only records the durable intent;
        # the task stays RUNNING and NO result is fabricated.
        task = self.add_queued_task("task-r")
        self.assertTrue(self.store.begin_execution("task-r"))

        decision = self.store.cancel_by_task_id("cr-r1", "task-r", "RUN_CANCELED")

        self.assertEqual(decision.outcome, CancelOutcome.CANCEL_INTENT_RECORDED.value)
        self.assertEqual(decision.task_id, "task-r")
        self.assertEqual(decision.status, TaskStatus.RUNNING)
        self.assertEqual(task.status, TaskStatus.RUNNING)
        self.assertTrue(task.cancel_requested)
        self.assertEqual(task.cancel_reason, "RUN_CANCELED")
        self.assertIsNone(task.result)
        self.assertIsNone(task.finished_at)

        reloaded = self.reload_store()
        reloaded_task = reloaded.get("task-r")
        self.assertEqual(reloaded_task.status, TaskStatus.RUNNING)
        self.assertTrue(reloaded_task.cancel_requested)

    def test_cancel_terminal_tasks_report_already_terminal(self) -> None:
        # Contract: canceling an already-terminal task reports
        # ALREADY_TERMINAL echoing the task's current status, unchanged.
        succeeded = self.add_queued_task("task-s")
        succeeded.status = TaskStatus.SUCCEEDED
        self.store.save(succeeded)
        failed = self.add_queued_task("task-f")
        failed.status = TaskStatus.FAILED
        self.store.save(failed)
        self.add_queued_task("task-c")
        self.store.cancel_by_task_id("cr-c1", "task-c", "USER_REQUEST")

        cases = [
            ("cr-term-s", "task-s", TaskStatus.SUCCEEDED),
            ("cr-term-f", "task-f", TaskStatus.FAILED),
            ("cr-term-c", "task-c", TaskStatus.CANCELED),
        ]
        for cancel_id, task_id, expected_status in cases:
            with self.subTest(task_id=task_id):
                decision = self.store.cancel_by_task_id(cancel_id, task_id, "late")
                self.assertEqual(
                    decision.outcome, CancelOutcome.ALREADY_TERMINAL.value
                )
                self.assertEqual(decision.task_id, task_id)
                self.assertEqual(decision.status, expected_status)
        # Terminal tasks stay untouched: no result fabricated for the manual ones.
        self.assertIsNone(self.store.get("task-s").result)
        self.assertIsNone(self.store.get("task-f").result)

    def test_cancel_unknown_task_reports_not_found(self) -> None:
        # Contract: an unknown taskId is the business NOT_FOUND (task_id None,
        # no task created); the binding is still recorded so same-key replays
        # stay stable.
        decision = self.store.cancel_by_task_id("cr-nf", "task-ghost", "USER_REQUEST")

        self.assertEqual(decision.outcome, CancelOutcome.NOT_FOUND.value)
        self.assertIsNone(decision.task_id)
        self.assertIsNone(decision.status)
        self.assertFalse(decision.replayed)
        self.assertEqual(self.store.tasks, {})

        self.assertIn("cr-nf", self.store.cancel_requests)
        replay = self.store.cancel_by_task_id("cr-nf", "task-ghost", "USER_REQUEST")
        self.assertTrue(replay.replayed)
        self.assertEqual(replay.outcome, CancelOutcome.NOT_FOUND.value)
        self.assertIsNone(replay.task_id)
        self.assertIsNone(replay.status)


class CancelBindingDurabilityTests(_TaskStoreTestBase):
    def test_same_target_replay_returns_first_outcome_with_live_status(self) -> None:
        # Contract: same cancel_request_id + same target replays the FIRST
        # recorded outcome with replayed=True; status is a LIVE lookup that
        # may legitimately advance between the original call and the replay.
        self.add_queued_task("task-1")
        first = self.store.cancel_by_task_id("cr-rep", "task-1", "USER_REQUEST")
        self.assertEqual(first.outcome, CancelOutcome.CANCELED.value)
        self.assertFalse(first.replayed)

        replay = self.store.cancel_by_task_id("cr-rep", "task-1", "USER_REQUEST")
        self.assertTrue(replay.replayed)
        self.assertEqual(replay.outcome, CancelOutcome.CANCELED.value)
        self.assertEqual(replay.task_id, "task-1")
        self.assertEqual(replay.status, TaskStatus.CANCELED)

        # RUNNING variant: first outcome stays CANCEL_INTENT_RECORDED while
        # the live status advances to CANCELED via completion evidence.
        self.add_queued_task("task-2")
        self.assertTrue(self.store.begin_execution("task-2"))
        intent = self.store.cancel_by_task_id("cr-rep2", "task-2", "USER_REQUEST")
        self.assertEqual(intent.outcome, CancelOutcome.CANCEL_INTENT_RECORDED.value)
        self.assertEqual(intent.status, TaskStatus.RUNNING)

        observed = ExecuteResult(
            exit_code=137,
            stdout="",
            stderr="",
            dataset_dir="",
            resource_usage=SandboxResourceUsage(
                resource_class="STANDARD", exit_reason="KILLED"
            ),
            retryable=None,
        )
        self.store.complete_execution(
            "task-2",
            CompletionCandidate(
                status=TaskStatus.FAILED,
                result=observed,
                evidence=CancellationEvidence.MARKER_OBSERVED,
            ),
        )
        replay2 = self.store.cancel_by_task_id("cr-rep2", "task-2", "USER_REQUEST")
        self.assertTrue(replay2.replayed)
        self.assertEqual(replay2.outcome, CancelOutcome.CANCEL_INTENT_RECORDED.value)
        self.assertEqual(replay2.task_id, "task-2")
        self.assertEqual(replay2.status, TaskStatus.CANCELED)

    def test_rebinding_to_different_target_conflicts(self) -> None:
        # Contract: a cancel_request_id is a DURABLE binding to one target
        # identity; any different target (different task_id, different target
        # type, different fingerprint) raises CancelRequestBindingError (409).
        self.add_queued_task("task-1")
        self.add_queued_task("task-2")
        self.store.cancel_by_task_id("cr-bind", "task-1", "r")

        with self.assertRaises(CancelRequestBindingError):
            self.store.cancel_by_task_id("cr-bind", "task-2", "r")
        with self.assertRaises(CancelRequestBindingError):
            self.store.cancel_by_operation(
                "cr-bind", OPERATION_ID, FINGERPRINT_A, "r"
            )

        request = identified_request(operation_id="run-2:call-2:1")
        task3 = Task(task_id="task-3", status=TaskStatus.QUEUED, request=request)
        self.store.create_with_admission(task3)
        self.store.cancel_by_operation(
            "cr-bind2", "run-2:call-2:1", FINGERPRINT_A, "r"
        )
        with self.assertRaises(CancelRequestBindingError):
            self.store.cancel_by_task_id("cr-bind2", "task-1", "r")
        with self.assertRaises(CancelRequestBindingError):
            self.store.cancel_by_operation(
                "cr-bind2", "run-2:call-2:1", FINGERPRINT_B, "r"
            )

        # Failed rebinds must not have altered the original targets.
        self.assertEqual(self.store.get("task-2").status, TaskStatus.QUEUED)
        self.assertEqual(self.store.get("task-3").status, TaskStatus.CANCELED)

    def test_cancel_requests_survive_schema_v3_round_trip(self) -> None:
        # Contract: cancel_requests are part of the schema v3 document; after
        # a restart the same-target replay still answers correctly.
        self.add_queued_task("task-rt")
        self.store.cancel_by_task_id("cr-rt", "task-rt", "USER_REQUEST")

        document = json.loads(self.state_path.read_text(encoding="utf-8"))
        self.assertEqual(document["schema_version"], SCHEMA_VERSION_V3)
        self.assertEqual(document["schema_version"], "sandbox_task_store_v3")
        self.assertIn("cr-rt", document["cancel_requests"])
        self.assertEqual(
            document["cancel_requests"]["cr-rt"]["target_type"], "by_task_id"
        )

        reloaded = self.reload_store()
        replay = reloaded.cancel_by_task_id("cr-rt", "task-rt", "USER_REQUEST")
        self.assertTrue(replay.replayed)
        self.assertEqual(replay.outcome, CancelOutcome.CANCELED.value)
        self.assertEqual(replay.status, TaskStatus.CANCELED)
        self.assertEqual(replay.task_id, "task-rt")


class CancelByOperationTests(_TaskStoreTestBase):
    def test_unknown_operation_creates_pre_create_tombstone(self) -> None:
        # Contract: canceling an UNKNOWN operation creates a durable
        # pre-create tombstone (CANCELED, request None, honest result,
        # PRE_CREATE_CANCEL evidence) bound in the operations index with a
        # null payload_digest until adoption.
        decision = self.store.cancel_by_operation(
            "cr-pre1", "run-9:call-9:1", FINGERPRINT_A, "RUN_CANCELED"
        )

        self.assertEqual(decision.outcome, CancelOutcome.CANCELED.value)
        self.assertEqual(decision.status, TaskStatus.CANCELED)
        task_id = decision.task_id
        self.assertIsNotNone(task_id)

        tombstone = self.store.get(task_id)
        self.assertIsNotNone(tombstone)
        self.assertEqual(tombstone.status, TaskStatus.CANCELED)
        self.assertIsNone(tombstone.request)
        self.assertIsNone(tombstone.started_at)
        self.assertIsNotNone(tombstone.created_at)
        self.assertIsNotNone(tombstone.finished_at)
        self.assertTrue(tombstone.cancel_requested)
        self.assertEqual(tombstone.cancel_reason, "RUN_CANCELED")
        self.assertEqual(
            tombstone.cancellation_evidence, CancellationEvidence.PRE_CREATE_CANCEL
        )
        self.assertFalse(tombstone.retryable)

        result = tombstone.result
        self.assertIsNotNone(result)
        self.assertEqual(result.exit_code, SYNTHETIC_EXIT_CODE)
        self.assertEqual(result.stdout, "")
        self.assertEqual(result.stderr, "")
        self.assertEqual(result.resource_usage.exit_reason, "CANCELED")
        self.assertEqual(result.resource_usage.resource_class, "UNKNOWN")
        self.assertFalse(result.retryable)

        entry = self.store.operations["run-9:call-9:1"]
        self.assertEqual(entry["task_id"], task_id)
        self.assertEqual(entry["request_fingerprint"], FINGERPRINT_A)
        self.assertIsNone(entry["payload_digest"])
        self.assertEqual(self.store.get_by_operation_id("run-9:call-9:1").task_id, task_id)

        # The v3 load invariants accept the tombstone across a restart.
        reloaded = self.reload_store()
        self.assertEqual(reloaded.get(task_id).status, TaskStatus.CANCELED)
        self.assertIsNone(reloaded.get(task_id).request)

    def test_known_queued_task_canceled_via_operation(self) -> None:
        # Contract: cancel_by_operation on a known QUEUED task has the same
        # terminalizing semantics as cancel_by_task_id.
        request = identified_request(operation_id="run-3:call-3:1")
        task = Task(task_id="task-opq", status=TaskStatus.QUEUED, request=request)
        self.store.create_with_admission(task)

        # Wrong fingerprint for a KNOWN operation is a conflict, task untouched.
        with self.assertRaises(OperationConflictError):
            self.store.cancel_by_operation(
                "cr-op0", "run-3:call-3:1", FINGERPRINT_B, "r"
            )
        self.assertEqual(task.status, TaskStatus.QUEUED)

        decision = self.store.cancel_by_operation(
            "cr-op1", "run-3:call-3:1", FINGERPRINT_A, "USER_REQUEST"
        )
        self.assertEqual(decision.outcome, CancelOutcome.CANCELED.value)
        self.assertEqual(decision.task_id, "task-opq")
        self.assertEqual(decision.status, TaskStatus.CANCELED)

        self.assertEqual(task.status, TaskStatus.CANCELED)
        self.assertEqual(
            task.cancellation_evidence, CancellationEvidence.QUEUED_CANCEL
        )
        self.assertFalse(task.retryable)
        self.assertEqual(task.result.exit_code, SYNTHETIC_EXIT_CODE)
        self.assertEqual(task.result.resource_usage.exit_reason, "CANCELED")
        self.assertEqual(task.cancel_reason, "USER_REQUEST")
        self.assertIsNotNone(task.finished_at)

    def test_malformed_identity_rejected_before_any_state_change(self) -> None:
        # Contract: malformed operation_id or fingerprint is a ValueError
        # BEFORE the binding check — no task, no index entry, no binding, no
        # persist.
        with self.assertRaises(ValueError):
            self.store.cancel_by_operation("cr-bad1", "nocolons", FINGERPRINT_A, "r")
        with self.assertRaises(ValueError):
            self.store.cancel_by_operation(
                "cr-bad2", "run-1:call-1:1", "not-a-sha256", "r"
            )

        self.assertEqual(self.store.tasks, {})
        self.assertEqual(self.store.operations, {})
        self.assertEqual(self.store.cancel_requests, {})
        self.assertFalse(self.state_path.exists())


class TombstoneAdoptionTests(_TaskStoreTestBase):
    def test_adoption_fills_every_frozen_field_durably(self) -> None:
        # Contract: the first matching consult ADOPTS a pre-create tombstone,
        # filling request, fingerprint, payload digest, frozen output limits
        # and image ref while keeping the stable taskId, the CANCELED state
        # and the honest result — durably (survives reload).
        operation_id = "run-4:call-4:1"
        pre = self.store.cancel_by_operation(
            "cr-adopt", operation_id, FINGERPRINT_A, "RUN_CANCELED"
        )
        tombstone_id = pre.task_id

        request = identified_request(operation_id=operation_id)
        limits = sample_limits()
        decision = self.store.find_existing_or_adopt_tombstone(
            request, limits, IMAGE_REF
        )

        self.assertIsNotNone(decision)
        self.assertTrue(decision.existing)
        self.assertEqual(decision.task.task_id, tombstone_id)
        adopted = decision.task
        self.assertEqual(adopted.status, TaskStatus.CANCELED)
        self.assertEqual(adopted.request, request)
        self.assertEqual(adopted.request_fingerprint, FINGERPRINT_A)
        self.assertEqual(adopted.payload_digest, request_payload_digest(request))
        self.assertEqual(adopted.effective_output_limits, limits)
        self.assertEqual(adopted.runtime_image_ref, IMAGE_REF)
        # Cancel bookkeeping and honest result are kept.
        self.assertEqual(
            adopted.cancellation_evidence, CancellationEvidence.PRE_CREATE_CANCEL
        )
        self.assertEqual(adopted.cancel_reason, "RUN_CANCELED")
        self.assertEqual(adopted.result.exit_code, SYNTHETIC_EXIT_CODE)
        self.assertEqual(
            self.store.operations[operation_id]["payload_digest"],
            request_payload_digest(request),
        )

        # A late create_with_admission with the same identity also resolves to
        # the adopted tombstone (idempotent, no admission).
        late = Task(
            task_id="task-late",
            status=TaskStatus.QUEUED,
            request=identified_request(operation_id=operation_id),
        )
        create_again = self.store.create_with_admission(late)
        self.assertTrue(create_again.existing)
        self.assertEqual(create_again.task.task_id, tombstone_id)

        reloaded = self.reload_store()
        reloaded_task = reloaded.get_by_operation_id(operation_id)
        self.assertEqual(reloaded_task.task_id, tombstone_id)
        self.assertEqual(reloaded_task.status, TaskStatus.CANCELED)
        self.assertEqual(reloaded_task.request, request)
        self.assertEqual(reloaded_task.payload_digest, request_payload_digest(request))
        self.assertEqual(reloaded_task.effective_output_limits, limits)
        self.assertEqual(reloaded_task.runtime_image_ref, IMAGE_REF)

    def test_different_fingerprint_on_tombstone_conflicts(self) -> None:
        # Contract: a consult whose fingerprint differs from the tombstone's
        # binding is a 409 conflict and must not mutate the tombstone.
        pre = self.store.cancel_by_operation(
            "cr-conf", "run-5:call-5:1", FINGERPRINT_A, "r"
        )
        conflicting = identified_request(
            operation_id="run-5:call-5:1", fingerprint=FINGERPRINT_B
        )

        with self.assertRaises(OperationConflictError):
            self.store.find_existing_or_adopt_tombstone(
                conflicting, sample_limits(), IMAGE_REF
            )

        self.assertIsNone(self.store.operations["run-5:call-5:1"]["payload_digest"])
        self.assertIsNone(self.store.get(pre.task_id).request)

    def test_existing_normal_task_returned_without_mutation(self) -> None:
        # Contract: for an existing NORMAL task the consult returns the
        # existing-task decision without touching any field; unknown or
        # absent operation identity returns None (create must proceed).
        request = identified_request(operation_id="run-6:call-6:1")
        task = Task(task_id="task-normal", status=TaskStatus.QUEUED, request=request)
        created = self.store.create_with_admission(task)
        self.assertFalse(created.existing)
        self.assertIsNone(task.effective_output_limits)
        self.assertIsNone(task.runtime_image_ref)

        consult = self.store.find_existing_or_adopt_tombstone(
            request, sample_limits(), IMAGE_REF
        )
        self.assertIsNotNone(consult)
        self.assertTrue(consult.existing)
        self.assertIs(consult.task, task)
        # Not a tombstone: the consult must NOT fill frozen fields here.
        self.assertIsNone(task.effective_output_limits)
        self.assertIsNone(task.runtime_image_ref)
        self.assertEqual(task.payload_digest, request_payload_digest(request))

        self.assertIsNone(
            self.store.find_existing_or_adopt_tombstone(
                identified_request(operation_id="run-x:call-x:1"),
                sample_limits(),
                IMAGE_REF,
            )
        )
        self.assertIsNone(
            self.store.find_existing_or_adopt_tombstone(
                plain_request(), sample_limits(), IMAGE_REF
            )
        )


class ExecutionTransitionTests(_TaskStoreTestBase):
    def test_begin_execution_refuses_canceled_task(self) -> None:
        # Contract: begin_execution is the atomic QUEUED->RUNNING gate; a
        # task canceled in the meantime (or unknown) is refused with False.
        task = self.add_queued_task("task-be")
        self.store.cancel_by_task_id("cr-be", "task-be", "USER_REQUEST")
        self.assertFalse(self.store.begin_execution("task-be"))
        self.assertEqual(task.status, TaskStatus.CANCELED)
        self.assertFalse(self.store.begin_execution("task-ghost"))

        live = self.add_queued_task("task-live")
        self.assertTrue(self.store.begin_execution("task-live"))
        self.assertEqual(live.status, TaskStatus.RUNNING)
        self.assertIsNotNone(live.started_at)

    def test_marker_observed_with_intent_forces_canceled(self) -> None:
        # Contract (d6841a2e + codex c6c49248): MARKER_OBSERVED evidence
        # PLUS a durable cancel intent (cancel_requested True) forces
        # CANCELED.  Without the intent the genuine result stands.
        task = self.add_queued_task("task-mo")
        task.cancel_requested = True  # cancel intent was already recorded
        self.assertTrue(self.store.begin_execution("task-mo"))
        usage = SandboxResourceUsage(
            resource_class="STANDARD", exit_reason="KILLED", cpu_millis=12
        )
        observed = ExecuteResult(
            exit_code=137,
            stdout="partial",
            stderr="err",
            dataset_dir="",
            resource_usage=usage,
            retryable=True,
        )

        returned = self.store.complete_execution(
            "task-mo",
            CompletionCandidate(
                status=TaskStatus.FAILED,
                result=observed,
                evidence=CancellationEvidence.MARKER_OBSERVED,
            ),
        )

        self.assertIs(returned, task)
        self.assertEqual(task.status, TaskStatus.CANCELED)
        self.assertEqual(
            task.cancellation_evidence, CancellationEvidence.MARKER_OBSERVED
        )
        # Observations kept, classification forced.
        self.assertEqual(task.result.exit_code, 137)
        self.assertEqual(task.result.stdout, "partial")
        self.assertFalse(task.result.retryable)
        self.assertEqual(task.result.resource_usage.exit_reason, "CANCELED")
        self.assertEqual(task.resource_usage.exit_reason, "CANCELED")
        self.assertFalse(task.retryable)
        self.assertIsNone(task.error)
        self.assertIsNotNone(task.finished_at)

    def test_canceled_before_start_with_intent_builds_canceled_result(self) -> None:
        # Contract: CANCELED_BEFORE_START evidence PLUS cancel_requested=True
        # replaces the candidate result with the honest build_canceled_result.
        task = self.add_queued_task("task-cbs")
        task.cancel_requested = True
        self.assertTrue(self.store.begin_execution("task-cbs"))
        never_ran = ExecuteResult(
            exit_code=99, stdout="discarded", stderr="", dataset_dir="", retryable=True
        )

        self.store.complete_execution(
            "task-cbs",
            CompletionCandidate(
                status=TaskStatus.FAILED,
                result=never_ran,
                evidence=CancellationEvidence.CANCELED_BEFORE_START,
            ),
        )

        self.assertEqual(task.status, TaskStatus.CANCELED)
        self.assertEqual(
            task.cancellation_evidence, CancellationEvidence.CANCELED_BEFORE_START
        )
        self.assertEqual(task.result.exit_code, SYNTHETIC_EXIT_CODE)
        self.assertEqual(task.result.resource_usage.exit_reason, "CANCELED")
        self.assertEqual(task.result.resource_usage.resource_class, "STANDARD")
        self.assertFalse(task.result.retryable)
        self.assertFalse(task.retryable)

    def test_evidence_without_intent_preserves_genuine_result(self) -> None:
        # codex c6c49248: MARKER_OBSERVED or CANCELED_BEFORE_START evidence
        # WITHOUT a durable cancel intent (cancel_requested=False) must NOT
        # force CANCELED — a leftover marker or a stale stop signal must not
        # fabricate a cancellation for a task that was never cancelled.
        for evidence, label in (
            (CancellationEvidence.MARKER_OBSERVED, "marker"),
            (CancellationEvidence.CANCELED_BEFORE_START, "cbs"),
        ):
            with self.subTest(evidence=label):
                task = self.add_queued_task(f"task-no-intent-{label}")
                self.assertTrue(self.store.begin_execution(f"task-no-intent-{label}"))
                result = ExecuteResult(
                    exit_code=0, stdout="ok", stderr="", dataset_dir=""
                )
                self.store.complete_execution(
                    f"task-no-intent-{label}",
                    CompletionCandidate(
                        status=TaskStatus.SUCCEEDED,
                        result=result,
                        evidence=evidence,
                    ),
                )
                self.assertEqual(task.status, TaskStatus.SUCCEEDED)
                self.assertEqual(task.result.exit_code, 0)
                self.assertEqual(
                    task.cancellation_evidence, CancellationEvidence.NONE
                )

    def test_terminal_task_ignores_late_completion(self) -> None:
        # Contract: an already-terminal task is returned as-is; a late
        # completion (e.g. the cancel terminalized it first) changes nothing.
        task = self.add_queued_task("task-term")
        done = ExecuteResult(exit_code=0, stdout="ok", stderr="", dataset_dir="")
        self.store.complete_execution(
            "task-term", CompletionCandidate(status=TaskStatus.SUCCEEDED, result=done)
        )
        self.assertEqual(task.status, TaskStatus.SUCCEEDED)

        late = ExecuteResult(exit_code=1, stdout="late", stderr="", dataset_dir="")
        returned = self.store.complete_execution(
            "task-term",
            CompletionCandidate(
                status=TaskStatus.FAILED, result=late, error="late failure"
            ),
        )

        self.assertIs(returned, task)
        self.assertEqual(task.status, TaskStatus.SUCCEEDED)
        self.assertEqual(task.result.exit_code, 0)
        self.assertIsNone(task.error)

    def test_snapshot_modification_does_not_affect_store(self) -> None:
        """codex c6c49248: begin_execution returns a deep copy.  Mutating the
        snapshot must never corrupt the store's authoritative Task."""
        self.add_queued_task("task-snap-write")
        snapshot = self.store.begin_execution("task-snap-write")
        self.assertIsNotNone(snapshot)

        snapshot.request.dataset_id = "corrupted"
        authoritative = self.store.get("task-snap-write")
        self.assertNotEqual(authoritative.request.dataset_id, "corrupted")

    def test_store_mutation_after_snapshot_does_not_retroactively_change(self) -> None:
        """codex c6c49248: a cancel (or any concurrent write) that mutates the
        store Task AFTER begin_execution must NOT alter the snapshot that was
        already handed to the execution path."""
        task = self.add_queued_task("task-snap-read")
        # Simulate cancel intent arriving before begin_execution.
        task.cancel_requested = True
        snapshot = self.store.begin_execution("task-snap-read")
        self.assertIsNotNone(snapshot)
        self.assertTrue(snapshot.cancel_requested)

        # Later: the store Task is mutated (rogue mutation / reset).
        authoritative = self.store.get("task-snap-read")
        authoritative.cancel_requested = False
        self.store.save(authoritative)

        # The snapshot preserves the value it had when it was taken.
        self.assertTrue(snapshot.cancel_requested)
        self.assertFalse(self.store.get("task-snap-read").cancel_requested)

    def test_invalid_evidence_or_status_rejected(self) -> None:
        # Contract: completion status must be SUCCEEDED/FAILED and evidence
        # must be NONE/CANCELED_BEFORE_START/MARKER_OBSERVED; anything else
        # is a ValueError, and an unknown task id is a KeyError.
        result = ExecuteResult(exit_code=0, stdout="", stderr="", dataset_dir="")
        for bad_evidence in (
            CancellationEvidence.QUEUED_CANCEL,
            CancellationEvidence.PRE_CREATE_CANCEL,
        ):
            with self.subTest(evidence=bad_evidence):
                with self.assertRaises(ValueError):
                    self.store.complete_execution(
                        "task-any",
                        CompletionCandidate(
                            status=TaskStatus.FAILED,
                            result=result,
                            evidence=bad_evidence,
                        ),
                    )
        for bad_status in (TaskStatus.CANCELED, TaskStatus.QUEUED, TaskStatus.RUNNING):
            with self.subTest(status=bad_status):
                with self.assertRaises(ValueError):
                    self.store.complete_execution(
                        "task-any",
                        CompletionCandidate(status=bad_status, result=result),
                    )

        self.add_queued_task("task-known")
        with self.assertRaises(KeyError):
            self.store.complete_execution(
                "task-ghost",
                CompletionCandidate(status=TaskStatus.FAILED, result=result),
            )


class RecoveryTests(_TaskStoreTestBase):
    def test_recover_after_restart_requeues_queued_and_fails_running(self) -> None:
        # Contract: recovery re-enqueues durable QUEUED tasks and terminalizes
        # abandoned RUNNING tasks with the honest restart result
        # (exit_reason UNKNOWN, retryable None = absent presence).
        self.add_queued_task("task-q")
        running = self.add_queued_task("task-r")
        self.assertTrue(self.store.begin_execution("task-r"))
        cancel_requested = self.add_queued_task("task-rc")
        self.assertTrue(self.store.begin_execution("task-rc"))
        intent = self.store.cancel_by_task_id("cr-rc", "task-rc", "USER_REQUEST")
        self.assertEqual(intent.outcome, CancelOutcome.CANCEL_INTENT_RECORDED.value)
        self.assertTrue(cancel_requested.cancel_requested)

        requeue = self.store.recover_after_restart()
        self.assertEqual(requeue, ["task-q"])

        self.assertEqual(running.status, TaskStatus.FAILED)
        self.assertIn("restarted", running.error)
        self.assertEqual(running.result.exit_code, SYNTHETIC_EXIT_CODE)
        self.assertEqual(running.result.resource_usage.exit_reason, "UNKNOWN")
        self.assertIsNone(running.result.retryable)
        self.assertIsNone(running.retryable)
        self.assertIsNotNone(running.finished_at)

        # A RUNNING task with a pending cancel ALSO becomes FAILED: the kill
        # was never observed (the service died), so forcing CANCELED would be
        # fabricated evidence (d6841a2e rule 4).  The restart outcome wins;
        # the durable cancel intent and binding survive the restart.
        self.assertEqual(cancel_requested.status, TaskStatus.FAILED)
        self.assertTrue(cancel_requested.cancel_requested)
        self.assertEqual(cancel_requested.cancel_reason, "USER_REQUEST")

        reloaded = self.reload_store()
        self.assertEqual(reloaded.get("task-r").status, TaskStatus.FAILED)
        self.assertEqual(reloaded.get("task-rc").status, TaskStatus.FAILED)
        self.assertEqual(reloaded.get("task-q").status, TaskStatus.QUEUED)
        self.assertTrue(reloaded.get("task-rc").cancel_requested)
        self.assertIn("cr-rc", reloaded.cancel_requests)


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
    def test_queue_full_admission_rolls_back_durably(self) -> None:
        # Contract (codex 4334bc9d constraint 1): when admission raises
        # (queue full) the just-written records are rolled back under the
        # same lock — a rejected create leaves no durable trace.
        task = Task(
            task_id="task-qfull", status=TaskStatus.QUEUED, request=identified_request()
        )

        def admit() -> None:
            raise asyncio.QueueFull()

        with self.assertRaises(asyncio.QueueFull):
            self.store.create_with_admission(task, admission=admit)

        self.assertEqual(self.store.tasks, {})
        self.assertEqual(self.store.operations, {})
        reloaded = self.reload_store()
        self.assertEqual(reloaded.tasks, {})
        self.assertEqual(reloaded.operations, {})

    def test_rollback_persist_failure_surfaces_persistence_error(self) -> None:
        # Contract (codex 4334bc9d constraint 1): if the ROLLBACK persist
        # fails, the task IS durable on disk (the first persist succeeded),
        # so memory is restored to match the disk and the PERSISTENCE error
        # propagates — never the QueueFull.
        state_path = Path(self._temp_dir.name) / "state-persist-fail.json"
        store = _SecondPersistFailsStore(state_path)
        store.fail_from_call = 2  # call 1 = insert persist, call 2 = rollback
        task = Task(
            task_id="task-rb", status=TaskStatus.QUEUED, request=identified_request()
        )

        def admit() -> None:
            raise asyncio.QueueFull()

        with self.assertRaises(OSError) as raised:
            store.create_with_admission(task, admission=admit)
        self.assertIn("injected persist failure", str(raised.exception))
        self.assertEqual(store.persist_call_count, 2)

        # The first persist made the task durable; memory mirrors the disk.
        restored = store.get("task-rb")
        self.assertIsNotNone(restored)
        self.assertEqual(restored.status, TaskStatus.QUEUED)
        self.assertEqual(
            store.operations[OPERATION_ID]["task_id"], "task-rb"
        )
        reloaded = DurableTaskStore(state_path)
        self.assertIsNotNone(reloaded.get("task-rb"))
        self.assertEqual(reloaded.operations[OPERATION_ID]["task_id"], "task-rb")

    def test_concurrent_same_operation_creates_admit_once(self) -> None:
        # Contract (codex 4334bc9d constraint 1): the dedup check, insert and
        # admission share ONE critical section — of 8 racing creates for the
        # same operation exactly one is admitted, all resolve to one task.
        thread_count = 8
        barrier = threading.Barrier(thread_count)
        decisions: list = []
        admissions: list = []
        errors: list = []
        collected_lock = threading.Lock()

        def worker(index: int) -> None:
            try:
                task = Task(
                    task_id=f"task-race-{index}",
                    status=TaskStatus.QUEUED,
                    request=identified_request(),
                )

                def admit() -> None:
                    with collected_lock:
                        admissions.append(task.task_id)

                barrier.wait(timeout=10)
                decision = self.store.create_with_admission(task, admission=admit)
                with collected_lock:
                    decisions.append(decision)
            except Exception as exc:  # noqa: BLE001 - report any thread failure
                with collected_lock:
                    errors.append(exc)

        threads = [
            threading.Thread(target=worker, args=(i,)) for i in range(thread_count)
        ]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join(timeout=30)
        for thread in threads:
            self.assertFalse(thread.is_alive())

        self.assertEqual(errors, [])
        self.assertEqual(len(decisions), thread_count)
        fresh = [d for d in decisions if not d.existing]
        replayed = [d for d in decisions if d.existing]
        self.assertEqual(len(fresh), 1)
        self.assertEqual(len(replayed), thread_count - 1)
        winner_id = fresh[0].task.task_id
        for decision in replayed:
            self.assertEqual(decision.task.task_id, winner_id)
        self.assertEqual(len(admissions), 1)
        self.assertEqual(len(self.store.tasks), 1)

        reloaded = self.reload_store()
        self.assertEqual(len(reloaded.tasks), 1)
        self.assertEqual(len(reloaded.operations), 1)
        self.assertEqual(reloaded.operations[OPERATION_ID]["task_id"], winner_id)


class SyntheticResultShapeTests(unittest.TestCase):
    def test_build_canceled_result_shape(self) -> None:
        # Contract: the single honest CANCELED result — synthetic exit code
        # -1, empty streams, exit_reason CANCELED, retryable False, nothing
        # measured (attribution_complete False, all 12 measurement fields
        # listed as missing).
        result = build_canceled_result(None)
        self.assertEqual(SYNTHETIC_EXIT_CODE, -1)
        self.assertEqual(result.exit_code, SYNTHETIC_EXIT_CODE)
        self.assertEqual(result.stdout, "")
        self.assertEqual(result.stderr, "")
        self.assertEqual(result.dataset_dir, "")
        self.assertFalse(result.retryable)
        usage = result.resource_usage
        self.assertEqual(usage.exit_reason, "CANCELED")
        self.assertFalse(usage.attribution_complete)
        self.assertEqual(usage.resource_class, "UNKNOWN")
        self.assertEqual(usage.missing_fields, list(_MISSING_MEASUREMENT_FIELDS))
        self.assertEqual(len(usage.missing_fields), 12)

        with_request = build_canceled_result(identified_request())
        self.assertEqual(with_request.exit_code, SYNTHETIC_EXIT_CODE)
        self.assertEqual(with_request.resource_usage.resource_class, "STANDARD")
        self.assertFalse(with_request.retryable)


if __name__ == "__main__":
    unittest.main()
