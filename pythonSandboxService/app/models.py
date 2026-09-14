from __future__ import annotations

from datetime import datetime
from enum import Enum
from typing import List, Optional

from pydantic import BaseModel, Field, model_validator

# D15 §4.2.3 round-4 (codex 56976668 MUST-FIX #3): single payload contract
# shared with bounded_exec_wrapper.parse_wrapper_input. Imported at top
# level — payload_contract.py is stdlib-only so this does NOT drag pydantic
# into the wrapper's import graph (the wrapper imports payload_contract
# directly, not via this module).
from app.payload_contract import (
    PayloadContractError,
    validate_payload_contract,
)


class TaskStatus(str, Enum):
    QUEUED = "QUEUED"
    RUNNING = "RUNNING"
    SUCCEEDED = "SUCCEEDED"
    FAILED = "FAILED"
    CANCELED = "CANCELED"


# 260809-26Q3-stage1-w2 D11 (task #108): how a CANCELED terminal state was
# evidenced.  Frozen by codex d6841a2e's four rules: a cancellation is only
# real when the execution layer OBSERVED it — a stop request or an issued
# kill alone is never enough (rule 4), and a child that finished before the
# stop took effect keeps its genuine SUCCEEDED/FAILED result (rule 3).
class CancellationEvidence(str, Enum):
    NONE = "none"
    # by_operation cancel arrived BEFORE any create was persisted: tombstone
    # task (schema v3), no request ever existed, nothing ever ran.
    PRE_CREATE_CANCEL = "pre_create_cancel"
    # cancel arrived while the task was QUEUED: terminalized inside the store
    # lock before any worker started it — nothing ever ran.
    QUEUED_CANCEL = "queued_cancel"
    # cancel arrived after dispatch but execution never started (the pool
    # Future was canceled before a container worker picked the job up).
    CANCELED_BEFORE_START = "canceled_before_start"
    # the bounded wrapper OBSERVED the cancel marker (owned by the
    # container's unprivileged user — deletable by the same-uid user child,
    # the cancel-resistance trade-off frog accepted 2026-08-18) and, because
    # of it, killed its own child process group — the only evidence that
    # justifies CANCELED for a task whose child was actually running.
    MARKER_OBSERVED = "marker_observed"


class ExecuteRequest(BaseModel):
    dataset_id: str = Field(..., description="Dataset identifier")
    dataset_ids: Optional[List[str]] = Field(
        default=None, description="Additional dataset identifiers to mount"
    )
    code: str = Field(..., description="Python code to execute")
    files: Optional[List[str]] = Field(
        default=None, description="Files under dataset_id to copy into sandbox"
    )
    libraries: Optional[List[str]] = Field(
        default=None, description="Python libraries to install (e.g. numpy)"
    )
    timeout_seconds: Optional[float] = Field(
        default=None, description="Execution timeout override"
    )
    # 260623-harness-optimization-02: agent run 级 dataset / manifest CSV 注入。
    # Java 端 AgentRunDatasetRegistry 生成这两份 CSV，sandbox 端负责：
    #   1. 替换 /__AF_INPUT__/ placeholder 为实际 task_input 路径
    #   2. 对 manifest_file_path = NONE 的行，物化临时 manifest.json
    #   3. 把 CSVs 写到 {workdir}/paths_dataset.csv + {workdir}/path_manifest.csv
    # 透传约定：未传等价于空字符串（Python 端按空字符串处理即可）。
    paths_dataset_csv: Optional[str] = Field(
        default=None,
        description="Agent run-level paths_dataset.csv content (with /__AF_INPUT__/ placeholder)"
    )
    path_manifest_csv: Optional[str] = Field(
        default=None,
        description="Agent run-level path_manifest.csv content (with /__AF_INPUT__/ or NONE marker)"
    )
    resource_class: str = Field(default="STANDARD", pattern="^(STANDARD|HEAVY)$")
    estimated_rows: Optional[int] = Field(default=None, ge=0)
    estimated_bytes: Optional[int] = Field(default=None, ge=0)
    file_count: Optional[int] = Field(default=None, ge=0)
    capacity_units: Optional[int] = Field(default=None, ge=1)
    operation_id: Optional[str] = None
    request_fingerprint: Optional[str] = None
    memory_limit_bytes: Optional[int] = Field(default=None, gt=0)
    timeout_millis: Optional[int] = Field(default=None, gt=0)
    runtime_environment_version: Optional[str] = None
    canonical_spec_schema_version: Optional[str] = None
    code_hash: Optional[str] = None
    immutable_dataset_snapshot_digest: Optional[str] = None
    libraries_digest: Optional[str] = None
    sandbox_options_digest: Optional[str] = None

    @model_validator(mode="after")
    def validate_idempotency_identity(self) -> "ExecuteRequest":
        if bool(self.operation_id) != bool(self.request_fingerprint):
            raise ValueError("operation_id and request_fingerprint must be provided together")
        if self.operation_id:
            required = {
                "canonical_spec_schema_version": self.canonical_spec_schema_version,
                "code_hash": self.code_hash,
                "immutable_dataset_snapshot_digest": self.immutable_dataset_snapshot_digest,
                "runtime_environment_version": self.runtime_environment_version,
                "libraries_digest": self.libraries_digest,
                "sandbox_options_digest": self.sandbox_options_digest,
            }
            missing = [name for name, value in required.items() if not value or not value.strip()]
            if missing:
                raise ValueError("canonical create spec fields are required: " + ", ".join(missing))
        return self


