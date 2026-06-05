from __future__ import annotations

import logging
import re
import time
from pathlib import Path
from typing import Any, Dict, List

from llm_sandbox import SandboxSession
from llm_sandbox.exceptions import SandboxTimeoutError

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
    session: SandboxSession,
    source: Path,
    dest_path: str,
) -> None:
    session.copy_to_runtime(str(source), dest_path)


def _log_in_container(
    session: SandboxSession,
    task_id: str,
    config: SandboxConfig,
    message: str,
) -> None:
    """Write a timestamped log line inside the container for debugging."""
    log_path = f"{config.workspace_root}/{task_id}/task.log"
    cmd = f"echo '[$(date -Iseconds)] {message}' >> {log_path}"
    try:
        session.execute_command(cmd)
    except Exception:
        # Best-effort: container logging should not break the task
        pass


def _flush_container_log(
    session: SandboxSession,
    task_id: str,
    config: SandboxConfig,
) -> None:
    """Read the container-internal task log and emit it to the service logger.

    This preserves the log in service stdout even when the workspace is deleted
    during cleanup.
    """
    log_path = f"{config.workspace_root}/{task_id}/task.log"
    try:
        output = session.execute_command(f"cat {log_path} 2>/dev/null || true")
        if output.stdout:
            for line in output.stdout.strip().splitlines():
                logger.info("[container-log] task=%s %s", task_id, line)
    except Exception:
        pass


