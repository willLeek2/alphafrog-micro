from __future__ import annotations

import sys
import types
import unittest

llm_sandbox = types.ModuleType("llm_sandbox")
llm_sandbox.SandboxSession = object
llm_sandbox_exceptions = types.ModuleType("llm_sandbox.exceptions")
llm_sandbox_exceptions.SandboxTimeoutError = TimeoutError
sys.modules.setdefault("llm_sandbox", llm_sandbox)
sys.modules.setdefault("llm_sandbox.exceptions", llm_sandbox_exceptions)

from app.config import SandboxConfig
from app.sandbox_runner import (
    SANDBOX_LOADER_FILES,
    _loader_smoke_check_command,
)


def _test_config() -> SandboxConfig:
    return SandboxConfig(
        data_dir=__import__("pathlib").Path("data/agent_datasets"),
        max_concurrency=1,
        execution_timeout_seconds=5.0,
        memory_limit="512m",
        memswap_limit="512m",
        docker_backend="docker",
        workdir="/sandbox",
        log_level="INFO",
        sandbox_image="alphafrog-sandbox-runtime:latest",
        skip_environment_setup=True,
        preinstalled_libraries=frozenset(),
        pool_enabled=False,
        pool_min_size=0,
        pool_max_size=1,
        pool_acquire_timeout_seconds=30.0,
        pool_idle_timeout_seconds=None,
        pool_max_container_uses=None,
        workspace_root="/sandbox/runs",
        compat_input_path_enabled=True,
    )


class SandboxRunnerLoaderTest(unittest.TestCase):
    def test_loader_files_cover_runtime_modules(self) -> None:
        self.assertIn("af_dataset_loader.py", SANDBOX_LOADER_FILES)
        self.assertIn("dataset_manifest.py", SANDBOX_LOADER_FILES)

    def test_loader_smoke_check_command_imports_from_workdir(self) -> None:
        command = _loader_smoke_check_command(_test_config())
        self.assertIn("sys.path.insert(0,", command)
        self.assertIn("/sandbox", command)
        self.assertIn("from af_dataset_loader import load_manifest", command)
        self.assertIn("sandbox_loader_ok", command)

    def test_user_code_with_future_import_is_not_rewritten(self) -> None:
        user_code = 'from __future__ import annotations\nprint("ok")\n'
        compile(user_code, "<user>", "exec")


if __name__ == "__main__":
    unittest.main()
