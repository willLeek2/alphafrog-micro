from __future__ import annotations

import csv
import io
import json
import logging
import os
import re
import shlex
import tempfile
import time
from pathlib import Path
from typing import Any, Dict, List, Tuple

from llm_sandbox import SandboxSession
from llm_sandbox.exceptions import SandboxTimeoutError

from .config import SandboxConfig
from .dataset_manifest import expand_dataset_ids
from .resource_usage import SandboxResourceUsageCollector
from .runtime_environment import (
    ExecutionEnvironment,
    collect_runtime_environment,
    write_runtime_environment_json,
    write_runtime_environment_to_container,
)

logger = logging.getLogger(__name__)

APP_DIR = Path(__file__).resolve().parent
SANDBOX_LOADER_FILES = ("af_dataset_loader.py", "dataset_manifest.py")
DATASET_ID_PATTERN = re.compile(r"^[a-zA-Z0-9._-]+$")
SANDBOX_WORKER_LABELS = {
    "com.alphafrog.role": "python-sandbox-worker",
    "com.alphafrog.owner": "python-sandbox-service",
}

# 260623-harness-optimization-02: 与 Java 端 AgentRunDatasetCsvWriter.SANDBOX_INPUT_PLACEHOLDER 对齐。
# sandbox 端在写入 CSV 前必须替换为实际 task_input 路径。
SANDBOX_INPUT_PLACEHOLDER = "/__AF_INPUT__/"
# Java 端在写 path_manifest.csv 时如果 manifest 还没落盘，用此标记（Q7 拍板）。
# MF6 (Cindy 拍板 path C): sandbox 端从 paths_dataset.csv 反查 related_dataset_ids 的 from_ts_code,
# 物化临时 manifest.json 到 <task_input>/_agent_run_manifest_<id>/manifest.json,
# 然后把 CSV 行内的 NONE 替换为该 temp 路径。完全 derive 自两张现有 CSV，无 side-channel。
MANIFEST_NONE_MARKER = "NONE"
# MF6: NONE 行物化产物子目录前缀（与 run_id / agent_run_manifest_id 拼接成 sandbox 内绝对路径）。
TEMP_MANIFEST_DIR_PREFIX = "_agent_run_manifest_"


def _read_json_file(path: Path) -> Dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
        return value if isinstance(value, dict) else {}
    except Exception:
        return {}


def _dataset_public_metadata(source_path: str) -> Dict[str, Any]:
    source = Path(source_path)
    document: Dict[str, Any] = {}
    candidates = [source.with_suffix(".meta.json"), source.parent / "meta.json"]
    for candidate in candidates:
        if candidate.is_file():
            document = _read_json_file(candidate)
            if document:
                break
    columns = document.get("columns") if isinstance(document.get("columns"), list) else []
    if not columns and source.is_file() and source.suffix.lower() == ".csv":
        try:
            with source.open("r", encoding="utf-8", newline="") as handle:
                columns = next(csv.reader(handle), [])
        except Exception:
            columns = []
    try:
        byte_count = source.stat().st_size
    except OSError:
        byte_count = document.get("bytes")
    row_count = document.get("rowCount")
    return {
        "rowCount": row_count if isinstance(row_count, int) else None,
        "bytes": byte_count if isinstance(byte_count, int) else None,
        "columns": columns,
        "recommendedUsecols": document.get("recommendedUsecols") or columns,
        "recommendedDtype": document.get("recommendedDtype") or {},
        "readProfiles": document.get("readProfiles") or {},
        "metadataStatus": "complete" if isinstance(row_count, int) and isinstance(byte_count, int) and columns else "partial",
    }


def _build_agent_run_metadata_documents(
    paths_dataset_csv: str,
    path_manifest_csv: str,
) -> Tuple[Dict[str, Any], Dict[str, Any]]:
    datasets: Dict[str, Dict[str, Any]] = {}
    if paths_dataset_csv.strip():
        for row in csv.reader(io.StringIO(paths_dataset_csv)):
            if not row or row[0].strip() == "agent_run_dataset_id" or len(row) < 4:
                continue
            number = row[0].strip()
            if number:
                datasets[number] = _dataset_public_metadata(row[3].strip())

    manifests: Dict[str, Dict[str, Any]] = {}
    if path_manifest_csv.strip():
        for row in csv.reader(io.StringIO(path_manifest_csv)):
            if not row or row[0].strip() == "agent_run_manifest_id" or len(row) < 3:
                continue
            number = row[0].strip()
            member_numbers = [int(value) for value in row[2].split("#") if value.strip().isdigit()]
            member_meta = [datasets.get(str(value), {}) for value in member_numbers]
            complete_members = [value for value in member_meta if value]
            row_counts = [value.get("rowCount") for value in complete_members]
            byte_counts = [value.get("bytes") for value in complete_members]
            columns = complete_members[0].get("columns", []) if complete_members else []
            usecols = complete_members[0].get("recommendedUsecols", []) if complete_members else []
            dtypes = complete_members[0].get("recommendedDtype", {}) if complete_members else {}
            profiles = complete_members[0].get("readProfiles", {}) if complete_members else {}
            metadata_complete = (
                len(complete_members) == len(member_numbers)
                and all(isinstance(value, int) for value in row_counts)
                and all(isinstance(value, int) for value in byte_counts)
                and bool(columns)
            )
            manifests[number] = {
                "totalRowCount": sum(row_counts) if metadata_complete else None,
                "totalBytes": sum(byte_counts) if byte_counts and all(isinstance(value, int) for value in byte_counts) else None,
                "columns": columns,
                "recommendedUsecols": usecols,
                "recommendedDtype": dtypes,
                "readProfiles": profiles,
                "memberNumbers": member_numbers,
                "metadataStatus": "complete" if metadata_complete else "partial",
            }
    return (
        {"schema_version": "agent_run_dataset_meta_v1", "datasets": datasets},
        {"schema_version": "agent_run_manifest_meta_v1", "manifests": manifests},
    )


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


def _copy_text_to_runtime(
    session: SandboxSession,
    content: str,
    dest_path: str,
) -> None:
    """Copy generated text into the runtime via llm-sandbox's source-path API."""
    temp_path: Path | None = None
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", delete=False) as handle:
        handle.write(content)
        temp_path = Path(handle.name)
    try:
        _copy_dataset_file(session, temp_path, dest_path)
    finally:
        if temp_path is not None:
            temp_path.unlink(missing_ok=True)


def _atomic_copy_text_to_runtime(
    session: SandboxSession,
    content: str,
    dest_path: str,
) -> None:
    temp_dest = dest_path + ".tmp"
    _copy_text_to_runtime(session, content, temp_dest)
    _exec_checked(
        session,
        f"mv {shlex.quote(temp_dest)} {shlex.quote(dest_path)}",
        "atomic_metadata_rename",
    )


