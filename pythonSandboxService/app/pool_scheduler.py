from __future__ import annotations

import logging
import queue
import threading
import time
from collections import Counter
from concurrent.futures import Future, ThreadPoolExecutor, TimeoutError as FutureTimeoutError
from dataclasses import dataclass
from typing import List

from .config import SandboxConfig
from .sandbox_runner import (
    SANDBOX_WORKER_LABELS,
    create_sandbox_session,
    get_session_container_id,
    prepare_container_loader_modules,
    run_in_open_session,
    smoke_check_session,
)

logger = logging.getLogger(__name__)


class SandboxQueueTimeoutError(TimeoutError):
    """Raised when a task waits too long for scheduler execution."""


@dataclass(frozen=True)
class SandboxJob:
    task_id: str
    dataset_id: str
    dataset_ids: List[str] | None
    code: str
    files: List[str] | None
    libraries: List[str] | None
    timeout_seconds: float | None
    enqueued_at: float
    future: Future
    # 260623-harness-optimization-02: agent run 级 dataset / manifest CSV 注入
    paths_dataset_csv: str | None = None
    path_manifest_csv: str | None = None
    resource_class: str = "STANDARD"


class ContainerWorker:
    def __init__(self, worker_id: int, config: SandboxConfig, manager: "ContainerPoolScheduler") -> None:
        self.worker_id = worker_id
        self.config = config
        self.manager = manager
        self.session = None
        self.container_id = "starting"
        self.state = "starting"
        self.started_at: float | None = None
        self.last_used_at: float | None = None
        self.processed_count = 0
        self._stop_event = threading.Event()
        self._draining = threading.Event()
        self._thread: threading.Thread | None = None
        self._state_lock = threading.Lock()
        # Intra-container concurrency: one worker container can run N Python tasks
        # concurrently using independent task workspaces. We use a semaphore to
        # track available slots and a thread pool to execute tasks.
        self._slot_semaphore = threading.Semaphore(config.container_max_concurrency)
        self._executor = ThreadPoolExecutor(max_workers=config.container_max_concurrency)
        self._in_flight = 0
        self._in_flight_lock = threading.Lock()
        self._recycle_reason: str | None = None

    def start(self, *, block_until_ready: bool) -> None:
        if block_until_ready:
            self._open_session()
            self.manager.worker_ready(self)
            self._thread = threading.Thread(target=self._loop, name=f"sandbox-worker-{self.worker_id}", daemon=True)
        else:
            self._thread = threading.Thread(
                target=self._bootstrap_and_loop,
                name=f"sandbox-worker-{self.worker_id}",
                daemon=True,
            )
        self._thread.start()

    def stop(self) -> None:
        self._draining.set()
        self._stop_event.set()

    def join(self, timeout: float | None = None) -> None:
        if self._thread is not None:
            self._thread.join(timeout)
        self._executor.shutdown(wait=timeout is not None)

    def snapshot_state(self) -> str:
        with self._state_lock:
            return self.state

    def mark_stopping(self) -> None:
        with self._state_lock:
            self.state = "stopping"

    def available_slots(self) -> int:
        """Number of additional tasks this container can accept right now."""
        with self._in_flight_lock:
            if self.state in ("starting", "stopping", "closed"):
                return 0
            if self._draining.is_set() or self._stop_event.is_set():
                return 0
            return self.config.container_max_concurrency - self._in_flight

    def _set_state(self, state: str) -> None:
        with self._state_lock:
            self.state = state

    def _open_session(self) -> None:
        start = time.monotonic()
        session = create_sandbox_session(self.config)
        container_id = get_session_container_id(session)
        try:
            smoke_check_session(self.config, session, container_id)
            # Copy static loader modules once per warm container so concurrent
            # tasks do not race copying the same files into /sandbox.
            prepare_container_loader_modules(session, self.config)
        except Exception:
            session.close()
            raise
        self.session = session
        self.container_id = container_id
        self.started_at = time.monotonic()
        self.last_used_at = self.started_at
        self._set_state("idle")
        logger.info(
            "POOL_WORKER_READY worker=%s container=%s slots=%s create_ms=%s",
            self.worker_id,
            self.container_id,
            self.config.container_max_concurrency,
            int((time.monotonic() - start) * 1000),
        )

    def _bootstrap_and_loop(self) -> None:
        try:
            self._open_session()
            self.manager.worker_ready(self)
        except Exception as exc:
            logger.exception("POOL_WORKER_START_FAILED worker=%s error=%s", self.worker_id, exc)
            self.manager.worker_failed_to_start(self, exc)
            return
        self._loop()

    def _loop(self) -> None:
        try:
            while True:
                if self._stop_event.is_set() or self._draining.is_set():
                    with self._in_flight_lock:
                        in_flight = self._in_flight
                    if in_flight == 0:
                        break
                    time.sleep(0.1)
                    continue
                acquired = self._slot_semaphore.acquire(timeout=1.0)
                if not acquired:
                    continue
                if self._stop_event.is_set() or self._draining.is_set():
                    self._slot_semaphore.release()
                    continue

                try:
                    job = self.manager.next_job(timeout=1.0)
                except queue.Empty:
                    self._slot_semaphore.release()
                    if self.manager.should_retire_idle(self):
                        logger.info(
                            "POOL_WORKER_RETIRE_IDLE worker=%s container=%s",
                            self.worker_id,
                            self.container_id,
                        )
                        break
                    continue

                if job is None:
                    self._slot_semaphore.release()
                    self.manager.job_done()
                    break
                if self._stop_event.is_set() or self._draining.is_set():
                    self._slot_semaphore.release()
                    self.manager.requeue_job(job)
                    self.manager.job_done()
                    continue
                if not job.future.set_running_or_notify_cancel():
                    self._slot_semaphore.release()
                    self.manager.job_done()
                    continue

                with self._in_flight_lock:
                    self._in_flight += 1
                    self.last_used_at = time.monotonic()
                    if self._in_flight >= self.config.container_max_concurrency:
                        self._set_state("busy")
                    else:
                        self._set_state("partial")

                submit_future = self._executor.submit(self._run_job, job)
                submit_future.add_done_callback(lambda f, j=job: self._on_job_done(j, f))
        finally:
            self._set_state("closed")
            if self.session is not None:
                try:
                    self.session.close()
                except Exception:
                    logger.exception(
                        "POOL_WORKER_CLOSE_FAILED worker=%s container=%s",
                        self.worker_id,
                        self.container_id,
                    )
            self.manager.worker_stopped(self, self._recycle_reason)

    def _run_job(self, job: SandboxJob) -> dict:
        queue_wait_ms = int((time.monotonic() - job.enqueued_at) * 1000)
        return run_in_open_session(
            self.config,
            self.session,
            job.task_id,
            job.dataset_id,
            job.dataset_ids,
            job.code,
            job.files,
            job.libraries,
            job.timeout_seconds,
            paths_dataset_csv=job.paths_dataset_csv,
            path_manifest_csv=job.path_manifest_csv,
            queue_wait_ms=queue_wait_ms,
            container_id=self.container_id,
            prepare_loader_modules=False,
            resource_class=job.resource_class,
            usage_sampling_interval_millis=self.config.usage_sampling_interval_millis,
        )

    def _on_job_done(self, job: SandboxJob, future: Future) -> None:
        try:
            result = future.result()
            if not job.future.done():
                job.future.set_result(result)
            self.processed_count += 1

            if result.get("container_recycled"):
                self._recycle_reason = result.get("recycle_reason") or "task_requested_recycle"
                self._draining.set()
            elif (
                self.config.pool_max_container_uses is not None
                and self.processed_count >= self.config.pool_max_container_uses
            ):
                self._recycle_reason = "max_container_uses"
                self._draining.set()
        except Exception as exc:
            if not job.future.done():
                job.future.set_exception(exc)
            self._recycle_reason = f"execution_error:{type(exc).__name__}"
            self._draining.set()
        finally:
            self.manager.job_done()
            with self._in_flight_lock:
                self._in_flight = max(0, self._in_flight - 1)
                self.last_used_at = time.monotonic()
                if self._in_flight == 0:
                    self._set_state("idle")
                elif self._in_flight >= self.config.container_max_concurrency:
                    self._set_state("busy")
                else:
                    self._set_state("partial")
            self._slot_semaphore.release()


