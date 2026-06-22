"""260623-harness-optimization-02: 锁定 sandbox_runner._materialize_agent_run_csvs 的 placeholder 替换 +
NONE marker 保留行为。
"""

from __future__ import annotations

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
    _materialize_agent_run_csvs,
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


class _FakeSession:
    """记录 copy_to_runtime 调用，模拟 sandbox 写入。"""

    def __init__(self) -> None:
        self.writes: list[tuple[bytes, str]] = []

    def copy_to_runtime(self, payload: bytes, dest: str) -> None:
        self.writes.append((payload, dest))


class SandboxRunnerCsvMaterializeTest(unittest.TestCase):
    def test_paths_dataset_csv_placeholder_substitution(self) -> None:
        config = _test_config(Path("/tmp/none"))
        session = _FakeSession()
        ds_csv = (
            "agent_run_dataset_id,dataset_file_path,from_ts_code\n"
            "1,/__AF_INPUT__/ds-a/a.csv,000300.SH\n"
            "2,/__AF_INPUT__/ds-b/b.csv,000300.SH#510300.SH\n"
        )
        _materialize_agent_run_csvs(session, config, "/sandbox/runs/task-1/input", ds_csv, "")
        # 只有 paths_dataset.csv 被写入，path_manifest.csv 空 CSV 时不写
        paths_writes = [w for w in session.writes if w[1].endswith("paths_dataset.csv")]
        manifest_writes = [w for w in session.writes if w[1].endswith("path_manifest.csv")]
        self.assertEqual(len(paths_writes), 1, "exactly one paths_dataset.csv write")
        self.assertEqual(manifest_writes, [], "no path_manifest.csv when input empty")
        content = paths_writes[0][0].decode("utf-8")
        self.assertIn("/sandbox/runs/task-1/input/ds-a/a.csv", content)
        self.assertIn("/sandbox/runs/task-1/input/ds-b/b.csv", content)
        self.assertNotIn(SANDBOX_INPUT_PLACEHOLDER, content)

    def test_path_manifest_csv_placeholder_substitution(self) -> None:
        config = _test_config(Path("/tmp/none"))
        session = _FakeSession()
        mf_csv = (
            "agent_run_manifest_id,manifest_file_path,related_dataset_ids\n"
            "1,/__AF_INPUT__/m-x/manifest.json,ds-a#ds-b\n"
        )
        _materialize_agent_run_csvs(session, config, "/sandbox/runs/task-1/input", "", mf_csv)
        manifest_writes = [w for w in session.writes if w[1].endswith("path_manifest.csv")]
        self.assertEqual(len(manifest_writes), 1)
        content = manifest_writes[0][0].decode("utf-8")
        self.assertIn("/sandbox/runs/task-1/input/m-x/manifest.json", content)
        self.assertIn("ds-a#ds-b", content)
        self.assertNotIn(SANDBOX_INPUT_PLACEHOLDER, content)

    def test_path_manifest_csv_none_marker_preserved_as_is(self) -> None:
        # 260623-harness-optimization-02: NONE marker 行保留原样（不物化）。
        # 原因：CSV 行内只有 agent_run_manifest_id（run-level 编号），不含 original manifest_id，
        # sandbox 端无法定位 data_dir 下对应的 manifest.json 文件。
        # sandbox 内 af_dataset_loader 看到 NONE marker 时应给出明确错误。
        config = _test_config(Path("/tmp/none"))
        session = _FakeSession()
        mf_csv = (
            "agent_run_manifest_id,manifest_file_path,related_dataset_ids\n"
            f"1,{MANIFEST_NONE_MARKER},ds-a#ds-b\n"
        )
        _materialize_agent_run_csvs(session, config, "/sandbox/runs/task-2/input", "", mf_csv)
        manifest_writes = [w for w in session.writes if w[1].endswith("path_manifest.csv")]
        self.assertEqual(len(manifest_writes), 1, "NONE 行保留，path_manifest.csv 写一份")
        content = manifest_writes[0][0].decode("utf-8")
        self.assertIn(MANIFEST_NONE_MARKER, content)
        self.assertIn("1,NONE,ds-a#ds-b", content)
        self.assertIn("agent_run_manifest_id,manifest_file_path,related_dataset_ids", content)

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
                "1,/__AF_INPUT__/m-x/manifest.json,ds-a\n"
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


if __name__ == "__main__":
    unittest.main()
