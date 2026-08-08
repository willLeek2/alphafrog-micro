from __future__ import annotations

import hashlib
import json
import logging
import os
from typing import Any, List, Optional, Tuple

from .models import ExecutionEnvironment, SandboxPackageApi


logger = logging.getLogger(__name__)


# 260808-finance-methodspec-v5 work package D: single-source generator for the
# runtime environment snapshot. Both the workdir/runtime-environment.json file
# and the HTTP execution_environment field MUST derive from the same
# ExecutionEnvironment instance returned here. This module is the proto
# authoritative source for environmentId / imageDigest / librarySetDigest /
# packageApis; downstream consumers must NOT recompute these values.


def _canonical_bytes(data: Any) -> bytes:
    """Stable JSON encoding: sorted keys, no whitespace, UTF-8."""
    return json.dumps(
        data, sort_keys=True, separators=(",", ":"), ensure_ascii=False
    ).encode("utf-8")


def _sha256_hex_digest(data: bytes) -> str:
    return "sha256:" + hashlib.sha256(data).hexdigest()


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

    package_apis = [
        SandboxPackageApi(
            name=p["name"],
            version=p["version"],
            # canonical placeholder; non-sandbox packages lack distinct
            # API surface metadata; sandbox-specific packages (e.g.,
            # alphafrog_finance) override this in their canonical registry.
            api_version="1.0",
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
    """Persist the ExecutionEnvironment snapshot for in-sandbox code to read.

    The file at <task_workspace>/runtime-environment.json is the canonical
    source user code (e.g., report()) consults to look up environmentId;
    the same ExecutionEnvironment is also surfaced on the HTTP
    execution_environment field for gateway presence-aware mapping.
    """
    os.makedirs(task_workspace, exist_ok=True)
    path = os.path.join(task_workspace, "runtime-environment.json")
    payload = json.dumps(
        env.model_dump(mode="json"), indent=2, sort_keys=True
    )
    with open(path, "w", encoding="utf-8") as fp:
        fp.write(payload)
    return path


__all__ = [
    "collect_runtime_environment",
    "write_runtime_environment_json",
]
