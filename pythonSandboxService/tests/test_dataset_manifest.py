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

if __name__ == "__main__":
    unittest.main()
