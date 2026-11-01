"""普通脚本失败后同盘修正，以及执行状态不明时保盘拒绝的回归。

真实包装器作为本机子进程运行；Docker 身份查询和会话由替身承担，
不连接 Docker。容器停止/OOM 的真实远端行为仍需另行验收。
"""
from __future__ import annotations

import asyncio
import json
import os
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

from tests.test_bounded_wrapper_wiring import (
    FakeContainerSession, _test_config, _LIMITS, _make_dataset,
)
from tests.test_workspace_lifecycle import (
    _WorkspaceStoreTestBase, ws_request,
)
from app import sandbox_runner
from app.models import (
    AcquireWorkspaceRequest, EffectiveOutputLimits, ExecuteResult,
    SandboxResourceUsage, Task, TaskStatus, WorkspaceStatus,
    CancellationEvidence, WorkspaceResult,
)
from app.task_store import CompletionCandidate, DurableTaskStore


LABELS = {
    "com.alphafrog.sandbox.store-instance": "store",
    "com.alphafrog.sandbox.task-id": "task",
    "com.alphafrog.sandbox.deployment-id": "deployment",
    "com.alphafrog.sandbox.workspace-id": "workspace",
    "com.alphafrog.sandbox.workspace-generation": "1",
}
CONTAINER_ID = "a" * 64


def container_attrs(labels=LABELS):
    return {
        "Id": CONTAINER_ID,
        "Config": {"Labels": {**labels, "com.alphafrog.role": "python-sandbox-worker"}},
        "State": {"Running": True, "Status": "running", "Pid": 123,
                  "Restarting": False, "OOMKilled": False},
    }


class ContainerProofTests(unittest.TestCase):
    def check(self, attrs, *, after_close=False, expected=LABELS, error=None):
        container = mock.Mock(attrs=attrs)
        client = mock.Mock()
        client.containers.get.side_effect = error
        client.containers.get.return_value = container
        with mock.patch.object(sandbox_runner, "build_docker_client", return_value=client):
            result = sandbox_runner.verify_completed_task_container(
                CONTAINER_ID, expected, after_close=after_close,
            )
        client.containers.get.assert_called_once_with(CONTAINER_ID)
        client.containers.list.assert_not_called()
        container.stop.assert_not_called()
        container.remove.assert_not_called()
        client.close.assert_called_once()
        return result

class StoreFailureTests(_WorkspaceStoreTestBase):
    def setup_holder(self):
        workspace_id = self.acquire_active_workspace("run-correct")
        self.add_ws_task("failed", "run-correct", workspace_id, call="1")
        self.add_ws_task("queued", "run-correct", workspace_id, call="2")
        self.store.begin_execution_exclusive("failed")
        return workspace_id

    def candidate(self, *, safe=True, exit_code=1, reason="NON_ZERO_EXIT",
                  oom=False, timeout=False, evidence=CancellationEvidence.NONE):
        return CompletionCandidate(
            status=TaskStatus.FAILED,
            result=ExecuteResult(
                exit_code=exit_code, stdout="before failure", stderr="KeyError",
                dataset_dir="/input", retryable=False,
                resource_usage=SandboxResourceUsage(
                    resource_class="STANDARD", exit_reason=reason,
                    oom_killed=oom, timed_out=timeout,
                ),
            ),
            evidence=evidence,
            workspace_safe_to_continue=safe,
        )

class WorkspaceFailureChainTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        import app.main as main
        self.main = main
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name).resolve()
        _make_dataset(self.root)
        self.workspace_dir = self.root / "persistent"
        self.config = _test_config(self.root, skip_environment_setup=False)
        self.store = DurableTaskStore(self.root / "state.json")
        self.workspace_id = self.store.acquire_workspace(
            AcquireWorkspaceRequest(run_id="run-correct")
        ).workspace.workspace_id
        self.attrs = None
        self.removed = False
        self.close_mode = "removed"
        self.extra_oom = False
        self.protocol_mode = None
        self.cleanup_ok = True
        owner = self

        class Session(FakeContainerSession):
            container_id = CONTAINER_ID

            def execute_command(self, command, workdir=None):
                output = super().execute_command(command, workdir)
                if sandbox_runner.WRAPPER_BOOTSTRAP_NAME in command:
                    if owner.protocol_mode == "lost":
                        return SimpleNamespace(exit_code=0, stdout="", stderr="")
                    if owner.protocol_mode == "wrapper_error":
                        return SimpleNamespace(exit_code=1, stdout="", stderr="internal error")
                    if owner.protocol_mode in {"invalid", "legacy", "sweep_incomplete"}:
                        doc = json.loads(output.stdout)
                        if owner.protocol_mode == "invalid":
                            doc["completion"]["timedOut"] = "false"
                        elif owner.protocol_mode == "legacy":
                            doc.pop("completion")
                        else:
                            doc["completion"]["processTreeCleaned"] = False
                        return SimpleNamespace(exit_code=0, stdout=json.dumps(doc), stderr="")
                return output

            def close(self):
                if owner.close_mode == "removed":
                    owner.removed = True
                elif owner.close_mode == "stopped":
                    owner.attrs["State"].update(Running=False, Status="exited", Pid=0)
                elif owner.close_mode == "unknown":
                    owner.removed = None
                elif owner.close_mode == "raise":
                    raise RuntimeError("close failed")
                # 模拟 SDK 吞掉 stop 失败，close 正常返回但容器仍在运行。

        def create_session(*args, **kwargs):
            self.attrs = container_attrs(kwargs["container_labels"])
            self.attrs["State"]["OOMKilled"] = self.extra_oom
            self.removed = False
            return Session(self.root, skip_environment_setup=False)

        def get_container(container_id):
            from docker.errors import NotFound
            self.assertEqual(container_id, CONTAINER_ID)
            if self.removed is None:
                raise TimeoutError("daemon unreachable")
            if self.removed:
                raise NotFound("removed")
            return SimpleNamespace(attrs=self.attrs, reload=lambda: None)

        client = mock.Mock()
        client.containers.get.side_effect = get_container
        self.patches = [
            mock.patch.multiple(main, task_store=self.store, tasks=self.store.tasks,
                config=self.config, pool=None,
                dynamic_config=main.DynamicSandboxConfig(self.config)),
            mock.patch.object(main, "_workspace_directory", return_value=self.workspace_dir),
            mock.patch.object(sandbox_runner, "CONTAINER_PERSISTENT_WORKSPACE_MOUNT", str(self.workspace_dir)),
            mock.patch.object(sandbox_runner, "create_sandbox_session", side_effect=create_session),
            mock.patch.object(sandbox_runner, "initialize_runtime_environment", return_value=None),
            mock.patch.object(sandbox_runner, "build_docker_client", return_value=client),
            mock.patch.object(sandbox_runner, "_container_oom_killed", return_value=False),
            mock.patch.object(sandbox_runner, "_cleanup_task_control_dir", side_effect=lambda *a: self.cleanup_ok),
            mock.patch.dict(os.environ, {"AF_TASK_CONTROL_ROOT": str(self.root / "control")}),
            # 采样器的 Docker 查询也禁止落到真实本机 daemon。
            mock.patch("docker.from_env", side_effect=RuntimeError("host Docker forbidden in test")),
        ]
        for patch in self.patches:
            patch.start()

    async def asyncTearDown(self):
        for patch in reversed(self.patches):
            patch.stop()
        self.temp.cleanup()

    async def execute(self, task_id, code, timeout=None):
        request = ws_request("run-correct", self.workspace_id, call=task_id, code=code)
        request.dataset_id = "ds1"
        request.timeout_seconds = timeout
        task = Task(task_id=task_id, status=TaskStatus.QUEUED, request=request)
        task.effective_output_limits = EffectiveOutputLimits(**_LIMITS)
        self.store.create_with_admission(task)
        await self.main.process_task(task, worker_id=1)
        return task

if __name__ == "__main__":
    unittest.main()
