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


class _FakeSession:
    """记录 copy_to_runtime / execute_command 调用，模拟 sandbox 写入。"""

    def __init__(self) -> None:
        self.writes: list[tuple[bytes, str]] = []
        self.exec_calls: list[str] = []
        self.exec_responses: dict[str, _ExecResult] = {}

    def copy_to_runtime(self, payload: bytes, dest: str) -> None:
        self.writes.append((payload, dest))

    def execute_command(self, cmd: str) -> _ExecResult:
        self.exec_calls.append(cmd)
        return self.exec_responses.get(cmd, _ExecResult(0, "", ""))


def _temp_manifest_path(task_input: str, manifest_number: str) -> str:
    """MF6: 物化临时 manifest.json 写到 <task_input>/_agent_run_manifest_<id>/manifest.json。"""
    return f"{task_input.rstrip('/')}/{TEMP_MANIFEST_DIR_PREFIX}{manifest_number}/manifest.json"


class SandboxRunnerCsvMaterializeTest(unittest.TestCase):
    # ----- placeholder 替换 -----

    def test_paths_dataset_csv_placeholder_substitution(self) -> None:
        config = _test_config(Path("/tmp/none"))
        session = _FakeSession()
        ds_csv = (
            "agent_run_dataset_id,dataset_file_path,from_ts_code\n"
            "1,/__AF_INPUT__/ds-a/a.csv,000300.SH\n"
            "2,/__AF_INPUT__/ds-b/b.csv,000300.SH#510300.SH\n"
        )
        _materialize_agent_run_csvs(session, config, "/sandbox/runs/task-1/input", ds_csv, "")
        paths_writes = [w for w in session.writes if w[1].endswith("paths_dataset.csv")]
        manifest_writes = [w for w in session.writes if w[1].endswith("path_manifest.csv")]
        self.assertEqual(len(paths_writes), 1, "exactly one paths_dataset.csv write")
        self.assertEqual(manifest_writes, [], "no path_manifest.csv when input empty")
        content = paths_writes[0][0].decode("utf-8")
        self.assertIn("/sandbox/runs/task-1/input/ds-a/a.csv", content)
        self.assertIn("/sandbox/runs/task-mf6/input/ds-b/b.csv".replace("task-mf6", "task-1"), content)
        self.assertNotIn(SANDBOX_INPUT_PLACEHOLDER, content)

    def test_path_manifest_csv_placeholder_substitution(self) -> None:
        # MF2 后 related_dataset_ids 是 run-level number 列表（# 拼接）
        config = _test_config(Path("/tmp/none"))
        session = _FakeSession()
        mf_csv = (
            "agent_run_manifest_id,manifest_file_path,related_dataset_ids\n"
            "1,/__AF_INPUT__/m-x/manifest.json,1#2\n"
        )
        _materialize_agent_run_csvs(session, config, "/sandbox/runs/task-1/input", "", mf_csv)
        manifest_writes = [w for w in session.writes if w[1].endswith("path_manifest.csv")]
        self.assertEqual(len(manifest_writes), 1)
        content = manifest_writes[0][0].decode("utf-8")
        self.assertIn("/sandbox/runs/task-1/input/m-x/manifest.json", content)
        self.assertIn("1#2", content)
        self.assertNotIn(SANDBOX_INPUT_PLACEHOLDER, content)

    def test_combined_csvs_in_one_call(self) -> None:
        tmp = Path("/tmp/_af_csv_combined")
        tmp.mkdir(parents=True, exist_ok=True)
        try:
            config = _test_config(tmp)
            session = _FakeSession()
            ds_csv = (
                "agent_run_dataset_id,dataset_file_path,from_ts_code\n"
                "1,/__AF_INPUT__/ds-a/a.csv,000300.SH\n"
            )
            mf_csv = (
                "agent_run_manifest_id,manifest_file_path,related_dataset_ids\n"
                "1,/__AF_INPUT__/m-x/manifest.json,1\n"
            )
            _materialize_agent_run_csvs(
                session, config, "/sandbox/runs/task-x/input", ds_csv, mf_csv
            )
            ds_writes = [w for w in session.writes if w[1].endswith("paths_dataset.csv")]
            mf_writes = [w for w in session.writes if w[1].endswith("path_manifest.csv")]
            self.assertEqual(len(ds_writes), 1)
            self.assertEqual(len(mf_writes), 1)
        finally:
            import shutil
            shutil.rmtree(tmp, ignore_errors=True)

    # ----- MF6 (Cindy 拍板 path C): NONE marker 物化，无 side-channel -----

    def test_none_marker_materializes_temp_manifest_with_cydny_schema(self) -> None:
        """MF6: NONE 行 → 物化临时 manifest.json，使用最小 schema。
        字段来源：related_dataset_ids（run-level numbers）→ paths_dataset.csv from_ts_code。
        """
        config = _test_config(Path("/tmp/none"))
        session = _FakeSession()
        ds_csv = (
            "agent_run_dataset_id,dataset_file_path,from_ts_code\n"
            "1,/__AF_INPUT__/ds-a/a.csv,000300.SH\n"
            "2,/__AF_INPUT__/ds-b/b.csv,000002.SZ\n"
        )
        mf_csv = (
            "agent_run_manifest_id,manifest_file_path,related_dataset_ids\n"
            f"1,{MANIFEST_NONE_MARKER},1#2\n"
        )
        _materialize_agent_run_csvs(
            session, config, "/sandbox/runs/task-mf6/input", ds_csv, mf_csv
        )
        expected_temp_path = _temp_manifest_path("/sandbox/runs/task-mf6/input", "1")
        temp_writes = [w for w in session.writes if w[1] == expected_temp_path]
        self.assertEqual(len(temp_writes), 1, "expected exactly one NONE → temp manifest.json")
        payload = json.loads(temp_writes[0][0].decode("utf-8"))
        # Cindy 拍板的 schema 字段
        self.assertEqual(payload["manifestId"], "agent-run-manifest-1")
        self.assertEqual(payload["kind"], "agent_run_manifest")
        self.assertEqual(payload["memberCount"], 2)
        self.assertEqual(payload["readyCount"], 2)
        self.assertEqual(payload["failedCount"], 0)
        # members 顺序按 related_dataset_ids 切分顺序
        self.assertEqual(len(payload["members"]), 2)
        self.assertEqual(payload["members"][0]["tsCode"], "000300.SH")
        self.assertEqual(payload["members"][0]["datasetId"], "1")
        self.assertEqual(payload["members"][0]["status"], "ready")
        self.assertEqual(payload["members"][1]["tsCode"], "000002.SZ")
        self.assertEqual(payload["members"][1]["datasetId"], "2")
        self.assertEqual(payload["members"][1]["status"], "ready")

    def test_none_marker_replaces_none_in_csv(self) -> None:
        """MF6: 物化后 path_manifest.csv 行内 NONE marker 应被临时路径替换。"""
        config = _test_config(Path("/tmp/none"))
        session = _FakeSession()
        ds_csv = (
            "agent_run_dataset_id,dataset_file_path,from_ts_code\n"
            "1,/__AF_INPUT__/ds-a/a.csv,000300.SH\n"
        )
        mf_csv = (
            "agent_run_manifest_id,manifest_file_path,related_dataset_ids\n"
            f"1,{MANIFEST_NONE_MARKER},1\n"
            "2,/__AF_INPUT__/m-real/manifest.json,1\n"
        )
        _materialize_agent_run_csvs(
            session, config, "/sandbox/runs/task-mf6/input", ds_csv, mf_csv
        )
        manifest_writes = [w for w in session.writes if w[1].endswith("path_manifest.csv")]
        self.assertEqual(len(manifest_writes), 1)
        content = manifest_writes[0][0].decode("utf-8")
        # NONE 被替换为 temp 路径
        self.assertNotIn(MANIFEST_NONE_MARKER, content,
                         "NONE marker should be replaced after materialization")
        expected_temp_path = _temp_manifest_path("/sandbox/runs/task-mf6/input", "1")
        self.assertIn(expected_temp_path, content)
        # 真实 manifest 行保留
        self.assertIn("/sandbox/runs/task-mf6/input/m-real/manifest.json", content)
        # 2 行数据
        data_lines = [l for l in content.splitlines() if l and not l.startswith("agent_run_manifest_id")]
        self.assertEqual(2, len(data_lines), "expected 2 data rows in materialized csv")

    def test_none_marker_broken_member_when_dataset_not_in_paths_dataset(self) -> None:
        """MF6: related_dataset_ids 引用 paths_dataset.csv 不存在的编号 → member 标 broken。

        不要生成假 ready member；count 进 failedCount（fail loud, not fail silent）。
        """
        config = _test_config(Path("/tmp/none"))
        session = _FakeSession()
        ds_csv = (
            "agent_run_dataset_id,dataset_file_path,from_ts_code\n"
            "1,/__AF_INPUT__/ds-a/a.csv,000300.SH\n"
        )
        mf_csv = (
            "agent_run_manifest_id,manifest_file_path,related_dataset_ids\n"
            f"1,{MANIFEST_NONE_MARKER},1#99\n"
        )
        _materialize_agent_run_csvs(
            session, config, "/sandbox/runs/task-mf6/input", ds_csv, mf_csv
        )
        expected_temp_path = _temp_manifest_path("/sandbox/runs/task-mf6/input", "1")
        temp_writes = [w for w in session.writes if w[1] == expected_temp_path]
        self.assertEqual(len(temp_writes), 1)
        payload = json.loads(temp_writes[0][0].decode("utf-8"))
        # memberCount = 2, readyCount = 1 (dataset 1 找到), failedCount = 1 (dataset 99 找不到)
        self.assertEqual(payload["memberCount"], 2)
        self.assertEqual(payload["readyCount"], 1)
        self.assertEqual(payload["failedCount"], 1)
        self.assertEqual(len(payload["members"]), 2)
        self.assertEqual(payload["members"][0]["datasetId"], "1")
        self.assertEqual(payload["members"][0]["status"], "ready")
        self.assertEqual(payload["members"][1]["datasetId"], "99")
        self.assertEqual(payload["members"][1]["status"], "broken")
        self.assertEqual(payload["members"][1]["errorCode"], "MISSING_DATASET_NUMBER")
        self.assertIn("99", payload["members"][1]["errorMessage"])
        # 找不到的 member tsCode 兜底 UNCERTAIN（不是凭空捏造）
        self.assertEqual(payload["members"][1]["tsCode"], "UNCERTAIN")

    def test_none_marker_empty_related_dataset_ids(self) -> None:
        """MF6: related_dataset_ids 为空 → members = [], counts 全 0。"""
        config = _test_config(Path("/tmp/none"))
        session = _FakeSession()
        ds_csv = (
            "agent_run_dataset_id,dataset_file_path,from_ts_code\n"
            "1,/__AF_INPUT__/ds-a/a.csv,000300.SH\n"
        )
        mf_csv = (
            "agent_run_manifest_id,manifest_file_path,related_dataset_ids\n"
            f"1,{MANIFEST_NONE_MARKER},\n"  # empty related
        )
        _materialize_agent_run_csvs(
            session, config, "/sandbox/runs/task-mf6/input", ds_csv, mf_csv
        )
        expected_temp_path = _temp_manifest_path("/sandbox/runs/task-mf6/input", "1")
        temp_writes = [w for w in session.writes if w[1] == expected_temp_path]
        self.assertEqual(len(temp_writes), 1)
        payload = json.loads(temp_writes[0][0].decode("utf-8"))
        self.assertEqual(payload["memberCount"], 0)
        self.assertEqual(payload["readyCount"], 0)
        self.assertEqual(payload["failedCount"], 0)
        self.assertEqual(payload["members"], [])

    def test_none_marker_no_paths_dataset_csv(self) -> None:
        """MF6: paths_dataset_csv 为空但 path_manifest 有 NONE 行 → 全部 member broken。"""
        config = _test_config(Path("/tmp/none"))
        session = _FakeSession()
        mf_csv = (
            "agent_run_manifest_id,manifest_file_path,related_dataset_ids\n"
            f"1,{MANIFEST_NONE_MARKER},1#2\n"
        )
        _materialize_agent_run_csvs(
            session, config, "/sandbox/runs/task-mf6/input", "", mf_csv
        )
        expected_temp_path = _temp_manifest_path("/sandbox/runs/task-mf6/input", "1")
        temp_writes = [w for w in session.writes if w[1] == expected_temp_path]
        self.assertEqual(len(temp_writes), 1)
        payload = json.loads(temp_writes[0][0].decode("utf-8"))
        # 找不到任何 related dataset → 全部 broken
        self.assertEqual(payload["memberCount"], 2)
        self.assertEqual(payload["readyCount"], 0)
        self.assertEqual(payload["failedCount"], 2)
        for m in payload["members"]:
            self.assertEqual(m["status"], "broken")
            self.assertEqual(m["errorCode"], "MISSING_DATASET_NUMBER")
            self.assertEqual(m["tsCode"], "UNCERTAIN")

    def test_none_marker_first_segment_of_multi_ts_code(self) -> None:
        """MF6: from_ts_code 多段（A#B）取第一个 segment 作为 member tsCode。"""
        config = _test_config(Path("/tmp/none"))
        session = _FakeSession()
        ds_csv = (
            "agent_run_dataset_id,dataset_file_path,from_ts_code\n"
            "1,/__AF_INPUT__/ds-a/a.csv,000300.SH#510300.SH\n"
        )
        mf_csv = (
            "agent_run_manifest_id,manifest_file_path,related_dataset_ids\n"
            f"1,{MANIFEST_NONE_MARKER},1\n"
        )
        _materialize_agent_run_csvs(
            session, config, "/sandbox/runs/task-mf6/input", ds_csv, mf_csv
        )
        expected_temp_path = _temp_manifest_path("/sandbox/runs/task-mf6/input", "1")
        payload = json.loads(
            [w for w in session.writes if w[1] == expected_temp_path][0][0].decode("utf-8")
        )
        self.assertEqual(payload["members"][0]["tsCode"], "000300.SH")
        self.assertEqual(payload["members"][0]["status"], "ready")

    def test_none_marker_uncertain_ts_code_propagates(self) -> None:
        """MF6: from_ts_code = UNCERTAIN 时 schema 仍稳定（member tsCode = UNCERTAIN）。"""
        config = _test_config(Path("/tmp/none"))
        session = _FakeSession()
        ds_csv = (
            "agent_run_dataset_id,dataset_file_path,from_ts_code\n"
            "1,/__AF_INPUT__/ds-a/a.csv,UNCERTAIN\n"
        )
        mf_csv = (
            "agent_run_manifest_id,manifest_file_path,related_dataset_ids\n"
            f"1,{MANIFEST_NONE_MARKER},1\n"
        )
        _materialize_agent_run_csvs(
            session, config, "/sandbox/runs/task-mf6/input", ds_csv, mf_csv
        )
        expected_temp_path = _temp_manifest_path("/sandbox/runs/task-mf6/input", "1")
        payload = json.loads(
            [w for w in session.writes if w[1] == expected_temp_path][0][0].decode("utf-8")
        )
        self.assertEqual(payload["members"][0]["tsCode"], "UNCERTAIN")
        self.assertEqual(payload["members"][0]["status"], "ready")
        self.assertEqual(payload["readyCount"], 1)

    def test_none_marker_writes_to_correct_temp_path(self) -> None:
        """MF6: temp manifest.json 写到 <task_input>/_agent_run_manifest_<id>/manifest.json。

        与 §4.2 公开 path_manifest.csv schema 保持一致（不暴露 originalId / persistedPath 给 LLM）。
        """
        config = _test_config(Path("/tmp/none"))
        session = _FakeSession()
        ds_csv = "agent_run_dataset_id,dataset_file_path,from_ts_code\n1,/__AF_INPUT__/ds-a/a.csv,000300.SH\n"
        mf_csv = (
            "agent_run_manifest_id,manifest_file_path,related_dataset_ids\n"
            f"1,{MANIFEST_NONE_MARKER},1\n"
        )
        _materialize_agent_run_csvs(
            session, config, "/sandbox/runs/task-mf6/input", ds_csv, mf_csv
        )
        # 不写到 /tmp/，写到 task_input 子目录
        tmp_writes = [w for w in session.writes if w[1].startswith("/tmp/")]
        self.assertEqual(tmp_writes, [], "no writes under /tmp/ (sandbox-relative only)")
        expected = "/sandbox/runs/task-mf6/input/_agent_run_manifest_1/manifest.json"
        matching = [w for w in session.writes if w[1] == expected]
        self.assertEqual(len(matching), 1, "temp manifest.json under <task_input>/_agent_run_manifest_1/")

    def test_none_marker_mkdir_called_before_copy(self) -> None:
        """MF6: 物化前先 mkdir 临时目录（copy_to_runtime 不创建父目录）。"""
        config = _test_config(Path("/tmp/none"))
        session = _FakeSession()
        ds_csv = "agent_run_dataset_id,dataset_file_path,from_ts_code\n1,/__AF_INPUT__/ds-a/a.csv,000300.SH\n"
        mf_csv = (
            "agent_run_manifest_id,manifest_file_path,related_dataset_ids\n"
            f"1,{MANIFEST_NONE_MARKER},1\n"
        )
        _materialize_agent_run_csvs(
            session, config, "/sandbox/runs/task-mf6/input", ds_csv, mf_csv
        )
        # 验证 mkdir -p 被调用
        mkdir_calls = [c for c in session.exec_calls if "mkdir -p" in c]
        self.assertGreaterEqual(len(mkdir_calls), 1, "mkdir -p was called at least once")
        # 物化成功的条件 = mkdir 成功 + copy_to_runtime 成功
        expected_temp_path = _temp_manifest_path("/sandbox/runs/task-mf6/input", "1")
        temp_writes = [w for w in session.writes if w[1] == expected_temp_path]
        self.assertEqual(len(temp_writes), 1, "temp manifest.json was written")

    def test_none_marker_preserved_when_mkdir_fails(self) -> None:
        """MF6: mkdir 失败 → 物化失败，CSV 行保留 NONE 原文（让 sandbox loader 报明确错误）。"""
        config = _test_config(Path("/tmp/none"))
        session = _FakeSession()
        expected_dir = "/sandbox/runs/task-mf6/input/_agent_run_manifest_1"
        # shlex.quote wraps path in single quotes (no special chars → just adds quotes)
        import shlex as _shlex
        session.exec_responses[f"mkdir -p {_shlex.quote(expected_dir)}"] = _ExecResult(1, "", "permission denied")
        ds_csv = "agent_run_dataset_id,dataset_file_path,from_ts_code\n1,/__AF_INPUT__/ds-a/a.csv,000300.SH\n"
        mf_csv = (
            "agent_run_manifest_id,manifest_file_path,related_dataset_ids\n"
            f"1,{MANIFEST_NONE_MARKER},1\n"
        )
        _materialize_agent_run_csvs(
            session, config, "/sandbox/runs/task-mf6/input", ds_csv, mf_csv
        )
        # 物化失败 → CSV 行保留 NONE
        manifest_writes = [w for w in session.writes if w[1].endswith("path_manifest.csv")]
        self.assertEqual(len(manifest_writes), 1)
        content = manifest_writes[0][0].decode("utf-8")
        self.assertIn(f"1,{MANIFEST_NONE_MARKER},1", content, "NONE marker preserved when mkdir fails")
        # 没有写 temp manifest.json
        expected_temp_path = _temp_manifest_path("/sandbox/runs/task-mf6/input", "1")
        temp_writes = [w for w in session.writes if w[1] == expected_temp_path]
        self.assertEqual(temp_writes, [])

    def test_none_marker_preserved_when_manifest_number_not_digit(self) -> None:
        """MF6: manifest_number 非数字 → 物化返回 NONE 原文（path traversal 防护）。"""
        config = _test_config(Path("/tmp/none"))
        session = _FakeSession()
        ds_csv = "agent_run_dataset_id,dataset_file_path,from_ts_code\n1,/__AF_INPUT__/ds-a/a.csv,000300.SH\n"
        # 这一行（"1; rm -rf /" 整段没引号）会被 csv.reader 解析为 3 个 cell:
        # number="1; rm -rf /", path="NONE", related="1"（";" 不是 csv 分隔符）
        mf_csv = (
            "agent_run_manifest_id,manifest_file_path,related_dataset_ids\n"
            "1; rm -rf /,NONE,1\n"
        )
        _materialize_agent_run_csvs(
            session, config, "/sandbox/runs/task-mf6/input", ds_csv, mf_csv
        )
        # 物化失败 → CSV 行保留 NONE 原文
        manifest_writes = [w for w in session.writes if w[1].endswith("path_manifest.csv")]
        self.assertEqual(len(manifest_writes), 1)
        content = manifest_writes[0][0].decode("utf-8")
        self.assertIn(f"1; rm -rf /,{MANIFEST_NONE_MARKER},1", content,
                      "NONE marker preserved when manifest_number is non-digit")
        # 关键：没有把 "1; rm -rf /" 当作路径写到 sandbox 内
        # path_manifest.csv 内容里出现 "1; rm -rf /" 是 OK 的（CSV 数据），
        # 但 dest path 不应出现 "rm -rf"（那意味着我们把恶意字符拼进了 sandbox 路径）
        dangerous_dest_writes = [
            w for w in session.writes
            if "rm -rf" in w[1]
        ]
        self.assertEqual(dangerous_dest_writes, [],
                         "no write dest path should contain 'rm -rf' (path traversal guard)")
        # 没有写 temp manifest.json 到非数字目录
        temp_writes = [w for w in session.writes
                       if "_agent_run_manifest_" in w[1] and w[1].endswith("manifest.json")]
        self.assertEqual(temp_writes, [],
                         "no temp manifest when manifest_number is non-digit")

    def test_none_marker_no_persist_path_leaked_to_csv(self) -> None:
        """MF6: path_manifest.csv 行内不再含 NONE（已替换为 temp 路径），但也不能含
        original manifest_id / persistedPath（Cindy: 不暴露 01 contract 内部细节）。"""
        config = _test_config(Path("/tmp/none"))
        session = _FakeSession()
        ds_csv = "agent_run_dataset_id,dataset_file_path,from_ts_code\n1,/__AF_INPUT__/ds-a/a.csv,000300.SH\n"
        mf_csv = (
            "agent_run_manifest_id,manifest_file_path,related_dataset_ids\n"
            f"1,{MANIFEST_NONE_MARKER},1\n"
        )
        _materialize_agent_run_csvs(
            session, config, "/sandbox/runs/task-mf6/input", ds_csv, mf_csv
        )
        manifest_writes = [w for w in session.writes if w[1].endswith("path_manifest.csv")]
        self.assertEqual(len(manifest_writes), 1)
        content = manifest_writes[0][0].decode("utf-8")
        # 不应含 NONE marker（已物化）
        self.assertNotIn(MANIFEST_NONE_MARKER, content)
        # 不应含 original manifest_id（"m-virtual" 这类）
        self.assertNotIn("m-virtual", content)
        # 应含 temp 路径
        self.assertIn("_agent_run_manifest_1/manifest.json", content)

    def test_materialize_none_manifest_direct_call_with_cydny_schema(self) -> None:
        """MF6: 直接调 _materialize_none_manifest 也工作（用于单元测试）。"""
        session = _FakeSession()
        dataset_by_number = {"1": "000300.SH", "2": "000002.SZ"}
        path = _materialize_none_manifest(
            session, "/sandbox/runs/task-x/input/", "1", "1#2", dataset_by_number
        )
        self.assertEqual(
            path,
            "/sandbox/runs/task-x/input/_agent_run_manifest_1/manifest.json",
        )
        self.assertEqual(len(session.writes), 1)
        payload = json.loads(session.writes[0][0].decode("utf-8"))
        self.assertEqual(payload["manifestId"], "agent-run-manifest-1")
        self.assertEqual(payload["kind"], "agent_run_manifest")
        self.assertEqual(payload["memberCount"], 2)
        self.assertEqual(payload["readyCount"], 2)
        self.assertEqual(payload["failedCount"], 0)
        self.assertEqual(len(payload["members"]), 2)

    def test_materialize_none_manifest_rejects_non_digit_id(self) -> None:
        """MF6: agent_run_manifest_id 必须数字（path traversal 防护）。"""
        session = _FakeSession()
        path = _materialize_none_manifest(
            session, "/sandbox/runs/task-x/input/", "1; rm -rf /", "1", {"1": "000300.SH"}
        )
        self.assertEqual(path, MANIFEST_NONE_MARKER)
        self.assertEqual(session.writes, [])

    def test_materialize_none_manifest_empty_id(self) -> None:
        session = _FakeSession()
        self.assertEqual(
            _materialize_none_manifest(
                session, "/sandbox/runs/task-x/input/", "", "1", {"1": "x"}
            ),
            MANIFEST_NONE_MARKER,
        )
        self.assertEqual(
            _materialize_none_manifest(
                session, "/sandbox/runs/task-x/input/", "  ", "1", {"1": "x"}
            ),
            MANIFEST_NONE_MARKER,
        )

    # ----- MF4: 4 列 input → on-disk 3 列 public schema -----

    def test_materialized_paths_dataset_csv_is_three_columns(self) -> None:
        """MF4: paths_dataset.csv input 带 4 列（host-internal source_path）→
        sandbox on-disk paths_dataset.csv 强制 3 列 public schema（header + rows）。
        """
        import csv as _csv

        config = _test_config(Path("/tmp/none"))
        session = _FakeSession()
        ds_csv = (
            "agent_run_dataset_id,dataset_file_path,from_ts_code,source_path\n"
            "1,/__AF_INPUT__/ds-a/a.csv,000300.SH,/data/database_fetched/7D3A/000300.SH.csv\n"
            "2,/__AF_INPUT__/ds-b/b.csv,000002.SZ,/data/database_fetched/abc/000002.SZ.csv\n"
        )
        _materialize_agent_run_csvs(
            session, config, "/sandbox/runs/task-mf4/input", ds_csv, ""
        )
        paths_writes = [w for w in session.writes if w[1].endswith("paths_dataset.csv")]
        self.assertEqual(len(paths_writes), 1, "exactly one paths_dataset.csv write")
        content = paths_writes[0][0].decode("utf-8")
        # 不再含 placeholder
        self.assertNotIn(SANDBOX_INPUT_PLACEHOLDER, content)
        # 不再含 source_path 字段值（4 列数据被 strip 掉）
        self.assertNotIn("/data/database_fetched/", content)
        # 解析 row count + 列数：header 1 行 + 2 行数据
        reader = _csv.reader(io.StringIO(content))
        rows = [r for r in reader if r]
        self.assertEqual(len(rows), 3, "expected header + 2 data rows")
        # header 是 3 列 public schema
        self.assertEqual(
            rows[0],
            ["agent_run_dataset_id", "dataset_file_path", "from_ts_code"],
            "on-disk header must be 3 columns",
        )
        # 数据行也必须 3 列
        for row in rows[1:]:
            self.assertEqual(
                len(row), 3,
                f"data row must be 3 columns, got {len(row)}: {row!r}",
            )
        # 数据行内容正确（placeholder 替换 + source_path strip）
        self.assertEqual(rows[1][0], "1")
        self.assertEqual(rows[1][1], "/sandbox/runs/task-mf4/input/ds-a/a.csv")
        self.assertEqual(rows[1][2], "000300.SH")
        self.assertEqual(rows[2][0], "2")
        self.assertEqual(rows[2][1], "/sandbox/runs/task-mf4/input/ds-b/b.csv")
        self.assertEqual(rows[2][2], "000002.SZ")

    def test_materialized_path_manifest_csv_is_three_columns(self) -> None:
        """MF4: path_manifest.csv input 带 4 列 → sandbox on-disk 强制 3 列 public schema。

        包含 NONE 行物化路径也必须是 3 列（id / temp_path / related）。
        """
        import csv as _csv

        config = _test_config(Path("/tmp/none"))
        session = _FakeSession()
        ds_csv = (
            "agent_run_dataset_id,dataset_file_path,from_ts_code,source_path\n"
            "1,/__AF_INPUT__/ds-a/a.csv,000300.SH,/data/ds.csv\n"
        )
        # 1 行 NONE + 1 行正常 manifest，都带第 4 列 source_path
        mf_csv = (
            "agent_run_manifest_id,manifest_file_path,related_dataset_ids,source_path\n"
            f"1,{MANIFEST_NONE_MARKER},1,/data/should-be-ignored.json\n"
            "2,/__AF_INPUT__/m-real/manifest.json,1,/data/m-real/manifest.json\n"
        )
        _materialize_agent_run_csvs(
            session, config, "/sandbox/runs/task-mf4/input", ds_csv, mf_csv
        )
        manifest_writes = [w for w in session.writes if w[1].endswith("path_manifest.csv")]
        self.assertEqual(len(manifest_writes), 1, "exactly one path_manifest.csv write")
        content = manifest_writes[0][0].decode("utf-8")
        # 解析 row count + 列数
        reader = _csv.reader(io.StringIO(content))
        rows = [r for r in reader if r]
        self.assertEqual(len(rows), 3, "expected header + 2 data rows")
        # header 是 3 列 public schema
        self.assertEqual(
            rows[0],
            ["agent_run_manifest_id", "manifest_file_path", "related_dataset_ids"],
            "on-disk header must be 3 columns",
        )
        # 所有数据行必须 3 列
        for row in rows[1:]:
            self.assertEqual(
                len(row), 3,
                f"data row must be 3 columns, got {len(row)}: {row!r}",
            )
        # NONE 行被物化为 temp 路径，仍是 3 列
        self.assertEqual(rows[1][0], "1")
        self.assertIn("_agent_run_manifest_1/manifest.json", rows[1][1])
        self.assertEqual(rows[1][2], "1")
        # 正常 manifest 行：placeholder 替换 + strip 第 4 列
        self.assertEqual(rows[2][0], "2")
        self.assertEqual(rows[2][1], "/sandbox/runs/task-mf4/input/m-real/manifest.json")
        self.assertEqual(rows[2][2], "1")
        # source_path 数据不应出现在 on-disk CSV
        self.assertNotIn("/data/should-be-ignored", content)
        self.assertNotIn("/data/m-real/manifest.json", content)

    def test_materialized_csvs_drop_source_path_when_legacy_3_column_input(self) -> None:
        """MF4 兼容路径: input 是 3 列 legacy CSV（无 source_path）→ on-disk 也是 3 列。

        确保 strip 逻辑不会破坏已经 3 列的输入（向后兼容）。
        """
        import csv as _csv

        config = _test_config(Path("/tmp/none"))
        session = _FakeSession()
        ds_csv = (
            "agent_run_dataset_id,dataset_file_path,from_ts_code\n"
            "1,/__AF_INPUT__/ds-a/a.csv,000300.SH\n"
        )
        mf_csv = (
            "agent_run_manifest_id,manifest_file_path,related_dataset_ids\n"
            "1,/__AF_INPUT__/m-x/manifest.json,1\n"
        )
        _materialize_agent_run_csvs(
            session, config, "/sandbox/runs/task-mf4/input", ds_csv, mf_csv
        )
        # paths_dataset.csv
        paths_writes = [w for w in session.writes if w[1].endswith("paths_dataset.csv")]
        self.assertEqual(len(paths_writes), 1)
        reader = _csv.reader(io.StringIO(paths_writes[0][0].decode("utf-8")))
        rows = [r for r in reader if r]
        for row in rows:
            self.assertEqual(len(row), 3, f"row must be 3 columns: {row!r}")
        # path_manifest.csv
        mf_writes = [w for w in session.writes if w[1].endswith("path_manifest.csv")]
        self.assertEqual(len(mf_writes), 1)
        reader = _csv.reader(io.StringIO(mf_writes[0][0].decode("utf-8")))
        rows = [r for r in reader if r]
        for row in rows:
            self.assertEqual(len(row), 3, f"row must be 3 columns: {row!r}")