def _copy_runtime_loader_modules(
    session: SandboxSession,
    config: SandboxConfig,
) -> None:
    """Copy af_dataset_loader helpers into the sandbox execution container."""
    sandbox_dir = config.workdir.rstrip("/")
    _exec_checked(session, f"mkdir -p {sandbox_dir}", "create_sandbox_module_dir")
    for filename in SANDBOX_LOADER_FILES:
        source = APP_DIR / filename
        if not source.is_file():
            raise FileNotFoundError(f"sandbox loader module not found: {source}")
        _copy_dataset_file(session, source, f"{sandbox_dir}/{filename}")


def prepare_container_loader_modules(
    session: SandboxSession,
    config: SandboxConfig,
) -> None:
    """Copy static loader modules once per warm container.

    In the pooled path, concurrent tasks share the same container; copying the
    same files for every task is redundant and races on /sandbox/*. Copy them
    once at container warm-up time instead.
    """
    _copy_runtime_loader_modules(session, config)
    logger.info("CONTAINER_LOADER_MODULES_READY container=%s", get_session_container_id(session))


def _loader_smoke_check_command(config: SandboxConfig) -> str:
    """Build a shell command that smoke-imports af_dataset_loader in the sandbox."""
    workdir = config.workdir.rstrip("/")
    import_check = (
        "import sys; "
        f"sys.path.insert(0, {workdir!r}); "
        "from af_dataset_loader import load_manifest; "
        "print('sandbox_loader_ok')"
    )
    script = f"set -e\ncd {shlex.quote(workdir)}\npython -c {shlex.quote(import_check)}"
    return f"sh -lc {shlex.quote(script)}"


def _smoke_check_loader_modules(
    session: SandboxSession,
    config: SandboxConfig,
    task_id: str,
) -> None:
    """Verify copied loader modules are importable without rewriting user code."""
    _exec_checked(
        session,
        _loader_smoke_check_command(config),
        f"loader_smoke_import task={task_id}",
    )


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
    paths_dataset_csv: str | None = None,
    path_manifest_csv: str | None = None,
    *,
    copy_loader_modules: bool = True,
) -> str:
    """Create task-scoped workspace and copy datasets. Returns the task workspace path.

    260808-finance-methodspec-v5 work package D (codex (A) plan 2026-08-08 23:06):
    the container-global ``<workdir>/runtime-environment.json`` is written once
    per container by ``initialize_runtime_environment()`` and shared by every
    task in that container. Sitecustomize.py no longer overrides
    ``AF_RUNTIME_ENVIRONMENT_FILE`` per task because concurrent tasks would
    race on the same global sitecustomize.py (and the cleanup path deleted
    it under their feet). Dynamic-install safety is enforced at config
    validation time (container_max_concurrency == 1), so concurrent tasks
    cannot mutate the shared venv out from under each other.
    """
    task_workspace = f"{config.workspace_root}/{task_id}"
    task_input = f"{task_workspace}/input"

    # Create workspace
    _exec_checked(session, f"mkdir -p {task_input}", "create_task_workspace")
    _log_in_container(session, task_id, config, f"task_start workspace={task_workspace}")

    # Set up /sandbox/input compatibility symlink if enabled
    if config.compat_input_path_enabled:
        _exec_checked(session, f"rm -rf {config.workdir}/input", "remove_old_input")
        _exec_checked(session, f"ln -s {task_input} {config.workdir}/input", "create_input_symlink")

    # MF5 (260623-02 round 1 review fix): agent run 模式下，CSVs 非空表示 caller 显式
    # 选了 run-level dataset/manifest 路径，必须走 CSV source_path cp。如果 CSVs 给到了
    # 但 source_copy_count == 0，意味着 01 DatasetPersistedEvent 漏带 persistedPath 或文件
    # 不在磁盘上——这种 config 错误必须 fail loud，不能 silently 退回 legacy data_dir
    # 扫描（那会让 sandbox 重新看到 originalId，违反 run-level 抽象）。
    # legacy non-agent-run 调用：CSVs 都空 → 走 data_dir 兼容路径保持向后兼容。
    has_csv = bool((paths_dataset_csv or "").strip() or (path_manifest_csv or "").strip())
    source_copy_count, expected_copy_count, failed_rows = _copy_via_csv_source_paths(
        session,
        config,
        task_id,
        task_input,
        paths_dataset_csv or "",
        path_manifest_csv or "",
    )
    if has_csv and failed_rows:
        # MF-new-3 (260623-02 round 2 review fix): agent-run 模式下任何"应该被 cp 但没 cp"
        # 的非 NONE 行（空 source_path / copy 抛异常）都必须 fail loud，
        # 不能 silently 跳过让 sandbox 启动后才发现文件缺失（delayed Python load failure）。
        # 列出 failed row(s) 的 originalId / source_path / reason 便于 caller 定位。
        rendered = ", ".join(
            f"kind={r['kind']} original_id={r['original_id']} "
            f"source_path={r['source_path']!r} reason={r['reason']}"
            for r in failed_rows
        )
        raise RuntimeError(
            f"agent_run mode but {len(failed_rows)} row(s) failed to copy from CSVs: "
            f"{rendered}. paths_dataset_csv provided={bool((paths_dataset_csv or '').strip())} "
            f"path_manifest_csv provided={bool((path_manifest_csv or '').strip())}. "
            "Check that DatasetPersistedEvent carries persistedPath for all entries and "
            "every source file exists on disk."
        )
    if has_csv and source_copy_count == 0 and expected_copy_count == 0:
        # MF5 fail loud：CSVs 非空但没有任何"应当被 cp"的非 NONE 行（既无 source_path 也无 NONE 物化）。
        # 这种情况意味着 caller 给了空 CSVs（只有 header / 完全没数据行）——同样是 config 错误。
        raise RuntimeError(
            "agent_run mode but no source files were copied from CSVs: "
            f"paths_dataset_csv provided={bool((paths_dataset_csv or '').strip())} "
            f"path_manifest_csv provided={bool((path_manifest_csv or '').strip())}. "
            "Check that DatasetPersistedEvent carries persistedPath for all entries."
        )

    if source_copy_count == 0:
        # Legacy path: 用 data_dir 目录展开（无 CSV source_path 信息的旧请求兼容）
        expanded = expand_dataset_ids(config.data_dir, dataset_id_list)
        copy_ids: List[str] = []
        for manifest_id in expanded.manifest_ids:
            if manifest_id not in copy_ids:
                copy_ids.append(manifest_id)
        for atomic_id in expanded.atomic_ids:
            if atomic_id not in copy_ids:
                copy_ids.append(atomic_id)

        if expanded.failed_members or expanded.skipped_members:
            _log_in_container(
                session,
                task_id,
                config,
                "manifest_expand "
                f"manifests={len(expanded.manifest_ids)} "
                f"atomics={len(expanded.atomic_ids)} "
                f"failed={len(expanded.failed_members)} "
                f"skipped={len(expanded.skipped_members)}",
            )

        # Copy manifest directories and expanded atomic datasets into task workspace.
        for ds_id in copy_ids:
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

    if copy_loader_modules:
        _copy_runtime_loader_modules(session, config)
        _log_in_container(session, task_id, config, "sandbox_loader_modules_ready")

    # 260623-harness-optimization-02: 把 Java 端 AgentRunDatasetRegistry 生成的 run-level CSV 落到 workdir。
    # - paths_dataset.csv：caller 选中的 dataset 子集（sub-snapshot）
    # - path_manifest.csv：当前 run 全量 manifest（用于 sandbox 内 cross-ref）
    # sandbox 内 af_dataset_loader 用这两份 CSV 解析 agent_run_*_id → 实际路径。
    if paths_dataset_csv or path_manifest_csv:
        _materialize_agent_run_csvs(
            session,
            config,
            task_input,
            paths_dataset_csv or "",
            path_manifest_csv or "",
        )

    metrics_path = f"{task_workspace}/metrics/loader_metrics.jsonl"
    artifact_dir = f"{task_workspace}/artifacts"
    temporary_dir = f"{task_workspace}/tmp"
    sitecustomize = (
        "import os, sys\n"
        f"os.environ['AF_TASK_METRICS_PATH'] = {metrics_path!r}\n"
        f"os.environ['AF_TASK_WORKSPACE'] = {task_workspace!r}\n"
        f"os.environ['AF_TASK_ARTIFACT_DIR'] = {artifact_dir!r}\n"
        f"os.environ['AF_TASK_TMP_DIR'] = {temporary_dir!r}\n"
        # 260808-finance-methodspec-v5 work package D (codex (A) plan 2026-08-08
        # 23:06): do NOT override AF_RUNTIME_ENVIRONMENT_FILE here. The
        # container-creation env var (set by create_sandbox_session) points at
        # the global <workdir>/runtime-environment.json that
        # initialize_runtime_environment() wrote once per container. Each task
        # in the same container reads the SAME file; concurrency=1 is enforced
        # via validate_dynamic_install_safety so the venv state cannot drift.
        f"os.makedirs({artifact_dir!r}, exist_ok=True)\n"
        f"os.makedirs({temporary_dir!r}, exist_ok=True)\n"
        f"sys.path.insert(0, {config.workdir.rstrip('/')!r})\n"
        f"os.chdir({task_workspace!r})\n"
    )
    _copy_text_to_runtime(session, sitecustomize, f"{config.workdir.rstrip('/')}/sitecustomize.py")

    return task_workspace


