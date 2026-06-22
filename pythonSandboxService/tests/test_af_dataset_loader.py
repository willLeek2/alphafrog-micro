from __future__ import annotations

import json
import sys
import tempfile
import types
import unittest
from pathlib import Path

import pandas as pd

llm_sandbox = types.ModuleType("llm_sandbox")
llm_sandbox.SandboxSession = object
llm_sandbox_exceptions = types.ModuleType("llm_sandbox.exceptions")
llm_sandbox_exceptions.SandboxTimeoutError = TimeoutError
sys.modules.setdefault("llm_sandbox", llm_sandbox)
sys.modules.setdefault("llm_sandbox.exceptions", llm_sandbox_exceptions)

from app.af_dataset_loader import load_datasets, load_manifest


def _write_run_dataset(sandbox: Path, dataset_number: str, ts_code: str, file_name: str | None = None) -> Path:
    """Write a dataset CSV under sandbox and return its path."""
    data_dir = sandbox / "_data"
    data_dir.mkdir(parents=True, exist_ok=True)
    name = file_name or f"{ts_code.replace('.', '_')}.csv"
    csv_path = data_dir / name
    csv_path.write_text(f"trade_date,close\n20240101,10.0\n20240102,11.0\n", encoding="utf-8")
    return csv_path


def _write_run_dataset_index(
    sandbox: Path,
    rows: list[dict],
) -> None:
    paths_csv = sandbox / "paths_dataset.csv"
    lines = ["agent_run_dataset_id,dataset_file_path,from_ts_code"]
    for row in rows:
        lines.append(
            f"{row['agent_run_dataset_id']},{row['dataset_file_path']},{row.get('from_ts_code', 'UNCERTAIN')}"
        )
    paths_csv.write_text("\n".join(lines), encoding="utf-8")


def _write_run_manifest_index(
    sandbox: Path,
    rows: list[dict],
) -> None:
    manifests_csv = sandbox / "path_manifest.csv"
    lines = ["agent_run_manifest_id,manifest_file_path,related_dataset_ids"]
    for row in rows:
        lines.append(
            f"{row['agent_run_manifest_id']},{row['manifest_file_path']},{row.get('related_dataset_ids', '')}"
        )
    manifests_csv.write_text("\n".join(lines), encoding="utf-8")


def _write_run_manifest_json(manifest_path: Path, members: list[dict]) -> None:
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "manifestId": f"agent-run-manifest-{manifest_path.parent.name.split('_')[-1]}",
        "kind": "agent_run_manifest",
        "memberCount": len(members),
        "readyCount": sum(1 for m in members if m.get("status") == "ready"),
        "failedCount": sum(1 for m in members if m.get("status") == "failed"),
        "members": members,
    }
    manifest_path.write_text(json.dumps(payload), encoding="utf-8")


class RunLevelDatasetLoaderTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.sandbox = Path(self._tmp.name)
        self.input_root = self.sandbox / "input"
        self.input_root.mkdir(parents=True, exist_ok=True)

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def test_load_run_level_dataset(self) -> None:
        csv_path = _write_run_dataset(self.sandbox, "1", "000300.SH")
        _write_run_dataset_index(
            self.sandbox,
            [{"agent_run_dataset_id": "1", "dataset_file_path": str(csv_path), "from_ts_code": "000300.SH"}],
        )

        result = load_datasets("1", input_root=str(self.input_root))
        self.assertIn("000300.SH", result)
        df = result["000300.SH"]
        self.assertEqual(list(df.columns), ["ts_code", "trade_date", "close"])
        self.assertEqual(len(df), 2)
        self.assertTrue((df["ts_code"] == "000300.SH").all())

    def test_load_run_level_dataset_uncertain_ts_code(self) -> None:
        csv_path = _write_run_dataset(self.sandbox, "1", "UNCERTAIN", file_name="data.csv")
        _write_run_dataset_index(
            self.sandbox,
            [{"agent_run_dataset_id": "1", "dataset_file_path": str(csv_path), "from_ts_code": "UNCERTAIN"}],
        )

        result = load_datasets("1", input_root=str(self.input_root))
        # When from_ts_code is UNCERTAIN we key by the run-level number.
        self.assertIn("1", result)
        df = result["1"]
        self.assertEqual(len(df), 2)

    def test_load_run_level_manifest(self) -> None:
        ds1 = _write_run_dataset(self.sandbox, "1", "000001.SZ")
        ds2 = _write_run_dataset(self.sandbox, "2", "000002.SZ")
        _write_run_dataset_index(
            self.sandbox,
            [
                {"agent_run_dataset_id": "1", "dataset_file_path": str(ds1), "from_ts_code": "000001.SZ"},
                {"agent_run_dataset_id": "2", "dataset_file_path": str(ds2), "from_ts_code": "000002.SZ"},
            ],
        )

        manifest_dir = self.sandbox / "_agent_run_manifest_1"
        manifest_path = manifest_dir / "manifest.json"
        _write_run_manifest_json(
            manifest_path,
            [
                {"tsCode": "000001.SZ", "datasetId": "1", "status": "ready"},
                {"tsCode": "000002.SZ", "datasetId": "2", "status": "ready"},
            ],
        )
        _write_run_manifest_index(
            self.sandbox,
            [{"agent_run_manifest_id": "1", "manifest_file_path": str(manifest_path), "related_dataset_ids": "1#2"}],
        )

        result = load_manifest("1", input_root=str(self.input_root))
        self.assertEqual(len(result.frame), 4)
        self.assertEqual(set(result.frame["ts_code"].unique()), {"000001.SZ", "000002.SZ"})
        self.assertEqual(result.failed_members, [])
        self.assertEqual(result.skipped_members, [])

    def test_load_run_level_manifest_with_failed_member(self) -> None:
        ds1 = _write_run_dataset(self.sandbox, "1", "000001.SZ")
        _write_run_dataset_index(
            self.sandbox,
            [{"agent_run_dataset_id": "1", "dataset_file_path": str(ds1), "from_ts_code": "000001.SZ"}],
        )

        manifest_dir = self.sandbox / "_agent_run_manifest_1"
        manifest_path = manifest_dir / "manifest.json"
        _write_run_manifest_json(
            manifest_path,
            [
                {"tsCode": "000001.SZ", "datasetId": "1", "status": "ready"},
                {"tsCode": "000002.SZ", "datasetId": "2", "status": "failed", "errorCode": "FETCH_ERROR", "errorMessage": "boom"},
            ],
        )
        _write_run_manifest_index(
            self.sandbox,
            [{"agent_run_manifest_id": "1", "manifest_file_path": str(manifest_path), "related_dataset_ids": "1"}],
        )

        result = load_manifest("1", input_root=str(self.input_root))
        self.assertEqual(len(result.frame), 2)
        self.assertEqual(len(result.failed_members), 1)
        self.assertEqual(result.failed_members[0]["tsCode"], "000002.SZ")

    def test_invalid_run_level_dataset_raises(self) -> None:
        _write_run_dataset_index(
            self.sandbox,
            [{"agent_run_dataset_id": "1", "dataset_file_path": "/tmp/x.csv", "from_ts_code": "000300.SH"}],
        )
        with self.assertRaises(FileNotFoundError):
            load_datasets("999", input_root=str(self.input_root))

    def test_legacy_mode_still_works_without_run_csv(self) -> None:
        # No paths_dataset.csv → fall back to legacy /sandbox/input/<id>/<id>.csv
        dataset_dir = self.input_root / "stock-a"
        dataset_dir.mkdir(parents=True, exist_ok=True)
        csv_path = dataset_dir / "stock-a.csv"
        csv_path.write_text("ts_code,close\n000001.SZ,1.0\n", encoding="utf-8")
        (dataset_dir / "stock-a.meta.json").write_text("{}", encoding="utf-8")

        result = load_datasets("stock-a", input_root=str(self.input_root))
        self.assertIn("000001.SZ", result)
        self.assertEqual(len(result["000001.SZ"]), 1)


if __name__ == "__main__":
    unittest.main()
