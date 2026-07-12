from __future__ import annotations

import json
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest.mock import patch

llm_sandbox = types.ModuleType("llm_sandbox")
llm_sandbox.SandboxSession = object
llm_sandbox_exceptions = types.ModuleType("llm_sandbox.exceptions")
llm_sandbox_exceptions.SandboxTimeoutError = TimeoutError
sys.modules.setdefault("llm_sandbox", llm_sandbox)
sys.modules.setdefault("llm_sandbox.exceptions", llm_sandbox_exceptions)

from app.config import load_config  # noqa: E402
from app.sandbox_runner import (  # noqa: E402
    _atomic_copy_text_to_runtime,
    _build_agent_run_metadata_documents,
    create_sandbox_session,
)


class DataIntenseRuntimeTest(unittest.TestCase):
    def test_default_runtime_is_one_task_per_container_with_two_memory_classes(self) -> None:
        with patch.dict("os.environ", {}, clear=True):
            config = load_config()

        self.assertFalse(config.pool_enabled)
        self.assertEqual(config.container_max_concurrency, 1)
        self.assertEqual(config.standard_memory_limit_bytes, 512 * 1024 * 1024)
        self.assertEqual(config.heavy_memory_limit_bytes, 1536 * 1024 * 1024)
        self.assertEqual(config.task_store_path, Path("/data/sandbox_tasks/state.json"))

    def test_metadata_materialization_is_versioned_path_free_and_partial_safe(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            csv_path = root / "prices.csv"
            csv_path.write_text("ts_code,trade_date,close\n000001.SZ,20240101,10.0\n", encoding="utf-8")
            csv_path.with_suffix(".meta.json").write_text(json.dumps({
                "rowCount": 1,
                "columns": ["ts_code", "trade_date", "close"],
                "recommendedUsecols": ["ts_code", "trade_date", "close"],
                "recommendedDtype": {"trade_date": "Int64", "close": "float64"},
                "readProfiles": {"price_volume": ["ts_code", "trade_date", "close"]},
            }), encoding="utf-8")
            dataset_csv = (
                "agent_run_dataset_id,dataset_file_path,from_ts_code,source_path\n"
                f"7,/__AF_INPUT__/_run_dataset_7/prices.csv,000001.SZ,{csv_path}\n"
            )
            manifest_csv = (
                "agent_run_manifest_id,manifest_file_path,related_dataset_ids,source_path\n"
                "3,NONE,7,\n"
            )

            dataset_meta, manifest_meta = _build_agent_run_metadata_documents(dataset_csv, manifest_csv)

        self.assertEqual(dataset_meta["schema_version"], "agent_run_dataset_meta_v1")
        self.assertEqual(dataset_meta["datasets"]["7"]["metadataStatus"], "complete")
        self.assertEqual(manifest_meta["schema_version"], "agent_run_manifest_meta_v1")
        self.assertEqual(manifest_meta["manifests"]["3"]["memberNumbers"], [7])
        encoded = json.dumps([dataset_meta, manifest_meta])
        self.assertNotIn(str(csv_path), encoded)
        self.assertNotIn("sourcePath", encoded)
        self.assertNotIn("originalId", encoded)

    def test_session_uses_per_task_heavy_memory_hard_limit(self) -> None:
        captured = {}

        class FakeSession:
            def __init__(self, **kwargs):
                captured.update(kwargs)

            def open(self):
                return None

        with patch.dict("os.environ", {}, clear=True):
            config = load_config()
        with patch("app.sandbox_runner.SandboxSession", FakeSession):
            create_sandbox_session(
                config,
                execution_timeout=60,
                memory_limit_bytes=config.heavy_memory_limit_bytes,
            )

        self.assertEqual(captured["runtime_configs"]["mem_limit"], 1536 * 1024 * 1024)
        self.assertEqual(captured["runtime_configs"]["memswap_limit"], 1536 * 1024 * 1024)

    def test_public_metadata_write_uses_temp_file_then_atomic_rename(self) -> None:
        class Output:
            exit_code = 0
            stdout = ""
            stderr = ""

        class FakeSession:
            def __init__(self):
                self.destinations = []
                self.commands = []

            def copy_to_runtime(self, _source: str, dest_path: str):
                self.destinations.append(dest_path)

            def execute_command(self, command: str):
                self.commands.append(command)
                return Output()

        session = FakeSession()
        _atomic_copy_text_to_runtime(
            session,
            '{"schema_version":"agent_run_dataset_meta_v1"}',
            "/sandbox/paths_dataset_meta.json",
        )

        self.assertEqual(session.destinations, ["/sandbox/paths_dataset_meta.json.tmp"])
        self.assertIn(
            "mv /sandbox/paths_dataset_meta.json.tmp /sandbox/paths_dataset_meta.json",
            session.commands,
        )


if __name__ == "__main__":
    unittest.main()
