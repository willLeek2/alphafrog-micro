"""Workspace expiry-phase tests: seal, query, candidates, conditional
delete, activity timestamps, and the post-deletion request-body purge.

Everything here runs WITHOUT Docker, mirroring
test_workspace_lifecycle.py (store level plus the HTTP transport shape);
container-level verification stays behind AF_RUN_DOCKER_TESTS in
test_workspace_docker.py.

Covered groups:

* seal: diskless and with-disk idempotency (first key stays recorded),
  sealed acquire/create refusals ride the existing WORKSPACE_DELETING
  business verdict, the diskless seal wins the eligibility-to-creation
  race against acquire, a sealed workspace never falls back to
  ACTIVE/DIRTY (holder finish and restart recovery only free the slot);
* per-Run read-only query: NOT_FOUND / FOUND (every status, plus the
  FOUND-without-workspace diskless seal) / FOUND_DELETED with the old
  identity attached; the query never creates a record;
* expiry candidates: stable workspaceId ordering, page-token walking,
  DELETED exclusion, the server-side page cap, row field shape;
* conditional delete: a matching scan-time activity moment deletes, new
  activity since the scan revokes (nothing deleted), the check is skipped
  once DELETING exists, busy/deleted answers unchanged;
* activity timestamps: refreshed at acquire/create, admission, holder
  begin and completion; frozen on DELETING/DELETED; the v4 upgrade
  backfills the anchor and persists it in the upgrade write itself;
* purge: after a confirmed deletion the full request bodies are replaced
  by the compact binding — shape A (operationId present) cross-validates
  against the operations entry, shape B (no operationId) fabricates
  nothing; both shapes reload under the DELETED workspace; late
  old-identity calls get the DELETED verdict;
* transport shape: the seal/query/candidates endpoints and the delete
  outcome classification on the normal 200 body.
"""

from __future__ import annotations

import hashlib
import json
import os
import sys
import tempfile
import types
import unittest
from datetime import datetime
from pathlib import Path
from unittest.mock import patch

from app.models import (
    AcquireWorkspaceRequest,
    CancellationEvidence,
    DeleteWorkspaceRequest,
    ExecuteRequest,
    ExecuteResult,
    Task,
    TaskStatus,
    WorkspaceQueryOutcome,
    WorkspaceResult,
    WorkspaceStatus,
)
from app.task_store import (
    DELETE_BUSY,
    DELETE_DONE,
    DELETE_REVOKED,
    DELETE_STARTED,
    AdmissionExhaustedError,
    CompletionCandidate,
    DurableTaskStore,
    _format_activity_utc,
)

FINGERPRINT_A = "sha256:" + "a" * 64

# A deterministic stale sentinel: tests plant it before an activity point
# and then assert the point refreshed the timestamp (no reliance on the
# wall clock advancing between two calls).
STALE = datetime(2020, 1, 1, 0, 0, 0)


