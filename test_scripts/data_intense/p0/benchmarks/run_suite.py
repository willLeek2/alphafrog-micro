#!/usr/bin/env python3
"""Orchestrate fixture generation, isolated loader cases, and consistency gates."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path


CASES = ("typed", "usecols", "chunk", "small_files")


def _run(command: list[str]) -> None:
    subprocess.run(command, check=True)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--work-dir", type=Path, required=True)
    parser.add_argument("--result-dir", type=Path, required=True)
    parser.add_argument("--preset", choices=("full", "smoke"), default="full")
    args = parser.parse_args()
    script_dir = Path(__file__).resolve().parent
    work_dir = args.work_dir.resolve()
    result_dir = args.result_dir.resolve()
    fixture_root = work_dir / "fixtures"
    result_dir.mkdir(parents=True, exist_ok=True)

    _run(
        [
            sys.executable,
            str(script_dir / "generate_fixtures.py"),
            "--root",
            str(fixture_root),
            "--preset",
            args.preset,
        ]
    )
    summary_csv = result_dir / "loader_benchmarks.csv"
    summary_csv.unlink(missing_ok=True)
    case_results = []
    for case_name in CASES:
        json_out = result_dir / f"loader_{case_name}.json"
        _run(
            [
                sys.executable,
                str(script_dir / "run_loader_benchmark.py"),
                "--fixture-root",
                str(fixture_root),
                "--case",
                case_name,
                "--json-out",
                str(json_out),
                "--csv-out",
                str(summary_csv),
            ]
        )
        case_results.append(json.loads(json_out.read_text(encoding="utf-8")))

    manifest = json.loads((fixture_root / "fixture_manifest.json").read_text(encoding="utf-8"))
    typed_case = manifest["cases"]["typed"]
    consistency_dir = result_dir / "consistency"
    _run(
        [
            sys.executable,
            str(script_dir / "run_collector_consistency.py"),
            "--loader-metrics",
            str(fixture_root / "metrics" / "typed.jsonl"),
            "--output-dir",
            str(consistency_dir),
            "--estimated-rows",
            str(typed_case["expectedRows"]),
            "--file-count",
            "1",
        ]
    )
    consistency_report = result_dir / "consistency_report.json"
    _run(
        [
            sys.executable,
            str(script_dir / "check_consistency.py"),
            "--loader-metrics",
            str(fixture_root / "metrics" / "typed.jsonl"),
            "--resource-usage",
            str(consistency_dir / "resource_usage.json"),
            "--observability",
            str(consistency_dir / "observability.json"),
            "--json-out",
            str(consistency_report),
        ]
    )
    consistency = json.loads(consistency_report.read_text(encoding="utf-8"))
    resource_class_path = result_dir / "resource_class_probe.json"
    _run(
        [
            sys.executable,
            str(script_dir / "run_resource_class_probe.py"),
            "--json-out",
            str(resource_class_path),
        ]
    )
    resource_class = json.loads(resource_class_path.read_text(encoding="utf-8"))
    suite = {
        "schemaVersion": "data_intense_benchmark_suite_result_v1",
        "status": "PASS"
        if all(result["status"] == "PASS" for result in case_results)
        and consistency["status"] == "PASS"
        and resource_class["status"] == "PASS"
        else "FAIL",
        "preset": args.preset,
        "cases": case_results,
        "consistency": consistency,
        "resourceClass": resource_class,
    }
    suite_path = result_dir / "suite_summary.json"
    suite_path.write_text(json.dumps(suite, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(suite, sort_keys=True))
    return 0 if suite["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
