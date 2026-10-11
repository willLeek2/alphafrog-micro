from __future__ import annotations

from datetime import datetime, timezone
from enum import Enum
from typing import Dict, List, Optional

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
    # A workspace-enabled request (three identity fields present) may carry NO
    # new dataset — a later call in the same Run reads only files earlier
    # calls left in the persistent workspace.  Legacy requests must declare
    # a non-empty dataset (the documented at-least-one-dataset default,
    # promoted to an explicit parameter error by the validator below).
    dataset_id: Optional[str] = Field(
        default=None, description="Primary dataset identifier (optional for workspace-enabled requests)"
    )
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
    # Persistent workspace identity.
    # All-or-nothing: the three fields must be absent together (legacy
    # request, pre-upgrade digest bytes preserved) or present together
    # (workspace-enabled request, identity enters the payload digest).
    # Partial presence is a parameter error.
    run_id: Optional[str] = Field(default=None, description="Owning Agent Run identity")
    workspace_id: Optional[str] = Field(default=None, description="Workspace to mount")
    workspace_generation: Optional[str] = Field(
        default=None, description="Workspace generation at acquisition time"
    )

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
        # Workspace identity is all-or-nothing so the
        # payload digest can use two stable projections (legacy bytes vs
        # identity-bearing bytes). Partial presence is a parameter error.
        # The three fields are stripped first so a whitespace-padded value
        # can never reach the registry gate or the digest.
        for identity_field in ("run_id", "workspace_id", "workspace_generation"):
            value = getattr(self, identity_field)
            if value is not None:
                stripped = value.strip()
                if not stripped:
                    # A present-but-blank identity is a parameter error,
                    # never a silent fallback to the legacy projection.
                    raise ValueError(
                        f"{identity_field} must not be blank when provided"
                    )
                setattr(self, identity_field, stripped)
        identity_present = {
            bool(self.run_id),
            bool(self.workspace_id),
            bool(self.workspace_generation),
        }
        if len(identity_present) != 1:
            raise ValueError(
                "run_id, workspace_id and workspace_generation must be provided together or all absent"
            )
        if self.run_id and self.operation_id:
            # operationId embeds the Run identity (runId:toolCallId:attempt,
            # task_store._validate_identity); a mismatched pair is rejected
            # before any state mutation.
            if not self.operation_id.startswith(self.run_id + ":"):
                raise ValueError("operation_id Run prefix does not match run_id")
        # Dataset rules keyed off the
        # same identity projection.  Workspace-enabled: empty/whitespace
        # dataset_id normalizes to None so both wire forms produce ONE
        # payload-digest projection (null), keeping idempotent replay stable.
        # Legacy: a non-empty dataset_id is required.  files is by definition
        # a list under dataset_id, so it always requires one.
        if self.run_id is not None:
            if self.dataset_id is not None and not self.dataset_id.strip():
                self.dataset_id = None
        elif not (self.dataset_id or "").strip():
            raise ValueError(
                "dataset_id must be a non-empty dataset identifier when the"
                " request carries no workspace identity"
            )
        if self.files and not (self.dataset_id or "").strip():
            raise ValueError("files requires a non-empty dataset_id")
        # A workspace call that carries NO dataset may still arrive with
        # header-only (or blank) path-mapping CSVs. There is nothing to
        # mount this call, so both wire forms — absent and header-only —
        # normalize to ONE projection (None): the payload digest stays
        # replay-stable and the runner's empty-CSV config error can never
        # fire for a legitimate read-only-workspace call.
        if (
            self.run_id is not None
            and self.dataset_id is None
            and not (self.dataset_ids or [])
        ):
            for csv_field in ("paths_dataset_csv", "path_manifest_csv"):
                value = getattr(self, csv_field)
                if value is not None and "\n" not in value.strip():
                    setattr(self, csv_field, None)
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
    # Persistent-workspace mode: the container-absolute mount point of the
    # Run's persistent user directory. Absent → legacy shape (child cwd =
    # taskWorkspace). Present → the child cwd AND AF_TASK_WORKSPACE switch
    # to this path while every control artifact stays under taskWorkspace;
    # the shared payload contract enforces the two trees stay disjoint.
    persistentWorkspace: Optional[str] = None
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
        if self.persistentWorkspace is not None:
            payload["persistentWorkspace"] = self.persistentWorkspace
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


