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
    AcquireWorkspaceRequest,
    AcquireWorkspaceResponse,
    CancelOutcome,
    CancellationEvidence,
    ExecuteRequest,
    ExecuteResult,
    PurgedRequestBinding,
    RunSealRecord,
    parse_rfc3339_utc,
    SandboxResourceUsage,
    Task,
    TaskStatus,
    WorkspaceExpiryCandidate,
    WorkspaceInfo,
    WorkspaceQueryOutcome,
    WorkspaceResult,
    WorkspaceState,
    WorkspaceStatus,
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
# v4 adds the top-level workspaces registry,
# the store_instance_id and the Task container-identity fields. v4 is the
# CURRENT write format; v1/v2/v3 documents (no workspaces section — a v3
# file carrying one fails the load closed) stay readable with the feature
# off. An older binary reading a v4 file fails closed on the unknown
# version, so an upgraded deployment never feeds new state to old code.
SCHEMA_VERSION_V4 = "sandbox_task_store_v4"
# v5 adds the workspace activity timestamp (last_active_at) and seal
# bookkeeping on workspace records, the top-level run_seals section
# (Run-level permanent seals for Runs that never had a disk), and the
# Task purged_request compact binding that replaces the full request body
# after a workspace deletion. v5 is the CURRENT write format; v1-v4
# documents stay readable, and a loaded v4 document is upgraded in memory
# AND persisted back as v5 in the same load (missing activity timestamps
# get the upgrade moment as their anchor — a lazy in-memory-only anchor
# would be reset by every restart, stretching the retention window
# forever). An older binary reading a v5 file fails closed on the unknown
# version, so an upgraded deployment never feeds new state to old code.
SCHEMA_VERSION_V5 = "sandbox_task_store_v5"
SUPPORTED_SCHEMA_VERSIONS = frozenset(
    {
        SCHEMA_VERSION_V1,
        SCHEMA_VERSION_V2,
        SCHEMA_VERSION_V3,
        SCHEMA_VERSION_V4,
        SCHEMA_VERSION_V5,
    }
)
# === end work-package-C (ccqwen) ===

# Server-side cap for one expiry-candidates page; larger requests are
# served at the cap (the caller simply paginates more).
EXPIRY_CANDIDATES_PAGE_CAP = 500

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


class AdmissionExhaustedError(RuntimeError):
    """The authoritative admission quota said no.

    Raised INSIDE the create critical section before any record is written,
    so a refused create leaves no durable trace; the endpoint answers 503
    with the OVERLOADED_OR_UNAVAILABLE classification.
    asyncio.Queue.qsize() is never
    the authority — the count of durably QUEUED tasks is.
    """


class CancelRequestBindingError(RuntimeError):
    """The same cancel_request_id was reused for a DIFFERENT target identity.

    D11 contract (proto CancelTaskRequest.cancelRequestId, codex a3aee2ad
    section 六 ruling 3): the binding is durable; a rebind must answer
    outcome UNSPECIFIED with errorDetail.category CONFLICT — this service
    expresses that as HTTP 409.
    """


@dataclass(frozen=True)
class CreateDecision:
    """Create verdict; the optional workspace refusal shape.

    workspace_result is set iff the create was REFUSED by the workspace
    gate: task is then None — no task was created, no admission was
    taken, no container may start. Callers check workspace_result before
    touching task.
    """

    task: Optional[Task]
    existing: bool
    workspace_result: Optional[WorkspaceResult] = None


@dataclass(frozen=True)
class ExclusiveBegin:
    """begin_execution_exclusive verdict.

    task: the deep-copy execution snapshot once QUEUED→RUNNING (plus, for
    workspace tasks, the workspace holder slot) committed in ONE persist;
    None otherwise.  busy: the workspace is ACTIVE but held by ANOTHER
    task — the caller defers the task and must NOT terminalize it.
    task=None + busy=False covers every don't-touch outcome (already
    terminal / canceled in the store lock / fail-closed terminalized on an
    unacquirable workspace).
    """

    task: Optional[Task]
    busy: bool = False


# delete_workspace outcomes: phase 1 = the durable DELETING
# transition, phase 2 = the disk outcome. Only "deleted" is terminal.
DELETE_STARTED = "started"
DELETE_DONE = "deleted"
DELETE_MISSING = "missing"
DELETE_BUSY = "busy"
DELETE_RETRYABLE = "retryable_failure"
# Conditional delete refused: the activity moment the caller observed at
# scan time no longer matches the current record — the workspace saw new
# activity since. Nothing was deleted; the caller should revoke whatever
# cleanup-in-progress mark it keeps for the Run.
DELETE_REVOKED = "revoked_new_activity"


def _format_activity_utc(value: datetime) -> str:
    """RFC3339 UTC rendering of a store timestamp.

    Store timestamps are naive utcnow() values; the explicit Z suffix is
    what the wire contract promises on query answers and candidate rows.
    Callers re-checking an observed moment parse it back (see
    parse_rfc3339_utc): the conditional-delete comparison is on time
    values, never on this string's literal form.
    """
    if value.tzinfo is None:
        return value.isoformat() + "Z"
    return value.isoformat()


@dataclass(frozen=True)
class DeleteDecision:
    outcome: str
    workspace: Optional[WorkspaceState] = None


@dataclass(frozen=True)
class SealDecision:
    """seal_workspace verdict: the durable record after the arbitration."""

    sealed: bool
    workspace: Optional[WorkspaceState] = None


@dataclass(frozen=True)
class QueryDecision:
    """query_workspace verdict: outcome plus the record that decided it.

    workspace is attached for FOUND with disk and always for
    FOUND_DELETED (the deleted audit still vouches the old identity);
    run_seal is attached only for a FOUND diskless seal.
    """

    outcome: WorkspaceQueryOutcome
    workspace: Optional[WorkspaceState] = None
    run_seal: Optional[RunSealRecord] = None


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
    """Bind the supplied canonical fingerprint to the exact first HTTP payload.

    Two-projection scheme: workspace-enabled
    requests carry run_id/workspace_id/workspace_generation and those keys
    join the digest; legacy requests keep the pre-upgrade byte layout by
    excluding the three keys, so an upgraded binary still matches the
    digests of tasks persisted before the upgrade (the model validator
    guarantees the three fields are all-present or all-absent).
    """
    exclude = {"request_fingerprint"}
    if request.run_id is None:
        exclude |= {"run_id", "workspace_id", "workspace_generation"}
    payload = request.model_dump(mode="json", exclude=exclude)
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


