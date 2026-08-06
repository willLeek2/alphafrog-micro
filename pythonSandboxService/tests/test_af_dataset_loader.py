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

    def test_load_run_level_dataset_comma_separated_numbers(self) -> None:
        ds1 = _write_run_dataset(self.sandbox, "1", "000001.SZ")
        ds3 = _write_run_dataset(self.sandbox, "3", "000003.SZ")
        _write_run_dataset_index(
            self.sandbox,
            [
                {"agent_run_dataset_id": "1", "dataset_file_path": str(ds1), "from_ts_code": "000001.SZ"},
                {"agent_run_dataset_id": "3", "dataset_file_path": str(ds3), "from_ts_code": "000003.SZ"},
            ],
        )

        result = load_datasets("1,3", input_root=str(self.input_root))
        self.assertEqual(set(result.keys()), {"000001.SZ", "000003.SZ"})
        self.assertEqual(len(result["000001.SZ"]), 2)
        self.assertEqual(len(result["000003.SZ"]), 2)

    def test_load_run_level_dataset_duplicate_keys_keep_both_frames(self) -> None:
        ds1 = _write_run_dataset(self.sandbox, "1", "UNCERTAIN", file_name="one.csv")
        ds2 = _write_run_dataset(self.sandbox, "2", "UNCERTAIN", file_name="two.csv")
        _write_run_dataset_index(
            self.sandbox,
            [
                {"agent_run_dataset_id": "1", "dataset_file_path": str(ds1), "from_ts_code": "UNCERTAIN"},
                {"agent_run_dataset_id": "2", "dataset_file_path": str(ds2), "from_ts_code": "UNCERTAIN"},
            ],
        )

        result = load_datasets("1,2", input_root=str(self.input_root))
        self.assertEqual(set(result.keys()), {"1", "2"})
        self.assertEqual(len(result["1"]), 2)
        self.assertEqual(len(result["2"]), 2)

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

    def test_load_run_level_manifest_comma_separated_numbers(self) -> None:
        ds1 = _write_run_dataset(self.sandbox, "1", "000001.SZ")
        ds2 = _write_run_dataset(self.sandbox, "2", "000002.SZ")
        _write_run_dataset_index(
            self.sandbox,
            [
                {"agent_run_dataset_id": "1", "dataset_file_path": str(ds1), "from_ts_code": "000001.SZ"},
                {"agent_run_dataset_id": "2", "dataset_file_path": str(ds2), "from_ts_code": "000002.SZ"},
            ],
        )

        manifest_path_1 = self.sandbox / "_agent_run_manifest_1" / "manifest.json"
        manifest_path_2 = self.sandbox / "_agent_run_manifest_2" / "manifest.json"
        _write_run_manifest_json(
            manifest_path_1,
            [{"tsCode": "000001.SZ", "datasetId": "1", "status": "ready"}],
        )
        _write_run_manifest_json(
            manifest_path_2,
            [
                {"tsCode": "000002.SZ", "datasetId": "2", "status": "ready"},
                {"tsCode": "000003.SZ", "datasetId": "3", "status": "failed", "errorCode": "MISS"},
            ],
        )
        _write_run_manifest_index(
            self.sandbox,
            [
                {"agent_run_manifest_id": "1", "manifest_file_path": str(manifest_path_1), "related_dataset_ids": "1"},
                {"agent_run_manifest_id": "2", "manifest_file_path": str(manifest_path_2), "related_dataset_ids": "2"},
            ],
        )

        result = load_manifest("1,2", input_root=str(self.input_root))
        self.assertEqual(len(result.frame), 4)
        self.assertEqual(set(result.frame["ts_code"].unique()), {"000001.SZ", "000002.SZ"})
        self.assertEqual(len(result.failed_members), 1)
        self.assertEqual(result.failed_members[0]["tsCode"], "000003.SZ")

    def test_invalid_run_level_dataset_raises(self) -> None:
        _write_run_dataset_index(
            self.sandbox,
            [{"agent_run_dataset_id": "1", "dataset_file_path": "/tmp/x.csv", "from_ts_code": "000300.SH"}],
        )
        with self.assertRaises(FileNotFoundError):
            load_datasets("999", input_root=str(self.input_root))

    def test_load_dataset_usecols_and_dtype(self) -> None:
        csv_path = _write_run_dataset(self.sandbox, "1", "000300.SH")
        _write_run_dataset_index(
            self.sandbox,
            [{"agent_run_dataset_id": "1", "dataset_file_path": str(csv_path), "from_ts_code": "000300.SH"}],
        )

        frame = load_datasets(
            "1",
            input_root=str(self.input_root),
            usecols=["trade_date", "close"],
            dtype={"trade_date": "Int64", "close": "float64"},
        )["000300.SH"]

        self.assertEqual(list(frame.columns), ["ts_code", "trade_date", "close"])
        self.assertEqual(str(frame["trade_date"].dtype), "Int64")
        self.assertEqual(str(frame["close"].dtype), "float64")

    def test_iter_datasets_chunksize_is_complete_rows(self) -> None:
        csv_path = self.sandbox / "rows.csv"
        csv_path.write_text(
            "trade_date,close\n20240101,1\n20240102,2\n20240103,3\n20240104,4\n20240105,5\n",
            encoding="utf-8",
        )
        _write_run_dataset_index(
            self.sandbox,
            [{"agent_run_dataset_id": "1", "dataset_file_path": str(csv_path), "from_ts_code": "000300.SH"}],
        )

        chunks = list(iter_datasets("1", 2, input_root=str(self.input_root)))

        self.assertEqual([len(chunk) for chunk in chunks], [2, 2, 1])
        self.assertTrue(all("ts_code" in chunk.columns for chunk in chunks))

    def test_iter_manifest_chunks_streams_ready_members(self) -> None:
        ds1 = _write_run_dataset(self.sandbox, "1", "000001.SZ")
        ds2 = _write_run_dataset(self.sandbox, "2", "000002.SZ")
        _write_run_dataset_index(
            self.sandbox,
            [
                {"agent_run_dataset_id": "1", "dataset_file_path": str(ds1), "from_ts_code": "000001.SZ"},
                {"agent_run_dataset_id": "2", "dataset_file_path": str(ds2), "from_ts_code": "000002.SZ"},
            ],
        )
        manifest_path = self.sandbox / "_run_manifest_1" / "manifest.json"
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

        chunks = list(iter_manifest_chunks("1", 1, input_root=str(self.input_root)))

        self.assertEqual(len(chunks), 4)
        self.assertTrue(all(len(chunk) == 1 for chunk in chunks))

    def test_150k_manifest_compatibility_and_25k_complete_row_chunks(self) -> None:
        csv_path = self.sandbox / "large.csv"
        rows = ["trade_date,close"]
        rows.extend(f"20240101,{index}.0" for index in range(150_000))
        csv_path.write_text("\n".join(rows) + "\n", encoding="utf-8")
        _write_run_dataset_index(
            self.sandbox,
            [{"agent_run_dataset_id": "1", "dataset_file_path": str(csv_path), "from_ts_code": "000300.SH"}],
        )
        manifest_path = self.sandbox / "_run_manifest_1" / "manifest.json"
        _write_run_manifest_json(
            manifest_path,
            [{"tsCode": "000300.SH", "datasetId": "1", "status": "ready"}],
        )
        _write_run_manifest_index(
            self.sandbox,
            [{"agent_run_manifest_id": "1", "manifest_file_path": str(manifest_path), "related_dataset_ids": "1"}],
        )

        loaded = load_manifest("1", input_root=str(self.input_root))
        chunks = list(iter_manifest_chunks("1", 25_000, input_root=str(self.input_root)))

        self.assertEqual(len(loaded.frame), 150_000)
        self.assertEqual([len(chunk) for chunk in chunks], [25_000] * 6)

    def test_loader_metric_jsonl_contains_no_real_path(self) -> None:
        csv_path = _write_run_dataset(self.sandbox, "1", "000300.SH")
        metrics_path = self.sandbox / "metrics" / "loader_metrics.jsonl"
        _write_run_dataset_index(
            self.sandbox,
            [{"agent_run_dataset_id": "1", "dataset_file_path": str(csv_path), "from_ts_code": "000300.SH"}],
        )

        with patch.dict(os.environ, {"AF_TASK_METRICS_PATH": str(metrics_path)}):
            load_datasets("1", input_root=str(self.input_root), usecols=["close"])

        metric = json.loads(metrics_path.read_text(encoding="utf-8").strip())
        self.assertEqual(metric["schema_version"], "loader_metric_v1")
        self.assertEqual(metric["datasetNumber"], "1")
        self.assertEqual(metric["openCount"], 1)
        self.assertEqual(metric["selectedColumnCount"], 1)
        self.assertEqual(metric["totalColumnCount"], 2)
        self.assertNotIn(str(csv_path), metrics_path.read_text(encoding="utf-8"))

    def test_load_read_profile_uses_public_metadata(self) -> None:
        csv_path = _write_run_dataset(self.sandbox, "1", "000300.SH")
        _write_run_dataset_index(
            self.sandbox,
            [{"agent_run_dataset_id": "1", "dataset_file_path": str(csv_path), "from_ts_code": "000300.SH"}],
        )
        (self.sandbox / "paths_dataset_meta.json").write_text(
            json.dumps({
                "schema_version": "agent_run_dataset_meta_v1",
                "datasets": {
                    "1": {
                        "readProfiles": {"price": ["trade_date", "close"]},
                        "recommendedDtype": {"trade_date": "Int64", "close": "float64"},
                    }
                },
            }),
            encoding="utf-8",
        )

        frame = load_read_profile("1", "price", input_root=str(self.input_root))["000300.SH"]

        self.assertEqual(list(frame.columns), ["ts_code", "trade_date", "close"])
        self.assertEqual(str(frame["trade_date"].dtype), "Int64")

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

    def test_public_metadata_golden_fixtures_are_versioned_and_path_free(self) -> None:
        fixtures = Path(__file__).parent / "fixtures"
        dataset_meta = json.loads((fixtures / "paths_dataset_meta_v1.json").read_text(encoding="utf-8"))
        manifest_meta = json.loads((fixtures / "path_manifest_meta_v1.json").read_text(encoding="utf-8"))

        self.assertEqual(dataset_meta["schema_version"], "agent_run_dataset_meta_v1")
        self.assertEqual(manifest_meta["schema_version"], "agent_run_manifest_meta_v1")
        self.assertEqual(manifest_meta["manifests"]["3"]["memberNumbers"], [7, 9])
        encoded = json.dumps([dataset_meta, manifest_meta])
        for forbidden in ("originalId", "persistedPath", "sourcePath", "/Users/", "/sandbox/"):
            self.assertNotIn(forbidden, encoded)


if __name__ == "__main__":
    unittest.main()
