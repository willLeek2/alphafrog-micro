from __future__ import annotations

import unittest

from app.resource_usage import SandboxResourceUsageCollector, parse_loader_metrics_jsonl


class SandboxResourceUsageCollectorTest(unittest.TestCase):
    def test_collects_container_delta_phases_and_loader_metrics(self) -> None:
        samples = iter([(1_000_000, 100), (6_000_000, 250)])

        def factory(_container_id: str):
            return lambda: next(samples)

        collector = SandboxResourceUsageCollector(
            "HEAVY", sampling_interval_millis=60_000, sampler_factory=factory
        )
        collector.start("container-1")
        usage = collector.finish(
            container_id="container-1",
            queue_wait_millis=10,
            prepare_millis=20,
            execution_wall_millis=30,
            cleanup_millis=40,
            loader_metrics_jsonl=(
                '{"schema_version":"loader_metric_v1","logicalBytes":100,"openCount":1}\n'
                '{"schema_version":"loader_metric_v1","logicalBytes":50,"openCount":2}\n'
            ),
            artifact_bytes_written=7,
            temporary_bytes_written=9,
            exit_reason="SUCCEEDED",
            oom_killed=False,
            timed_out=False,
        )

        self.assertEqual(usage.cpu_millis, 5)
        self.assertEqual(usage.memory_peak_bytes, 250)
        self.assertEqual(usage.logical_bytes_scanned, 150)
        self.assertEqual(usage.dataset_open_count, 3)
        self.assertEqual(usage.queue_wait_millis, 10)
        self.assertTrue(usage.attribution_complete)
        self.assertEqual(usage.missing_fields, [])

    def test_sampling_and_malformed_metric_fail_open_with_missing_fields(self) -> None:
        collector = SandboxResourceUsageCollector(
            "STANDARD", sampler_factory=lambda _container_id: (_ for _ in ()).throw(RuntimeError("no stats"))
        )
        collector.start("missing")
        usage = collector.finish(
            container_id="missing",
            queue_wait_millis=0,
            prepare_millis=None,
            execution_wall_millis=1,
            cleanup_millis=1,
            loader_metrics_jsonl="{bad-json\n",
            artifact_bytes_written=0,
            temporary_bytes_written=0,
            exit_reason="EXECUTION_ERROR",
            oom_killed=False,
            timed_out=False,
        )

        self.assertFalse(usage.attribution_complete)
        self.assertIn("containerSampling", usage.missing_fields)
        self.assertIn("prepareMillis", usage.missing_fields)
        self.assertIn("loaderMetricsMalformed", usage.missing_fields)

    def test_partial_last_loader_line_is_ignored_and_marks_incomplete(self) -> None:
        logical, opens, complete, missing = parse_loader_metrics_jsonl(
            '{"schema_version":"loader_metric_v1","logicalBytes":10,"openCount":1}\n{"schema_version"'
        )
        self.assertEqual((logical, opens), (10, 1))
        self.assertFalse(complete)
        self.assertEqual(missing, ["loaderMetricsMalformed"])


if __name__ == "__main__":
    unittest.main()
