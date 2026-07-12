#!/usr/bin/env python3
from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))

from check_consistency import check_consistency  # noqa: E402
from generate_fixtures import generate  # noqa: E402
from run_collector_consistency import build_bundle  # noqa: E402
from run_loader_benchmark import run_case  # noqa: E402
from run_resource_class_probe import probe  # noqa: E402


class BenchmarkToolsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name) / "fixtures"
        self.manifest = generate(self.root, "smoke")

    def tearDown(self) -> None:
        self.temp_dir.cleanup()

    def test_smoke_cases_validate_rows_projection_chunking_and_metrics(self) -> None:
        for case_name in ("typed", "usecols", "chunk", "small_files"):
            with self.subTest(case=case_name):
                result = run_case(self.root, case_name, chunk_size=333)
                self.assertEqual(result["status"], "PASS")
                self.assertEqual(
                    result["rowCount"],
                    self.manifest["cases"][case_name]["expectedRows"],
                )
                self.assertEqual(
                    result["datasetOpenCount"],
                    len(self.manifest["cases"][case_name]["datasetNumbers"]),
                )

    def test_collector_and_observability_consistency_oracle_detects_drift(self) -> None:
        run_case(self.root, "typed", chunk_size=333)
        metrics_path = self.root / "metrics" / "typed.jsonl"
        output_dir = Path(self.temp_dir.name) / "consistency"
        bundle = build_bundle(
            metrics_path,
            output_dir,
            estimated_rows=int(self.manifest["cases"]["typed"]["expectedRows"]),
            file_count=1,
            resource_class="STANDARD",
        )
        usage_path = Path(bundle["resourceUsage"])
        observability_path = Path(bundle["observability"])

        passing = check_consistency(metrics_path, usage_path, observability_path)
        self.assertEqual(passing["status"], "PASS")

        document = json.loads(observability_path.read_text(encoding="utf-8"))
        document["data_analysis_observability"]["summary"]["logicalBytesScanned"] += 1
        observability_path.write_text(json.dumps(document), encoding="utf-8")

        failing = check_consistency(metrics_path, usage_path, observability_path)
        self.assertEqual(failing["status"], "FAIL")
        failed_names = {item["name"] for item in failing["checks"] if item["status"] == "FAIL"}
        self.assertIn("observability_summary_aggregation", failed_names)

    def test_resource_class_probe_keeps_memory_units_and_usage_identity_aligned(self) -> None:
        result = probe()

        self.assertEqual(result["status"], "PASS")
        standard, heavy = result["classes"]
        self.assertEqual(standard["capacityUnits"], 1)
        self.assertEqual(heavy["capacityUnits"], 3)
        self.assertGreater(heavy["memoryLimitBytes"], standard["memoryLimitBytes"])
        self.assertEqual(standard["collectorResourceClass"], "STANDARD")
        self.assertEqual(heavy["collectorResourceClass"], "HEAVY")

    def test_generator_refuses_to_replace_unowned_directory(self) -> None:
        unowned = Path(self.temp_dir.name) / "unowned"
        unowned.mkdir()
        (unowned / "keep.txt").write_text("do not delete", encoding="utf-8")

        with self.assertRaisesRegex(ValueError, "refusing to replace"):
            generate(unowned, "smoke")

        self.assertEqual((unowned / "keep.txt").read_text(encoding="utf-8"), "do not delete")


if __name__ == "__main__":
    unittest.main()
