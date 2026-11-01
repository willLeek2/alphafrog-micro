"""run.py dispatch + scenarios 路径的烟雾测试。

不依赖真实 DB / Jina / OSS / Embedding / Qdrant。
通过 monkey-patch process_announcements / process_reports 记录调用参数,
验证 YAML → 路径分派 → ts_code 形态检测 → 循环展开 全部正确。

设计要点:
- scenarios 是 list (不是 dict), 每项是 dict, 必含 'name' 字段
- scenario 配置错误 (ts_code 缺值 / 缺 name) → ConfigError 抛出, 不静默跳过
"""
import os
import sys
import textwrap
import pytest

# 把 rag_ingestion 目录加入 path, 允许 import 同包模块
_RAG_DIR = os.path.dirname(os.path.abspath(__file__))
if _RAG_DIR not in sys.path:
    sys.path.insert(0, _RAG_DIR)

import run
from ts_code_filter import ConfigError, TsCodeFilter


# ── 工具: 假 db / cfg ───────────────────────────────────────


class _FakeDb:
    """只占位, 任何方法都不会被真实调用。"""


class _FakeCfg:
    pass


class _FakeScenarioCfg:
    """fake_override 返回的桩, 含 run.py:308-311 打印用的 3 个 embedding 属性。"""
    embedding_model = ""
    embedding_base_url = ""
    embedding_provider_order: list = []


@pytest.fixture
def fake_call_recorder(monkeypatch):
    """把 process_announcements / process_reports 替换成记录器, 返回 list of (func_name, kwargs)。

    顺手把 config_with_embedding_override 替换成一个返回 _FakeScenarioCfg 的桩, 避免
    scenarios.example.yml 包含 embedding 字段时 _FakeCfg 缺 Config 字段而炸。
    """
    calls: list = []

    def fake_process_announcements(db, cfg, **kwargs):
        calls.append(("process_announcements", kwargs))

    def fake_process_reports(db, cfg, **kwargs):
        calls.append(("process_reports", kwargs))

    def fake_override(cfg, emb):
        return _FakeScenarioCfg()

    monkeypatch.setattr(run, "process_announcements", fake_process_announcements)
    monkeypatch.setattr(run, "process_reports", fake_process_reports)
    monkeypatch.setattr(run, "config_with_embedding_override", fake_override)
    return calls


@pytest.fixture
def tmp_yaml(tmp_path):
    """返回 (path, content) 写入器, 写到 tmp_path/<name>.yml。"""
    def _write(name: str, content: str) -> str:
        p = tmp_path / name
        p.write_text(textwrap.dedent(content).lstrip("\n"), encoding="utf-8")
        return str(p)
    return _write


# ── 互斥校验 ────────────────────────────────────────────────


# ── 旧式 tasks: 向后兼容 ───────────────────────────────────


# ── 新式 scenarios: list 路径 ──────────────────────────────


# ── 新式 scenarios: select 路径 ────────────────────────────


# ── 新式 scenarios: 无 ts_code (整库扫) ─────────────────────


# ── 新式 scenarios: 配置错误 → ConfigError 抛出 (fail closed) ──


# ── 新式 scenarios: 多 scenario 顺序 + enabled 过滤 ───────


# ── 与真实 example 文件的对齐测试 ─────────────────────────


