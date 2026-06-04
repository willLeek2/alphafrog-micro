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
from .sandbox_runner import create_pool, run_in_sandbox

# Setup logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

config = load_config()

# In-memory storage
tasks: Dict[str, Task] = {}
task_queue: asyncio.Queue = asyncio.Queue()

# Container pool (created in lifespan)
pool = None


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
    logger.info("Processing task %s worker=%s queued_ms=%s", task.task_id, worker_id, queued_ms)

    try:
        # Run synchronous sandbox runner in thread pool
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
            pool,
        )
        task.result = ExecuteResult(
            exit_code=result_dict["exit_code"],
            stdout=result_dict["stdout"],
            stderr=result_dict["stderr"],
            dataset_dir=result_dict["dataset_dir"],
        )
        task.status = TaskStatus.SUCCEEDED
    except Exception as e:
        logger.error(f"Task {task.task_id} failed: {e}")
        task.error = str(e)
        task.status = TaskStatus.FAILED
    finally:
        task.finished_at = datetime.utcnow()
        duration_ms = int((task.finished_at - task.started_at).total_seconds() * 1000)
        logger.info(
            "Finished task %s worker=%s status=%s duration_ms=%s",
            task.task_id,
            worker_id,
            task.status,
            duration_ms,
        )


@asynccontextmanager
async def lifespan(app: FastAPI):
    global pool
    worker_count = max(1, config.max_concurrency)

    # Create container pool if enabled
    if config.pool_enabled:
        pool = create_pool(config)
        logger.info("Container pool initialized for sandbox runner")
    else:
        logger.info("Container pool disabled, using fresh containers per task")

    worker_tasks = [asyncio.create_task(worker(i + 1)) for i in range(worker_count)]
    yield

    # Shutdown workers
    for worker_task in worker_tasks:
        worker_task.cancel()
    await asyncio.gather(*worker_tasks, return_exceptions=True)

    # Close pool
    if pool is not None:
        logger.info("Closing container pool...")
        pool.close()
        pool = None


app = FastAPI(title="alphafrog-python-sandbox", version="0.2.0", lifespan=lifespan)


@app.get("/health")
async def health() -> dict:
    return {"status": "ok", "pool_enabled": config.pool_enabled}


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
