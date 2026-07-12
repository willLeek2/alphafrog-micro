#!/usr/bin/env python3
"""Check loader metrics, terminal usage, and full observability for one attempt."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any, Callable


REPORT_SCHEMA = "data_intense_consistency_report_v1"
P0_FIELDS = (
    "cpuMillis",
    "memoryPeakBytes",
    "logicalBytesScanned",
    "queueWaitMillis",
    "prepareMillis",
    "executionWallMillis",
    "cleanupMillis",
    "datasetOpenCount",
    "exitReason",
)
SNAKE_NAMES = {
    "cpuMillis": "cpu_millis",
    "memoryPeakBytes": "memory_peak_bytes",
    "logicalBytesScanned": "logical_bytes_scanned",
    "queueWaitMillis": "queue_wait_millis",
    "prepareMillis": "prepare_millis",
    "executionWallMillis": "execution_wall_millis",
    "cleanupMillis": "cleanup_millis",
    "datasetOpenCount": "dataset_open_count",
    "exitReason": "exit_reason",
    "resourceClass": "resource_class",
    "oomKilled": "oom_killed",
    "timedOut": "timed_out",
    "attributionComplete": "attribution_complete",
    "missingFields": "missing_fields",
}


def _field(document: dict[str, Any], camel: str) -> Any:
    if camel in document:
        return document[camel]
    snake = SNAKE_NAMES.get(camel)
    return document.get(snake) if snake else None


def _read_json(path: Path) -> dict[str, Any]:
    document = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(document, dict):
        raise ValueError(f"{path} must contain one JSON object")
    return document


def _parse_loader_metrics(path: Path) -> tuple[int, int, int]:
    logical_bytes = 0
    open_count = 0
    row_count = 0
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        row = json.loads(line)
        if not isinstance(row, dict) or row.get("schema_version") != "loader_metric_v1":
            raise ValueError(f"loader metric line {line_number} has unsupported schema")
        logical_bytes += int(row["logicalBytes"])
        open_count += int(row["openCount"])
        row_count += 1
    return logical_bytes, open_count, row_count


def _validate_usage(usage: dict[str, Any]) -> None:
    missing = sorted(str(value) for value in (_field(usage, "missingFields") or []))
    unknown = sorted(set(missing) - set(P0_FIELDS))
    if unknown:
        raise ValueError(f"usage missingFields contains unknown P0 names: {unknown}")
    actual_missing = sorted(field for field in P0_FIELDS if _field(usage, field) is None)
    if missing != actual_missing:
        raise ValueError(f"usage missingFields mismatch: declared={missing} actual={actual_missing}")
    complete = bool(_field(usage, "attributionComplete"))
    if complete != (not actual_missing):
        raise ValueError("usage attributionComplete does not match P0 field presence")
    if not str(_field(usage, "resourceClass") or "").strip():
        raise ValueError("usage resourceClass must be present")


def _sum_or_none(calls: list[dict[str, Any]], extractor: Callable[[dict[str, Any]], Any]) -> Any:
    values = [extractor(call) for call in calls]
    return None if any(value is None for value in values) else sum(int(value) for value in values)


def _max_or_none(calls: list[dict[str, Any]], extractor: Callable[[dict[str, Any]], Any]) -> Any:
    values = [extractor(call) for call in calls]
    return None if any(value is None for value in values) else max((int(value) for value in values), default=0)


def _expected_summary(calls: list[dict[str, Any]]) -> dict[str, Any]:
    def usage(call: dict[str, Any]) -> dict[str, Any]:
        return call["resourceUsage"]

    def estimate(call: dict[str, Any]) -> dict[str, Any]:
        return call["estimate"]

    missing = sorted(
        {
            str(field)
            for call in calls
            for field in (_field(usage(call), "missingFields") or [])
        }
    )
    return {
        "toolCallCount": len({str(call["toolCallId"]) for call in calls}),
        "attemptCount": len(calls),
        "estimatedRows": sum(int(estimate(call)["estimatedRows"]) for call in calls),
        "estimatedBytes": sum(int(estimate(call)["estimatedBytes"]) for call in calls),
        "fileCount": sum(int(estimate(call)["fileCount"]) for call in calls),
        "capacityUnits": sum(int(estimate(call)["capacityUnits"]) for call in calls),
        "cpuMillis": _sum_or_none(calls, lambda call: _field(usage(call), "cpuMillis")),
        "memoryPeakBytes": _max_or_none(calls, lambda call: _field(usage(call), "memoryPeakBytes")),
        "logicalBytesScanned": _sum_or_none(
            calls, lambda call: _field(usage(call), "logicalBytesScanned")
        ),
        "queueWaitMillis": _sum_or_none(calls, lambda call: _field(usage(call), "queueWaitMillis")),
        "prepareMillis": _sum_or_none(calls, lambda call: _field(usage(call), "prepareMillis")),
        "executionWallMillis": _sum_or_none(
            calls, lambda call: _field(usage(call), "executionWallMillis")
        ),
        "cleanupMillis": _sum_or_none(calls, lambda call: _field(usage(call), "cleanupMillis")),
        "datasetOpenCount": _sum_or_none(calls, lambda call: _field(usage(call), "datasetOpenCount")),
        "oomCount": sum(bool(_field(usage(call), "oomKilled")) for call in calls),
        "timeoutCount": sum(bool(_field(usage(call), "timedOut")) for call in calls),
        "attributionComplete": all(bool(_field(usage(call), "attributionComplete")) for call in calls),
        "missingFields": missing,
    }


def check_consistency(
    loader_metrics_path: Path,
    resource_usage_path: Path,
    observability_path: Path,
) -> dict[str, Any]:
    checks: list[dict[str, Any]] = []

    def check(name: str, action: Callable[[], None]) -> None:
        try:
            action()
            checks.append({"name": name, "status": "PASS"})
        except Exception as error:
            checks.append({"name": name, "status": "FAIL", "detail": str(error)})

    usage = _read_json(resource_usage_path)
    observability_document = _read_json(observability_path)
    root = observability_document.get("data_analysis_observability", observability_document)
    if not isinstance(root, dict):
        raise ValueError("observability root must be an object")
    calls = root.get("calls")
    if not isinstance(calls, list):
        raise ValueError("full observability must contain calls")

    metrics: dict[str, int] = {}

    def validate_metrics() -> None:
        logical_bytes, open_count, metric_rows = _parse_loader_metrics(loader_metrics_path)
        metrics.update(
            logicalBytesScanned=logical_bytes,
            datasetOpenCount=open_count,
            metricRows=metric_rows,
        )

    check("loader_metrics_schema", validate_metrics)
    check("terminal_usage_presence", lambda: _validate_usage(usage))

    def validate_loader_usage() -> None:
        if not metrics:
            raise ValueError("loader metrics did not validate")
        if _field(usage, "logicalBytesScanned") != metrics["logicalBytesScanned"]:
            raise ValueError("terminal logicalBytesScanned differs from loader JSONL sum")
        if _field(usage, "datasetOpenCount") != metrics["datasetOpenCount"]:
            raise ValueError("terminal datasetOpenCount differs from loader JSONL sum")

    check("loader_to_terminal_usage", validate_loader_usage)

    def validate_calls() -> None:
        identities: set[tuple[str, int]] = set()
        for call in calls:
            if not isinstance(call, dict):
                raise ValueError("calls must contain objects")
            key = (str(call["toolCallId"]), int(call["attempt"]))
            if key in identities:
                raise ValueError(f"duplicate observability identity: {key}")
            identities.add(key)
            call_usage = call["resourceUsage"]
            _validate_usage(call_usage)
            classes = {
                str(call["estimate"]["resourceClass"]),
                str(call["reservation"]["resourceClass"]),
                str(_field(call_usage, "resourceClass")),
            }
            if len(classes) != 1:
                raise ValueError(f"estimate/reservation/usage resourceClass mismatch: {classes}")
            if int(call["estimate"]["capacityUnits"]) != int(call["reservation"]["capacityUnits"]):
                raise ValueError("estimate/reservation capacityUnits mismatch")

    check("observability_call_contract", validate_calls)

    def validate_summary() -> None:
        if int(root.get("version", 0)) != 1:
            raise ValueError("observability version must be 1")
        summary = root.get("summary")
        if not isinstance(summary, dict):
            raise ValueError("observability summary must be an object")
        expected = _expected_summary(calls)
        mismatches = {
            key: {"expected": value, "actual": summary.get(key)}
            for key, value in expected.items()
            if summary.get(key) != value
        }
        if mismatches:
            raise ValueError(f"summary aggregation mismatch: {mismatches}")

    check("observability_summary_aggregation", validate_summary)

    def validate_same_usage() -> None:
        if len(calls) != 1:
            raise ValueError("external resource-usage cross-check requires exactly one call")
        call_usage = calls[0]["resourceUsage"]
        fields = (*P0_FIELDS, "resourceClass", "oomKilled", "timedOut", "attributionComplete", "missingFields")
        differences = {
            field: {"terminal": _field(usage, field), "observability": _field(call_usage, field)}
            for field in fields
            if _field(usage, field) != _field(call_usage, field)
        }
        if differences:
            raise ValueError(f"terminal/observability usage mismatch: {differences}")

    check("terminal_usage_to_observability", validate_same_usage)
    status = "PASS" if all(item["status"] == "PASS" for item in checks) else "FAIL"
    return {
        "schemaVersion": REPORT_SCHEMA,
        "status": status,
        "inputs": {
            "loaderMetrics": loader_metrics_path.name,
            "resourceUsage": resource_usage_path.name,
            "observability": observability_path.name,
        },
        "metrics": metrics,
        "checks": checks,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--loader-metrics", type=Path, required=True)
    parser.add_argument("--resource-usage", type=Path, required=True)
    parser.add_argument("--observability", type=Path, required=True)
    parser.add_argument("--json-out", type=Path, required=True)
    args = parser.parse_args()
    report = check_consistency(args.loader_metrics, args.resource_usage, args.observability)
    args.json_out.parent.mkdir(parents=True, exist_ok=True)
    args.json_out.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(report, sort_keys=True))
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