def ws_request(
    run_id: str,
    workspace_id: str,
    call: str = "call-1",
    with_operation: bool = True,
    code: str = "print(1)",
) -> ExecuteRequest:
    """A workspace-enabled request, with or without the operation binding.

    The create contract allows workspace tasks WITHOUT an operationId
    (admitted without an operations index entry); both shapes exist in
    real state documents and the purge must handle each.
    """
    return ExecuteRequest(
        dataset_id=None,
        code=code,
        operation_id=f"{run_id}:{call}:1" if with_operation else None,
        request_fingerprint=FINGERPRINT_A if with_operation else None,
        resource_class="STANDARD",
        memory_limit_bytes=512 * 1024 * 1024,
        timeout_millis=60_000,
        runtime_environment_version="python-runtime-v1",
        canonical_spec_schema_version=(
            "sandbox_create_v1" if with_operation else None
        ),
        code_hash=(
            "sha256:" + hashlib.sha256(code.encode("utf-8")).hexdigest()
            if with_operation
            else None
        ),
        immutable_dataset_snapshot_digest=(
            "sha256:" + "c" * 64 if with_operation else None
        ),
        libraries_digest="sha256:" + "d" * 64 if with_operation else None,
        sandbox_options_digest="sha256:" + "e" * 64 if with_operation else None,
        run_id=run_id,
        workspace_id=workspace_id,
        workspace_generation="1",
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


class _ExpiryStoreTestBase(unittest.TestCase):
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
        assert response.workspace is not None
        assert response.workspace_result is None
        return response.workspace.workspace_id

    def add_ws_task(
        self,
        task_id: str,
        run_id: str,
        workspace_id: str,
        call: str = "call-1",
        with_operation: bool = True,
        code: str = "print(1)",
    ) -> Task:
        task = Task(
            task_id=task_id,
            status=TaskStatus.QUEUED,
            request=ws_request(
                run_id, workspace_id, call=call,
                with_operation=with_operation, code=code,
            ),
        )
        decision = self.store.create_with_admission(task)
        assert decision.task is not None
        return decision.task

    def delete_workspace(self, workspace_id: str, key: str = "dk-1") -> None:
        begin = self.store.delete_workspace_begin(workspace_id, key)
        assert begin.outcome == DELETE_STARTED, begin.outcome
        finish = self.store.delete_workspace_finish(workspace_id, True)
        assert finish.outcome == DELETE_DONE, finish.outcome


class SealStoreTests(_ExpiryStoreTestBase):
    def test_diskless_seal_idempotent_and_wins_the_create_race(self) -> None:
        first = self.store.seal_workspace("run-seal", "sk-1")
        self.assertTrue(first.sealed)
        self.assertIsNone(first.workspace)
        record = self.store.run_seals["run-seal"]
        self.assertEqual(record.seal_idempotency_key, "sk-1")

        # Same key AND a different key both return the record already
        # persisted (first key stays recorded, same as the delete rule).
        again = self.store.seal_workspace("run-seal", "sk-1")
        other_key = self.store.seal_workspace("run-seal", "sk-2")
        self.assertTrue(again.sealed and other_key.sealed)
        kept = self.store.run_seals["run-seal"]
        self.assertEqual(kept.seal_idempotency_key, "sk-1")
        self.assertEqual(kept.sealed_at, record.sealed_at)

        # The create side loses the eligibility-to-creation race: no
        # workspace is created for a sealed Run, and the answer rides the
        # existing DELETING business verdict.
        acquire = self.store.acquire_workspace(
            AcquireWorkspaceRequest(run_id="run-seal")
        )
        self.assertIsNone(acquire.workspace)
        self.assertEqual(
            acquire.workspace_result, WorkspaceResult.WORKSPACE_DELETING
        )
        self.assertEqual(len(self.store.workspaces), 0)

        # Durable across a restart.
        reloaded = self.reload_store()
        acquire2 = reloaded.acquire_workspace(
            AcquireWorkspaceRequest(run_id="run-seal")
        )
        self.assertEqual(
            acquire2.workspace_result, WorkspaceResult.WORKSPACE_DELETING
        )
        self.assertEqual(len(reloaded.workspaces), 0)

    def test_seal_with_disk_refuses_acquire_and_create(self) -> None:
        workspace_id = self.acquire_active_workspace("run-sealws")
        decision = self.store.seal_workspace("run-sealws", "sk-ws")
        self.assertTrue(decision.sealed)
        self.assertIsNotNone(decision.workspace)
        self.assertEqual(decision.workspace.status, WorkspaceStatus.SEALED)
        self.assertEqual(decision.workspace.seal_idempotency_key, "sk-ws")
        self.assertIsNotNone(decision.workspace.sealed_at)

        # Repeat seals answer the same record (first key kept).
        repeat = self.store.seal_workspace("run-sealws", "sk-other")
        self.assertEqual(repeat.workspace.status, WorkspaceStatus.SEALED)
        self.assertEqual(repeat.workspace.seal_idempotency_key, "sk-ws")

        # Acquire (both the scan path and the known-identity recovery
        # path) is refused with the DELETING verdict.
        scan = self.store.acquire_workspace(
            AcquireWorkspaceRequest(run_id="run-sealws")
        )
        self.assertEqual(scan.workspace_result, WorkspaceResult.WORKSPACE_DELETING)
        known = self.store.acquire_workspace(
            AcquireWorkspaceRequest(
                run_id="run-sealws",
                known_workspace_id=workspace_id,
                known_workspace_generation="1",
            )
        )
        self.assertEqual(known.workspace_result, WorkspaceResult.WORKSPACE_DELETING)

        # A create carrying the sealed identity is refused by the same
        # gate verdict — no task is created.
        task = Task(
            task_id="task-sealed",
            status=TaskStatus.QUEUED,
            request=ws_request("run-sealws", workspace_id),
        )
        created = self.store.create_with_admission(task)
        self.assertIsNone(created.task)
        self.assertEqual(
            created.workspace_result, WorkspaceResult.WORKSPACE_DELETING
        )
        self.assertNotIn("task-sealed", self.store.tasks)

        # Durable across a restart (the v5 record round-trips).
        reloaded = self.reload_store()
        self.assertEqual(
            reloaded.get_workspace(workspace_id).status, WorkspaceStatus.SEALED
        )

    def test_seal_after_deleting_and_deleted_returns_the_truth(self) -> None:
        workspace_id = self.acquire_active_workspace("run-sealdel")
        begin = self.store.delete_workspace_begin(workspace_id, "dk-1")
        self.assertEqual(begin.outcome, DELETE_STARTED)
        # Deletion already underway: the seal is moot, the record is
        # returned as-is (NOT flipped away from DELETING).
        sealing = self.store.seal_workspace("run-sealdel", "sk-d")
        self.assertTrue(sealing.sealed)
        self.assertEqual(sealing.workspace.status, WorkspaceStatus.DELETING)

        finish = self.store.delete_workspace_finish(workspace_id, True)
        self.assertEqual(finish.outcome, DELETE_DONE)
        sealed_after = self.store.seal_workspace("run-sealdel", "sk-d")
        self.assertEqual(sealed_after.workspace.status, WorkspaceStatus.DELETED)
        # The Run-level diskless seal is never created alongside a
        # workspace record.
        self.assertNotIn("run-sealdel", self.store.run_seals)

    def test_sealed_holder_finish_keeps_sealed(self) -> None:
        workspace_id = self.acquire_active_workspace("run-sealhold")
        self.add_ws_task("task-hold", "run-sealhold", workspace_id, call="c1")
        self.add_ws_task("task-queued", "run-sealhold", workspace_id, call="c2")
        begin = self.store.begin_execution_exclusive("task-hold")
        self.assertIsNotNone(begin.task)

        # Sealing while the holder runs: the seal lands, the holder slot
        # stays with the running task.
        self.store.seal_workspace("run-sealhold", "sk-h")
        held = self.store.get_workspace(workspace_id)
        self.assertEqual(held.status, WorkspaceStatus.SEALED)
        self.assertEqual(held.holder_task_id, "task-hold")

        # The holder finishing (FAILED here) only frees the slot — a
        # sealed record never falls back to DIRTY, and the QUEUED sibling
        # waits for the deletion to terminalize it.
        complete(self.store, "task-hold", failed_result())
        after = self.store.get_workspace(workspace_id)
        self.assertEqual(after.status, WorkspaceStatus.SEALED)
        self.assertIsNone(after.holder_task_id)
        self.assertEqual(
            self.store.get("task-queued").status, TaskStatus.QUEUED
        )

        # The deletion then proceeds on the sealed record and terminalizes
        # the sibling.
        deleted = self.store.delete_workspace_begin(workspace_id, "dk-h")
        self.assertEqual(deleted.outcome, DELETE_STARTED)
        self.assertEqual(
            self.store.get("task-queued").status, TaskStatus.FAILED
        )

    def test_sealed_restart_recovery_keeps_sealed(self) -> None:
        workspace_id = self.acquire_active_workspace("run-sealrst")
        self.add_ws_task("task-rst", "run-sealrst", workspace_id)
        begin = self.store.begin_execution_exclusive("task-rst")
        self.assertIsNotNone(begin.task)
        self.store.seal_workspace("run-sealrst", "sk-r")

        # Restart with the holder abandoned mid-run: the task is
        # terminalized honestly, the sealed record keeps its status and
        # frees the slot (no dirty verdict on a sealed workspace).
        reloaded = self.reload_store()
        reloaded.recover_after_restart()
        self.assertEqual(
            reloaded.get("task-rst").status, TaskStatus.FAILED
        )
        workspace = reloaded.get_workspace(workspace_id)
        self.assertEqual(workspace.status, WorkspaceStatus.SEALED)
        self.assertIsNone(workspace.holder_task_id)
        # And the recovered state validates on yet another reload.
        self.reload_store()


class QueryStoreTests(_ExpiryStoreTestBase):
    def test_not_found_creates_nothing(self) -> None:
        decision = self.store.query_workspace("run-unknown")
        self.assertEqual(
            decision.outcome, WorkspaceQueryOutcome.WORKSPACE_QUERY_NOT_FOUND
        )
        self.assertIsNone(decision.workspace)
        self.assertIsNone(decision.run_seal)
        # The query is read-only: no workspace, no seal, not even a state
        # file appeared.
        self.assertEqual(len(self.store.workspaces), 0)
        self.assertEqual(len(self.store.run_seals), 0)
        self.assertFalse(self.state_path.exists())

    def test_found_diskless_seal_reports_the_seal_moment(self) -> None:
        self.store.seal_workspace("run-qseal", "sk-q")
        decision = self.store.query_workspace("run-qseal")
        self.assertEqual(
            decision.outcome, WorkspaceQueryOutcome.WORKSPACE_QUERY_FOUND
        )
        self.assertIsNone(decision.workspace)
        self.assertIsNotNone(decision.run_seal)
        self.assertEqual(
            decision.run_seal.seal_idempotency_key, "sk-q"
        )

    def test_found_states_return_the_record_as_is(self) -> None:
        workspace_id = self.acquire_active_workspace("run-qactive")
        found = self.store.query_workspace("run-qactive")
        self.assertEqual(found.outcome, WorkspaceQueryOutcome.WORKSPACE_QUERY_FOUND)
        self.assertEqual(found.workspace.workspace_id, workspace_id)
        self.assertEqual(found.workspace.status, WorkspaceStatus.ACTIVE)
        self.assertIsNotNone(found.workspace.last_active_at)

        sealed = self.store.seal_workspace("run-qactive", "sk-qa")
        self.assertTrue(sealed.sealed)
        found_sealed = self.store.query_workspace("run-qactive")
        self.assertEqual(
            found_sealed.workspace.status, WorkspaceStatus.SEALED
        )

    def test_found_deleted_vouches_the_old_identity(self) -> None:
        workspace_id = self.acquire_active_workspace("run-qdel")
        pre_delete_activity = self.store.get_workspace(
            workspace_id
        ).last_active_at
        self.delete_workspace(workspace_id)

        decision = self.store.query_workspace("run-qdel")
        self.assertEqual(
            decision.outcome,
            WorkspaceQueryOutcome.WORKSPACE_QUERY_FOUND_DELETED,
        )
        workspace = decision.workspace
        self.assertIsNotNone(workspace)
        self.assertEqual(workspace.workspace_id, workspace_id)
        self.assertEqual(workspace.generation, "1")
        self.assertEqual(workspace.run_id, "run-qdel")
        self.assertEqual(workspace.status, WorkspaceStatus.DELETED)
        # The activity moment froze at the pre-delete value; the deletion
        # audit moment is present.
        self.assertEqual(workspace.last_active_at, pre_delete_activity)
        self.assertIsNotNone(workspace.deleted_at)

    def test_query_never_interferes_with_a_later_acquire(self) -> None:
        # A NOT_FOUND answer leaves no trace: a real acquire afterwards
        # creates the workspace normally.
        self.store.query_workspace("run-qlater")
        workspace_id = self.acquire_active_workspace("run-qlater")
        self.assertTrue(workspace_id)


class CandidatesStoreTests(_ExpiryStoreTestBase):
    def _create_workspaces(self, run_prefix: str, count: int) -> list[str]:
        return [
            self.acquire_active_workspace(f"{run_prefix}-{index}")
            for index in range(count)
        ]

    def test_multi_page_walk_is_complete_and_sorted(self) -> None:
        created = self._create_workspaces("run-page", 5)
        seen: list[str] = []
        token = ""
        pages = 0
        while True:
            page, token = self.store.list_workspace_expiry_candidates(2, token)
            seen.extend(row.workspace_id for row in page)
            pages += 1
            if not token:
                break
            self.assertLessEqual(pages, 10, "pagination did not terminate")
        self.assertEqual(sorted(created), seen)
        self.assertEqual(len(seen), 5)

    def test_page_token_boundary_excludes_already_seen_rows(self) -> None:
        created = sorted(self._create_workspaces("run-bound", 3))
        first_page, token = self.store.list_workspace_expiry_candidates(2, "")
        self.assertEqual([row.workspace_id for row in first_page], created[:2])
        second_page, token = self.store.list_workspace_expiry_candidates(2, token)
        self.assertEqual([row.workspace_id for row in second_page], created[2:])
        self.assertEqual(token, "")

    def test_deleted_rows_never_appear(self) -> None:
        created = self._create_workspaces("run-delc", 3)
        self.delete_workspace(created[0], "dk-c")
        page, token = self.store.list_workspace_expiry_candidates(100, "")
        self.assertEqual(token, "")
        self.assertEqual(
            sorted(row.workspace_id for row in page), sorted(created[1:])
        )
        # The deleted audit stays per-Run queryable forever.
        decision = self.store.query_workspace("run-delc-0")
        self.assertEqual(
            decision.outcome, WorkspaceQueryOutcome.WORKSPACE_QUERY_FOUND_DELETED
        )

    def test_page_size_is_capped_server_side(self) -> None:
        self._create_workspaces("run-cap", 3)
        with patch("app.task_store.EXPIRY_CANDIDATES_PAGE_CAP", 2):
            page, token = self.store.list_workspace_expiry_candidates(100, "")
        self.assertEqual(len(page), 2)
        self.assertTrue(token)

    def test_page_size_must_be_positive(self) -> None:
        with self.assertRaises(ValueError):
            self.store.list_workspace_expiry_candidates(0, "")

    def test_rows_carry_the_contract_fields(self) -> None:
        workspace_id = self.acquire_active_workspace("run-row")
        page, _ = self.store.list_workspace_expiry_candidates(10, "")
        (row,) = [row for row in page if row.workspace_id == workspace_id]
        self.assertEqual(row.run_id, "run-row")
        self.assertEqual(row.workspace_generation, "1")
        self.assertEqual(row.status, WorkspaceStatus.ACTIVE)
        # RFC3339 UTC with the explicit Z suffix.
        self.assertTrue(row.last_active_at.endswith("Z"))
        parsed = datetime.fromisoformat(
            row.last_active_at.replace("Z", "+00:00")
        )
        self.assertIsNotNone(parsed.tzinfo)


class ConditionalDeleteTests(_ExpiryStoreTestBase):
    def test_matching_scan_value_deletes(self) -> None:
        workspace_id = self.acquire_active_workspace("run-cond")
        scanned = _format_activity_utc(
            self.store.get_workspace(workspace_id).last_active_at
        )
        begin = self.store.delete_workspace_begin(
            workspace_id, "dk-c", expected_last_active_at=scanned
        )
        self.assertEqual(begin.outcome, DELETE_STARTED)
        finish = self.store.delete_workspace_finish(workspace_id, True)
        self.assertEqual(finish.outcome, DELETE_DONE)

    def test_equivalent_spelling_of_the_same_moment_deletes(self) -> None:
        # The coordinator's Java side parses the candidate row's RFC3339
        # string and re-serializes it with Instant.toString(), which trims
        # trailing fractional zeros ("…123000Z" becomes "…123Z"); a
        # numeric offset is an equivalent spelling too. All spellings of
        # the scanned moment must delete — a literal string comparison
        # would revoke a legitimate cleanup here.
        spellings = (
            "2026-01-01T12:00:00.123000Z",
            "2026-01-01T12:00:00.123Z",
            "2026-01-01T12:00:00.123000+00:00",
            "2026-01-01T20:00:00.123+08:00",
        )
        for index, spelling in enumerate(spellings):
            workspace_id = self.acquire_active_workspace(f"run-equiv-{index}")
            workspace = self.store.workspaces[workspace_id]
            workspace.last_active_at = datetime(2026, 1, 1, 12, 0, 0, 123000)
            self.store._persist_locked()
            begin = self.store.delete_workspace_begin(
                workspace_id, "dk-e", expected_last_active_at=spelling
            )
            self.assertEqual(begin.outcome, DELETE_STARTED, spelling)
            finish = self.store.delete_workspace_finish(workspace_id, True)
            self.assertEqual(finish.outcome, DELETE_DONE, spelling)

    def test_invalid_expected_format_is_an_input_error(self) -> None:
        workspace_id = self.acquire_active_workspace("run-badfmt")
        with self.assertRaises(ValueError):
            self.store.delete_workspace_begin(
                workspace_id, "dk-f", expected_last_active_at="not-a-time"
            )
        # Nothing was touched: an unparseable scan value is neither a
        # match nor a mismatch.
        self.assertEqual(
            self.store.get_workspace(workspace_id).status,
            WorkspaceStatus.ACTIVE,
        )

    def test_new_activity_since_the_scan_revokes(self) -> None:
        workspace_id = self.acquire_active_workspace("run-rev")
        scanned = _format_activity_utc(
            self.store.get_workspace(workspace_id).last_active_at
        )
        # New activity after the scan: a task admission refreshes the
        # activity moment.
        self.add_ws_task("task-rev", "run-rev", workspace_id)
        begin = self.store.delete_workspace_begin(
            workspace_id, "dk-r", expected_last_active_at=scanned
        )
        self.assertEqual(begin.outcome, DELETE_REVOKED)
        # Nothing was deleted: the record is still ACTIVE, the task
        # untouched.
        workspace = self.store.get_workspace(workspace_id)
        self.assertEqual(workspace.status, WorkspaceStatus.ACTIVE)
        self.assertEqual(
            self.store.get("task-rev").status, TaskStatus.QUEUED
        )
        # A retry carrying the CURRENT value proceeds.
        current = _format_activity_utc(workspace.last_active_at)
        retry = self.store.delete_workspace_begin(
            workspace_id, "dk-r", expected_last_active_at=current
        )
        self.assertEqual(retry.outcome, DELETE_STARTED)

    def test_check_is_skipped_once_deleting(self) -> None:
        workspace_id = self.acquire_active_workspace("run-skip")
        first = self.store.delete_workspace_begin(workspace_id, "dk-s")
        self.assertEqual(first.outcome, DELETE_STARTED)
        # A conditional retry carrying a stale scan value continues the
        # delete — the durable DELETING intent already exists, so the
        # activity comparison no longer applies.
        retry = self.store.delete_workspace_begin(
            workspace_id, "dk-s", expected_last_active_at="1970-01-01T00:00:00Z"
        )
        self.assertEqual(retry.outcome, DELETE_STARTED)

    def test_busy_holder_stays_retryable_under_a_matching_value(self) -> None:
        workspace_id = self.acquire_active_workspace("run-cbusy")
        self.add_ws_task("task-cbusy", "run-cbusy", workspace_id)
        begin_exec = self.store.begin_execution_exclusive("task-cbusy")
        self.assertIsNotNone(begin_exec.task)
        scanned = _format_activity_utc(
            self.store.get_workspace(workspace_id).last_active_at
        )
        begin = self.store.delete_workspace_begin(
            workspace_id, "dk-b", expected_last_active_at=scanned
        )
        self.assertEqual(begin.outcome, DELETE_BUSY)

    def test_deleted_repeat_ignores_the_condition(self) -> None:
        workspace_id = self.acquire_active_workspace("run-cdone")
        self.delete_workspace(workspace_id)
        repeat = self.store.delete_workspace_begin(
            workspace_id, "dk-d", expected_last_active_at="1970-01-01T00:00:00Z"
        )
        self.assertEqual(repeat.outcome, DELETE_DONE)

    def test_manual_unconditional_path_unchanged(self) -> None:
        workspace_id = self.acquire_active_workspace("run-man")
        begin = self.store.delete_workspace_begin(workspace_id, "dk-m")
        self.assertEqual(begin.outcome, DELETE_STARTED)
        finish = self.store.delete_workspace_finish(workspace_id, False)
        self.assertEqual(finish.outcome, "retryable_failure")
        retry = self.store.delete_workspace_begin(workspace_id, "dk-m")
        self.assertEqual(retry.outcome, DELETE_STARTED)


class ActivityTimestampTests(_ExpiryStoreTestBase):
    def _plant_stale(self, workspace_id: str) -> None:
        workspace = self.store.workspaces[workspace_id]
        workspace.last_active_at = STALE
        self.store._persist_locked()

    def test_acquire_and_create_refresh(self) -> None:
        workspace_id = self.acquire_active_workspace("run-act")
        created_value = self.store.get_workspace(workspace_id).last_active_at
        self.assertIsNotNone(created_value)
        self.assertGreater(created_value, STALE)

        self._plant_stale(workspace_id)
        self.store.acquire_workspace(AcquireWorkspaceRequest(run_id="run-act"))
        self.assertGreater(
            self.store.get_workspace(workspace_id).last_active_at, STALE
        )

    def test_admission_holder_begin_and_completion_refresh(self) -> None:
        workspace_id = self.acquire_active_workspace("run-act2")
        self._plant_stale(workspace_id)
        self.add_ws_task("task-act", "run-act2", workspace_id)
        self.assertGreater(
            self.store.get_workspace(workspace_id).last_active_at, STALE
        )

        self._plant_stale(workspace_id)
        begin = self.store.begin_execution_exclusive("task-act")
        self.assertIsNotNone(begin.task)
        self.assertGreater(
            self.store.get_workspace(workspace_id).last_active_at, STALE
        )

        self._plant_stale(workspace_id)
        complete(self.store, "task-act", ok_result())
        self.assertGreater(
            self.store.get_workspace(workspace_id).last_active_at, STALE
        )

    def test_restart_recovery_restarts_the_activity_clock(self) -> None:
        # A RUNNING holder terminalized by a service restart reaches its
        # terminal moment at the recovery instant, so the workspace's
        # activity clock must restart from that same moment — otherwise a
        # task that ran longer than the retention window would look idle
        # since its start and the disk would enter cleanup immediately.
        workspace_id = self.acquire_active_workspace("run-recover")
        self.add_ws_task("task-recover", "run-recover", workspace_id)
        begin = self.store.begin_execution_exclusive("task-recover")
        self.assertIsNotNone(begin.task)
        self._plant_stale(workspace_id)

        reloaded = self.reload_store()
        reloaded.recover_after_restart()

        task = reloaded.get("task-recover")
        workspace = reloaded.get_workspace(workspace_id)
        self.assertEqual(task.status, TaskStatus.FAILED)
        self.assertEqual(workspace.status, WorkspaceStatus.DIRTY)
        self.assertIsNone(workspace.holder_task_id)
        self.assertIsNotNone(task.finished_at)
        self.assertEqual(workspace.last_active_at, task.finished_at)
        self.assertGreater(workspace.last_active_at, STALE)

    def test_restart_recovery_keeps_a_sealed_workspace_sealed(self) -> None:
        # Sealing is the persisted cleanup intent: a restart frees the
        # abandoned holder slot but neither unseals nor dirties the
        # record, and the seal's activity moment is left untouched.
        workspace_id = self.acquire_active_workspace("run-sealed")
        self.add_ws_task("task-sealed", "run-sealed", workspace_id)
        begin = self.store.begin_execution_exclusive("task-sealed")
        self.assertIsNotNone(begin.task)
        sealed = self.store.seal_workspace("run-sealed", "sk-recover")
        self.assertTrue(sealed.sealed)
        self._plant_stale(workspace_id)

        reloaded = self.reload_store()
        reloaded.recover_after_restart()

        task = reloaded.get("task-sealed")
        workspace = reloaded.get_workspace(workspace_id)
        self.assertEqual(task.status, TaskStatus.FAILED)
        self.assertEqual(workspace.status, WorkspaceStatus.SEALED)
        self.assertIsNone(workspace.holder_task_id)
        self.assertEqual(workspace.last_active_at, STALE)

    def test_deleting_and_deleted_freeze_the_moment(self) -> None:
        workspace_id = self.acquire_active_workspace("run-frz")
        frozen = self.store.get_workspace(workspace_id).last_active_at
        self.store.delete_workspace_begin(workspace_id, "dk-f")
        self.assertEqual(
            self.store.get_workspace(workspace_id).last_active_at, frozen
        )
        self.store.delete_workspace_finish(workspace_id, True)
        self.assertEqual(
            self.store.get_workspace(workspace_id).last_active_at, frozen
        )

    def test_v4_upgrade_anchor_is_persisted_in_the_upgrade_write(self) -> None:
        # A v4 document whose workspace predates the activity field.
        document = {
            "schema_version": "sandbox_task_store_v4",
            "store_instance_id": "inst-v4",
            "tasks": {},
            "operations": {},
            "cancel_requests": {},
            "workspaces": {
                "ws-old": {
                    "workspace_id": "ws-old",
                    "run_id": "run-old",
                    "generation": "1",
                    "status": "active",
                    "holder_task_id": None,
                    "created_at": "2026-01-01T00:00:00",
                    "status_changed_at": None,
                    "dirtied_by_task_id": None,
                    "delete_idempotency_key": None,
                    "deleted_at": None,
                }
            },
        }
        self.state_path.write_text(json.dumps(document), encoding="utf-8")
        upgraded = DurableTaskStore(self.state_path)
        anchor = upgraded.get_workspace("ws-old").last_active_at
        self.assertIsNotNone(anchor)

        # The upgrade write happened at load time: the file on disk is
        # already v5 with the anchor present.
        on_disk = json.loads(self.state_path.read_text(encoding="utf-8"))
        self.assertEqual(
            on_disk["schema_version"], "sandbox_task_store_v5"
        )
        self.assertIn("last_active_at", on_disk["workspaces"]["ws-old"])

        # A restart with NO intervening write observes the SAME anchor
        # (a lazy in-memory anchor would reset it here).
        reloaded = self.reload_store()
        self.assertEqual(
            reloaded.get_workspace("ws-old").last_active_at, anchor
        )

    def test_v4_document_rejects_v5_only_sections(self) -> None:
        document = {
            "schema_version": "sandbox_task_store_v4",
            "store_instance_id": "inst-v4",
            "tasks": {},
            "operations": {},
            "cancel_requests": {},
            "workspaces": {},
            "run_seals": {
                "run-x": {
                    "sealed_at": "2026-01-01T00:00:00",
                    "seal_idempotency_key": "sk-x",
                }
            },
        }
        self.state_path.write_text(json.dumps(document), encoding="utf-8")
        with self.assertRaises(RuntimeError):
            DurableTaskStore(self.state_path)


class PurgeTests(_ExpiryStoreTestBase):
    def test_shape_a_binding_matches_operations_and_survives_reload(self) -> None:
        workspace_id = self.acquire_active_workspace("run-purge-a")
        task = self.add_ws_task(
            "task-pa", "run-purge-a", workspace_id, code="print('secret-a')"
        )
        begin = self.store.begin_execution_exclusive("task-pa")
        self.assertIsNotNone(begin.task)
        complete(self.store, "task-pa", ok_result())
        self.delete_workspace(workspace_id)

        purged = self.store.get("task-pa")
        self.assertIsNone(purged.request)
        binding = purged.purged_request
        self.assertIsNotNone(binding)
        self.assertEqual(binding.operation_id, "run-purge-a:call-1:1")
        self.assertEqual(binding.request_fingerprint, FINGERPRINT_A)
        self.assertEqual(binding.payload_digest, purged.payload_digest)
        self.assertEqual(binding.run_id, "run-purge-a")
        self.assertEqual(binding.workspace_id, workspace_id)
        self.assertEqual(binding.workspace_generation, "1")
        # The operations entry is untouched, so the authoritative lookup
        # still resolves the old operationId.
        looked_up = self.store.get_by_operation_id("run-purge-a:call-1:1")
        self.assertIsNotNone(looked_up)
        self.assertEqual(looked_up.task_id, "task-pa")

        # The user code left the durable document entirely.
        raw = self.state_path.read_text(encoding="utf-8")
        self.assertNotIn("secret-a", raw)

        # Both the live store and a reloaded one validate the purged
        # shape under the DELETED workspace.
        reloaded = self.reload_store()
        self.assertIsNone(reloaded.get("task-pa").request)
        self.assertIsNotNone(reloaded.get("task-pa").purged_request)

        # A late old-identity create is refused by the workspace DELETED
        # audit — the gate answers before any task-level replay.
        late = Task(
            task_id="task-late",
            status=TaskStatus.QUEUED,
            request=ws_request("run-purge-a", workspace_id),
        )
        decision = reloaded.create_with_admission(late)
        self.assertIsNone(decision.task)
        self.assertEqual(
            decision.workspace_result, WorkspaceResult.WORKSPACE_DELETED
        )

    def test_shape_b_binding_fabricates_nothing(self) -> None:
        workspace_id = self.acquire_active_workspace("run-purge-b")
        self.add_ws_task(
            "task-pb", "run-purge-b", workspace_id,
            with_operation=False, code="print('secret-b')",
        )
        # Sanity: no operations index entry exists for shape B tasks.
        self.assertEqual(len(self.store.operations), 0)
        begin = self.store.begin_execution_exclusive("task-pb")
        self.assertIsNotNone(begin.task)
        complete(self.store, "task-pb", ok_result())
        self.delete_workspace(workspace_id)

        purged = self.store.get("task-pb")
        self.assertIsNone(purged.request)
        binding = purged.purged_request
        self.assertIsNotNone(binding)
        # Only the values the record actually had: no fingerprint, no
        # operationId, and no operations entry appeared.
        self.assertIsNone(binding.operation_id)
        self.assertIsNone(binding.request_fingerprint)
        self.assertEqual(binding.payload_digest, purged.payload_digest)
        self.assertEqual(binding.run_id, "run-purge-b")
        self.assertEqual(binding.workspace_id, workspace_id)
        self.assertEqual(len(self.store.operations), 0)

        self.assertNotIn(
            "secret-b", self.state_path.read_text(encoding="utf-8")
        )
        reloaded = self.reload_store()
        self.assertIsNotNone(reloaded.get("task-pb").purged_request)

    def test_queued_siblings_terminalized_at_begin_are_purged_too(self) -> None:
        workspace_id = self.acquire_active_workspace("run-purge-q")
        self.add_ws_task(
            "task-pq", "run-purge-q", workspace_id, code="print('secret-q')"
        )
        self.delete_workspace(workspace_id)
        purged = self.store.get("task-pq")
        # The QUEUED sibling was terminalized FAILED when the workspace
        # entered deletion, then purged together with the audit row.
        self.assertEqual(purged.status, TaskStatus.FAILED)
        self.assertIsNone(purged.request)
        self.assertIsNotNone(purged.purged_request)
        self.assertNotIn(
            "secret-q", self.state_path.read_text(encoding="utf-8")
        )
        self.reload_store()

    def test_failed_disk_removal_does_not_purge(self) -> None:
        workspace_id = self.acquire_active_workspace("run-purge-r")
        self.add_ws_task(
            "task-pr", "run-purge-r", workspace_id, code="print('keep-me')"
        )
        begin = self.store.delete_workspace_begin(workspace_id, "dk-pr")
        self.assertEqual(begin.outcome, DELETE_STARTED)
        finish = self.store.delete_workspace_finish(workspace_id, False)
        self.assertEqual(finish.outcome, "retryable_failure")
        # The delete is not done: the request body stays (the task is
        # still answerable in full, and the retry continues).
        self.assertIsNotNone(self.store.get("task-pr").request)
        self.assertIsNone(self.store.get("task-pr").purged_request)
        self.assertIn(
            "keep-me", self.state_path.read_text(encoding="utf-8")
        )


# ---------------------------------------------------------------------------
# Transport shape (app.main): the new endpoints and the delete outcome
# classification ride the normal 200 body.
# ---------------------------------------------------------------------------

os.environ.setdefault("AF_SANDBOX_IMAGE", "sha256:" + "a" * 64)
os.environ.setdefault("AF_SANDBOX_IMAGE_ALLOW_DEV_TAG", "true")

llm_sandbox = types.ModuleType("llm_sandbox")
llm_sandbox.SandboxSession = object
llm_sandbox_exceptions = types.ModuleType("llm_sandbox.exceptions")
llm_sandbox_exceptions.SandboxTimeoutError = TimeoutError
sys.modules.setdefault("llm_sandbox", llm_sandbox)
sys.modules.setdefault("llm_sandbox.exceptions", llm_sandbox_exceptions)

from fastapi.testclient import TestClient  # noqa: E402

from app import main  # noqa: E402


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

    def _acquire(self, run_id: str) -> str:
        response = self.client.post("/workspaces/acquire", json={"run_id": run_id})
        assert response.status_code == 200, response.text
        return response.json()["workspace"]["workspace_id"]

    def test_seal_endpoint_diskless_and_with_disk(self) -> None:
        diskless = self.client.post(
            "/workspaces/seal",
            json={"run_id": "run-tseal", "idempotency_key": "sk-t"},
        )
        self.assertEqual(diskless.status_code, 200)
        self.assertTrue(diskless.json()["sealed"])
        self.assertIsNone(diskless.json()["workspace"])

        workspace_id = self._acquire("run-tseal2")
        with_disk = self.client.post(
            "/workspaces/seal",
            json={"run_id": "run-tseal2", "idempotency_key": "sk-t2"},
        )
        self.assertEqual(with_disk.status_code, 200)
        body = with_disk.json()
        self.assertTrue(body["sealed"])
        self.assertEqual(body["workspace"]["workspace_id"], workspace_id)
        self.assertEqual(body["workspace"]["status"], "sealed")

        blank = self.client.post(
            "/workspaces/seal", json={"run_id": " ", "idempotency_key": "sk"}
        )
        self.assertEqual(blank.status_code, 400)

    def test_query_endpoint_three_outcomes(self) -> None:
        missing = self.client.post(
            "/workspaces/query", json={"run_id": "run-tq-none"}
        )
        self.assertEqual(missing.status_code, 200)
        self.assertEqual(missing.json()["outcome"], "WORKSPACE_QUERY_NOT_FOUND")
        self.assertIsNone(missing.json()["workspace"])

        self.client.post(
            "/workspaces/seal",
            json={"run_id": "run-tq-seal", "idempotency_key": "sk-q"},
        )
        diskless = self.client.post(
            "/workspaces/query", json={"run_id": "run-tq-seal"}
        )
        self.assertEqual(diskless.json()["outcome"], "WORKSPACE_QUERY_FOUND")
        self.assertIsNone(diskless.json()["workspace"])
        self.assertTrue(diskless.json()["last_active_at"].endswith("Z"))

        workspace_id = self._acquire("run-tq-del")
        with patch(
            "app.main.verify_workspace_containers_stopped", return_value=True
        ):
            self.client.post(
                "/workspaces/delete",
                json={"workspace_id": workspace_id, "idempotency_key": "dk-q"},
            )
        deleted = self.client.post(
            "/workspaces/query", json={"run_id": "run-tq-del"}
        )
        body = deleted.json()
        self.assertEqual(body["outcome"], "WORKSPACE_QUERY_FOUND_DELETED")
        self.assertEqual(body["workspace"]["workspace_id"], workspace_id)
        self.assertEqual(body["workspace"]["status"], "deleted")
        self.assertEqual(body["workspace"]["owned_by_run_id"], "run-tq-del")
        self.assertTrue(body["last_active_at"].endswith("Z"))
        self.assertTrue(body["deleted_at"].endswith("Z"))

    def test_expiry_candidates_endpoint_walks_pages(self) -> None:
        created = sorted(self._acquire(f"run-tc-{i}") for i in range(3))
        first = self.client.post(
            "/workspaces/expiry-candidates",
            json={"page_size": 2, "page_token": ""},
        )
        self.assertEqual(first.status_code, 200)
        body = first.json()
        self.assertEqual(len(body["candidates"]), 2)
        token = body["next_page_token"]
        self.assertTrue(token)
        second = self.client.post(
            "/workspaces/expiry-candidates",
            json={"page_size": 2, "page_token": token},
        )
        rows = body["candidates"] + second.json()["candidates"]
        self.assertEqual(
            sorted(row["workspace_id"] for row in rows), created
        )
        self.assertEqual(second.json()["next_page_token"], "")
        row = rows[0]
        self.assertEqual(
            set(row),
            {
                "run_id",
                "workspace_id",
                "workspace_generation",
                "status",
                "last_active_at",
            },
        )

        invalid = self.client.post(
            "/workspaces/expiry-candidates",
            json={"page_size": 0, "page_token": ""},
        )
        self.assertEqual(invalid.status_code, 400)

    def test_delete_endpoint_rejects_invalid_expected_format(self) -> None:
        workspace_id = self._acquire("run-td-bad")
        response = self.client.post(
            "/workspaces/delete",
            json={
                "workspace_id": workspace_id,
                "idempotency_key": "dk-bad",
                "expected_last_active_at": "not-a-time",
            },
        )
        self.assertEqual(response.status_code, 400)
        self.assertEqual(
            self.store.get_workspace(workspace_id).status,
            WorkspaceStatus.ACTIVE,
        )

    def test_delete_endpoint_classifies_the_three_outcomes(self) -> None:
        # DELETED: the unconditional manual path.
        workspace_id = self._acquire("run-td-1")
        with patch(
            "app.main.verify_workspace_containers_stopped", return_value=True
        ):
            deleted = self.client.post(
                "/workspaces/delete",
                json={"workspace_id": workspace_id, "idempotency_key": "dk-1"},
            )
        body = deleted.json()
        self.assertTrue(body["deleted"])
        self.assertEqual(body["outcome"], "WORKSPACE_DELETE_DELETED")

        # TEMPORARILY_UNAVAILABLE: the live-container pre-check refuses.
        workspace_id = self._acquire("run-td-2")
        with patch(
            "app.main.verify_workspace_containers_stopped", return_value=False
        ):
            refused = self.client.post(
                "/workspaces/delete",
                json={"workspace_id": workspace_id, "idempotency_key": "dk-2"},
            )
        body = refused.json()
        self.assertFalse(body["deleted"])
        self.assertTrue(body["retryable_failure"])
        self.assertEqual(
            body["outcome"], "WORKSPACE_DELETE_TEMPORARILY_UNAVAILABLE"
        )

        # REVOKED_NEW_ACTIVITY: the scan-time value no longer matches.
        workspace_id = self._acquire("run-td-3")
        revoked = self.client.post(
            "/workspaces/delete",
            json={
                "workspace_id": workspace_id,
                "idempotency_key": "dk-3",
                "expected_last_active_at": "1970-01-01T00:00:00Z",
            },
        )
        body = revoked.json()
        self.assertFalse(body["deleted"])
        self.assertFalse(body["retryable_failure"])
        self.assertEqual(
            body["outcome"], "WORKSPACE_DELETE_REVOKED_NEW_ACTIVITY"
        )
        # Nothing was deleted.
        self.assertEqual(
            self.store.get_workspace(workspace_id).status,
            WorkspaceStatus.ACTIVE,
        )


if __name__ == "__main__":
    unittest.main()
