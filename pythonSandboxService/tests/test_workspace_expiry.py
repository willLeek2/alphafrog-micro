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
    SandboxResourceUsage,
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
        exit_code=0, stdout="ok", stderr="", dataset_dir="/tmp/done",
        resource_usage=SandboxResourceUsage(
            resource_class="STANDARD", exit_reason="SUCCEEDED",
            oom_killed=False, timed_out=False,
        )
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
            workspace_safe_to_continue=result.exit_code == 0,
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
    pass
class QueryStoreTests(_ExpiryStoreTestBase):
    pass
class CandidatesStoreTests(_ExpiryStoreTestBase):
    def _create_workspaces(self, run_prefix: str, count: int) -> list[str]:
        return [
            self.acquire_active_workspace(f"{run_prefix}-{index}")
            for index in range(count)
        ]

class ConditionalDeleteTests(_ExpiryStoreTestBase):
    pass
class ActivityTimestampTests(_ExpiryStoreTestBase):
    def _plant_stale(self, workspace_id: str) -> None:
        workspace = self.store.workspaces[workspace_id]
        workspace.last_active_at = STALE
        self.store._persist_locked()

class PurgeTests(_ExpiryStoreTestBase):
    pass
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

if __name__ == "__main__":
    unittest.main()
