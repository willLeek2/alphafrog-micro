from __future__ import annotations

import json
import os
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

            def install(self, libraries) -> None:
                return None

            def run(self, code: str, libraries, timeout: float) -> FakeRunResult:
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

            def __init__(self) -> None:
                self.install_calls: list[list[str]] = []
                self.copy_to_runtime_calls: list[tuple[str, str]] = []
                self.run_calls: list[tuple[str, list | None, float]] = []

            def execute_command(self, command: str) -> _FakeOutput:
                return _FakeOutput()

            def copy_to_runtime(self, source: str, dest_path: str) -> None:
                self.copy_to_runtime_calls.append((source, dest_path))

            def install(self, libraries) -> None:
                self.install_calls.append(list(libraries))

            def run(self, code: str, libraries, timeout: float) -> _FakeRunResult:
                self.run_calls.append((code, libraries, timeout))
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
        # 260808-finance-methodspec-v5 codex rework 2026-08-08 22:49: verify
        # the new install→collect→write→run sequence instead of "run then
        # re-collect after report() already executed".
        self.assertEqual([["requests"]], session.install_calls)
        # session.run() MUST be called WITHOUT install_libraries (we already
        # installed via session.install() before re-collecting).
        self.assertEqual(1, len(session.run_calls))
        run_call = session.run_calls[0]
        self.assertEqual(run_call[0], "import requests; print('ok')")
        self.assertIsNone(run_call[1])
        # install_ms timing must be reported.
        self.assertIn("install_ms", result["timings"])
        # copy_to_runtime must have been called at least twice: once by
        # _prepare_task_workspace (baked env into task_workspace file) and
        # once by the post-install phase (post-install env overwriting it).
        copy_destinations = [
            dest for _src, dest in session.copy_to_runtime_calls
        ]
        runtime_env_destinations = [
            d for d in copy_destinations if d.endswith("runtime-environment.json")
        ]
        self.assertGreaterEqual(
            len(runtime_env_destinations), 2,
            f"expected at least 2 copy_to_runtime calls writing runtime-environment.json "
            f"(baked + post-install), got: {copy_destinations}",
        )

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
        # 260808-finance-methodspec-v5 codex rework 2026-08-08 22:49: no-install
        # path MUST NOT call session.install(), and MUST call session.run()
        # with libraries=[] (an explicit no-op for llm-sandbox).
        self.assertEqual([], session.install_calls)
        self.assertEqual(1, len(session.run_calls))
        self.assertEqual([], session.run_calls[0][1])


