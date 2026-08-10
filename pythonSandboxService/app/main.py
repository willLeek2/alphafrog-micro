from __future__ import annotations

import asyncio
import logging
import math
import uuid
from contextlib import asynccontextmanager
from datetime import datetime
from typing import Dict

from fastapi import FastAPI, HTTPException, Request
from fastapi.exception_handlers import (
    request_validation_exception_handler as _fastapi_422_handler,
)
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from .canonical_fingerprint import (
    CanonicalFingerprintMismatch,
    CanonicalSpecError,
    verify_request_fingerprint,
)
from .config import load_config
from .cancel_registry import registry as cancel_registry, shutdown_marker_write_pool
from .models import (
    CancelOutcome,
    CancelTaskRequest,
    CancelTaskResponse,
    CancellationEvidence,
    CreateTaskResponse,
    EffectiveOutputLimits,
    ExecuteRequest,
    ExecuteResult,
    ExecutionEnvironment,
    FinanceRecordChannel,
    OperationLookupResponse,
    SandboxResourceUsage,
    Task,
    TaskStatus,
)
from .nacos_config import DynamicSandboxConfig, start_nacos_listener
from .pool_scheduler import (
    ContainerPoolScheduler,
    SandboxTaskCanceledBeforeStart,
)
from .retry_classification import classify_terminal_retryable
from .sandbox_runner import run_in_sandbox
from .task_store import (
    CancelRequestBindingError,
    CompletionCandidate,
    DurableTaskStore,
    OperationConflictError,
    build_canceled_result,
)

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
# D13 (26Q3): BOUNDED acceptance queue. create_task rejects 503 BEFORE
# persisting when the queue is full, making capacity exhaustion
# machine-observable (frozen D13 category OVERLOADED_OR_UNAVAILABLE →
# Gateway maps purely by downstream HTTP status). Size comes from
# AF_SANDBOX_QUEUE_MAX_SIZE (default 128, config.queue_max_size).
task_queue: asyncio.Queue = asyncio.Queue(maxsize=config.queue_max_size)

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


def _attach_finance_record_channel(result: ExecuteResult, channel, model_cls=None) -> ExecuteResult:
    """Merge-safe §5.1 attach of the captured finance_record_channel.

    The frozen consumer DTO field is declared by work package D (models.py
    NOTE, owner split msg f4341b21); until it lands at owner merge the fully
    VALIDATED channel is simply not attached, keeping the C write path
    tolerant of the field's absence.  Once D's field exists on ExecuteResult
    this attaches without any further change here.

    Real-DTO branch (model_cls=None): ``model_copy(update=...)`` does NOT
    validate/coerce, so the channel is explicitly validated against D's frozen
    ``FinanceRecordChannel`` first — a malformed payload raises (fail-closed)
    instead of bypassing the DTO as a raw dict.
    """
    if channel is None:
        return result
    cls = model_cls if model_cls is not None else ExecuteResult
    if "finance_record_channel" not in getattr(cls, "model_fields", {}):
        return result
    if model_cls is None:
        channel = FinanceRecordChannel.model_validate(channel)
    return result.model_copy(update={"finance_record_channel": channel})


def _safe_parse_execution_environment(payload):
    """260808-finance-methodspec-v5 work package D: best-effort parse.

    Returns a validated ExecutionEnvironment when the sandbox runner supplied
    a payload, None when the payload is absent (queue timeout, exception
    before collection) or malformed (validation failure logged at WARNING).
    Presence-aware consumers translate None into proto parent absence, which
    is the v5 signal for "old producer / environment facts unknown".
    """
    if not payload:
        return None
    try:
        return ExecutionEnvironment.model_validate(payload)
    except Exception as exc:
        logger.warning(
            "EXECUTION_ENVIRONMENT_VALIDATION_FAILED error=%s payload=%s",
            exc, payload,
        )
        return None


async def process_task(task: Task, worker_id: int):
    # 260809-26Q3-stage1-w2 D11 (task #108): this task's cancel handle exists
    # before any other processing, so a stop request that arrives at ANY
    # moment (pool Future not built yet / job enqueued / child running) can
    # be delivered by the registry; it is removed only after the terminal
    # state is persisted.  register() is idempotent, so a handle created by
    # an earlier interleaving is reused, never replaced.
    cancel_registry.register(task.task_id)
    try:
        await _process_task_inner(task, worker_id)
    finally:
        cancel_registry.unregister(task.task_id)