class SandboxResourceUsage(BaseModel):
    resource_class: str
    cpu_millis: Optional[int] = None
    memory_peak_bytes: Optional[int] = None
    memory_byte_millis: Optional[int] = None
    logical_bytes_scanned: Optional[int] = None
    artifact_bytes_written: Optional[int] = None
    temporary_bytes_written: Optional[int] = None
    queue_wait_millis: Optional[int] = None
    prepare_millis: Optional[int] = None
    execution_wall_millis: Optional[int] = None
    cleanup_millis: Optional[int] = None
    dataset_open_count: Optional[int] = None
    exit_reason: str = "UNKNOWN"
    oom_killed: bool = False
    timed_out: bool = False
    attribution_complete: bool = False
    sampling_interval_millis: Optional[int] = None
    missing_fields: List[str] = Field(default_factory=list)


# 260808-finance-methodspec-v5 work package D-owned Pydantic classes.
# 边界：ccmax D 拥有 class 定义；ccqwen C 只承载 ExecuteResult.finance_record_channel /
# execution_environment 写入路径，不重定义（按 sub-task 01 thread f4341b21 + 3cbdbaac
# 双向确认）。gateway presence-aware 映射负责 snake_case -> camelCase proto 转换，
# runtime_environment.py 单源生成 environmentId / runtime-environment.json。
# model_config: 调用方传 snake_case 字段（与现有 ExecuteRequest/ExecuteResult 风格一致），
# 不引入 alias，保持 Pydantic 内部表示 + JSON 序列化两端 snake_case 一致。
class SandboxPackageApi(BaseModel):
    name: str = Field(..., description="Package name (e.g. alphafrog_finance)")
    version: str = Field(..., description="Package version (e.g. 1.0.3)")
    api_version: str = Field(..., description="Package API version (e.g. 1.0)")


class FinanceRecordChannel(BaseModel):
    emitted_record_count: int = Field(
        default=0,
        description="Marker line count after bounded capture; 0 means no markers in this batch",
    )
    emitted_record_bytes: int = Field(
        default=0,
        description="Sum of rawPayload UTF-8 byte lengths for this batch",
    )
    record_set_complete: bool = Field(
        default=True,
        description="True iff the channel finished without dropping records; drops set record_set_complete=False",
    )
    drop_reason: str = Field(
        default="",
        description="Stable reason when record_set_complete=False; empty when complete",
    )
    record_digest: str = Field(
        default="e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        description="SHA-256 of length-prefix concatenation; empty batch -> SHA-256 of empty bytes",
    )
    stdout_truncated: bool = Field(
        default=False,
        description="True iff ordinary stdout was clipped before the bounded file",
    )
    stderr_truncated: bool = Field(
        default=False,
        description="True iff stderr was clipped before the bounded file",
    )


class ExecutionEnvironment(BaseModel):
    environment_id: str = Field(..., description="SHA-256 of the runtime environment snapshot")
    image_digest: str = Field(..., description="SHA-256 of the immutable container image")
    library_set_digest: str = Field(
        ...,
        description="SHA-256 of canonical-encoded library-set.json (sorted packages)",
    )
    package_apis: List[SandboxPackageApi] = Field(
        default_factory=list,
        description="Snapshot of installed package APIs visible to user code",
    )
    inventory_complete: bool = Field(
        default=False,
        description="True iff the inventory is comprehensive; false means unknown hidden packages",
    )


