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

    def test_running_verified_before_close_but_not_after(self):
        self.assertTrue(self.check(container_attrs()))
        self.assertFalse(self.check(container_attrs(), after_close=True))

    def test_stopped_exact_container_is_verified(self):
        attrs = container_attrs()
        attrs["State"].update(Running=False, Status="exited", Pid=0)
        self.assertTrue(self.check(attrs, after_close=True))

    def test_explicit_not_found_only_counts_after_close(self):
        from docker.errors import NotFound
        self.assertFalse(self.check({}, error=NotFound("missing")))
        self.assertTrue(self.check({}, after_close=True, error=NotFound("missing")))

    def test_unknown_daemon_errors_are_not_absence(self):
        for error in (RuntimeError("unreachable"), TimeoutError("inspect timeout")):
            with self.subTest(error=type(error).__name__):
                self.assertFalse(self.check({}, after_close=True, error=error))

    def test_non_workspace_close_error_keeps_existing_exception_behavior(self):
        with tempfile.TemporaryDirectory() as directory:
            config = _test_config(Path(directory), skip_environment_setup=False)
            session = SimpleNamespace(container_id=CONTAINER_ID,
                                      close=mock.Mock(side_effect=RuntimeError("close failed")))
            with mock.patch.object(sandbox_runner, "create_sandbox_session", return_value=session), \
                 mock.patch.object(sandbox_runner, "initialize_runtime_environment", return_value=None), \
                 mock.patch.object(sandbox_runner, "run_in_open_session", return_value={"exit_code": 0}):
                with self.assertRaisesRegex(RuntimeError, "close failed"):
                    sandbox_runner.run_in_sandbox(config, "plain", "ds1", None,
                        "print('ok')", None, None, None)

    def test_every_identity_label_is_required_in_both_directions(self):
        for key in LABELS:
            with self.subTest(key=key):
                attrs = container_attrs()
                del attrs["Config"]["Labels"][key]
                self.assertFalse(self.check(attrs))
                attrs["Config"]["Labels"][key] = "other"
                self.assertFalse(self.check(attrs))
        attrs = container_attrs()
        attrs["Config"]["Labels"]["com.alphafrog.sandbox.extra"] = "foreign"
        self.assertFalse(self.check(attrs))

    def test_oom_missing_state_and_wrong_container_are_rejected(self):
        for change in (
            lambda a: a["State"].update(OOMKilled=True),
            lambda a: a["State"].pop("OOMKilled"),
            lambda a: a["State"].update(Restarting=True),
            lambda a: a.update(Id="b" * 64),
        ):
            attrs = container_attrs()
            change(attrs)
            self.assertFalse(self.check(attrs))


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

    def test_trusted_failure_stays_failed_and_queued_task_takes_same_disk(self):
        workspace_id = self.setup_holder()
        self.store.complete_execution("failed", self.candidate())
        reloaded = self.reload_store()
        self.assertEqual(reloaded.get("failed").status, TaskStatus.FAILED)
        self.assertEqual(reloaded.get("failed").result.stderr, "KeyError")
        self.assertFalse(reloaded.get("failed").retryable)
        self.assertEqual(reloaded.get_workspace(workspace_id).status, WorkspaceStatus.ACTIVE)
        self.assertIsNone(reloaded.get_workspace(workspace_id).holder_task_id)
        self.assertEqual(reloaded.get("queued").status, TaskStatus.QUEUED)
        self.assertIsNotNone(reloaded.begin_execution_exclusive("queued").task)
        self.assertEqual(reloaded.get_workspace(workspace_id).holder_task_id, "queued")

    def test_missing_proof_and_excluded_causes_still_dirty_and_fail_queue(self):
        cases = [
            {"safe": False}, {"safe": "true"}, {"reason": "TIMEOUT"},
            {"reason": "EXECUTION_ERROR"}, {"reason": "UNKNOWN"},
            {"reason": "OOM_KILLED"}, {"oom": True}, {"timeout": True},
            {"exit_code": -9}, {"exit_code": 124}, {"exit_code": 127},
            {"exit_code": 137}, {"evidence": CancellationEvidence.MARKER_OBSERVED},
        ]
        for n, options in enumerate(cases):
            with self.subTest(options=options):
                self.store = DurableTaskStore(self.state_path.parent / f"case-{n}.json")
                workspace_id = self.setup_holder()
                self.store.complete_execution("failed", self.candidate(**options))
                self.assertEqual(self.store.get_workspace(workspace_id).status, WorkspaceStatus.DIRTY)
                self.assertEqual(self.store.get("queued").status, TaskStatus.FAILED)

    def test_success_without_internal_proof_is_dirty_and_keeps_true_success(self):
        workspace_id = self.setup_holder()
        result = ExecuteResult(exit_code=0, stdout="ok", stderr="", dataset_dir="/input",
            resource_usage=SandboxResourceUsage(resource_class="STANDARD", exit_reason="SUCCEEDED"))
        self.store.complete_execution("failed", CompletionCandidate(status=TaskStatus.SUCCEEDED, result=result))
        self.assertEqual(self.store.get("failed").status, TaskStatus.SUCCEEDED)
        self.assertEqual(self.store.get_workspace(workspace_id).status, WorkspaceStatus.DIRTY)
        self.assertEqual(self.store.get("queued").status, TaskStatus.FAILED)

    def test_success_proof_lost_before_commit_does_not_reverse_restart_dirty(self):
        workspace_id = self.setup_holder()
        candidate = CompletionCandidate(status=TaskStatus.SUCCEEDED,
            result=ExecuteResult(exit_code=0, stdout="ok", stderr="", dataset_dir="/input",
                resource_usage=SandboxResourceUsage(resource_class="STANDARD", exit_reason="SUCCEEDED")),
            workspace_safe_to_continue=True)
        reloaded = self.reload_store()
        reloaded.recover_after_restart()
        reloaded.complete_execution("failed", candidate)
        self.assertEqual(reloaded.get("failed").status, TaskStatus.FAILED)
        self.assertEqual(reloaded.get("failed").result.resource_usage.exit_reason, "UNKNOWN")
        self.assertEqual(reloaded.get_workspace(workspace_id).status, WorkspaceStatus.DIRTY)

    def test_cancel_intent_does_not_gain_release_from_trusted_failure(self):
        workspace_id = self.setup_holder()
        self.store.cancel_by_task_id("cancel-id", "failed", "user request")
        self.store.complete_execution("failed", self.candidate())
        self.assertEqual(self.store.get_workspace(workspace_id).status, WorkspaceStatus.DIRTY)

    def test_crash_after_container_proof_before_terminal_commit_still_dirty(self):
        workspace_id = self.setup_holder()
        # 即使锁外已完成执行/容器核验，未提交的证据不持久化、不用于恢复。
        prepared_candidate = self.candidate()
        self.assertTrue(prepared_candidate.workspace_safe_to_continue)
        reloaded = self.reload_store()
        reloaded.recover_after_restart()
        self.assertEqual(reloaded.get_workspace(workspace_id).status, WorkspaceStatus.DIRTY)
        self.assertEqual(reloaded.get("failed").status, TaskStatus.FAILED)
        self.assertEqual(reloaded.get("failed").result.resource_usage.exit_reason, "UNKNOWN")
        self.assertEqual(reloaded.get("queued").status, TaskStatus.FAILED)
        # 迟到的可信结果也不能翻转已恢复的终态或工作区。
        reloaded.complete_execution("failed", prepared_candidate)
        self.assertEqual(reloaded.get_workspace(workspace_id).status, WorkspaceStatus.DIRTY)


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

    async def test_real_script_failure_preserves_file_and_corrected_call_reuses_disk(self):
        first = await self.execute("first", "from pathlib import Path\nPath('partial.txt').write_text('saved')\nraise KeyError('match_conditions')")
        self.assertEqual(first.status, TaskStatus.FAILED)
        self.assertEqual(first.result.exit_code, 1)
        self.assertIn("match_conditions", first.result.stderr)
        self.assertEqual(self.store.get_workspace(self.workspace_id).status, WorkspaceStatus.ACTIVE)
        self.assertEqual((self.workspace_dir / "partial.txt").read_text(), "saved")
        second = await self.execute("corrected", "from pathlib import Path\nassert Path('partial.txt').read_text() == 'saved'\nprint('corrected')")
        self.assertEqual(second.status, TaskStatus.SUCCEEDED)
        self.assertIn("corrected", second.result.stdout)
        self.assertEqual(first.status, TaskStatus.FAILED)

    async def test_success_requires_verified_close_before_same_disk_continue(self):
        first = await self.execute("success", "from pathlib import Path\nPath('success.txt').write_text('saved')")
        self.assertEqual(first.status, TaskStatus.SUCCEEDED)
        self.assertEqual(self.store.get_workspace(self.workspace_id).status, WorkspaceStatus.ACTIVE)
        second = await self.execute("success-next", "from pathlib import Path\nassert Path('success.txt').read_text() == 'saved'")
        self.assertEqual(second.status, TaskStatus.SUCCEEDED)

    async def test_success_with_unverified_close_preserves_success_but_blocks_writers(self):
        for mode in ("running", "unknown", "raise"):
            with self.subTest(mode=mode):
                self.close_mode = mode
                run_id = "run-success-" + mode
                self.workspace_id = self.store.acquire_workspace(
                    AcquireWorkspaceRequest(run_id=run_id)
                ).workspace.workspace_id
                first = Task(task_id="success-" + mode, status=TaskStatus.QUEUED,
                    request=ws_request(run_id, self.workspace_id, call="first", code="print('real success')"),
                    effective_output_limits=EffectiveOutputLimits(**_LIMITS))
                sibling = Task(task_id="queued-" + mode, status=TaskStatus.QUEUED,
                    request=ws_request(run_id, self.workspace_id, call="queued", code="raise AssertionError('must not run')"))
                self.store.create_with_admission(first)
                self.store.create_with_admission(sibling)
                await self.main.process_task(first, worker_id=1)
                self.assertEqual(first.status, TaskStatus.SUCCEEDED)
                self.assertEqual(first.result.exit_code, 0)
                self.assertIn("real success", first.result.stdout)
                self.assertEqual(self.store.get_workspace(self.workspace_id).status, WorkspaceStatus.DIRTY)
                self.assertEqual(sibling.status, TaskStatus.FAILED)
                self.assertEqual(sibling.result.resource_usage.exit_reason, "WORKSPACE_DIRTY")
                self.assertIsNone(self.store.begin_execution_exclusive(sibling.task_id).task)
                late = Task(task_id="late-" + mode, status=TaskStatus.QUEUED,
                    request=ws_request(run_id, self.workspace_id, call="late", code="print('must not run')"))
                decision = self.store.create_with_admission(late)
                self.assertIsNone(decision.task)
                self.assertEqual(decision.workspace_result, WorkspaceResult.WORKSPACE_DIRTY)
                reloaded = DurableTaskStore(self.root / "state.json")
                reloaded.recover_after_restart()
                self.assertEqual(reloaded.get(first.task_id).status, TaskStatus.SUCCEEDED)
                self.assertEqual(reloaded.get_workspace(self.workspace_id).status, WorkspaceStatus.DIRTY)

    async def test_success_without_complete_wrapper_or_cleanup_proof_is_dirty(self):
        cases = [("protocol_mode", "legacy"), ("protocol_mode", "sweep_incomplete"),
                 ("cleanup_ok", False), ("extra_oom", True)]
        for name, value in cases:
            with self.subTest(name=name, value=value):
                previous = getattr(self, name)
                setattr(self, name, value)
                run_id = "run-success-proof-" + name + str(value)
                self.workspace_id = self.store.acquire_workspace(
                    AcquireWorkspaceRequest(run_id=run_id)
                ).workspace.workspace_id
                task = Task(task_id=run_id, status=TaskStatus.QUEUED,
                    request=ws_request(run_id, self.workspace_id, call="first", code="print('finished')"),
                    effective_output_limits=EffectiveOutputLimits(**_LIMITS))
                self.store.create_with_admission(task)
                await self.main.process_task(task, worker_id=1)
                self.assertEqual(task.status, TaskStatus.SUCCEEDED)
                self.assertEqual(self.store.get_workspace(self.workspace_id).status, WorkspaceStatus.DIRTY)
                setattr(self, name, previous)

    async def test_stopped_container_is_sufficient_without_removal(self):
        self.close_mode = "stopped"
        task = await self.execute("stopped", "raise ValueError('ordinary')")
        self.assertEqual(task.status, TaskStatus.FAILED)
        self.assertEqual(self.store.get_workspace(self.workspace_id).status, WorkspaceStatus.ACTIVE)

    async def test_incomplete_or_unsafe_execution_cannot_release(self):
        cases = [
            ("close_mode", "running"), ("close_mode", "unknown"), ("close_mode", "raise"),
            ("extra_oom", True), ("protocol_mode", "lost"),
            ("protocol_mode", "wrapper_error"), ("protocol_mode", "invalid"),
            ("protocol_mode", "legacy"), ("cleanup_ok", False),
            ("protocol_mode", "sweep_incomplete"),
        ]
        for name, value in cases:
            with self.subTest(name=name, value=value):
                previous = getattr(self, name)
                setattr(self, name, value)
                self.workspace_id = self.store.acquire_workspace(
                    AcquireWorkspaceRequest(run_id="run-correct-" + str(value))
                ).workspace.workspace_id
                # 使用当前 Run 身份，不通过替换归属绕过准入。
                request = ws_request("run-correct-" + str(value), self.workspace_id,
                                     call=name, code="raise ValueError('ordinary')")
                request.dataset_id = "ds1"
                task = Task(task_id=name + str(value), status=TaskStatus.QUEUED, request=request,
                            effective_output_limits=EffectiveOutputLimits(**_LIMITS))
                self.store.create_with_admission(task)
                await self.main.process_task(task, worker_id=1)
                self.assertEqual(task.status, TaskStatus.FAILED)
                self.assertEqual(self.store.get_workspace(self.workspace_id).status, WorkspaceStatus.DIRTY)
                setattr(self, name, previous)

    async def test_wrapper_timeout_is_explicit_and_dirties_disk(self):
        task = await self.execute("timeout", "import time\ntime.sleep(10)", timeout=0.05)
        self.assertEqual(task.status, TaskStatus.FAILED)
        self.assertTrue(task.result.resource_usage.timed_out)
        self.assertEqual(task.result.resource_usage.exit_reason, "TIMEOUT")
        self.assertEqual(self.store.get_workspace(self.workspace_id).status, WorkspaceStatus.DIRTY)

    async def test_signal_exit_is_not_ordinary_failure(self):
        task = await self.execute("signal", "import os,signal\nos.kill(os.getpid(), signal.SIGKILL)")
        self.assertEqual(task.result.exit_code, -9)
        self.assertEqual(self.store.get_workspace(self.workspace_id).status, WorkspaceStatus.DIRTY)

    async def test_observed_cancel_stays_canceled_and_dirty(self):
        running = asyncio.create_task(self.execute(
            "canceled", "from pathlib import Path\nimport time\nPath('started.txt').write_text('ready')\ntime.sleep(20)",
        ))
        try:
            for _ in range(200):
                if (self.workspace_dir / "started.txt").exists():
                    break
                await asyncio.sleep(0.05)
            self.assertTrue((self.workspace_dir / "started.txt").exists())
            self.store.cancel_by_task_id("cancel-id", "canceled", "user request")
            sandbox_runner.cancel_registry.request_stop("canceled")
            task = await asyncio.wait_for(running, 10)
            self.assertEqual(task.status, TaskStatus.CANCELED)
            self.assertEqual(task.cancellation_evidence, CancellationEvidence.MARKER_OBSERVED)
            self.assertEqual(self.store.get_workspace(self.workspace_id).status, WorkspaceStatus.DIRTY)
        finally:
            if not running.done():
                await running

    async def test_real_verified_result_lost_before_commit_is_not_recovered_as_clean(self):
        request = ws_request("run-correct", self.workspace_id, code="raise ValueError('ordinary')")
        request.dataset_id = "ds1"
        task = Task(task_id="lost-before-commit", status=TaskStatus.QUEUED, request=request,
                    effective_output_limits=EffectiveOutputLimits(**_LIMITS))
        self.store.create_with_admission(task)
        self.store.begin_execution_exclusive(task.task_id)
        labels = self.store.prepare_container_identity(task.task_id, self.config.deployment_id)
        result = await asyncio.to_thread(
            sandbox_runner.run_in_sandbox, self.config, task.task_id, "ds1", None,
            request.code, None, None, None,
            effective_output_limits=_LIMITS,
            workspace_mount=sandbox_runner.WorkspaceMount(str(self.workspace_dir), labels),
        )
        self.assertIs(result["workspace_safe_to_continue"], True)
        self.store.record_container_id(task.task_id, result["container_id"])
        reloaded = DurableTaskStore(self.root / "state.json")
        reloaded.recover_after_restart()
        self.assertEqual(reloaded.get(task.task_id).status, TaskStatus.FAILED)
        self.assertEqual(reloaded.get(task.task_id).result.resource_usage.exit_reason, "UNKNOWN")
        self.assertEqual(reloaded.get_workspace(self.workspace_id).status, WorkspaceStatus.DIRTY)


if __name__ == "__main__":
    unittest.main()
