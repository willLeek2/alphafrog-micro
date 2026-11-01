from __future__ import annotations

import threading
import time
import sys
import types
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from unittest.mock import patch

llm_sandbox = types.ModuleType("llm_sandbox")
llm_sandbox.SandboxSession = object
llm_sandbox_exceptions = types.ModuleType("llm_sandbox.exceptions")
llm_sandbox_exceptions.SandboxTimeoutError = TimeoutError
sys.modules.setdefault("llm_sandbox", llm_sandbox)
sys.modules.setdefault("llm_sandbox.exceptions", llm_sandbox_exceptions)

from app.config import SandboxConfig
from app.pool_scheduler import ContainerPoolScheduler, SandboxQueueTimeoutError
from tests.nonroot_fakes import prime_fake_session


class FakeSession:
    def __init__(self) -> None:
        # Non-root staging surface (260818): the pool worker's startup
        # writes runtime-environment.json through container.put_archive,
        # so the fake needs the container proxy + cached identity +
        # an execute_command for the staging mkdir.
        prime_fake_session(self)

    def execute_command(self, command: str):
        return types.SimpleNamespace(exit_code=0, stdout="", stderr="")

    def copy_to_runtime(self, source: str, dest_path: str) -> None:
        raise AssertionError(
            "production must stage via container.put_archive "
            "(non-root contract, 260818)"
        )

    def close(self) -> None:
        pass


class FakeStateWorker:
    def __init__(self, state: str, available_slots: int = 0, container_max_concurrency: int = 1) -> None:
        self.state = state
        self._available_slots = available_slots
        self.config = make_config(container_max_concurrency=container_max_concurrency)

    def snapshot_state(self) -> str:
        return self.state

    def available_slots(self) -> int:
        return self._available_slots


class FakeDockerContainer:
    def __init__(self, container_id: str) -> None:
        self.short_id = container_id
        self.removed = False

    def remove(self, *, force: bool) -> None:
        self.removed = force


def make_config(*, min_size: int = 1, max_size: int = 2, container_max_concurrency: int = 1) -> SandboxConfig:
    return SandboxConfig(
        data_dir=Path("/tmp"),
        max_concurrency=20,
        execution_timeout_seconds=5,
        memory_limit="512m",
        memswap_limit="512m",
        docker_backend="docker",
        workdir="/sandbox",
        log_level="INFO",
        sandbox_image="test-runtime",
        skip_environment_setup=True,
        preinstalled_libraries=frozenset({"numpy", "pandas", "matplotlib", "scipy"}),
        container_max_concurrency=container_max_concurrency,
        pool_enabled=True,
        pool_min_size=min_size,
        pool_max_size=max_size,
        pool_acquire_timeout_seconds=30,
        pool_idle_timeout_seconds=None,
        pool_max_container_uses=None,
        workspace_root="/sandbox/runs",
        compat_input_path_enabled=(container_max_concurrency == 1),
    )


class ContainerPoolSchedulerTest(unittest.TestCase):
    pass
if __name__ == "__main__":
    unittest.main()