class ExecuteResult(BaseModel):
    exit_code: int
    stdout: str
    stderr: str
    dataset_dir: str
    artifacts: Optional[dict] = None
    resource_usage: Optional[SandboxResourceUsage] = None
    retryable: Optional[bool] = None
    # 260808-finance-methodspec-v5 work package D. Presence-aware:
    # None = channel not active / pre-v5; non-None = v5 enabled (including empty
    # but complete batch). Same convention applies to execution_environment.
    finance_record_channel: Optional[FinanceRecordChannel] = None
    execution_environment: Optional[ExecutionEnvironment] = None


# === work-package-C (ccqwen) ===
# Spec §7.2 / frozen contract §13: output-limit snapshot models.
# Contract §13 spells the four Python task snapshot keys VERBATIM in camelCase
# (recordChannelMaxRecords/recordChannelMaxBytes/stdoutMaxBytes/stderrMaxBytes)
# plus a source revision; the snapshot is frozen at create_task (idempotent
# create returns the original snapshot) and is the ONLY limit source execution
# may read — hot config is never re-read mid-run.
class EffectiveOutputLimits(BaseModel):
    """Frozen per-task output limit snapshot (§7.2, contract §13)."""

    stdoutMaxBytes: int = Field(..., ge=0)
    stderrMaxBytes: int = Field(..., ge=0)
    recordChannelMaxBytes: int = Field(..., ge=0)
    recordChannelMaxRecords: int = Field(..., ge=0)
    sourceRevision: str = Field(
        default="",
        description="Config generation this snapshot was frozen from",
    )


class BoundedExecRequest(BaseModel):
    """§7.1 wrapper input (wrapper-input.json) shape, camelCase keys."""

    scriptPath: str = Field(..., min_length=1)
    timeoutSeconds: float = Field(..., ge=0)
    effectiveOutputLimits: EffectiveOutputLimits
    runtimeEnvironmentPath: Optional[str] = None
    # D15 §4.2 (Scenario B) round-2 (codex fe54d9f0 MUST-FIX #3):
    # taskWorkspace + taskEnvironment + loaderPythonPath are REQUIRED. The
    # wrapper parser treats them as required (missing or empty is
    # fail-closed per D15 §4.2), so the schema MUST agree: a model that
    # could omit them while the parser rejects them would let callers
    # build payloads that fail at runtime instead of at validation time.
    # D15 is a new feature; there is no backwards-compat migration path.
    taskWorkspace: str = Field(..., min_length=1)
    taskEnvironment: dict[str, str]
    # D15 §4.2: workdir the user child needs on sys.path so it can import
    # af_dataset_loader etc. The wrapper stages a per-task bootstrap that
    # inserts this path AFTER Python site init (see bounded_exec_wrapper
    # _write_loader_bootstrap), so a stale sitecustomize in the loader
    # workdir is never auto-imported at startup.
    loaderPythonPath: str = Field(..., min_length=1)
    # 260809-26Q3-stage1-w2 D11 (task #108): the cancel marker file the
    # wrapper polls while the child runs — owned by the container's
    # unprivileged user (NOT root-protected: a same-uid user child can
    # delete it and suppress a cancel, the trade-off frog accepted
    # 2026-08-18). Optional for backward
    # compatibility with pre-D11 inputs; when present the wrapper validates
    # the EXACT task-local binding (<control_root>/<taskId>/cancel)
    # fail-closed.
    cancelMarkerPath: Optional[str] = None

    @model_validator(mode="after")
    def validate_d15_round4_payload_contract(self) -> "BoundedExecRequest":
        """D15 §4.2.3 round-4 (codex 56976668 MUST-FIX #3): pydantic-side
        mirror of the wrapper parser's payload contract. Calls the SAME
        ``validate_payload_contract`` function (single source of truth in
        ``app.payload_contract``) so a payload that passes pydantic
        cannot fail at the wrapper parser on field-level invariants.

        Filesystem-anchored checks (workspace == wrapper-input.json
        parent; scriptPath regular file; loaderPythonPath existing
        directory; ``_bootstrap`` symlink rejection) are NOT done here —
        pydantic has no filesystem context. The wrapper parser adds those
        on top when it has the wrapper-input.json path.

        Without this validator the model could construct objects the
        runtime would reject (smuggled PYTHONPATH, AF_TASK_WORKSPACE !=
        taskWorkspace, AF sub-path equal to workspace, etc.) — codex
        56976668 MUST-FIX #3 explicitly forbids that gap.
        """
        try:
            validate_payload_contract(
                self.wrapper_input_payload(), wrapper_input_path=None,
            )
        except PayloadContractError as exc:
            # pydantic's model_validator protocol: raise ValueError (or
            # AssertionError) to mark validation failure; pydantic then
            # converts it to ValidationError for the caller.
            raise ValueError(str(exc)) from exc
        return self

    def wrapper_input_payload(self) -> dict:
        """Serialize to the exact §7.1 input shape.

        The wrapper (bounded_exec_wrapper.parse_wrapper_input) requires the
        four §13 limit keys verbatim; the snapshot's sourceRevision is Task
        metadata and is NOT part of the wrapper input.
        """
        limits = self.effectiveOutputLimits.model_dump()
        limits.pop("sourceRevision", None)
        payload: dict = {
            "scriptPath": self.scriptPath,
            "timeoutSeconds": self.timeoutSeconds,
            "effectiveOutputLimits": limits,
            "taskWorkspace": self.taskWorkspace,
            "taskEnvironment": dict(self.taskEnvironment),
            "loaderPythonPath": self.loaderPythonPath,
        }
        if self.runtimeEnvironmentPath is not None:
            payload["runtimeEnvironmentPath"] = self.runtimeEnvironmentPath
        if self.cancelMarkerPath is not None:
            payload["cancelMarkerPath"] = self.cancelMarkerPath
        return payload


