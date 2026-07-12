#!/usr/bin/env python3
"""Generate deterministic run-level CSV fixtures for the P0 data benchmarks."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import shutil
from pathlib import Path


SCHEMA_VERSION = "data_intense_fixture_manifest_v1"
CSV_COLUMNS = [
    "ts_code",
    "trade_date",
    "open",
    "high",
    "low",
    "close",
    "volume",
    "amount",
    "sector",
]


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return "sha256:" + digest.hexdigest()


def _write_csv(path: Path, rows: int, ts_code: str) -> dict[str, object]:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle, lineterminator="\n")
        writer.writerow(CSV_COLUMNS)
        for index in range(rows):
            base = 10.0 + (index % 10_000) / 100.0
            writer.writerow(
                (
                    ts_code,
                    20200101 + (index % 2000),
                    f"{base:.2f}",
                    f"{base + 0.80:.2f}",
                    f"{base - 0.60:.2f}",
                    f"{base + 0.20:.2f}",
                    1000 + (index % 100_000),
                    f"{(base + 0.20) * (1000 + index % 100_000):.2f}",
                    f"sector-{index % 12:02d}",
                )
            )
    return {
        "rowCount": rows,
        "byteSize": path.stat().st_size,
        "sha256": _sha256(path),
    }


def _dataset(
    root: Path,
    number: int,
    name: str,
    rows: int,
    ts_code: str,
) -> dict[str, object]:
    path = root / "datasets" / f"{name}.csv"
    measurements = _write_csv(path, rows, ts_code)
    return {
        "number": number,
        "name": name,
        "path": str(path.resolve()),
        "tsCode": ts_code,
        **measurements,
    }


def generate(root: Path, preset: str) -> dict[str, object]:
    if root.exists():
        if root.is_symlink():
            raise ValueError("fixture root must not be a symlink")
        marker = root / "fixture_manifest.json"
        try:
            existing = json.loads(marker.read_text(encoding="utf-8"))
        except Exception as error:
            raise ValueError("refusing to replace a directory without a valid fixture manifest") from error
        if existing.get("schemaVersion") != SCHEMA_VERSION:
            raise ValueError("refusing to replace a directory owned by another fixture schema")
        shutil.rmtree(root)
    (root / "sandbox" / "input").mkdir(parents=True)

    if preset == "smoke":
        row_specs = [(1, "rows_1k", 1_000, "150000.SM"), (2, "rows_5k", 5_000, "500000.SM")]
        small_count, small_rows = 5, 100
    else:
        row_specs = [
            (1, "rows_150k", 150_000, "150000.FX"),
            (2, "rows_500k", 500_000, "500000.FX"),
        ]
        small_count, small_rows = 100, 1_000

    datasets = [_dataset(root, *spec) for spec in row_specs]
    for offset in range(small_count):
        datasets.append(
            _dataset(
                root,
                101 + offset,
                f"small_{offset + 1:03d}",
                small_rows,
                f"S{offset + 1:05d}.FX",
            )
        )

    sandbox_root = root / "sandbox"
    with (sandbox_root / "paths_dataset.csv").open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle, lineterminator="\n")
        writer.writerow(("agent_run_dataset_id", "dataset_file_path", "from_ts_code"))
        for dataset in datasets:
            writer.writerow((dataset["number"], dataset["path"], dataset["tsCode"]))

    public_metadata = {
        "schema_version": "agent_run_dataset_meta_v1",
        "datasets": {
            str(dataset["number"]): {
                "metadataStatus": "complete",
                "rowCount": dataset["rowCount"],
                "columns": CSV_COLUMNS,
                "recommendedUsecols": ["trade_date", "close", "volume"],
                "recommendedDtype": {
                    "trade_date": "string",
                    "close": "float64",
                    "volume": "int64",
                },
                "readProfiles": {
                    "price_volume": ["trade_date", "close", "volume"],
                    "close_only": ["trade_date", "close"],
                },
            }
            for dataset in datasets
        },
    }
    (sandbox_root / "paths_dataset_meta.json").write_text(
        json.dumps(public_metadata, ensure_ascii=False, sort_keys=True, separators=(",", ":")),
        encoding="utf-8",
    )

    manifest: dict[str, object] = {
        "schemaVersion": SCHEMA_VERSION,
        "preset": preset,
        "datasets": datasets,
        "cases": {
            "typed": {"datasetNumbers": [1], "expectedRows": row_specs[0][2]},
            "usecols": {"datasetNumbers": [2], "expectedRows": row_specs[1][2]},
            "chunk": {"datasetNumbers": [2], "expectedRows": row_specs[1][2]},
            "small_files": {
                "datasetNumbers": [101 + index for index in range(small_count)],
                "expectedRows": small_count * small_rows,
                "expectedFiles": small_count,
            },
        },
    }
    manifest_path = root / "fixture_manifest.json"
    manifest_path.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--preset", choices=("full", "smoke"), default="full")
    args = parser.parse_args()
    manifest = generate(args.root.resolve(), args.preset)
    summary = {
        "schemaVersion": "data_intense_fixture_generation_result_v1",
        "status": "PASS",
        "preset": args.preset,
        "root": str(args.root.resolve()),
        "datasetCount": len(manifest["datasets"]),
        "rowCount": sum(int(item["rowCount"]) for item in manifest["datasets"]),
        "byteSize": sum(int(item["byteSize"]) for item in manifest["datasets"]),
    }
    print(json.dumps(summary, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
