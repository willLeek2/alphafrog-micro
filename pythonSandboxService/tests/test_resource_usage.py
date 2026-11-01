from __future__ import annotations

import unittest

from app.resource_usage import SandboxResourceUsageCollector, parse_loader_metrics_jsonl


class SandboxResourceUsageCollectorTest(unittest.TestCase):
    def test_partial_last_loader_line_is_ignored_and_marks_incomplete(self) -> None:
        logical, opens, complete, missing = parse_loader_metrics_jsonl(
            '{"schema_version":"loader_metric_v1","logicalBytes":10,"openCount":1}\n{"schema_version"'
        )
        self.assertEqual((logical, opens), (10, 1))
        self.assertFalse(complete)
        self.assertEqual(missing, ["loaderMetricsMalformed"])


if __name__ == "__main__":
    unittest.main()
