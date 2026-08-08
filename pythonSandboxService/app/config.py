from __future__ import annotations

import logging
import os
import re
from dataclasses import dataclass
from pathlib import Path


logger = logging.getLogger(__name__)


# --- AF_SANDBOX_IMAGE reference policy (MethodSpec V5, Spec §12) -------------
# Production AF_SANDBOX_IMAGE must be a sha256 digest reference; a bare tag is
# permitted only behind the explicit, independent dev-allow switch
# AF_SANDBOX_IMAGE_ALLOW_DEV_TAG (accepted values: true/1). There is NO silent
# fallback to `latest`.
#
# These helpers mirror scripts/build_runtime_manifest.py's is_digest_reference /
# validate_af_sandbox_image with identical semantics. They are implemented here
# (plain stdlib, no pydantic) because scripts/ is not an importable package.
# The identical semantics are also pinned over the same accept/reject vectors
# (tests/digest_reference_vectors.py) for the shell entry points
# (deploy_latest.sh / docker_build.sh via scripts/af_digest_reference.sh).
#
# A pinned runtime image ref must be EXACTLY ``<repo>@sha256:<64 lowercase
# hex>``: anchored full match over the ENTIRE string (no leading/trailing
# content), lowercase-only hex. Grammar mirrors the Docker reference grammar
# (conservative lowercase-only form, see scripts/build_runtime_manifest.py).
_PATH_COMPONENT = r"[a-z0-9]+(?:[._-][a-z0-9]+)*"
_DIGEST_REFERENCE_RE = re.compile(
    # Optional leading registry host component (may carry a numeric port),
    # e.g. "registry.local/" or "registry.local:5000/". A port is only
    # accepted when a path component follows it ("host:5000" alone is not a
    # plausible repository).
    r"(?:%s(?::[0-9]+)?/)?" % _PATH_COMPONENT
    # At least one path component, then any number of further components.
    # Components are non-empty, so there can be no leading/trailing slash,
    # no empty "//" components, no whitespace and no control characters.
    + _PATH_COMPONENT
    + r"(?:/%s)*" % _PATH_COMPONENT
    # Digest: "@sha256:" + exactly 64 LOWERCASE hex chars; the fullmatch
    # anchors both ends (trailing hex chars or ":latest" are rejected).
    + r"@sha256:[0-9a-f]{64}"
)

# A syntactically VALID bare tag/reference (admitted ONLY under the explicit
# dev-allow switch; Spec §12 round-2 R2-4): ``<repo>[:<tag>]`` over the same
# conservative lowercase path grammar. No ``@`` anywhere -- anything
# digest-shaped must satisfy the anchored digest grammar EVEN UNDER the dev
# switch. Tag grammar per the conservative Docker reference form.
_BARE_TAG = r"[A-Za-z0-9_][A-Za-z0-9._-]{0,127}"
_DEV_TAG_REFERENCE_RE = re.compile(
    r"(?:%s(?::[0-9]+)?/)?" % _PATH_COMPONENT
    + _PATH_COMPONENT
    + r"(?:/%s)*" % _PATH_COMPONENT
    + r"(?::%s)?" % _BARE_TAG
)

# Exact accepted values for AF_SANDBOX_IMAGE_ALLOW_DEV_TAG (Spec §12: the
# dev-allow must be an independent, explicit switch -- never implicit).
_DEV_TAG_ALLOW_VALUES = {"true", "1"}


def is_digest_reference(value: str) -> bool:
    """Return True iff ``value`` is a complete sha256 digest reference.

    The ENTIRE string must be ``<repo>@sha256:<64 lowercase hex>`` (anchored
    full match, lowercase-only), e.g.
    ``registry.local/alphafrog/runtime@sha256:<64hex>``. Uppercase hex, a
    wrong hex length, or any leading/trailing content is rejected.
    """
    if not isinstance(value, str):
        return False
    return _DIGEST_REFERENCE_RE.fullmatch(value) is not None


