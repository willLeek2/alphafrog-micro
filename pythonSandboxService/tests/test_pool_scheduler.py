from __future__ import annotations

import threading
import time
import sys
import types
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from unittest.mock import patch

llm_sandbox = types.ModuleType("llm_sandbox")
llm_sandbox.SandboxSession = object
llm_sandbox_exceptions = types.ModuleType("llm_sandbox.exceptions")
llm_sandbox_exceptions.SandboxTimeoutError = TimeoutError
sys.modules.setdefault("llm_sandbox", llm_sandbox)
sys.modules.setdefault("llm_sandbox.exceptions", llm_sandbox_exceptions)

from app.config import SandboxConfig
from app.pool_scheduler import ContainerPoolScheduler, SandboxQueueTimeoutError


class FakeSession:
    def close(self) -> None:
        pass


class FakeStateWorker:
    def __init__(self, state: str) -> None:
        self.state = state

    def snapshot_state(self) -> str:
        return self.state


def make_config(*, min_size: int = 1, max_size: int = 2) -> SandboxConfig:
    return SandboxConfig(
        data_dir=Path("/tmp"),
        max_concurrency=20,
        execution_timeout_seconds=5,
        memory_limit="512m",
        memswap_limit="512m",
        docker_backend="docker",
        workdir="/sandbox",
        log_level="INFO",
        sandbox_image="test-runtime",
        skip_environment_setup=True,
        preinstalled_libraries=frozenset({"numpy", "pandas", "matplotlib", "scipy"}),
        pool_enabled=True,
        pool_min_size=min_size,
        pool_max_size=max_size,
        pool_acquire_timeout_seconds=30,
        pool_idle_timeout_seconds=None,
        pool_max_container_uses=None,
        workspace_root="/sandbox/runs",
        compat_input_path_enabled=True,
    )


class ContainerPoolSchedulerTest(unittest.TestCase):
    def test_run_task_returns_container_and_queue_timings(self) -> None:
        def fake_run(config, session, task_id, dataset_id, dataset_ids, code, files, libraries, timeout, **kwargs):
            return {
                "exit_code": 0,
                "stdout": "ok",
                "stderr": "",
                "dataset_dir": "/sandbox/input/ds1",
                "timings": {"queue_wait_ms": kwargs["queue_wait_ms"], "total_runner_ms": 3},
                "container_recycled": False,
                "recycle_reason": None,
                "container_id": kwargs["container_id"],
            }

        with patch("app.pool_scheduler.create_sandbox_session", return_value=FakeSession()), \
            patch("app.pool_scheduler.get_session_container_id", return_value="container-1"), \
            patch("app.pool_scheduler.smoke_check_session"), \
            patch("app.pool_scheduler.run_in_open_session", side_effect=fake_run):
            scheduler = ContainerPoolScheduler(make_config())
            scheduler.start()
            try:
                result = scheduler.run_task("task-1", "ds1", None, "print(1)", None, None, 5)
                self.assertEqual(result["exit_code"], 0)
                self.assertEqual(result["container_id"], "container-1")
                self.assertIn("queue_wait_ms", result["timings"])
                self.assertEqual(scheduler.get_stats()["total_size"], 1)
            finally:
                scheduler.close()

    def test_busy_worker_scales_without_blocking_ready_worker(self) -> None:
        first_task_started = threading.Event()
        release_first_task = threading.Event()

        def fake_run(config, session, task_id, dataset_id, dataset_ids, code, files, libraries, timeout, **kwargs):
            if task_id == "task-1":
                first_task_started.set()
                self.assertTrue(release_first_task.wait(timeout=3))
            return {
                "exit_code": 0,
                "stdout": task_id,
                "stderr": "",
                "dataset_dir": f"/sandbox/input/{dataset_id}",
                "timings": {"queue_wait_ms": kwargs["queue_wait_ms"], "total_runner_ms": 1},
                "container_recycled": False,
                "recycle_reason": None,
                "container_id": kwargs["container_id"],
            }

        container_counter = {"value": 0}

        def fake_container_id(session) -> str:
            container_counter["value"] += 1
            return f"container-{container_counter['value']}"

        with patch("app.pool_scheduler.create_sandbox_session", return_value=FakeSession()), \
            patch("app.pool_scheduler.get_session_container_id", side_effect=fake_container_id), \
            patch("app.pool_scheduler.smoke_check_session"), \
            patch("app.pool_scheduler.run_in_open_session", side_effect=fake_run):
            scheduler = ContainerPoolScheduler(make_config(min_size=1, max_size=2))
            scheduler.start()
            try:
                with ThreadPoolExecutor(max_workers=2) as executor:
                    first = executor.submit(
                        scheduler.run_task,
                        "task-1",
                        "ds1",
                        None,
                        "print(1)",
                        None,
                        None,
                        5,
                    )
                    self.assertTrue(first_task_started.wait(timeout=2))
                    second = executor.submit(
                        scheduler.run_task,
                        "task-2",
                        "ds2",
                        None,
                        "print(2)",
                        None,
                        None,
                        5,
                    )
                    second_result = second.result(timeout=2)
                    self.assertEqual(second_result["stdout"], "task-2")
                    self.assertEqual(scheduler.get_stats()["total_size"], 2)
                    release_first_task.set()
                    self.assertEqual(first.result(timeout=2)["stdout"], "task-1")
            finally:
                release_first_task.set()
                scheduler.close()

    def test_starting_workers_do_not_count_as_available_capacity(self) -> None:
        scheduler = ContainerPoolScheduler(make_config(min_size=1, max_size=4))
        scheduler._started = True
        scheduler._workers = [
            FakeStateWorker("idle"),
            FakeStateWorker("starting"),
            FakeStateWorker("starting"),
        ]
        scheduler._jobs.put(object())
        scheduler._jobs.put(object())
        scheduler._jobs.put(object())
        with patch.object(scheduler, "_start_worker") as start_worker:
            scheduler._maybe_scale_up()
            start_worker.assert_called_once_with(block_until_ready=False)

    def test_run_task_times_out_and_cancels_pending_job(self) -> None:
        scheduler = ContainerPoolScheduler(make_config(min_size=0, max_size=0))
        scheduler._started = True
        with patch.object(scheduler, "_task_wait_timeout", return_value=0.01):
            with self.assertRaises(SandboxQueueTimeoutError):
                scheduler.run_task("task-timeout", "ds1", None, "print(1)", None, None, 5)

        job = scheduler._jobs.get_nowait()
        try:
            self.assertTrue(job.future.cancelled())
        finally:
            scheduler._jobs.task_done()


if __name__ == "__main__":
    unittest.main()
