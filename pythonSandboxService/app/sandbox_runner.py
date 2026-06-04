from __future__ import annotations

import logging
import re
import time
from pathlib import Path
from typing import Any, Dict, List

from llm_sandbox import SandboxSession
from llm_sandbox.exceptions import SandboxTimeoutError
from llm_sandbox.pool import create_pool_manager, PoolConfig
from llm_sandbox.pool.base import ContainerPoolManager
from llm_sandbox.pool.session import PooledSandboxSession

from .config import SandboxConfig

logger = logging.getLogger(__name__)

DATASET_ID_PATTERN = re.compile(r"^[a-zA-Z0-9._-]+$")


def _normalize_library_name(library: str) -> str:
    return re.split(r"[<>=!~\[]", library.strip(), maxsplit=1)[0].strip().lower()


def _resolve_dataset_dir(config: SandboxConfig, dataset_id: str) -> Path:
    if not DATASET_ID_PATTERN.match(dataset_id):
        raise ValueError("dataset_id contains illegal characters")
    dataset_dir = (config.data_dir / dataset_id).resolve()
    base_dir = config.data_dir.resolve()
    if not str(dataset_dir).startswith(str(base_dir)):
        raise ValueError("dataset_id resolves outside base directory")
    if not dataset_dir.exists() or not dataset_dir.is_dir():
        raise FileNotFoundError("dataset_id directory not found")
    return dataset_dir


def _list_files(dataset_dir: Path, files: List[str] | None) -> List[Path]:
    if files:
        resolved = []
        for name in files:
            file_path = (dataset_dir / name).resolve()
            if not str(file_path).startswith(str(dataset_dir)):
                raise ValueError(f"invalid file path: {name}")
            if not file_path.exists() or not file_path.is_file():
                raise FileNotFoundError(f"file not found: {name}")
            resolved.append(file_path)
        return resolved
    return [path for path in dataset_dir.iterdir() if path.is_file()]


def _normalize_dataset_ids(primary: str, extra: List[str] | None) -> List[str]:
    ids: List[str] = []
    for ds_id in [primary, *(extra or [])]:
        if not ds_id:
            continue
        cleaned = ds_id.strip()
        if not cleaned or cleaned in ids:
            continue
        ids.append(cleaned)
    return ids


def _copy_dataset_file(
    session: SandboxSession | PooledSandboxSession,
    source: Path,
    dest_path: str,
) -> None:
    session.copy_to_runtime(str(source), dest_path)


def _exec_checked(
    session: SandboxSession | PooledSandboxSession,
    command: str,
    context: str = "",
) -> None:
    """Execute a command in the sandbox and raise if exit code is non-zero."""
    output = session.execute_command(command)
    if output.exit_code != 0:
        ctx = f" ({context})" if context else ""
        raise RuntimeError(
            f"Command failed{ctx}: exit_code={output.exit_code} cmd={command!r} "
            f"stderr={output.stderr!r}"
        )


def _recycle_container(session: PooledSandboxSession, reason: str) -> None:
    """Destroy container and detach from session so close() doesn't release it back to pool.

    NOTE: Uses ContainerPoolManager._destroy_container (private API) because
    llm-sandbox 0.3.33 does not expose a public discard/recycle method.
    We acquire the pool's internal lock to avoid racing with acquire/release/health_check.
    """
    if session._pooled_container and session._pool_manager:
        logger.warning("Recycling container %s: %s", session._pooled_container.container_id, reason)
        try:
            manager = session._pool_manager
            container = session._pooled_container
            with manager._condition:
                manager._destroy_container(container)
        except Exception:
            logger.exception("Failed to destroy container during recycle")
        session._pooled_container = None


