from __future__ import annotations

import hashlib
import json
import os
import re
import tempfile
import threading
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Dict

from .models import ExecuteRequest, Task, TaskStatus


SHA256_PATTERN = re.compile(r"^sha256:[0-9a-f]{64}$")
OPERATION_ID_PATTERN = re.compile(r"^[^:\s]+:[^:\s]+:[1-9][0-9]*$")

# === work-package-C (ccqwen) ===
# §7.1: `state.json` format versions.  v2 is the CURRENT write format; v1
# documents stay readable (the bump is additive: Task.effective_output_limits
# and Task.runtime_image_ref are optional fields, and D's future result fields
# ride the same pydantic round-trip at owner merge).  Unknown versions fail
# the load closed — a never-silently-migrate rule.
SCHEMA_VERSION_V1 = "sandbox_task_store_v1"
SCHEMA_VERSION_V2 = "sandbox_task_store_v2"
SUPPORTED_SCHEMA_VERSIONS = frozenset({SCHEMA_VERSION_V1, SCHEMA_VERSION_V2})
# === end work-package-C (ccqwen) ===


class OperationConflictError(RuntimeError):
    pass


@dataclass(frozen=True)
class CreateDecision:
    task: Task
    existing: bool


def request_payload_digest(request: ExecuteRequest) -> str:
    """Bind the supplied canonical fingerprint to the exact first HTTP payload."""
    payload = request.model_dump(mode="json", exclude={"request_fingerprint"})
    encoded = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return "sha256:" + hashlib.sha256(encoded).hexdigest()


class DurableTaskStore:
    """Single-file atomic store for tasks and the operationId index.

    Task and operation records are replaced together, so an operation mapping can
    never survive without the first payload digest it was bound to.
    """

    def __init__(self, state_path: Path) -> None:
        self.state_path = state_path
        self._lock = threading.RLock()
        self.tasks: Dict[str, Task] = {}
        self.operations: Dict[str, dict[str, str]] = {}
        self._load()

    def create(self, task: Task) -> CreateDecision:
        operation_id = (task.request.operation_id or "").strip()
        fingerprint = (task.request.request_fingerprint or "").strip().lower()
        payload_digest = request_payload_digest(task.request)
        task.payload_digest = payload_digest
        task.request_fingerprint = fingerprint or None

        with self._lock:
            if not operation_id:
                self.tasks[task.task_id] = task
                self._persist_locked()
                return CreateDecision(task=task, existing=False)
            self._validate_identity(operation_id, fingerprint)
            existing = self.operations.get(operation_id)
            if existing is not None:
                existing_task = self.tasks.get(existing["task_id"])
                if existing_task is None:
                    raise RuntimeError(f"operation index references missing task: {operation_id}")
                if existing["request_fingerprint"] != fingerprint or existing["payload_digest"] != payload_digest:
                    raise OperationConflictError(
                        "operation_id is already bound to a different request fingerprint or payload"
                    )
                return CreateDecision(task=existing_task, existing=True)

            self.tasks[task.task_id] = task
            self.operations[operation_id] = {
                "task_id": task.task_id,
                "request_fingerprint": fingerprint,
                "payload_digest": payload_digest,
            }
            self._persist_locked()
            return CreateDecision(task=task, existing=False)

    def save(self, task: Task) -> None:
        with self._lock:
            self.tasks[task.task_id] = task
            self._persist_locked()

    def get(self, task_id: str) -> Task | None:
        with self._lock:
            return self.tasks.get(task_id)

    def get_by_operation_id(self, operation_id: str) -> Task | None:
        with self._lock:
            entry = self.operations.get(operation_id)
            return self.tasks.get(entry["task_id"]) if entry else None

    def recover_after_restart(self) -> list[str]:
        """Requeue durable QUEUED tasks and terminalize abandoned RUNNING tasks."""
        queued: list[str] = []
        changed = False
        with self._lock:
            for task in self.tasks.values():
                if task.status == TaskStatus.QUEUED:
                    queued.append(task.task_id)
                elif task.status == TaskStatus.RUNNING:
                    task.status = TaskStatus.FAILED
                    task.error = "sandbox service restarted while task was running"
                    task.finished_at = datetime.utcnow()
                    changed = True
            if changed:
                self._persist_locked()
        return queued

    def _validate_identity(self, operation_id: str, fingerprint: str) -> None:
        if not OPERATION_ID_PATTERN.fullmatch(operation_id):
            raise ValueError("operation_id must be runId:toolCallId:attempt")
        if not SHA256_PATTERN.fullmatch(fingerprint):
            raise ValueError("request_fingerprint must be lowercase sha256:<64 hex>")

    def _load(self) -> None:
        if not self.state_path.exists():
            return
        try:
            document = json.loads(self.state_path.read_text(encoding="utf-8"))
            schema_version = document.get("schema_version")
            if schema_version not in SUPPORTED_SCHEMA_VERSIONS:
                raise ValueError(
                    f"unsupported state.json schema_version: {schema_version!r}"
                )
            self.tasks = {
                task_id: Task.model_validate(payload)
                for task_id, payload in (document.get("tasks") or {}).items()
            }
            self.operations = dict(document.get("operations") or {})
            for operation_id, entry in self.operations.items():
                if entry.get("task_id") not in self.tasks:
                    raise ValueError(f"operation {operation_id} references missing task")
                if not entry.get("request_fingerprint") or not entry.get("payload_digest"):
                    raise ValueError(f"operation {operation_id} is missing its immutable request binding")
        except Exception as error:
            raise RuntimeError(f"failed to load durable sandbox task store: {self.state_path}") from error

    def _persist_locked(self) -> None:
        self.state_path.parent.mkdir(parents=True, exist_ok=True)
        document = {
            "schema_version": SCHEMA_VERSION_V2,
            "tasks": {task_id: task.model_dump(mode="json") for task_id, task in self.tasks.items()},
            "operations": self.operations,
        }
        fd, temp_name = tempfile.mkstemp(prefix=self.state_path.name + ".", suffix=".tmp", dir=self.state_path.parent)
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as handle:
                json.dump(document, handle, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
                handle.flush()
                os.fsync(handle.fileno())
            os.replace(temp_name, self.state_path)
            directory_fd = os.open(self.state_path.parent, getattr(os, "O_DIRECTORY", 0))
            try:
                os.fsync(directory_fd)
            finally:
                os.close(directory_fd)
        finally:
            try:
                os.unlink(temp_name)
            except FileNotFoundError:
                pass
