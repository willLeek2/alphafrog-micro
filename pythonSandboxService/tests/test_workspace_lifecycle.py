"""Persistent-workspace lifecycle tests (store, config and transport shape).

These tests pin the persistent-workspace single-writer contract on the
synchronous store level (plus the config mutex and the HTTP transport
shape). Everything here runs WITHOUT Docker; the container-level
behaviors (uid probe, shared directory, restart sweep) live in
test_workspace_docker.py behind AF_RUN_DOCKER_TESTS.

Covered groups:

* positive acceptance: legacy path unchanged, workspace path
  acquire → create → begin (holder slot) → complete → release;
* 同盘排队和标脏规则：只有带可信停止证明的成功或普通失败释放工作区；
  其他持有者终态标脏工作区，并明确失败仍在排队的同盘任务；
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
    SandboxResourceUsage,
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
    pass
class DigestProjectionTests(_WorkspaceStoreTestBase):
    pass
class BusyAndDirtyTests(_WorkspaceStoreTestBase):
    pass
class AdmissionQuotaTests(_WorkspaceStoreTestBase):
    pass
class OwnershipGateTests(_WorkspaceStoreTestBase):
    pass
class CrashWindowTests(_WorkspaceStoreTestBase):
    pass
class DeleteRaceTests(_WorkspaceStoreTestBase):
    pass
class SwitchRoutingTests(_WorkspaceStoreTestBase):
    pass
class ConfigMutexTests(unittest.TestCase):
    pass
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

class HeaderOnlyCsvNormalizationTests(_WorkspaceStoreTestBase):
    pass
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

class BlankIdentityTests(_WorkspaceStoreTestBase):
    pass
