from __future__ import annotations

import sys
import tempfile
import types
import unittest
from dataclasses import replace
from pathlib import Path

llm_sandbox = types.ModuleType("llm_sandbox")
llm_sandbox.SandboxSession = object
llm_sandbox_exceptions = types.ModuleType("llm_sandbox.exceptions")
llm_sandbox_exceptions.SandboxTimeoutError = TimeoutError
sys.modules.setdefault("llm_sandbox", llm_sandbox)
sys.modules.setdefault("llm_sandbox.exceptions", llm_sandbox_exceptions)

from app.config import SandboxConfig  # noqa: E402
from app.sandbox_runner import (  # noqa: E402
    SANDBOX_LOADER_FILES,
    _loader_smoke_check_command,
    run_in_open_session,
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
        container_max_concurrency=1,
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

    def test_run_in_open_session_exposes_observability_phase_timings(self) -> None:
        class FakeOutput:
            exit_code = 0
            stdout = ""
            stderr = ""

        class FakeRunResult:
            exit_code = 0
            stdout = "done"
            stderr = ""

        class FakeSession:
            container_id = "container-test"

            def execute_command(self, command: str) -> FakeOutput:
                return FakeOutput()

            def copy_to_runtime(self, source: str, dest_path: str) -> None:
                return None

            def run(self, code: str, libraries: list[str], timeout: float) -> FakeRunResult:
                return FakeRunResult()

        with tempfile.TemporaryDirectory() as temp_dir:
            data_dir = Path(temp_dir)
            dataset_dir = data_dir / "ds1"
            dataset_dir.mkdir()
            (dataset_dir / "ds1.csv").write_text("x\n1\n", encoding="utf-8")
            (dataset_dir / "ds1.meta.json").write_text("{}", encoding="utf-8")

            config = replace(_test_config(), data_dir=data_dir)

            result = run_in_open_session(
                config,
                FakeSession(),
                "task-1",
                "ds1",
                None,
                "print('done')",
                None,
                None,
                5,
            )

        timings = result["timings"]
        self.assertIn("env_load_ms", timings)
        self.assertIn("code_exec_ms", timings)
        self.assertIn("artifact_collect_ms", timings)
        self.assertEqual(timings["env_load_ms"], timings["workspace_prepare_ms"])
        self.assertEqual(timings["code_exec_ms"], timings["script_run_ms"])
        self.assertGreaterEqual(timings["artifact_collect_ms"], 0)
        usage = result["resource_usage"]
        self.assertEqual(usage["resource_class"], "STANDARD")
        self.assertEqual(usage["exit_reason"], "SUCCEEDED")
        self.assertEqual(usage["queue_wait_millis"], 0)
        self.assertIn("attribution_complete", usage)


if __name__ == "__main__":
    unittest.main()
