from __future__ import annotations

import asyncio
import logging
import uuid
from contextlib import asynccontextmanager
from datetime import datetime
from typing import Dict

from fastapi import FastAPI, HTTPException

from .canonical_fingerprint import (
    CanonicalFingerprintMismatch,
    CanonicalSpecError,
    verify_request_fingerprint,
)
from .config import load_config
from .models import (
    CreateTaskResponse,
    ExecuteRequest,
    ExecuteResult,
    OperationLookupResponse,
    SandboxResourceUsage,
    Task,
    TaskStatus,
)
from .nacos_config import DynamicSandboxConfig, start_nacos_listener
from .pool_scheduler import ContainerPoolScheduler
from .sandbox_runner import run_in_sandbox
from .task_store import DurableTaskStore, OperationConflictError

# Setup logging with Asia/Shanghai timezone
from zoneinfo import ZoneInfo


class ShanghaiFormatter(logging.Formatter):
    def formatTime(self, record, datefmt=None):
        dt = datetime.fromtimestamp(record.created, tz=ZoneInfo("Asia/Shanghai"))
        if datefmt:
            return dt.strftime(datefmt)
        return dt.isoformat()


_formatter = ShanghaiFormatter(
    fmt="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S %z",
)
_handler = logging.StreamHandler()
_handler.setFormatter(_formatter)
logging.basicConfig(level=logging.INFO, handlers=[_handler], force=True)

# Suppress uvicorn access logs to reduce polling noise
uvicorn_access = logging.getLogger("uvicorn.access")
uvicorn_access.handlers = []
uvicorn_access.addHandler(_handler)
uvicorn_access.setLevel(logging.WARNING)

logger = logging.getLogger(__name__)

config = load_config()
dynamic_config = DynamicSandboxConfig(config)

# Durable task/result storage and operationId index
task_store = DurableTaskStore(config.task_store_path)
tasks: Dict[str, Task] = task_store.tasks
task_queue: asyncio.Queue = asyncio.Queue()

# Container scheduler (created in lifespan)
pool: ContainerPoolScheduler | None = None


async def _log_pool_stats():
    """Background task: emit pool stats every 30s so operators can see warm-up progress."""
    while True:
        try:
            await asyncio.sleep(30)
            if pool is not None:
                try:
                    stats = pool.get_stats()
                    logger.info(
                        "POOL_STATS total=%s ready=%s busy=%s queued=%s max=%s min=%s states=%s",
                        stats.get("total_size"),
                        stats.get("ready"),
                        stats.get("busy"),
                        stats.get("queued"),
                        stats.get("max_size"),
                        stats.get("min_size"),
                        stats.get("state_counts"),
                    )
                except Exception:
                    pass
        except asyncio.CancelledError:
            break
        except Exception:
            pass


async def worker(worker_id: int):
    logger.info("Worker %s started", worker_id)
    while True:
        try:
            task_id = await task_queue.get()
            task = tasks.get(task_id)
            if task and task.status == TaskStatus.QUEUED:
                await process_task(task, worker_id)
            task_queue.task_done()
        except asyncio.CancelledError:
            logger.info("Worker %s cancelled", worker_id)
            break
        except Exception as e:
            logger.error("Worker %s error: %s", worker_id, e)


