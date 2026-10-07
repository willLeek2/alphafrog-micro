"""Persistent-workspace lifecycle tests (store, config and transport shape).

These tests pin the persistent-workspace single-writer contract on the
synchronous store level (plus the config mutex and the HTTP transport
shape). Everything here runs WITHOUT Docker; the container-level
behaviors (uid probe, shared directory, restart sweep) live in
test_workspace_docker.py behind AF_RUN_DOCKER_TESTS.

Covered groups:

* positive acceptance: legacy path unchanged, workspace path
  acquire → create → begin (holder slot) → complete → release;
* busy deferral and the dirty rule: only a SUCCEEDED holder frees the
  workspace; every other holder outcome dirties it and explicitly fails
  every still-QUEUED sibling (typed result, never re-admitted);
* late-duplicate safety: a create refused by the workspace gate refuses
  again when a duplicate of the same operation arrives later (the gate
  is a pure function of monotone durable state, so no orphan task can
  appear after the caller finalized on the refusal);
* replay visibility: a task admitted before the workspace turned dirty
  is found by the operationId lookup even though a create replay of the
  same operation is refused — the authoritative recovery path is the
  query, not the replay;
* admission quota: the durable count of QUEUED tasks caps concurrent
  creates (threads), a RUNNING transition frees a slot, an idempotent
  replay never takes a second slot;
* crash window: a restart dirties the abandoned RUNNING holder's
  workspace, fails its QUEUED siblings, and requeues only healthy
  QUEUED tasks;
* delete state machine: busy-while-held, two-phase removal with
  terminalized QUEUED siblings, retryable failure on a failed removal,
  idempotent repeats, and stable refusals for the dead identity;
* switch routing: creation off blocks NEW workspaces only; existing
  ones keep being served (create + delete) after the switch flips off;
* config mutex: the warm pool and the persistent workspace cannot be
  enabled together (startup fails closed);
* transport shape: the create refusal answers 200 with an empty
  task_id, absent status and the workspace_result machine field; the
  acquire/delete endpoints map their business outcomes.
"""

from __future__ import annotations

import asyncio
import hashlib
import os
import sys
import tempfile
import threading
import types
import unittest
from pathlib import Path
from unittest.mock import patch

import pytest
from pydantic import ValidationError

from app.models import (
    AcquireWorkspaceRequest,
    AcquireWorkspaceResponse,
    CancellationEvidence,
    DeleteWorkspaceRequest,
    ExecuteRequest,
    ExecuteResult,
    Task,
    TaskStatus,
    WorkspaceResult,
    WorkspaceStatus,
)
from app.task_store import (
    DELETE_BUSY,
    DELETE_DONE,
    DELETE_MISSING,
    DELETE_RETRYABLE,
    DELETE_STARTED,
    AdmissionExhaustedError,
    CompletionCandidate,
    DurableTaskStore,
    OperationConflictError,
    request_payload_digest,
)

FINGERPRINT_A = "sha256:" + "a" * 64
IMAGE_REF = "registry.local/alphafrog/runtime@sha256:" + "f" * 64


def ws_request(
    run_id: str,
    workspace_id: str,
    generation: str = "1",
    code: str = "print(1)",
    call: str = "call-1",
    fingerprint: str = FINGERPRINT_A,
) -> ExecuteRequest:
    """A workspace-enabled request: three identity fields, no dataset.

    A workspace request may carry no dataset (later calls of the same Run
    read what earlier calls left in the workspace); the validator accepts
    dataset_id=None exactly in this shape.
    """
    return ExecuteRequest(
        dataset_id=None,
        code=code,
        operation_id=f"{run_id}:{call}:1",
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
        run_id=run_id,
        workspace_id=workspace_id,
        workspace_generation=generation,
    )


def legacy_request(
    operation_id: str = "run-legacy:call-1:1",
    fingerprint: str = FINGERPRINT_A,
    code: str = "print(1)",
) -> ExecuteRequest:
    """A pre-workspace request: no identity fields, dataset required."""
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


def ok_result() -> ExecuteResult:
    return ExecuteResult(
        exit_code=0, stdout="ok", stderr="", dataset_dir="/tmp/done"
    )


def failed_result() -> ExecuteResult:
    return ExecuteResult(
        exit_code=1, stdout="", stderr="boom", dataset_dir="/tmp/done"
    )


def complete(store: DurableTaskStore, task_id: str, result: ExecuteResult):
    return store.complete_execution(
        task_id,
        CompletionCandidate(
            status=(
                TaskStatus.SUCCEEDED
                if result.exit_code == 0
                else TaskStatus.FAILED
            ),
            result=result,
            evidence=CancellationEvidence.NONE,
        ),
    )


class _WorkspaceStoreTestBase(unittest.TestCase):
    """A fresh temp dir + DurableTaskStore per test."""

    def setUp(self) -> None:
        self._temp_dir = tempfile.TemporaryDirectory()
        self.state_path = Path(self._temp_dir.name) / "state.json"
        self.store = DurableTaskStore(self.state_path)

    def tearDown(self) -> None:
        self._temp_dir.cleanup()

    def reload_store(self) -> DurableTaskStore:
        """Simulate a service restart: a second store on the same file."""
        return DurableTaskStore(self.state_path)

    def acquire_active_workspace(self, run_id: str) -> str:
        response = self.store.acquire_workspace(
            AcquireWorkspaceRequest(run_id=run_id)
        )
        assert isinstance(response, AcquireWorkspaceResponse)
        assert response.workspace is not None
        assert response.workspace_result is None
        return response.workspace.workspace_id

    def add_ws_task(self, task_id: str, run_id: str, workspace_id: str,
                    call: str = "call-1") -> Task:
        task = Task(
            task_id=task_id,
            status=TaskStatus.QUEUED,
            request=ws_request(run_id, workspace_id, call=call),
        )
        decision = self.store.create_with_admission(task)
        assert decision.task is not None
        return decision.task


class AcceptancePositiveTests(_WorkspaceStoreTestBase):
    def test_first_acquisition_creates_and_second_returns_same(self) -> None:
        first = self.store.acquire_workspace(
            AcquireWorkspaceRequest(run_id="run-pos")
        )
        second = self.store.acquire_workspace(
            AcquireWorkspaceRequest(run_id="run-pos")
        )
        self.assertIsNotNone(first.workspace)
        self.assertIsNone(first.workspace_result)
        self.assertEqual(first.workspace.workspace_id,
                         second.workspace.workspace_id)
        self.assertEqual(first.workspace.workspace_generation, "1")
        self.assertEqual(first.workspace.status, WorkspaceStatus.ACTIVE.value)
        self.assertEqual(first.workspace.owned_by_run_id, "run-pos")
        # Durable: a restarted store serves the same identity.
        reloaded = self.reload_store()
        third = reloaded.acquire_workspace(
            AcquireWorkspaceRequest(run_id="run-pos")
        )
        self.assertEqual(third.workspace.workspace_id,
                         first.workspace.workspace_id)

    def test_workspace_task_lifecycle_takes_and_releases_holder(self) -> None:
        workspace_id = self.acquire_active_workspace("run-life")
        task = self.add_ws_task("task-life", "run-life", workspace_id)

        begin = self.store.begin_execution_exclusive("task-life")
        self.assertIsNotNone(begin.task)
        self.assertFalse(begin.busy)
        self.assertEqual(begin.task.status, TaskStatus.RUNNING)
        holder = self.store.get_workspace(workspace_id)
        self.assertEqual(holder.holder_task_id, "task-life")

        complete(self.store, "task-life", ok_result())
        released = self.store.get_workspace(workspace_id)
        self.assertEqual(released.status, WorkspaceStatus.ACTIVE)
        self.assertIsNone(released.holder_task_id)

        # The next task of the same workspace can take the slot.
        nxt = self.add_ws_task("task-life-2", "run-life", workspace_id,
                               call="call-2")
        begin2 = self.store.begin_execution_exclusive("task-life-2")
        self.assertIsNotNone(begin2.task)
        self.assertEqual(self.store.get_workspace(workspace_id).holder_task_id,
                         "task-life-2")
        self.assertEqual(nxt.status, TaskStatus.RUNNING)

    def test_legacy_task_lifecycle_unchanged(self) -> None:
        task = Task(
            task_id="task-leg",
            status=TaskStatus.QUEUED,
            request=legacy_request(),
        )
        decision = self.store.create_with_admission(task)
        self.assertIsNone(decision.workspace_result)
        begin = self.store.begin_execution_exclusive("task-leg")
        self.assertIsNotNone(begin.task)
        complete(self.store, "task-leg", ok_result())
        self.assertEqual(self.store.get("task-leg").status,
                         TaskStatus.SUCCEEDED)
        # No workspace registry entry was created for a legacy task.
        self.assertEqual(self.store.workspaces, {})