class BoundedExecResult(BaseModel):
    """§7.1 capture-result.json summary shape (wrapper layer, camelCase).

    These are the wrapper's own reporting fields; the frozen consumer surface
    is the §5.1 snake_case finance_record_channel built from them by
    app.finance_record_channel.finance_channel_from_capture.
    """

    exitCode: int
    ordinaryStdoutBytes: int = Field(..., ge=0)
    stderrBytes: int = Field(..., ge=0)
    stdoutTruncated: bool
    stderrTruncated: bool
    emittedRecordCount: int = Field(..., ge=0)
    emittedRecordBytes: int = Field(..., ge=0)
    recordSetComplete: bool
    dropReason: str
    recordDigest: str
    # 260809-26Q3-stage1-w2 D11 (task #108): True iff the wrapper OBSERVED
    # the cancel marker and, because of it, killed its own child process
    # group (d6841a2e rule 2). A child that finished before the marker was
    # observed keeps cancelObserved=False and its genuine result (rule 3).
    cancelObserved: bool = False
# === end work-package-C (ccqwen) ===


class Task(BaseModel):
    task_id: str
    status: TaskStatus
    # 260809-26Q3-stage1-w2 D11 (task #108, schema v3): request is None ONLY
    # for a pre-create cancel tombstone — a by_operation cancel that arrived
    # before any create payload was persisted, so there was never a request
    # to store.  The first matching create ADOPTS the tombstone and fills the
    # request (plus payload digest / frozen limits / image ref).  Every
    # non-tombstone task always carries its request; v1/v2 state files can
    # never contain a request-less task (task_store._load enforces both).
    request: Optional[ExecuteRequest] = None
    result: Optional[ExecuteResult] = None
    error: Optional[str] = None
    created_at: datetime = Field(default_factory=datetime.utcnow)
    started_at: Optional[datetime] = None
    finished_at: Optional[datetime] = None
    request_fingerprint: Optional[str] = None
    payload_digest: Optional[str] = None
    resource_usage: Optional[SandboxResourceUsage] = None
    retryable: Optional[bool] = None
    # === work-package-C (ccqwen) ===
    # §7.2/§13: the frozen output-limit snapshot, set once in create_task and
    # read-only for the whole execution (idempotent create returns the
    # original Task and snapshot). runtime_image_ref stores the digest
    # reference of the image the task runs on (H owns resolution rules; C
    # only stores). Both are backend facts, never model/user-visible.
    effective_output_limits: Optional[EffectiveOutputLimits] = None
    runtime_image_ref: Optional[str] = None
    # === end work-package-C (ccqwen) ===
    # === 260809-26Q3-stage1-w2 D11 (task #108): cancellation bookkeeping ===
    # cancellation_evidence records HOW the CANCELED terminal state was
    # evidenced (see CancellationEvidence); it stays NONE for every task that
    # was never canceled.  cancel_requested is the durable audit flag that a
    # cancel was asked for a RUNNING task (it does NOT by itself justify a
    # forced CANCELED — d6841a2e rule 4).  cancel_reason carries the
    # caller-supplied audit reason (USER_REQUEST / QUEUE_TIMEOUT /
    # RUN_CANCELED / TOOLJOB_FINALIZER), never used for routing.
    cancellation_evidence: CancellationEvidence = CancellationEvidence.NONE
    cancel_reason: Optional[str] = None
    cancel_requested: bool = False
    # === end D11 (task #108) ===