async def process_task(task: Task, worker_id: int):
    started_at = datetime.utcnow()
    queued_ms = int((started_at - task.created_at).total_seconds() * 1000)
    if queued_ms > int(config.queue_wait_timeout_seconds * 1000):
        task.status = TaskStatus.FAILED
        task.finished_at = started_at
        task.error = "sandbox queue wait timeout"
        task.resource_usage = SandboxResourceUsage(
            resource_class=task.request.resource_class,
            queue_wait_millis=queued_ms,
            exit_reason="QUEUE_TIMEOUT",
            attribution_complete=False,
            missing_fields=[
                "cpuMillis",
                "memoryPeakBytes",
                "prepareMillis",
                "executionWallMillis",
                "cleanupMillis",
            ],
        )
        task.result = ExecuteResult(
            exit_code=-1,
            stdout="",
            stderr=task.error,
            dataset_dir=f"{config.workdir}/input/{task.request.dataset_id}",
            resource_usage=task.resource_usage,
        )
        task_store.save(task)
        return
    task.status = TaskStatus.RUNNING
    task.started_at = started_at
    task_store.save(task)
    logger.info(
        "TASK_START task=%s worker=%s queued_ms=%s dataset=%s pool_enabled=%s",
        task.task_id, worker_id, queued_ms,
        task.request.dataset_id, config.pool_enabled,
    )

    result_dict: dict = {}
    try:
        # Run synchronous sandbox runner in thread pool
        if pool is not None and config.pool_enabled:
            result_dict = await asyncio.to_thread(
                pool.run_task,
                task.task_id,
                task.request.dataset_id,
                task.request.dataset_ids,
                task.request.code,
                task.request.files,
                task.request.libraries,
                task.request.timeout_seconds,
                task.request.paths_dataset_csv,
                task.request.path_manifest_csv,
                task.request.resource_class,
            )
        else:
            result_dict = await asyncio.to_thread(
                run_in_sandbox,
                config,
                task.task_id,
                task.request.dataset_id,
                task.request.dataset_ids,
                task.request.code,
                task.request.files,
                task.request.libraries,
                task.request.timeout_seconds,
                paths_dataset_csv=task.request.paths_dataset_csv,
                path_manifest_csv=task.request.path_manifest_csv,
                queue_wait_ms=queued_ms,
                resource_class=task.request.resource_class,
                memory_limit_bytes=task.request.memory_limit_bytes,
            )
        usage_payload = result_dict.get("resource_usage")
        if usage_payload:
            task.resource_usage = SandboxResourceUsage.model_validate(usage_payload)
        task.result = ExecuteResult(
            exit_code=result_dict["exit_code"],
            stdout=result_dict["stdout"],
            stderr=result_dict["stderr"],
            dataset_dir=result_dict["dataset_dir"],
            artifacts={
                "timings": result_dict.get("timings", {}),
                "container_id": result_dict.get("container_id"),
                "container_recycled": result_dict.get("container_recycled", False),
                "recycle_reason": result_dict.get("recycle_reason"),
            },
            resource_usage=task.resource_usage,
        )
        task.status = TaskStatus.SUCCEEDED if result_dict["exit_code"] == 0 else TaskStatus.FAILED
        if task.status == TaskStatus.FAILED:
            task.error = f"sandbox exited with code {result_dict['exit_code']}"
    except Exception as e:
        logger.error("Task %s failed: %s", task.task_id, e)
        task.error = str(e)
        task.status = TaskStatus.FAILED
        usage_payload = getattr(e, "resource_usage", None)
        if usage_payload:
            task.resource_usage = SandboxResourceUsage.model_validate(usage_payload)
        task.result = ExecuteResult(
            exit_code=-1,
            stdout="",
            stderr=str(e),
            dataset_dir=f"{config.workdir}/input/{task.request.dataset_id}",
            artifacts={"timings": getattr(e, "timings", {})},
            resource_usage=task.resource_usage,
        )
    finally:
        task.finished_at = datetime.utcnow()
        task_store.save(task)
        duration_ms = int((task.finished_at - task.started_at).total_seconds() * 1000)

        timings = result_dict.get("timings", {})
        container_recycled = result_dict.get("container_recycled", False)
        recycle_reason = result_dict.get("recycle_reason")
        container_id = result_dict.get("container_id", "-")
        container_create_ms = timings.get("container_create_ms")
        if container_create_ms is None:
            container_create_ms = "n/a" if config.pool_enabled else "-"

        status_label = "SUCCESS" if task.status == TaskStatus.SUCCEEDED else "FAILED"
        logger.info(
            "TASK_COMPLETED task=%s worker=%s status=%s duration_ms=%s "
            "queued_ms=%s pool_enabled=%s queue_wait_ms=%s container_id=%s container_create_ms=%s "
            "workspace_prepare_ms=%s script_run_ms=%s workspace_cleanup_ms=%s "
            "env_load_ms=%s code_exec_ms=%s artifact_collect_ms=%s "
            "total_runner_ms=%s total_duration_ms=%s container_recycled=%s recycle_reason=%s",
            task.task_id,
            worker_id,
            status_label,
            duration_ms,
            queued_ms,
            config.pool_enabled,
            timings.get("queue_wait_ms", "-"),
            container_id,
            container_create_ms,
            timings.get("workspace_prepare_ms", "-"),
            timings.get("script_run_ms", "-"),
            timings.get("workspace_cleanup_ms", "-"),
            timings.get("env_load_ms", "-"),
            timings.get("code_exec_ms", "-"),
            timings.get("artifact_collect_ms", "-"),
            timings.get("total_runner_ms", "-"),
            timings.get("total_duration_ms", "-"),
            container_recycled,
            recycle_reason or "none",
        )