def _copy_via_csv_source_paths(
    session: SandboxSession,
    config: SandboxConfig,
    task_id: str,
    task_input: str,
    paths_dataset_csv: str,
    path_manifest_csv: str,
) -> Tuple[int, int, List[Dict[str, str]]]:
    """MF3: 从 paths_dataset.csv / path_manifest.csv 第 4 列读 source_path，直接 cp 到 sandbox。

    输入 CSV schema（Java AgentRunDatasetCsvWriter 写）：
      paths_dataset.csv: agent_run_dataset_id, dataset_file_path, from_ts_code, source_path
      path_manifest.csv: agent_run_manifest_id, manifest_file_path, related_dataset_ids, source_path

    行为：
      - 第 4 列非空行 → 用 source_path 做 _copy_dataset_file（绕过 _resolve_dataset_dir + _list_files）
      - 目的路径 = 第 2 列 placeholder 替换为 task_input
      - sandbox path = `<task_input>/<originalId>/<sortKey>`，filename = sortKey
      - manifest 行统一由 _materialize_agent_run_csvs 物化为 run-level manifest，
        不在此 cp；真实 manifest JSON 内 members[].datasetId 是 shared/original id

    MF-new-3（260623-02 round 3 review fix，commit `6450c2a`）：agent-run 模式下任何
    "应该被 cp 但没 cp" 的非 NONE 行（空 source_path / copy 抛异常）都必须 fail loud，
    不能 silently 跳过让 sandbox 接着启动后才发现文件缺失（delayed Python load failure）。
    返回 failed_rows 列表，由调用方 _prepare_task_workspace 决定是否抛 RuntimeError。

    Returns:
        (count, expected_count, failed_rows)
        - count: 实际成功 copy 的文件数
        - expected_count: 应当被 copy 的行数（非 NONE 且 source_path 非空）
        - failed_rows: 每条 {"kind": "dataset", "original_id": str,
          "source_path": str, "reason": str}——dataset 行空 source_path 与 copy 异常计入；
          manifest 行统一由 _materialize_agent_run_csvs 根据 related_dataset_ids 物化
    """
    task_input_prefix = task_input.rstrip("/") + "/"
    count = 0
    expected_count = 0
    failed_rows: List[Dict[str, str]] = []

    def _copy_row(sandbox_path_field: str, source_path_field: str, kind: str, row_number: str) -> None:
        nonlocal count, expected_count, failed_rows
        if sandbox_path_field == MANIFEST_NONE_MARKER:
            # NONE 行走 _materialize_none_manifest，不在此 cp，也不算失败
            return
        if not source_path_field or not source_path_field.strip():
            # agent-run 模式下 CSVs 由 caller 显式提供 source_path，
            # 空 source_path 表示 caller 漏带 persistedPath —— 视为失败
            expected_count += 1
            failed_rows.append({
                "kind": kind,
                "original_id": row_number,
                "source_path": "",
                "reason": "empty_source_path",
            })
            logger.warning(
                "mf3_source_copy 空 source_path：kind=%s row=%s",
                kind, row_number,
            )
            return
        expected_count += 1
        dest = sandbox_path_field.replace(SANDBOX_INPUT_PLACEHOLDER, task_input_prefix)
        source = source_path_field.strip()
        # filename = basename(source)
        try:
            filename = source.rsplit("/", 1)[-1]
        except Exception:
            filename = dest.rsplit("/", 1)[-1]
        # 实际写入 dest（dest 已是绝对路径，含完整 filename）；sortKey == basename(source) 通常成立
        # 但 spec 保证 sandbox_path 的 basename 跟 source 一致；不一致时 fallback 到 dest 的 basename
        if filename and not dest.endswith(filename):
            dest = f"{dest.rsplit('/', 1)[0]}/{filename}"
        try:
            _copy_dataset_file(session, Path(source), dest)
            count += 1
            _log_in_container(
                session,
                task_id,
                config,
                f"mf3_source_copy kind={kind} row={row_number} src={source} dest={dest}",
            )
        except Exception as e:  # noqa: BLE001 — 失败原因记录到 failed_rows
            logger.warning(
                "mf3_source_copy 失败：kind=%s row=%s src=%s err=%s",
                kind, row_number, source, e,
            )
            failed_rows.append({
                "kind": kind,
                "original_id": row_number,
                "source_path": source,
                "reason": f"copy_failed:{type(e).__name__}",
            })

    # paths_dataset.csv 行
    if paths_dataset_csv.strip():
        reader = csv.reader(io.StringIO(paths_dataset_csv))
        for row in reader:
            if not row:
                continue
            if len(row) < 2:
                continue
            if row[0].strip() == "agent_run_dataset_id":
                continue  # header
            if len(row) < 4:
                continue
            _copy_row(row[1], row[3], "dataset", row[0])

    return count, expected_count, failed_rows


