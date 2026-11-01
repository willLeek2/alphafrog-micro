from __future__ import annotations

import json
import os
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest.mock import patch

import pandas as pd

llm_sandbox = types.ModuleType("llm_sandbox")
llm_sandbox.SandboxSession = object
llm_sandbox_exceptions = types.ModuleType("llm_sandbox.exceptions")
llm_sandbox_exceptions.SandboxTimeoutError = TimeoutError
sys.modules.setdefault("llm_sandbox", llm_sandbox)
sys.modules.setdefault("llm_sandbox.exceptions", llm_sandbox_exceptions)

from app.af_dataset_loader import (
    iter_datasets,
    iter_manifest_chunks,
    load_datasets,
    load_manifest,
    load_read_profile,
)


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

class TabularDatasetContractTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.sandbox = Path(self.tmp.name)
        self.input_root = self.sandbox / "input"
        self.input_root.mkdir()

    def tearDown(self):
        self.tmp.cleanup()

    def mount_json(self, document, metadata):
        from app.sandbox_runner import _build_agent_run_metadata_documents
        source = self.sandbox / "source" / "data.json"
        source.parent.mkdir(exist_ok=True)
        source.write_text(json.dumps(document), encoding="utf-8")
        source.with_suffix(".meta.json").write_text(json.dumps(metadata), encoding="utf-8")
        frame_meta, _ = _build_agent_run_metadata_documents(
            f"agent_run_dataset_id,dataset_file_path,from_ts_code,source_path\n1,{source},UNCERTAIN,{source}\n", "")
        (self.sandbox / "paths_dataset_meta.json").write_text(json.dumps(frame_meta), encoding="utf-8")
        _write_run_dataset_index(self.sandbox, [{"agent_run_dataset_id": "1", "dataset_file_path": str(source), "from_ts_code": "UNCERTAIN"}])
        return source, frame_meta["datasets"]["1"]

if __name__ == "__main__":
    unittest.main()
