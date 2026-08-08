from __future__ import annotations

import hashlib
import importlib
import json
import logging
import os
import tempfile
from pathlib import Path
from typing import Any, List, Optional, Tuple

from .models import ExecutionEnvironment, SandboxPackageApi


logger = logging.getLogger(__name__)


# 260808-finance-methodspec-v5 work package D: single-source generator for the
# runtime environment snapshot. Both the workdir/runtime-environment.json file
# and the HTTP execution_environment field MUST derive from the same
# ExecutionEnvironment instance returned here. This module is the proto
# authoritative source for environmentId / imageDigest / librarySetDigest /
# packageApis; downstream consumers must NOT recompute these values.

# Spec §8 L1019: packages without an explicit __api_version__ attribute fall
# back to this documented wire default. Tests pin this value so a change here
# requires an explicit contract update.
DEFAULT_PACKAGE_API_VERSION = "1.0"


def _canonical_bytes(data: Any) -> bytes:
    """Stable JSON encoding: sorted keys, no whitespace, UTF-8."""
    return json.dumps(
        data, sort_keys=True, separators=(",", ":"), ensure_ascii=False
    ).encode("utf-8")


def _sha256_hex_digest(data: bytes) -> str:
    return "sha256:" + hashlib.sha256(data).hexdigest()


def _read_package_api_version(package_name: str) -> str:
    """Read api_version from a package's module-level __api_version__ attribute.

    Spec §8 L1019: hardcoding "1.0" for every package broke E's target/actual
    API compatibility check (completion criteria #9), which depends on real
    values. Try to import the package and read its ``__api_version__``; on any
    failure (ImportError, AttributeError, syntax error inside the package),
    fall back to the documented wire default so a broken package cannot crash
    environment collection.

    This is called from the warm container at sandbox_runner startup, so
    import cost is paid once per container per package.
    """
    try:
        module = importlib.import_module(package_name)
    except Exception as exc:
        logger.info(
            "RUNTIME_ENVIRONMENT_API_VERSION_FALLBACK_IMPORT name=%s reason=%s",
            package_name, exc,
        )
        return DEFAULT_PACKAGE_API_VERSION
    value = getattr(module, "__api_version__", None)
    if not isinstance(value, str) or not value:
        logger.info(
            "RUNTIME_ENVIRONMENT_API_VERSION_FALLBACK_MISSING name=%s default=%s",
            package_name, DEFAULT_PACKAGE_API_VERSION,
        )
        return DEFAULT_PACKAGE_API_VERSION
    return value


def _inspect_container_image_digest(container_id: str) -> str:
    """Best-effort Docker SDK inspect for the running container's image digest.

    Returns the `sha256:...` digest if the SDK is importable and the inspect
    succeeds. Returns "" on any failure (SDK missing, container missing,
    unexpected image format). Callers must NOT silently substitute a fake
    value; an empty digest combined with inventory_complete=False signals
    "environment facts unknown" to downstream consumers.
    """
    if not container_id or container_id == "unknown":
        return ""
    client = None
    try:
        import docker

        client = docker.from_env()
        container = client.containers.get(container_id)
        container.reload()
        image = container.attrs.get("Image") or ""
        if isinstance(image, str) and image.startswith("sha256:"):
            return image
        logger.warning(
            "RUNTIME_ENVIRONMENT_IMAGE_DIGEST_UNEXPECTED container=%s image=%r",
            container_id, image,
        )
        return ""
    except Exception as exc:
        logger.warning(
            "RUNTIME_ENVIRONMENT_DOCKER_INSPECT_FAILED container=%s error=%s",
            container_id, exc,
        )
        return ""
    finally:
        if client is not None:
            try:
                client.close()
            except Exception:
                pass


def _read_installed_packages(session: Any) -> Tuple[List[dict], bool]:
    """Run `pip list --format=json` inside the sandbox and parse the output.

    Returns (packages, inventory_complete). inventory_complete is True iff
    pip list returned a non-empty list of {name, version} rows. Any failure
    (no session, command non-zero, empty output, parse error) yields
    inventory_complete=False so downstream can flag "unknown hidden packages".
    """
    if session is None:
        return [], False
    try:
        command = (
            "python -m pip list --format=json --disable-pip-version-check "
            "2>/dev/null"
        )
        output = session.execute_command(command)
        if getattr(output, "exit_code", 0) != 0:
            return [], False
        text = (getattr(output, "stdout", "") or "").strip()
        if not text:
            return [], False
        rows = json.loads(text)
        if not isinstance(rows, list) or not rows:
            return [], False
        packages: List[dict] = []
        for row in rows:
            if not isinstance(row, dict):
                continue
            name = str(row.get("name") or "").strip().lower()
            version = str(row.get("version") or "").strip()
            if not name or not version:
                continue
            packages.append({"name": name, "version": version})
        if not packages:
            return [], False
        return packages, True
    except Exception as exc:
        logger.warning(
            "RUNTIME_ENVIRONMENT_PIP_LIST_FAILED error=%s", exc,
        )
        return [], False


