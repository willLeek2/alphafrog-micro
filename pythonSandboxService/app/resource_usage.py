from __future__ import annotations

import json
import logging
import threading
import time
from dataclasses import dataclass
from typing import Callable

from .models import SandboxResourceUsage


logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class ContainerSample:
    monotonic_seconds: float
    cpu_total_nanos: int
    memory_bytes: int


def parse_loader_metrics_jsonl(content: str) -> tuple[int, int, bool, list[str]]:
    logical_bytes = 0
    open_count = 0
    complete = True
    missing: list[str] = []
    for line in content.splitlines():
        if not line.strip():
            continue
        try:
            row = json.loads(line)
            if row.get("schema_version") != "loader_metric_v1":
                raise ValueError("unsupported schema")
            logical_bytes += int(row["logicalBytes"])
            open_count += int(row["openCount"])
        except Exception:
            complete = False
            if "loaderMetricsMalformed" not in missing:
                missing.append("loaderMetricsMalformed")
    return logical_bytes, open_count, complete, missing


class SandboxResourceUsageCollector:
    """Best-effort per-container usage sampler for the one-task-per-container P0 path."""

    def __init__(
        self,
        resource_class: str,
        sampling_interval_millis: int = 200,
        sampler_factory: Callable[[str], Callable[[], tuple[int, int]]] | None = None,
    ) -> None:
        self.resource_class = resource_class
        self.sampling_interval_millis = sampling_interval_millis
        self._sampler_factory = sampler_factory or _docker_sampler_factory
        self._samples: list[ContainerSample] = []
        self._sample_error = False
        self._sampler: Callable[[], tuple[int, int]] | None = None
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None

    def start(self, container_id: str) -> None:
        try:
            self._sampler = self._sampler_factory(container_id)
            self._record(self._sampler)
        except Exception:
            self._sample_error = True
            return

        def poll() -> None:
            while not self._stop.wait(self.sampling_interval_millis / 1000.0):
                self._record(self._sampler)

        self._thread = threading.Thread(target=poll, name=f"sandbox-usage-{container_id[:12]}", daemon=True)
        self._thread.start()

    def finish(
        self,
        *,
        container_id: str,
        queue_wait_millis: int | None,
        prepare_millis: int | None,
        execution_wall_millis: int | None,
        cleanup_millis: int | None,
        loader_metrics_jsonl: str,
        artifact_bytes_written: int,
        temporary_bytes_written: int,
        exit_reason: str,
        oom_killed: bool,
        timed_out: bool,
    ) -> SandboxResourceUsage:
        self._stop.set()
        if self._thread is not None:
            self._thread.join(timeout=max(1.0, self.sampling_interval_millis / 500.0))
        if self._sampler is not None:
            self._record(self._sampler)
            close = getattr(self._sampler, "close", None)
            if callable(close):
                try:
                    close()
                except Exception:
                    self._sample_error = True

        missing: list[str] = []
        cpu_millis = None
        memory_peak = None
        memory_byte_millis = None
        if self._samples and not self._sample_error:
            cpu_millis = max(0, self._samples[-1].cpu_total_nanos - self._samples[0].cpu_total_nanos) // 1_000_000
            memory_peak = max(sample.memory_bytes for sample in self._samples)
            integral = 0.0
            for left, right in zip(self._samples, self._samples[1:]):
                elapsed_millis = max(0.0, (right.monotonic_seconds - left.monotonic_seconds) * 1000.0)
                integral += left.memory_bytes * elapsed_millis
            memory_byte_millis = int(integral)
        else:
            # A failed sampling stream may contain a prefix of plausible values, but the
            # terminal contract cannot call that attribution complete. Keep the affected
            # P0 metrics absent instead of publishing a partial number as measured-zero.
            missing.extend(["cpuMillis", "memoryPeakBytes"])
            if self._sample_error:
                logger.warning("Sandbox container sampling was incomplete for %s", container_id)

        logical_bytes, open_count, loader_complete, loader_missing = parse_loader_metrics_jsonl(
            loader_metrics_jsonl
        )
        if not loader_complete:
            # Partial JSONL aggregation is diagnostic-only. The stable terminal usage
            # contract exposes only P0 field names, so both affected aggregates are null.
            logical_bytes = None
            open_count = None
            missing.extend(["logicalBytesScanned", "datasetOpenCount"])
            logger.warning(
                "Sandbox loader metrics were incomplete for %s: %s",
                container_id,
                ",".join(loader_missing) or "unknown",
            )
        for field, value in (
            ("queueWaitMillis", queue_wait_millis),
            ("prepareMillis", prepare_millis),
            ("executionWallMillis", execution_wall_millis),
            ("cleanupMillis", cleanup_millis),
        ):
            if value is None:
                missing.append(field)
        if not exit_reason or not exit_reason.strip():
            missing.append("exitReason")

        return SandboxResourceUsage(
            resource_class=self.resource_class,
            cpu_millis=cpu_millis,
            memory_peak_bytes=memory_peak,
            memory_byte_millis=memory_byte_millis,
            logical_bytes_scanned=logical_bytes,
            artifact_bytes_written=max(0, artifact_bytes_written),
            temporary_bytes_written=max(0, temporary_bytes_written),
            queue_wait_millis=queue_wait_millis,
            prepare_millis=prepare_millis,
            execution_wall_millis=execution_wall_millis,
            cleanup_millis=cleanup_millis,
            dataset_open_count=open_count,
            exit_reason=exit_reason,
            oom_killed=oom_killed,
            timed_out=timed_out,
            attribution_complete=not missing,
            sampling_interval_millis=self.sampling_interval_millis,
            missing_fields=list(dict.fromkeys(missing)),
        )

    def _record(self, sampler: Callable[[], tuple[int, int]]) -> None:
        try:
            cpu_nanos, memory_bytes = sampler()
            self._samples.append(ContainerSample(time.monotonic(), int(cpu_nanos), int(memory_bytes)))
        except Exception:
            self._sample_error = True


class _DockerSampler:
    def __init__(self, container_id: str) -> None:
        import docker

        self.client = docker.from_env()
        self.container = self.client.containers.get(container_id)

    def __call__(self) -> tuple[int, int]:
        stats = self.container.stats(stream=False)
        cpu_nanos = int(stats.get("cpu_stats", {}).get("cpu_usage", {}).get("total_usage", 0))
        memory_bytes = int(stats.get("memory_stats", {}).get("usage", 0))
        return cpu_nanos, memory_bytes

    def close(self) -> None:
        self.client.close()


def _docker_sampler_factory(container_id: str) -> Callable[[], tuple[int, int]]:
    return _DockerSampler(container_id)