class ContainerPoolScheduler:
    """Small owned scheduler around llm-sandbox SandboxSession containers."""

    def __init__(self, config: SandboxConfig, dynamic_config=None) -> None:
        self.config = config
        self.dynamic_config = dynamic_config
        self._jobs: queue.Queue[SandboxJob | None] = queue.Queue()
        self._workers: list[ContainerWorker] = []
        self._lock = threading.Lock()
        self._closing = False
        self._started = False
        self._next_worker_id = 1
        self._starting_count = 0

    def _effective_config(self) -> SandboxConfig:
        """Return config with current dynamic values applied.

        Existing workers keep the config they were created with; new workers
        pick up runtime configuration changes (e.g. from Nacos).
        """
        if self.dynamic_config is None:
            return self.config
        return self.dynamic_config.apply_to(self.config)

    def start(self) -> None:
        self._cleanup_stale_worker_containers()
        effective = self._effective_config()
        logger.info(
            "POOL_WARMING min=%s max=%s max_concurrency=%s container_slots=%s image=%s",
            effective.pool_min_size,
            effective.pool_max_size,
            effective.max_concurrency,
            effective.container_max_concurrency,
            effective.sandbox_image,
        )
        for _ in range(effective.pool_min_size):
            self._start_worker(block_until_ready=True)
        self._started = True
        logger.info("POOL_READY %s", self.get_stats())

    def run_task(
        self,
        task_id: str,
        dataset_id: str,
        dataset_ids: List[str] | None,
        code: str,
        files: List[str] | None,
        libraries: List[str] | None,
        timeout_seconds: float | None,
        paths_dataset_csv: str | None = None,
        path_manifest_csv: str | None = None,
        resource_class: str = "STANDARD",
    ) -> dict:
        if self._closing:
            raise RuntimeError("sandbox pool is closing")
        if not self._started:
            raise RuntimeError("sandbox pool is not started")

        future: Future = Future()
        self._jobs.put(
            SandboxJob(
                task_id=task_id,
                dataset_id=dataset_id,
                dataset_ids=dataset_ids,
                code=code,
                files=files,
                libraries=libraries,
                timeout_seconds=timeout_seconds,
                enqueued_at=time.monotonic(),
                future=future,
                paths_dataset_csv=paths_dataset_csv,
                path_manifest_csv=path_manifest_csv,
                resource_class=resource_class,
            )
        )
        self._maybe_scale_up()
        wait_timeout = self._task_wait_timeout(timeout_seconds)
        try:
            return future.result(timeout=wait_timeout)
        except FutureTimeoutError as exc:
            future.cancel()
            raise SandboxQueueTimeoutError(
                f"sandbox task wait timed out after {wait_timeout:.1f}s"
            ) from exc

    def next_job(self, *, timeout: float) -> SandboxJob | None:
        return self._jobs.get(timeout=timeout)

    def job_done(self) -> None:
        self._jobs.task_done()

    def requeue_job(self, job: SandboxJob) -> None:
        """Put a job back on the queue after it was dequeued but not started."""
        self._jobs.put(job)

    def worker_ready(self, worker: ContainerWorker) -> None:
        with self._lock:
            self._starting_count = max(0, self._starting_count - 1)

    def worker_failed_to_start(self, worker: ContainerWorker, exc: Exception) -> None:
        effective = self._effective_config()
        with self._lock:
            self._starting_count = max(0, self._starting_count - 1)
            if worker in self._workers:
                self._workers.remove(worker)
            should_replace = not self._closing and self._total_worker_slots_locked() < effective.pool_min_size
        if should_replace:
            self._maybe_scale_up(force_min=True)

    def worker_stopped(self, worker: ContainerWorker, replace_reason: str | None) -> None:
        effective = self._effective_config()
        with self._lock:
            if worker in self._workers:
                self._workers.remove(worker)
            if self._closing:
                return
            queued = self._jobs.qsize()
            live_slots = self._total_worker_slots_locked()
            should_replace = (
                replace_reason is not None
                or live_slots < effective.pool_min_size
                or queued > 0
            ) and live_slots < effective.pool_max_size

        if replace_reason is not None:
            logger.warning(
                "POOL_WORKER_REPLACE worker=%s container=%s reason=%s",
                worker.worker_id,
                worker.container_id,
                replace_reason,
            )
        if should_replace:
            self._start_worker(block_until_ready=False)

    def should_retire_idle(self, worker: ContainerWorker) -> bool:
        if self.config.pool_idle_timeout_seconds is None or worker.last_used_at is None:
            return False
        idle_seconds = time.monotonic() - worker.last_used_at
        if idle_seconds < self.config.pool_idle_timeout_seconds:
            return False
        with self._lock:
            return not self._closing and len(self._workers) > self.config.pool_min_size and worker.available_slots() == worker.config.container_max_concurrency

    def close(self) -> None:
        with self._lock:
            self._closing = True
            workers = list(self._workers)
        for worker in workers:
            worker.mark_stopping()
            worker.stop()
            self._jobs.put(None)
        while True:
            try:
                job = self._jobs.get_nowait()
            except queue.Empty:
                break
            if job is not None and not job.future.done():
                job.future.set_exception(RuntimeError("sandbox pool is closing"))
            self._jobs.task_done()
        for worker in workers:
            worker.join(timeout=10)

    def get_stats(self) -> dict:
        effective = self._effective_config()
        with self._lock:
            workers = list(self._workers)
            starting = self._starting_count
        states = Counter(worker.snapshot_state() for worker in workers)
        available_slots = sum(worker.available_slots() for worker in workers)
        total_slots = sum(worker.config.container_max_concurrency for worker in workers)
        return {
            "total_size": len(workers),
            "min_size": effective.pool_min_size,
            "max_size": effective.pool_max_size,
            "queued": self._jobs.qsize(),
            "ready": states.get("idle", 0) + states.get("partial", 0),
            "busy": states.get("busy", 0),
            "starting": starting,
            "available_slots": available_slots,
            "total_slots": total_slots,
            "container_max_concurrency": effective.container_max_concurrency,
            "state_counts": dict(states),
            "workers": [
                {
                    "worker_id": worker.worker_id,
                    "container_id": worker.container_id,
                    "state": worker.snapshot_state(),
                    "processed_count": worker.processed_count,
                    "available_slots": worker.available_slots(),
                    "total_slots": worker.config.container_max_concurrency,
                }
                for worker in workers
            ],
        }

    def _maybe_scale_up(self, *, force_min: bool = False) -> None:
        effective = self._effective_config()
        with self._lock:
            if self._closing:
                return
            queued = self._jobs.qsize()
            slots = self._total_worker_slots_locked()
            # Load balancing: capacity-first / min-container. We keep tasks on
            # existing warm containers until their total available slots can no
            # longer cover the queued workload, then start a new container.
            available_capacity = sum(worker.available_slots() for worker in self._workers)
            need_min = force_min and slots < effective.pool_min_size
            need_queue_capacity = queued > available_capacity and slots < effective.pool_max_size
            if not need_min and not need_queue_capacity:
                return
        self._start_worker(block_until_ready=False)

    def _cleanup_stale_worker_containers(self) -> None:
        """Remove labeled worker containers left behind by a previous service process."""
        if self.config.docker_backend != "docker":
            return
        try:
            import docker
        except Exception as exc:
            logger.warning("POOL_STALE_CLEANUP_SKIPPED reason=docker_import_failed error=%s", exc)
            return

        client = None
        try:
            client = docker.from_env()
            filters = {
                "label": [f"{key}={value}" for key, value in SANDBOX_WORKER_LABELS.items()],
            }
            containers = client.containers.list(all=True, filters=filters)
            if not containers:
                return
            logger.warning("POOL_STALE_CLEANUP_START count=%s", len(containers))
            for container in containers:
                container_id = getattr(container, "short_id", None) or getattr(container, "id", "unknown")
                try:
                    container.remove(force=True)
                    logger.warning("POOL_STALE_CLEANUP_REMOVED container=%s", container_id)
                except Exception as exc:
                    logger.warning("POOL_STALE_CLEANUP_REMOVE_FAILED container=%s error=%s", container_id, exc)
        except Exception as exc:
            logger.warning("POOL_STALE_CLEANUP_FAILED error=%s", exc)
        finally:
            if client is not None:
                try:
                    client.close()
                except Exception:
                    pass

    def _task_wait_timeout(self, timeout_seconds: float | None) -> float:
        return (timeout_seconds or self.config.execution_timeout_seconds) + self.config.queue_wait_timeout_seconds

    def _start_worker(self, *, block_until_ready: bool) -> None:
        effective = self._effective_config()
        with self._lock:
            if self._closing:
                return
            if self._total_worker_slots_locked() >= effective.pool_max_size:
                return
            worker_id = self._next_worker_id
            self._next_worker_id += 1
            worker = ContainerWorker(worker_id, effective, self)
            self._workers.append(worker)
            self._starting_count += 1
        try:
            worker.start(block_until_ready=block_until_ready)
        except Exception:
            with self._lock:
                self._starting_count = max(0, self._starting_count - 1)
                if worker in self._workers:
                    self._workers.remove(worker)
            raise

    def _total_worker_slots_locked(self) -> int:
        return len(self._workers)