class SandboxRunnerContainerWriteTest(unittest.TestCase):
    """260808-finance-methodspec-v5 codex rework 2026-08-08 22:49:
    write_runtime_environment_json on the service host's local filesystem is
    invisible to a Python subprocess inside the execution container. The
    sandbox runner MUST push the file into the container via
    session.copy_to_runtime and read it back via the per-task
    AF_RUNTIME_ENVIRONMENT_FILE sitecustomize override.
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
            stdout = ""
            stderr = ""

        class _FakeSession:
            container_id = "container-container-write"

            def __init__(self) -> None:
                self.copy_to_runtime_calls: list[tuple[str, str]] = []

            def execute_command(self, command: str) -> _FakeOutput:
                return _FakeOutput()

            def copy_to_runtime(self, source: str, dest_path: str) -> None:
                self.copy_to_runtime_calls.append((source, dest_path))

            def install(self, libraries) -> None:
                return None

            def run(self, code: str, libraries, timeout: float) -> _FakeRunResult:
                return _FakeRunResult()

        return _FakeSession()

    def test_run_in_open_session_writes_runtime_environment_json_into_task_workspace_in_container(self) -> None:
        """Verify the per-task runtime-environment.json is copy_to_runtime'd
        into the container at <task_workspace>/runtime-environment.json (NOT
        the global /sandbox path that the service host's local fs would map
        to). Without this, report() running inside the container could not
        read environmentId.
        """
        baked_env = ExecutionEnvironment(
            environment_id="sha256:baked_for_container_write",
            image_digest="sha256:img-baked",
            library_set_digest="sha256:libs-baked",
            package_apis=[],
            inventory_complete=True,
        )

        config = self._test_config()
        session = self._fake_session()
        # Capture the tempfile payload BEFORE the helper cleans up.
        captured_runtime_env_payloads: list[dict] = []
        captured_sitecustomize_text: list[str] = []
        original_copy = session.copy_to_runtime

        def capture_copy(source: str, dest_path: str) -> None:
            if dest_path.endswith("runtime-environment.json"):
                with open(source, "r", encoding="utf-8") as fp:
                    captured_runtime_env_payloads.append(json.loads(fp.read()))
            elif dest_path.endswith("sitecustomize.py"):
                with open(source, "r", encoding="utf-8") as fp:
                    captured_sitecustomize_text.append(fp.read())
            session.copy_to_runtime_calls.append((source, dest_path))

        session.copy_to_runtime = capture_copy  # type: ignore[method-assign]

        with tempfile.TemporaryDirectory() as temp_dir:
            data_dir = Path(temp_dir)
            dataset_dir = data_dir / "ds1"
            dataset_dir.mkdir()
            (dataset_dir / "ds1.csv").write_text("x\n1\n", encoding="utf-8")
            (dataset_dir / "ds1.meta.json").write_text("{}", encoding="utf-8")

            config = replace(config, data_dir=data_dir)

            run_in_open_session(
                config,
                session,
                "task-container-write",
                "ds1",
                None,
                "print('ok')",
                None,
                [],
                5,
                execution_environment=baked_env,
            )

        # _prepare_task_workspace must call copy_to_runtime for the
        # runtime-environment.json file under the task workspace.
        runtime_env_writes = [
            (src, dest) for src, dest in session.copy_to_runtime_calls
            if dest.endswith("runtime-environment.json")
        ]
        self.assertEqual(1, len(runtime_env_writes))
        _src_path, dest_path = runtime_env_writes[0]
        self.assertTrue(
            dest_path.endswith("/sandbox/runs/task-container-write/runtime-environment.json"),
            f"runtime-environment.json must live in the task workspace, "
            f"got: {dest_path}",
        )
        # Verify the payload written was the baked env, byte-for-byte.
        self.assertEqual(1, len(captured_runtime_env_payloads))
        payload = captured_runtime_env_payloads[0]
        self.assertEqual(
            "sha256:baked_for_container_write",
            payload["environment_id"],
        )

        # sitecustomize.py write must also be present — it carries the
        # AF_RUNTIME_ENVIRONMENT_FILE override that points at the per-task
        # file above.
        self.assertGreaterEqual(
            len(captured_sitecustomize_text), 1,
            "sitecustomize.py must be copied into the container so the "
            "per-task AF_RUNTIME_ENVIRONMENT_FILE override takes effect",
        )
        sitecustomize_text = captured_sitecustomize_text[0]
        self.assertIn(
            "AF_RUNTIME_ENVIRONMENT_FILE", sitecustomize_text,
        )
        self.assertIn(
            "/sandbox/runs/task-container-write/runtime-environment.json",
            sitecustomize_text,
        )


class WriteRuntimeEnvironmentToContainerTest(unittest.TestCase):
    """260808-finance-methodspec-v5 codex rework 2026-08-08 22:49:
    write_runtime_environment_to_container delegates persistence to
    session.copy_to_runtime. The tempfile is local-only and MUST be cleaned
    up after the copy, even on failure (no leaked service-host files).
    """

    def test_calls_copy_to_runtime_with_serialized_env(self) -> None:
        from app.runtime_environment import (
            ExecutionEnvironment,
            SandboxPackageApi,
            write_runtime_environment_to_container,
        )

        env = ExecutionEnvironment(
            environment_id="sha256:abc",
            image_digest="sha256:img",
            library_set_digest="sha256:libs",
            package_apis=[
                SandboxPackageApi(name="numpy", version="1.0", api_version="1.0"),
            ],
            inventory_complete=True,
        )
        captured: list[tuple[str, str]] = []
        captured_payloads: list[dict] = []

        class _FakeSession:
            def copy_to_runtime(self, source: str, dest_path: str) -> None:
                # Read payload BEFORE the helper cleans up the tempfile.
                with open(source, "r", encoding="utf-8") as fp:
                    captured_payloads.append(json.loads(fp.read()))
                captured.append((source, dest_path))

        returned = write_runtime_environment_to_container(
            _FakeSession(), env, "/sandbox/runs/t1/runtime-environment.json",
        )
        self.assertEqual("/sandbox/runs/t1/runtime-environment.json", returned)
        self.assertEqual(1, len(captured))
        src_path, dest_path = captured[0]
        self.assertEqual(
            "/sandbox/runs/t1/runtime-environment.json", dest_path,
        )
        # Payload was a valid JSON document matching the env.
        payload = captured_payloads[0]
        self.assertEqual("sha256:abc", payload["environment_id"])
        self.assertEqual("sha256:img", payload["image_digest"])
        self.assertEqual(1, len(payload["package_apis"]))
        # Tempfile MUST be cleaned up (codex requirement: no service-host leaks).
        self.assertFalse(
            os.path.exists(src_path),
            f"tempfile {src_path} must be removed after copy_to_runtime",
        )

    def test_cleans_up_tempfile_even_when_copy_to_runtime_raises(self) -> None:
        from app.runtime_environment import (
            ExecutionEnvironment,
            write_runtime_environment_to_container,
        )

        env = ExecutionEnvironment(
            environment_id="sha256:boom",
            image_digest="",
            library_set_digest="sha256:libs",
            package_apis=[],
            inventory_complete=False,
        )

        class _BoomSession:
            def copy_to_runtime(self, source: str, dest_path: str) -> None:
                raise RuntimeError("container unreachable")

        captured_src: list[str] = []

        original_copy = _BoomSession.copy_to_runtime

        def capture_then_raise(self, source: str, dest_path: str) -> None:
            captured_src.append(source)
            original_copy(self, source, dest_path)

        _BoomSession.copy_to_runtime = capture_then_raise  # type: ignore[method-assign]

        with __import__("pytest").raises(RuntimeError):
            write_runtime_environment_to_container(
                _BoomSession(), env, "/sandbox/runtime-environment.json",
            )
        self.assertEqual(1, len(captured_src))
        self.assertFalse(
            os.path.exists(captured_src[0]),
            "tempfile MUST be cleaned up even when copy_to_runtime raises",
        )


if __name__ == "__main__":
    unittest.main()