class DigestProjectionTests(_WorkspaceStoreTestBase):
    def test_identity_fields_change_the_payload_digest(self) -> None:
        # Same operation id on both sides: the ONLY difference between
        # the two digests is the identity projection.
        legacy = legacy_request(operation_id="run-dig:call-1:1")
        workspace = ws_request("run-dig", "ws-dig-1")
        # The legacy projection keeps the pre-upgrade byte layout: the
        # same request minus the identity fields digests like a legacy
        # request with identical remaining fields.
        stripped = ws_request("run-dig", "ws-dig-1").model_dump()
        for key in ("run_id", "workspace_id", "workspace_generation"):
            stripped[key] = None
        stripped["dataset_id"] = "dataset-1"
        stripped_request = ExecuteRequest.model_validate(stripped)

        self.assertNotEqual(request_payload_digest(workspace),
                            request_payload_digest(stripped_request))
        self.assertEqual(request_payload_digest(stripped_request),
                         request_payload_digest(legacy))
        # The identity projection is stable across repeated evaluation.
        self.assertEqual(request_payload_digest(workspace),
                         request_payload_digest(
                             ws_request("run-dig", "ws-dig-1")))

    def test_same_operation_replays_are_refused_or_conflict_never_wrong_disk(
        self,
    ) -> None:
        workspace = self.acquire_active_workspace("run-conf")
        # A second workspace of a DIFFERENT run, used as a fabricated
        # identity for this run's operation id.
        foreign = self.acquire_active_workspace("run-conf-2")

        first = Task(
            task_id="task-conf-1",
            status=TaskStatus.QUEUED,
            request=ws_request("run-conf", workspace, call="c1"),
        )
        self.store.create_with_admission(first)

        # Same operation id with a foreign workspace identity: the gate
        # refuses before the replay is ever consulted — the replay can
        # never return the first task through another disk.
        foreign_duplicate = Task(
            task_id="task-conf-2",
            status=TaskStatus.QUEUED,
            request=ws_request("run-conf", foreign, call="c1"),
        )
        decision = self.store.create_with_admission(foreign_duplicate)
        self.assertIsNone(decision.task)
        self.assertEqual(decision.workspace_result,
                         WorkspaceResult.WORKSPACE_OWNERSHIP_MISMATCH)
        self.assertNotIn("task-conf-2", self.store.tasks)

        # Same operation id, SAME identity, different payload: now the
        # gate passes and the payload-digest mismatch raises the
        # operation conflict (the caller may never rebind an operation
        # to different content).
        mutated = Task(
            task_id="task-conf-3",
            status=TaskStatus.QUEUED,
            request=ws_request("run-conf", workspace, call="c1",
                               code="print(2)"),
        )
        with pytest.raises(OperationConflictError):
            self.store.create_with_admission(mutated)

        # Same operation id, same identity, same payload: an idempotent
        # replay returns the original task.
        replay = Task(
            task_id="task-conf-4",
            status=TaskStatus.QUEUED,
            request=ws_request("run-conf", workspace, call="c1"),
        )
        replay_decision = self.store.create_with_admission(replay)
        self.assertIsNotNone(replay_decision.task)
        self.assertTrue(replay_decision.existing)
        self.assertEqual(replay_decision.task.task_id, "task-conf-1")


class BusyAndDirtyTests(_WorkspaceStoreTestBase):
    def test_second_begin_on_held_workspace_reports_busy(self) -> None:
        workspace_id = self.acquire_active_workspace("run-busy")
        self.add_ws_task("task-b1", "run-busy", workspace_id, call="c1")
        self.add_ws_task("task-b2", "run-busy", workspace_id, call="c2")
        first = self.store.begin_execution_exclusive("task-b1")
        self.assertIsNotNone(first.task)

        second = self.store.begin_execution_exclusive("task-b2")
        self.assertTrue(second.busy)
        self.assertIsNone(second.task)
        # The deferred task stays durably QUEUED — deferral never
        # terminalizes anything.
        self.assertEqual(self.store.get("task-b2").status, TaskStatus.QUEUED)

    def test_failed_holder_dirties_and_fails_queued_siblings(self) -> None:
        workspace_id = self.acquire_active_workspace("run-dirty")
        healthy_id = self.acquire_active_workspace("run-healthy")
        self.add_ws_task("task-h", "run-dirty", workspace_id, call="c1")
        self.add_ws_task("task-s", "run-dirty", workspace_id, call="c2")
        healthy = self.add_ws_task("task-x", "run-healthy", healthy_id,
                                   call="c1")
        self.assertIsNotNone(self.store.begin_execution_exclusive("task-h").task)

        complete(self.store, "task-h", failed_result())

        workspace = self.store.get_workspace(workspace_id)
        self.assertEqual(workspace.status, WorkspaceStatus.DIRTY)
        self.assertIsNone(workspace.holder_task_id)
        self.assertEqual(workspace.dirtied_by_task_id, "task-h")

        sibling = self.store.get("task-s")
        self.assertEqual(sibling.status, TaskStatus.FAILED)
        self.assertFalse(sibling.retryable)
        self.assertIn("marked dirty", sibling.error)
        self.assertEqual(
            sibling.result.resource_usage.exit_reason, "WORKSPACE_DIRTY"
        )

        # The healthy workspace is untouched.
        self.assertEqual(self.store.get_workspace(healthy_id).status,
                         WorkspaceStatus.ACTIVE)
        self.assertEqual(healthy.status, TaskStatus.QUEUED)

        # A NEW create for the dirty workspace is refused.
        refused = Task(
            task_id="task-new",
            status=TaskStatus.QUEUED,
            request=ws_request("run-dirty", workspace_id, call="c3"),
        )
        decision = self.store.create_with_admission(refused)
        self.assertIsNone(decision.task)
        self.assertEqual(decision.workspace_result,
                         WorkspaceResult.WORKSPACE_DIRTY)

    def test_late_duplicate_create_after_refusal_still_refused(self) -> None:
        # The gate is a pure function of monotone durable state: after a
        # refusal, a late-arriving duplicate of the same operation must be
        # refused again — the caller may safely finalize its member on
        # the first refusal without leaving an orphan task behind.
        workspace_id = self.acquire_active_workspace("run-late")
        self.add_ws_task("task-l1", "run-late", workspace_id, call="c1")
        self.assertIsNotNone(self.store.begin_execution_exclusive("task-l1").task)
        complete(self.store, "task-l1", failed_result())

        refused_request = ws_request("run-late", workspace_id, call="c2")
        first = Task(task_id="task-l2", status=TaskStatus.QUEUED,
                     request=refused_request)
        first_decision = self.store.create_with_admission(first)
        self.assertEqual(first_decision.workspace_result,
                         WorkspaceResult.WORKSPACE_DIRTY)

        # The late duplicate: same operation id, same payload.
        late = Task(task_id="task-l3", status=TaskStatus.QUEUED,
                    request=ws_request("run-late", workspace_id, call="c2"))
        late_decision = self.store.create_with_admission(late)
        self.assertIsNone(late_decision.task)
        self.assertEqual(late_decision.workspace_result,
                         WorkspaceResult.WORKSPACE_DIRTY)

        # No task was ever recorded for the operation.
        self.assertIsNone(self.store.get_by_operation_id("run-late:c2:1"))
        self.assertNotIn("task-l2", self.store.tasks)
        self.assertNotIn("task-l3", self.store.tasks)
        # The same holds through the pre-capacity consult entry.
        consult = self.store.find_existing_or_adopt_tombstone(
            ws_request("run-late", workspace_id, call="c2"), None, IMAGE_REF
        )
        self.assertIsNotNone(consult)
        self.assertEqual(consult.workspace_result,
                         WorkspaceResult.WORKSPACE_DIRTY)

    def test_replay_after_dirty_refused_but_query_finds_task(self) -> None:
        # A task was admitted BEFORE the workspace turned dirty and the
        # caller lost the create response. The replay is refused by the
        # gate (this call creates nothing), but the authoritative
        # operationId lookup still finds the original task with its
        # honest terminal result — the documented recovery path.
        workspace_id = self.acquire_active_workspace("run-replay")
        admitted = self.add_ws_task("task-r1", "run-replay", workspace_id,
                                    call="c1")
        self.assertIsNotNone(
            self.store.begin_execution_exclusive("task-r1").task
        )
        complete(self.store, "task-r1", failed_result())
        self.assertEqual(admitted.status, TaskStatus.FAILED)

        replay = Task(
            task_id="task-r2",
            status=TaskStatus.QUEUED,
            request=ws_request("run-replay", workspace_id, call="c1"),
        )
        decision = self.store.create_with_admission(replay)
        self.assertIsNone(decision.task)
        self.assertEqual(decision.workspace_result, WorkspaceResult.WORKSPACE_DIRTY)

        found = self.store.get_by_operation_id("run-replay:c1:1")
        self.assertIsNotNone(found)
        self.assertEqual(found.task_id, "task-r1")
        self.assertEqual(found.status, TaskStatus.FAILED)


