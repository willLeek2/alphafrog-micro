#!/usr/bin/env python3
"""Build a deterministic production-collector usage/observability consistency bundle."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any


def _repo_root() -> Path:
    return Path(__file__).resolve().parents[4]


def _camel_usage(usage: dict[str, Any]) -> dict[str, Any]:
    mapping = {
        "resource_class": "resourceClass",
        "cpu_millis": "cpuMillis",
        "memory_peak_bytes": "memoryPeakBytes",
        "memory_byte_millis": "memoryByteMillis",
        "logical_bytes_scanned": "logicalBytesScanned",
        "artifact_bytes_written": "artifactBytesWritten",
        "temporary_bytes_written": "temporaryBytesWritten",
        "queue_wait_millis": "queueWaitMillis",
        "prepare_millis": "prepareMillis",
        "execution_wall_millis": "executionWallMillis",
        "cleanup_millis": "cleanupMillis",
        "dataset_open_count": "datasetOpenCount",
        "exit_reason": "exitReason",
        "oom_killed": "oomKilled",
        "timed_out": "timedOut",
        "attribution_complete": "attributionComplete",
        "sampling_interval_millis": "samplingIntervalMillis",
        "missing_fields": "missingFields",
    }
    return {mapping[key]: value for key, value in usage.items() if key in mapping}


def build_bundle(
    loader_metrics_path: Path,
    output_dir: Path,
    estimated_rows: int,
    file_count: int,
    resource_class: str,
) -> dict[str, str]:
    service_root = _repo_root() / "pythonSandboxService"
    sys.path.insert(0, str(service_root))
    from app.resource_usage import SandboxResourceUsageCollector  # noqa: PLC0415

    class DeterministicSampler:
        def __init__(self) -> None:
            self.calls = 0

        def __call__(self) -> tuple[int, int]:
            self.calls += 1
            return 1_000_000_000 + self.calls * 5_000_000, 64 * 1024 * 1024 + self.calls * 1024 * 1024

        def close(self) -> None:
            return None

    collector = SandboxResourceUsageCollector(
        resource_class=resource_class,
        sampling_interval_millis=60_000,
        sampler_factory=lambda _container_id: DeterministicSampler(),
    )
    collector.start("deterministic-fixture-container")
    usage_model = collector.finish(
        container_id="deterministic-fixture-container",
        queue_wait_millis=7,
        prepare_millis=11,
        execution_wall_millis=13,
        cleanup_millis=17,
        loader_metrics_jsonl=loader_metrics_path.read_text(encoding="utf-8"),
        artifact_bytes_written=19,
        temporary_bytes_written=23,
        exit_reason="SUCCESS",
        oom_killed=False,
        timed_out=False,
    )
    usage = usage_model.model_dump(mode="json")
    camel_usage = _camel_usage(usage)
    capacity_units = 3 if resource_class == "HEAVY" else 1
    estimated_bytes = int(camel_usage["logicalBytesScanned"])
    call = {
        "toolCallId": "consistency-call-1",
        "attempt": 1,
        "operationId": "consistency-run-1:consistency-call-1:1",
        "taskId": "consistency-task-1",
        "terminalStatus": "COMPLETED",
        "success": True,
        "retryable": False,
        "terminalAt": "2026-07-13T00:00:00Z",
        "background": False,
        "estimate": {
            "estimatedRows": estimated_rows,
            "estimatedBytes": estimated_bytes,
            "fileCount": file_count,
            "selectedColumnRatio": 1.0,
            "manifestMemberCount": file_count,
            "heavyOperationHints": [],
            "resourceClass": resource_class,
            "capacityUnits": capacity_units,
        },
        "reservation": {
            "reservationId": "consistency-run-1:consistency-call-1:1",
            "resourceClass": resource_class,
            "capacityUnits": capacity_units,
            "taskId": "consistency-task-1",
            "state": "TERMINAL_CONFIRMED",
        },
        "resourceUsage": camel_usage,
    }
    summary = {
        "toolCallCount": 1,
        "attemptCount": 1,
        "estimatedRows": estimated_rows,
        "estimatedBytes": estimated_bytes,
        "fileCount": file_count,
        "capacityUnits": capacity_units,
        "cpuMillis": camel_usage["cpuMillis"],
        "memoryPeakBytes": camel_usage["memoryPeakBytes"],
        "logicalBytesScanned": camel_usage["logicalBytesScanned"],
        "queueWaitMillis": camel_usage["queueWaitMillis"],
        "prepareMillis": camel_usage["prepareMillis"],
        "executionWallMillis": camel_usage["executionWallMillis"],
        "cleanupMillis": camel_usage["cleanupMillis"],
        "datasetOpenCount": camel_usage["datasetOpenCount"],
        "oomCount": 0,
        "timeoutCount": 0,
        "attributionComplete": camel_usage["attributionComplete"],
        "missingFields": camel_usage["missingFields"],
    }
    observability = {
        "data_analysis_observability": {
            "version": 1,
            "runId": "consistency-run-1",
            "summary": summary,
            "calls": [call],
        }
    }
    output_dir.mkdir(parents=True, exist_ok=True)
    usage_path = output_dir / "resource_usage.json"
    observability_path = output_dir / "observability.json"
    usage_path.write_text(json.dumps(usage, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    observability_path.write_text(
        json.dumps(observability, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    return {
        "resourceUsage": str(usage_path.resolve()),
        "observability": str(observability_path.resolve()),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--loader-metrics", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--estimated-rows", type=int, required=True)
    parser.add_argument("--file-count", type=int, required=True)
    parser.add_argument("--resource-class", choices=("STANDARD", "HEAVY"), default="STANDARD")
    args = parser.parse_args()
    result = build_bundle(
        args.loader_metrics.resolve(),
        args.output_dir.resolve(),
        args.estimated_rows,
        args.file_count,
        args.resource_class,
    )
    print(json.dumps({"schemaVersion": "collector_consistency_bundle_v1", "status": "PASS", **result}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
