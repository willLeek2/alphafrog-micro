#!/usr/bin/env python3
"""Run one loader benchmark case and emit JSON plus an append-only CSV row."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import os
import platform
import resource
import sys
import time
from pathlib import Path
from typing import Any


RESULT_SCHEMA = "data_intense_loader_benchmark_result_v1"
SUMMARY_FIELDS = [
    "schemaVersion",
    "status",
    "case",
    "preset",
    "elapsedMillis",
    "processPeakRssKiB",
    "rowCount",
    "datasetCount",
    "chunkCount",
    "logicalBytesScanned",
    "datasetOpenCount",
]


def _repo_root() -> Path:
    return Path(__file__).resolve().parents[4]


def _load_production_modules():
    service_root = _repo_root() / "pythonSandboxService"
    sys.path.insert(0, str(service_root))
    from app.af_dataset_loader import iter_datasets, load_datasets  # noqa: PLC0415
    from app.resource_usage import parse_loader_metrics_jsonl  # noqa: PLC0415

    return iter_datasets, load_datasets, parse_loader_metrics_jsonl


def _manifest_digest(path: Path) -> str:
    manifest = json.loads(path.read_text(encoding="utf-8"))
    for dataset in manifest.get("datasets", []):
        dataset.pop("path", None)
    canonical = json.dumps(manifest, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return "sha256:" + hashlib.sha256(canonical).hexdigest()


def _peak_rss_kib() -> int:
    value = int(resource.getrusage(resource.RUSAGE_SELF).ru_maxrss)
    return value // 1024 if sys.platform == "darwin" else value


def _append_csv(path: Path, row: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    exists = path.exists()
    with path.open("a", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=SUMMARY_FIELDS, lineterminator="\n")
        if not exists:
            writer.writeheader()
        writer.writerow({key: row.get(key) for key in SUMMARY_FIELDS})


def run_case(fixture_root: Path, case_name: str, chunk_size: int) -> dict[str, Any]:
    iter_datasets, load_datasets, parse_loader_metrics_jsonl = _load_production_modules()
    manifest_path = fixture_root / "fixture_manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    case = manifest["cases"][case_name]
    numbers = [str(value) for value in case["datasetNumbers"]]
    input_root = fixture_root / "sandbox" / "input"
    metrics_path = fixture_root / "metrics" / f"{case_name}.jsonl"
    metrics_path.parent.mkdir(parents=True, exist_ok=True)
    metrics_path.unlink(missing_ok=True)

    previous_metrics_path = os.environ.get("AF_TASK_METRICS_PATH")
    os.environ["AF_TASK_METRICS_PATH"] = str(metrics_path)
    start = time.perf_counter_ns()
    chunk_count = 0
    try:
        if case_name == "typed":
            frames = load_datasets(
                numbers[0],
                input_root=str(input_root),
                usecols=["trade_date", "close", "volume"],
                dtype={"trade_date": "string", "close": "float64", "volume": "int64"},
            )
            frame = next(iter(frames.values()))
            row_count = len(frame)
            expected_columns = ["ts_code", "trade_date", "close", "volume"]
            if list(frame.columns) != expected_columns:
                raise AssertionError(f"typed columns mismatch: {list(frame.columns)}")
            if str(frame["trade_date"].dtype) != "string":
                raise AssertionError(f"trade_date dtype mismatch: {frame['trade_date'].dtype}")
        elif case_name == "usecols":
            frames = load_datasets(
                numbers[0],
                input_root=str(input_root),
                usecols=["trade_date", "close"],
            )
            frame = next(iter(frames.values()))
            row_count = len(frame)
            if list(frame.columns) != ["ts_code", "trade_date", "close"]:
                raise AssertionError(f"usecols projection mismatch: {list(frame.columns)}")
        elif case_name == "chunk":
            row_count = 0
            for chunk in iter_datasets(
                numbers[0],
                chunksize=chunk_size,
                input_root=str(input_root),
                usecols=["trade_date", "close", "volume"],
            ):
                chunk_count += 1
                row_count += len(chunk)
                if len(chunk) > chunk_size:
                    raise AssertionError("chunk exceeds configured complete-row chunk size")
        elif case_name == "small_files":
            frames = load_datasets(
                ",".join(numbers),
                input_root=str(input_root),
                usecols=["trade_date", "close"],
            )
            row_count = sum(len(frame) for frame in frames.values())
            if len(frames) != int(case["expectedFiles"]):
                raise AssertionError(f"small-file dataset count mismatch: {len(frames)}")
        else:
            raise ValueError(f"unknown benchmark case: {case_name}")
    finally:
        if previous_metrics_path is None:
            os.environ.pop("AF_TASK_METRICS_PATH", None)
        else:
            os.environ["AF_TASK_METRICS_PATH"] = previous_metrics_path
    elapsed_millis = (time.perf_counter_ns() - start) // 1_000_000

    if row_count != int(case["expectedRows"]):
        raise AssertionError(f"row count mismatch: expected={case['expectedRows']} actual={row_count}")
    content = metrics_path.read_text(encoding="utf-8")
    logical_bytes, open_count, complete, missing = parse_loader_metrics_jsonl(content)
    if not complete:
        raise AssertionError(f"loader metrics incomplete: {missing}")
    expected_bytes = sum(
        int(dataset["byteSize"])
        for dataset in manifest["datasets"]
        if int(dataset["number"]) in {int(number) for number in numbers}
    )
    if logical_bytes != expected_bytes:
        raise AssertionError(f"logical bytes mismatch: expected={expected_bytes} actual={logical_bytes}")
    if open_count != len(numbers):
        raise AssertionError(f"open count mismatch: expected={len(numbers)} actual={open_count}")

    try:
        import pandas as pd  # noqa: PLC0415

        pandas_version = pd.__version__
    except Exception:
        pandas_version = None
    return {
        "schemaVersion": RESULT_SCHEMA,
        "status": "PASS",
        "case": case_name,
        "preset": manifest["preset"],
        "fixtureManifestDigest": _manifest_digest(manifest_path),
        "elapsedMillis": elapsed_millis,
        "processPeakRssKiB": _peak_rss_kib(),
        "rowCount": row_count,
        "datasetCount": len(numbers),
        "chunkCount": chunk_count,
        "logicalBytesScanned": logical_bytes,
        "datasetOpenCount": open_count,
        "loaderMetricsPath": str(metrics_path.relative_to(fixture_root)),
        "runtime": {
            "python": platform.python_version(),
            "pandas": pandas_version,
            "platform": platform.platform(),
        },
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--fixture-root", type=Path, required=True)
    parser.add_argument("--case", choices=("typed", "usecols", "chunk", "small_files"), required=True)
    parser.add_argument("--chunk-size", type=int, default=50_000)
    parser.add_argument("--json-out", type=Path, required=True)
    parser.add_argument("--csv-out", type=Path, required=True)
    args = parser.parse_args()
    result = run_case(args.fixture_root.resolve(), args.case, args.chunk_size)
    args.json_out.parent.mkdir(parents=True, exist_ok=True)
    args.json_out.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    _append_csv(args.csv_out, result)
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
