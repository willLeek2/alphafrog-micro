from __future__ import annotations

import sys
import tempfile
import types
import unittest
from dataclasses import replace
from pathlib import Path
from unittest.mock import patch

llm_sandbox = types.ModuleType("llm_sandbox")
llm_sandbox.SandboxSession = object
llm_sandbox_exceptions = types.ModuleType("llm_sandbox.exceptions")
llm_sandbox_exceptions.SandboxTimeoutError = TimeoutError
sys.modules.setdefault("llm_sandbox", llm_sandbox)
sys.modules.setdefault("llm_sandbox.exceptions", llm_sandbox_exceptions)

from app.config import SandboxConfig  # noqa: E402
from app.models import ExecutionEnvironment  # noqa: E402
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


class SandboxRunnerPostInstallReCollectionTest(unittest.TestCase):
    """Spec §8 L1019 + Kimi rework 2026-08-08: when a task actually installs
    non-preinstalled packages, the runtime environment MUST be re-collected
    after the install so the HTTP execution_environment field reflects the
    post-install state. Pool container reuse across tasks otherwise leaves
    residual installs polluting the next task's environment identity.
    """

    def _test_config(self, **overrides) -> SandboxConfig:
        base = SandboxConfig(
            data_dir=__import__("pathlib").Path("data/agent_datasets"),
            max_concurrency=1,
            execution_timeout_seconds=5.0,
            memory_limit="512m",
            memswap_limit="512m",
            docker_backend="docker",
            workdir="/sandbox",
            log_level="INFO",
            sandbox_image="alphafrog-sandbox-runtime:latest",
            skip_environment_setup=False,
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
        return replace(base, **overrides)

    def _fake_session(self) -> object:
        class _FakeOutput:
            exit_code = 0
            stdout = ""
            stderr = ""

        class _FakeRunResult:
            exit_code = 0
            stdout = "installed requests"
            stderr = ""

        class _FakeSession:
            container_id = "container-post-install"

            def execute_command(self, command: str) -> _FakeOutput:
                return _FakeOutput()

            def copy_to_runtime(self, source: str, dest_path: str) -> None:
                return None

            def run(self, code: str, libraries, timeout: float) -> _FakeRunResult:
                return _FakeRunResult()

        return _FakeSession()

    def test_post_install_environment_overrides_baked_when_install_happens(self) -> None:
        """install_libraries non-empty → result env uses post-install re-collection."""
        baked_env = ExecutionEnvironment(
            environment_id="sha256:baked_only_numpy",
            image_digest="sha256:img-baked",
            library_set_digest="sha256:libs-baked",
            package_apis=[],
            inventory_complete=True,
        )
        post_install_env = ExecutionEnvironment(
            environment_id="sha256:post_install_numpy_requests",
            image_digest="sha256:img-post",
            library_set_digest="sha256:libs-post",
            package_apis=[],
            inventory_complete=True,
        )

        config = self._test_config()
        session = self._fake_session()

        with tempfile.TemporaryDirectory() as temp_dir:
            data_dir = Path(temp_dir)
            dataset_dir = data_dir / "ds1"
            dataset_dir.mkdir()
            (dataset_dir / "ds1.csv").write_text("x\n1\n", encoding="utf-8")
            (dataset_dir / "ds1.meta.json").write_text("{}", encoding="utf-8")

            config = replace(config, data_dir=data_dir)

            with patch(
                "app.sandbox_runner.collect_runtime_environment",
                return_value=post_install_env,
            ) as collect_mock:
                result = run_in_open_session(
                    config,
                    session,
                    "task-post-install",
                    "ds1",
                    None,
                    "import requests; print('ok')",
                    None,
                    # libraries: requests is not preinstalled, so install_libraries
                    # becomes ["requests"] (non-empty) which triggers re-collection.
                    ["requests"],
                    5,
                    execution_environment=baked_env,
                )

        self.assertEqual(1, collect_mock.call_count)
        result_env = result["execution_environment"]
        self.assertIsNotNone(result_env)
        self.assertEqual(
            "sha256:post_install_numpy_requests",
            result_env["environment_id"],
        )
        self.assertNotEqual(result_env["environment_id"], baked_env.environment_id)
        # post-install re-collect timing should be reported
        self.assertIn("post_install_recollect_ms", result["timings"])

    def test_baked_environment_kept_when_no_install_happens(self) -> None:
        """install_libraries empty (all preinstalled or skip_environment_setup=True)
        → result env uses caller-supplied baked env, no re-collection."""
        baked_env = ExecutionEnvironment(
            environment_id="sha256:baked_only",
            image_digest="sha256:img-baked",
            library_set_digest="sha256:libs-baked",
            package_apis=[],
            inventory_complete=True,
        )

        config = self._test_config(skip_environment_setup=True)
        session = self._fake_session()

        with tempfile.TemporaryDirectory() as temp_dir:
            data_dir = Path(temp_dir)
            dataset_dir = data_dir / "ds1"
            dataset_dir.mkdir()
            (dataset_dir / "ds1.csv").write_text("x\n1\n", encoding="utf-8")
            (dataset_dir / "ds1.meta.json").write_text("{}", encoding="utf-8")

            config = replace(config, data_dir=data_dir)

            with patch(
                "app.sandbox_runner.collect_runtime_environment",
            ) as collect_mock:
                result = run_in_open_session(
                    config,
                    session,
                    "task-no-install",
                    "ds1",
                    None,
                    "print('ok')",
                    None,
                    # Even though the caller asked for these, skip_environment_setup
                    # forces install_libraries=[] so no install path is triggered.
                    ["requests"],
                    5,
                    execution_environment=baked_env,
                )

        # Re-collection MUST NOT have been triggered.
        self.assertEqual(0, collect_mock.call_count)
        result_env = result["execution_environment"]
        self.assertEqual(
            "sha256:baked_only", result_env["environment_id"],
        )
        self.assertNotIn("post_install_recollect_ms", result["timings"])


if __name__ == "__main__":
    unittest.main()