class PurgedRequestBinding(BaseModel):
    """Compact replacement for the full ExecuteRequest after workspace deletion.

    Once the workspace disk is confirmed gone, the full request body (user
    code, files, libraries, dataset CSVs) is no longer needed for any
    replay or audit decision: late operationId replays resolve through the
    operations table plus the Task top-level fingerprint/digest, and late
    old-identity calls are refused by the workspace DELETED audit row. Two
    shapes exist because the create contract allows workspace tasks without
    an operationId (such tasks were admitted without an operations index
    entry): shape A (operation_id present) carries the full six-field
    binding and cross-validates against the operations entry; shape B
    (no operation_id) carries only the values the original record actually
    had — a fingerprint or an operations entry is NEVER fabricated.
    """

    operation_id: Optional[str] = None
    request_fingerprint: Optional[str] = None
    payload_digest: Optional[str] = None
    run_id: str
    workspace_id: str
    workspace_generation: str


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
    # === Persistent workspace: container identity bookkeeping ===
    # container_identity is persisted BEFORE the container is created: the
    # full label tuple (state-store instance UUID, taskId, workspaceId +
    # generation, deployment identity) the sweep later two-way-checks
    # against live containers.  container_id is written back after the
    # creation returns; the pre-persisted labels are the recovery path for
    # the "created but id not yet recorded" crash window.
    container_identity: Optional[Dict[str, str]] = None
    container_id: Optional[str] = None
    # === end container identity bookkeeping ===
    # Present iff the workspace deletion replaced the full request body with
    # its compact binding (request is then None). Only terminal tasks of a
    # DELETED workspace ever carry this; the operations table and the
    # top-level fingerprint/digest are kept alongside, so late replays and
    # audits resolve exactly as before.
    purged_request: Optional[PurgedRequestBinding] = None


class WorkspaceStatus(str, Enum):
    """Lifecycle states of a persistent workspace.

    ACTIVE covers both idle and occupied (occupancy is tracked by the
    holder task field, not a separate state — the transition stays atomic
    inside the single task state document). DELETING is the persistent
    "no new writer may acquire" state; DELETED is the irrevocable audit
    record that survives disk removal. SEALED is the durable "cleanup
    intent recorded" state: like DELETING it refuses every new acquire and
    task admission, but it is set by an explicit seal call before any
    deletion starts (a seal with no disk leaves only a Run-level record).
    A sealed workspace never returns to ACTIVE/DIRTY: a finishing holder
    only frees its slot, and the only way forward is deletion.
    """

    ACTIVE = "active"
    DIRTY = "dirty"
    DELETING = "deleting"
    DELETED = "deleted"
    SEALED = "sealed"


class WorkspaceResult(str, Enum):
    """Machine-determinable workspace business outcomes.
    Carried as an optional field on the normal 200 response —
    presence alone decides; the HTTP/gRPC error channel stays reserved for
    transport and system faults, never for business refusals.
    """

    WORKSPACE_DIRTY = "WORKSPACE_DIRTY"
    WORKSPACE_NOT_FOUND = "WORKSPACE_NOT_FOUND"
    WORKSPACE_OWNERSHIP_MISMATCH = "WORKSPACE_OWNERSHIP_MISMATCH"
    WORKSPACE_DELETED = "WORKSPACE_DELETED"
    WORKSPACE_DELETING = "WORKSPACE_DELETING"
    WORKSPACE_IDENTITY_CONFLICT = "WORKSPACE_IDENTITY_CONFLICT"
    WORKSPACE_UNSUPPORTED = "WORKSPACE_UNSUPPORTED"


class WorkspaceState(BaseModel):
    """The durable per-workspace record.

    Lives in the SAME atomic state document as the tasks (occupancy and
    the QUEUED→RUNNING transition commit in one persist).  holder_task_id is the single
    writer slot — ACTIVE with holder None means free.  DELETED is the
    irrevocable audit row that survives disk removal: it stays forever so
    late acquire/create calls with the dead identity get a stable
    "already deleted" answer instead of a silent re-creation.
    """

    workspace_id: str
    run_id: str
    generation: str
    status: WorkspaceStatus
    holder_task_id: Optional[str] = None
    created_at: datetime = Field(default_factory=datetime.utcnow)
    status_changed_at: Optional[datetime] = None
    dirtied_by_task_id: Optional[str] = None
    delete_idempotency_key: Optional[str] = None
    deleted_at: Optional[datetime] = None
    # Last business-activity moment (UTC): refreshed under the state lock at
    # workspace acquire/create, workspace-task admission, holder begin and
    # task completion. Frozen once the workspace leaves ACTIVE (deleting and
    # deleted keep the pre-delete value as audit). The expiry coordinator
    # re-computes due-ness from this value with its own current retention
    # configuration; the sandbox never precomputes expiry.
    last_active_at: Optional[datetime] = None
    # Seal bookkeeping, written by the idempotent seal transition. Sticky:
    # a workspace that later advances to DELETING/DELETED keeps the fields
    # as audit of WHEN the cleanup intent was first recorded.
    seal_idempotency_key: Optional[str] = None
    sealed_at: Optional[datetime] = None