def build_workspace_unavailable_result(
    request: Optional[ExecuteRequest], exit_reason: str
) -> ExecuteResult:
    """Honest synthetic result for tasks
    failed WITHOUT execution because their workspace became unavailable
    (marked dirty by a failed holder / entered deletion / restart).

    Same honesty contract as build_canceled_result: exit_code=-1 (nothing
    produced a child exit code), nothing was measured, no output location
    is published, retryable=False — the five-element workspaceResult table
    marks these outcomes NOT retryable for the member.
    """
    resource_class = request.resource_class if request is not None else "UNKNOWN"
    usage = SandboxResourceUsage(
        resource_class=resource_class,
        exit_reason=exit_reason,
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


def _workspace_info(workspace: WorkspaceState) -> WorkspaceInfo:
    return WorkspaceInfo(
        workspace_id=workspace.workspace_id,
        workspace_generation=workspace.generation,
        status=workspace.status,
        owned_by_run_id=workspace.run_id,
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
        # The workspace registry shares the SAME
        # atomic document as the tasks (no dual-write file pair).
        self.workspaces: Dict[str, WorkspaceState] = {}
        # Run-level permanent seals for Runs that never had a disk on this
        # instance; written by seal_workspace, consulted by acquire before
        # any creation and by the per-Run read-only query.
        self.run_seals: Dict[str, RunSealRecord] = {}
        # Durable admission quota (count of QUEUED tasks); set by the
        # service from config.queue_max_size. None = unlimited (unit tests
        # that never opted in keep the pre-existing behavior).
        self.admission_limit: Optional[int] = None
        # Workspace feature switch, fixed at process startup. The switch only
        # blocks CREATING a workspace for a Run that has none: turning the
        # feature off affects newly created Runs only, while Runs whose
        # workspace already exists in this state document keep being served
        # (execution and deletion included).
        self.workspace_creation_enabled: bool = True
        # Persistent state-store instance identity, generated at first
        # document creation and carried on every task container's labels so
        # the sweep two-way-checks records against live containers.
        self.store_instance_id = str(uuid.uuid4())
        self._regenerated_instance_id = False
        # Set when _load upgraded an older document: the very first persist
        # after the load writes the CURRENT schema version back, so the
        # upgrade (including the activity-anchor backfill) is durable
        # immediately instead of waiting for an incidental later write.
        self._schema_upgraded = False
        self._load()
        if self._regenerated_instance_id or self._schema_upgraded:
            # A pre-v4 document existed without the UUID, or an older
            # document was upgraded in memory: write it back ONCE now so
            # consecutive restarts observe the same identity and the same
            # upgraded schema (anchors never reset).
            with self._lock:
                self._persist_locked()

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
            # The workspace gate runs BEFORE the existing-task replay and
            # the tombstone adoption below — a late replay carrying a dead
            # or mismatched workspace identity must get the workspace
            # verdict, never the old task (the same gate serves the early
            # consult, so no branch bypasses it).
            workspace_result = self._workspace_gate_locked(task.request)
            if workspace_result is not None:
                return CreateDecision(
                    task=None, existing=False, workspace_result=workspace_result
                )
            if not operation_id:
                self._check_admission_capacity_locked()
                self.tasks[task.task_id] = task
                self._touch_workspace_activity_locked(
                    task.request.workspace_id, datetime.utcnow()
                )
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

            # Idempotent replays resolved above never reach here, so
            # only genuinely new work takes a quota slot.
            self._check_admission_capacity_locked()
            self.tasks[task.task_id] = task
            self.operations[operation_id] = {
                "task_id": task.task_id,
                "request_fingerprint": fingerprint,
                "payload_digest": payload_digest,
            }
            self._touch_workspace_activity_locked(
                task.request.workspace_id, datetime.utcnow()
            )
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

    def _workspace_gate_locked(
        self, request: ExecuteRequest
    ) -> Optional[WorkspaceResult]:
        """The single workspace state check.

        Ownership / generation / dirty / deleting / deleted — in that
        order, all inside the caller's state lock. Returns None for legacy
        requests and for healthy workspace requests; a refusal means NO
        task is created, no admission is taken, no container starts.
        """
        if request.workspace_id is None:
            return None
        workspace = self.workspaces.get(request.workspace_id)
        if workspace is None:
            # No registry entry: with creation switched off this is the
            # deployment-mismatch signal (the sandbox never built a
            # workspace for this identity); with it on, the honest
            # not-found. Existing workspaces are served either way.
            if not self.workspace_creation_enabled:
                return WorkspaceResult.WORKSPACE_UNSUPPORTED
            return WorkspaceResult.WORKSPACE_NOT_FOUND
        if workspace.run_id != request.run_id:
            return WorkspaceResult.WORKSPACE_OWNERSHIP_MISMATCH
        if workspace.generation != request.workspace_generation:
            return WorkspaceResult.WORKSPACE_IDENTITY_CONFLICT
        if workspace.status == WorkspaceStatus.DELETED:
            return WorkspaceResult.WORKSPACE_DELETED
        if workspace.status == WorkspaceStatus.DELETING:
            return WorkspaceResult.WORKSPACE_DELETING
        if workspace.status == WorkspaceStatus.SEALED:
            # The cleanup intent is already durable: from the caller's
            # perspective this is the same "deletion intended, retry
            # later" answer DELETING gives — no new business verdict.
            return WorkspaceResult.WORKSPACE_DELETING
        if workspace.status == WorkspaceStatus.DIRTY:
            return WorkspaceResult.WORKSPACE_DIRTY
        return None

    def _touch_workspace_activity_locked(
        self, workspace_id: Optional[str], now: datetime
    ) -> None:
        """Refresh the activity moment of a live workspace.

        Called under the state lock at the four activity points (acquire /
        create, workspace-task admission, holder begin, task completion)
        so the expiry coordinator's due-ness re-check observes the latest
        business activity. Only ACTIVE and DIRTY records move: DELETING
        and DELETED keep the pre-delete moment as audit, and a SEALED
        record already had its cleanup intent persisted — its only
        forward path is deletion.
        """
        if not workspace_id:
            return
        workspace = self.workspaces.get(workspace_id)
        if workspace is not None and workspace.status in (
            WorkspaceStatus.ACTIVE,
            WorkspaceStatus.DIRTY,
        ):
            workspace.last_active_at = now

    def _check_admission_capacity_locked(self) -> None:
        """The authoritative admission quota check.

        Counts durably-admitted-but-not-started tasks (status QUEUED) in
        the same critical section that inserts the new task, so the
        worker's dequeue-to-RUNNING window can never over-admit and
        asyncio.Queue.qsize() is never an authority. Raises (nothing
        written yet) → the endpoint answers 503.
        """
        if self.admission_limit is None:
            return
        admitted = sum(
            1 for task in self.tasks.values() if task.status == TaskStatus.QUEUED
        )
        if admitted >= self.admission_limit:
            raise AdmissionExhaustedError(
                f"sandbox admission quota exhausted ({admitted} admitted tasks"
                f" not yet started, limit {self.admission_limit})"
            )

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
            # The SAME workspace gate, in the SAME
            # position (top of the lock), as create_with_admission — the
            # pre-capacity consult can never bypass it, and the gate
            # precedes the replay/adoption answers below.
            workspace_result = self._workspace_gate_locked(request)
            if workspace_result is not None:
                return CreateDecision(
                    task=None, existing=False, workspace_result=workspace_result
                )
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

    def begin_execution_exclusive(self, task_id: str) -> ExclusiveBegin:
        """Occupancy + QUEUED→RUNNING in ONE
        atomic store commit.

        Workspace-enabled tasks take the single-writer slot here, in the
        SAME persist that flips the status — the crash window between
        "task RUNNING" and "workspace held" simply does not exist. The
        deep-copy snapshot contract mirrors begin_execution. Legacy
        tasks (no workspace identity) take the plain
        path. busy=True means ACTIVE-but-held-by-another: the caller
        defers, it must NOT terminalize. A task whose workspace record is
        missing or no longer acquirable (DIRTY/DELETING/DELETED) is
        terminalized FAILED fail-closed here — that combination is
        unreachable through the create gate and the dirty/delete
        transitions, so reaching it means corrupted or hand-edited state,
        and starting a write container on such a disk is never acceptable.
        """
        with self._lock:
            task = self.tasks.get(task_id)
            if task is None or task.status != TaskStatus.QUEUED:
                return ExclusiveBegin(task=None)
            request = task.request
            workspace_id = request.workspace_id if request is not None else None
            if not workspace_id:
                task.status = TaskStatus.RUNNING
                task.started_at = datetime.utcnow()
                self._persist_locked()
                return ExclusiveBegin(task=task.model_copy(deep=True))
            workspace = self.workspaces.get(workspace_id)
            if workspace is not None and workspace.status == WorkspaceStatus.ACTIVE:
                if workspace.holder_task_id not in (None, task_id):
                    return ExclusiveBegin(task=None, busy=True)
                task.status = TaskStatus.RUNNING
                task.started_at = datetime.utcnow()
                workspace.holder_task_id = task_id
                # Holder begin is business activity on the disk.
                workspace.last_active_at = task.started_at
                self._persist_locked()
                return ExclusiveBegin(task=task.model_copy(deep=True))
            now = datetime.utcnow()
            status_label = workspace.status.value if workspace is not None else "missing"
            task.status = TaskStatus.FAILED
            task.error = (
                f"workspace {workspace_id} is not acquirable"
                f" (status={status_label}); task failed without execution"
            )
            task.result = build_workspace_unavailable_result(
                request, "WORKSPACE_UNAVAILABLE"
            )
            task.resource_usage = task.result.resource_usage
            task.retryable = False
            task.finished_at = now
            self._persist_locked()
            return ExclusiveBegin(task=None)

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
            # Task completion is business activity on the disk; refresh
            # BEFORE the completion rule below may flip the workspace
            # DIRTY (the touch helper only moves live records, and the
            # pre-delete moment must reflect this final activity).
            if task.request is not None:
                self._touch_workspace_activity_locked(
                    task.request.workspace_id, task.finished_at
                )
            # The one dirty-wakeup rule, applied
            # inside the same lock and written by the same single persist
            # below — no durable intermediate state exists where the
            # workspace is free but the holder's failure is unknown.
            self._apply_workspace_completion_locked(task)
            self._persist_locked()
            return task

    def _apply_workspace_completion_locked(self, task: Task) -> None:
        """Only a SUCCEEDED holder frees the workspace.

        Every other terminal outcome of the HOLDER — FAILED, CANCELED,
        timeout — marks the workspace DIRTY and explicitly fails every
        still-QUEUED task of the same workspace (typed result, no
        re-admission). A completion arriving for a task that never held
        the slot (queue-timeout before begin, QUEUED cancel) leaves the
        workspace untouched: nothing was ever written through it.
        """
        request = task.request
        if request is None or not request.workspace_id:
            return
        workspace = self.workspaces.get(request.workspace_id)
        if workspace is None or workspace.holder_task_id != task.task_id:
            return
        now = datetime.utcnow()
        if workspace.status == WorkspaceStatus.SEALED:
            # A sealed workspace never returns to ACTIVE/DIRTY: the holder
            # finishing — any outcome — only frees the slot. The persisted
            # cleanup intent stays authoritative (a dirty verdict would add
            # nothing the deletion path acts on, and would contradict the
            # sealed/deleting/deleted answer set callers verify after a
            # seal). Queued siblings are terminalized when the deletion
            # starts; no new execution can begin on a sealed record.
            workspace.holder_task_id = None
            workspace.status_changed_at = now
            return
        if task.status == TaskStatus.SUCCEEDED:
            workspace.holder_task_id = None
            workspace.status_changed_at = now
            return
        workspace.status = WorkspaceStatus.DIRTY
        workspace.holder_task_id = None
        workspace.dirtied_by_task_id = task.task_id
        workspace.status_changed_at = now
        for other in self.tasks.values():
            if other.task_id == task.task_id:
                continue
            other_request = other.request
            if (
                other_request is None
                or other_request.workspace_id != request.workspace_id
                or other.status != TaskStatus.QUEUED
            ):
                continue
            other.status = TaskStatus.FAILED
            other.error = (
                f"workspace {request.workspace_id} marked dirty by task"
                f" {task.task_id}; not re-admitted"
            )
            other.result = build_workspace_unavailable_result(
                other_request, "WORKSPACE_DIRTY"
            )
            other.resource_usage = other.result.resource_usage
            other.retryable = False
            other.finished_at = now

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
    # Workspace lifecycle (acquire / delete)
    # ------------------------------------------------------------------ #

    def acquire_workspace(
        self, request: AcquireWorkspaceRequest
    ) -> AcquireWorkspaceResponse:
        """取得或创建工作区.

        First acquisition (no known identity): the Run's workspace is
        created ACTIVE with generation "1" — there is no re-creation
        after delete, so a generation never advances — or returned with
        its CURRENT status, dirty/deleting/deleted included, so the caller
        always sees the truth. A recovery call carrying the known identity
        must MATCH (workspace exists, same run, same generation); any
        mismatch answers WORKSPACE_IDENTITY_CONFLICT with NO workspace
        attached — never hand out a replacement identity;
        a Run must not silently switch disks.
        """
        run_id = request.run_id.strip()
        if not run_id:
            # Defense in depth: the model layer already rejects blank ids;
            # a workspace owned by the empty string would collapse unrelated
            # invalid calls onto one disk.
            raise ValueError("run_id must not be blank")
        with self._lock:
            if request.known_workspace_id is not None:
                workspace = self.workspaces.get(request.known_workspace_id)
                if (
                    workspace is None
                    or workspace.run_id != run_id
                    or workspace.generation != request.known_workspace_generation
                ):
                    return AcquireWorkspaceResponse(
                        workspace=None,
                        workspace_result=WorkspaceResult.WORKSPACE_IDENTITY_CONFLICT,
                    )
                if workspace.status == WorkspaceStatus.SEALED:
                    # The cleanup intent is already durable; answer with the
                    # same "deletion intended" verdict DELETING gets.
                    return AcquireWorkspaceResponse(
                        workspace=None,
                        workspace_result=WorkspaceResult.WORKSPACE_DELETING,
                    )
                self._touch_workspace_activity_locked(
                    workspace.workspace_id, datetime.utcnow()
                )
                self._persist_locked()
                return AcquireWorkspaceResponse(
                    workspace=_workspace_info(workspace)
                )
            for workspace in self.workspaces.values():
                if workspace.run_id == run_id:
                    # An existing workspace is served regardless of the
                    # creation switch — a Run that started under the feature
                    # keeps being processed after it is turned off.
                    if workspace.status == WorkspaceStatus.SEALED:
                        return AcquireWorkspaceResponse(
                            workspace=None,
                            workspace_result=WorkspaceResult.WORKSPACE_DELETING,
                        )
                    self._touch_workspace_activity_locked(
                        workspace.workspace_id, datetime.utcnow()
                    )
                    self._persist_locked()
                    return AcquireWorkspaceResponse(
                        workspace=_workspace_info(workspace)
                    )
            if run_id in self.run_seals:
                # A diskless seal is durable for this Run: the create side
                # must lose the eligibility-to-creation race, so no
                # workspace is created after the seal landed.
                return AcquireWorkspaceResponse(
                    workspace=None,
                    workspace_result=WorkspaceResult.WORKSPACE_DELETING,
                )
            if not self.workspace_creation_enabled:
                return AcquireWorkspaceResponse(
                    workspace=None,
                    workspace_result=WorkspaceResult.WORKSPACE_UNSUPPORTED,
                )
            workspace = WorkspaceState(
                workspace_id=str(uuid.uuid4()),
                run_id=run_id,
                generation="1",
                status=WorkspaceStatus.ACTIVE,
                last_active_at=datetime.utcnow(),
            )
            self.workspaces[workspace.workspace_id] = workspace
            self._persist_locked()
            return AcquireWorkspaceResponse(workspace=_workspace_info(workspace))

    def get_workspace(self, workspace_id: str) -> Optional[WorkspaceState]:
        """Snapshot copy for the service layer (wake/purge decisions)."""
        with self._lock:
            workspace = self.workspaces.get(workspace_id)
            return workspace.model_copy() if workspace is not None else None

    def seal_workspace(self, run_id: str, idempotency_key: str) -> SealDecision:
        """Persist the cleanup intent for one Run (idempotent).

        Runs under the SAME state lock that arbitrates acquire and task
        admission, so a create racing the seal always loses. A Run with a
        workspace gets the record flipped to SEALED (new acquires and task
        admissions are refused from this persist on); a Run with no disk
        gets a Run-level permanent seal record — terminal by itself, never
        advanced to DELETED. Repeats return the record already persisted:
        like the delete idempotency rule, the first key stays recorded and
        a later key still answers the existing seal.
        """
        run_id = run_id.strip()
        idempotency_key = idempotency_key.strip()
        if not run_id or not idempotency_key:
            raise ValueError("run_id and idempotency_key must not be blank")
        with self._lock:
            for workspace in self.workspaces.values():
                if workspace.run_id != run_id:
                    continue
                if workspace.status in (
                    WorkspaceStatus.DELETING,
                    WorkspaceStatus.DELETED,
                ):
                    # Deletion already underway or finished: the seal is
                    # moot, and the truthful answer is the record as-is.
                    return SealDecision(
                        sealed=True, workspace=workspace.model_copy()
                    )
                if workspace.status != WorkspaceStatus.SEALED:
                    now = datetime.utcnow()
                    workspace.status = WorkspaceStatus.SEALED
                    workspace.status_changed_at = now
                    workspace.sealed_at = now
                    workspace.seal_idempotency_key = idempotency_key
                    self._persist_locked()
                return SealDecision(
                    sealed=True, workspace=workspace.model_copy()
                )
            if run_id not in self.run_seals:
                self.run_seals[run_id] = RunSealRecord(
                    sealed_at=datetime.utcnow(),
                    seal_idempotency_key=idempotency_key,
                )
                self._persist_locked()
            return SealDecision(sealed=True, workspace=None)

    def query_workspace(self, run_id: str) -> QueryDecision:
        """Read-only three-state answer for one Run; never creates.

        FOUND_DELETED is returned only when the irrevocable audit row is
        present (the attached workspace copy still vouches the old
        identity, so the caller can reject mis-routed or mis-audited
        answers). FOUND without a workspace means only a Run-level
        diskless seal exists. NOT_FOUND means this instance holds no
        record of the Run at all — the caller must not read that as a
        successful deletion (the disk may live on another lane or on
        another deployment generation's instance), and after a persisted
        seal a NOT_FOUND marks unclear routing/state, never a confirmed
        diskless Run.
        """
        run_id = run_id.strip()
        if not run_id:
            raise ValueError("run_id must not be blank")
        with self._lock:
            for workspace in self.workspaces.values():
                if workspace.run_id != run_id:
                    continue
                if workspace.status == WorkspaceStatus.DELETED:
                    return QueryDecision(
                        outcome=WorkspaceQueryOutcome.WORKSPACE_QUERY_FOUND_DELETED,
                        workspace=workspace.model_copy(),
                    )
                return QueryDecision(
                    outcome=WorkspaceQueryOutcome.WORKSPACE_QUERY_FOUND,
                    workspace=workspace.model_copy(),
                )
            run_seal = self.run_seals.get(run_id)
            if run_seal is not None:
                return QueryDecision(
                    outcome=WorkspaceQueryOutcome.WORKSPACE_QUERY_FOUND,
                    run_seal=run_seal.model_copy(),
                )
            return QueryDecision(
                outcome=WorkspaceQueryOutcome.WORKSPACE_QUERY_NOT_FOUND
            )

    def list_workspace_expiry_candidates(
        self, page_size: int, page_token: str
    ) -> tuple[list[WorkspaceExpiryCandidate], str]:
        """One bounded page of this instance's local non-DELETED workspaces.

        Weak-consistency cursor: rows are sorted by workspace_id and the
        token is the last row's id, so a concurrent write may repeat or
        skip a row — every row carries its run_id and activity moment for
        the caller's per-row re-checks. DELETED rows are excluded: their
        audit stays queryable per Run forever, and the page must not drown
        in history. Oversized pages are served at the cap.
        """
        if page_size <= 0:
            raise ValueError("page_size must be positive")
        limit = min(page_size, EXPIRY_CANDIDATES_PAGE_CAP)
        with self._lock:
            ordered = sorted(
                (
                    workspace
                    for workspace in self.workspaces.values()
                    if workspace.status != WorkspaceStatus.DELETED
                ),
                key=lambda workspace: workspace.workspace_id,
            )
            if page_token:
                ordered = [
                    workspace
                    for workspace in ordered
                    if workspace.workspace_id > page_token
                ]
            page = ordered[:limit]
            next_token = page[-1].workspace_id if len(ordered) > limit else ""
            return (
                [
                    WorkspaceExpiryCandidate(
                        run_id=workspace.run_id,
                        workspace_id=workspace.workspace_id,
                        workspace_generation=workspace.generation,
                        status=workspace.status,
                        last_active_at=_format_activity_utc(
                            workspace.last_active_at
                        ),
                    )
                    for workspace in page
                ],
                next_token,
            )

    def prepare_container_identity(
        self, task_id: str, deployment_id: str = "stable"
    ) -> Dict[str, str]:
        """Persist the container identity tuple BEFORE the container exists.

        Returns the label set to attach to the task's container: this state
        document's persistent instance UUID, the task id, the workspace id +
        generation (workspace tasks), and the deployment identity. A later
        sweep two-way-checks these persisted labels against live containers
        — a container whose full tuple does not match a record here is
        never touched. The crash window "container created but id not yet
        recorded" is recovered through exactly these labels.
        """
        with self._lock:
            task = self.tasks.get(task_id)
            if task is None:
                raise KeyError(f"unknown task: {task_id}")
            request = task.request
            labels = {
                "com.alphafrog.sandbox.store-instance": self.store_instance_id,
                "com.alphafrog.sandbox.task-id": task_id,
                "com.alphafrog.sandbox.deployment-id": deployment_id,
            }
            if request is not None and request.workspace_id:
                labels["com.alphafrog.sandbox.workspace-id"] = request.workspace_id
                labels["com.alphafrog.sandbox.workspace-generation"] = (
                    request.workspace_generation or ""
                )
            task.container_identity = dict(labels)
            self._persist_locked()
            return dict(labels)

    def record_container_id(self, task_id: str, container_id: Optional[str]) -> None:
        """Write the created container's id back onto the durable task."""
        if not container_id:
            return
        with self._lock:
            task = self.tasks.get(task_id)
            if task is None:
                return
            task.container_id = container_id
            self._persist_locked()

    def delete_workspace_begin(
        self,
        workspace_id: str,
        idempotency_key: str,
        expected_last_active_at: Optional[str] = None,
    ) -> DeleteDecision:
        """Delete phase 1: the durable DELETING transition.

        Happens inside the state lock BEFORE any disk or Docker touch, so
        a concurrent worker acquire (which needs ACTIVE under the same
        lock) loses cleanly — at most one side succeeds. Every
        still-QUEUED task of the workspace is terminalized FAILED here: a
        deleting workspace must not leave tasks waiting for a wake that
        will never come. The caller then verifies the holder/live
        container, removes the directory, and reports the outcome via
        delete_workspace_finish.

        A conditional (coordinator-driven) delete carries the activity
        moment observed at scan time; it is re-checked here under the same
        lock, and a mismatch refuses the delete with DELETE_REVOKED —
        nothing is deleted, and the coordinator revokes its cleanup mark
        because the workspace saw activity since the scan. The comparison
        is on time VALUES: the caller may re-serialize the moment with a
        different but equivalent spelling (trimmed fractional zeros,
        numeric offset instead of Z), so the string is parsed and compared
        as an instant, never matched literally. The check only applies
        before DELETING: once the durable intent exists the activity
        moment is frozen and a retry simply continues.
        """
        workspace_id = workspace_id.strip()
        idempotency_key = idempotency_key.strip()
        with self._lock:
            workspace = self.workspaces.get(workspace_id)
            if workspace is None:
                return DeleteDecision(outcome=DELETE_MISSING)
            if workspace.status == WorkspaceStatus.DELETED:
                # Stable idempotent answer for every post-success repeat.
                return DeleteDecision(
                    outcome=DELETE_DONE, workspace=workspace.model_copy()
                )
            if (
                expected_last_active_at is not None
                and workspace.status != WorkspaceStatus.DELETING
            ):
                # Format-invalid input is an input error, not a mismatch:
                # it raises before any state changes.
                expected_moment = parse_rfc3339_utc(expected_last_active_at)
                if (
                    workspace.last_active_at is None
                    or workspace.last_active_at != expected_moment
                ):
                    return DeleteDecision(
                        outcome=DELETE_REVOKED, workspace=workspace.model_copy()
                    )
            if workspace.status == WorkspaceStatus.DELETING:
                # A retry of an unfinished delete continues the removal; the
                # first key stays recorded (shared-result idempotency).
                if not workspace.delete_idempotency_key:
                    workspace.delete_idempotency_key = idempotency_key
                self._persist_locked()
                return DeleteDecision(
                    outcome=DELETE_STARTED, workspace=workspace.model_copy()
                )
            if workspace.holder_task_id is not None:
                holder = self.tasks.get(workspace.holder_task_id)
                if holder is not None and holder.status == TaskStatus.RUNNING:
                    # Concurrent acquire won; the delete retries later.
                    return DeleteDecision(
                        outcome=DELETE_BUSY, workspace=workspace.model_copy()
                    )
            now = datetime.utcnow()
            workspace.status = WorkspaceStatus.DELETING
            workspace.status_changed_at = now
            workspace.delete_idempotency_key = idempotency_key
            for other in self.tasks.values():
                other_request = other.request
                if (
                    other_request is None
                    or other_request.workspace_id != workspace_id
                    or other.status != TaskStatus.QUEUED
                ):
                    continue
                other.status = TaskStatus.FAILED
                other.error = (
                    f"workspace {workspace_id} entered deletion; task not"
                    " re-admitted"
                )
                other.result = build_workspace_unavailable_result(
                    other_request, "WORKSPACE_DELETING"
                )
                other.resource_usage = other.result.resource_usage
                other.retryable = False
                other.finished_at = now
            self._persist_locked()
            return DeleteDecision(
                outcome=DELETE_STARTED, workspace=workspace.model_copy()
            )

    def delete_workspace_finish(
        self, workspace_id: str, disk_removed: bool
    ) -> DeleteDecision:
        """Delete phase 2: the disk outcome becomes terminal bookkeeping.

        Only a CONFIRMED directory removal writes the irrevocable DELETED
        audit row; a failed removal keeps the retryable DELETING state so
        a repeated delete can continue. The audit row survives forever
        with no user files in it — late acquires/creates with the dead
        identity get a stable verdict instead of a re-creation.
        """
        workspace_id = workspace_id.strip()
        with self._lock:
            workspace = self.workspaces.get(workspace_id)
            if workspace is None:
                return DeleteDecision(outcome=DELETE_MISSING)
            if workspace.status == WorkspaceStatus.DELETED:
                return DeleteDecision(
                    outcome=DELETE_DONE, workspace=workspace.model_copy()
                )
            if workspace.status != WorkspaceStatus.DELETING:
                return DeleteDecision(
                    outcome=DELETE_BUSY, workspace=workspace.model_copy()
                )
            now = datetime.utcnow()
            if disk_removed:
                workspace.status = WorkspaceStatus.DELETED
                workspace.deleted_at = now
                workspace.status_changed_at = now
                # Same lock, same atomic persist: the full request bodies
                # of this workspace's terminal tasks are replaced by their
                # compact bindings together with the DELETED audit row.
                self._purge_workspace_task_bodies_locked(workspace)
                self._persist_locked()
                return DeleteDecision(
                    outcome=DELETE_DONE, workspace=workspace.model_copy()
                )
            self._persist_locked()
            return DeleteDecision(
                outcome=DELETE_RETRYABLE, workspace=workspace.model_copy()
            )

    def _purge_workspace_task_bodies_locked(
        self, workspace: WorkspaceState
    ) -> None:
        """Replace the full request body of every terminal task of a DELETED
        workspace with its compact binding.

        The disk is confirmed gone, so the user code/files/libraries/
        dataset CSVs inside the stored ExecuteRequest are no longer needed
        for any replay or audit decision: late operationId replays resolve
        through the operations table plus the Task top-level fingerprint
        and digest, and late old-identity calls are refused by the
        workspace DELETED audit row. Shape A (the original request carried
        an operationId) keeps the six-field binding that cross-validates
        against the operations entry; shape B (no operationId — legal for
        workspace tasks admitted without an operations index) keeps only
        the values the record actually had and NEVER fabricates a
        fingerprint or an operations entry. Non-terminal tasks are
        unreachable here (QUEUED siblings were terminalized when the
        workspace entered deletion and a RUNNING holder blocks the
        delete); if corrupted state ever presents one, it keeps its
        request rather than being rewritten underneath a live execution.
        """
        for task in self.tasks.values():
            request = task.request
            if request is None or request.workspace_id != workspace.workspace_id:
                continue
            if task.status not in (
                TaskStatus.SUCCEEDED,
                TaskStatus.FAILED,
                TaskStatus.CANCELED,
            ):
                continue
            operation_id = (request.operation_id or "").strip() or None
            if operation_id is not None:
                binding = PurgedRequestBinding(
                    operation_id=operation_id,
                    request_fingerprint=(
                        (request.request_fingerprint or "").strip().lower()
                        or None
                    ),
                    payload_digest=task.payload_digest,
                    run_id=request.run_id,
                    workspace_id=request.workspace_id,
                    workspace_generation=request.workspace_generation,
                )
            else:
                binding = PurgedRequestBinding(
                    payload_digest=task.payload_digest,
                    run_id=request.run_id,
                    workspace_id=request.workspace_id,
                    workspace_generation=request.workspace_generation,
                )
            task.purged_request = binding
            task.request = None

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

        Restart rule and ordering: the
        RUNNING-on-workspace candidates are snapshotted BEFORE any
        transition; their workspaces are marked DIRTY, their holder
        slots cleared and their last-activity moment moved to the same
        recovery instant (the restart IS the task's terminal moment),
        in the SAME persist that terminalizes the tasks;
        every still-QUEUED task of those workspaces is failed explicitly
        (never re-admitted); only the remaining QUEUED tasks — whose
        workspace is recorded and ACTIVE and not dirtied by this restart —
        are returned for re-enqueue. Store-side this is one atomic write;
        the deployment-identity container verification is the
        service layer's job around this call, not the store's.
        """
        queued: list[str] = []
        changed = False
        with self._lock:
            # Snapshot candidates BEFORE mutating any status.
            restart_dirtied: Dict[str, str] = {}
            for task in self.tasks.values():
                if (
                    task.status == TaskStatus.RUNNING
                    and task.request is not None
                    and task.request.workspace_id
                ):
                    restart_dirtied[task.request.workspace_id] = task.task_id
            now = datetime.utcnow()
            for workspace_id, task_id in restart_dirtied.items():
                workspace = self.workspaces.get(workspace_id)
                if workspace is not None and workspace.status == WorkspaceStatus.ACTIVE:
                    workspace.status = WorkspaceStatus.DIRTY
                    workspace.holder_task_id = None
                    workspace.dirtied_by_task_id = task_id
                    workspace.status_changed_at = now
                    # The restart-failed task reaches its terminal moment
                    # here, so the workspace's last-activity clock restarts
                    # from the same `now` — otherwise a long-running task
                    # would look idle since its start and enter retention
                    # cleanup long before its actual completion.
                    workspace.last_active_at = now
                    changed = True
                elif (
                    workspace is not None
                    and workspace.status == WorkspaceStatus.SEALED
                ):
                    # The abandoned holder is terminalized below; a sealed
                    # record keeps its status (no dirty verdict — the
                    # persisted cleanup intent remains the only truth the
                    # deletion path needs) and just frees the slot.
                    workspace.holder_task_id = None
                    workspace.status_changed_at = now
                    changed = True
            for task in self.tasks.values():
                if task.status == TaskStatus.QUEUED:
                    request = task.request
                    workspace_id = (
                        request.workspace_id if request is not None else None
                    )
                    if workspace_id:
                        workspace = self.workspaces.get(workspace_id)
                        acquirable = (
                            workspace is not None
                            and workspace.status == WorkspaceStatus.ACTIVE
                            and workspace_id not in restart_dirtied
                        )
                        if not acquirable:
                            # Fail-closed: unreachable through the normal
                            # transitions (dirty/delete already failed these
                            # siblings), so this guards corrupted state.
                            task.status = TaskStatus.FAILED
                            task.error = (
                                f"workspace {workspace_id} is not available"
                                " after restart; task not re-admitted"
                            )
                            task.result = build_workspace_unavailable_result(
                                request, "WORKSPACE_DIRTY"
                            )
                            task.resource_usage = task.result.resource_usage
                            task.retryable = False
                            task.finished_at = now
                            changed = True
                            continue
                    queued.append(task.task_id)
                elif task.status == TaskStatus.RUNNING:
                    task.status = TaskStatus.FAILED
                    task.error = "sandbox service restarted while task was running"
                    task.result = build_restart_failed_result(task.request)
                    task.resource_usage = task.result.resource_usage
                    task.retryable = task.result.retryable
                    task.finished_at = now
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
            if schema_version in (SCHEMA_VERSION_V4, SCHEMA_VERSION_V5):
                self.workspaces = {
                    workspace_id: WorkspaceState.model_validate(payload)
                    for workspace_id, payload in (
                        document.get("workspaces") or {}
                    ).items()
                }
                persisted_instance_id = document.get("store_instance_id")
                if not persisted_instance_id:
                    # Upgrade path: a pre-v4 document gets its write-once
                    # instance identity generated now; __init__ persists it
                    # immediately so consecutive restarts observe the same UUID.
                    self._regenerated_instance_id = True
                else:
                    self.store_instance_id = persisted_instance_id
                if schema_version == SCHEMA_VERSION_V5:
                    self.run_seals = {
                        run_id: RunSealRecord.model_validate(payload)
                        for run_id, payload in (
                            document.get("run_seals") or {}
                        ).items()
                    }
                else:
                    # v4 predates the Run-level seals: a non-empty section
                    # means the file is not what its version claims — fail
                    # closed.
                    if document.get("run_seals"):
                        raise ValueError(
                            f"a {schema_version} store must not carry run_seals"
                        )
                    # v4 upgrade: a workspace record without an activity
                    # moment gets the load moment as its anchor, and
                    # __init__ persists the upgraded document immediately —
                    # a lazy in-memory anchor would be reset by every
                    # restart, stretching the retention window forever.
                    upgrade_anchor = datetime.utcnow()
                    for workspace in self.workspaces.values():
                        if workspace.last_active_at is None:
                            workspace.last_active_at = upgrade_anchor
                    self._schema_upgraded = True
            else:
                # v1/v2/v3 predate the registry: an old writer never wrote a
                # workspaces section, so a non-empty one means the file is
                # not what its version claims — fail closed.
                if document.get("workspaces") or document.get("run_seals"):
                    raise ValueError(
                        f"a {schema_version} store must not carry workspaces"
                        " or run_seals"
                    )
                self._regenerated_instance_id = True
            if schema_version in (SCHEMA_VERSION_V1, SCHEMA_VERSION_V2):
                self._validate_pre_cancel_invariants_locked(schema_version)
            elif schema_version == SCHEMA_VERSION_V3:
                self._validate_v3_invariants_locked()
            elif schema_version == SCHEMA_VERSION_V4:
                self._validate_v4_invariants_locked()
            else:
                self._validate_v5_invariants_locked()
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

    def _validate_v4_invariants_locked(self) -> None:
        """v4 = v3 structure PLUS the workspace registry (fail-closed).

        * every task carrying workspace identity references an existing
          workspace whose run and generation match (the create gate
          enforces this; a violation means hand-edited state);
        * a workspace's holder is a RUNNING task bound to THAT workspace;
        * DELETED carries its audit timestamp, and only DELETED does.
        """
        self._validate_v3_invariants_locked()
        for task_id, task in self.tasks.items():
            if task.purged_request is not None:
                # The compact binding is a v5 feature; a v4 file carrying
                # one is not what its version claims — fail closed.
                raise ValueError(
                    f"task {task_id} carries purged_request in a v4 store"
                )
            request = task.request
            if request is not None and request.workspace_id:
                workspace = self.workspaces.get(request.workspace_id)
                if workspace is None:
                    raise ValueError(
                        f"task {task_id} references missing workspace"
                        f" {request.workspace_id}"
                    )
                if (
                    workspace.run_id != request.run_id
                    or workspace.generation != request.workspace_generation
                ):
                    raise ValueError(
                        f"task {task_id} workspace identity does not match"
                        f" workspace {request.workspace_id}"
                    )
        for workspace_id, workspace in self.workspaces.items():
            if workspace.status == WorkspaceStatus.DELETED:
                if workspace.deleted_at is None:
                    raise ValueError(
                        f"workspace {workspace_id} is DELETED without an"
                        " audit timestamp"
                    )
            elif workspace.deleted_at is not None:
                raise ValueError(
                    f"workspace {workspace_id} is not DELETED but carries"
                    " deleted_at"
                )
            holder_id = workspace.holder_task_id
            if holder_id is not None:
                holder = self.tasks.get(holder_id)
                if holder is None or holder.status != TaskStatus.RUNNING:
                    raise ValueError(
                        f"workspace {workspace_id} holder {holder_id} is not"
                        " a RUNNING task"
                    )
                holder_request = holder.request
                if (
                    holder_request is None
                    or holder_request.workspace_id != workspace_id
                ):
                    raise ValueError(
                        f"workspace {workspace_id} holder {holder_id} is not"
                        " bound to it"
                    )

    def _validate_v5_invariants_locked(self) -> None:
        """v5 = the v3/v4 structure PLUS seal/activity/purged-shape rules.

        Each schema version keeps its own standalone validator, so a
        version's fail-closed description never depends on another
        version's rules staying put. v5 adds:

        * a request-less task is legal iff it is a pre-create tombstone
          (the v3 shape) XOR a purged shape — a terminal task whose full
          request body was replaced at workspace deletion. Shape A (the
          original had an operationId) carries the six-field binding and
          cross-validates against the Task top-level and the operations
          entry; shape B (no operationId) carries no fingerprint (never
          fabricated), a digest consistent with the top-level when
          present, and must NOT have an operations entry;
        * the task↔workspace cross-check reads the purged binding when
          the request is gone; purged tasks exist only under a DELETED
          workspace;
        * every workspace carries its activity moment (backfilled at the
          v4 upgrade), SEALED carries its seal timestamp and key, and the
          holder/DELETED-audit rules are unchanged (holders are RUNNING,
          so a holder is never a purged task);
        * run_seals entries are structurally complete, and a Run never
          holds both a workspace record and a diskless seal (the acquire
          path checks the seal before creating, the seal path prefers the
          workspace — the two can never co-exist through the code).
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
            if task.request is not None:
                if task.purged_request is not None:
                    raise ValueError(
                        f"task {task_id} carries both its request and a"
                        " purged binding"
                    )
                continue
            binding = task.purged_request
            if binding is None:
                if (
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
                continue
            if task.status not in (
                TaskStatus.SUCCEEDED,
                TaskStatus.FAILED,
                TaskStatus.CANCELED,
            ):
                raise ValueError(
                    f"task {task_id} is purged but not in a terminal status"
                )
            if not (
                binding.run_id
                and binding.workspace_id
                and binding.workspace_generation
            ):
                raise ValueError(
                    f"task {task_id} purged binding is missing its workspace"
                    " ownership fields"
                )
            if binding.operation_id is not None:
                # Shape A: the full triplet, consistent with the Task
                # top-level AND the operations entry the replay path reads.
                if not binding.request_fingerprint or not binding.payload_digest:
                    raise ValueError(
                        f"task {task_id} purged binding (with operationId) is"
                        " missing its fingerprint or digest"
                    )
                if (
                    binding.request_fingerprint != task.request_fingerprint
                    or binding.payload_digest != task.payload_digest
                ):
                    raise ValueError(
                        f"task {task_id} purged binding does not match the"
                        " task top-level binding"
                    )
                entry = self.operations.get(binding.operation_id)
                if entry is None or entry.get("task_id") != task_id:
                    raise ValueError(
                        f"task {task_id} purged binding references a missing"
                        " operations entry"
                    )
                if (
                    entry.get("request_fingerprint") != binding.request_fingerprint
                    or entry.get("payload_digest") != binding.payload_digest
                ):
                    raise ValueError(
                        f"task {task_id} purged binding does not match its"
                        " operations entry"
                    )
            else:
                # Shape B: no operationId ever existed, so no fingerprint
                # and no operations entry may appear now, and the digest
                # (when the record had one) stays consistent.
                if binding.request_fingerprint is not None:
                    raise ValueError(
                        f"task {task_id} purged binding (without operationId)"
                        " carries a fabricated fingerprint"
                    )
                if binding.payload_digest != task.payload_digest:
                    raise ValueError(
                        f"task {task_id} purged binding digest does not match"
                        " the task top-level digest"
                    )
                for entry in self.operations.values():
                    if entry.get("task_id") == task_id:
                        raise ValueError(
                            f"task {task_id} has an operations entry but its"
                            " purged binding carries no operationId"
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
        for task_id, task in self.tasks.items():
            request = task.request
            if request is not None and request.workspace_id:
                workspace = self.workspaces.get(request.workspace_id)
                if workspace is None:
                    raise ValueError(
                        f"task {task_id} references missing workspace"
                        f" {request.workspace_id}"
                    )
                if (
                    workspace.run_id != request.run_id
                    or workspace.generation != request.workspace_generation
                ):
                    raise ValueError(
                        f"task {task_id} workspace identity does not match"
                        f" workspace {request.workspace_id}"
                    )
            elif task.purged_request is not None:
                binding = task.purged_request
                workspace = self.workspaces.get(binding.workspace_id)
                if workspace is None:
                    raise ValueError(
                        f"task {task_id} references missing workspace"
                        f" {binding.workspace_id}"
                    )
                if (
                    workspace.run_id != binding.run_id
                    or workspace.generation != binding.workspace_generation
                ):
                    raise ValueError(
                        f"task {task_id} purged workspace identity does not"
                        f" match workspace {binding.workspace_id}"
                    )
                if workspace.status != WorkspaceStatus.DELETED:
                    raise ValueError(
                        f"task {task_id} is purged under a non-DELETED"
                        f" workspace {binding.workspace_id}"
                    )
        for workspace_id, workspace in self.workspaces.items():
            if workspace.status == WorkspaceStatus.DELETED:
                if workspace.deleted_at is None:
                    raise ValueError(
                        f"workspace {workspace_id} is DELETED without an"
                        " audit timestamp"
                    )
            elif workspace.deleted_at is not None:
                raise ValueError(
                    f"workspace {workspace_id} is not DELETED but carries"
                    " deleted_at"
                )
            if workspace.last_active_at is None:
                raise ValueError(
                    f"workspace {workspace_id} is missing its activity moment"
                )
            if workspace.status == WorkspaceStatus.SEALED and (
                workspace.sealed_at is None or not workspace.seal_idempotency_key
            ):
                raise ValueError(
                    f"workspace {workspace_id} is SEALED without its seal"
                    " timestamp and key"
                )
            holder_id = workspace.holder_task_id
            if holder_id is not None:
                holder = self.tasks.get(holder_id)
                if holder is None or holder.status != TaskStatus.RUNNING:
                    raise ValueError(
                        f"workspace {workspace_id} holder {holder_id} is not"
                        " a RUNNING task"
                    )
                holder_request = holder.request
                if (
                    holder_request is None
                    or holder_request.workspace_id != workspace_id
                ):
                    raise ValueError(
                        f"workspace {workspace_id} holder {holder_id} is not"
                        " bound to it"
                    )
        for run_id, record in self.run_seals.items():
            if not run_id.strip() or not record.seal_idempotency_key.strip():
                raise ValueError(
                    f"run seal {run_id!r} is missing its identity or key"
                )
            for workspace in self.workspaces.values():
                if workspace.run_id == run_id:
                    raise ValueError(
                        f"run {run_id} holds both a workspace record and a"
                        " diskless seal"
                    )

    def _persist_locked(self) -> None:
        self.state_path.parent.mkdir(parents=True, exist_ok=True)
        document = {
            "schema_version": SCHEMA_VERSION_V5,
            "store_instance_id": self.store_instance_id,
            "tasks": {
                task_id: task.model_dump(mode="json")
                for task_id, task in self.tasks.items()
            },
            "operations": self.operations,
            "cancel_requests": self.cancel_requests,
            "workspaces": {
                workspace_id: workspace.model_dump(mode="json")
                for workspace_id, workspace in self.workspaces.items()
            },
            "run_seals": {
                run_id: record.model_dump(mode="json")
                for run_id, record in self.run_seals.items()
            },
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