async def _process_task_inner(task: Task, worker_id: int):
    started_at = datetime.utcnow()
    queued_ms = int((started_at - task.created_at).total_seconds() * 1000)
    if queued_ms > int(config.queue_wait_timeout_seconds * 1000):
        resource_usage = SandboxResourceUsage(
            resource_class=task.request.resource_class,
            queue_wait_millis=queued_ms,
            exit_reason="QUEUE_TIMEOUT",
            attribution_complete=False,
            missing_fields=[
                "cpuMillis",
                "memoryPeakBytes",
                "logicalBytesScanned",
                "prepareMillis",
                "executionWallMillis",
                "cleanupMillis",
                "datasetOpenCount",
            ],
        )
        result = ExecuteResult(
            exit_code=-1,
            stdout="",
            stderr="sandbox queue wait timeout",
            dataset_dir=f"{config.workdir}/input/{task.request.dataset_id}",
            resource_usage=resource_usage,
            retryable=classify_terminal_retryable(
                status=TaskStatus.FAILED,
                exit_code=-1,
                resource_usage=resource_usage,
            ),
            # 260808-finance-methodspec-v5 work package D: queue timeout runs
            # before sandbox opens, so no execution_environment is available;
            # presence-aware consumers see hasExecutionEnvironment() == false.
            execution_environment=None,
        )
        # D11: the terminal state is persisted through the same
        # complete_execution gate as every other outcome — if a cancel
        # already terminalized the task in the store lock, the CANCELED
        # state wins and this timeout result is dropped (d6841a2e rule 3).
        # No TASK_COMPLETED log on this branch (pre-D11 behavior kept).
        task_store.complete_execution(
            task.task_id,
            CompletionCandidate(
                status=TaskStatus.FAILED,
                result=result,
                evidence=CancellationEvidence.NONE,
                error="sandbox queue wait timeout",
            ),
        )
        return
    # D11 (task #108, codex c6c49248 review): begin_execution returns a
    # deep copy snapshot so the execution path reads a stable frozen task
    # and cannot accidentally overwrite a cancel terminal-state through a
    # stray save().  None means the task is no longer QUEUED inside the
    # store lock — a cancel terminalized it (QUEUED_CANCEL) and the worker
    # must not touch it.
    task_id = task.task_id
    task = task_store.begin_execution(task_id)
    if task is None:
        logger.info(
            "TASK_CANCELED_BEFORE_START task=%s worker=%s queued_ms=%s",
            task_id, worker_id, queued_ms,
        )
        return
    logger.info(
        "TASK_START task=%s worker=%s queued_ms=%s dataset=%s pool_enabled=%s",
        task.task_id, worker_id, queued_ms,
        task.request.dataset_id, config.pool_enabled,
    )

    result_dict: dict = {}
    # §7.2/§13: execution reads ONLY the snapshot frozen at create_task; the
    # hot dynamic config is never re-read mid-run.
    frozen_limits = (
        task.effective_output_limits.model_dump()
        if task.effective_output_limits is not None
        else None
    )
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
                effective_output_limits=frozen_limits,
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
                effective_output_limits=frozen_limits,
            )
        usage_payload = result_dict.get("resource_usage")
        resource_usage = None
        if usage_payload:
            resource_usage = SandboxResourceUsage.model_validate(usage_payload)
        status = (
            TaskStatus.SUCCEEDED
            if result_dict["exit_code"] == 0
            else TaskStatus.FAILED
        )
        result = ExecuteResult(
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
            resource_usage=resource_usage,
            retryable=classify_terminal_retryable(
                status=status,
                exit_code=result_dict["exit_code"],
                resource_usage=resource_usage,
            ),
            # 260808-finance-methodspec-v5 work package D: same ExecutionEnvironment
            # instance that drove the workdir file is surfaced here on the HTTP
            # ExecuteResult; gateway presence-aware mapping then sets the proto
            # executionEnvironment parent when this is non-None.
            execution_environment=_safe_parse_execution_environment(
                result_dict.get("execution_environment"),
            ),
        )
        # §5.1: attach the validated channel from the §7.1 write path (the
        # attach is merge-safe: it no-ops until D's frozen DTO field lands).
        result = _attach_finance_record_channel(
            result, result_dict.get("finance_record_channel")
        )
        # D11 (task #108): MARKER_OBSERVED is the ONLY evidence that may turn
        # a genuinely completed run into CANCELED — it means the wrapper saw
        # the cancel marker and, because of it, killed its own child process
        # group (d6841a2e rule 2).  Without that observation the genuine
        # SUCCEEDED/FAILED result stands even when a cancel was requested
        # (rules 3+4: the child may have finished before the stop landed).
        evidence = (
            CancellationEvidence.MARKER_OBSERVED
            if result_dict.get("cancel_observed")
            else CancellationEvidence.NONE
        )
        candidate = CompletionCandidate(
            status=status,
            result=result,
            evidence=evidence,
            error=(
                f"sandbox exited with code {result_dict['exit_code']}"
                if status == TaskStatus.FAILED
                else None
            ),
        )
    except SandboxTaskCanceledBeforeStart as canceled_exc:
        # D11 (task #108): the pool Future was canceled BEFORE any container
        # worker started this job — observed cancellation evidence (d6841a2e
        # rule 2).  complete_execution turns it into the CANCELED terminal
        # state with the shared honest canceled result.
        logger.info(
            "Task %s canceled before pool execution started: %s",
            task.task_id, canceled_exc,
        )
        candidate = CompletionCandidate(
            status=TaskStatus.FAILED,
            result=build_canceled_result(task.request),
            evidence=CancellationEvidence.CANCELED_BEFORE_START,
            error=str(canceled_exc),
        )
    except Exception as e:
        logger.error("Task %s failed: %s", task.task_id, e)
        usage_payload = getattr(e, "resource_usage", None)
        if usage_payload:
            resource_usage = SandboxResourceUsage.model_validate(usage_payload)
        else:
            resource_usage = SandboxResourceUsage(
                resource_class=task.request.resource_class,
                queue_wait_millis=queued_ms,
                exit_reason="EXECUTION_ERROR",
                attribution_complete=False,
                missing_fields=[
                    "cpuMillis",
                    "memoryPeakBytes",
                    "logicalBytesScanned",
                    "prepareMillis",
                    "executionWallMillis",
                    "cleanupMillis",
                    "datasetOpenCount",
                ],
            )
        result = ExecuteResult(
            exit_code=-1,
            stdout="",
            stderr=str(e),
            dataset_dir=f"{config.workdir}/input/{task.request.dataset_id}",
            artifacts={"timings": getattr(e, "timings", {})},
            resource_usage=resource_usage,
            retryable=classify_terminal_retryable(
                status=TaskStatus.FAILED,
                exit_code=-1,
                resource_usage=resource_usage,
            ),
            # 260808-finance-methodspec-v5 work package D: execution_environment
            # may be missing or partially populated if the exception happened
            # before/around runtime_environment collection. _safe_parse handles
            # both cases; None propagates as proto parent absence.
            # codex 529a823f (#97 owner additive): when the runner raised, the
            # environment rides the EXCEPTION attribute (baked, or post-install
            # once install+recollect+push all succeeded) while result_dict is
            # still empty — the attribute wins; result_dict is only a fallback.
            execution_environment=_safe_parse_execution_environment(
                getattr(e, "execution_environment", None)
                if getattr(e, "execution_environment", None) is not None
                else result_dict.get("execution_environment"),
            ),
        )
        # D11 (task #108): a runner exception is genuine failure evidence,
        # never cancellation evidence — even with cancel_requested set, a
        # crash is not an observed stop (d6841a2e rule 4).
        candidate = CompletionCandidate(
            status=TaskStatus.FAILED,
            result=result,
            evidence=CancellationEvidence.NONE,
            error=str(e),
        )
    final_task = task_store.complete_execution(task.task_id, candidate)
    duration_ms = int(
        (final_task.finished_at - final_task.started_at).total_seconds() * 1000
    )

    timings = result_dict.get("timings", {})
    container_recycled = result_dict.get("container_recycled", False)
    recycle_reason = result_dict.get("recycle_reason")
    container_id = result_dict.get("container_id", "-")
    container_create_ms = timings.get("container_create_ms")
    if container_create_ms is None:
        container_create_ms = "n/a" if config.pool_enabled else "-"

    if final_task.status == TaskStatus.SUCCEEDED:
        status_label = "SUCCESS"
    elif final_task.status == TaskStatus.CANCELED:
        status_label = "CANCELED"
    else:
        status_label = "FAILED"
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

    # D13 (26Q3): the acceptance queue is bounded (config.queue_max_size).
    # Enqueueing recovered tasks BEFORE the workers start would deadlock
    # startup whenever the recovered backlog exceeds the queue capacity, so
    # recovery IDs are materialized here and only enqueued AFTER the workers
    # exist (workers drain the queue while the backlog is being put).
    recovered_task_ids = list(task_store.recover_after_restart())

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
    # D13 (26Q3): enqueue recovered tasks only after the workers exist so a
    # backlog larger than the bounded queue cannot deadlock startup.
    for recovered_task_id in recovered_task_ids:
        await task_queue.put(recovered_task_id)
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

    # D11 (task #108): stop accepting cancel-marker writes.  wait=False:
    # marker writes are best-effort by contract and must never hang shutdown.
    shutdown_marker_write_pool()


