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
from app.dataset_manifest import expand_dataset_ids, is_manifest_dataset


def _write_atomic(data_dir: Path, dataset_id: str, ts_code: str) -> None:
    dataset_dir = data_dir / dataset_id
    dataset_dir.mkdir(parents=True, exist_ok=True)
    csv_path = dataset_dir / f"{dataset_id}.csv"
    csv_path.write_text(f"ts_code,close\n{ts_code},1.0\n", encoding="utf-8")
    meta_path = dataset_dir / f"{dataset_id}.meta.json"
    meta_path.write_text("{}", encoding="utf-8")


def _write_manifest(
    data_dir: Path,
    manifest_id: str,
    members: list[dict],
) -> None:
    manifest_dir = data_dir / manifest_id
    manifest_dir.mkdir(parents=True, exist_ok=True)
    payload = {
        "manifestId": manifest_id,
        "kind": "dataset_manifest",
        "dataType": "stock_daily",
        "startDate": "20240101",
        "endDate": "20240131",
        "memberCount": len(members),
        "readyCount": sum(1 for m in members if m.get("status") == "ready"),
        "failedCount": sum(1 for m in members if m.get("status") == "failed"),
        "brokenCount": 0,
        "totalRowCount": 2,
        "columns": ["ts_code", "close"],
        "columnsSignature": "ts_code,close",
        "members": members,
        "createdAt": 1739428200000,
    }
    manifest_path = manifest_dir / f"{manifest_id}.manifest.json"
    manifest_path.write_text(json.dumps(payload), encoding="utf-8")
    (manifest_dir / f"{manifest_id}.meta.json").write_text("{}", encoding="utf-8")


class DatasetManifestExpansionTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.data_dir = Path(self._tmp.name)

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def test_expand_manifest_ready_members_deduped(self) -> None:
        _write_atomic(self.data_dir, "stock-a", "000001.SZ")
        _write_atomic(self.data_dir, "stock-b", "000002.SZ")
        manifest_id = "manifest-stock_daily-20240101-20240131-abc12345"
        _write_manifest(
            self.data_dir,
            manifest_id,
            [
                {
                    "tsCode": "000001.SZ",
                    "datasetId": "stock-a",
                    "status": "ready",
                    "rowCount": 1,
                },
                {
                    "tsCode": "000002.SZ",
                    "datasetId": "stock-b",
                    "status": "ready",
                    "rowCount": 1,
                },
                {
                    "tsCode": "000003.SZ",
                    "datasetId": None,
                    "status": "failed",
                    "errorCode": "EMPTY_DATA",
                    "errorMessage": "no rows",
                },
            ],
        )

        expanded = expand_dataset_ids(self.data_dir, [manifest_id, "stock-a"])
        self.assertEqual([manifest_id], expanded.manifest_ids)
        self.assertEqual(["stock-a", "stock-b"], expanded.atomic_ids)
        self.assertEqual(1, len(expanded.failed_members))
        self.assertTrue(is_manifest_dataset(self.data_dir, manifest_id))

    def test_expand_fail_fast_when_ready_member_missing(self) -> None:
        manifest_id = "manifest-stock_daily-20240101-20240131-missing01"
        _write_manifest(
            self.data_dir,
            manifest_id,
            [
                {
                    "tsCode": "000001.SZ",
                    "datasetId": "missing-atomic",
                    "status": "ready",
                    "rowCount": 1,
                }
            ],
        )
        with self.assertRaises(FileNotFoundError):
            expand_dataset_ids(self.data_dir, [manifest_id])

    def test_loader_concat_and_failed_members(self) -> None:
        _write_atomic(self.data_dir, "stock-a", "000001.SZ")
        _write_atomic(self.data_dir, "stock-b", "000002.SZ")
        manifest_id = "manifest-stock_daily-20240101-20240131-loader01"
        _write_manifest(
            self.data_dir,
            manifest_id,
            [
                {
                    "tsCode": "000001.SZ",
                    "datasetId": "stock-a",
                    "status": "ready",
                    "rowCount": 1,
                },
                {
                    "tsCode": "000002.SZ",
                    "datasetId": "stock-b",
                    "status": "ready",
                    "rowCount": 1,
                },
                {
                    "tsCode": "000003.SZ",
                    "datasetId": None,
                    "status": "failed",
                    "errorCode": "EMPTY_DATA",
                    "errorMessage": "no rows",
                },
            ],
        )

        task_input = self.data_dir / "task_input"
        task_input.mkdir()
        expanded = expand_dataset_ids(self.data_dir, [manifest_id])
        for ds_id in [manifest_id, *expanded.atomic_ids]:
            src = self.data_dir / ds_id
            dest = task_input / ds_id
            dest.mkdir()
            for file_path in src.iterdir():
                if file_path.is_file():
                    (dest / file_path.name).write_text(file_path.read_text(encoding="utf-8"), encoding="utf-8")

        result = load_manifest(manifest_id, input_root=str(task_input))
        self.assertEqual(2, len(result.frame))
        self.assertIn("ts_code", result.frame.columns)
        self.assertEqual(1, len(result.failed_members))
        by_ts = load_datasets(manifest_id, input_root=str(task_input))
        self.assertEqual({"000001.SZ", "000002.SZ"}, set(by_ts.keys()))
        self.assertIsInstance(by_ts["000001.SZ"], pd.DataFrame)


if __name__ == "__main__":
    unittest.main()