@asynccontextmanager
async def lifespan(app: FastAPI):
    global pool
    worker_count = max(1, config.max_concurrency)

    for recovered_task_id in task_store.recover_after_restart():
        await task_queue.put(recovered_task_id)

    # Start Nacos config listener for hot-reloadable values.
    start_nacos_listener(config, dynamic_config)

    # Create container pool if enabled
    if config.pool_enabled:
        pool = ContainerPoolScheduler(config, dynamic_config=dynamic_config)
        pool.start()
        logger.info("Container scheduler initialized for sandbox runner")
    else:
        logger.info("Container pool disabled, using fresh containers per task")

    worker_tasks = [asyncio.create_task(worker(i + 1)) for i in range(worker_count)]
    stats_task = asyncio.create_task(_log_pool_stats())
    yield

    # Shutdown workers
    stats_task.cancel()
    for worker_task in worker_tasks:
        worker_task.cancel()
    await asyncio.gather(*worker_tasks, stats_task, return_exceptions=True)

    # Close pool
    if pool is not None:
        logger.info("Closing container pool...")
        pool.close()
        pool = None


app = FastAPI(title="alphafrog-python-sandbox", version="0.3.0", lifespan=lifespan)


@app.get("/health")
async def health() -> dict:
    result = {"status": "ok", "pool_enabled": config.pool_enabled}
    if pool is not None:
        try:
            stats = pool.get_stats()
            result["pool_stats"] = stats
        except Exception:
            pass
    return result


@app.post("/tasks", response_model=CreateTaskResponse)
async def create_task(request: ExecuteRequest):
    expected_memory = (
        config.heavy_memory_limit_bytes
        if request.resource_class == "HEAVY"
        else config.standard_memory_limit_bytes
    )
    expected_units = 3 if request.resource_class == "HEAVY" else 1
    if request.memory_limit_bytes is None:
        request.memory_limit_bytes = expected_memory
    elif request.memory_limit_bytes != expected_memory:
        raise HTTPException(
            status_code=400,
            detail=f"memory_limit_bytes must equal {expected_memory} for {request.resource_class}",
        )
    if request.capacity_units is not None and request.capacity_units != expected_units:
        raise HTTPException(
            status_code=400,
            detail=f"capacity_units must equal {expected_units} for {request.resource_class}",
        )
    if request.timeout_millis is not None:
        timeout_seconds = request.timeout_millis / 1000.0
        if request.timeout_seconds is not None and abs(request.timeout_seconds - timeout_seconds) > 0.001:
            raise HTTPException(status_code=400, detail="timeout_seconds conflicts with timeout_millis")
        request.timeout_seconds = timeout_seconds
    try:
        verify_request_fingerprint(request)
    except CanonicalFingerprintMismatch as error:
        raise HTTPException(status_code=409, detail=str(error)) from error
    except CanonicalSpecError as error:
        raise HTTPException(status_code=400, detail=str(error)) from error
    task_id = str(uuid.uuid4())
    task = Task(task_id=task_id, status=TaskStatus.QUEUED, request=request)
    try:
        decision = task_store.create(task)
    except OperationConflictError as error:
        raise HTTPException(status_code=409, detail=str(error)) from error
    except ValueError as error:
        raise HTTPException(status_code=400, detail=str(error)) from error
    if not decision.existing:
        await task_queue.put(decision.task.task_id)
    return CreateTaskResponse(
        task_id=decision.task.task_id,
        status=decision.task.status,
        existing=decision.existing,
        request_fingerprint=decision.task.request_fingerprint,
    )


@app.get("/tasks/{task_id}", response_model=Task)
async def get_task(task_id: str):
    task = task_store.get(task_id)
    if task is None:
        raise HTTPException(status_code=404, detail="Task not found")
    return task


@app.get("/operations/{operation_id}", response_model=OperationLookupResponse)
async def get_task_by_operation_id(operation_id: str):
    task = task_store.get_by_operation_id(operation_id)
    if task is None:
        return OperationLookupResponse(found=False)
    return OperationLookupResponse(
        found=True,
        task_id=task.task_id,
        status=task.status,
        request_fingerprint=task.request_fingerprint,
    )


@app.get("/tasks/{task_id}/result", response_model=ExecuteResult)
async def get_task_result(task_id: str):
    task = task_store.get(task_id)
    if task is None:
        raise HTTPException(status_code=404, detail="Task not found")
    if task.status != TaskStatus.SUCCEEDED:
        if task.status == TaskStatus.FAILED:
            if task.result is not None:
                return task.result
            raise HTTPException(status_code=400, detail=f"Task failed: {task.error}")
        raise HTTPException(status_code=409, detail=f"Task not finished. Status: {task.status}")
    return task.result