app = FastAPI(title="alphafrog-python-sandbox", version="0.3.0", lifespan=lifespan)


@app.exception_handler(Exception)
async def unhandled_exception_handler(request: Request, exc: Exception):
    # D13 (26Q3, ccqwen 1f4e16d4 #5): every unhandled exception must surface
    # as a JSON 500 (→ Gateway DOWNSTREAM_FAILURE). This eliminates the
    # non-JSON bare 500s that previously escaped straight out of the stack
    # (e.g. task_store RuntimeError/OSError paths). HTTPException keeps its
    # own dedicated handler, so the typed status codes raised by the
    # endpoints above are unaffected.
    logger.error(
        "UNHANDLED_EXCEPTION method=%s path=%s error=%r",
        request.method,
        request.url.path,
        exc,
    )
    return JSONResponse(
        status_code=500,
        content={"detail": f"internal error: {type(exc).__name__}: {exc}"},
    )


@app.exception_handler(RequestValidationError)
async def cancel_validation_handler(request: Request, exc: RequestValidationError):
    # D11 (task #108, codex c6c49248 review): FastAPI's default 422 for a
    # request-body type/shape defect is NOT in the frozen D13 status vocabulary
    # (only 400 / 409 / 500 / 503).  Every body defect on the /tasks/cancel
    # route must answer 400 → Gateway INVALID_ARGUMENT, which is what the
    # endpoint already does for the business-rule validations inside the
    # handler body.  Other routes keep their builtin 422 behavior untouched
    # (this handler is deliberately route-scoped, not global).
    if request.url.path == "/tasks/cancel":
        return JSONResponse(
            status_code=400,
            content={"detail": "invalid request body"},
        )
    # Other routes: explicitly delegate back to FastAPI's builtin 422
    # handler.  Raising the exception would instead land in the generic
    # unhandled_exception_handler above → an incorrect 500.
    return await _fastapi_422_handler(request, exc)


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
    # D14 (Q-14): production refuse create without operation_id. Non-empty is
    # judged AFTER strip; empty / all-whitespace are rejected and never
    # auto-generated. Non-production fixtures must set
    # AF_SANDBOX_ALLOW_CREATE_WITHOUT_OPERATION_ID=true (no operation index /
    # no idempotent recovery). The switch only admits keyless creates —
    # keyed creates still run fingerprint/units/memory validation below.
    operation_id = (request.operation_id or "").strip()
    if not operation_id and not config.allow_create_without_operation_id:
        raise HTTPException(
            status_code=400,
            detail=(
                "operation_id is required for sandbox create "
                "(D14 production refuse create without idempotency key; "
                "set AF_SANDBOX_ALLOW_CREATE_WITHOUT_OPERATION_ID=true only for "
                "explicit non-production fixtures — no global capacity admission, "
                "no idempotent recovery)"
            ),
        )
    if operation_id:
        request.operation_id = operation_id
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
    # D13 (26Q3, Cindy 91490076 MUST-FIX 3 execution-entry side + Cindy
    # 8e21955c/6a6e6158 + codex 5457b713 MUST-FIX 2): after BOTH legacy
    # timeout_seconds and canonical timeout_millis are consistency-normalized
    # above, RESOLVE AND FREEZE the FINAL effective timeout, then validate it
    # ONCE: `0 < effective <= max`.
    #
    # MF2 freeze: when neither timeout_seconds nor timeout_millis is
    # supplied, the execution path (run_in_sandbox / pool.run_task) falls
    # back to config.execution_timeout_seconds — that configured default IS
    # the final effective timeout. It is frozen into the persisted request
    # HERE (same mutation precedent as the millis→seconds normalization
    # above), so execution never re-derives an unvalidated value at run
    # time and an idempotent re-create returns the original frozen task.
    # load_config already rejects a configured default that exceeds the
    # ceiling (fail-fast at startup); the gate below is defense in depth
    # for both branches. The fingerprint is unaffected: the canonical spec
    # binds timeout_millis (required for keyed creates), never
    # timeout_seconds.
    #
    # Rejection threshold is `effective > max` — the Gateway long-read
    # margin is NOT part of this business limit. A Pydantic field constraint
    # is deliberately NOT used: canonical timeout_millis is normalized into
    # timeout_seconds AFTER field validation, so field revalidation may
    # never fire (Cindy 8e21955c). The seconds-domain float comparison has
    # no integer-truncation hole: millis → seconds is exact IEEE-754
    # division (e.g. 1800.0009s > 1800.0 is rejected); NaN/Infinity/
    # negative/zero fail closed via math.isfinite + the chained comparison.
    # max_task_timeout_seconds (AF_SANDBOX_MAX_TASK_TIMEOUT_SECONDS, default
    # 1800s = 30min) is lock-step aligned with the Gateway-side key
    # `sandbox.service.max-task-timeout-millis` (default 1800000, ccmax
    # a1687d2f); release config binds both ends through the canonical
    # companion AF_SANDBOX_MAX_TASK_TIMEOUT_MILLIS with fail-fast
    # equivalence checking in load_config (codex 5457b713 MUST-FIX 1).
    if request.timeout_seconds is None:
        request.timeout_seconds = config.execution_timeout_seconds
    if not math.isfinite(request.timeout_seconds) or not (
        0 < request.timeout_seconds <= config.max_task_timeout_seconds
    ):
        raise HTTPException(
            status_code=400,
            detail=(
                "effective task timeout must satisfy 0 < effective <= "
                f"{config.max_task_timeout_seconds} seconds; got "
                f"timeout_seconds={request.timeout_seconds!r}, "
                f"timeout_millis={request.timeout_millis!r}"
            ),
        )
    try:
        verify_request_fingerprint(request)
    except CanonicalFingerprintMismatch as error:
        # D13 (26Q3, ccqwen 1f4e16d4 #1): a declared request_fingerprint that
        # does not match the recomputed canonical fingerprint is
        # SELF-CONTRADICTORY CLIENT DATA → 400 → Gateway INVALID_ARGUMENT
        # (was 409). It is NOT a state conflict; a genuine conflict
        # (operation_id already bound to another fingerprint/payload) stays
        # 409 → CONFLICT at the store layer below.
        raise HTTPException(status_code=400, detail=str(error)) from error
    except CanonicalSpecError as error:
        raise HTTPException(status_code=400, detail=str(error)) from error
    # §7.2/§13: freeze the output-limit snapshot at creation time.  An
    # idempotent re-create returns the ORIGINAL task from the store, so the
    # original snapshot is kept untouched by any later Nacos update.
    # §7.1 (codex b5a92810, C/H seam): freeze the validated image reference
    # the task will run on.  Set ONCE here: an idempotent re-create returns
    # the ORIGINAL task (original ref kept), execution never re-reads hot
    # config, and a later image change only affects NEW tasks.
    # D11 (task #108, v4-3): both values are frozen BEFORE any store consult
    # so a pre-create tombstone adoption fills exactly the same snapshot an
    # ordinary create would have used.
    frozen_limits = EffectiveOutputLimits(
        **dynamic_config.output_limits_snapshot()
    )
    frozen_image_ref = config.sandbox_image
    # D11 (task #108, v4-4 re-check #1): the authoritative store consult
    # BEFORE the capacity check.  An idempotent replay and a pre-create
    # tombstone adoption need no queue slot, so they must never be rejected
    # by a full queue (cancellation cannot be bypassed by racing capacity);
    # None means the operation is still unknown and a fresh create proceeds.
    try:
        early_decision = task_store.find_existing_or_adopt_tombstone(
            request, frozen_limits, frozen_image_ref
        )
    except OperationConflictError as error:
        raise HTTPException(status_code=409, detail=str(error)) from error
    except ValueError as error:
        raise HTTPException(status_code=400, detail=str(error)) from error
    if early_decision is not None:
        return CreateTaskResponse(
            task_id=early_decision.task.task_id,
            status=early_decision.task.status,
            existing=early_decision.existing,
            request_fingerprint=early_decision.task.request_fingerprint,
        )
    task_id = str(uuid.uuid4())
    task = Task(
        task_id=task_id,
        status=TaskStatus.QUEUED,
        request=request,
        effective_output_limits=frozen_limits,
        runtime_image_ref=frozen_image_ref,
    )
    # D13 (26Q3, ccqwen 1f4e16d4 #4): bounded acceptance queue. Reject
    # BEFORE persisting when the queue is already full so no task is ever
    # stored without a queue entry (no orphan). 503 → frozen category
    # OVERLOADED_OR_UNAVAILABLE.
    # D11 (task #108, v4-4 re-check #2): a by_operation cancel may have
    # created a tombstone for this operation between re-check #1 and now —
    # consult the store once more before rejecting, because adopting a
    # tombstone needs no queue slot.  Only a genuinely unknown operation is
    # rejected with 503 while the queue is full.
    if task_queue.full():
        try:
            full_decision = task_store.find_existing_or_adopt_tombstone(
                request, frozen_limits, frozen_image_ref
            )
        except OperationConflictError as error:
            raise HTTPException(status_code=409, detail=str(error)) from error
        except ValueError as error:
            raise HTTPException(status_code=400, detail=str(error)) from error
        if full_decision is not None:
            return CreateTaskResponse(
                task_id=full_decision.task.task_id,
                status=full_decision.task.status,
                existing=full_decision.existing,
                request_fingerprint=full_decision.task.request_fingerprint,
            )
        raise HTTPException(
            status_code=503,
            detail="sandbox task queue is full; retry later",
        )
    # D11 / codex 4334bc9d constraint 1 (v4-4 re-check #3): the final dedup
    # re-check, the insert, the persist AND the queue admission share ONE
    # store critical section.  When the queue filled between full() above
    # and the admission, the store rolls the just-written records back under
    # the SAME lock before the QueueFull propagates — a rejected create
    # leaves no durable trace, so the 503 below is honest.  (Crash boundary:
    # if the process dies after the persist but before the enqueue returns,
    # the task stays QUEUED on disk and recover_after_restart re-enqueues it
    # on the next startup — the documented honest resolution.)
    try:
        decision = task_store.create_with_admission(
            task, admission=lambda: task_queue.put_nowait(task.task_id)
        )
    except OperationConflictError as error:
        raise HTTPException(status_code=409, detail=str(error)) from error
    except ValueError as error:
        raise HTTPException(status_code=400, detail=str(error)) from error
    except asyncio.QueueFull as error:
        raise HTTPException(
            status_code=503,
            detail="sandbox task queue is full; retry later",
        ) from error
    return CreateTaskResponse(
        task_id=decision.task.task_id,
        status=decision.task.status,
        existing=decision.existing,
        request_fingerprint=decision.task.request_fingerprint,
    )


