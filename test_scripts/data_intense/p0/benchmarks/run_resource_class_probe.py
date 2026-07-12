#!/usr/bin/env python3
"""Probe the production STANDARD/HEAVY configuration and usage identity contract."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path


def _repo_root() -> Path:
    return Path(__file__).resolve().parents[4]


def probe() -> dict[str, object]:
    service_root = _repo_root() / "pythonSandboxService"
    sys.path.insert(0, str(service_root))
    from app.config import load_config  # noqa: PLC0415
    from app.resource_usage import SandboxResourceUsageCollector  # noqa: PLC0415

    config = load_config()

    class Sampler:
        def __init__(self) -> None:
            self.calls = 0

        def __call__(self) -> tuple[int, int]:
            self.calls += 1
            return self.calls * 1_000_000, self.calls * 1024

    classes = []
    for resource_class, units, memory_limit in (
        ("STANDARD", 1, config.standard_memory_limit_bytes),
        ("HEAVY", 3, config.heavy_memory_limit_bytes),
    ):
        collector = SandboxResourceUsageCollector(
            resource_class=resource_class,
            sampling_interval_millis=60_000,
            sampler_factory=lambda _container_id: Sampler(),
        )
        collector.start(f"{resource_class.lower()}-probe")
        usage = collector.finish(
            container_id=f"{resource_class.lower()}-probe",
            queue_wait_millis=0,
            prepare_millis=0,
            execution_wall_millis=0,
            cleanup_millis=0,
            loader_metrics_jsonl="",
            artifact_bytes_written=0,
            temporary_bytes_written=0,
            exit_reason="SUCCESS",
            oom_killed=False,
            timed_out=False,
        )
        if usage.resource_class != resource_class or not usage.attribution_complete:
            raise AssertionError(f"collector resource class contract failed for {resource_class}")
        classes.append(
            {
                "resourceClass": resource_class,
                "capacityUnits": units,
                "memoryLimitBytes": memory_limit,
                "collectorResourceClass": usage.resource_class,
                "collectorAttributionComplete": usage.attribution_complete,
            }
        )
    if config.heavy_memory_limit_bytes <= config.standard_memory_limit_bytes:
        raise AssertionError("HEAVY memory limit must exceed STANDARD")
    return {
        "schemaVersion": "data_intense_resource_class_probe_v1",
        "status": "PASS",
        "poolEnabled": config.pool_enabled,
        "containerMaxConcurrency": config.container_max_concurrency,
        "classes": classes,
        "measurementScope": "configuration_and_collector_identity_not_live_container_throughput",
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--json-out", type=Path, required=True)
    args = parser.parse_args()
    result = probe()
    args.json_out.parent.mkdir(parents=True, exist_ok=True)
    args.json_out.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
