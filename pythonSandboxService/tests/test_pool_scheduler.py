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
from tests.nonroot_fakes import prime_fake_session


class FakeSession:
    def __init__(self) -> None:
        # Non-root staging surface (260818): the pool worker's startup
        # writes runtime-environment.json through container.put_archive,
        # so the fake needs the container proxy + cached identity +
        # an execute_command for the staging mkdir.
        prime_fake_session(self)

    def execute_command(self, command: str):
        return types.SimpleNamespace(exit_code=0, stdout="", stderr="")

    def copy_to_runtime(self, source: str, dest_path: str) -> None:
        raise AssertionError(
            "production must stage via container.put_archive "
            "(non-root contract, 260818)"
        )

    def close(self) -> None:
        pass


class FakeStateWorker:
    def __init__(self, state: str, available_slots: int = 0, container_max_concurrency: int = 1) -> None:
        self.state = state
        self._available_slots = available_slots
        self.config = make_config(container_max_concurrency=container_max_concurrency)

    def snapshot_state(self) -> str:
        return self.state

    def available_slots(self) -> int:
        return self._available_slots


class FakeDockerContainer:
    def __init__(self, container_id: str) -> None:
        self.short_id = container_id
        self.removed = False

    def remove(self, *, force: bool) -> None:
        self.removed = force


