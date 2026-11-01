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


class _PutArchiveRecorder:
    """Host-side fake for the non-root staging path (260818): production
    staging hands a tar owned by the container user to docker
    ``put_archive`` (app.container_copy) instead of llm-sandbox's
    ``copy_to_runtime`` (root chown).  This recorder parses the tar and
    records ``(payload_bytes, dest_path)`` per member; container
    destination paths stay container paths, nothing touches the host
    filesystem.
    """

    def __init__(self) -> None:
        self.calls: list[tuple[bytes, str]] = []

    def put_archive(self, dest_dir: str, data: bytes) -> None:
        import io as _io
        import tarfile as _tarfile

        with _tarfile.open(fileobj=_io.BytesIO(data)) as tar:
            for member in tar.getmembers():
                payload = (
                    tar.extractfile(member).read() if member.isfile() else b""
                )
                self.calls.append(
                    (payload, f"{dest_dir.rstrip('/')}/{member.name}")
                )


def _equip_nonroot(fake) -> None:
    """Give a fake session the container/identity surface the production
    copy path needs: a recording ``container`` proxy plus the cached
    ``(uid, gid)`` identity that ``prime_container_identity`` installs on
    REAL sessions at creation time (so the in-container ``id -u``/``id -g``
    lookup never runs in these suites)."""
    from app.container_copy import CONTAINER_IDENTITY_ATTR

    fake.container = _PutArchiveRecorder()
    setattr(fake, CONTAINER_IDENTITY_ATTR, (10000, 10001))


def _bomb_copy_to_runtime(source, dest_path):
    raise AssertionError(
        "production must stage via container.put_archive (non-root "
        "contract, 260818): copy_to_runtime execs chown as root"
    )


class SandboxRunnerLoaderTest(unittest.TestCase):
    pass
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
                self.run_calls: list[tuple[str, list | None, float]] = []
                _equip_nonroot(self)

            def execute_command(self, command: str) -> _FakeOutput:
                return _FakeOutput()

            def copy_to_runtime(self, source: str, dest_path: str) -> None:
                _bomb_copy_to_runtime(source, dest_path)

            def install(self, libraries) -> None:
                self.install_calls.append(list(libraries))

            def run(self, code: str, libraries, timeout: float) -> _FakeRunResult:
                self.run_calls.append((code, libraries, timeout))
                return _FakeRunResult()

        return _FakeSession()

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
                _equip_nonroot(self)

            def execute_command(self, command: str) -> _FakeOutput:
                return _FakeOutput()

            def copy_to_runtime(self, source: str, dest_path: str) -> None:
                _bomb_copy_to_runtime(source, dest_path)

            def install(self, libraries) -> None:
                return None

            def run(self, code: str, libraries, timeout: float) -> _FakeRunResult:
                return _FakeRunResult()

        return _FakeSession()

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
        """Build a fake session whose install / staging may raise.

        copy_exc now fails the NON-ROOT staging path (container.put_archive,
        260818 — copy_to_runtime is gone). copy_runtime_env_only: when True,
        staging only raises for dest ending in runtime-environment.json, so
        dataset staging succeeds and the post-install env write is the
        failing step.
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
                self.run_calls: list[tuple[str, list | None, float]] = []
                _equip_nonroot(self)
                if copy_exc is not None:
                    original_put = self.container.put_archive

                    def maybe_failing_put(dest_dir: str, data: bytes) -> None:
                        original_put(dest_dir, data)
                        dest = self.container.calls[-1][1]
                        if copy_runtime_env_only and not dest.endswith(
                            "runtime-environment.json"
                        ):
                            return
                        raise copy_exc

                    self.container.put_archive = maybe_failing_put  # type: ignore[method-assign]

            def execute_command(self, command: str) -> _FakeOutput:
                return _FakeOutput()

            def copy_to_runtime(self, source: str, dest_path: str) -> None:
                _bomb_copy_to_runtime(source, dest_path)

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

class WriteRuntimeEnvironmentToContainerTest(unittest.TestCase):
    """260808-finance-methodspec-v5 codex rework 2026-08-08 22:49,
    updated 260818: write_runtime_environment_to_container delegates
    persistence to the NON-ROOT staging path (container.put_archive via
    app.container_copy). The tempfile is local-only and MUST be cleaned
    up after the staging attempt, even on failure (no leaked
    service-host files).
    """

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

if __name__ == "__main__":
    unittest.main()
