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

    def test_wrapper_records_preserve_nested_values_and_projection(self):
        rows = [{"label": "a", "nested": [[20230103, .5]], "detail": {"valid": True}}, {"label": "b", "nested": [], "detail": None}]
        source, metadata = self.mount_json({"results": rows, "row_count": 900}, {
            "format": "json", "recordsPath": "results", "rowCount": 2, "columns": ["label", "nested", "detail"]})
        self.assertEqual(metadata["format"], "json")
        self.assertEqual(metadata["recordsPath"], "results")
        self.assertEqual(metadata["bytes"], source.stat().st_size)
        frame = next(iter(load_datasets("1", input_root=str(self.input_root)).values()))
        self.assertEqual(len(frame), 2)
        self.assertEqual(frame.loc[0, "nested"], rows[0]["nested"])
        self.assertEqual(frame.loc[0, "detail"], rows[0]["detail"])
        projected = next(iter(load_datasets("1", input_root=str(self.input_root), usecols=["label", "nested"], dtype={"label": "string"}).values()))
        self.assertEqual(str(projected["label"].dtype), "string")
        self.assertEqual(projected.loc[0, "nested"], rows[0]["nested"])
        chunks = list(iter_datasets("1", 1, input_root=str(self.input_root)))
        self.assertEqual([len(chunk) for chunk in chunks], [1, 1])
        self.assertEqual(chunks[0].iloc[0]["nested"], rows[0]["nested"])

    def test_root_object_and_object_array_do_not_guess_record_fields(self):
        from app.af_dataset_loader import read_dataset_file
        for document, expected in (({"label": "x", "results": [{"value": 3}]}, 1), ([{"label": "x"}, {"label": "y", "detail": {"a": 1}}], 2)):
            source, _ = self.mount_json(document, {"format": "json"})
            frame = read_dataset_file(source)
            self.assertEqual(len(frame), expected)
            if isinstance(document, dict):
                self.assertEqual(frame.iloc[0]["results"], document["results"])

    def test_empty_records_keep_declared_columns(self):
        self.mount_json({"results": []}, {"format": "json", "recordsPath": "results", "rowCount": 0, "columns": ["label", "nested"]})
        frame = next(iter(load_datasets("1", input_root=str(self.input_root)).values()))
        self.assertEqual(len(frame), 0)
        self.assertEqual(list(frame.columns), ["ts_code", "label", "nested"])

    def test_invalid_record_shapes_fail_instead_of_falling_back_to_csv(self):
        from app.af_dataset_loader import read_dataset_file
        for document, meta in (({"wrong": []}, {"format": "json", "recordsPath": "records"}), ({"records": {}}, {"format": "json", "recordsPath": "records"}), ([{"a": 1}, 2], {"format": "json"}), (42, {"format": "json"})):
            source, _ = self.mount_json(document, meta)
            with self.assertRaises(ValueError):
                read_dataset_file(source)
        source.write_text("{not json", encoding="utf-8")
        with self.assertRaises(json.JSONDecodeError):
            read_dataset_file(source)

    def test_parquet_uses_available_duckdb_for_loading_and_complete_row_chunks(self):
        import duckdb
        path = self.sandbox / "table.parquet"
        with duckdb.connect() as con:
            con.execute("COPY (SELECT range AS id, range * 1.5 AS value FROM range(5)) TO ? (FORMAT PARQUET)", [str(path)])
        _write_run_dataset_index(self.sandbox, [{"agent_run_dataset_id": "1", "dataset_file_path": str(path), "from_ts_code": "UNCERTAIN"}])
        frame = next(iter(load_datasets("1", input_root=str(self.input_root), usecols=["id"], dtype={"id": "Int64"}).values()))
        self.assertEqual(frame["id"].tolist(), list(range(5)))
        self.assertEqual(str(frame["id"].dtype), "Int64")
        chunks = list(iter_datasets("1", 2, input_root=str(self.input_root), usecols=["id"]))
        self.assertEqual([len(part) for part in chunks], [2, 2, 1])
        self.assertEqual(pd.concat(chunks)["id"].tolist(), list(range(5)))

    def test_json_loader_metric_does_not_read_json_as_csv(self):
        self.mount_json({"results": [{"label": "x", "nested": [1, 2]}]}, {"format": "json", "recordsPath": "results", "columns": ["label", "nested"]})
        metrics = self.sandbox / "metrics.jsonl"
        with patch.dict(os.environ, {"AF_TASK_METRICS_PATH": str(metrics)}):
            load_datasets("1", input_root=str(self.input_root), usecols=["label"])
        metric = json.loads(metrics.read_text())
        self.assertEqual(metric["totalColumnCount"], 2)
        self.assertEqual(metric["selectedColumnCount"], 1)
        self.assertEqual(metric["openCount"], 1)

    @unittest.skipUnless(os.environ.get("AF_REAL_MEMBERS_DIR"), "真实成员文件由本地验收显式提供")
    def test_real_member_documents_through_materialized_run_index(self):
        import shutil
        from app.sandbox_runner import _materialize_agent_run_csvs, _copy_via_csv_source_paths
        for year in (2023, 2024):
            actual = Path(os.environ["AF_REAL_MEMBERS_DIR"]) / f"members-{year}.json"
            document = json.loads(actual.read_text())
            source, metadata = self.mount_json(document, {"format": "json", "recordsPath": "results", "rowCount": len(document["results"]), "columns": list(document["results"][0])})
            source.write_bytes(actual.read_bytes())
            self.assertEqual(source.read_bytes(), actual.read_bytes())
            self.assertEqual(json.loads(source.read_text()), document)
            paths_csv = f"agent_run_dataset_id,dataset_file_path,from_ts_code,source_path\n1,/__AF_INPUT__/_run_dataset_1/data.json,UNCERTAIN,{source}\n"
            def copy_file(session, src, dest):
                Path(dest).parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(src, dest)
            def copy_text(session, text, dest):
                Path(dest).parent.mkdir(parents=True, exist_ok=True)
                Path(dest).write_text(text)
            cfg = types.SimpleNamespace(workdir=str(self.sandbox))
            with patch("app.sandbox_runner._copy_dataset_file", side_effect=copy_file), patch("app.sandbox_runner._log_in_container"), patch("app.sandbox_runner._copy_text_to_runtime", side_effect=copy_text), patch("app.sandbox_runner._atomic_copy_text_to_runtime", side_effect=copy_text):
                count, expected, failed = _copy_via_csv_source_paths(object(), cfg, "local", str(self.input_root), paths_csv, "")
                self.assertEqual((count, expected, failed), (1, 1, []))
                _materialize_agent_run_csvs(object(), cfg, str(self.input_root), paths_csv, "")
            frame = next(iter(load_datasets("1", input_root=str(self.input_root)).values()))
            self.assertEqual(len(frame), len(document["results"]))
            self.assertEqual(set(frame["ts_code"]), {row["ts_code"] for row in document["results"]})
            self.assertEqual(frame["match_conditions"].tolist(), [row["match_conditions"] for row in document["results"]])
            print(f"真实成员JSON加载通过：{year}年{len(frame)}行，代码集合和嵌套条件一致")


if __name__ == "__main__":
    unittest.main()
