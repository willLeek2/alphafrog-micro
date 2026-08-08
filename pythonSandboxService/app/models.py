from __future__ import annotations

from datetime import datetime
from enum import Enum
from typing import List, Optional

from pydantic import BaseModel, Field, model_validator


class TaskStatus(str, Enum):
    QUEUED = "QUEUED"
    RUNNING = "RUNNING"
    SUCCEEDED = "SUCCEEDED"
    FAILED = "FAILED"
    CANCELED = "CANCELED"


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


class ExecuteResult(BaseModel):
    exit_code: int
    stdout: str
    stderr: str
    dataset_dir: str
    artifacts: Optional[dict] = None
    resource_usage: Optional[SandboxResourceUsage] = None
    retryable: Optional[bool] = None
    # NOTE: the §5.1 `finance_record_channel` / `execution_environment` fields
    # are declared by work package D (frozen DTO classes FinanceRecordChannel /
    # ExecutionEnvironment, contract §5.1 snake_case). Work package C only
    # WRITES finance_record_channel (app.finance_record_channel write path) and
    # never redefines the classes (owner split, msg f4341b21). They land here
    # at owner merge; the C write path tolerates their absence until then.


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
        }
        if self.runtimeEnvironmentPath is not None:
            payload["runtimeEnvironmentPath"] = self.runtimeEnvironmentPath
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
# === end work-package-C (ccqwen) ===


class Task(BaseModel):
    task_id: str
    status: TaskStatus
    request: ExecuteRequest
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