def _materialize_agent_run_csvs(
    session: SandboxSession,
    config: SandboxConfig,
    task_input: str,
    paths_dataset_csv: str,
    path_manifest_csv: str,
) -> None:
    """把 Java 端的 run-level CSV 物化到 sandbox workdir（Cindy 拍板 path C，260623-02 MF6）。

    输入 CSV 形态（来自 Java AgentRunDatasetCsvWriter，可能带第 4 列 source_path）：
      - paths_dataset.csv: agent_run_dataset_id, dataset_file_path, from_ts_code [, source_path]
        dataset_file_path 中含 /__AF_INPUT__/ placeholder
      - path_manifest.csv: agent_run_manifest_id, manifest_file_path, related_dataset_ids [, source_path]
        manifest_file_path 中含 /__AF_INPUT__/ placeholder 或 NONE marker（Q7 拍板）

    物化规则：
      1. paths_dataset.csv:
         - placeholder 替换 → 写到 {workdir}/paths_dataset.csv
         - 同时在内存里构建 (agent_run_dataset_id → from_ts_code) 映射，供 NONE 行反查
      2. path_manifest.csv:
         - 所有行都从 related_dataset_ids（run-level 编号 # 串）物化为 task-local
           manifest.json，不再直接暴露 persisted manifest_file_path。真实 manifest
           JSON 内 members[].datasetId 是 shared/original id，直接给 run-level loader
           会破坏 spec §4.2.2。
         - 在 dataset_by_number 映射里查 from_ts_code，构造最小 manifest schema
           （Cindy 拍板：manifestId / kind / memberCount / readyCount / failedCount / members），
           写到 <task_input>/_agent_run_manifest_<id>/manifest.json，
           再把 CSV 行内路径替换为该 temp 路径。
           找不到 related dataset number → member 标 status="broken" + errorCode +
           errorMessage，但 manifest 仍生成（fail loud, not fail silent）。

    MF4（260623-02 round 1 review fix）：sandbox 内 on-disk CSV 必须 strip 回 3 列 public
    schema（agent_run_*_id / *_file_path / related_dataset_ids 或 from_ts_code）。
    第 4 列 source_path 是 Java→sandbox request 内部 helper（host-side copy 用），落到
    sandbox on-disk 后会污染 tool description 描述的 schema，也会让 sandbox 内
    af_dataset_loader 的 csv.reader 取错列。本函数落盘时强制只写前 3 列。

    完全 derive 自两张现有 CSV，无 side-channel；CSV 落盘后 sandbox 内
    af_dataset_loader 看到的全是 run-level 抽象（agent_run_manifest_id, 临时路径）。
    """
    workdir = config.workdir.rstrip("/")
    task_input_prefix = task_input.rstrip("/") + "/"

    # 1. 解析 paths_dataset.csv，构建 (run-level number → from_ts_code) 映射
    #    多 ts_code（"A#B"）取第一个 segment；空 / 纯空白 → UNCERTAIN
    #    同时收集已 strip 后的 3 列 data rows，供落盘用（避免二次 parse）。
    dataset_by_number: Dict[str, str] = {}
    materialized_ds_lines: List[str] = [
        "agent_run_dataset_id,dataset_file_path,from_ts_code"
    ]
    if paths_dataset_csv.strip():
        reader = csv.reader(io.StringIO(paths_dataset_csv))
        for row in reader:
            if not row:
                continue
            if row[0].strip() == "agent_run_dataset_id":
                # header（容忍 4 列 header，第 4 列 source_path 落盘时丢弃）
                continue
            if len(row) < 3:
                logger.warning(
                    "agent_run paths_dataset.csv 行字段不足，skip: row=%r",
                    row,
                )
                continue
            number = row[0].strip()
            sandbox_path = row[1]
            raw_ts_code = row[2] or ""
            if not number:
                continue
            first_segment = raw_ts_code.split("#", 1)[0].strip()
            dataset_by_number[number] = first_segment if first_segment else "UNCERTAIN"
            # placeholder 替换 + strip 第 4 列 → 3 列 public schema
            materialized_path = sandbox_path.replace(
                SANDBOX_INPUT_PLACEHOLDER, task_input_prefix
            )
            materialized_ds_lines.append(
                ",".join([number, materialized_path, raw_ts_code])
            )

    # 2. 落盘 paths_dataset.csv（3 列 public schema）
    if len(materialized_ds_lines) > 1:  # 至少 1 行 data row
        _copy_text_to_runtime(
            session,
            "\n".join(materialized_ds_lines) + "\n",
            f"{workdir}/paths_dataset.csv",
        )

    # 3. path_manifest.csv 行级处理（所有行都物化为 run-level manifest）
    #    落盘 header 强制用 3 列 literal（不沿用 input header，避免 4 列污染）。
    materialized_mf_lines: List[str] = [
        "agent_run_manifest_id,manifest_file_path,related_dataset_ids"
    ]
    if path_manifest_csv.strip():
        reader = csv.reader(io.StringIO(path_manifest_csv))
        for row in reader:
            if not row:
                continue
            if row[0].strip() == "agent_run_manifest_id":
                # header（4 列 input 也跳过，落盘用 3 列 literal header）
                continue
            if len(row) < 3:
                logger.warning(
                    "agent_run path_manifest.csv 行字段不足，skip: row=%r",
                    row,
                )
                continue
            agent_run_manifest_id, _manifest_file_path_field, related = row[0], row[1], row[2]
            # MF7: 所有 manifest 行统一物化。真实 persisted manifest 的
            # members[].datasetId 是 original/shared id；run-level loader 需要的是 1/2/3。
            temp_path = _materialize_none_manifest(
                session,
                task_input_prefix,
                agent_run_manifest_id,
                related,
                dataset_by_number,
            )
            materialized_mf_lines.append(
                ",".join([agent_run_manifest_id, temp_path, related])
            )
    # 只有在至少有 1 行可解析的数据时才写 path_manifest.csv（header-only 没意义）。
    if len(materialized_mf_lines) > 1:  # 至少 1 行 data row
        _copy_text_to_runtime(
            session,
            "\n".join(materialized_mf_lines) + "\n",
            f"{workdir}/path_manifest.csv",
        )

    dataset_metadata, manifest_metadata = _build_agent_run_metadata_documents(
        paths_dataset_csv, path_manifest_csv
    )
    if dataset_metadata["datasets"]:
        _atomic_copy_text_to_runtime(
            session,
            json.dumps(dataset_metadata, ensure_ascii=False, separators=(",", ":")),
            f"{workdir}/paths_dataset_meta.json",
        )
    if manifest_metadata["manifests"]:
        _atomic_copy_text_to_runtime(
            session,
            json.dumps(manifest_metadata, ensure_ascii=False, separators=(",", ":")),
            f"{workdir}/path_manifest_meta.json",
        )


