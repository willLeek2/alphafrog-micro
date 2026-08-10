from __future__ import annotations

import hashlib
import json
import os
import re
import tempfile
import threading
import uuid
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Callable, Dict, Optional

from .models import (
    CancelOutcome,
    CancellationEvidence,
    ExecuteRequest,
    ExecuteResult,
    SandboxResourceUsage,
    Task,
    TaskStatus,
)


SHA256_PATTERN = re.compile(r"^sha256:[0-9a-f]{64}$")
OPERATION_ID_PATTERN = re.compile(r"^[^:\s]+:[^:\s]+:[1-9][0-9]*$")

# === work-package-C (ccqwen) ===
# §7.1: `state.json` format versions.  v3 is the CURRENT write format; v1
# and v2 documents stay readable.  The v2→v3 bump is additive (D11 cancel
# lifecycle, task #108): Task gains the cancellation bookkeeping fields,
# the store gains the top-level cancel_requests registry and the
# request-less pre-create cancel tombstone.  Unknown versions fail the load
# closed — a never-silently-migrate rule.
SCHEMA_VERSION_V1 = "sandbox_task_store_v1"
SCHEMA_VERSION_V2 = "sandbox_task_store_v2"
SCHEMA_VERSION_V3 = "sandbox_task_store_v3"
SUPPORTED_SCHEMA_VERSIONS = frozenset(
    {SCHEMA_VERSION_V1, SCHEMA_VERSION_V2, SCHEMA_VERSION_V3}
)
# === end work-package-C (ccqwen) ===

# The twelve OPTIONAL SandboxResourceUsage measurement fields, spelled in
# camelCase like the proto field names — the same convention the existing
# queue-timeout / EXECUTION_ERROR synthetic results in main.py already use.
# A canceled run and a restart-aborted run measure nothing, so every one of
# them goes into missing_fields: an honest result never fabricates numbers
# that were never observed (attribution_complete stays False alongside).
_MISSING_MEASUREMENT_FIELDS = (
    "cpuMillis",
    "memoryPeakBytes",
    "memoryByteMillis",
    "logicalBytesScanned",
    "artifactBytesWritten",
    "temporaryBytesWritten",
    "queueWaitMillis",
    "prepareMillis",
    "executionWallMillis",
    "cleanupMillis",
    "datasetOpenCount",
    "samplingIntervalMillis",
)

# Synthetic terminal results for runs that never produced a child exit code
# (nothing ran / the service died mid-run) reuse the queue-timeout precedent
# of exit_code=-1.
SYNTHETIC_EXIT_CODE = -1


class OperationConflictError(RuntimeError):
    pass


class CancelRequestBindingError(RuntimeError):
    """The same cancel_request_id was reused for a DIFFERENT target identity.

    D11 contract (proto CancelTaskRequest.cancelRequestId, codex a3aee2ad
    section 六 ruling 3): the binding is durable; a rebind must answer
    outcome UNSPECIFIED with errorDetail.category CONFLICT — this service
    expresses that as HTTP 409.
    """


@dataclass(frozen=True)
class CreateDecision:
    task: Task
    existing: bool


@dataclass(frozen=True)
class CancelDecision:
    """Store verdict of one cancel request (the endpoint maps it to HTTP).

    ``outcome`` is a CancelOutcome value; ``task_id`` is the stable taskId
    (None only for the business NOT_FOUND); ``status`` is the task's CURRENT
    durable state (live lookup, so a replayed CANCEL_INTENT_RECORDED can
    already show CANCELED); ``replayed`` marks a same-key same-target replay
    returning the first recorded outcome.
    """

    outcome: str
    task_id: Optional[str]
    status: Optional[TaskStatus]
    replayed: bool = False


@dataclass(frozen=True)
class CompletionCandidate:
    """One execution attempt's honest terminal report.

    ``status`` must be SUCCEEDED or FAILED — the CANCELED terminal state is
    only ever reached through the evidence rules below, never by direct
    claim.  ``evidence`` may only be NONE / CANCELED_BEFORE_START /
    MARKER_OBSERVED; anything else is rejected (the QUEUED/PRE_CREATE
    evidences belong to the store-lock cancel paths, not to execution).
    """

    status: TaskStatus
    result: ExecuteResult
    evidence: CancellationEvidence = CancellationEvidence.NONE
    error: Optional[str] = None