def _prepare_task_workspace(
    session: SandboxSession | PooledSandboxSession,
    task_id: str,
    config: SandboxConfig,
    dataset_id_list: List[str],
    files: List[str] | None,
) -> str:
    """Create task-scoped workspace and copy datasets. Returns the task workspace path."""
    task_workspace = f"{config.workspace_root}/{task_id}"
    task_input = f"{task_workspace}/input"

    # Create workspace
    _exec_checked(session, f"mkdir -p {task_input}", "create_task_workspace")

    # Set up /sandbox/input compatibility symlink if enabled
    if config.compat_input_path_enabled:
        _exec_checked(session, f"rm -rf {config.workdir}/input", "remove_old_input")
        _exec_checked(session, f"ln -s {task_input} {config.workdir}/input", "create_input_symlink")

    # Copy datasets into task workspace
    for ds_id in dataset_id_list:
        dataset_dir = _resolve_dataset_dir(config, ds_id)
        files_to_copy = _list_files(dataset_dir, files)
        dataset_mount = f"{task_input}/{ds_id}"
        _exec_checked(session, f"mkdir -p {dataset_mount}", "create_dataset_mount")
        for file_path in files_to_copy:
            dest = f"{dataset_mount}/{file_path.name}"
            _copy_dataset_file(session, file_path, dest)
            # Compatibility copies for common read patterns
            if file_path.name == f"{ds_id}.csv":
                _copy_dataset_file(session, file_path, f"{dataset_mount}/data.csv")
                _copy_dataset_file(session, file_path, f"{task_workspace}/{ds_id}")
                _copy_dataset_file(session, file_path, f"{task_workspace}/{ds_id}.csv")
            elif file_path.name == f"{ds_id}.meta.json":
                _copy_dataset_file(session, file_path, f"{dataset_mount}/data.meta.json")

    return task_workspace


def _cleanup_task_workspace(
    session: PooledSandboxSession,
    task_id: str,
    config: SandboxConfig,
) -> bool:
    """Clean up task workspace. Returns True on success, False on failure (container should be recycled)."""
    task_workspace = f"{config.workspace_root}/{task_id}"
    try:
        # Remove task workspace
        _exec_checked(session, f"rm -rf {task_workspace}", "cleanup_task_workspace")
        # Remove compatibility symlink
        if config.compat_input_path_enabled:
            _exec_checked(session, f"rm -rf {config.workdir}/input", "cleanup_input_symlink")
        return True
    except Exception as e:
        logger.warning("Workspace cleanup failed for task %s: %s", task_id, e)
        return False


def create_pool(config: SandboxConfig) -> ContainerPoolManager | None:
    """Create and return a ContainerPoolManager if pooling is enabled."""
    if not config.pool_enabled:
        return None

    pool_config = PoolConfig(
        max_pool_size=config.pool_max_size,
        min_pool_size=config.pool_min_size,
        acquisition_timeout=config.pool_acquire_timeout_seconds,
        idle_timeout=config.pool_idle_timeout_seconds,
        max_container_uses=config.pool_max_container_uses,
    )

    runtime_configs = {
        "mem_limit": config.memory_limit,
        "memswap_limit": config.memswap_limit,
    }

    pool = create_pool_manager(
        backend=config.docker_backend,
        config=pool_config,
        lang="python",
        image=config.sandbox_image,
        runtime_configs=runtime_configs,
        workdir=config.workdir,
    )
    logger.info(
        "Pool created: max_size=%d min_size=%d acquire_timeout=%s idle_timeout=%s max_uses=%s",
        config.pool_max_size,
        config.pool_min_size,
        config.pool_acquire_timeout_seconds,
        config.pool_idle_timeout_seconds,
        config.pool_max_container_uses,
    )
    return pool