def _materialize_none_manifest(
    session: SandboxSession,
    task_input_prefix: str,
    manifest_number: str,
    related_dataset_ids: str,
    dataset_by_number: Dict[str, str],
) -> str:
    """Q7 + Cindy MF6 path C: 物化 NONE marker → 写临时 manifest.json 到 sandbox 内 task_input。

    字段来源：
      - related_dataset_ids 提供 run-level dataset number（#-split）
      - dataset_by_number 来自 paths_dataset.csv 反查，提供对应 from_ts_code

    Schema（Cindy 拍板）：
      {
        "manifestId": "agent-run-manifest-<id>",
        "kind": "agent_run_manifest",
        "memberCount": 2,
        "readyCount": 2,
        "failedCount": 0,
        "members": [
          {"tsCode": "000300.SH", "datasetId": "1", "status": "ready"},
          ...
        ]
      }

    找不到 related dataset number（paths_dataset.csv 中不存在该编号）→ member 标
    status="broken" + errorCode="MISSING_DATASET_NUMBER" + errorMessage，但 manifest 仍生成
    （fail loud: 不生成假 ready member，count 进 failedCount）。

    Returns:
        物化成功：sandbox 内绝对路径（``<task_input>/_agent_run_manifest_<id>/manifest.json``）
        物化失败（mkdir 失败 / JSON 失败 / copy_to_runtime 失败 / manifest_number 非数字）：返回
        ``MANIFEST_NONE_MARKER`` 原文，让 sandbox 内 af_dataset_loader 报明确错误。
    """
    safe_id = (manifest_number or "").strip()
    if not safe_id or not safe_id.isdigit():
        logger.warning(
            "agent_run NONE manifest_number 不是数字，跳过物化：manifest_number=%r",
            manifest_number,
        )
        return MANIFEST_NONE_MARKER

    # 解析 related_dataset_ids（#-split，过滤空段）
    related_numbers: List[str] = [
        n.strip() for n in (related_dataset_ids or "").split("#") if n and n.strip()
    ]

    members: List[Dict[str, Any]] = []
    ready_count = 0
    failed_count = 0
    for rel_num in related_numbers:
        ts_code = dataset_by_number.get(rel_num)
        if ts_code is None:
            # 找不到对应 dataset → broken member（Cindy 拍板：不要生成假 ready member）
            members.append({
                "tsCode": "UNCERTAIN",
                "datasetId": rel_num,
                "status": "broken",
                "errorCode": "MISSING_DATASET_NUMBER",
                "errorMessage": (
                    f"related_dataset_ids 引用了 paths_dataset.csv 中不存在的 "
                    f"agent_run_dataset_id={rel_num}"
                ),
            })
            failed_count += 1
        else:
            members.append({
                "tsCode": ts_code,
                "datasetId": rel_num,
                "status": "ready",
            })
            ready_count += 1

    manifest_payload = {
        "manifestId": f"agent-run-manifest-{safe_id}",
        "kind": "agent_run_manifest",
        "memberCount": len(members),
        "readyCount": ready_count,
        "failedCount": failed_count,
        "members": members,
    }

    # 写临时 manifest.json：先 mkdir（copy_to_runtime 不创建父目录），再 copy_to_runtime
    temp_dir = f"{task_input_prefix}{TEMP_MANIFEST_DIR_PREFIX}{safe_id}"
    temp_path = f"{temp_dir}/manifest.json"
    try:
        output = session.execute_command(f"mkdir -p {shlex.quote(temp_dir)}")
        if getattr(output, "exit_code", 0) != 0:
            stderr = getattr(output, "stderr", "") or ""
            logger.warning(
                "agent_run NONE manifest mkdir 失败：path=%s exit=%s stderr=%s",
                temp_dir, getattr(output, "exit_code", "?"), stderr,
            )
            return MANIFEST_NONE_MARKER
    except Exception as e:  # noqa: BLE001 — sandbox mkdir 容错
        logger.warning(
            "agent_run NONE manifest mkdir 异常：path=%s err=%s", temp_dir, e
        )
        return MANIFEST_NONE_MARKER

    try:
        manifest_json = json.dumps(manifest_payload, ensure_ascii=False)
    except (TypeError, ValueError) as e:
        logger.warning(
            "agent_run NONE manifest JSON 序列化失败：manifest_number=%s err=%s",
            safe_id, e,
        )
        return MANIFEST_NONE_MARKER

    try:
        _copy_text_to_runtime(session, manifest_json, temp_path)
    except Exception as e:  # noqa: BLE001 — sandbox 写入容错
        logger.warning(
            "agent_run NONE manifest copy_to_runtime 失败：path=%s err=%s",
            temp_path, e,
        )
        return MANIFEST_NONE_MARKER

    logger.info(
        "agent_run NONE manifest 物化成功：manifest_id=%s temp_path=%s ready=%s failed=%s",
        safe_id, temp_path, ready_count, failed_count,
    )
    return temp_path


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
        _exec_checked(
            session,
            "rm -f "
            f"{shlex.quote(config.workdir.rstrip('/') + '/sitecustomize.py')} "
            f"{shlex.quote(config.workdir.rstrip('/') + '/paths_dataset.csv')} "
            f"{shlex.quote(config.workdir.rstrip('/') + '/path_manifest.csv')} "
            f"{shlex.quote(config.workdir.rstrip('/') + '/paths_dataset_meta.json')} "
            f"{shlex.quote(config.workdir.rstrip('/') + '/path_manifest_meta.json')}",
            "cleanup_public_task_files",
        )
        return True
    except Exception as e:
        logger.warning("Workspace cleanup failed for task %s: %s", task_id, e)
        return False


def validate_dynamic_install_safety(config: SandboxConfig) -> None:
    """Spec §8 L1019 + codex (A) plan 2026-08-08 23:06 safety invariant.

    finance-methodspec-v5 enforces ``container_max_concurrency == 1`` for
    every config (regardless of skip_environment_setup) because every
    SandboxConfig writes a per-task bootstrap into the SHARED global file
    ``/sandbox/sitecustomize.py`` (AF_TASK_WORKSPACE / AF_TASK_ARTIFACT_DIR /
    AF_TASK_TMP_DIR / AF_TASK_METRICS_PATH) and deletes that same file on
    cleanup. Concurrent tasks would race on that file even without dynamic
    install — the bootstrap file is shared mutable state by construction.

    Dynamic install (skip_environment_setup=False) additionally mutates the
    shared venv via ``session.install()``, which compounds the race:
    ``PoolWorker.execution_environment`` is captured once at warm-up and
    never refreshed, so a second task in the same worker would read the
    baked environmentId while the container's actual venv had already been
    mutated by ``session.install()`` from the previous task.

    Both invariants collapse to the same rule: exactly one task per
    worker container at a time. Raise ``ConfigurationError`` (with a stable
    code) if the invariant is violated. Callers that mutate config
    dynamically (Nacos hot-reload, pool_min_size adjustment, etc.) MUST
    re-run this check before accepting the new config; failing closed
    prevents silent throughput drift that would corrupt environment
    identity under the surface.

    codex 2026-08-08 23:16 (bc11e841 item 2): the original plan only
    enforced this for skip_environment_setup=False; sitecustomize.py races
    even for preinstalled-only configs because the per-task AF_TASK_* env
    vars are still written into the same global file. This invariant now
    applies to ALL SandboxConfig instances.
    """
    if config.container_max_concurrency != 1:
        raise ConfigurationError(
            "CONTAINER_MAX_CONCURRENCY_REQUIRES_ONE: "
            f"container_max_concurrency={config.container_max_concurrency} "
            "is not allowed. SandboxConfig writes per-task bootstrap "
            "(AF_TASK_WORKSPACE / AF_TASK_ARTIFACT_DIR / AF_TASK_TMP_DIR / "
            "AF_TASK_METRICS_PATH) into the shared global file "
            "/sandbox/sitecustomize.py and deletes that same file on cleanup; "
            "concurrent tasks would race on the bootstrap. Dynamic install "
            "(skip_environment_setup=False) additionally mutates the shared "
            "venv via session.install(), which compounds the race. Both "
            "invariants collapse to: one task per worker container at a time."
        )


