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

    def test_measured_zero_stays_zero_and_complete(self) -> None:
        samples = iter([(1_000_000, 0), (1_000_000, 0)])
        collector = SandboxResourceUsageCollector(
            "STANDARD",
            sampling_interval_millis=60_000,
            sampler_factory=lambda _container_id: lambda: next(samples),
        )
        collector.start("zero")
        usage = collector.finish(
            container_id="zero",
            queue_wait_millis=0,
            prepare_millis=0,
            execution_wall_millis=0,
            cleanup_millis=0,
            loader_metrics_jsonl=(
                '{"schema_version":"loader_metric_v1","logicalBytes":0,"openCount":0}\n'
            ),
            artifact_bytes_written=0,
            temporary_bytes_written=0,
            exit_reason="SUCCEEDED",
            oom_killed=False,
            timed_out=False,
        )

        self.assertEqual(usage.cpu_millis, 0)
        self.assertEqual(usage.memory_peak_bytes, 0)
        self.assertEqual(usage.logical_bytes_scanned, 0)
        self.assertEqual(usage.dataset_open_count, 0)
        self.assertTrue(usage.attribution_complete)
        self.assertEqual(usage.missing_fields, [])

    def test_sampling_failure_nulls_affected_p0_fields(self) -> None:
        samples = iter([(1_000_000, 100)])
        collector = SandboxResourceUsageCollector(
            "STANDARD",
            sampling_interval_millis=60_000,
            sampler_factory=lambda _container_id: lambda: next(samples),
        )
        collector.start("sampling-partial")
        usage = collector.finish(
            container_id="sampling-partial",
            queue_wait_millis=0,
            prepare_millis=0,
            execution_wall_millis=1,
            cleanup_millis=1,
            loader_metrics_jsonl=(
                '{"schema_version":"loader_metric_v1","logicalBytes":0,"openCount":0}\n'
            ),
            artifact_bytes_written=0,
            temporary_bytes_written=0,
            exit_reason="EXECUTION_ERROR",
            oom_killed=False,
            timed_out=False,
        )

        self.assertFalse(usage.attribution_complete)
        self.assertIsNone(usage.cpu_millis)
        self.assertIsNone(usage.memory_peak_bytes)
        self.assertEqual(
            usage.missing_fields,
            ["cpuMillis", "memoryPeakBytes"],
        )

    def test_loader_partial_nulls_affected_p0_fields(self) -> None:
        samples = iter([(1_000_000, 100), (6_000_000, 250)])
        collector = SandboxResourceUsageCollector(
            "STANDARD",
            sampling_interval_millis=60_000,
            sampler_factory=lambda _container_id: lambda: next(samples),
        )
        collector.start("loader-partial")
        usage = collector.finish(
            container_id="loader-partial",
            queue_wait_millis=0,
            prepare_millis=0,
            execution_wall_millis=0,
            cleanup_millis=0,
            loader_metrics_jsonl=(
                '{"schema_version":"loader_metric_v1","logicalBytes":10,"openCount":1}\n'
                "{bad-json\n"
            ),
            artifact_bytes_written=0,
            temporary_bytes_written=0,
            exit_reason="SUCCEEDED",
            oom_killed=False,
            timed_out=False,
        )

        self.assertIsNone(usage.logical_bytes_scanned)
        self.assertIsNone(usage.dataset_open_count)
        self.assertFalse(usage.attribution_complete)
        self.assertEqual(
            usage.missing_fields,
            ["logicalBytesScanned", "datasetOpenCount"],
        )

    def test_partial_last_loader_line_is_ignored_and_marks_incomplete(self) -> None:
        logical, opens, complete, missing = parse_loader_metrics_jsonl(
            '{"schema_version":"loader_metric_v1","logicalBytes":10,"openCount":1}\n{"schema_version"'
        )
        self.assertEqual((logical, opens), (10, 1))
        self.assertFalse(complete)
        self.assertEqual(missing, ["loaderMetricsMalformed"])


if __name__ == "__main__":
    unittest.main()