def _exec_checked(
    session: SandboxSession,
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


def _prepare_task_workspace(
    session: SandboxSession,
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
    _log_in_container(session, task_id, config, f"task_start workspace={task_workspace}")

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
                _copy_dataset_file(session, file_path, f"{task_workspace}/{ds_id}.csv")
            elif file_path.name == f"{ds_id}.meta.json":
                _copy_dataset_file(session, file_path, f"{dataset_mount}/data.meta.json")
        _log_in_container(session, task_id, config, f"dataset_ready dataset={ds_id} files={len(files_to_copy)}")

    return task_workspace


def _cleanup_task_workspace(
    session: SandboxSession,
    task_id: str,
    config: SandboxConfig,
) -> bool:
    """Clean up task workspace. Returns True on success, False on failure (container should be recycled)."""
    task_workspace = f"{config.workspace_root}/{task_id}"
    _log_in_container(session, task_id, config, "cleanup_start")
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


def create_sandbox_session(config: SandboxConfig, *, execution_timeout: float | None = None) -> SandboxSession:
    """Create and open one llm-sandbox session.

    The caller owns the returned session and must close it.
    """
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
        execution_timeout=execution_timeout or config.execution_timeout_seconds,
        skip_environment_setup=config.skip_environment_setup,
    )
    session.open()
    return session


def get_session_container_id(session: SandboxSession) -> str:
    """Best-effort container id extraction across llm-sandbox versions."""
    for attr in ("container_id", "_container_id"):
        value = getattr(session, attr, None)
        if value:
            return str(value)
    container = getattr(session, "container", None) or getattr(session, "_container", None)
    if container is not None:
        value = getattr(container, "id", None) or getattr(container, "short_id", None)
        if value:
            return str(value)
    return "unknown"


def smoke_check_session(config: SandboxConfig, session: SandboxSession, container_id: str) -> None:
    """Verify the warm container has a usable baked runtime."""
    smoke_cmd = f"""
set -e
test -x {config.workdir}/.sandbox-venv/bin/python
{config.workdir}/.sandbox-venv/bin/python - <<'PY'
import numpy
import pandas
import matplotlib
import scipy
print("sandbox runtime ready")
PY
test -w {config.workdir}
"""
    _exec_checked(session, smoke_cmd, f"ready_check container={container_id}")


def run_in_open_session(
    config: SandboxConfig,
    session: SandboxSession,
    task_id: str,
    dataset_id: str,
    dataset_ids: List[str] | None,
    code: str,
    files: List[str] | None,
    libraries: List[str] | None,
    timeout_seconds: float | None,
    *,
    queue_wait_ms: int | None = None,
    container_id: str | None = None,
    pool_enabled: bool = True,
) -> dict:
    """Run one task inside an already-open session.

    The caller owns the container lifecycle. This function returns
    container_recycled=True when the caller should destroy and replace it.
    """
    dataset_id_list = _normalize_dataset_ids(dataset_id, dataset_ids)
    timeout = timeout_seconds or config.execution_timeout_seconds
    requested_libraries = [lib.strip() for lib in (libraries or []) if lib and lib.strip()]
    install_libraries = [
        lib for lib in requested_libraries
        if _normalize_library_name(lib) not in config.preinstalled_libraries
    ]
    if config.skip_environment_setup:
        install_libraries = []

    t0 = time.monotonic()
    timings: Dict[str, float] = {}
    if queue_wait_ms is not None:
        timings["queue_wait_ms"] = queue_wait_ms
    result = None
    container_recycled = False
    recycle_reason: str | None = None

    try:
        t_workspace_start = time.monotonic()
        _prepare_task_workspace(session, task_id, config, dataset_id_list, files)
        timings["workspace_prepare_ms"] = int((time.monotonic() - t_workspace_start) * 1000)

        _log_in_container(session, task_id, config, "script_start")
        t_run_start = time.monotonic()
        result = session.run(code, libraries=install_libraries, timeout=timeout)
        timings["script_run_ms"] = int((time.monotonic() - t_run_start) * 1000)
        _log_in_container(
            session,
            task_id,
            config,
            f"script_end exit_code={result.exit_code} stdout_len={len(result.stdout or '')} stderr_len={len(result.stderr or '')}",
        )

        _flush_container_log(session, task_id, config)

        t_cleanup_start = time.monotonic()
        cleanup_ok = _cleanup_task_workspace(session, task_id, config)
        timings["workspace_cleanup_ms"] = int((time.monotonic() - t_cleanup_start) * 1000)
        if cleanup_ok:
            _log_in_container(session, task_id, config, "cleanup_end ok")
        else:
            container_recycled = True
            recycle_reason = "cleanup_failed"
            _log_in_container(session, task_id, config, f"cleanup_failed recycle={recycle_reason}")

    except SandboxTimeoutError as e:
        logger.error("Task %s timed out: %s", task_id, e)
        _flush_container_log(session, task_id, config)
        _log_in_container(session, task_id, config, "script_timeout recycle=timeout")
        raise
    except Exception as e:
        logger.error("Task %s execution error: %s", task_id, e)
        _flush_container_log(session, task_id, config)
        _log_in_container(session, task_id, config, f"script_error error={type(e).__name__}")
        raise
    finally:
        timings["total_runner_ms"] = int((time.monotonic() - t0) * 1000)

    primary_mount = f"{config.workdir}/input/{dataset_id}"
    actual_container_id = container_id or get_session_container_id(session)
    logger.info(
        "Sandbox task=%s container=%s pool_enabled=%s %s recycled=%s recycle_reason=%s",
        task_id,
        actual_container_id,
        pool_enabled,
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
        "container_id": actual_container_id,
    }


def run_in_sandbox(
    config: SandboxConfig,
    task_id: str,
    dataset_id: str,
    dataset_ids: List[str] | None,
    code: str,
    files: List[str] | None,
    libraries: List[str] | None,
    timeout_seconds: float | None,
) -> dict:
    timeout = timeout_seconds or config.execution_timeout_seconds
    t0 = time.monotonic()
    t_create_start = time.monotonic()
    session = create_sandbox_session(config, execution_timeout=timeout)
    container_create_ms = int((time.monotonic() - t_create_start) * 1000)
    container_id = get_session_container_id(session)
    try:
        result = run_in_open_session(
            config,
            session,
            task_id,
            dataset_id,
            dataset_ids,
            code,
            files,
            libraries,
            timeout_seconds,
            queue_wait_ms=0,
            container_id=container_id,
            pool_enabled=False,
        )
        timings = result.setdefault("timings", {})
        timings["container_create_ms"] = container_create_ms
        timings["total_duration_ms"] = int((time.monotonic() - t0) * 1000)
        logger.info(
            "Sandbox task=%s container=%s pool_enabled=False container_create_ms=%s total_duration_ms=%s",
            task_id,
            container_id,
            container_create_ms,
            timings["total_duration_ms"],
        )
        return result
    finally:
        session.close()