def make_config(*, min_size: int = 1, max_size: int = 2, container_max_concurrency: int = 1) -> SandboxConfig:
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
        container_max_concurrency=container_max_concurrency,
        pool_enabled=True,
        pool_min_size=min_size,
        pool_max_size=max_size,
        pool_acquire_timeout_seconds=30,
        pool_idle_timeout_seconds=None,
        pool_max_container_uses=None,
        workspace_root="/sandbox/runs",
        compat_input_path_enabled=(container_max_concurrency == 1),
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
            patch("app.pool_scheduler.prepare_container_loader_modules"), \
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
            patch("app.pool_scheduler.prepare_container_loader_modules"), \
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

    def test_startup_removes_stale_labeled_worker_containers(self) -> None:
        stale = FakeDockerContainer("old-worker")
        filters_seen = {}

        class FakeContainers:
            def list(self, *, all, filters):
                filters_seen.update(filters)
                return [stale]

        class FakeDockerClient:
            containers = FakeContainers()

            def close(self):
                pass

        fake_docker = types.ModuleType("docker")
        fake_docker.from_env = lambda: FakeDockerClient()

        old_docker = sys.modules.get("docker")
        sys.modules["docker"] = fake_docker
        try:
            scheduler = ContainerPoolScheduler(make_config(min_size=0, max_size=0))
            scheduler.start()
            self.assertTrue(stale.removed)
            self.assertIn("label", filters_seen)
            self.assertIn("com.alphafrog.role=python-sandbox-worker", filters_seen["label"])
        finally:
            if old_docker is None:
                sys.modules.pop("docker", None)
            else:
                sys.modules["docker"] = old_docker


    def test_single_container_runs_multiple_tasks_concurrently(self) -> None:
        """With container_max_concurrency=3, one container can run 3 tasks at once."""
        barrier = threading.Barrier(3)
        completed = []
        completed_lock = threading.Lock()

        def fake_run(config, session, task_id, dataset_id, dataset_ids, code, files, libraries, timeout, **kwargs):
            barrier.wait(timeout=3)
            with completed_lock:
                completed.append(task_id)
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

        with patch("app.pool_scheduler.create_sandbox_session", return_value=FakeSession()), \
            patch("app.pool_scheduler.get_session_container_id", return_value="container-1"), \
            patch("app.pool_scheduler.smoke_check_session"), \
            patch("app.pool_scheduler.prepare_container_loader_modules"), \
            patch("app.pool_scheduler.run_in_open_session", side_effect=fake_run):
            scheduler = ContainerPoolScheduler(
                make_config(min_size=1, max_size=1, container_max_concurrency=3)
            )
            scheduler.start()
            try:
                with ThreadPoolExecutor(max_workers=3) as executor:
                    futures = [
                        executor.submit(scheduler.run_task, f"task-{i}", f"ds{i}", None, "print(1)", None, None, 5)
                        for i in range(3)
                    ]
                    results = [f.result(timeout=5) for f in futures]
                    self.assertEqual(len(results), 3)
                    self.assertEqual(set(r["container_id"] for r in results), {"container-1"})
                    self.assertEqual(len(completed), 3)
            finally:
                scheduler.close()


    def test_draining_container_does_not_accept_new_tasks(self) -> None:
        """If one concurrent task triggers recycle, the worker must drain and not pick up new jobs."""
        a_started = threading.Event()
        b_started = threading.Event()
        a_release = threading.Event()
        b_release = threading.Event()

        def fake_run(config, session, task_id, dataset_id, dataset_ids, code, files, libraries, timeout, **kwargs):
            if task_id == "task-a":
                a_started.set()
                self.assertTrue(a_release.wait(timeout=3))
                return {
                    "exit_code": 0,
                    "stdout": "a",
                    "stderr": "",
                    "dataset_dir": f"/sandbox/input/{dataset_id}",
                    "timings": {"queue_wait_ms": kwargs["queue_wait_ms"], "total_runner_ms": 1},
                    "container_recycled": True,
                    "recycle_reason": "test-drain",
                    "container_id": kwargs["container_id"],
                }
            if task_id == "task-b":
                b_started.set()
                self.assertTrue(b_release.wait(timeout=3))
                return {
                    "exit_code": 0,
                    "stdout": "b",
                    "stderr": "",
                    "dataset_dir": f"/sandbox/input/{dataset_id}",
                    "timings": {"queue_wait_ms": kwargs["queue_wait_ms"], "total_runner_ms": 1},
                    "container_recycled": False,
                    "recycle_reason": None,
                    "container_id": kwargs["container_id"],
                }
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
            patch("app.pool_scheduler.prepare_container_loader_modules"), \
            patch("app.pool_scheduler.run_in_open_session", side_effect=fake_run):
            scheduler = ContainerPoolScheduler(
                make_config(min_size=1, max_size=2, container_max_concurrency=2)
            )
            scheduler.start()
            try:
                with ThreadPoolExecutor(max_workers=3) as executor:
                    first = executor.submit(scheduler.run_task, "task-a", "ds-a", None, "print(1)", None, None, 5)
                    second = executor.submit(scheduler.run_task, "task-b", "ds-b", None, "print(2)", None, None, 5)
                    self.assertTrue(a_started.wait(timeout=2))
                    self.assertTrue(b_started.wait(timeout=2))

                    a_release.set()
                    result_a = first.result(timeout=3)
                    self.assertTrue(result_a["container_recycled"])

                    # Submit C while B is still running on the old container.
                    third = executor.submit(scheduler.run_task, "task-c", "ds-c", None, "print(3)", None, None, 5)
                    b_release.set()
                    result_b = second.result(timeout=3)
                    result_c = third.result(timeout=5)

                    self.assertEqual(result_b["container_id"], "container-1")
                    self.assertNotEqual(result_c["container_id"], "container-1")
            finally:
                a_release.set()
                b_release.set()
                scheduler.close()

    def test_draining_requeues_job_dequeued_after_recycle(self) -> None:
        """If a worker dequeues a job *after* recycle starts, it must requeue it safely."""
        a_started = threading.Event()
        a_release = threading.Event()
        next_job_blocked = threading.Event()
        drain_permitted = threading.Event()

        def fake_run(config, session, task_id, dataset_id, dataset_ids, code, files, libraries, timeout, **kwargs):
            if task_id == "task-a":
                a_started.set()
                self.assertTrue(a_release.wait(timeout=3))
                return {
                    "exit_code": 0,
                    "stdout": "a",
                    "stderr": "",
                    "dataset_dir": f"/sandbox/input/{dataset_id}",
                    "timings": {"queue_wait_ms": kwargs["queue_wait_ms"], "total_runner_ms": 1},
                    "container_recycled": True,
                    "recycle_reason": "test-drain-requeue",
                    "container_id": kwargs["container_id"],
                }
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
            patch("app.pool_scheduler.prepare_container_loader_modules"), \
            patch("app.pool_scheduler.run_in_open_session", side_effect=fake_run):
            scheduler = ContainerPoolScheduler(
                make_config(min_size=1, max_size=2, container_max_concurrency=2)
            )
            scheduler.start()
            original_next_job = scheduler.next_job

            def patched_next_job(*, timeout):
                next_job_blocked.set()
                self.assertTrue(drain_permitted.wait(timeout=3))
                return original_next_job(timeout=timeout)

            scheduler.next_job = patched_next_job
            try:
                with ThreadPoolExecutor(max_workers=2) as executor:
                    first = executor.submit(scheduler.run_task, "task-a", "ds-a", None, "print(1)", None, None, 5)
                    self.assertTrue(a_started.wait(timeout=2))
                    self.assertTrue(next_job_blocked.wait(timeout=2))

                    a_release.set()
                    result_a = first.result(timeout=3)
                    self.assertTrue(result_a["container_recycled"])

                    # The worker is now draining but still blocked inside next_job().
                    # Submit task-b; it must be requeued and picked up by a replacement container.
                    second = executor.submit(scheduler.run_task, "task-b", "ds-b", None, "print(2)", None, None, 5)
                    drain_permitted.set()
                    result_b = second.result(timeout=5)
                    self.assertNotEqual(result_b["container_id"], "container-1")
            finally:
                a_release.set()
                drain_permitted.set()
                scheduler.close()

    def test_pool_initialize_failure_closes_session_and_does_not_register_worker(self) -> None:
        """codex 2026-08-08 23:44 (msg 044974a1) pool init lifecycle:
        smoke + loader + initialize MUST share one close-on-error try, and
        the worker MUST only register itself (idle/ready, holding
        ``self.session/container_id``) AFTER initialize succeeds. Otherwise
        an init collect/copy failure orphans the Docker container — the
        manager never sees an idle worker, but the container stays alive.
        """

        class _TrackingSession:
            # No container object on purpose: the non-root staging path
            # (260818) fails closed during initialize_runtime_environment
            # (NonRootContractError), which is this test's init failure.
            def __init__(self) -> None:
                self.close_count = 0

            def close(self) -> None:
                self.close_count += 1

        tracking_session = _TrackingSession()

        with patch("app.pool_scheduler.create_sandbox_session", return_value=tracking_session), \
            patch("app.pool_scheduler.get_session_container_id", return_value="container-init-leak"), \
            patch("app.pool_scheduler.smoke_check_session"), \
            patch("app.pool_scheduler.prepare_container_loader_modules"), \
            patch("app.pool_scheduler.run_in_open_session") as run_mock:
            scheduler = ContainerPoolScheduler(
                make_config(min_size=0, max_size=1, container_max_concurrency=1)
            )
            worker_ready_calls: list[object] = []
            worker_failed_calls: list[object] = []

            scheduler.worker_ready = lambda w: worker_ready_calls.append(w)  # type: ignore[method-assign]
            scheduler.worker_failed_to_start = lambda w, exc: worker_failed_calls.append((w, exc))  # type: ignore[method-assign]

            scheduler._start_worker(block_until_ready=False)
            try:
                time.sleep(0.5)
            finally:
                scheduler.close()

        self.assertEqual(
            1, tracking_session.close_count,
            "pool init failure MUST close the just-created session exactly "
            "once; otherwise the Docker container is orphaned.",
        )
        self.assertEqual(
            [], worker_ready_calls,
            "worker_ready MUST NOT be called when initialize fails; the "
            "manager would otherwise accept jobs against a half-initialized "
            "container.",
        )
        self.assertGreaterEqual(
            len(worker_failed_calls), 1,
            "worker_failed_to_start MUST be called when initialize raises "
            "(caller worker_failed_to_start hook is responsible for evicting "
            "the failed worker from the scheduler).",
        )
        self.assertEqual(
            [], run_mock.call_args_list,
            "run_in_open_session MUST NOT be invoked when pool initialize "
            "fails.",
        )


if __name__ == "__main__":
    unittest.main()
