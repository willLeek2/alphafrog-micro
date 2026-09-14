from __future__ import annotations

import unittest

from app.models import ExecuteRequest, ExecuteResult, SandboxResourceUsage, Task, TaskStatus
from app.retry_classification import classify_terminal_retryable


def usage(
    exit_reason: str,
    *,
    oom_killed: bool = False,
    timed_out: bool = False,
) -> SandboxResourceUsage:
    return SandboxResourceUsage(
        resource_class="STANDARD",
        exit_reason=exit_reason,
        oom_killed=oom_killed,
        timed_out=timed_out,
    )


class TerminalRetryClassificationTest(unittest.TestCase):
    def test_frozen_p0_matrix(self) -> None:
        cases = (
            (TaskStatus.SUCCEEDED, 0, usage("SUCCEEDED"), False),
            (TaskStatus.CANCELED, -1, usage("CANCELED"), False),
            (TaskStatus.FAILED, -1, usage("OOM_KILLED", oom_killed=True), True),
            (TaskStatus.FAILED, -1, usage("QUEUE_TIMEOUT"), True),
            (TaskStatus.FAILED, -1, usage("TIMEOUT", timed_out=True), False),
            (TaskStatus.FAILED, 2, usage("NON_ZERO_EXIT"), False),
            (TaskStatus.FAILED, -1, usage("EXECUTION_ERROR"), False),
            (TaskStatus.FAILED, -1, None, None),
            (TaskStatus.FAILED, -1, usage("UNKNOWN"), None),
        )

        for status, exit_code, resource_usage, expected in cases:
            with self.subTest(status=status, exit_code=exit_code, usage=resource_usage):
                self.assertIs(
                    classify_terminal_retryable(
                        status=status,
                        exit_code=exit_code,
                        resource_usage=resource_usage,
                    ),
                    expected,
                )

    def test_success_and_cancel_precede_conflicting_usage_hints(self) -> None:
        oom = usage("OOM_KILLED", oom_killed=True)
        self.assertFalse(
            classify_terminal_retryable(
                status=TaskStatus.SUCCEEDED,
                exit_code=0,
                resource_usage=oom,
            )
        )
        self.assertFalse(
            classify_terminal_retryable(
                status=TaskStatus.CANCELED,
                exit_code=-1,
                resource_usage=oom,
            )
        )

    def test_optional_field_distinguishes_missing_from_false(self) -> None:
        result_missing = ExecuteResult(exit_code=-1, stdout="", stderr="", dataset_dir="/tmp")
        result_false = result_missing.model_copy(update={"retryable": False})
        task_missing = Task(
            task_id="task-1",
            status=TaskStatus.FAILED,
            request=ExecuteRequest(dataset_id="dataset-1", code="print(1)"),
        )
        task_false = task_missing.model_copy(update={"retryable": False})

        self.assertNotIn("retryable", result_missing.model_dump(exclude_none=True))
        self.assertIs(result_false.model_dump(exclude_none=True)["retryable"], False)
        self.assertNotIn("retryable", task_missing.model_dump(exclude_none=True))
        self.assertIs(task_false.model_dump(exclude_none=True)["retryable"], False)


if __name__ == "__main__":
    unittest.main()
