from __future__ import annotations

import csv
import io
import json
import logging
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
        "labels": SANDBOX_WORKER_LABELS,
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
        _prepare_task_workspace(
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

        _log_in_container(session, task_id, config, "script_start")
        t_run_start = time.monotonic()
        _smoke_check_loader_modules(session, config, task_id)
        result = session.run(code, libraries=install_libraries, timeout=timeout)
        timings["script_run_ms"] = int((time.monotonic() - t_run_start) * 1000)
        timings["env_load_ms"] = timings["workspace_prepare_ms"]
        timings["code_exec_ms"] = timings["script_run_ms"]
        _log_in_container(
            session,
            task_id,
            config,
            f"script_end exit_code={result.exit_code} stdout_len={len(result.stdout or '')} stderr_len={len(result.stderr or '')}",
        )

        t_artifact_start = time.monotonic()
        _flush_container_log(session, task_id, config)
        timings["artifact_collect_ms"] = int((time.monotonic() - t_artifact_start) * 1000)

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
    *,
    paths_dataset_csv: str | None = None,
    path_manifest_csv: str | None = None,
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
            paths_dataset_csv=paths_dataset_csv,
            path_manifest_csv=path_manifest_csv,
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