def collect_runtime_environment(
    *,
    container_id: str,
    session: Any = None,
) -> ExecutionEnvironment:
    """Single-source generator for the runtime environment snapshot.

    Both the workdir runtime-environment.json file and the HTTP
    execution_environment field MUST come from the same ExecutionEnvironment
    instance returned here. Callers should:
      1. env = collect_runtime_environment(container_id=..., session=...)
      2. write_runtime_environment_json(task_workspace, env)
      3. surface env on the HTTP ExecuteResult
    Three places, one ExecutionEnvironment instance — never recomputed.

    Returns an ExecutionEnvironment with:
      - environment_id: SHA-256 of canonical-encoded snapshot
        (image_digest + library_set_digest + sorted package_apis)
      - image_digest: Docker-inspected container image (sha256:...) or ""
      - library_set_digest: SHA-256 of canonical-encoded sorted package list
      - package_apis: SandboxPackageApi list sorted by package name
      - inventory_complete: True iff pip list succeeded non-empty
    """
    image_digest = _inspect_container_image_digest(container_id)
    packages, inventory_complete = _read_installed_packages(session)
    packages_sorted = sorted(packages, key=lambda p: p["name"])

    library_set_payload = [
        {"name": p["name"], "version": p["version"]} for p in packages_sorted
    ]
    library_set_digest = _sha256_hex_digest(_canonical_bytes(library_set_payload))

    # Spec §8 L1019: api_version must come from each package's own metadata
    # (__api_version__ attribute), not a hardcoded constant — E's target/actual
    # API compatibility check (completion criteria #9) depends on real values.
    # Packages without __api_version__ fall back to DEFAULT_PACKAGE_API_VERSION
    # via _read_package_api_version; the default is pinned in tests.
    package_apis = [
        SandboxPackageApi(
            name=p["name"],
            version=p["version"],
            api_version=_read_package_api_version(p["name"]),
        )
        for p in packages_sorted
    ]

    snapshot = {
        "image_digest": image_digest,
        "library_set_digest": library_set_digest,
        "package_apis": [
            {
                "name": pkg.name,
                "version": pkg.version,
                "api_version": pkg.api_version,
            }
            for pkg in package_apis
        ],
    }
    environment_id = _sha256_hex_digest(_canonical_bytes(snapshot))

    return ExecutionEnvironment(
        environment_id=environment_id,
        image_digest=image_digest,
        library_set_digest=library_set_digest,
        package_apis=package_apis,
        inventory_complete=inventory_complete,
    )


def write_runtime_environment_json(
    task_workspace: str, env: ExecutionEnvironment
) -> str:
    """Persist the ExecutionEnvironment snapshot on the **service** filesystem.

    The service host's <task_workspace>/runtime-environment.json is an ops
    audit copy only — user code inside the execution container reads from a
    SEPARATE file written via write_runtime_environment_to_container (the
    service host's filesystem is not visible inside the running sandbox
    container; only volumes explicitly mounted are shared, and the python-
    sandbox-service compose does not mount the sandbox workdir into the
    execution container).

    Use write_runtime_environment_to_container for the runtime-visible
    file that user code actually consults. This helper is kept for ops
    audit / debugging on the service host.
    """
    os.makedirs(task_workspace, exist_ok=True)
    path = os.path.join(task_workspace, "runtime-environment.json")
    payload = json.dumps(
        env.model_dump(mode="json"), indent=2, sort_keys=True
    )
    with open(path, "w", encoding="utf-8") as fp:
        fp.write(payload)
    return path


def write_runtime_environment_to_container(
    session: Any,
    env: ExecutionEnvironment,
    dest_path: str,
) -> str:
    """Serialize env and push it into the execution container via copy_to_runtime.

    Spec §8 L1019 (codex rework 2026-08-08 22:49): the runtime-environment.json
    that user code (e.g., report()) reads MUST live inside the execution
    container, not on the service host's filesystem. The two have independent
    /sandbox roots — the service's local /sandbox/runtime-environment.json
    is invisible to a Python subprocess inside the container. This helper
    serializes the ExecutionEnvironment to JSON, drops it onto a local
    tempfile, and asks the llm-sandbox session to copy that file into the
    container at dest_path. The tempfile is cleaned up regardless of outcome.

    Returns dest_path so callers can chain it (e.g., echo into log lines).
    Raises whatever session.copy_to_runtime raises; callers may catch and
    downgrade to a warning if best-effort, but Spec §8 treats the container
    file as the runtime-visible single source of truth for environmentId.
    """
    payload = env.model_dump(mode="json")
    temp_path: Optional[str] = None
    try:
        with tempfile.NamedTemporaryFile(
            "w", encoding="utf-8", delete=False, suffix=".json",
        ) as handle:
            json.dump(payload, handle, indent=2, sort_keys=True, ensure_ascii=False)
            temp_path = handle.name
        session.copy_to_runtime(temp_path, dest_path)
        return dest_path
    finally:
        if temp_path is not None:
            Path(temp_path).unlink(missing_ok=True)


__all__ = [
    "collect_runtime_environment",
    "write_runtime_environment_json",
    "write_runtime_environment_to_container",
]