def run_in_sandbox(
    config: SandboxConfig,
    task_id: str,
    dataset_id: str,
    dataset_ids: List[str] | None,
    code: str,
    files: List[str] | None,
    libraries: List[str] | None,
    timeout_seconds: float | None,
    pool: ContainerPoolManager | None = None,
) -> dict:
    dataset_id_list = _normalize_dataset_ids(dataset_id, dataset_ids)

    timeout = timeout_seconds or config.execution_timeout_seconds
    requested_libraries = [lib.strip() for lib in (libraries or []) if lib and lib.strip()]
    install_libraries = [
        lib for lib in requested_libraries
        if _normalize_library_name(lib) not in config.preinstalled_libraries
    ]

    if config.skip_environment_setup:
        install_libraries = []

    # Timing
    t0 = time.monotonic()
    timings: Dict[str, float] = {}
    container_recycled = False
    recycle_reason: str | None = None

    if pool is not None and config.pool_enabled:
        # Pooled execution
        session = PooledSandboxSession(
            pool_manager=pool,
            workdir=config.workdir,
            execution_timeout=timeout,
        )
        t_acquire_start = time.monotonic()
        try:
            session.open()
        except Exception as e:
            logger.error("Failed to acquire pooled container for task %s: %s", task_id, e)
            raise
        timings["pool_acquire_ms"] = int((time.monotonic() - t_acquire_start) * 1000)
    else:
        # Non-pooled execution (fallback / original behavior)
        runtime_configs = {
            "mem_limit": config.memory_limit,
            "memswap_limit": config.memswap_limit,
        }
        session = SandboxSession(
            lang="python",
            image=config.sandbox_image,
            backend=config.docker_backend,
            runtime_configs=runtime_configs,
            workdir=config.workdir,
            execution_timeout=timeout,
            skip_environment_setup=config.skip_environment_setup,
        )
        t_create_start = time.monotonic()
        session.open()
        timings["container_create_ms"] = int((time.monotonic() - t_create_start) * 1000)

    try:
        # Prepare workspace
        t_workspace_start = time.monotonic()
        task_workspace = _prepare_task_workspace(
            session, task_id, config, dataset_id_list, files
        )
        timings["workspace_prepare_ms"] = int((time.monotonic() - t_workspace_start) * 1000)

        # Run code
        t_run_start = time.monotonic()
        result = session.run(code, libraries=install_libraries, timeout=timeout)
        timings["script_run_ms"] = int((time.monotonic() - t_run_start) * 1000)

        # Cleanup workspace
        if isinstance(session, PooledSandboxSession):
            t_cleanup_start = time.monotonic()
            cleanup_ok = _cleanup_task_workspace(session, task_id, config)
            timings["workspace_cleanup_ms"] = int((time.monotonic() - t_cleanup_start) * 1000)
            if not cleanup_ok:
                container_recycled = True
                recycle_reason = "cleanup_failed"
                _recycle_container(session, recycle_reason)

    except SandboxTimeoutError as e:
        logger.error("Task %s timed out: %s", task_id, e)
        if isinstance(session, PooledSandboxSession):
            container_recycled = True
            recycle_reason = "timeout"
            _recycle_container(session, recycle_reason)
        raise
    except Exception as e:
        logger.error("Task %s execution error: %s", task_id, e)
        # On any error during pooled execution, recycle container to be safe
        if isinstance(session, PooledSandboxSession):
            container_recycled = True
            recycle_reason = f"execution_error: {type(e).__name__}"
            _recycle_container(session, recycle_reason)
        raise
    finally:
        session.close()
        timings["total_duration_ms"] = int((time.monotonic() - t0) * 1000)

    primary_mount = f"{config.workdir}/input/{dataset_id}"

    logger.info(
        "Sandbox task=%s pool_enabled=%s %s recycled=%s recycle_reason=%s",
        task_id,
        pool is not None and config.pool_enabled,
        " ".join(f"{k}={v}" for k, v in timings.items()),
        container_recycled,
        recycle_reason or "none",
    )

    return {
        "exit_code": result.exit_code,
        "stdout": result.stdout or "",
        "stderr": result.stderr or "",
        "dataset_dir": primary_mount,
        "timings": timings,
        "container_recycled": container_recycled,
        "recycle_reason": recycle_reason,
    }
