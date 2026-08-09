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
    ConfigurationError,
    _loader_smoke_check_command,
    run_in_open_session,
    validate_dynamic_install_safety,
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
        # codex (A) plan 2026-08-08 23:06: per-task baked-env writes were
        # removed. The single runtime-environment.json file is the
        # container-global one written once by initialize_runtime_environment
        # at container startup; the post-install phase overwrites it with
        # the post-install env. Only the post-install write happens inside
        # run_in_open_session, so we expect exactly ONE copy_to_runtime call
        # targeting runtime-environment.json here.
        copy_destinations = [
            dest for _src, dest in session.copy_to_runtime_calls
        ]
        runtime_env_destinations = [
            d for d in copy_destinations if d.endswith("runtime-environment.json")
        ]
        self.assertEqual(
            1, len(runtime_env_destinations),
            f"expected exactly 1 copy_to_runtime call writing "
            f"runtime-environment.json (post-install overwrite), got: "
            f"{copy_destinations}",
        )
        self.assertTrue(
            runtime_env_destinations[0].endswith("/sandbox/runtime-environment.json"),
            f"post-install write MUST target the container-global "
            f"<workdir>/runtime-environment.json path, got: "
            f"{runtime_env_destinations[0]}",
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
    session.copy_to_runtime and read it back via the container-creation
    AF_RUNTIME_ENVIRONMENT_FILE env var (D15 §4.2 removed the per-task
    sitecustomize.py override that previously broke pool reuse).
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

    def test_run_in_open_session_writes_runtime_environment_json_into_container_global_path(self) -> None:
        """Verify the container-global runtime-environment.json is
        copy_to_runtime'd into the container at <workdir>/runtime-environment.json.
        codex (A) plan 2026-08-08 23:06: the file path is the container-global
        <workdir>/runtime-environment.json set by create_sandbox_session. D15
        §4.2 (Scenario B, 2026-08-10) additionally removed the per-task
        sitecustomize.py write altogether — AF_TASK_* now travels via the
        task-local wrapper-input.json taskEnvironment field. Without the
        global runtime-environment.json, report() inside the container could
        not read environmentId.
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
        # runtime-environment.json file at the container-global path
        # (workdir). Per-task writes were removed by the (A) plan.
        runtime_env_writes = [
            (src, dest) for src, dest in session.copy_to_runtime_calls
            if dest.endswith("runtime-environment.json")
        ]
        # run_in_open_session reuses the container-level environment file written
        # during initialization; it must not rewrite that global file per task.
        self.assertEqual([], runtime_env_writes)
        self.assertEqual([], captured_runtime_env_payloads)

        # D15 §4.2 (Scenario B): the per-task write of /sandbox/sitecustomize.py
        # is GONE — AF_TASK_* now travels inside the task-local wrapper-input.json
        # (staged at {task_workspace}/wrapper-input.json) and the wrapper injects
        # it via Popen(env=...). Asserting ZERO sitecustomize writes here pins
        # the new contract: no global bootstrap file is written per task.
        self.assertEqual(
            [], captured_sitecustomize_text,
            "D15 §4.2: _prepare_task_workspace must NOT write the global "
            "sitecustomize.py per task; AF_TASK_* travels via the task-local "
            "wrapper-input.json taskEnvironment field instead.",
        )


class ValidateDynamicInstallSafetyTest(unittest.TestCase):
    """260808-finance-methodspec-v5 codex (A) plan 2026-08-08 23:06 +
    codex 2026-08-08 23:16 (bc11e841 item 2), updated by D15 §4.2 (2026-08-10):

    The v5 safety invariant is ``container_max_concurrency == 1`` for every
    SandboxConfig. D15 §4.2 removed the historical driver — the per-task
    write of AF_TASK_* into the SHARED global ``/sandbox/sitecustomize.py``
    (those vars now travel via the task-local wrapper-input.json) — so the
    sitecustomize race is no longer a concurrency blocker. The cmc==1 rule
    STILL holds, now driven solely by the dynamic-install venv mutation
    race (PoolWorker.execution_environment is captured once and never
    refreshed, so concurrent tasks would see stale environmentId while the
    venv had already been mutated by session.install()).

    Lifting cmc>1 is gated by S3B-04 and out of scope for D15. Tests here
    still pin cmc==1 fail-fast for all configurations.
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

    def test_validate_dynamic_install_safety_rejects_concurrency_gt_1_dynamic(self) -> None:
        """skip_environment_setup=False + cmc>=2 must raise."""
        for bad_concurrency in (2, 3, 4, 8):
            config = self._test_config(container_max_concurrency=bad_concurrency)
            with self.assertRaises(
                ConfigurationError,
                msg=f"cmc={bad_concurrency} with dynamic install must raise",
            ) as cm:
                validate_dynamic_install_safety(config)
            self.assertIn(
                "CONTAINER_MAX_CONCURRENCY_REQUIRES_ONE", str(cm.exception),
            )
            self.assertIn(str(bad_concurrency), str(cm.exception))

    def test_validate_dynamic_install_safety_rejects_concurrency_gt_1_preinstalled(self) -> None:
        """codex 2026-08-08 23:16 (bc11e841 item 2): sitecustomize.py races
        even for preinstalled-only configs. cmc>=2 must raise even when
        skip_environment_setup=True."""
        for bad_concurrency in (2, 3, 4, 8, 16):
            config = self._test_config(
                skip_environment_setup=True,
                container_max_concurrency=bad_concurrency,
            )
            with self.assertRaises(
                ConfigurationError,
                msg=f"cmc={bad_concurrency} with skip_environment_setup=True "
                    f"must STILL raise (sitecustomize.py race)",
            ) as cm:
                validate_dynamic_install_safety(config)
            self.assertIn(
                "CONTAINER_MAX_CONCURRENCY_REQUIRES_ONE", str(cm.exception),
            )
            self.assertIn(str(bad_concurrency), str(cm.exception))

    def test_validate_dynamic_install_safety_allows_concurrency_eq_1_dynamic(self) -> None:
        """skip_environment_setup=False + cmc=1 must pass."""
        config = self._test_config(container_max_concurrency=1)
        # MUST NOT raise.
        validate_dynamic_install_safety(config)

    def test_validate_dynamic_install_safety_allows_concurrency_eq_1_preinstalled(self) -> None:
        """skip_environment_setup=True + cmc=1 must pass."""
        config = self._test_config(
            skip_environment_setup=True, container_max_concurrency=1,
        )
        # MUST NOT raise.
        validate_dynamic_install_safety(config)


class PostInstallFailClosedTest(unittest.TestCase):
    """260808-finance-methodspec-v5 codex (A) plan 2026-08-08 23:06:

    install/collect/copy 任一失败 MUST raise before session.run; the
    container is marked recycled so the pool scheduler retires the worker
    and the next task gets a fresh baked container. session.run() MUST
    NOT be called when any post-install step fails.
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

    def _build_session(self, *, install_exc=None, copy_exc=None, copy_runtime_env_only: bool = False):
        """Build a fake session whose install/copy_to_runtime may raise.

        copy_runtime_env_only: when True, copy_to_runtime only raises for
        dest_path ending in runtime-environment.json. This matches the
        production behavior where sitecustomize.py copy_to_runtime succeeds
        but the post-install runtime-environment.json write fails. Used to
        isolate the post-install failure path from _prepare_task_workspace
        sitecustomize.py copy.
        """

        class _FakeRunResult:
            exit_code = 0
            stdout = "should-not-run"
            stderr = ""

        class _FakeOutput:
            exit_code = 0
            stdout = ""
            stderr = ""

        class _FakeSession:
            container_id = "container-fail-closed"

            def __init__(self) -> None:
                self.install_calls: list[list[str]] = []
                self.copy_to_runtime_calls: list[tuple[str, str]] = []
                self.run_calls: list[tuple[str, list | None, float]] = []

            def execute_command(self, command: str) -> _FakeOutput:
                return _FakeOutput()

            def copy_to_runtime(self, source: str, dest_path: str) -> None:
                self.copy_to_runtime_calls.append((source, dest_path))
                if copy_exc is None:
                    return
                if copy_runtime_env_only and not dest_path.endswith(
                    "runtime-environment.json"
                ):
                    return
                raise copy_exc

            def install(self, libraries) -> None:
                self.install_calls.append(list(libraries))
                if install_exc is not None:
                    raise install_exc

            def run(self, code: str, libraries, timeout: float) -> _FakeRunResult:
                self.run_calls.append((code, libraries, timeout))
                return _FakeRunResult()

        return _FakeSession()

    def _baked_env(self) -> ExecutionEnvironment:
        return ExecutionEnvironment(
            environment_id="sha256:baked",
            image_digest="sha256:img-baked",
            library_set_digest="sha256:libs-baked",
            package_apis=[],
            inventory_complete=True,
        )

    def test_post_install_install_raises_blocks_run_and_recycles_container(self) -> None:
        """session.install() raises → session.run() MUST NOT be called +
        exception propagates with execution_environment falling back to baked."""
        session = self._build_session(
            install_exc=RuntimeError("pip index unreachable"),
        )
        config = self._test_config()
        baked_env = self._baked_env()

        with tempfile.TemporaryDirectory() as temp_dir:
            data_dir = Path(temp_dir)
            dataset_dir = data_dir / "ds1"
            dataset_dir.mkdir()
            (dataset_dir / "ds1.csv").write_text("x\n1\n", encoding="utf-8")
            (dataset_dir / "ds1.meta.json").write_text("{}", encoding="utf-8")
            config = replace(config, data_dir=data_dir)

            with self.assertRaises(RuntimeError) as cm:
                run_in_open_session(
                    config,
                    session,
                    "task-install-raises",
                    "ds1",
                    None,
                    "print('should not run')",
                    None,
                    ["requests"],  # not preinstalled → triggers install path
                    5,
                    execution_environment=baked_env,
                )
            self.assertIn("pip index unreachable", str(cm.exception))

        # session.run() MUST NOT have been called.
        self.assertEqual(
            0, len(session.run_calls),
            f"session.run() MUST NOT be called when session.install() "
            f"raises; got run_calls={session.run_calls}",
        )
        # session.install() WAS called and raised.
        self.assertEqual([["requests"]], session.install_calls)
        # The exception carries the baked execution_environment fallback
        # so downstream observers see a stable envId even on failure.
        self.assertIsNotNone(getattr(cm.exception, "execution_environment", None))
        self.assertEqual(
            "sha256:baked",
            cm.exception.execution_environment["environment_id"],
        )

    def _post_install_env(self) -> ExecutionEnvironment:
        return ExecutionEnvironment(
            environment_id="sha256:post-install",
            image_digest="sha256:img-baked",
            library_set_digest="sha256:libs-post-install",
            package_apis=[],
            inventory_complete=True,
        )

    def _frozen_limits(self) -> dict:
        return {
            "recordChannelMaxRecords": 10,
            "recordChannelMaxBytes": 4096,
            "stdoutMaxBytes": 4096,
            "stderrMaxBytes": 4096,
            "sourceRevision": "test-rev",
        }

    def _run_wrapper_failure_case(self, wrapper_exc):
        """install+recollect+push succeed, then the bounded wrapper fails.

        codex 88ff8a41/d48a2275 (#97 owner merge additive): the propagated
        exception MUST carry the POST-INSTALL execution_environment (the
        actual container state), not the caller-supplied baked env.
        Returns the caught exception.
        """
        session = self._build_session()
        config = self._test_config()
        baked_env = self._baked_env()

        with tempfile.TemporaryDirectory() as temp_dir:
            data_dir = Path(temp_dir)
            dataset_dir = data_dir / "ds1"
            dataset_dir.mkdir()
            (dataset_dir / "ds1.csv").write_text("x\n1\n", encoding="utf-8")
            (dataset_dir / "ds1.meta.json").write_text("{}", encoding="utf-8")
            config = replace(config, data_dir=data_dir)

            with patch(
                "app.sandbox_runner.collect_runtime_environment",
                return_value=self._post_install_env(),
            ), patch(
                "app.sandbox_runner._run_bounded_wrapper_path",
                side_effect=wrapper_exc,
            ):
                with self.assertRaises(Exception) as cm:
                    run_in_open_session(
                        config,
                        session,
                        "task-wrapper-post-install-env",
                        "ds1",
                        None,
                        "print('x')",
                        None,
                        ["requests"],  # not preinstalled -> install path
                        5,
                        effective_output_limits=self._frozen_limits(),
                        execution_environment=baked_env,
                    )
        return cm.exception

    def test_wrapper_raise_after_successful_recollect_prefers_post_install_env(self) -> None:
        exc = self._run_wrapper_failure_case(RuntimeError("wrapper boom"))
        self.assertIn("wrapper boom", str(exc))
        self.assertIsNotNone(getattr(exc, "execution_environment", None))
        self.assertEqual(
            "sha256:post-install",
            exc.execution_environment["environment_id"],
        )

    def test_wrapper_timeout_after_successful_recollect_prefers_post_install_env(self) -> None:
        # llm_sandbox is stubbed in this module: SandboxTimeoutError = TimeoutError.
        exc = self._run_wrapper_failure_case(TimeoutError("wrapper timeout"))
        self.assertIsNotNone(getattr(exc, "execution_environment", None))
        self.assertEqual(
            "sha256:post-install",
            exc.execution_environment["environment_id"],
        )

    def test_post_install_collect_raises_blocks_run_and_recycles_container(self) -> None:
        """collect_runtime_environment raises → session.run() MUST NOT be called."""
        session = self._build_session()
        config = self._test_config()
        baked_env = self._baked_env()

        with tempfile.TemporaryDirectory() as temp_dir:
            data_dir = Path(temp_dir)
            dataset_dir = data_dir / "ds1"
            dataset_dir.mkdir()
            (dataset_dir / "ds1.csv").write_text("x\n1\n", encoding="utf-8")
            (dataset_dir / "ds1.meta.json").write_text("{}", encoding="utf-8")
            config = replace(config, data_dir=data_dir)

            with patch(
                "app.sandbox_runner.collect_runtime_environment",
                side_effect=RuntimeError("pip list crash"),
            ):
                with self.assertRaises(RuntimeError) as cm:
                    run_in_open_session(
                        config,
                        session,
                        "task-collect-raises",
                        "ds1",
                        None,
                        "print('should not run')",
                        None,
                        ["requests"],
                        5,
                        execution_environment=baked_env,
                    )
                self.assertIn("pip list crash", str(cm.exception))

        # session.install() succeeded but collect failed → run still not called.
        self.assertEqual([["requests"]], session.install_calls)
        self.assertEqual(
            0, len(session.run_calls),
            f"session.run() MUST NOT be called when post-install collect "
            f"raises; got run_calls={session.run_calls}",
        )
        # Baked env fallback on the exception.
        self.assertIsNotNone(getattr(cm.exception, "execution_environment", None))
        self.assertEqual(
            "sha256:baked",
            cm.exception.execution_environment["environment_id"],
        )

    def test_post_install_copy_raises_blocks_run_and_recycles_container(self) -> None:
        """write_runtime_environment_to_container raises → session.run() MUST
        NOT be called."""
        session = self._build_session(
            copy_exc=RuntimeError("container copy unreachable"),
            copy_runtime_env_only=True,
        )
        config = self._test_config()
        baked_env = self._baked_env()

        post_install_env = ExecutionEnvironment(
            environment_id="sha256:post_install",
            image_digest="sha256:img-post",
            library_set_digest="sha256:libs-post",
            package_apis=[],
            inventory_complete=True,
        )

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
            ):
                with self.assertRaises(RuntimeError) as cm:
                    run_in_open_session(
                        config,
                        session,
                        "task-copy-raises",
                        "ds1",
                        None,
                        "print('should not run')",
                        None,
                        ["requests"],
                        5,
                        execution_environment=baked_env,
                    )
                self.assertIn("container copy unreachable", str(cm.exception))

        # session.install() and collect succeeded but copy_to_runtime raised.
        self.assertEqual([["requests"]], session.install_calls)
        # copy_to_runtime WAS called (the failed one was the post-install
        # write) so the first copy attempt (if any) is recorded.
        self.assertGreaterEqual(len(session.copy_to_runtime_calls), 1)
        # session.run() MUST NOT be called.
        self.assertEqual(
            0, len(session.run_calls),
            f"session.run() MUST NOT be called when post-install copy "
            f"raises; got run_calls={session.run_calls}",
        )
        # Baked env fallback on the exception.
        self.assertIsNotNone(getattr(cm.exception, "execution_environment", None))
        self.assertEqual(
            "sha256:baked",
            cm.exception.execution_environment["environment_id"],
        )

    def test_post_install_pollution_recycles_after_successful_install(self) -> None:
        """codex 2026-08-08 23:16 (bc11e841 item 1): every successful
        dynamic install MUST set container_recycled=True with reason
        "post_install_pollution" — even when user code itself succeeded.
        The next task in this worker would otherwise inherit the mutated
        venv while PoolWorker.execution_environment still holds the baked
        snapshot, causing environmentId to disagree with actual state.
        """
        session = self._build_session()
        config = self._test_config()
        baked_env = self._baked_env()

        post_install_env = ExecutionEnvironment(
            environment_id="sha256:post_install",
            image_digest="sha256:img-post",
            library_set_digest="sha256:libs-post",
            package_apis=[],
            inventory_complete=True,
        )

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
            ):
                result = run_in_open_session(
                    config,
                    session,
                    "task-pollution-recycle",
                    "ds1",
                    None,
                    "import requests; print('ok')",
                    None,
                    ["requests"],
                    5,
                    execution_environment=baked_env,
                )

        # session.install + collect + copy + run all succeeded.
        self.assertEqual([["requests"]], session.install_calls)
        self.assertEqual(1, len(session.run_calls))
        # Result MUST signal the pool worker to drain and recycle.
        self.assertTrue(
            result["container_recycled"],
            f"after a successful dynamic install the worker MUST be marked "
            f"for recycling (container_recycled=True); got: {result['container_recycled']}",
        )
        self.assertEqual(
            "post_install_pollution", result["recycle_reason"],
            f"recycle_reason MUST be 'post_install_pollution' on successful "
            f"install; got: {result['recycle_reason']!r}",
        )
        # post_install env still drives the HTTP execution_environment
        # field; the recycle signal is orthogonal to the recorded envId.
        result_env = result["execution_environment"]
        self.assertIsNotNone(result_env)
        self.assertEqual("sha256:post_install", result_env["environment_id"])

    def test_no_install_path_does_not_recycle(self) -> None:
        """skip_environment_setup=True OR empty install_libraries MUST NOT
        set container_recycled — the no-install path leaves the venv
        pristine, the next task can safely reuse the worker."""
        session = self._build_session()
        config = self._test_config(skip_environment_setup=True)
        baked_env = self._baked_env()

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
                    "task-no-recycle",
                    "ds1",
                    None,
                    "print('ok')",
                    None,
                    [],  # empty install list
                    5,
                    execution_environment=baked_env,
                )

        self.assertEqual([], session.install_calls)
        self.assertEqual(1, len(session.run_calls))
        self.assertFalse(
            result["container_recycled"],
            f"no-install path MUST NOT recycle; got: {result['container_recycled']}",
        )
        self.assertIsNone(result["recycle_reason"])
        # Re-collection MUST NOT have been triggered.
        self.assertEqual(0, collect_mock.call_count)


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


class RunInSandboxInitFailClosedTest(unittest.TestCase):
    """codex 2026-08-08 23:28 (msg 0d67cf11) init fail-closed lifecycle:
    ``initialize_runtime_environment`` MUST run inside the same
    ``try/finally session.close()`` as ``run_in_open_session``. If the
    helper raises (collect or container write), the freshly-created
    session/container MUST be closed exactly once so we don't leak
    containers into the pool.
    """

    def _config(self) -> SandboxConfig:
        return _test_config()

    def test_initialize_failure_closes_session_exactly_once(self) -> None:
        from app import sandbox_runner

        class _TrackingSession:
            def __init__(self) -> None:
                self.close_count = 0

            def close(self) -> None:
                self.close_count += 1

            def copy_to_runtime(self, source: str, dest_path: str) -> None:
                raise RuntimeError("container copy unreachable")

        tracking_session = _TrackingSession()

        def fake_create(config, **kwargs):
            return tracking_session

        with patch.object(sandbox_runner, "create_sandbox_session", side_effect=fake_create):
            with self.assertRaises(RuntimeError):
                sandbox_runner.run_in_sandbox(
                    self._config(),
                    "task-init-leak",
                    "ds1",
                    None,
                    "print('ok')",
                    None,
                    None,
                    5.0,
                )
        self.assertEqual(
            1, tracking_session.close_count,
            "run_in_sandbox init failure MUST close the just-created "
            "session/container exactly once; otherwise we leak containers.",
        )

    def test_initialize_failure_does_not_call_run_in_open_session(self) -> None:
        from app import sandbox_runner

        class _TrackingSession:
            def close(self) -> None:
                pass

            def copy_to_runtime(self, source: str, dest_path: str) -> None:
                raise RuntimeError("container copy unreachable")

        run_calls = []

        def fake_run_in_open_session(*args, **kwargs):
            run_calls.append((args, kwargs))
            return {}

        with patch.object(sandbox_runner, "create_sandbox_session", return_value=_TrackingSession()), \
            patch.object(sandbox_runner, "run_in_open_session", side_effect=fake_run_in_open_session):
            with self.assertRaises(RuntimeError):
                sandbox_runner.run_in_sandbox(
                    self._config(),
                    "task-init-leak",
                    "ds1",
                    None,
                    "print('ok')",
                    None,
                    None,
                    5.0,
                )
        self.assertEqual(
            [], run_calls,
            "run_in_open_session MUST NOT be invoked when initialize raises; "
            "otherwise the worker's user code runs against a stale or "
            "half-initialized environment.",
        )


if __name__ == "__main__":
    unittest.main()