def request_payload_digest(request: ExecuteRequest) -> str:
    """Bind the supplied canonical fingerprint to the exact first HTTP payload."""
    payload = request.model_dump(mode="json", exclude={"request_fingerprint"})
    encoded = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return "sha256:" + hashlib.sha256(encoded).hexdigest()


def build_canceled_result(request: Optional[ExecuteRequest]) -> ExecuteResult:
    """The single honest result shared by every CANCELED terminal state.

    exit_code=-1 follows the queue-timeout synthetic-result precedent (a run
    that never produced a child exit code); exit_reason=CANCELED lets
    retry_classification and the Gateway see the cancellation without
    reading human text; retryable=False is the frozen D11 rule (a canceled
    run is never auto-retried); attribution_complete=False plus the full
    missing-fields list declare that nothing was measured.  dataset_dir is
    empty: a canceled run publishes no output location.
    """
    resource_class = request.resource_class if request is not None else "UNKNOWN"
    usage = SandboxResourceUsage(
        resource_class=resource_class,
        exit_reason="CANCELED",
        attribution_complete=False,
        missing_fields=list(_MISSING_MEASUREMENT_FIELDS),
    )
    return ExecuteResult(
        exit_code=SYNTHETIC_EXIT_CODE,
        stdout="",
        stderr="",
        dataset_dir="",
        resource_usage=usage,
        retryable=False,
    )


def build_restart_failed_result(request: Optional[ExecuteRequest]) -> ExecuteResult:
    """Honest result for a task whose RUNNING state a service restart aborted.

    Pre-D11 the recovery path stored error text only, so the result endpoint
    answered a bare 500 for every restarted task.  The restart is real but
    its classification is unknown: exit_reason=UNKNOWN and retryable=None
    (absent presence) keep the harness from reading a fabricated retry
    verdict.
    """
    resource_class = request.resource_class if request is not None else "UNKNOWN"
    usage = SandboxResourceUsage(
        resource_class=resource_class,
        exit_reason="UNKNOWN",
        attribution_complete=False,
        missing_fields=list(_MISSING_MEASUREMENT_FIELDS),
    )
    return ExecuteResult(
        exit_code=SYNTHETIC_EXIT_CODE,
        stdout="",
        stderr="",
        dataset_dir="",
        resource_usage=usage,
        retryable=None,
    )


