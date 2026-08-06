from __future__ import annotations

from .models import SandboxResourceUsage, TaskStatus


def classify_terminal_retryable(
    *,
    status: TaskStatus | str | None,
    exit_code: int | None,
    resource_usage: SandboxResourceUsage | None,
) -> bool | None:
    """Return the frozen P0 terminal classification, preserving unknown as None."""
    status_value = status.value if isinstance(status, TaskStatus) else (status or "").strip().upper()
    if status_value == TaskStatus.SUCCEEDED.value or exit_code == 0:
        return False
    if status_value == TaskStatus.CANCELED.value:
        return False
    if resource_usage is None:
        return None

    exit_reason = (resource_usage.exit_reason or "").strip().upper()
    if resource_usage.oom_killed or exit_reason == "OOM_KILLED":
        return True
    if exit_reason == "QUEUE_TIMEOUT":
        return True
    if resource_usage.timed_out or exit_reason == "TIMEOUT":
        return False
    if exit_reason in {"NON_ZERO_EXIT", "EXECUTION_ERROR"}:
        return False
    return None
