from __future__ import annotations

import asyncio
import logging
import uuid
from contextlib import asynccontextmanager
from datetime import datetime
from typing import Dict

from fastapi import FastAPI, HTTPException

from .config import load_config
from .models import CreateTaskResponse, ExecuteRequest, ExecuteResult, Task, TaskStatus
from .pool_scheduler import ContainerPoolScheduler
from .sandbox_runner import run_in_sandbox

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

# In-memory storage
tasks: Dict[str, Task] = {}
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
    task.status = TaskStatus.RUNNING
    task.started_at = datetime.utcnow()
    queued_ms = int((task.started_at - task.created_at).total_seconds() * 1000)
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
            )
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
        )
        task.status = TaskStatus.SUCCEEDED
    except Exception as e:
        logger.error("Task %s failed: %s", task.task_id, e)
        task.error = str(e)
        task.status = TaskStatus.FAILED
    finally:
        task.finished_at = datetime.utcnow()
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
            timings.get("total_runner_ms", "-"),
            timings.get("total_duration_ms", "-"),
            container_recycled,
            recycle_reason or "none",
        )


@asynccontextmanager
async def lifespan(app: FastAPI):
    global pool
    worker_count = max(1, config.max_concurrency)

    # Create container pool if enabled
    if config.pool_enabled:
        pool = ContainerPoolScheduler(config)
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
    task_id = str(uuid.uuid4())
    task = Task(task_id=task_id, status=TaskStatus.QUEUED, request=request)
    tasks[task_id] = task
    await task_queue.put(task_id)
    return CreateTaskResponse(task_id=task_id, status=task.status)


@app.get("/tasks/{task_id}", response_model=Task)
async def get_task(task_id: str):
    if task_id not in tasks:
        raise HTTPException(status_code=404, detail="Task not found")
    return tasks[task_id]


@app.get("/tasks/{task_id}/result", response_model=ExecuteResult)
async def get_task_result(task_id: str):
    if task_id not in tasks:
        raise HTTPException(status_code=404, detail="Task not found")
    task = tasks[task_id]
    if task.status != TaskStatus.SUCCEEDED:
        if task.status == TaskStatus.FAILED:
            raise HTTPException(status_code=400, detail=f"Task failed: {task.error}")
        raise HTTPException(status_code=409, detail=f"Task not finished. Status: {task.status}")
    return task.result
