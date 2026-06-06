from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class SandboxConfig:
    data_dir: Path
    max_concurrency: int
    execution_timeout_seconds: float
    memory_limit: str
    memswap_limit: str
    docker_backend: str
    workdir: str
    log_level: str
    sandbox_image: str
    skip_environment_setup: bool
    preinstalled_libraries: frozenset[str]
    # Pool config
    pool_enabled: bool
    pool_min_size: int
    pool_max_size: int
    pool_acquire_timeout_seconds: float
    pool_idle_timeout_seconds: float | None
    pool_max_container_uses: int | None
    workspace_root: str
    compat_input_path_enabled: bool


def load_config() -> SandboxConfig:
    data_dir = Path(os.getenv("AF_SANDBOX_DATA_DIR", "data/agent_datasets"))
    max_concurrency = int(os.getenv("AF_SANDBOX_MAX_CONCURRENCY", "2"))
    execution_timeout = float(os.getenv("AF_SANDBOX_EXECUTION_TIMEOUT", "5"))
    memory_limit = os.getenv("AF_SANDBOX_MEMORY", "512m")
    memswap_limit = os.getenv("AF_SANDBOX_MEMSWAP", "512m")
    docker_backend = os.getenv("AF_SANDBOX_BACKEND", "docker")
    workdir = os.getenv("AF_SANDBOX_WORKDIR", "/sandbox")
    log_level = os.getenv("AF_SANDBOX_LOG_LEVEL", "INFO")
    sandbox_image = os.getenv("AF_SANDBOX_IMAGE", "alphafrog-sandbox-runtime:latest")
    skip_environment_setup = _parse_bool(os.getenv("AF_SANDBOX_SKIP_ENVIRONMENT_SETUP"), default=True)
    preinstalled_libraries = frozenset(
        item.strip().lower()
        for item in os.getenv(
            "AF_SANDBOX_PREINSTALLED_LIBRARIES",
            "numpy,pandas,matplotlib,scipy",
        ).split(",")
        if item.strip()
    )
    # Pool config (default disabled for safe rollout)
    pool_enabled = _parse_bool(os.getenv("AF_SANDBOX_POOL_ENABLED"), default=False)
    pool_min_size = int(os.getenv("AF_SANDBOX_POOL_MIN_SIZE", "2"))
    pool_max_size = int(os.getenv("AF_SANDBOX_POOL_MAX_SIZE", str(max_concurrency)))
    pool_acquire_timeout = float(os.getenv("AF_SANDBOX_POOL_ACQUIRE_TIMEOUT", "30"))
    pool_idle_timeout = _parse_float_or_none(os.getenv("AF_SANDBOX_POOL_IDLE_TIMEOUT"), default=300.0)
    pool_max_container_uses = _parse_int_or_none(os.getenv("AF_SANDBOX_POOL_MAX_CONTAINER_USES"))
    workspace_root = os.getenv("AF_SANDBOX_WORKSPACE_ROOT", "/sandbox/runs")
    compat_input_path_enabled = _parse_bool(os.getenv("AF_SANDBOX_COMPAT_INPUT_PATH"), default=True)

    # Config validation
    if pool_enabled and pool_min_size > pool_max_size:
        raise ValueError(
            f"Invalid pool config: pool_min_size ({pool_min_size}) > pool_max_size ({pool_max_size}). "
            f"Ensure AF_SANDBOX_POOL_MIN_SIZE <= AF_SANDBOX_POOL_MAX_SIZE."
        )
    return SandboxConfig(
        data_dir=data_dir,
        max_concurrency=max_concurrency,
        execution_timeout_seconds=execution_timeout,
        memory_limit=memory_limit,
        memswap_limit=memswap_limit,
        docker_backend=docker_backend,
        workdir=workdir,
        log_level=log_level,
        sandbox_image=sandbox_image,
        skip_environment_setup=skip_environment_setup,
        preinstalled_libraries=preinstalled_libraries,
        pool_enabled=pool_enabled,
        pool_min_size=pool_min_size,
        pool_max_size=pool_max_size,
        pool_acquire_timeout_seconds=pool_acquire_timeout,
        pool_idle_timeout_seconds=pool_idle_timeout,
        pool_max_container_uses=pool_max_container_uses,
        workspace_root=workspace_root,
        compat_input_path_enabled=compat_input_path_enabled,
    )


def _parse_bool(value: str | None, default: bool) -> bool:
    if value is None or value == "":
        return default
    return value.strip().lower() in {"1", "true", "yes", "y", "on"}


def _parse_float_or_none(value: str | None, default: float | None) -> float | None:
    if value is None or value.strip() == "":
        return default
    try:
        return float(value)
    except ValueError:
        return default


def _parse_int_or_none(value: str | None) -> int | None:
    if value is None or value.strip() == "":
        return None
    try:
        return int(value)
    except ValueError:
        return None