class ConfigurationError(RuntimeError):
    """Raised when SandboxConfig violates a finance-methodspec-v5 invariant."""


def create_sandbox_session(
    config: SandboxConfig,
    *,
    execution_timeout: float | None = None,
    memory_limit_bytes: int | None = None,
) -> SandboxSession:
    """Create and open one llm-sandbox session.

    The caller owns the returned session and must close it.
    """
    validate_dynamic_install_safety(config)
    effective_memory_limit: int | str = memory_limit_bytes or config.memory_limit
    runtime_configs = {
        "mem_limit": effective_memory_limit,
        "memswap_limit": effective_memory_limit if memory_limit_bytes else config.memswap_limit,
        "labels": SANDBOX_WORKER_LABELS,
        # 260808-finance-methodspec-v5 work package D: contract with package
        # B/C (ccqwen). The Python finance library reads environmentId from
        # the read-only task environment file; the file path is communicated
        # to user code via this env var, set at container creation so all
        # child processes (including session.run()) inherit it. The file
        # itself is written below in this function after session.open().
        "environment": [
            f"AF_RUNTIME_ENVIRONMENT_FILE={config.workdir.rstrip('/')}/runtime-environment.json",
        ],
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


def initialize_runtime_environment(
    config: SandboxConfig,
    session: SandboxSession,
    *,
    task_id: str | None = None,
) -> ExecutionEnvironment:
    """Collect runtime environment single-source and push the file INTO the container.

    Called once per container after create_sandbox_session(). The
    AF_RUNTIME_ENVIRONMENT_FILE env var was set at container creation to
    ``<workdir>/runtime-environment.json``; this function writes the file
    at that path via ``copy_to_runtime`` so user code (e.g. ccqwen's
    reporting library) reads it from inside the container. Failure to push
    the file into the container raises — the worker cannot become ready
    without an honest runtime environment file for user code to consult.

    Returns the ExecutionEnvironment instance so the caller can surface it
    on the HTTP execution_environment field; one ExecutionEnvironment
    drives both the container file and the wire field (single-source
    invariant).

    For non-pool mode, task_id is logged to aid ops correlation. For pool
    mode, task_id may be None because the same container serves many tasks;
    the environment is constant per container so the file is logically
    valid for any task in that container.

    Spec §8 L1019 + codex 2026-08-08 23:06 (A plan): the file path is the
    container-global ``<workdir>/runtime-environment.json`` set by
    ``create_sandbox_session``. Per-task sitecustomize overrides were
    removed because they were racy under pool reuse (concurrent tasks
    clobbered each other's bootstrap files).
    """
    container_id = get_session_container_id(session)
    env = collect_runtime_environment(container_id=container_id, session=session)
    container_env_path = os.path.join(
        config.workdir.rstrip("/"), "runtime-environment.json",
    )
    # Push into the execution container — this is the runtime-visible
    # source user code actually consults. Failure here MUST raise so the
    # worker is not considered ready; failing closed prevents report()
    # from reading a stale or missing environment file.
    write_runtime_environment_to_container(session, env, container_env_path)
    logger.info(
        "RUNTIME_ENVIRONMENT_READY container=%s task=%s environment_id=%s "
        "image_digest=%s library_set_digest=%s package_count=%s "
        "inventory_complete=%s container_path=%s",
        container_id, task_id or "-", env.environment_id, env.image_digest,
        env.library_set_digest, len(env.package_apis),
        env.inventory_complete, container_env_path,
    )
    # Ops-audit copy on the service host filesystem (best-effort: the
    # service host's <workdir>/ is not shared with the container, so this
    # is purely a debugging convenience and MUST NOT fail the init path).
    try:
        write_runtime_environment_json(config.workdir, env)
    except Exception as exc:
        logger.info(
            "RUNTIME_ENVIRONMENT_AUDIT_WRITE_FAILED container=%s error=%s",
            container_id, exc,
        )
    return env


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
    """Verify the warm container has a usable baked runtime.

    With skip_environment_setup=True on a fresh SandboxSession, llm-sandbox
    executes code with system `python`, not the venv path. Check that actual
    runner path first, then verify the baked venv compatibility path if present.
    """
    import_check = "import numpy, pandas, matplotlib, scipy; print('sandbox runtime ready')"
    script = f"""
set -e
python -c {shlex.quote(import_check)}
if [ -x {shlex.quote(config.workdir)}/.sandbox-venv/bin/python ]; then
  {shlex.quote(config.workdir)}/.sandbox-venv/bin/python -c {shlex.quote(import_check)}
fi
test -w {shlex.quote(config.workdir)}
"""
    smoke_cmd = f"sh -lc {shlex.quote(script)}"
    _exec_checked(session, smoke_cmd, f"ready_check container={container_id}")


def _read_runtime_text(session: SandboxSession, command: str) -> str:
    try:
        output = session.execute_command(command)
        return output.stdout or ""
    except Exception:
        return ""


def _read_runtime_size(session: SandboxSession, path: str) -> int:
    text = _read_runtime_text(
        session,
        f"if [ -e {shlex.quote(path)} ]; then "
        f"find {shlex.quote(path)} -type f -exec stat -c %s {{}} + 2>/dev/null | "
        "awk '{total += $1} END {print total + 0}'; else echo 0; fi",
    ).strip()
    try:
        return max(0, int(text.splitlines()[-1]))
    except (ValueError, IndexError):
        return 0


def _read_task_temporary_bytes(session: SandboxSession, task_workspace: str) -> int:
    quoted = shlex.quote(task_workspace)
    command = (
        f"find {quoted} -type f "
        f"! -path {shlex.quote(task_workspace + '/input/*')} "
        f"! -path {shlex.quote(task_workspace + '/metrics/*')} "
        f"! -path {shlex.quote(task_workspace + '/artifacts/*')} "
        f"! -name task.log -exec stat -c %s {{}} + 2>/dev/null | "
        "awk '{total += $1} END {print total + 0}'"
    )
    text = _read_runtime_text(session, command).strip()
    try:
        return max(0, int(text.splitlines()[-1]))
    except (ValueError, IndexError):
        return 0


def _container_oom_killed(container_id: str) -> bool:
    if not container_id or container_id == "unknown":
        return False
    client = None
    try:
        import docker

        client = docker.from_env()
        container = client.containers.get(container_id)
        container.reload()
        return bool((container.attrs.get("State") or {}).get("OOMKilled"))
    except Exception:
        return False
    finally:
        if client is not None:
            try:
                client.close()
            except Exception:
                pass


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
    paths_dataset_csv: str | None = None,
    path_manifest_csv: str | None = None,
    queue_wait_ms: int | None = None,
    container_id: str | None = None,
    pool_enabled: bool = True,
    prepare_loader_modules: bool = True,
    resource_class: str = "STANDARD",
    usage_sampling_interval_millis: int | None = None,
    execution_environment: ExecutionEnvironment | None = None,
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
    timings["queue_wait_ms"] = queue_wait_ms if queue_wait_ms is not None else 0
    result = None
    container_recycled = False
    recycle_reason: str | None = None
    actual_container_id = container_id or get_session_container_id(session)
    collector = SandboxResourceUsageCollector(
        resource_class,
        usage_sampling_interval_millis or config.usage_sampling_interval_millis,
    )
    collector.start(actual_container_id)
    workspace_created = False
    execution_error: Exception | None = None
    loader_metrics_jsonl = ""
    artifact_bytes_written = 0
    temporary_bytes_written = 0
    timed_out = False
    oom_killed = False
    exit_reason = "UNKNOWN"
    resource_usage = None
    # Spec §8 L1019 + Kimi rework 2026-08-08: when this task actually installs
    # non-preinstalled packages, re-collect the runtime environment after the
    # install so the task's HTTP execution_environment field reflects the
    # post-install state. Pool container reuse across tasks otherwise leaves
    # residual installs polluting the next task's environment identity if we
    # only sample at container warm-up time. Effective env falls back to the
    # caller-supplied baked env if re-collection fails or no install happened.
    post_install_environment: ExecutionEnvironment | None = None

    try:
        t_workspace_start = time.monotonic()
        workspace_created = True
        task_workspace = _prepare_task_workspace(
            session,
            task_id,
            config,
            dataset_id_list,
            files,
            paths_dataset_csv=paths_dataset_csv,
            path_manifest_csv=path_manifest_csv,
            copy_loader_modules=prepare_loader_modules,
        )
        timings["workspace_prepare_ms"] = int((time.monotonic() - t_workspace_start) * 1000)

        # 260808-finance-methodspec-v5 work package D: ExecutionEnvironment was
        # collected by initialize_runtime_environment() once per container and
        # pushed into the container-global <workdir>/runtime-environment.json.
        # All tasks in the same container read the same file (concurrent
        # tasks are forbidden via validate_dynamic_install_safety), so we do
        # NOT re-collect or re-write the file per task.
        #
        # Spec §8 L1019 (Kimi rework + codex (A) plan 2026-08-08 23:06):
        # when this task installs non-preinstalled packages, the dynamic install
        # path MUST be split into install() → re-collect → push updated file
        # into container → run(code, libraries=None). Re-collecting AFTER run()
        # is too late because report()/report_custom() already executed inside
        # the run and read the baked file; the recorded environmentId would
        # then disagree with the HTTP post-install field.
        #
        # codex 2026-08-08 23:06 (A plan): install/collect/copy 任一失败 MUST
        # raise before session.run (not silently fall back to baked env).
        # session.install() mutated the shared venv; the recorded envId must
        # reflect the actual container state or the task MUST NOT run.

        _log_in_container(session, task_id, config, "script_start")
        t_run_start = time.monotonic()
        _smoke_check_loader_modules(session, config, task_id)
        if install_libraries:
            # Phase 1: dynamic install via llm-sandbox's install(). Failure
            # raises; pool worker must close session and not reuse the
            # partially-mutated venv for the next task.
            try:
                t_install_start = time.monotonic()
                session.install(install_libraries)
                timings["install_ms"] = int(
                    (time.monotonic() - t_install_start) * 1000,
                )
            except Exception as install_exc:
                # Spec §8 L1019 + codex 2026-08-08 23:06 (A plan): session.install
                # already mutated the container's shared venv. If we silently
                # fall back, the next task in this worker inherits a partial
                # install set whose environmentId disagrees with what HTTP
                # records. Mark the container for recycling so the pool
                # scheduler retires this worker and the next task gets a
                # fresh baked container.
                container_recycled = True
                recycle_reason = "post_install_install_failed"
                logger.error(
                    "RUNTIME_ENVIRONMENT_POST_INSTALL_INSTALL_FAILED "
                    "task=%s libraries=%s error=%s",
                    task_id, install_libraries, install_exc,
                )
                raise
            # Phase 2: re-collect the actual post-install package set inside
            # the container and push it to the SAME container-global path
            # initialize_runtime_environment wrote (so user code reads the new
            # file via the AF_RUNTIME_ENVIRONMENT_FILE env var set at container
            # creation). Any failure (collect OR copy) raises; the pool worker
            # observes container_recycled=True and retires the worker so the
            # next task gets a fresh baked container.
            try:
                t_post_install_start = time.monotonic()
                post_install_environment = collect_runtime_environment(
                    container_id=actual_container_id, session=session,
                )
                container_env_path = os.path.join(
                    config.workdir.rstrip("/"), "runtime-environment.json",
                )
                write_runtime_environment_to_container(
                    session, post_install_environment, container_env_path,
                )
                timings["post_install_recollect_ms"] = int(
                    (time.monotonic() - t_post_install_start) * 1000,
                )
                logger.info(
                    "RUNTIME_ENVIRONMENT_POST_INSTALL_RECOLLECT task=%s "
                    "installed=%s environment_id=%s baked_environment_id=%s "
                    "container_path=%s elapsed_ms=%s",
                    task_id, install_libraries,
                    post_install_environment.environment_id,
                    execution_environment.environment_id
                    if execution_environment is not None else "-",
                    container_env_path,
                    timings["post_install_recollect_ms"],
                )
            except Exception as post_install_exc:
                # Spec §8 L1019 + codex 2026-08-08 23:06 (A plan): the install
                # above already changed the venv. If collect OR copy fails,
                # the recorded environmentId would not match the actual
                # container state; recycling is mandatory, not optional.
                container_recycled = True
                recycle_reason = "post_install_collect_or_write_failed"
                logger.error(
                    "RUNTIME_ENVIRONMENT_POST_INSTALL_COLLECT_OR_WRITE_FAILED "
                    "task=%s libraries=%s error=%s",
                    task_id, install_libraries, post_install_exc,
                )
                raise
            # Phase 3: run user code WITHOUT reinstalling — libraries are
            # already present from session.install() above. Reached only if
            # install + collect + write all succeeded; otherwise the exception
            # above propagates and session.run() is NOT called.
            result = session.run(code, libraries=None, timeout=timeout)
            # Spec §8 L1019 + codex (A) plan 2026-08-08 23:06: session.install()
            # mutated the shared venv in this container. Even on a successful
            # run the worker must NOT serve another task because that next task
            # would inherit the polluted venv while PoolWorker.execution_environment
            # still holds the baked snapshot — the recorded environmentId
            # would then disagree with the actual container state.
            # Mark the container for recycling unconditionally; the pool worker's
            # _on_job_done observes container_recycled=True and drains the worker
            # so the next task gets a fresh baked container.
            container_recycled = True
            recycle_reason = "post_install_pollution"
            logger.info(
                "RUNTIME_ENVIRONMENT_POST_INSTALL_POLLUTION task=%s "
                "installed=%s recycle_reason=%s",
                task_id, install_libraries, recycle_reason,
            )
        else:
            # No dynamic install: just run user code; libraries=[] is a no-op
            # in llm-sandbox and keeps the contract explicit.
            result = session.run(code, libraries=[], timeout=timeout)
        timings["script_run_ms"] = int((time.monotonic() - t_run_start) * 1000)
        timings["env_load_ms"] = timings["workspace_prepare_ms"]
        timings["code_exec_ms"] = timings["script_run_ms"]
        _log_in_container(
            session,
            task_id,
            config,
            f"script_end exit_code={result.exit_code} stdout_len={len(result.stdout or '')} stderr_len={len(result.stderr or '')}",
        )
        exit_reason = "SUCCEEDED" if result.exit_code == 0 else "NON_ZERO_EXIT"

        t_artifact_start = time.monotonic()
        _flush_container_log(session, task_id, config)
        timings["artifact_collect_ms"] = int((time.monotonic() - t_artifact_start) * 1000)

    except SandboxTimeoutError as e:
        execution_error = e
        timed_out = True
        exit_reason = "TIMEOUT"
        timings.setdefault("script_run_ms", int((time.monotonic() - t_run_start) * 1000) if "t_run_start" in locals() else 0)
        logger.error("Task %s timed out: %s", task_id, e)
        _flush_container_log(session, task_id, config)
        _log_in_container(session, task_id, config, "script_timeout recycle=timeout")
    except Exception as e:
        execution_error = e
        exit_reason = "EXECUTION_ERROR"
        logger.error("Task %s execution error: %s", task_id, e)
        _flush_container_log(session, task_id, config)
        _log_in_container(session, task_id, config, f"script_error error={type(e).__name__}")
    finally:
        task_workspace = f"{config.workspace_root}/{task_id}"
        loader_metrics_jsonl = _read_runtime_text(
            session,
            f"cat {shlex.quote(task_workspace + '/metrics/loader_metrics.jsonl')} 2>/dev/null || true",
        )
        artifact_bytes_written = _read_runtime_size(session, task_workspace + "/artifacts")
        temporary_bytes_written = _read_task_temporary_bytes(session, task_workspace)
        oom_killed = _container_oom_killed(actual_container_id)
        if oom_killed:
            exit_reason = "OOM_KILLED"
        t_cleanup_start = time.monotonic()
        cleanup_ok = True
        if workspace_created:
            cleanup_ok = _cleanup_task_workspace(session, task_id, config)
        timings["workspace_cleanup_ms"] = int((time.monotonic() - t_cleanup_start) * 1000)
        if not cleanup_ok:
            container_recycled = True
            # Preserve a more specific reason (post-install install/collect/write
            # failure) over the generic cleanup_failed; both still recycle, but
            # the operator can act on the root cause.
            if recycle_reason is None:
                recycle_reason = "cleanup_failed"
        resource_usage = collector.finish(
            container_id=actual_container_id,
            queue_wait_millis=int(timings.get("queue_wait_ms", 0)),
            prepare_millis=int(timings["workspace_prepare_ms"]) if "workspace_prepare_ms" in timings else None,
            execution_wall_millis=int(timings["script_run_ms"]) if "script_run_ms" in timings else None,
            cleanup_millis=int(timings["workspace_cleanup_ms"]),
            loader_metrics_jsonl=loader_metrics_jsonl,
            artifact_bytes_written=artifact_bytes_written,
            temporary_bytes_written=temporary_bytes_written,
            exit_reason=exit_reason,
            oom_killed=oom_killed,
            timed_out=timed_out,
        )
        timings["total_runner_ms"] = int((time.monotonic() - t0) * 1000)

    if execution_error is not None:
        setattr(execution_error, "resource_usage", resource_usage.model_dump(mode="json"))
        setattr(execution_error, "timings", timings)
        # Spec §8 L1019: on the exception path, fall back to the caller-
        # supplied baked env (post-install collection did not run or failed).
        if execution_environment is not None:
            setattr(
                execution_error,
                "execution_environment",
                execution_environment.model_dump(mode="json"),
            )
        raise execution_error

    # Spec §8 L1019: post-install re-collection overrides the baked env when
    # available, so the HTTP field reflects what the container actually has
    # after this task's dynamic installs.
    effective_execution_environment: ExecutionEnvironment | None = (
        post_install_environment if post_install_environment is not None
        else execution_environment
    )

    primary_mount = f"{config.workdir}/input/{dataset_id}"
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
        "resource_usage": resource_usage.model_dump(mode="json"),
        # 260808-finance-methodspec-v5 work package D: caller-supplied
        # ExecutionEnvironment instance is surfaced here on the HTTP
        # ExecuteResult; gateway presence-aware mapping then sets the proto
        # executionEnvironment parent when this is non-None. The same
        # instance is the workdir file's contents (single-source invariant).
        #
        # Spec §8 L1019 (Kimi rework 2026-08-08): when this task actually
        # installed non-preinstalled packages, post_install_environment was
        # re-collected after the install and overrides the caller-supplied
        # baked env so the HTTP field reflects post-install state. Otherwise
        # we keep the baked env (no install happened, no re-collection needed).
        "execution_environment": (
            effective_execution_environment.model_dump(mode="json")
            if effective_execution_environment is not None else None
        ),
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
    *,
    paths_dataset_csv: str | None = None,
    path_manifest_csv: str | None = None,
    queue_wait_ms: int = 0,
    resource_class: str = "STANDARD",
    memory_limit_bytes: int | None = None,
) -> dict:
    timeout = timeout_seconds or config.execution_timeout_seconds
    t0 = time.monotonic()
    t_create_start = time.monotonic()
    session = create_sandbox_session(
        config,
        execution_timeout=timeout,
        memory_limit_bytes=memory_limit_bytes,
    )
    container_create_ms = int((time.monotonic() - t_create_start) * 1000)
    container_id = get_session_container_id(session)
    # 260808-finance-methodspec-v5 work package D: single-source env collection.
    # The same ExecutionEnvironment instance drives the workdir file (written
    # here), the AF_RUNTIME_ENVIRONMENT_FILE env var (set at container
    # creation), and the HTTP execution_environment field (passed to
    # run_in_open_session).
    #
    # codex 2026-08-08 23:28 (msg 0d67cf11) init fail-closed lifecycle:
    # ``initialize_runtime_environment`` MUST run inside the same
    # ``try/finally session.close()`` as run_in_open_session, otherwise an init
    # collect/copy failure raises and the just-created session/container leaks.
    try:
        execution_environment = initialize_runtime_environment(
            config, session, task_id=task_id,
        )
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
            paths_dataset_csv=paths_dataset_csv,
            path_manifest_csv=path_manifest_csv,
            queue_wait_ms=queue_wait_ms,
            container_id=container_id,
            pool_enabled=False,
            resource_class=resource_class,
            execution_environment=execution_environment,
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