class RunSealRecord(BaseModel):
    """Run-level seal record for a Run that never had a workspace on disk.

    Permanent and terminal by itself: a diskless seal never advances to
    DELETED (there is no disk whose removal could be audited). Its presence
    is what a later acquire checks before creating a workspace — the create
    side must lose the eligibility-to-creation race against a persisted
    seal.
    """

    sealed_at: datetime
    seal_idempotency_key: str


class CreateTaskResponse(BaseModel):
    """Create answer; the optional workspace refusal shape.

    workspace_result is present iff the workspace refused the create: NO
    task was created, no admission was taken, no container started —
    task_id is then the empty-string placeholder and status is absent.
    Callers decide on workspace_result's presence, never on the HTTP
    status; the error channel stays reserved for transport faults.
    """

    task_id: str
    status: Optional[TaskStatus] = None
    existing: bool = False
    request_fingerprint: Optional[str] = None
    workspace_result: Optional[WorkspaceResult] = None


class AcquireWorkspaceRequest(BaseModel):
    """取得或创建工作区.

    First acquisition carries only run_id and returns the Run's active
    workspace. A recovery call may carry the known identity; on mismatch
    the answer is WORKSPACE_IDENTITY_CONFLICT with NO replaceable workspace
    identity attached (a Run must never be silently switched to another
    disk). Every identity field is stripped and must be non-blank: a
    whitespace-only run id would otherwise create a workspace owned by
    the empty string, collapsing unrelated invalid calls onto one disk.
    """

    run_id: str = Field(..., min_length=1)
    known_workspace_id: Optional[str] = None
    known_workspace_generation: Optional[str] = None

    @model_validator(mode="after")
    def validate_known_identity(self) -> "AcquireWorkspaceRequest":
        self.run_id = self.run_id.strip()
        if not self.run_id:
            raise ValueError("run_id must not be blank")
        if self.known_workspace_id is not None:
            self.known_workspace_id = self.known_workspace_id.strip()
        if self.known_workspace_generation is not None:
            self.known_workspace_generation = (
                self.known_workspace_generation.strip()
            )
        # The recovery pair is all-or-nothing: a half-known identity cannot
        # be verified and must be a parameter error, not a silent
        # first-acquisition.
        if bool(self.known_workspace_id) != bool(self.known_workspace_generation):
            raise ValueError(
                "known_workspace_id and known_workspace_generation must be"
                " provided together or all absent"
            )
        if self.known_workspace_id == "" or self.known_workspace_generation == "":
            raise ValueError(
                "known_workspace_id and known_workspace_generation must not"
                " be blank when provided"
            )
        return self


class WorkspaceInfo(BaseModel):
    workspace_id: str
    workspace_generation: str
    status: WorkspaceStatus
    owned_by_run_id: str


class AcquireWorkspaceResponse(BaseModel):
    workspace: Optional[WorkspaceInfo] = None
    workspace_result: Optional[WorkspaceResult] = None


def parse_rfc3339_utc(value: str) -> datetime:
    """Parse an RFC3339 timestamp into a naive UTC datetime.

    The conditional-delete comparison is a time-VALUE comparison: two
    spellings of the same moment (trailing fractional zeros trimmed or
    kept, ``Z`` versus ``+00:00``) must compare equal, so the caller's
    string is parsed and normalized instead of matched literally. A zone
    designator is required; anything unparseable raises ValueError.
    """
    text = value.strip()
    if text.endswith(("Z", "z")):
        text = text[:-1] + "+00:00"
    try:
        parsed = datetime.fromisoformat(text)
    except ValueError:
        raise ValueError(f"not a valid RFC3339 timestamp: {value!r}") from None
    if parsed.tzinfo is None:
        raise ValueError(f"RFC3339 timestamp must carry a timezone: {value!r}")
    return parsed.astimezone(timezone.utc).replace(tzinfo=None)


class DeleteWorkspaceRequest(BaseModel):
    """Explicit delete, shared by the manual path and the expiry coordinator.

    Stripped, non-blank identifiers: a blank key would make separate
    delete attempts share one idempotency binding. The coordinator's
    conditional delete carries expected_last_active_at (the activity moment
    observed at scan time); the store re-checks it under the state lock as a
    time value (spellings of the same moment compare equal) and refuses with
    WORKSPACE_DELETE_REVOKED_NEW_ACTIVITY when the workspace saw activity
    since. The manual path leaves it absent (unconditional).
    """

    workspace_id: str = Field(..., min_length=1)
    idempotency_key: str = Field(..., min_length=1)
    expected_last_active_at: Optional[str] = None

    @model_validator(mode="after")
    def validate_stripped_ids(self) -> "DeleteWorkspaceRequest":
        self.workspace_id = self.workspace_id.strip()
        self.idempotency_key = self.idempotency_key.strip()
        if not self.workspace_id or not self.idempotency_key:
            raise ValueError(
                "workspace_id and idempotency_key must not be blank"
            )
        if self.expected_last_active_at is not None:
            self.expected_last_active_at = self.expected_last_active_at.strip()
            if not self.expected_last_active_at:
                raise ValueError(
                    "expected_last_active_at must not be blank when provided"
                )
            # Format errors are input errors (rejected at the edge), never a
            # reason to compare or to delete.
            parse_rfc3339_utc(self.expected_last_active_at)
        return self


