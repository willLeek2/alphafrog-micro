from __future__ import annotations

import logging
import queue
import threading
import time
from collections import Counter
from concurrent.futures import Future, TimeoutError as FutureTimeoutError
from dataclasses import dataclass
from typing import List

from .config import SandboxConfig
from .sandbox_runner import (
    SANDBOX_WORKER_LABELS,
    create_sandbox_session,
    get_session_container_id,
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
        self._thread: threading.Thread | None = None
        self._state_lock = threading.Lock()

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
        self._stop_event.set()

    def join(self, timeout: float | None = None) -> None:
        if self._thread is not None:
            self._thread.join(timeout)

    def snapshot_state(self) -> str:
        with self._state_lock:
            return self.state

    def mark_stopping(self) -> None:
        with self._state_lock:
            self.state = "stopping"

    def _set_state(self, state: str) -> None:
        with self._state_lock:
            self.state = state

    def _open_session(self) -> None:
        start = time.monotonic()
        session = create_sandbox_session(self.config)
        container_id = get_session_container_id(session)
        try:
            smoke_check_session(self.config, session, container_id)
        except Exception:
            session.close()
            raise
        self.session = session
        self.container_id = container_id
        self.started_at = time.monotonic()
        self.last_used_at = self.started_at
        self._set_state("idle")
        logger.info(
            "POOL_WORKER_READY worker=%s container=%s create_ms=%s",
            self.worker_id,
            self.container_id,
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
        replace_reason: str | None = None
        try:
            while not self._stop_event.is_set():
                try:
                    job = self.manager.next_job(timeout=1.0)
                except queue.Empty:
                    if self.manager.should_retire_idle(self):
                        replace_reason = None
                        logger.info("POOL_WORKER_RETIRE_IDLE worker=%s container=%s", self.worker_id, self.container_id)
                        break
                    continue

                if job is None:
                    self.manager.job_done()
                    break
                if not job.future.set_running_or_notify_cancel():
                    self.manager.job_done()
                    continue

                queue_wait_ms = int((time.monotonic() - job.enqueued_at) * 1000)
                self._set_state("busy")
                self.last_used_at = time.monotonic()
                try:
                    result = run_in_open_session(
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
                    )
                    self.processed_count += 1
                    if not job.future.done():
                        job.future.set_result(result)

                    if result.get("container_recycled"):
                        replace_reason = result.get("recycle_reason") or "task_requested_recycle"
                        break
                    if (
                        self.config.pool_max_container_uses is not None
                        and self.processed_count >= self.config.pool_max_container_uses
                    ):
                        replace_reason = "max_container_uses"
                        break

                    self.last_used_at = time.monotonic()
                    self._set_state("idle")
                except Exception as exc:
                    if not job.future.done():
                        job.future.set_exception(exc)
                    replace_reason = f"execution_error:{type(exc).__name__}"
                    break
                finally:
                    self.manager.job_done()
        finally:
            self._set_state("closed")
            if self.session is not None:
                try:
                    self.session.close()
                except Exception:
                    logger.exception("POOL_WORKER_CLOSE_FAILED worker=%s container=%s", self.worker_id, self.container_id)
            self.manager.worker_stopped(self, replace_reason)


class ContainerPoolScheduler:
    """Small owned scheduler around llm-sandbox SandboxSession containers."""

    def __init__(self, config: SandboxConfig) -> None:
        self.config = config
        self._jobs: queue.Queue[SandboxJob | None] = queue.Queue()
        self._workers: list[ContainerWorker] = []
        self._lock = threading.Lock()
        self._closing = False
        self._started = False
        self._next_worker_id = 1
        self._starting_count = 0

    def start(self) -> None:
        self._cleanup_stale_worker_containers()
        logger.info(
            "POOL_WARMING min=%s max=%s max_concurrency=%s image=%s",
            self.config.pool_min_size,
            self.config.pool_max_size,
            self.config.max_concurrency,
            self.config.sandbox_image,
        )
        for _ in range(self.config.pool_min_size):
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

    def worker_ready(self, worker: ContainerWorker) -> None:
        with self._lock:
            self._starting_count = max(0, self._starting_count - 1)

    def worker_failed_to_start(self, worker: ContainerWorker, exc: Exception) -> None:
        with self._lock:
            self._starting_count = max(0, self._starting_count - 1)
            if worker in self._workers:
                self._workers.remove(worker)
            should_replace = not self._closing and self._total_worker_slots_locked() < self.config.pool_min_size
        if should_replace:
            self._maybe_scale_up(force_min=True)

    def worker_stopped(self, worker: ContainerWorker, replace_reason: str | None) -> None:
        with self._lock:
            if worker in self._workers:
                self._workers.remove(worker)
            if self._closing:
                return
            queued = self._jobs.qsize()
            live_slots = self._total_worker_slots_locked()
            should_replace = (
                replace_reason is not None
                or live_slots < self.config.pool_min_size
                or queued > 0
            ) and live_slots < self.config.pool_max_size

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
            return not self._closing and len(self._workers) > self.config.pool_min_size

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
        with self._lock:
            workers = list(self._workers)
            starting = self._starting_count
        states = Counter(worker.snapshot_state() for worker in workers)
        return {
            "total_size": len(workers),
            "min_size": self.config.pool_min_size,
            "max_size": self.config.pool_max_size,
            "queued": self._jobs.qsize(),
            "ready": states.get("idle", 0) + states.get("busy", 0),
            "busy": states.get("busy", 0),
            "starting": starting,
            "state_counts": dict(states),
            "workers": [
                {
                    "worker_id": worker.worker_id,
                    "container_id": worker.container_id,
                    "state": worker.snapshot_state(),
                    "processed_count": worker.processed_count,
                }
                for worker in workers
            ],
        }

    def _maybe_scale_up(self, *, force_min: bool = False) -> None:
        with self._lock:
            if self._closing:
                return
            queued = self._jobs.qsize()
            slots = self._total_worker_slots_locked()
            states = Counter(worker.snapshot_state() for worker in self._workers)
            available_capacity = states.get("idle", 0)
            need_min = force_min and slots < self.config.pool_min_size
            need_queue_capacity = queued > available_capacity and slots < self.config.pool_max_size
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
        return max((timeout_seconds or self.config.execution_timeout_seconds) * 2, 30)

    def _start_worker(self, *, block_until_ready: bool) -> None:
        with self._lock:
            if self._closing:
                return
            if self._total_worker_slots_locked() >= self.config.pool_max_size:
                return
            worker_id = self._next_worker_id
            self._next_worker_id += 1
            worker = ContainerWorker(worker_id, self.config, self)
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
