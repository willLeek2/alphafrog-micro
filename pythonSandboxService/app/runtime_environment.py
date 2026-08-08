from __future__ import annotations

import hashlib
import json
import logging
import os
import shlex
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

def _canonical_bytes(data: Any) -> bytes:
    """Stable JSON encoding: sorted keys, no whitespace, UTF-8."""
    return json.dumps(
        data, sort_keys=True, separators=(",", ":"), ensure_ascii=False
    ).encode("utf-8")


def _sha256_hex_digest(data: bytes) -> str:
    return "sha256:" + hashlib.sha256(data).hexdigest()


SUPPORTED_PACKAGE_API_NAMES = frozenset({"alphafrog_finance"})


def _resolve_target_interpreter(session: Any) -> str:
    """Resolve the interpreter used by user code and dynamic installs."""
    if session is None:
        return "python"
    config = getattr(session, "config", None)
    skip_environment_setup = bool(getattr(config, "skip_environment_setup", False))
    using_existing_container = bool(getattr(session, "using_existing_container", False))
    python_executable = getattr(session, "python_executable_path", None)
    if (
        (not skip_environment_setup or using_existing_container)
        and isinstance(python_executable, str)
        and python_executable
    ):
        return python_executable
    return "python"


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
        interpreter = shlex.quote(_resolve_target_interpreter(session))
        command = (
            f"{interpreter} -m pip list --format=json "
            "--disable-pip-version-check 2>/dev/null"
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


def _probe_package_api_versions(
    session: Any, package_names: List[str]
) -> Tuple[dict[str, str], bool]:
    """Read package API versions inside the task runtime interpreter."""
    if session is None or not package_names:
        return {}, False
    interpreter = shlex.quote(_resolve_target_interpreter(session))
    names_json = json.dumps(sorted(set(package_names)), separators=(",", ":"))
    script = (
        "import importlib, json, sys\n"
        f"names = {names_json}\n"
        "result = {}\n"
        "for name in names:\n"
        "    try:\n"
        "        module = importlib.import_module(name)\n"
        "        value = getattr(module, '__api_version__', None)\n"
        "        result[name] = value if isinstance(value, str) and value else None\n"
        "    except Exception:\n"
        "        result[name] = None\n"
        "sys.stdout.write(json.dumps(result, sort_keys=True))\n"
    )
    command = f"{interpreter} -c {shlex.quote(script)}"
    try:
        output = session.execute_command(command)
        if getattr(output, "exit_code", 0) != 0:
            return {}, False
        text = (getattr(output, "stdout", "") or "").strip()
        payload = json.loads(text)
        if not isinstance(payload, dict):
            return {}, False
        versions: dict[str, str] = {}
        for name in package_names:
            raw = payload.get(name)
            if isinstance(raw, str) and raw:
                versions[name] = raw
        return versions, bool(versions) and len(versions) == len(set(package_names))
    except Exception as exc:
        logger.warning(
            "RUNTIME_ENVIRONMENT_PACKAGE_API_PROBE_FAILED error=%s", exc
        )
        return {}, False


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
      - inventory_complete: True iff package inventory and supported API probes succeed
    """
    image_digest = _inspect_container_image_digest(container_id)
    packages, inventory_complete = _read_installed_packages(session)
    packages_sorted = sorted(packages, key=lambda p: p["name"])
    installed_names = {package["name"] for package in packages_sorted}
    # 260808-finance-methodspec-v5 work package D (codex msg b92ef5bc):
    # the wire apiVersion can only be trusted when the supported distribution
    # is actually pip-installed in the same interpreter the probe runs in. A
    # process-time PYTHONPATH injection that lets importlib find the module
    # but leaves the pip metadata missing would otherwise fabricate a wire
    # fingerprint against a distribution the user never installed. Cross-check
    # required-supported-set against installed names; any missing required
    # distribution forces inventory_complete=False.
    required_supported = set(SUPPORTED_PACKAGE_API_NAMES)
    missing_required = sorted(required_supported - installed_names)
    if missing_required:
        inventory_complete = False
    # Probe only `supported ∩ installed`: required-but-not-installed names
    # cannot be probed successfully anyway and were already flagged above.
    # `package_apis` and the canonical packages below derive from the same
    # installed row + probe value (one row, one source of truth).
    probe_targets = sorted(required_supported & installed_names)
    api_versions, api_probe_complete = _probe_package_api_versions(
        session, probe_targets
    )
    inventory_complete = inventory_complete and api_probe_complete

    canonical_packages = []
    for package in packages_sorted:
        item = {"name": package["name"], "version": package["version"]}
        api_version = api_versions.get(package["name"])
        if api_version:
            item["apiVersion"] = api_version
        canonical_packages.append(item)
    library_set_digest = _sha256_hex_digest(_canonical_bytes(canonical_packages))

    package_apis = [
        SandboxPackageApi(
            name=package["name"],
            version=package["version"],
            api_version=api_versions[package["name"]],
        )
        for package in packages_sorted
        if package["name"] in api_versions
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
