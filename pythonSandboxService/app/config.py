from __future__ import annotations

import logging
import os
from dataclasses import dataclass
from pathlib import Path


logger = logging.getLogger(__name__)


# --- MethodSpec V5 output limits (Spec §7.1/§7.2, frozen contract §13) -----
# Verbatim camelCase keys used in python-sandbox.json / Nacos payloads and in
# the per-task snapshot. Contract §13 fixes the exact spelling:
# recordChannelMaxRecords/recordChannelMaxBytes/stdoutMaxBytes/stderrMaxBytes.
OUTPUT_LIMIT_KEYS: tuple[str, ...] = (
    "stdoutMaxBytes",
    "stderrMaxBytes",
    "recordChannelMaxBytes",
    "recordChannelMaxRecords",
)

# Application defaults. These mirror the *shape* of the Spec §7.1 wrapper
# input example (numbers only denote shape, 数字只表示形状). The production
# numbers must be confirmed by the four-stage tests (subprocess / Python HTTP
# / Java gateway-Dubbo / Java parse-save, Spec §7.1 & contract §13: 生产默认
# 取四段最小已验证值并留余量；正式数字必须由工作包 C/D 的四段测试确认，本协议
# 不编造生产值). Do not raise them until those tests exist.
DEFAULT_OUTPUT_LIMITS: dict[str, int] = {
    "stdoutMaxBytes": 1048576,
    "stderrMaxBytes": 262144,
    "recordChannelMaxBytes": 262144,
    "recordChannelMaxRecords": 128,
}

# Static hard ceilings, written in code (Spec §7.1: 静态硬上限写在代码或启动
# 配置里，Nacos 只能调低或调到硬上限，不能提高硬上限; contract §13: 静态硬上限
# 只能被 Nacos 调低，不能被动态配置提高). Until the four-stage tests confirm
# real numbers, the ceilings are pinned AT the application defaults so no
# dynamic config can raise any limit above what the code ships with. Raising a
# ceiling requires a code change backed by four-stage test evidence.
HARD_OUTPUT_LIMIT_CEILINGS: dict[str, int] = {
    "stdoutMaxBytes": 1048576,
    "stderrMaxBytes": 262144,
    "recordChannelMaxBytes": 262144,
    "recordChannelMaxRecords": 128,
}


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
    # Per-container concurrency: how many Python tasks may execute concurrently
    # inside a single warm container. Default 5; set to 1 for the legacy serial
    # behavior. Values >1 require compat_input_path_enabled=False (global symlink
    # would otherwise collide across concurrent tasks).
    container_max_concurrency: int
    # Pool config
    pool_enabled: bool
    pool_min_size: int
    pool_max_size: int
    pool_acquire_timeout_seconds: float
    pool_idle_timeout_seconds: float | None
    pool_max_container_uses: int | None
    workspace_root: str
    compat_input_path_enabled: bool
    standard_memory_limit_bytes: int = 512 * 1024 * 1024
    heavy_memory_limit_bytes: int = 1536 * 1024 * 1024
    queue_wait_timeout_seconds: float = 30.0
    usage_sampling_interval_millis: int = 200
    task_store_path: Path = Path("/data/sandbox_tasks/state.json")
    # MethodSpec V5 sandbox output limits (Spec §7.2 / contract §13).
    # Application defaults; the dynamic (Nacos) layer may only lower these or
    # clamp them down to HARD_OUTPUT_LIMIT_CEILINGS, never raise them.
    stdout_max_bytes: int = DEFAULT_OUTPUT_LIMITS["stdoutMaxBytes"]
    stderr_max_bytes: int = DEFAULT_OUTPUT_LIMITS["stderrMaxBytes"]
    record_channel_max_bytes: int = DEFAULT_OUTPUT_LIMITS["recordChannelMaxBytes"]
    record_channel_max_records: int = DEFAULT_OUTPUT_LIMITS["recordChannelMaxRecords"]


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
    container_max_concurrency = int(os.getenv("AF_SANDBOX_CONTAINER_MAX_CONCURRENCY", "1"))
    if container_max_concurrency < 1:
        raise ValueError(
            f"Invalid container_max_concurrency ({container_max_concurrency}): must be >= 1."
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
    standard_memory_limit_bytes = int(os.getenv("AF_SANDBOX_STANDARD_MEMORY_BYTES", str(512 * 1024 * 1024)))
    heavy_memory_limit_bytes = int(os.getenv("AF_SANDBOX_HEAVY_MEMORY_BYTES", str(1536 * 1024 * 1024)))
    queue_wait_timeout_seconds = float(os.getenv("AF_SANDBOX_QUEUE_WAIT_TIMEOUT", "30"))
    usage_sampling_interval_millis = int(os.getenv("AF_SANDBOX_USAGE_SAMPLE_MILLIS", "200"))
    task_store_path = Path(os.getenv("AF_SANDBOX_TASK_STORE_PATH", "/data/sandbox_tasks/state.json"))

    # Config validation
    if pool_enabled and pool_min_size > pool_max_size:
        raise ValueError(
            f"Invalid pool config: pool_min_size ({pool_min_size}) > pool_max_size ({pool_max_size}). "
            f"Ensure AF_SANDBOX_POOL_MIN_SIZE <= AF_SANDBOX_POOL_MAX_SIZE."
        )
    if standard_memory_limit_bytes <= 0 or heavy_memory_limit_bytes <= standard_memory_limit_bytes:
        raise ValueError("Sandbox memory limits must be positive and HEAVY must exceed STANDARD")
    if queue_wait_timeout_seconds <= 0:
        raise ValueError("AF_SANDBOX_QUEUE_WAIT_TIMEOUT must be positive")
    if usage_sampling_interval_millis <= 0:
        raise ValueError("AF_SANDBOX_USAGE_SAMPLE_MILLIS must be positive")
    if container_max_concurrency > 1 and compat_input_path_enabled:
        # The global /sandbox/input symlink would be overwritten by concurrent tasks.
        logger.warning(
            "container_max_concurrency=%s > 1 is incompatible with compat_input_path_enabled=True; "
            "disabling compat_input_path_enabled automatically.",
            container_max_concurrency,
        )
        compat_input_path_enabled = False
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
        container_max_concurrency=container_max_concurrency,
        pool_enabled=pool_enabled,
        pool_min_size=pool_min_size,
        pool_max_size=pool_max_size,
        pool_acquire_timeout_seconds=pool_acquire_timeout,
        pool_idle_timeout_seconds=pool_idle_timeout,
        pool_max_container_uses=pool_max_container_uses,
        workspace_root=workspace_root,
        compat_input_path_enabled=compat_input_path_enabled,
        standard_memory_limit_bytes=standard_memory_limit_bytes,
        heavy_memory_limit_bytes=heavy_memory_limit_bytes,
        queue_wait_timeout_seconds=queue_wait_timeout_seconds,
        usage_sampling_interval_millis=usage_sampling_interval_millis,
        task_store_path=task_store_path,
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
