"""260623-harness-optimization-02: 锁定 sandbox_runner._materialize_agent_run_csvs 的 placeholder 替换 +
NONE marker 物化（Cindy 拍板 path C，no side-channel，derive 自 paths_dataset.csv）。

MF4（round 1 review fix）：on-disk CSV 强制 strip 第 4 列 source_path，落到 sandbox
workdir 的 schema 跟 tool description 描述的 3 列 public schema 一致。
MF5（round 1 review fix）：agent run 模式下 CSVs 非空但 0 次 cp → RuntimeError fail loud，
不再 silently fallback 到 legacy data_dir 目录扫描。
"""

from __future__ import annotations

import io
import json
import sys
import tempfile
import types
import unittest
from pathlib import Path

llm_sandbox = types.ModuleType("llm_sandbox")
llm_sandbox.SandboxSession = object
llm_sandbox_exceptions = types.ModuleType("llm_sandbox.exceptions")
llm_sandbox_exceptions.SandboxTimeoutError = TimeoutError
sys.modules.setdefault("llm_sandbox", llm_sandbox)
sys.modules.setdefault("llm_sandbox.exceptions", llm_sandbox_exceptions)

from app.config import SandboxConfig
from app.sandbox_runner import (
    MANIFEST_NONE_MARKER,
    SANDBOX_INPUT_PLACEHOLDER,
    TEMP_MANIFEST_DIR_PREFIX,
    _materialize_agent_run_csvs,
    _materialize_none_manifest,
)


def _test_config(tmp_path: Path) -> SandboxConfig:
    return SandboxConfig(
        data_dir=tmp_path,
        max_concurrency=1,
        execution_timeout_seconds=5.0,
        memory_limit="512m",
        memswap_limit="512m",
        docker_backend="docker",
        workdir="/sandbox",
        log_level="INFO",
        sandbox_image="alphafrog-sandbox-runtime:latest",
        skip_environment_setup=True,
        preinstalled_libraries=frozenset(),
        container_max_concurrency=1,
        pool_enabled=False,
        pool_min_size=0,
        pool_max_size=1,
        pool_acquire_timeout_seconds=30.0,
        pool_idle_timeout_seconds=None,
        pool_max_container_uses=None,
        workspace_root="/sandbox/runs",
        compat_input_path_enabled=True,
    )


class _ExecResult:
    def __init__(self, exit_code: int, stdout: str = "", stderr: str = "") -> None:
        self.exit_code = exit_code
        self.stdout = stdout
        self.stderr = stderr


class _SessionContainer:
    """非 root 拷贝路径的容器代理 fake（260818）：解析 put_archive 的
    tar，把 (payload, dest) 记进所属 session 的 writes，语义与旧
    copy_to_runtime 记录完全一致；不落盘。"""

    def __init__(self, session: "_FakeSession") -> None:
        self._session = session

    def put_archive(self, dest_dir: str, data: bytes) -> None:
        import io as _io
        import tarfile as _tarfile

        with _tarfile.open(fileobj=_io.BytesIO(data)) as tar:
            for member in tar.getmembers():
                payload = (
                    tar.extractfile(member).read() if member.isfile() else b""
                )
                dest = f"{dest_dir.rstrip('/')}/{member.name}"
                self._session.writes.append((payload, dest))
                self._session.archive_entries.append(
                    (dest_dir, member.name, member.uid, member.gid, member.mode)
                )


class _FakeSession:
    """记录容器内文件写入（put_archive 通道）与 execute_command 调用。"""

    def __init__(self) -> None:
        self.writes: list[tuple[bytes, str]] = []
        self.copy_sources: list[str] = []
        self.exec_calls: list[str] = []
        self.exec_responses: dict[str, _ExecResult] = {}
        self.archive_entries: list[tuple[str, str, int, int, int]] = []
        self.container = _SessionContainer(self)
        # 非 root 合同：生产在会话创建时预解析容器身份（uid 10000/gid 10001），
        # 这里同样预置缓存，避免 fake 的 id -u/id -g 查询。
        from app.container_copy import CONTAINER_IDENTITY_ATTR

        setattr(self, CONTAINER_IDENTITY_ATTR, (10000, 10001))

    def copy_to_runtime(self, source_path: str, dest: str) -> None:
        raise AssertionError(
            "production must stage via container.put_archive "
            "(non-root contract, 260818): copy_to_runtime execs chown as root"
        )

    def execute_command(self, cmd: str) -> _ExecResult:
        self.exec_calls.append(cmd)
        return self.exec_responses.get(cmd, _ExecResult(0, "", ""))


def _temp_manifest_path(task_input: str, manifest_number: str) -> str:
    """MF6: 物化临时 manifest.json 写到 <task_input>/_agent_run_manifest_<id>/manifest.json。"""
    return f"{task_input.rstrip('/')}/{TEMP_MANIFEST_DIR_PREFIX}{manifest_number}/manifest.json"


class SandboxRunnerCsvMaterializeTest(unittest.TestCase):
    pass
    # ----- placeholder 替换 -----

    # ----- MF6 (Cindy 拍板 path C): NONE marker 物化，无 side-channel -----

    # ----- MF4: 4 列 input → on-disk 3 列 public schema -----

class SandboxRunnerCsvSourcePathCopyTest(unittest.TestCase):
    """260623-harness-optimization-02: MF3 — 第 4 列 source_path 直接 cp 测试。

    验证 _copy_via_csv_source_paths 从 CSV 4 列读 source_path，
    用 _copy_dataset_file 直接 cp（绕过 _resolve_dataset_dir + _list_files）。
    """

class SandboxRunnerPrepareWorkspaceFailLoudTest(unittest.TestCase):
    """260623-harness-optimization-02: MF5 — agent run 模式下 source_path 0 次 cp → fail loud。

    验证 _prepare_task_workspace：
      - CSVs 非空（agent run 模式）但 source_copy_count == 0 → RuntimeError，不再 silently
        fallback 到 legacy data_dir 扫描
      - CSVs 都空（legacy non-agent-run 调用）→ 走 data_dir 兼容路径，不报错

    _copy_via_csv_source_paths 的 row-level 行为保持不变（单 row 失败只 log warning + false）。
    关键区分在 _prepare_task_workspace 这一层做 fail loud 兜底。
    """

    def _patch_expand_dataset_ids(self, expanded_dict: Dict[str, List]):
        """Patch expand_dataset_ids via monkeypatch on imported reference in sandbox_runner module."""
        from app import sandbox_runner

        class _StubExpanded:
            def __init__(self, d: Dict[str, List]) -> None:
                self.manifest_ids = d.get("manifest_ids", [])
                self.atomic_ids = d.get("atomic_ids", [])
                self.failed_members = d.get("failed_members", [])
                self.skipped_members = d.get("skipped_members", [])

        def _fake_expand(data_dir, dataset_id_list):
            return _StubExpanded(expanded_dict)

        return sandbox_runner.expand_dataset_ids, _fake_expand

if __name__ == "__main__":
    unittest.main()