def is_valid_dev_reference(value: str) -> bool:
    """Return True iff ``value`` is a syntactically VALID bare tag/reference.

    This is the ONLY shape the explicit dev-allow switch admits (Spec §12
    round-2 R2-4): ``<repo>[:<tag>]`` over the conservative lowercase path
    grammar, anchored full match. Empty values, whitespace or control
    characters, uppercase repositories and malformed tags are rejected;
    anything ``@``-bearing is digest-shaped and must satisfy the anchored
    lowercase digest grammar EVEN UNDER the dev switch (rejected here).
    """
    if not isinstance(value, str) or not value:
        return False
    return _DEV_TAG_REFERENCE_RE.fullmatch(value) is not None


def validate_sandbox_image(value: str, *, allow_dev_tag: bool) -> None:
    """Validate an ``AF_SANDBOX_IMAGE`` reference (Spec §12).

    Digest references are always accepted. The dev-allow switch admits ONLY a
    syntactically valid bare tag/reference (``is_valid_dev_reference``) -- it
    is NOT a blanket bypass (Spec §12 round-2 R2-4): empty values,
    whitespace/control characters, wrong digest lengths, uppercase digests
    and arbitrary garbage are ALWAYS rejected, and anything digest-shaped but
    not an anchored-lowercase-64hex digest reference is rejected EVEN UNDER
    the switch. Raises ``ValueError`` otherwise; never falls back silently to
    ``latest``.
    """
    if not isinstance(value, str) or not value.strip():
        raise ValueError(
            "AF_SANDBOX_IMAGE must be a non-empty image reference; got %r. "
            "There is no implicit default and no silent fallback to 'latest' "
            "(Spec §12)." % (value,)
        )
    if is_digest_reference(value):
        return
    if "@" in value:
        raise ValueError(
            "AF_SANDBOX_IMAGE %r is digest-shaped but is NOT a valid anchored "
            "sha256:<64 lowercase hex> digest reference; such values are "
            "rejected EVEN UNDER the dev-allow switch (Spec §12 R2-4)."
            % (value,)
        )
    if allow_dev_tag and is_valid_dev_reference(value):
        return
    raise ValueError(
        "AF_SANDBOX_IMAGE must be a sha256 digest reference "
        "(e.g. repo/name@sha256:<64hex>); got bare/undigested reference %r. "
        "Set AF_SANDBOX_IMAGE_ALLOW_DEV_TAG=true (or 1) to permit a development "
        "tag. Spec §12: production requires a digest reference and must never "
        "silently fall back to 'latest'." % (value,)
    )


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


def load_config() -> SandboxConfig:
    data_dir = Path(os.getenv("AF_SANDBOX_DATA_DIR", "data/agent_datasets"))
    max_concurrency = int(os.getenv("AF_SANDBOX_MAX_CONCURRENCY", "2"))
    execution_timeout = float(os.getenv("AF_SANDBOX_EXECUTION_TIMEOUT", "5"))
    memory_limit = os.getenv("AF_SANDBOX_MEMORY", "512m")
    memswap_limit = os.getenv("AF_SANDBOX_MEMSWAP", "512m")
    docker_backend = os.getenv("AF_SANDBOX_BACKEND", "docker")
    workdir = os.getenv("AF_SANDBOX_WORKDIR", "/sandbox")
    log_level = os.getenv("AF_SANDBOX_LOG_LEVEL", "INFO")
    # Spec §12: AF_SANDBOX_IMAGE has NO implicit default (the pre-§12 silent
    # fallback to "alphafrog-sandbox-runtime:latest" is removed). Production
    # requires a sha256 digest reference; a bare tag is accepted only when the
    # explicit dev-allow switch AF_SANDBOX_IMAGE_ALLOW_DEV_TAG is true/1.
    sandbox_image = os.getenv("AF_SANDBOX_IMAGE", "").strip()
    sandbox_image_allow_dev_tag = (
        os.getenv("AF_SANDBOX_IMAGE_ALLOW_DEV_TAG", "").strip().lower()
        in _DEV_TAG_ALLOW_VALUES
    )
    if not sandbox_image:
        raise ValueError(
            "AF_SANDBOX_IMAGE must be set explicitly; there is no implicit "
            "default and no silent fallback to 'latest' (Spec §12). Production "
            "requires a sha256 digest reference, e.g. "
            "registry.example/alphafrog/runtime@sha256:<64hex>."
        )
    validate_sandbox_image(sandbox_image, allow_dev_tag=sandbox_image_allow_dev_tag)
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