class DurableTaskStore:
    """Single-file atomic store for tasks, the operationId index and the
    D11 cancelRequestId binding registry.

    Task, operation and cancel-binding records are replaced together under
    ONE RLock and ONE atomic persist, so an operation mapping can never
    survive without the first payload digest it was bound to, and a cancel
    binding can never survive without the outcome it recorded.
    """

    def __init__(self, state_path: Path) -> None:
        self.state_path = state_path
        self._lock = threading.RLock()
        self.tasks: Dict[str, Task] = {}
        self.operations: Dict[str, dict] = {}
        self.cancel_requests: Dict[str, dict] = {}
        self._load()

    # ------------------------------------------------------------------ #
    # create / admission
    # ------------------------------------------------------------------ #

    def create(self, task: Task) -> CreateDecision:
        """Backward-compatible create without queue admission (tests)."""
        return self.create_with_admission(task)

    def create_with_admission(
        self,
        task: Task,
        admission: Optional[Callable[[], object]] = None,
    ) -> CreateDecision:
        """Persist a new task (or resolve the existing one) and admit it.

        D11 / codex 4334bc9d constraint 1: the dedup check, the insert, the
        persist AND the admission callback (bounded-queue put) share ONE
        critical section.  When admission raises (queue full), the
        just-written records are rolled back under the SAME lock and the
        error re-raises, so a rejected create leaves no durable trace.  If
        the rollback persist itself fails, the task IS durable on disk: the
        in-memory state is restored to match the disk and the persistence
        error propagates — the caller must then answer a persistence
        failure, never a 503 "not accepted".  (A crash between the first
        persist and the enqueue has the same honest resolution: the task
        stays QUEUED on disk and recover_after_restart re-enqueues it on
        the next startup — the documented crash boundary.)
        """
        if task.request is None:
            raise ValueError(
                "cannot create a task without a request; tombstones are only"
                " created by cancel_by_operation"
            )
        operation_id = (task.request.operation_id or "").strip()
        fingerprint = (task.request.request_fingerprint or "").strip().lower()
        payload_digest = request_payload_digest(task.request)
        task.payload_digest = payload_digest
        task.request_fingerprint = fingerprint or None

        with self._lock:
            if not operation_id:
                self.tasks[task.task_id] = task
                self._persist_locked()
                self._admit_locked(task.task_id, None, admission)
                return CreateDecision(task=task, existing=False)
            self._validate_identity(operation_id, fingerprint)
            existing = self.operations.get(operation_id)
            if existing is not None:
                existing_task = self.tasks.get(existing["task_id"])
                if existing_task is None:
                    raise RuntimeError(
                        f"operation index references missing task: {operation_id}"
                    )
                if existing["request_fingerprint"] != fingerprint:
                    raise OperationConflictError(
                        "operation_id is already bound to a different request fingerprint or payload"
                    )
                if existing.get("payload_digest") is None:
                    # D11 pre-create tombstone adoption (v4-3): the first
                    # matching create adopts the tombstone's stable taskId
                    # and fills EVERY frozen field through one shared helper;
                    # the CANCELED terminal state and its honest result stay
                    # untouched.  No admission — a tombstone never runs, so
                    # an adopted create can never be rejected by a full
                    # queue (cancellation cannot be bypassed by racing the
                    # capacity check).
                    self._adopt_tombstone_locked(
                        existing_task,
                        task.request,
                        existing,
                        payload_digest,
                        task.effective_output_limits,
                        task.runtime_image_ref,
                    )
                    return CreateDecision(task=existing_task, existing=True)
                if existing["payload_digest"] != payload_digest:
                    raise OperationConflictError(
                        "operation_id is already bound to a different request fingerprint or payload"
                    )
                return CreateDecision(task=existing_task, existing=True)

            self.tasks[task.task_id] = task
            self.operations[operation_id] = {
                "task_id": task.task_id,
                "request_fingerprint": fingerprint,
                "payload_digest": payload_digest,
            }
            self._persist_locked()
            self._admit_locked(task.task_id, operation_id, admission)
            return CreateDecision(task=task, existing=False)

    def _admit_locked(
        self,
        task_id: str,
        operation_id: Optional[str],
        admission: Optional[Callable[[], object]],
    ) -> None:
        """Run the admission callback under the store lock with rollback."""
        if admission is None:
            return
        try:
            admission()
        except Exception:
            removed_task = self.tasks.pop(task_id, None)
            removed_entry = (
                self.operations.pop(operation_id) if operation_id else None
            )
            try:
                self._persist_locked()
            except Exception:
                # The rollback did not reach the disk: the task is durable.
                # Restore memory to match the disk and surface a persistence
                # failure — answering "queue full, not accepted" now would
                # be a lie (the task will be re-enqueued after a restart).
                if removed_task is not None:
                    self.tasks[task_id] = removed_task
                if removed_entry is not None and operation_id is not None:
                    self.operations[operation_id] = removed_entry
                raise
            raise

    def find_existing_or_adopt_tombstone(
        self,
        request: ExecuteRequest,
        effective_output_limits,
        runtime_image_ref: Optional[str],
    ) -> Optional[CreateDecision]:
        """Authoritative pre-capacity store consult (v4-4 re-check #1).

        Returns None when the operation is unknown (a fresh create must
        proceed to the capacity check), the existing-task decision for an
        idempotent replay, or the adopted-tombstone decision when a
        by_operation cancel arrived before this create.  Tombstone adoption
        fills every frozen field through the SAME helper as the in-lock
        adoption inside create_with_admission (v4-3).
        """
        operation_id = (request.operation_id or "").strip()
        if not operation_id:
            return None
        fingerprint = (request.request_fingerprint or "").strip().lower()
        with self._lock:
            self._validate_identity(operation_id, fingerprint)
            entry = self.operations.get(operation_id)
            if entry is None:
                return None
            existing_task = self.tasks.get(entry["task_id"])
            if existing_task is None:
                raise RuntimeError(
                    f"operation index references missing task: {operation_id}"
                )
            if entry["request_fingerprint"] != fingerprint:
                raise OperationConflictError(
                    "operation_id is already bound to a different request fingerprint or payload"
                )
            incoming_digest = request_payload_digest(request)
            if entry.get("payload_digest") is None:
                self._adopt_tombstone_locked(
                    existing_task,
                    request,
                    entry,
                    incoming_digest,
                    effective_output_limits,
                    runtime_image_ref,
                )
            elif entry["payload_digest"] != incoming_digest:
                # Same fingerprint but a DIFFERENT payload is a conflict, not
                # an idempotent replay — the consult must raise exactly like
                # the final create_with_admission re-check does, so an early
                # consult can never mask a genuine 409.
                raise OperationConflictError(
                    "operation_id is already bound to a different request fingerprint or payload"
                )
            return CreateDecision(task=existing_task, existing=True)

    def _adopt_tombstone_locked(
        self,
        tombstone: Task,
        request: ExecuteRequest,
        entry: dict,
        payload_digest: str,
        effective_output_limits,
        runtime_image_ref: Optional[str],
    ) -> None:
        """Fill EVERY frozen field of a pre-create tombstone (v4-3).

        The stable taskId, the CANCELED terminal state, the honest result and
        the cancel bookkeeping are kept; the late create's request, payload
        digest, frozen output limits and image reference are adopted so the
        durable record is complete.
        """
        tombstone.request = request
        tombstone.request_fingerprint = (
            (request.request_fingerprint or "").strip().lower() or None
        )
        tombstone.payload_digest = payload_digest
        tombstone.effective_output_limits = effective_output_limits
        tombstone.runtime_image_ref = runtime_image_ref
        entry["payload_digest"] = payload_digest
        self._persist_locked()

    # ------------------------------------------------------------------ #
    # execution transitions
    # ------------------------------------------------------------------ #

    def begin_execution(self, task_id: str) -> Task | None:
        """Atomically QUEUED→RUNNING and return a DEEP-COPY execution snapshot.

        Returns None when the task is not QUEUED (a cancel terminalized it
        inside the store lock between dequeue and now, or it is already
        terminal); the caller must then NOT touch the task at all.

        The returned Task is a standalone copy (codex c6c49248 review):
        modifying it cannot corrupt the store's authoritative Task, and a
        concurrent cancel thread that mutates the store's Task cannot change
        what the execution path reads.  Only ``complete_execution`` writes
        the terminal state back through the store lock.
        """
        with self._lock:
            task = self.tasks.get(task_id)
            if task is None or task.status != TaskStatus.QUEUED:
                return None
            task.status = TaskStatus.RUNNING
            task.started_at = datetime.utcnow()
            self._persist_locked()
            return task.model_copy(deep=True)

    def complete_execution(self, task_id: str, candidate: CompletionCandidate) -> Task:
        """Persist the terminal state of one execution attempt.

        Rules (codex d6841a2e): an already-terminal task is returned as-is
        (a cancel may have terminalized it first — the cancel wins).  Real
        cancellation evidence — CANCELED_BEFORE_START (the pool Future was
        canceled before the job ran) or MARKER_OBSERVED (the wrapper saw the
        marker and killed its own child) — turns the terminal state into
        CANCELED.  A mere stop request or an issued kill without observation
        is NEVER enough (rule 4): with evidence NONE the genuine
        SUCCEEDED/FAILED result stands, including the case where the child
        finished before the stop took effect (rule 3).
        """
        if candidate.status not in (TaskStatus.SUCCEEDED, TaskStatus.FAILED):
            raise ValueError("completion status must be SUCCEEDED or FAILED")
        if candidate.evidence not in (
            CancellationEvidence.NONE,
            CancellationEvidence.CANCELED_BEFORE_START,
            CancellationEvidence.MARKER_OBSERVED,
        ):
            raise ValueError(
                "completion evidence must be NONE, CANCELED_BEFORE_START or"
                " MARKER_OBSERVED"
            )
        with self._lock:
            task = self.tasks.get(task_id)
            if task is None:
                raise KeyError(f"unknown task: {task_id}")
            if task.status in (
                TaskStatus.SUCCEEDED,
                TaskStatus.FAILED,
                TaskStatus.CANCELED,
            ):
                return task
            if candidate.evidence in (
                CancellationEvidence.CANCELED_BEFORE_START,
                CancellationEvidence.MARKER_OBSERVED,
            ):
                # D11 (task #108, codex c6c49248 review): execution evidence
                # alone is NOT enough to force CANCELED — a durable cancel
                # intent MUST have been recorded first.  Without it, a
                # leftover marker or an erroneous stop signal could fabricate
                # a CANCELED terminal state for a task that was never
                # cancelled.  With both evidence AND intent the classification
                # stands (d6841a2e rules 2+4).
                if task.cancel_requested:
                    task.status = TaskStatus.CANCELED
                    task.cancellation_evidence = candidate.evidence
                    if candidate.evidence == CancellationEvidence.MARKER_OBSERVED:
                        result = candidate.result
                        result.retryable = False
                        if result.resource_usage is not None:
                            result.resource_usage.exit_reason = "CANCELED"
                        task.result = result
                        task.resource_usage = result.resource_usage
                    else:
                        task.result = build_canceled_result(task.request)
                        task.resource_usage = task.result.resource_usage
                    task.retryable = False
                    task.error = None
                else:
                    # No cancel intent — the genuine completion result stands.
                    task.status = candidate.status
                    task.result = candidate.result
                    task.resource_usage = candidate.result.resource_usage
                    task.retryable = candidate.result.retryable
                    task.error = candidate.error
            else:
                task.status = candidate.status
                task.result = candidate.result
                task.resource_usage = candidate.result.resource_usage
                task.retryable = candidate.result.retryable
                task.error = candidate.error
            task.finished_at = datetime.utcnow()
            self._persist_locked()
            return task

    def save(self, task: Task) -> None:
        with self._lock:
            self.tasks[task.task_id] = task
            self._persist_locked()

    def get(self, task_id: str) -> Task | None:
        with self._lock:
            return self.tasks.get(task_id)

    def get_by_operation_id(self, operation_id: str) -> Task | None:
        with self._lock:
            entry = self.operations.get(operation_id)
            return self.tasks.get(entry["task_id"]) if entry else None

    # ------------------------------------------------------------------ #
    # D11 cancel paths
    # ------------------------------------------------------------------ #

    def cancel_by_task_id(
        self, cancel_request_id: str, task_id: str, reason: str
    ) -> CancelDecision:
        """Cancel by the stable taskId (client already holds a taskId)."""
        task_id = task_id.strip()
        with self._lock:
            replay = self._check_binding_locked(
                cancel_request_id, "by_task_id", task_id=task_id
            )
            if replay is not None:
                return replay
            task = self.tasks.get(task_id)
            if task is None:
                # Business NOT_FOUND: the sandbox is authoritative — this
                # taskId never existed here.  The binding is still recorded
                # (same-key replays must stay stable).
                self._record_binding_locked(
                    cancel_request_id,
                    "by_task_id",
                    first_outcome=CancelOutcome.NOT_FOUND.value,
                    first_task_id="",
                    task_id=task_id,
                    reason=reason,
                )
                self._persist_locked()
                return CancelDecision(
                    outcome=CancelOutcome.NOT_FOUND.value, task_id=None, status=None
                )
            decision = self._cancel_existing_task_locked(task, reason)
            self._record_binding_locked(
                cancel_request_id,
                "by_task_id",
                first_outcome=decision.outcome,
                first_task_id=task.task_id,
                task_id=task_id,
                reason=reason,
            )
            self._persist_locked()
            return decision

    def cancel_by_operation(
        self,
        cancel_request_id: str,
        operation_id: str,
        request_fingerprint: str,
        reason: str,
    ) -> CancelDecision:
        """Cancel by operation identity (PREPARING window or no taskId yet).

        An UNKNOWN operation produces a pre-create tombstone instead of
        NOT_FOUND: the cancel may race an in-flight create, and fail-closed
        means the intent must be durable either way.  The tombstone owns a
        stable taskId assigned here, an honest CANCELED result, and the
        operation binding with a None payload digest until the first
        matching create adopts it.
        """
        operation_id = operation_id.strip()
        fingerprint = request_fingerprint.strip().lower()
        with self._lock:
            # Format errors are INVALID_ARGUMENT (400) regardless of any
            # binding state — validated before the replay check.
            self._validate_identity(operation_id, fingerprint)
            replay = self._check_binding_locked(
                cancel_request_id,
                "by_operation",
                operation_id=operation_id,
                request_fingerprint=fingerprint,
            )
            if replay is not None:
                return replay
            entry = self.operations.get(operation_id)
            if entry is None:
                task_id = str(uuid.uuid4())
                now = datetime.utcnow()
                tombstone = Task(
                    task_id=task_id,
                    status=TaskStatus.CANCELED,
                    request=None,
                    result=build_canceled_result(None),
                    created_at=now,
                    finished_at=now,
                    request_fingerprint=fingerprint,
                    payload_digest=None,
                    retryable=False,
                    cancellation_evidence=CancellationEvidence.PRE_CREATE_CANCEL,
                    cancel_reason=reason or None,
                    cancel_requested=True,
                )
                self.tasks[task_id] = tombstone
                self.operations[operation_id] = {
                    "task_id": task_id,
                    "request_fingerprint": fingerprint,
                    # payload_digest stays null until adoption fills it —
                    # the load-time invariant that distinguishes tombstones.
                    "payload_digest": None,
                }
                self._record_binding_locked(
                    cancel_request_id,
                    "by_operation",
                    first_outcome=CancelOutcome.CANCELED.value,
                    first_task_id=task_id,
                    operation_id=operation_id,
                    request_fingerprint=fingerprint,
                    reason=reason,
                )
                self._persist_locked()
                return CancelDecision(
                    outcome=CancelOutcome.CANCELED.value,
                    task_id=task_id,
                    status=TaskStatus.CANCELED,
                )
            if entry["request_fingerprint"] != fingerprint:
                raise OperationConflictError(
                    "cancel request_fingerprint does not match the fingerprint"
                    " bound to this operation_id"
                )
            task = self.tasks.get(entry["task_id"])
            if task is None:
                raise RuntimeError(
                    f"operation index references missing task: {operation_id}"
                )
            decision = self._cancel_existing_task_locked(task, reason)
            self._record_binding_locked(
                cancel_request_id,
                "by_operation",
                first_outcome=decision.outcome,
                first_task_id=task.task_id,
                operation_id=operation_id,
                request_fingerprint=fingerprint,
                reason=reason,
            )
            self._persist_locked()
            return decision

    def _cancel_existing_task_locked(self, task: Task, reason: str) -> CancelDecision:
        """Status dispatch shared by both cancel paths (store lock held).

        QUEUED: terminalized to CANCELED right here with the honest
        synthesized result (nothing ever ran — the same precedent as the
        queue-timeout synthetic result).  RUNNING: only the durable intent
        is recorded; the actual stop signal travels the cancel registry
        OUTSIDE this lock, and the terminal CANCELED is written by
        complete_execution once the execution layer reports real evidence.
        Terminal: ALREADY_TERMINAL, unchanged.
        """
        if task.status in (
            TaskStatus.SUCCEEDED,
            TaskStatus.FAILED,
            TaskStatus.CANCELED,
        ):
            return CancelDecision(
                outcome=CancelOutcome.ALREADY_TERMINAL.value,
                task_id=task.task_id,
                status=task.status,
            )
        if task.status == TaskStatus.QUEUED:
            task.status = TaskStatus.CANCELED
            task.cancellation_evidence = CancellationEvidence.QUEUED_CANCEL
            task.cancel_reason = reason or None
            task.cancel_requested = True
            task.result = build_canceled_result(task.request)
            task.resource_usage = task.result.resource_usage
            task.retryable = False
            task.finished_at = datetime.utcnow()
            return CancelDecision(
                outcome=CancelOutcome.CANCELED.value,
                task_id=task.task_id,
                status=TaskStatus.CANCELED,
            )
        # RUNNING
        task.cancel_requested = True
        task.cancel_reason = reason or None
        return CancelDecision(
            outcome=CancelOutcome.CANCEL_INTENT_RECORDED.value,
            task_id=task.task_id,
            status=task.status,
        )

    def _check_binding_locked(
        self,
        cancel_request_id: str,
        target_type: str,
        *,
        task_id: str = "",
        operation_id: str = "",
        request_fingerprint: str = "",
    ) -> Optional[CancelDecision]:
        """Enforce the durable cancelRequestId binding; build replay answers.

        Same key + different target identity raises CancelRequestBindingError
        (409 CONFLICT).  Same key + same target returns the FIRST recorded
        outcome with a live status lookup (the status field may legitimately
        advance between the original call and a replay).
        """
        entry = self.cancel_requests.get(cancel_request_id)
        if entry is None:
            return None
        same_target = (
            entry.get("target_type") == target_type
            and entry.get("task_id", "") == task_id
            and entry.get("operation_id", "") == operation_id
            and entry.get("request_fingerprint", "") == request_fingerprint
        )
        if not same_target:
            raise CancelRequestBindingError(
                f"cancel_request_id {cancel_request_id} is already bound to a"
                " different cancel target"
            )
        first_task_id = entry.get("first_task_id") or None
        status = None
        if first_task_id:
            task = self.tasks.get(first_task_id)
            if task is not None:
                status = task.status
        return CancelDecision(
            outcome=entry["first_outcome"],
            task_id=first_task_id,
            status=status,
            replayed=True,
        )

    def _record_binding_locked(
        self,
        cancel_request_id: str,
        target_type: str,
        *,
        first_outcome: str,
        first_task_id: str,
        task_id: str = "",
        operation_id: str = "",
        request_fingerprint: str = "",
        reason: str = "",
    ) -> None:
        self.cancel_requests[cancel_request_id] = {
            "target_type": target_type,
            "task_id": task_id,
            "operation_id": operation_id,
            "request_fingerprint": request_fingerprint,
            "first_outcome": first_outcome,
            "first_task_id": first_task_id or "",
            "recorded_at": datetime.utcnow().isoformat(),
            "reason": reason or "",
        }

    # ------------------------------------------------------------------ #
    # recovery / load / persist
    # ------------------------------------------------------------------ #

    def recover_after_restart(self) -> list[str]:
        """Requeue durable QUEUED tasks and terminalize abandoned RUNNING tasks.

        An abandoned RUNNING task gets the honest restart-FAILED result
        (exit_reason UNKNOWN, retryable absent) instead of error text only —
        the result endpoint must never answer a bare 500 for a restarted
        task.  A task that was cancel_requested while RUNNING stays FAILED
        here: the kill was never observed (the service died), so a forced
        CANCELED would be fabricated evidence (d6841a2e rule 4); the
        durable cancel binding survives the restart untouched.
        """
        queued: list[str] = []
        changed = False
        with self._lock:
            for task in self.tasks.values():
                if task.status == TaskStatus.QUEUED:
                    queued.append(task.task_id)
                elif task.status == TaskStatus.RUNNING:
                    task.status = TaskStatus.FAILED
                    task.error = "sandbox service restarted while task was running"
                    task.result = build_restart_failed_result(task.request)
                    task.resource_usage = task.result.resource_usage
                    task.retryable = task.result.retryable
                    task.finished_at = datetime.utcnow()
                    changed = True
            if changed:
                self._persist_locked()
        return queued

    def _validate_identity(self, operation_id: str, fingerprint: str) -> None:
        if not OPERATION_ID_PATTERN.fullmatch(operation_id):
            raise ValueError("operation_id must be runId:toolCallId:attempt")
        if not SHA256_PATTERN.fullmatch(fingerprint):
            raise ValueError("request_fingerprint must be lowercase sha256:<64 hex>")

    def _load(self) -> None:
        if not self.state_path.exists():
            return
        try:
            document = json.loads(self.state_path.read_text(encoding="utf-8"))
            schema_version = document.get("schema_version")
            if schema_version not in SUPPORTED_SCHEMA_VERSIONS:
                raise ValueError(
                    f"unsupported state.json schema_version: {schema_version!r}"
                )
            self.tasks = {
                task_id: Task.model_validate(payload)
                for task_id, payload in (document.get("tasks") or {}).items()
            }
            self.operations = dict(document.get("operations") or {})
            self.cancel_requests = dict(document.get("cancel_requests") or {})
            if schema_version in (SCHEMA_VERSION_V1, SCHEMA_VERSION_V2):
                self._validate_pre_cancel_invariants_locked(schema_version)
            else:
                self._validate_v3_invariants_locked()
        except Exception as error:
            raise RuntimeError(
                f"failed to load durable sandbox task store: {self.state_path}"
            ) from error

    def _validate_pre_cancel_invariants_locked(self, schema_version: str) -> None:
        """v1/v2 files predate the cancel lifecycle: no tombstones, no
        cancel_requests, and every operation entry carries its full
        immutable request binding."""
        for task_id, task in self.tasks.items():
            if task.request is None:
                raise ValueError(
                    f"task {task_id} in a {schema_version} store must carry"
                    " its request (tombstones are a v3 feature)"
                )
        for operation_id, entry in self.operations.items():
            if entry.get("task_id") not in self.tasks:
                raise ValueError(f"operation {operation_id} references missing task")
            if not entry.get("request_fingerprint") or not entry.get("payload_digest"):
                raise ValueError(
                    f"operation {operation_id} is missing its immutable request binding"
                )
        if self.cancel_requests:
            raise ValueError(
                f"a {schema_version} store must not carry cancel_requests"
            )

    def _validate_v3_invariants_locked(self) -> None:
        """v3 structural invariants (fail-closed, same as the older ones).

        * every operation entry references an existing task and carries a
          fingerprint; a None payload_digest is permitted ONLY when the
          referenced task is a genuine pre-create tombstone;
        * a request-less task must be shaped exactly like a tombstone
          (CANCELED, honest result present, never started, PRE_CREATE_CANCEL
          evidence);
        * every cancel binding is structurally complete and its first_task_id
          (when assigned) references an existing task.
        """
        for operation_id, entry in self.operations.items():
            task_id = entry.get("task_id")
            if task_id not in self.tasks:
                raise ValueError(f"operation {operation_id} references missing task")
            if not entry.get("request_fingerprint"):
                raise ValueError(
                    f"operation {operation_id} is missing its request fingerprint"
                )
            payload_digest = entry.get("payload_digest")
            if payload_digest is None:
                task = self.tasks[task_id]
                if (
                    task.status != TaskStatus.CANCELED
                    or task.request is not None
                    or task.result is None
                    or task.started_at is not None
                    or task.cancellation_evidence
                    != CancellationEvidence.PRE_CREATE_CANCEL
                ):
                    raise ValueError(
                        f"operation {operation_id} has a dangling payload_digest"
                        " but its task is not a pre-create cancel tombstone"
                    )
            elif not payload_digest:
                raise ValueError(
                    f"operation {operation_id} has an empty payload_digest"
                )
        for task_id, task in self.tasks.items():
            if task.request is None and (
                task.status != TaskStatus.CANCELED
                or task.result is None
                or task.started_at is not None
                or task.cancellation_evidence
                != CancellationEvidence.PRE_CREATE_CANCEL
            ):
                raise ValueError(
                    f"task {task_id} is request-less but not shaped like a"
                    " pre-create cancel tombstone"
                )
        for cancel_request_id, entry in self.cancel_requests.items():
            target_type = entry.get("target_type")
            if target_type not in ("by_task_id", "by_operation"):
                raise ValueError(
                    f"cancel request {cancel_request_id} has an unknown"
                    f" target_type: {target_type!r}"
                )
            if not entry.get("first_outcome"):
                raise ValueError(
                    f"cancel request {cancel_request_id} is missing its"
                    " first_outcome"
                )
            first_task_id = entry.get("first_task_id") or ""
            if first_task_id and first_task_id not in self.tasks:
                raise ValueError(
                    f"cancel request {cancel_request_id} references missing"
                    f" task {first_task_id}"
                )
            if target_type == "by_task_id" and not entry.get("task_id"):
                raise ValueError(
                    f"cancel request {cancel_request_id} is a by_task_id"
                    " binding without its task_id identity"
                )
            if target_type == "by_operation" and (
                not entry.get("operation_id")
                or not entry.get("request_fingerprint")
            ):
                raise ValueError(
                    f"cancel request {cancel_request_id} is a by_operation"
                    " binding without its operation identity"
                )

    def _persist_locked(self) -> None:
        self.state_path.parent.mkdir(parents=True, exist_ok=True)
        document = {
            "schema_version": SCHEMA_VERSION_V3,
            "tasks": {
                task_id: task.model_dump(mode="json")
                for task_id, task in self.tasks.items()
            },
            "operations": self.operations,
            "cancel_requests": self.cancel_requests,
        }
        fd, temp_name = tempfile.mkstemp(
            prefix=self.state_path.name + ".",
            suffix=".tmp",
            dir=self.state_path.parent,
        )
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as handle:
                json.dump(
                    document, handle, ensure_ascii=False, sort_keys=True,
                    separators=(",", ":"),
                )
                handle.flush()
                os.fsync(handle.fileno())
            os.replace(temp_name, self.state_path)
            directory_fd = os.open(
                self.state_path.parent, getattr(os, "O_DIRECTORY", 0)
            )
            try:
                os.fsync(directory_fd)
            finally:
                os.close(directory_fd)
        finally:
            try:
                os.unlink(temp_name)
            except FileNotFoundError:
                pass