class AdmissionQuotaTests(_WorkspaceStoreTestBase):
    def test_concurrent_creates_capped_by_durable_quota(self) -> None:
        self.store.admission_limit = 4
        barrier = threading.Barrier(10)
        outcomes: list[str] = []
        lock = threading.Lock()

        def creator(index: int) -> None:
            request = legacy_request(operation_id=f"run-q:call-{index}:1")
            task = Task(task_id=f"task-q-{index}", status=TaskStatus.QUEUED,
                        request=request)
            barrier.wait()
            try:
                decision = self.store.create_with_admission(task)
                with lock:
                    outcomes.append("admitted" if decision.task is not None
                                    else "refused")
            except AdmissionExhaustedError:
                with lock:
                    outcomes.append("exhausted")

        threads = [threading.Thread(target=creator, args=(i,))
                   for i in range(10)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join()

        self.assertEqual(outcomes.count("admitted"), 4)
        self.assertEqual(outcomes.count("exhausted"), 6)
        self.assertEqual(outcomes.count("refused"), 0)
        queued = sum(1 for task in self.store.tasks.values()
                     if task.status == TaskStatus.QUEUED)
        self.assertEqual(queued, 4)
        # A refused create leaves no durable trace.
        self.assertEqual(len(self.store.tasks), 4)
        self.assertEqual(len(self.store.operations), 4)

    def test_running_transition_frees_quota_slot(self) -> None:
        self.store.admission_limit = 1
        first = Task(task_id="task-f1", status=TaskStatus.QUEUED,
                     request=legacy_request(operation_id="run-f:c1:1"))
        self.store.create_with_admission(first)

        # QUEUED→RUNNING removes the task from the counted set, so the
        # next create fits without waiting for the terminal state.
        self.assertIsNotNone(
            self.store.begin_execution_exclusive("task-f1").task
        )
        second = Task(task_id="task-f2", status=TaskStatus.QUEUED,
                      request=legacy_request(operation_id="run-f:c2:1"))
        decision = self.store.create_with_admission(second)
        self.assertIsNotNone(decision.task)

    def test_idempotent_replay_takes_no_second_slot(self) -> None:
        self.store.admission_limit = 1
        first = Task(task_id="task-i1", status=TaskStatus.QUEUED,
                     request=legacy_request(operation_id="run-i:c1:1"))
        self.store.create_with_admission(first)
        replay = Task(task_id="task-i2", status=TaskStatus.QUEUED,
                      request=legacy_request(operation_id="run-i:c1:1"))
        decision = self.store.create_with_admission(replay)
        # The replay resolves to the existing task instead of consuming
        # the (already exhausted) quota.
        self.assertIsNotNone(decision.task)
        self.assertTrue(decision.existing)
        self.assertEqual(decision.task.task_id, "task-i1")


class OwnershipGateTests(_WorkspaceStoreTestBase):
    def test_unknown_workspace_not_found_then_unsupported_when_off(self) -> None:
        unknown = ws_request("run-unk", "ws-never-created")
        decision = self.store.create_with_admission(
            Task(task_id="task-unk", status=TaskStatus.QUEUED,
                 request=unknown)
        )
        self.assertEqual(decision.workspace_result,
                         WorkspaceResult.WORKSPACE_NOT_FOUND)

        self.store.workspace_creation_enabled = False
        decision_off = self.store.create_with_admission(
            Task(task_id="task-unk2", status=TaskStatus.QUEUED,
                 request=ws_request("run-unk", "ws-never-created"))
        )
        self.assertEqual(decision_off.workspace_result,
                         WorkspaceResult.WORKSPACE_UNSUPPORTED)

    def test_ownership_mismatch_refused(self) -> None:
        workspace_id = self.acquire_active_workspace("run-owner")
        decision = self.store.create_with_admission(
            Task(task_id="task-own", status=TaskStatus.QUEUED,
                 request=ws_request("run-other", workspace_id))
        )
        self.assertIsNone(decision.task)
        self.assertEqual(decision.workspace_result,
                         WorkspaceResult.WORKSPACE_OWNERSHIP_MISMATCH)

    def test_generation_mismatch_refused(self) -> None:
        workspace_id = self.acquire_active_workspace("run-gen")
        decision = self.store.create_with_admission(
            Task(task_id="task-gen", status=TaskStatus.QUEUED,
                 request=ws_request("run-gen", workspace_id, generation="2"))
        )
        self.assertEqual(decision.workspace_result,
                         WorkspaceResult.WORKSPACE_IDENTITY_CONFLICT)

    def test_known_identity_recovery_pair_rules(self) -> None:
        workspace_id = self.acquire_active_workspace("run-rec")
        match = self.store.acquire_workspace(AcquireWorkspaceRequest(
            run_id="run-rec",
            known_workspace_id=workspace_id,
            known_workspace_generation="1",
        ))
        self.assertIsNotNone(match.workspace)
        conflict = self.store.acquire_workspace(AcquireWorkspaceRequest(
            run_id="run-rec",
            known_workspace_id=workspace_id,
            known_workspace_generation="2",
        ))
        self.assertIsNone(conflict.workspace)
        self.assertEqual(conflict.workspace_result,
                         WorkspaceResult.WORKSPACE_IDENTITY_CONFLICT)
        # Half-known identity is a parameter error, not a silent
        # first-acquisition.
        with pytest.raises(ValidationError):
            AcquireWorkspaceRequest(run_id="run-rec",
                                    known_workspace_id=workspace_id)

    def test_dataset_rules_keyed_off_identity(self) -> None:
        # Legacy (no identity): blank dataset is a parameter error.
        legacy_blank = legacy_request().model_dump()
        legacy_blank["dataset_id"] = "   "
        with pytest.raises(ValidationError):
            ExecuteRequest.model_validate(legacy_blank)
        # Workspace identity: blank dataset normalizes to None.
        workspace_blank = ws_request("run-ds", "ws-ds").model_dump()
        workspace_blank["dataset_id"] = "   "
        normalized = ExecuteRequest.model_validate(workspace_blank)
        self.assertIsNone(normalized.dataset_id)
        # files always requires a dataset, workspace identity included.
        files_no_dataset = ws_request("run-ds", "ws-ds").model_dump()
        files_no_dataset["files"] = ["a.csv"]
        with pytest.raises(ValidationError):
            ExecuteRequest.model_validate(files_no_dataset)
        # Partial identity is a parameter error.
        partial = ws_request("run-ds", "ws-ds").model_dump()
        partial["workspace_generation"] = None
        with pytest.raises(ValidationError):
            ExecuteRequest.model_validate(partial)
        # operationId embeds the run id; a mismatched pair is rejected.
        mismatched = ws_request("run-ds", "ws-ds").model_dump()
        mismatched["operation_id"] = "run-other:c1:1"
        with pytest.raises(ValidationError):
            ExecuteRequest.model_validate(mismatched)


class CrashWindowTests(_WorkspaceStoreTestBase):
    def test_restart_dirties_holder_fails_siblings_requeues_healthy(self) -> None:
        dirty_id = self.acquire_active_workspace("run-crash")
        healthy_id = self.acquire_active_workspace("run-fine")
        self.add_ws_task("task-c1", "run-crash", dirty_id, call="c1")
        self.add_ws_task("task-c2", "run-crash", dirty_id, call="c2")
        self.add_ws_task("task-c3", "run-fine", healthy_id, call="c1")
        self.assertIsNotNone(
            self.store.begin_execution_exclusive("task-c1").task
        )

        reloaded = self.reload_store()
        requeue = reloaded.recover_after_restart()

        # The abandoned RUNNING holder is terminalized and its workspace
        # dirtied in the same atomic write.
        self.assertEqual(reloaded.get("task-c1").status, TaskStatus.FAILED)
        workspace = reloaded.get_workspace(dirty_id)
        self.assertEqual(workspace.status, WorkspaceStatus.DIRTY)
        self.assertEqual(workspace.dirtied_by_task_id, "task-c1")
        # The same-workspace QUEUED sibling is failed explicitly, never
        # re-admitted.
        sibling = reloaded.get("task-c2")
        self.assertEqual(sibling.status, TaskStatus.FAILED)
        self.assertEqual(sibling.result.resource_usage.exit_reason,
                         "WORKSPACE_DIRTY")
        # Only the healthy QUEUED task is re-enqueued.
        self.assertEqual(requeue, ["task-c3"])
        self.assertEqual(reloaded.get_workspace(healthy_id).status,
                         WorkspaceStatus.ACTIVE)
        # The restarted store refuses new work for the dirtied workspace.
        decision = reloaded.create_with_admission(
            Task(task_id="task-c4", status=TaskStatus.QUEUED,
                 request=ws_request("run-crash", dirty_id, call="c3"))
        )
        self.assertEqual(decision.workspace_result,
                         WorkspaceResult.WORKSPACE_DIRTY)


class DeleteRaceTests(_WorkspaceStoreTestBase):
    def test_delete_busy_while_holder_running(self) -> None:
        workspace_id = self.acquire_active_workspace("run-delbusy")
        self.add_ws_task("task-db", "run-delbusy", workspace_id)
        self.assertIsNotNone(
            self.store.begin_execution_exclusive("task-db").task
        )
        decision = self.store.delete_workspace_begin(workspace_id, "dk-1")
        self.assertEqual(decision.outcome, DELETE_BUSY)
        self.assertEqual(self.store.get_workspace(workspace_id).status,
                         WorkspaceStatus.ACTIVE)

    def test_delete_two_phase_terminalizes_siblings_and_confirms(self) -> None:
        workspace_id = self.acquire_active_workspace("run-del")
        self.add_ws_task("task-d1", "run-del", workspace_id, call="c1")
        self.add_ws_task("task-d2", "run-del", workspace_id, call="c2")
        self.assertIsNotNone(
            self.store.begin_execution_exclusive("task-d1").task
        )
        complete(self.store, "task-d1", ok_result())

        decision = self.store.delete_workspace_begin(workspace_id, "dk-1")
        self.assertEqual(decision.outcome, DELETE_STARTED)
        # Every still-QUEUED task of the workspace is terminalized at the
        # DELETING transition — nothing waits for a wake that never comes.
        sibling = self.store.get("task-d2")
        self.assertEqual(sibling.status, TaskStatus.FAILED)
        self.assertEqual(sibling.result.resource_usage.exit_reason,
                         "WORKSPACE_DELETING")

        finish = self.store.delete_workspace_finish(workspace_id, True)
        self.assertEqual(finish.outcome, DELETE_DONE)
        deleted = self.store.get_workspace(workspace_id)
        self.assertEqual(deleted.status, WorkspaceStatus.DELETED)
        self.assertIsNotNone(deleted.deleted_at)
        self.assertEqual(deleted.delete_idempotency_key, "dk-1")

        # Idempotent repeats answer DONE without re-entering the machine.
        self.assertEqual(
            self.store.delete_workspace_begin(workspace_id, "dk-1").outcome,
            DELETE_DONE,
        )
        self.assertEqual(
            self.store.delete_workspace_begin(workspace_id, "dk-other").outcome,
            DELETE_DONE,
        )
        # Late creates for the dead identity are refused; the first
        # acquisition for the run reports the recorded truth instead of
        # silently creating a replacement.
        late = self.store.create_with_admission(
            Task(task_id="task-d3", status=TaskStatus.QUEUED,
                 request=ws_request("run-del", workspace_id, call="c3"))
        )
        self.assertEqual(late.workspace_result, WorkspaceResult.WORKSPACE_DELETED)
        acquired = self.store.acquire_workspace(
            AcquireWorkspaceRequest(run_id="run-del")
        )
        self.assertIsNotNone(acquired.workspace)
        self.assertEqual(acquired.workspace.status,
                         WorkspaceStatus.DELETED.value)

    def test_delete_failed_removal_stays_retryable(self) -> None:
        workspace_id = self.acquire_active_workspace("run-retry")
        self.store.delete_workspace_begin(workspace_id, "dk-r")
        finish = self.store.delete_workspace_finish(workspace_id, False)
        self.assertEqual(finish.outcome, DELETE_RETRYABLE)
        self.assertEqual(self.store.get_workspace(workspace_id).status,
                         WorkspaceStatus.DELETING)

        # A repeated delete continues the removal until it is confirmed.
        again = self.store.delete_workspace_begin(workspace_id, "dk-r")
        self.assertEqual(again.outcome, DELETE_STARTED)
        confirmed = self.store.delete_workspace_finish(workspace_id, True)
        self.assertEqual(confirmed.outcome, DELETE_DONE)

    def test_delete_unknown_workspace_reports_missing(self) -> None:
        decision = self.store.delete_workspace_begin("ws-nope", "dk-x")
        self.assertEqual(decision.outcome, DELETE_MISSING)


class SwitchRoutingTests(_WorkspaceStoreTestBase):
    def test_creation_off_blocks_new_and_serves_existing(self) -> None:
        workspace_id = self.acquire_active_workspace("run-sw")

        self.store.workspace_creation_enabled = False

        # New run: acquisition and unknown-identity create are refused.
        self.assertEqual(
            self.store.acquire_workspace(
                AcquireWorkspaceRequest(run_id="run-sw-new")
            ).workspace_result,
            WorkspaceResult.WORKSPACE_UNSUPPORTED,
        )
        # Existing workspace: create is served (the gate passes; the
        # registry entry exists regardless of the switch).
        served = self.store.create_with_admission(
            Task(task_id="task-sw", status=TaskStatus.QUEUED,
                 request=ws_request("run-sw", workspace_id, call="c1"))
        )
        self.assertIsNone(served.workspace_result)
        self.assertIsNotNone(served.task)
        self.assertIsNotNone(
            self.store.begin_execution_exclusive("task-sw").task
        )
        complete(self.store, "task-sw", ok_result())
        # Deletion of the existing workspace still works after the flip.
        self.assertEqual(
            self.store.delete_workspace_begin(workspace_id, "dk-sw").outcome,
            DELETE_STARTED,
        )


class ConfigMutexTests(unittest.TestCase):
    def test_pool_and_workspace_mutually_exclusive(self) -> None:
        from app.config import load_config

        env = {
            "AF_SANDBOX_IMAGE": "sha256:" + "a" * 64,
            "AF_SANDBOX_IMAGE_ALLOW_DEV_TAG": "true",
            "AF_SANDBOX_POOL_ENABLED": "true",
            "AF_SANDBOX_PERSISTENT_WORKSPACE_ENABLED": "true",
            # A valid explicit root keeps the root validation quiet so the
            # mutex contradiction is the error under test.
            "AF_SANDBOX_PERSISTENT_WORKSPACE_ROOT": "/tmp/ws-mutex-test",
            "AF_DEPLOYMENT_ID": "deploy-a",
        }
        with patch.dict(os.environ, env):
            with pytest.raises(ValueError) as raised:
                load_config()
        message = str(raised.value)
        self.assertIn("AF_SANDBOX_PERSISTENT_WORKSPACE_ENABLED", message)
        self.assertIn("AF_SANDBOX_POOL_ENABLED", message)


# ---------------------------------------------------------------------------
# Transport shape (app.main): the create refusal rides the normal 200
# response; the acquire/delete endpoints map their business outcomes.
# ---------------------------------------------------------------------------

os.environ.setdefault(
    "AF_SANDBOX_IMAGE",
    "sha256:" + "a" * 64,
)
os.environ.setdefault("AF_SANDBOX_IMAGE_ALLOW_DEV_TAG", "true")

llm_sandbox = types.ModuleType("llm_sandbox")
llm_sandbox.SandboxSession = object
llm_sandbox_exceptions = types.ModuleType("llm_sandbox.exceptions")
llm_sandbox_exceptions.SandboxTimeoutError = TimeoutError
sys.modules.setdefault("llm_sandbox", llm_sandbox)
sys.modules.setdefault("llm_sandbox.exceptions", llm_sandbox_exceptions)

from fastapi.testclient import TestClient  # noqa: E402

from app import main  # noqa: E402
from app.canonical_fingerprint import spec_from_request  # noqa: E402


def endpoint_request(run_id: str, workspace_id: str, call: str) -> dict:
    """Serialize a workspace request with its REAL canonical fingerprint."""
    request = ws_request(run_id, workspace_id, call=call)
    request.request_fingerprint = spec_from_request(request).request_fingerprint()
    return request.model_dump()


class TransportShapeTests(unittest.TestCase):
    def setUp(self) -> None:
        self._temp_dir = tempfile.TemporaryDirectory()
        self.store = DurableTaskStore(
            Path(self._temp_dir.name) / "state.json"
        )
        self._previous_store = main.task_store
        self._previous_tasks = main.tasks
        self._previous_root = main.config.persistent_workspace_root
        main.task_store = self.store
        main.tasks = self.store.tasks
        object.__setattr__(
            main.config,
            "persistent_workspace_root",
            Path(self._temp_dir.name) / "persistent_workspaces",
        )
        self.client = TestClient(main.app)

    def tearDown(self) -> None:
        main.task_store = self._previous_store
        main.tasks = self._previous_tasks
        object.__setattr__(
            main.config,
            "persistent_workspace_root",
            self._previous_root,
        )
        self._temp_dir.cleanup()

    def test_acquire_create_refusal_and_delete_endpoints(self) -> None:
        acquired = self.client.post(
            "/workspaces/acquire", json={"run_id": "run-ep"}
        )
        self.assertEqual(acquired.status_code, 200)
        # The snake_case body mirrors the pydantic model.
        body = acquired.json()["workspace"]
        workspace_id = body["workspace_id"]
        self.assertEqual(body["status"], WorkspaceStatus.ACTIVE.value)
        self.assertEqual(body["owned_by_run_id"], "run-ep")

        created = self.client.post(
            "/tasks", json=endpoint_request("run-ep", workspace_id, "c1")
        )
        self.assertEqual(created.status_code, 200)
        self.assertTrue(created.json()["task_id"])
        self.assertIsNone(created.json().get("workspace_result"))

        # Dirty the workspace through the store (holder failure), then a
        # NEW create must answer the machine refusal on the 200 body.
        begin = self.store.begin_execution_exclusive(
            self.store.get_by_operation_id("run-ep:c1:1").task_id
        )
        self.assertIsNotNone(begin.task)
        complete(self.store, begin.task.task_id, failed_result())

        refused = self.client.post(
            "/tasks", json=endpoint_request("run-ep", workspace_id, "c2")
        )
        self.assertEqual(refused.status_code, 200)
        payload = refused.json()
        self.assertEqual(payload["task_id"], "")
        self.assertIsNone(payload["status"])
        self.assertEqual(payload["workspace_result"], "WORKSPACE_DIRTY")

        # The authoritative lookup still finds the admitted task.
        lookup = self.client.get("/operations/run-ep:c1:1")
        self.assertEqual(lookup.status_code, 200)
        self.assertTrue(lookup.json()["found"])

        # Delete: two-phase with a real directory under the temp root. The
        # live-container pre-check is faked green here (its refusal path
        # has a dedicated test below).
        with patch(
            "app.main.verify_workspace_containers_stopped",
            return_value=True,
        ):
            deleted = self.client.post(
                "/workspaces/delete",
                json={"workspace_id": workspace_id, "idempotency_key": "dk-ep"},
            )
        self.assertEqual(deleted.status_code, 200)
        self.assertTrue(deleted.json()["deleted"])
        self.assertFalse(deleted.json()["retryable_failure"])

        missing = self.client.post(
            "/workspaces/delete",
            json={"workspace_id": "ws-nope", "idempotency_key": "dk-x"},
        )
        self.assertEqual(missing.status_code, 404)

        # Half-known recovery identity on acquire: parameter error 400.
        bad_acquire = self.client.post(
            "/workspaces/acquire",
            json={"run_id": "run-ep", "known_workspace_id": workspace_id},
        )
        self.assertEqual(bad_acquire.status_code, 400)

    def test_delete_keeps_deleting_when_container_check_fails(self) -> None:
        acquired = self.client.post(
            "/workspaces/acquire", json={"run_id": "run-dcc"}
        )
        workspace_id = acquired.json()["workspace"]["workspace_id"]

        # The live-container pre-check refusing (Docker unreachable, or a
        # foreign container still binds the directory) must keep the
        # retryable DELETING state — the directory is never removed blind.
        with patch(
            "app.main.verify_workspace_containers_stopped",
            return_value=False,
        ):
            refused = self.client.post(
                "/workspaces/delete",
                json={"workspace_id": workspace_id,
                      "idempotency_key": "dk-dcc"},
            )
        self.assertEqual(refused.status_code, 200)
        self.assertFalse(refused.json()["deleted"])
        self.assertTrue(refused.json()["retryable_failure"])
        self.assertEqual(
            self.store.get_workspace(workspace_id).status,
            WorkspaceStatus.DELETING,
        )

        # The same idempotency key continues the delete once the check
        # passes.
        with patch(
            "app.main.verify_workspace_containers_stopped",
            return_value=True,
        ):
            finished = self.client.post(
                "/workspaces/delete",
                json={"workspace_id": workspace_id,
                      "idempotency_key": "dk-dcc"},
            )
        self.assertTrue(finished.json()["deleted"])


class ComposeWorkspaceMountContractTests(unittest.TestCase):
    """Pin the compose wiring of the persistent-workspace root.

    The service resolves the trusted root inside its own container and
    hands that very path to the host Docker daemon as the task container's
    bind source, which the daemon resolves on the HOST filesystem. The
    host path, the container path and the configured root may therefore
    never diverge: compose wires all three positions from ONE substitution
    source so they cannot drift apart, and the substitution default must
    be an absolute path.
    """

    def test_persistent_workspace_mount_uses_one_identity_path(self) -> None:
        try:
            import yaml  # type: ignore[import-untyped]
        except ImportError:  # pragma: no cover
            self.skipTest("pyyaml not installed")
        repo_root = Path(__file__).resolve().parents[2]
        compose = yaml.safe_load(
            (repo_root / "docker-compose.yml").read_text(encoding="utf-8")
        )
        service = compose["services"]["python-sandbox-service"]
        env_root = service["environment"]["AF_SANDBOX_PERSISTENT_WORKSPACE_ROOT"]
        mounts = [
            volume for volume in service["volumes"]
            if "persistent_workspaces" in volume
        ]
        self.assertEqual(len(mounts), 1)
        # Both sides may be ${VAR:-default} substitutions, so a plain colon
        # split is ambiguous; the mount separator is the "}:${" boundary
        # (falling back to the last colon for plain paths).
        raw_mount = mounts[0]
        if "}:${" in raw_mount:
            host_side, rest = raw_mount.split("}:${", 1)
            host_side += "}"
            container_side = "${" + rest
        else:
            host_side, container_side = raw_mount.rsplit(":", 1)
        self.assertEqual(
            host_side, container_side,
            "host and container side must be the SAME absolute path: the "
            "service hands its configured root to the host Docker daemon "
            "as the bind source, so any divergence mounts task containers "
            "onto an unrelated host directory",
        )
        self.assertEqual(
            env_root, container_side,
            "the service trusted root must come from the same substitution "
            "source as the volume, so the bind source it computes always "
            "matches the directory mounted into the service container",
        )
        for side in (host_side, container_side, env_root):
            candidate = side
            if candidate.startswith("${"):
                candidate = candidate.split(":-", 1)[1].rstrip("}")
            self.assertTrue(
                candidate.startswith("/"),
                f"substitution default must be an absolute path, got {side!r}",
            )


class HeaderOnlyCsvNormalizationTests(_WorkspaceStoreTestBase):
    def test_header_only_csvs_normalize_to_absent_when_no_dataset(self) -> None:
        base = ws_request("run-csv", "ws-csv").model_dump()
        base["paths_dataset_csv"] = "run_local_id,persisted_path"
        base["path_manifest_csv"] = "manifest_id,source_path\n\n"
        request = ExecuteRequest.model_validate(base)
        self.assertIsNone(request.paths_dataset_csv)
        self.assertIsNone(request.path_manifest_csv)
        # ONE projection: header-only and absent digests are identical, so
        # an idempotent replay across the two wire forms is stable.
        self.assertEqual(request_payload_digest(request),
                         request_payload_digest(ws_request("run-csv", "ws-csv")))
        # A CSV with real data rows is never normalized.
        filled = ws_request("run-csv", "ws-csv").model_dump()
        filled["paths_dataset_csv"] = "run_local_id,persisted_path\n1,/data/x\n"
        filled_request = ExecuteRequest.model_validate(filled)
        self.assertIsNotNone(filled_request.paths_dataset_csv)
        # With a dataset present, header-only stays a config error for the
        # runner to surface — normalization is a no-dataset-call courtesy.
        with_dataset = legacy_request().model_dump()
        with_dataset["paths_dataset_csv"] = "run_local_id,persisted_path"
        with_dataset_request = ExecuteRequest.model_validate(with_dataset)
        self.assertIsNotNone(with_dataset_request.paths_dataset_csv)


class ReadmitBusyRestoresHeadTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        self._temp_dir = tempfile.TemporaryDirectory()
        self.store = DurableTaskStore(Path(self._temp_dir.name) / "state.json")
        self._previous_store = main.task_store
        self._previous_tasks = main.tasks
        self._previous_root = main.config.persistent_workspace_root
        main.task_store = self.store
        main.tasks = self.store.tasks
        object.__setattr__(
            main.config,
            "persistent_workspace_root",
            Path(self._temp_dir.name) / "persistent_workspaces",
        )
        # Earlier tests in this module may have left admitted ids in the
        # module-level queue (no workers run under test); drain so the
        # emptiness assertions below are about THIS test only.
        while not main.task_queue.empty():
            main.task_queue.get_nowait()
            main.task_queue.task_done()

    def tearDown(self) -> None:
        main.task_store = self._previous_store
        main.tasks = self._previous_tasks
        object.__setattr__(
            main.config,
            "persistent_workspace_root",
            self._previous_root,
        )
        self._temp_dir.cleanup()

    async def test_busy_workspace_head_returns_to_fifo_front(self) -> None:
        workspace_id = self.store.acquire_workspace(
            AcquireWorkspaceRequest(run_id="run-rm")
        ).workspace.workspace_id
        for call, task_id in (("c1", "task-rm-1"), ("c2", "task-rm-2")):
            self.store.create_with_admission(Task(
                task_id=task_id,
                status=TaskStatus.QUEUED,
                request=ws_request("run-rm", workspace_id, call=call),
            ))
        # Holder takes the slot; the second task lands in the deferral FIFO.
        self.assertIsNotNone(
            self.store.begin_execution_exclusive("task-rm-1").task
        )
        main.workspace_deferrals.defer(workspace_id, "task-rm-2")
        main._readmit_inflight.discard(workspace_id)

        await main._readmit_workspace_head(workspace_id)

        # The workspace is busy again, so the head must be BACK in the FIFO
        # (not dropped) and nothing may enter the global queue.
        self.assertEqual(
            main.workspace_deferrals.pending().get(workspace_id),
            ["task-rm-2"],
        )
        self.assertTrue(main.task_queue.empty())
        # FIFO order survives a later restore: head first.
        main.workspace_deferrals.defer(workspace_id, "task-rm-3")
        popped = main.workspace_deferrals.pop_head(workspace_id)
        self.assertEqual(popped, "task-rm-2")
        main.workspace_deferrals.push_front(workspace_id, popped)
        self.assertEqual(
            main.workspace_deferrals.pop_head(workspace_id), "task-rm-2"
        )


class PoolBypassTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        self._temp_dir = tempfile.TemporaryDirectory()
        self.store = DurableTaskStore(Path(self._temp_dir.name) / "state.json")
        self._previous_store = main.task_store
        self._previous_tasks = main.tasks
        self._previous_root = main.config.persistent_workspace_root
        self._previous_pool = main.pool
        self._previous_pool_enabled = main.config.pool_enabled
        main.task_store = self.store
        main.tasks = self.store.tasks
        object.__setattr__(
            main.config,
            "persistent_workspace_root",
            Path(self._temp_dir.name) / "persistent_workspaces",
        )

    def tearDown(self) -> None:
        main.task_store = self._previous_store
        main.tasks = self._previous_tasks
        main.pool = self._previous_pool
        object.__setattr__(
            main.config, "pool_enabled", self._previous_pool_enabled
        )
        object.__setattr__(
            main.config,
            "persistent_workspace_root",
            self._previous_root,
        )
        self._temp_dir.cleanup()

    async def test_workspace_task_never_rides_the_pool(self) -> None:
        class _BoobyPool:
            def run_task(self, *args, **kwargs):  # pragma: no cover - guard
                raise AssertionError(
                    "a workspace task must never run on the warm pool"
                )

        workspace_id = self.store.acquire_workspace(
            AcquireWorkspaceRequest(run_id="run-pb")
        ).workspace.workspace_id
        task = Task(
            task_id="task-pb",
            status=TaskStatus.QUEUED,
            request=ws_request("run-pb", workspace_id),
        )
        self.store.create_with_admission(task)

        main.pool = _BoobyPool()
        object.__setattr__(main.config, "pool_enabled", True)

        captured: dict = {}

        def fake_run_in_sandbox(config, task_id, *args, **kwargs):
            captured.update(kwargs)
            return {
                "exit_code": 0, "stdout": "", "stderr": "",
                "dataset_dir": "/tmp/done", "timings": {},
                "container_id": "ctr-pb",
            }

        with patch("app.main.run_in_sandbox", fake_run_in_sandbox):
            await main._process_task_inner(self.store.get("task-pb"), 1)

        final = self.store.get("task-pb")
        self.assertEqual(final.status, TaskStatus.SUCCEEDED)
        # The fresh-container path ran WITH the workspace mount bound.
        self.assertIn("workspace_mount", captured)
        runner = captured["workspace_mount"]
        self.assertEqual(runner.labels["com.alphafrog.sandbox.workspace-id"],
                         workspace_id)
        # The identity tuple was persisted before the container existed.
        self.assertIsNotNone(final.container_identity)


class QueueStaleResidentPurgeTests(unittest.IsolatedAsyncioTestCase):
    """The durable QUEUED count is the ONLY admission authority.

    Counterexample pinned here: capacity 1, task A admitted then canceled
    while its id still sits in the in-memory transport queue. A fresh
    create B must be ADMITTED — the stale resident may neither trigger
    the early full() refusal nor make the final enqueue fail.
    """

    def setUp(self) -> None:
        self._temp_dir = tempfile.TemporaryDirectory()
        self.store = DurableTaskStore(Path(self._temp_dir.name) / "state.json")
        self.store.admission_limit = 1
        self._previous_store = main.task_store
        self._previous_tasks = main.tasks
        self._previous_queue = main.task_queue
        main.task_store = self.store
        main.tasks = self.store.tasks
        main.task_queue = asyncio.Queue(maxsize=1)

    def tearDown(self) -> None:
        main.task_store = self._previous_store
        main.tasks = self._previous_tasks
        main.task_queue = self._previous_queue
        self._temp_dir.cleanup()

    async def test_canceled_resident_does_not_refuse_next_create(self) -> None:
        def endpoint_request(operation_id: str) -> dict:
            request = legacy_request(operation_id=operation_id)
            request.request_fingerprint = (
                spec_from_request(request).request_fingerprint()
            )
            return request.model_dump()

        first = await main.create_task(
            ExecuteRequest.model_validate(endpoint_request("run-stale:c1:1"))
        )
        self.assertTrue(first.task_id)
        self.assertTrue(main.task_queue.full())

        # Cancel while queued: the durable slot is released immediately,
        # the in-memory resident is now stale.
        self.store.cancel_by_task_id("cr-stale", first.task_id, "USER_REQUEST")
        self.assertEqual(
            self.store.get(first.task_id).status, TaskStatus.CANCELED
        )
        self.assertTrue(main.task_queue.full())

        second = await main.create_task(
            ExecuteRequest.model_validate(endpoint_request("run-stale:c2:1"))
        )
        # B is admitted; no 503, no rollback.
        self.assertTrue(second.task_id)
        self.assertIsNone(second.workspace_result)
        self.assertEqual(self.store.get(second.task_id).status, TaskStatus.QUEUED)
        # The transport queue now holds ONLY the live id.
        residents = []
        while not main.task_queue.empty():
            residents.append(main.task_queue.get_nowait())
            main.task_queue.task_done()
        self.assertEqual(residents, [second.task_id])


class DeleteContainerCheckTests(_WorkspaceStoreTestBase):
    """The pre-removal container check uses the FULL identity tuple.

    An unknown task id or a wrong generation on a same-deployment,
    same-workspace container must NOT be stopped; only a container whose
    labels match a persisted task record two-for-two is removed.
    """

    class _FakeContainer:
        def __init__(self, labels: dict):
            self.labels = dict(labels)
            self.id = "ctr-" + labels.get("com.alphafrog.sandbox.task-id", "x")
            self.status = "running"
            self.stopped = False
            self.removed = False

        def stop(self, timeout: int = 10) -> None:
            self.stopped = True

        def remove(self, force: bool = False) -> None:
            self.removed = True

    class _FakeClient:
        def __init__(self, containers):
            self._containers = containers
            self.containers = self

        def list(self, all=False, filters=None):  # noqa: A002 - docker API
            return self._containers

    def test_unknown_task_and_wrong_generation_are_not_stopped(self) -> None:
        from app.sandbox_runner import verify_workspace_containers_stopped

        workspace_id = self.acquire_active_workspace("run-vc")
        self.add_ws_task("task-vc", "run-vc", workspace_id)
        identity = self.store.prepare_container_identity("task-vc", "deploy-test")
        config = types.SimpleNamespace(deployment_id="deploy-test")

        forged = {
            "com.alphafrog.role": "python-sandbox-worker",
            "com.alphafrog.sandbox.store-instance":
                self.store.store_instance_id,
            "com.alphafrog.sandbox.deployment-id": "deploy-test",
            "com.alphafrog.sandbox.task-id": "unknown-task",
            "com.alphafrog.sandbox.workspace-id": workspace_id,
            "com.alphafrog.sandbox.workspace-generation": "999",
        }
        stale_generation = dict(identity)
        stale_generation["com.alphafrog.sandbox.workspace-generation"] = "2"
        for labels in (forged, stale_generation):
            container = self._FakeContainer(labels)
            client = self._FakeClient([container])
            with patch(
                "app.sandbox_runner.build_docker_client", return_value=client
            ):
                verified = verify_workspace_containers_stopped(
                    config, self.store, workspace_id
                )
            self.assertFalse(verified)
            self.assertFalse(container.stopped)
            self.assertFalse(container.removed)

    def test_fully_matched_container_is_stopped_and_removed(self) -> None:
        from app.sandbox_runner import verify_workspace_containers_stopped

        workspace_id = self.acquire_active_workspace("run-vc2")
        self.add_ws_task("task-vc2", "run-vc2", workspace_id)
        identity = self.store.prepare_container_identity("task-vc2", "deploy-test")
        config = types.SimpleNamespace(deployment_id="deploy-test")

        matched = self._FakeContainer({
            "com.alphafrog.role": "python-sandbox-worker",
            **identity,
        })
        client = self._FakeClient([matched])
        with patch(
            "app.sandbox_runner.build_docker_client", return_value=client
        ):
            verified = verify_workspace_containers_stopped(
                config, self.store, workspace_id
            )
        self.assertTrue(verified)
        self.assertTrue(matched.stopped)
        self.assertTrue(matched.removed)


class ConfigWorkspaceValidationTests(unittest.TestCase):
    """Startup validation of the trusted root and the deployment identity."""

    def setUp(self) -> None:
        self._temp_dir = tempfile.TemporaryDirectory()

    def tearDown(self) -> None:
        self._temp_dir.cleanup()

    def _load(self, extra: dict, pop_deployment: bool = False,
              pop_root: bool = False):
        from app.config import load_config

        env = {
            "AF_SANDBOX_IMAGE": "sha256:" + "a" * 64,
            "AF_SANDBOX_IMAGE_ALLOW_DEV_TAG": "true",
            "AF_SANDBOX_WORKDIR": str(Path(self._temp_dir.name) / "sandbox"),
        }
        env.update(extra)
        with patch.dict(os.environ, env, clear=False):
            if pop_deployment:
                os.environ.pop("AF_DEPLOYMENT_ID", None)
            if pop_root:
                os.environ.pop("AF_SANDBOX_PERSISTENT_WORKSPACE_ROOT", None)
            return load_config()

    def test_missing_root_variable_rejected_when_enabled(self) -> None:
        # With the feature on there is no default location: the variable
        # itself must be present and non-blank, so a deployment that forgot
        # to set it fails startup instead of silently using a host path it
        # never mounted into the service container.
        with pytest.raises(ValueError) as raised:
            self._load({
                "AF_SANDBOX_PERSISTENT_WORKSPACE_ENABLED": "true",
                "AF_DEPLOYMENT_ID": "deploy-a",
            }, pop_root=True)
        self.assertIn("AF_SANDBOX_PERSISTENT_WORKSPACE_ROOT", str(raised.value))
        for blank in ("", "   "):
            with pytest.raises(ValueError) as raised:
                self._load({
                    "AF_SANDBOX_PERSISTENT_WORKSPACE_ENABLED": "true",
                    "AF_SANDBOX_PERSISTENT_WORKSPACE_ROOT": blank,
                    "AF_DEPLOYMENT_ID": "deploy-a",
                })
            self.assertIn(
                "AF_SANDBOX_PERSISTENT_WORKSPACE_ROOT", str(raised.value)
            )

    def test_whitespace_padded_root_rejected_when_enabled(self) -> None:
        # The compose volume mount substitutes the variable VERBATIM, so a
        # padded value must fail startup instead of being normalized inside
        # the service — normalizing would split the service root and the
        # mounted directory apart.
        for padded in (" /data/persistent_workspaces",
                       "/data/persistent_workspaces ",
                       " /data/persistent_workspaces "):
            with pytest.raises(ValueError) as raised:
                self._load({
                    "AF_SANDBOX_PERSISTENT_WORKSPACE_ENABLED": "true",
                    "AF_SANDBOX_PERSISTENT_WORKSPACE_ROOT": padded,
                    "AF_DEPLOYMENT_ID": "deploy-a",
                })
            self.assertIn(
                "AF_SANDBOX_PERSISTENT_WORKSPACE_ROOT", str(raised.value)
            )
            self.assertIn("whitespace", str(raised.value))

    def test_default_root_kept_when_feature_off(self) -> None:
        config = self._load({}, pop_root=True, pop_deployment=True)
        self.assertEqual(
            config.persistent_workspace_root,
            Path("/sandbox/persistent_workspaces"),
        )

    def test_relative_root_rejected_when_enabled(self) -> None:
        with pytest.raises(ValueError) as raised:
            self._load({
                "AF_SANDBOX_PERSISTENT_WORKSPACE_ENABLED": "true",
                "AF_SANDBOX_PERSISTENT_WORKSPACE_ROOT": "relative/ws",
                "AF_DEPLOYMENT_ID": "deploy-a",
            })
        self.assertIn("absolute", str(raised.value))

    def test_root_aliasing_control_tree_rejected_after_normalization(self) -> None:
        workdir = str(Path(self._temp_dir.name) / "sandbox")
        # The env values deliberately carry a redundant /./ or /sub/../
        # segment: the aliasing verdict must be computed on the NORMALIZED
        # path, not the raw string.
        for env_root in (
            workdir + "/.",
            workdir + "/sub/../runs",
            workdir + "/sub/ws",
        ):
            with pytest.raises(ValueError) as raised:
                self._load({
                    "AF_SANDBOX_PERSISTENT_WORKSPACE_ENABLED": "true",
                    "AF_SANDBOX_PERSISTENT_WORKSPACE_ROOT": env_root,
                    "AF_DEPLOYMENT_ID": "deploy-a",
                })
            self.assertIn("control", str(raised.value))

    def test_valid_root_stored_normalized(self) -> None:
        root = str(Path(self._temp_dir.name) / "ws")
        config = self._load({
            "AF_SANDBOX_PERSISTENT_WORKSPACE_ENABLED": "true",
            "AF_SANDBOX_PERSISTENT_WORKSPACE_ROOT": root + "/.",
            "AF_DEPLOYMENT_ID": "deploy-a",
        })
        self.assertEqual(
            config.persistent_workspace_root, Path(root).resolve()
        )
        self.assertEqual(config.deployment_id, "deploy-a")

    def test_deployment_identity_required_when_enabled(self) -> None:
        base = {
            "AF_SANDBOX_PERSISTENT_WORKSPACE_ENABLED": "true",
            "AF_SANDBOX_PERSISTENT_WORKSPACE_ROOT":
                str(Path(self._temp_dir.name) / "ws"),
        }
        for deployment_value, pop in (("stable", False), ("", False),
                                      (None, True), ("   ", False)):
            extra = dict(base)
            if deployment_value is not None:
                extra["AF_DEPLOYMENT_ID"] = deployment_value
            with pytest.raises(ValueError) as raised:
                self._load(extra, pop_deployment=pop)
            self.assertIn("AF_DEPLOYMENT_ID", str(raised.value))

    def test_deployment_placeholder_kept_when_feature_off(self) -> None:
        config = self._load({}, pop_deployment=True)
        self.assertEqual(config.deployment_id, "stable")


class BlankIdentityTests(_WorkspaceStoreTestBase):
    def test_blank_acquire_identity_rejected(self) -> None:
        with pytest.raises(ValidationError):
            AcquireWorkspaceRequest(run_id="   ")
        with pytest.raises(ValidationError):
            AcquireWorkspaceRequest(run_id="run-bi",
                                    known_workspace_id="ws-1",
                                    known_workspace_generation="  ")
        stripped = AcquireWorkspaceRequest(run_id="  run-bi  ",
                                           known_workspace_id=" ws-1 ",
                                           known_workspace_generation=" 1 ")
        self.assertEqual(stripped.run_id, "run-bi")
        self.assertEqual(stripped.known_workspace_id, "ws-1")
        self.assertEqual(stripped.known_workspace_generation, "1")

    def test_blank_delete_identifiers_rejected(self) -> None:
        with pytest.raises(ValidationError):
            DeleteWorkspaceRequest(workspace_id="  ", idempotency_key="dk")
        with pytest.raises(ValidationError):
            DeleteWorkspaceRequest(workspace_id="ws-1", idempotency_key=" ")

    def test_blank_execute_identity_rejected_not_silently_legacy(self) -> None:
        request = legacy_request().model_dump()
        request["run_id"] = "   "
        request["workspace_id"] = "ws-1"
        request["workspace_generation"] = "1"
        with pytest.raises(ValidationError):
            ExecuteRequest.model_validate(request)

    def test_store_defends_against_blank_run_id(self) -> None:
        with pytest.raises(ValueError):
            self.store.acquire_workspace(
                AcquireWorkspaceRequest.model_construct(run_id="   ")
            )