class CreateTaskResponse(BaseModel):
    task_id: str
    status: TaskStatus
    existing: bool = False
    request_fingerprint: Optional[str] = None


class OperationLookupResponse(BaseModel):
    found: bool
    task_id: Optional[str] = None
    status: Optional[TaskStatus] = None
    request_fingerprint: Optional[str] = None
    error: Optional[str] = None


# === 260809-26Q3-stage1-w2 D11 (task #108): POST /tasks/cancel API ===
# HTTP mirror of the frozen proto CancelTaskRequest/CancelTaskResponse
# (pythonSandbox.proto, codex a3aee2ad v3).  The body is snake_case like the
# rest of this service; the Gateway owns the proto mapping.  Business
# outcomes answer 200 + body (including the business NOT_FOUND); failure
# outcomes follow the D13 status-code convention (409 CONFLICT / 400
# INVALID_ARGUMENT) because this service maps errors purely by HTTP status.
class TaskIdCancelTarget(BaseModel):
    task_id: Optional[str] = None


class OperationCancelTarget(BaseModel):
    operation_id: Optional[str] = None
    request_fingerprint: Optional[str] = None


class CancelTaskRequest(BaseModel):
    """POST /tasks/cancel body.

    Deliberately LOOSE at the pydantic layer: the endpoint validates the
    target exclusivity and the non-blank field rules itself and answers 400
    (the D13 INVALID_ARGUMENT surface) instead of FastAPI's default 422,
    which the frozen D13 status vocabulary does not cover.  proto3 oneof
    only guarantees compile-time mutual exclusion; the runtime checks here
    are the service-side equivalent (codex a3aee2ad section 二).
    """

    by_task_id: Optional[TaskIdCancelTarget] = None
    by_operation: Optional[OperationCancelTarget] = None
    # cancelRequestId is a DURABLE binding, not an optional cache (codex
    # a3aee2ad section 六 ruling 3): the same id must always carry the same
    # target identity (a rebind answers 409), and a same-target replay
    # returns the first recorded outcome.  The endpoint rejects an empty id.
    cancel_request_id: Optional[str] = None
    # Audit-only cancel reason; never influences routing or outcome.
    reason: Optional[str] = None


class CancelOutcome(str, Enum):
    """proto CancelOutcome names, verbatim (the Gateway maps body -> proto)."""

    UNSPECIFIED = "CANCEL_OUTCOME_UNSPECIFIED"
    CANCEL_INTENT_RECORDED = "CANCEL_INTENT_RECORDED"
    CANCELED = "CANCELED"
    ALREADY_TERMINAL = "ALREADY_TERMINAL"
    NOT_FOUND = "NOT_FOUND"


class CancelTaskResponse(BaseModel):
    """200-body of POST /tasks/cancel.

    outcome is the branch signal (never the error text); task_id is the
    stable taskId assigned when the cancel intent was persisted (absent for
    the business NOT_FOUND); status is the CURRENT sandbox-side durable
    state of that task — the same QUEUED/RUNNING/SUCCEEDED/FAILED/CANCELED
    vocabulary as getTaskStatus/getTaskResult, no second status word set
    (codex f25b394a section 2).  For CANCEL_INTENT_RECORDED the status
    still shows QUEUED/RUNNING and callers poll until it turns CANCELED —
    this service never reports a CANCELING intermediate state.
    """

    outcome: CancelOutcome
    task_id: Optional[str] = None
    status: Optional[TaskStatus] = None
    error: Optional[str] = None
# === end D11 (task #108) ===