class SandboxRunnerCsvSourcePathCopyTest(unittest.TestCase):
    """260623-harness-optimization-02: MF3 — 第 4 列 source_path 直接 cp 测试。

    验证 _copy_via_csv_source_paths 从 CSV 4 列读 source_path，
    用 _copy_dataset_file 直接 cp（绕过 _resolve_dataset_dir + _list_files）。
    """

    def test_dataset_source_path_copies_via_source(self) -> None:
        """MF3: paths_dataset.csv 第 4 列非空 → 直接 cp source_path → sandbox path。"""
        from app.sandbox_runner import _copy_via_csv_source_paths
        config = _test_config(Path("/tmp/none"))
        session = _FakeSession()
        ds_csv = (
            "agent_run_dataset_id,dataset_file_path,from_ts_code,source_path\n"
            "1,/__AF_INPUT__/ds-a/a.csv,000300.SH,/data/database_fetched/7D3A/000300.SH.csv\n"
            "2,/__AF_INPUT__/ds-b/b.csv,000002.SZ,/data/database_fetched/abc/000002.SZ.csv\n"
        )
        count = _copy_via_csv_source_paths(
            session, config, "task-mf3", "/sandbox/runs/task-mf3/input", ds_csv, ""
        )
        self.assertEqual(count, 2)
        writes_by_dest = {w[1]: w[0] for w in session.writes}
        self.assertIn("/sandbox/runs/task-mf3/input/ds-a/000300.SH.csv", writes_by_dest)
        self.assertIn("/sandbox/runs/task-mf3/input/ds-b/000002.SZ.csv", writes_by_dest)

    def test_manifest_source_path_copies_via_source(self) -> None:
        """MF3: path_manifest.csv 第 4 列非空 → cp source_path → sandbox path。"""
        from app.sandbox_runner import _copy_via_csv_source_paths
        config = _test_config(Path("/tmp/none"))
        session = _FakeSession()
        mf_csv = (
            "agent_run_manifest_id,manifest_file_path,related_dataset_ids,source_path\n"
            "1,/__AF_INPUT__/m-x/manifest.json,1,/data/manifests/v1/manifest-x/manifest.json\n"
        )
        count = _copy_via_csv_source_paths(
            session, config, "task-mf3", "/sandbox/runs/task-mf3/input", "", mf_csv
        )
        self.assertEqual(count, 1)
        writes_by_dest = {w[1]: w[0] for w in session.writes}
        self.assertIn(
            "/sandbox/runs/task-mf3/input/m-x/manifest.json",
            writes_by_dest,
        )

    def test_none_manifest_row_skipped_in_source_copy(self) -> None:
        """MF3: NONE 行（manifest_file_path=NONE）由 _materialize_none_manifest 处理，不在此 cp。"""
        from app.sandbox_runner import _copy_via_csv_source_paths
        config = _test_config(Path("/tmp/none"))
        session = _FakeSession()
        mf_csv = (
            "agent_run_manifest_id,manifest_file_path,related_dataset_ids,source_path\n"
            f"1,{MANIFEST_NONE_MARKER},1#,/data/should-be-ignored.json\n"
        )
        count = _copy_via_csv_source_paths(
            session, config, "task-mf3", "/sandbox/runs/task-mf3/input", "", mf_csv
        )
        # NONE 行不复制（即使 source_path 不为空）
        self.assertEqual(count, 0)
        self.assertEqual(session.writes, [])

    def test_empty_source_path_returns_zero(self) -> None:
        """MF3: 第 4 列为空 → 0 次复制（调用方决定是否走 legacy data_dir fallback）。"""
        from app.sandbox_runner import _copy_via_csv_source_paths
        config = _test_config(Path("/tmp/none"))
        session = _FakeSession()
        ds_csv = (
            "agent_run_dataset_id,dataset_file_path,from_ts_code,source_path\n"
            "1,/__AF_INPUT__/ds-a/a.csv,000300.SH,\n"  # 空 source_path
        )
        count = _copy_via_csv_source_paths(
            session, config, "task-mf3", "/sandbox/runs/task-mf3/input", ds_csv, ""
        )
        self.assertEqual(count, 0)
        self.assertEqual(session.writes, [])

    def test_three_column_legacy_csv_returns_zero(self) -> None:
        """MF3: 旧 3 列 CSV（无 source_path）→ 0 次复制（保持向后兼容）。"""
        from app.sandbox_runner import _copy_via_csv_source_paths
        config = _test_config(Path("/tmp/none"))
        session = _FakeSession()
        ds_csv = (
            "agent_run_dataset_id,dataset_file_path,from_ts_code\n"
            "1,/__AF_INPUT__/ds-a/a.csv,000300.SH\n"
        )
        count = _copy_via_csv_source_paths(
            session, config, "task-mf3", "/sandbox/runs/task-mf3/input", ds_csv, ""
        )
        self.assertEqual(count, 0)
        self.assertEqual(session.writes, [])

    def test_combined_dataset_and_manifest(self) -> None:
        """MF3: paths_dataset + path_manifest 同时给 → 两边都 cp。"""
        from app.sandbox_runner import _copy_via_csv_source_paths
        config = _test_config(Path("/tmp/none"))
        session = _FakeSession()
        ds_csv = (
            "agent_run_dataset_id,dataset_file_path,from_ts_code,source_path\n"
            "1,/__AF_INPUT__/ds-a/a.csv,000300.SH,/data/ds-a/a.csv\n"
        )
        mf_csv = (
            "agent_run_manifest_id,manifest_file_path,related_dataset_ids,source_path\n"
            "1,/__AF_INPUT__/m-x/manifest.json,1,/data/m-x/manifest.json\n"
        )
        count = _copy_via_csv_source_paths(
            session, config, "task-mf3", "/sandbox/runs/task-mf3/input", ds_csv, mf_csv
        )
        self.assertEqual(count, 2)

    def test_empty_csvs_return_zero(self) -> None:
        """MF3: 两个 CSV 都为空 → 0 次复制。"""
        from app.sandbox_runner import _copy_via_csv_source_paths
        config = _test_config(Path("/tmp/none"))
        session = _FakeSession()
        count = _copy_via_csv_source_paths(
            session, config, "task-mf3", "/sandbox/runs/task-mf3/input", "", ""
        )
        self.assertEqual(count, 0)
        self.assertEqual(session.writes, [])


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

    def test_agent_run_mode_fails_loud_when_no_source_path(self) -> None:
        """MF5: CSVs 非空但所有行 source_path 为空 → RuntimeError fail loud。

        不能 silently fallback 到 legacy data_dir 扫描（那会让 sandbox 看到 originalId，
        违反 run-level 抽象）。
        """
        from app.sandbox_runner import _prepare_task_workspace

        config = _test_config(Path("/tmp/none"))
        session = _FakeSession()
        ds_csv = (
            "agent_run_dataset_id,dataset_file_path,from_ts_code,source_path\n"
            "1,/__AF_INPUT__/ds-a/a.csv,000300.SH,\n"  # 空 source_path
            "2,/__AF_INPUT__/ds-b/b.csv,000002.SZ,\n"  # 空 source_path
        )
        # 不管 expand_dataset_ids 是否被调用，都不应该 silently 走 legacy 路径。
        # 此处 mock 让 expand_dataset_ids 即使被调也返回一个干净结果，确保是 fail loud 触发。
        _orig, fake_expand = self._patch_expand_dataset_ids(
            {"manifest_ids": [], "atomic_ids": []}
        )
        from app import sandbox_runner
        sandbox_runner.expand_dataset_ids = fake_expand
        try:
            with self.assertRaises(RuntimeError) as ctx:
                _prepare_task_workspace(
                    session,
                    "task-mf5-fail-loud",
                    config,
                    [],
                    None,
                    paths_dataset_csv=ds_csv,
                    path_manifest_csv="",
                )
            msg = str(ctx.exception)
            self.assertIn("agent_run mode", msg)
            self.assertIn("paths_dataset_csv provided=True", msg)
            self.assertIn("path_manifest_csv provided=False", msg)
            self.assertIn("DatasetPersistedEvent", msg)
            # CSVs 没有被 materialize（fail fast，workspace 没准备好）
            paths_writes = [w for w in session.writes if w[1].endswith("paths_dataset.csv")]
            self.assertEqual(paths_writes, [], "no CSV written when fail loud")
        finally:
            sandbox_runner.expand_dataset_ids = _orig

    def test_agent_run_mode_fails_loud_when_only_manifest_csv_provided(self) -> None:
        """MF5: 只给 path_manifest_csv（paths_dataset_csv 为空）也走 agent run 模式 contract。"""
        from app.sandbox_runner import _prepare_task_workspace

        config = _test_config(Path("/tmp/none"))
        session = _FakeSession()
        mf_csv = (
            "agent_run_manifest_id,manifest_file_path,related_dataset_ids,source_path\n"
            f"1,{MANIFEST_NONE_MARKER},1,\n"  # NONE 行 + 空 source_path
        )
        _orig, fake_expand = self._patch_expand_dataset_ids(
            {"manifest_ids": [], "atomic_ids": []}
        )
        from app import sandbox_runner
        sandbox_runner.expand_dataset_ids = fake_expand
        try:
            with self.assertRaises(RuntimeError) as ctx:
                _prepare_task_workspace(
                    session,
                    "task-mf5-mf-only",
                    config,
                    [],
                    None,
                    paths_dataset_csv="",
                    path_manifest_csv=mf_csv,
                )
            msg = str(ctx.exception)
            self.assertIn("paths_dataset_csv provided=False", msg)
            self.assertIn("path_manifest_csv provided=True", msg)
        finally:
            sandbox_runner.expand_dataset_ids = _orig

    def test_agent_run_mode_succeeds_when_source_path_copies_succeed(self) -> None:
        """MF5 sanity: CSVs 非空且有可 cp 的 source_path → 不抛异常，正常走流程。"""
        from app.sandbox_runner import _prepare_task_workspace

        config = _test_config(Path("/tmp/none"))
        session = _FakeSession()
        ds_csv = (
            "agent_run_dataset_id,dataset_file_path,from_ts_code,source_path\n"
            "1,/__AF_INPUT__/ds-a/a.csv,000300.SH,/tmp/_af_mf5_ok.csv\n"
        )
        # 把 source_path 指向一个真实存在的临时文件
        tmp_src = Path("/tmp/_af_mf5_ok.csv")
        tmp_src.parent.mkdir(parents=True, exist_ok=True)
        try:
            tmp_src.write_text("dummy\n")
            ds_csv = (
                "agent_run_dataset_id,dataset_file_path,from_ts_code,source_path\n"
                f"1,/__AF_INPUT__/ds-a/a.csv,000300.SH,{tmp_src}\n"
            )
            workspace = _prepare_task_workspace(
                session,
                "task-mf5-ok",
                config,
                [],
                None,
                paths_dataset_csv=ds_csv,
                path_manifest_csv="",
            )
            self.assertTrue(workspace.endswith("task-mf5-ok"))
            # paths_dataset.csv 应该被 materialize（strip 后的 3 列 public schema）
            paths_writes = [w for w in session.writes if w[1].endswith("paths_dataset.csv")]
            self.assertEqual(len(paths_writes), 1)
        finally:
            tmp_src.unlink(missing_ok=True)

    def test_legacy_mode_falls_back_to_data_dir_when_csvs_empty(self) -> None:
        """MF5: CSVs 都空 → 走 legacy data_dir 兼容路径（保持向后兼容），不抛异常。

        expand_dataset_ids 应被调用；不被 RuntimeError 兜底。
        """
        from app.sandbox_runner import _prepare_task_workspace
        from app.dataset_manifest import ExpandedDatasets

        config = _test_config(Path("/tmp/none"))
        session = _FakeSession()

        # mock expand_dataset_ids 让它返回一个空 result（不抛异常），且不被 RuntimeError 拦截
        from app import sandbox_runner
        called_with: List[List[str]] = []

        def _fake_expand(data_dir, dataset_id_list):
            called_with.append(list(dataset_id_list))
            return ExpandedDatasets(manifest_ids=[], atomic_ids=[])

        _orig = sandbox_runner.expand_dataset_ids
        sandbox_runner.expand_dataset_ids = _fake_expand
        try:
            workspace = _prepare_task_workspace(
                session,
                "task-mf5-legacy",
                config,
                ["ds-x"],
                None,
                paths_dataset_csv="",
                path_manifest_csv="",
            )
            # legacy 路径 → expand_dataset_ids 被调用
            self.assertEqual(len(called_with), 1)
            self.assertEqual(called_with[0], ["ds-x"])
            self.assertTrue(workspace.endswith("task-mf5-legacy"))
        finally:
            sandbox_runner.expand_dataset_ids = _orig

    def test_agent_run_mode_fails_loud_only_when_both_csvs_provided_and_no_copy(self) -> None:
        """MF5 boundary: CSVs 一个非空 + source_path 部分 cp → 不抛 RuntimeError（正常 agent run）。

        关键区分：MF5 fail loud 只在 (has_csv && source_copy_count == 0) 时触发。
        哪怕只有 1 行 cp 成功，也不抛异常（_copy_via_csv_source_paths 内部单 row 失败
        仍 log warning + false，但 source_copy_count > 0 → 上层不 fail loud）。
        """
        from app.sandbox_runner import _prepare_task_workspace

        config = _test_config(Path("/tmp/none"))
        session = _FakeSession()
        # 1 行有 source_path + 1 行 source_path 为空 → source_copy_count == 1
        tmp_src = Path("/tmp/_af_mf5_partial.csv")
        tmp_src.parent.mkdir(parents=True, exist_ok=True)
        try:
            tmp_src.write_text("partial\n")
            ds_csv = (
                "agent_run_dataset_id,dataset_file_path,from_ts_code,source_path\n"
                f"1,/__AF_INPUT__/ds-a/a.csv,000300.SH,{tmp_src}\n"
                "2,/__AF_INPUT__/ds-b/b.csv,000002.SZ,\n"
            )
            workspace = _prepare_task_workspace(
                session,
                "task-mf5-partial",
                config,
                [],
                None,
                paths_dataset_csv=ds_csv,
                path_manifest_csv="",
            )
            self.assertTrue(workspace.endswith("task-mf5-partial"))
            # 没抛异常即符合预期
        finally:
            tmp_src.unlink(missing_ok=True)


if __name__ == "__main__":
    unittest.main()