# === 260809-26Q3-stage1-w2 D11 (task #108): POST /tasks/cancel ============
# HTTP mirror of the frozen proto cancelTask RPC (pythonSandbox.proto, codex
# a3aee2ad v3).  Business outcomes answer 200 + body — including the
# business NOT_FOUND (an authoritative "this taskId never existed here");
# failure outcomes follow the D13 convention and are expressed purely by
# HTTP status (400 INVALID_ARGUMENT / 409 CONFLICT), because this service
# has no errorDetail body field and the Gateway maps by status code.
@app.post("/tasks/cancel", response_model=CancelTaskResponse)
async def cancel_task(request: CancelTaskRequest):
    # Deliberate endpoint-side validation (the pydantic model is loose on
    # purpose): the frozen D13 status vocabulary has no 422, so every body
    # defect must answer a plain 400.  proto3 oneof only gives compile-time
    # mutual exclusion; these runtime checks are the service-side equivalent
    # (codex a3aee2ad section 二).
    cancel_request_id = (request.cancel_request_id or "").strip()
    if not cancel_request_id:
        raise HTTPException(
            status_code=400, detail="cancel_request_id must not be empty"
        )
    has_by_task_id = request.by_task_id is not None
    has_by_operation = request.by_operation is not None
    if has_by_task_id == has_by_operation:
        raise HTTPException(
            status_code=400,
            detail="exactly one of by_task_id or by_operation must be set",
        )
    reason = (request.reason or "").strip()
    if has_by_task_id:
        target_task_id = (request.by_task_id.task_id or "").strip()
        if not target_task_id:
            raise HTTPException(
                status_code=400, detail="by_task_id.task_id must not be empty"
            )
        try:
            decision = task_store.cancel_by_task_id(
                cancel_request_id, target_task_id, reason
            )
        except CancelRequestBindingError as error:
            raise HTTPException(status_code=409, detail=str(error)) from error
    else:
        operation_id = (request.by_operation.operation_id or "").strip()
        fingerprint = (request.by_operation.request_fingerprint or "").strip()
        if not operation_id or not fingerprint:
            raise HTTPException(
                status_code=400,
                detail=(
                    "by_operation.operation_id and"
                    " by_operation.request_fingerprint must not be empty"
                ),
            )
        try:
            decision = task_store.cancel_by_operation(
                cancel_request_id, operation_id, fingerprint, reason
            )
        except OperationConflictError as error:
            # Same operationId, different fingerprint (codex a3aee2ad
            # section 四末: the by_operation path verifies identity before
            # answering) → UNSPECIFIED outcome + CONFLICT category → 409.
            raise HTTPException(status_code=409, detail=str(error)) from error
        except CancelRequestBindingError as error:
            raise HTTPException(status_code=409, detail=str(error)) from error
        except ValueError as error:
            raise HTTPException(status_code=400, detail=str(error)) from error
    # The actual stop signal for a RUNNING task travels the cancel registry
    # OUTSIDE the store lock: pool Future cancellation / cancel-marker write
    # are best-effort deliveries, and the durable CANCELED terminal state is
    # only ever written by complete_execution once the execution layer
    # reports real observed evidence (d6841a2e rules 2+4).  A repeated or
    # replayed CANCEL_INTENT_RECORDED re-requests idempotently (the handle
    # deduplicates).
    if (
        decision.outcome == CancelOutcome.CANCEL_INTENT_RECORDED.value
        and decision.task_id
    ):
        stop_newly_requested = cancel_registry.request_stop(decision.task_id)
        logger.info(
            "TASK_CANCEL_STOP task=%s newly_requested=%s outcome=%s reason=%s",
            decision.task_id,
            stop_newly_requested,
            decision.outcome,
            reason or "none",
        )
    return CancelTaskResponse(
        outcome=CancelOutcome(decision.outcome),
        task_id=decision.task_id,
        status=decision.status,
        error=None,
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
        if task.status in {TaskStatus.FAILED, TaskStatus.CANCELED}:
            if task.result is not None:
                return task.result
            terminal_status = task.status.value.lower()
            # D13 (26Q3, ccqwen 1f4e16d4 #3): a TERMINAL task without a
            # persisted result is an execution-entry INTERNAL failure (e.g.
            # restart-recovery gap), not a client argument defect → JSON 500
            # → Gateway DOWNSTREAM_FAILURE (was bare 400, which the Gateway
            # would mis-map to INVALID_ARGUMENT).
            raise HTTPException(
                status_code=500,
                detail=f"Task {terminal_status} without result: {task.error}",
            )
        # D13 (26Q3, ccqwen 1f4e16d4 #2): task not finished yet. 425 Too
        # Early is an UNKNOWN 4xx to the Gateway mapping → UNSPECIFIED with
        # the downstream status preserved → Gateway fails closed and keeps
        # polling, instead of misreading a live task as a terminal 409
        # CONFLICT (the pre-D13 behavior).
        raise HTTPException(
            status_code=425,
            detail=f"Task not finished. Status: {task.status}",
        )
    return task.result
