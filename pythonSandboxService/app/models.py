from __future__ import annotations

from datetime import datetime
from enum import Enum
from typing import List, Optional

from pydantic import BaseModel, Field


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


class ExecuteResult(BaseModel):
    exit_code: int
    stdout: str
    stderr: str
    dataset_dir: str
    artifacts: Optional[dict] = None


class Task(BaseModel):
    task_id: str
    status: TaskStatus
    request: ExecuteRequest
    result: Optional[ExecuteResult] = None
    error: Optional[str] = None
    created_at: datetime = Field(default_factory=datetime.utcnow)
    started_at: Optional[datetime] = None
    finished_at: Optional[datetime] = None


class CreateTaskResponse(BaseModel):
    task_id: str
    status: TaskStatus