class WorkspaceDeleteOutcome(str, Enum):
    """Machine-readable three-way classification of a delete answer.

    Mirrors the two booleans one-to-one; callers that predate the field see
    unchanged deleted/retryable_failure values.
    """

    WORKSPACE_DELETE_DELETED = "WORKSPACE_DELETE_DELETED"
    WORKSPACE_DELETE_REVOKED_NEW_ACTIVITY = "WORKSPACE_DELETE_REVOKED_NEW_ACTIVITY"
    WORKSPACE_DELETE_TEMPORARILY_UNAVAILABLE = "WORKSPACE_DELETE_TEMPORARILY_UNAVAILABLE"


class DeleteWorkspaceResponse(BaseModel):
    # DELETED = disk confirmed gone, audit record persisted (the
    # audit row is written only after the directory removal is confirmed;
    # a failed removal keeps the retryable DELETING state and answers
    # RETRYABLE_FAILURE).
    deleted: bool = False
    retryable_failure: bool = False
    outcome: Optional[WorkspaceDeleteOutcome] = None


class SealWorkspaceRequest(BaseModel):
    """Idempotent seal: persist the cleanup intent for one Run.

    Sealing a Run that has a workspace flips the record to SEALED (refusing
    every new acquire and task admission); sealing a Run with no disk
    leaves only the Run-level record. Both are decided under the same state
    lock that arbitrates acquire and admission, so a create racing the seal
    always loses.
    """

    run_id: str = Field(..., min_length=1)
    idempotency_key: str = Field(..., min_length=1)

    @model_validator(mode="after")
    def validate_stripped_ids(self) -> "SealWorkspaceRequest":
        self.run_id = self.run_id.strip()
        self.idempotency_key = self.idempotency_key.strip()
        if not self.run_id or not self.idempotency_key:
            raise ValueError("run_id and idempotency_key must not be blank")
        return self


class SealWorkspaceResponse(BaseModel):
    # sealed=True: the seal record is durable. workspace is attached when
    # the Run has a workspace record (status as-is); a pure diskless seal
    # has no workspace identity to attach.
    sealed: bool = False
    workspace: Optional[WorkspaceInfo] = None


class WorkspaceQueryOutcome(str, Enum):
    """Three-way read-only answer for one Run. The query never creates."""

    WORKSPACE_QUERY_NOT_FOUND = "WORKSPACE_QUERY_NOT_FOUND"
    WORKSPACE_QUERY_FOUND = "WORKSPACE_QUERY_FOUND"
    WORKSPACE_QUERY_FOUND_DELETED = "WORKSPACE_QUERY_FOUND_DELETED"


class QueryWorkspaceRequest(BaseModel):
    run_id: str = Field(..., min_length=1)

    @model_validator(mode="after")
    def validate_stripped_ids(self) -> "QueryWorkspaceRequest":
        self.run_id = self.run_id.strip()
        if not self.run_id:
            raise ValueError("run_id must not be blank")
        return self


class QueryWorkspaceResponse(BaseModel):
    outcome: WorkspaceQueryOutcome
    # Attached for FOUND with disk and always for FOUND_DELETED (the deleted
    # audit still vouches the old identity); absent only for a FOUND
    # diskless seal.
    workspace: Optional[WorkspaceInfo] = None
    # RFC3339 UTC: the current activity moment (FOUND with disk), the seal
    # moment (FOUND diskless), or the frozen pre-delete activity moment
    # (FOUND_DELETED).
    last_active_at: Optional[str] = None
    # Deletion audit moment, RFC3339 UTC; FOUND_DELETED only.
    deleted_at: Optional[str] = None


class ListWorkspaceExpiryCandidatesRequest(BaseModel):
    """Bounded page over this instance's local non-DELETED workspaces."""

    page_size: int = Field(..., gt=0)
    page_token: str = ""


class WorkspaceExpiryCandidate(BaseModel):
    run_id: str
    workspace_id: str
    workspace_generation: str
    status: WorkspaceStatus
    # RFC3339 UTC; the caller re-computes due-ness with its current
    # retention configuration and re-checks lane ownership per row.
    last_active_at: str


class ListWorkspaceExpiryCandidatesResponse(BaseModel):
    # Sorted by workspace_id; the token is the last row's workspace_id
    # (weak-consistency cursor — concurrent writes may repeat or skip a
    # row, every row carries its run_id for the caller's re-check).
    candidates: List[WorkspaceExpiryCandidate]
    next_page_token: str = ""


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
