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


def test_dispatch_both_tasks_and_scenarios_raises(tmp_yaml):
    """tasks + scenarios 同时存在 → ConfigError, fail closed。"""
    p = tmp_yaml("both.yml", """
        tasks:
          - {name: t1, doc_type: ann}
        scenarios:
          - {name: s1, doc_type: ann}
    """)
    with pytest.raises(ConfigError, match="同时包含 'tasks' 和 'scenarios'"):
        run.dispatch_from_config(p, _FakeDb(), _FakeCfg())


def test_dispatch_neither_tasks_nor_scenarios_raises(tmp_yaml):
    """两者都缺 → ConfigError。"""
    p = tmp_yaml("empty.yml", "other_key: 1\n")
    with pytest.raises(ConfigError, match="必须包含 'tasks' 或 'scenarios' 之一"):
        run.dispatch_from_config(p, _FakeDb(), _FakeCfg())


def test_dispatch_scenarios_top_level_not_list_raises(tmp_yaml):
    """scenarios 是 dict (不是 list) → ConfigError。"""
    p = tmp_yaml("dict.yml", """
        scenarios:
          s1:
            doc_type: ann
    """)
    with pytest.raises(ConfigError, match="scenarios 必须是 list"):
        run.dispatch_from_config(p, _FakeDb(), _FakeCfg())


# ── 旧式 tasks: 向后兼容 ───────────────────────────────────


def test_dispatch_legacy_tasks_routes_to_legacy(tmp_yaml, fake_call_recorder):
    """tasks 列表 仍能跑, 且行为不变 (不调 scenarios 路径)。"""
    p = tmp_yaml("legacy.yml", """
        tasks:
          - name: t1
            doc_type: ann
            date_from: "20240101"
            date_to: "20241231"
            ts_code: "000001.SZ"
            title_patterns: ["年度报告"]
            limit: 10
    """)
    run.dispatch_from_config(p, _FakeDb(), _FakeCfg())
    # t1 是 ann doc_type → 应调 1 次 process_announcements
    assert len(fake_call_recorder) == 1
    name, kwargs = fake_call_recorder[0]
    assert name == "process_announcements"
    assert kwargs["ts_code"] == "000001.SZ"
    assert kwargs["date_from"] == "20240101"
    assert kwargs["date_to"] == "20241231"
    assert kwargs["title_patterns"] == ["年度报告"]
    assert kwargs["limit"] == 10


def test_dispatch_legacy_tasks_all_doc_type(tmp_yaml, fake_call_recorder):
    """doc_type=all → 同时调 announcement 和 reports。"""
    p = tmp_yaml("legacy_all.yml", """
        tasks:
          - {name: t1, doc_type: all, limit: 5}
    """)
    run.dispatch_from_config(p, _FakeDb(), _FakeCfg())
    names = [n for n, _ in fake_call_recorder]
    assert names == ["process_announcements", "process_reports"]


def test_dispatch_legacy_tasks_disabled_skipped(tmp_yaml, fake_call_recorder):
    """enabled=false 的 task 被跳过。"""
    p = tmp_yaml("legacy_disabled.yml", """
        tasks:
          - {name: t1, doc_type: ann, enabled: false}
          - {name: t2, doc_type: ann, enabled: true, limit: 3}
    """)
    run.dispatch_from_config(p, _FakeDb(), _FakeCfg())
    assert len(fake_call_recorder) == 1
    assert fake_call_recorder[0][1]["limit"] == 3


# ── 新式 scenarios: list 路径 ──────────────────────────────


def test_scenarios_list_iterates_per_ts_code(tmp_yaml, fake_call_recorder):
    """type=list 时, 每个 ts_code 各跑一次 process_*, 用 scenario 的 limit。"""
    p = tmp_yaml("list.yml", """
        scenarios:
          - name: watchlist
            doc_type: ann
            date_from: "20240101"
            date_to: "20241231"
            title_patterns: ["年度报告"]
            ts_code:
              type: list
              values: ["600519.SH", "601318.SH", "000858.SZ"]
            limit: 5
    """)
    run.dispatch_from_config(p, _FakeDb(), _FakeCfg())
    # 3 个 ts_code × ann → 3 次 process_announcements
    assert len(fake_call_recorder) == 3
    codes = [kwargs["ts_code"] for _, kwargs in fake_call_recorder]
    assert codes == ["600519.SH", "601318.SH", "000858.SZ"]
    for _, kwargs in fake_call_recorder:
        assert kwargs["limit"] == 5
        assert kwargs["date_from"] == "20240101"
        assert kwargs["title_patterns"] == ["年度报告"]


# ── 新式 scenarios: select 路径 ────────────────────────────


def test_scenarios_select_passes_raw_dict(tmp_yaml, fake_call_recorder):
    """type=select 时, 原始 dict 直接传给 process_*, 触发 db_client 的 from_yaml 解析。"""
    p = tmp_yaml("select.yml", """
        scenarios:
          - name: hs300-2024-annual
            doc_type: ann
            date_from: "20240101"
            date_to: "20241231"
            ts_code:
              type: select
              conditions:
                index_codes: ["000300.SH"]
                member_date_from: "20200101"
                member_date_to: "20241231"
            limit: 1000
    """)
    run.dispatch_from_config(p, _FakeDb(), _FakeCfg())
    # select 是全局 SQL 分页 → 1 次调用
    assert len(fake_call_recorder) == 1
    name, kwargs = fake_call_recorder[0]
    assert name == "process_announcements"
    # ts_code 应该是原始 dict (传下去让 db_client 解析)
    assert kwargs["ts_code"] == {
        "type": "select",
        "conditions": {
            "index_codes": ["000300.SH"],
            "member_date_from": "20200101",
            "member_date_to": "20241231",
        },
    }
    assert kwargs["limit"] == 1000


# ── 新式 scenarios: 无 ts_code (整库扫) ─────────────────────


def test_scenarios_no_ts_code_global_pagination(tmp_yaml, fake_call_recorder):
    """ts_code 缺省 → 不按 ts_code 过滤, 1 次 process_* 调用。"""
    p = tmp_yaml("no_ts.yml", """
        scenarios:
          - name: all-quarterly
            doc_type: research
            date_from: "20260401"
            date_to: "20260607"
            title_patterns: ["季度报告"]
            limit: 200
    """)
    run.dispatch_from_config(p, _FakeDb(), _FakeCfg())
    assert len(fake_call_recorder) == 1
    name, kwargs = fake_call_recorder[0]
    assert name == "process_reports"
    assert kwargs["ts_code"] is None
    assert kwargs["limit"] == 200


def test_scenarios_title_match_include_exclude_passes_payload(tmp_yaml, fake_call_recorder):
    """title_match 新格式会标准化后传到 process_*。"""
    p = tmp_yaml("title_match.yml", """
        scenarios:
          - name: annual-no-summary
            doc_type: ann
            title_match:
              mode: contains
              include:
                - "年度报告"
              exclude:
                - "摘要"
                - "英文版"
            limit: 20
    """)
    run.dispatch_from_config(p, _FakeDb(), _FakeCfg())
    assert len(fake_call_recorder) == 1
    kwargs = fake_call_recorder[0][1]
    assert kwargs["title_patterns"] is None
    assert kwargs["title_match"] == {
        "mode": "contains",
        "includeMode": "any",
        "include": ["年度报告"],
        "exclude": ["摘要", "英文版"],
    }


def test_scenarios_title_match_include_mode_all(tmp_yaml, fake_call_recorder):
    p = tmp_yaml("title_match_all.yml", """
        scenarios:
          - name: annual-2024
            doc_type: ann
            title_match:
              mode: contains
              include_mode: all
              include:
                - "2024"
                - "年度报告"
            limit: 20
    """)
    run.dispatch_from_config(p, _FakeDb(), _FakeCfg())
    assert fake_call_recorder[0][1]["title_match"] == {
        "mode": "contains",
        "includeMode": "all",
        "include": ["2024", "年度报告"],
    }


def test_scenarios_title_patterns_and_title_match_raises(tmp_yaml, fake_call_recorder):
    p = tmp_yaml("both_title.yml", """
        scenarios:
          - name: bad-title
            doc_type: ann
            title_patterns: ["年度报告"]
            title_match:
              mode: contains
              include: ["年度报告"]
    """)
    with pytest.raises(ConfigError, match="互斥"):
        run.dispatch_from_config(p, _FakeDb(), _FakeCfg())
    assert len(fake_call_recorder) == 0


def test_scenarios_title_match_invalid_mode_raises(tmp_yaml, fake_call_recorder):
    p = tmp_yaml("bad_title_mode.yml", """
        scenarios:
          - name: bad-title-mode
            doc_type: ann
            title_match:
              mode: regex
              include: [".*年度报告"]
    """)
    with pytest.raises(ConfigError, match="只支持 contains"):
        run.dispatch_from_config(p, _FakeDb(), _FakeCfg())
    assert len(fake_call_recorder) == 0


# ── 新式 scenarios: 配置错误 → ConfigError 抛出 (fail closed) ──


def test_scenarios_invalid_ts_code_raises(tmp_yaml, fake_call_recorder):
    """type=list 但 values 为空 → ConfigError 抛出, 不静默跳过。"""
    p = tmp_yaml("invalid.yml", """
        scenarios:
          - name: bad-list
            doc_type: ann
            ts_code:
              type: list
              values: []
    """)
    with pytest.raises(ConfigError, match="values 必填且非空"):
        run.dispatch_from_config(p, _FakeDb(), _FakeCfg())
    # 抛错后不应有任何 process_* 调用
    assert len(fake_call_recorder) == 0


def test_scenarios_unknown_ts_code_type_raises(tmp_yaml, fake_call_recorder):
    """type=foo (未知) → ConfigError 抛出。"""
    p = tmp_yaml("unknown.yml", """
        scenarios:
          - name: bad-unknown
            doc_type: ann
            ts_code:
              type: foo
              values: ["x"]
    """)
    with pytest.raises(ConfigError, match="必须是 list 或 select"):
        run.dispatch_from_config(p, _FakeDb(), _FakeCfg())
    assert len(fake_call_recorder) == 0


def test_scenarios_item_not_dict_raises(tmp_yaml, fake_call_recorder):
    """scenario item 不是 dict (例如字符串) → ConfigError 抛出。"""
    p = tmp_yaml("bad_item.yml", """
        scenarios:
          - "not a dict"
    """)
    with pytest.raises(ConfigError, match="scenarios\\[0\\] 必须是 dict"):
        run.dispatch_from_config(p, _FakeDb(), _FakeCfg())
    assert len(fake_call_recorder) == 0


def test_scenarios_missing_name_raises(tmp_yaml, fake_call_recorder):
    """scenario 缺 name 字段 → ConfigError 抛出。"""
    p = tmp_yaml("no_name.yml", """
        scenarios:
          - doc_type: ann
    """)
    with pytest.raises(ConfigError, match="缺少必填字段 'name'"):
        run.dispatch_from_config(p, _FakeDb(), _FakeCfg())
    assert len(fake_call_recorder) == 0


def test_scenarios_first_bad_stops_rest(tmp_yaml, fake_call_recorder):
    """首个 scenario 配置错误, 后面正常的也不会跑 (fail closed + 立即停)。"""
    p = tmp_yaml("mixed.yml", """
        scenarios:
          - name: bad-list
            doc_type: ann
            ts_code:
              type: list
              values: []
          - name: good-no-ts
            doc_type: ann
            limit: 7
    """)
    with pytest.raises(ConfigError, match="values 必填且非空"):
        run.dispatch_from_config(p, _FakeDb(), _FakeCfg())
    # good-no-ts 没机会跑
    assert len(fake_call_recorder) == 0


# ── 新式 scenarios: 多 scenario 顺序 + enabled 过滤 ───────


def test_scenarios_multiple_with_disabled(tmp_yaml, fake_call_recorder):
    """多 scenario, enabled=false 的被跳过, 其余按 list 顺序跑。"""
    p = tmp_yaml("multi.yml", """
        scenarios:
          - {name: a, doc_type: ann, limit: 1, enabled: true}
          - {name: b, doc_type: ann, limit: 2, enabled: false}
          - {name: c, doc_type: research, limit: 3, enabled: true}
    """)
    run.dispatch_from_config(p, _FakeDb(), _FakeCfg())
    names = [kwargs["limit"] for _, kwargs in fake_call_recorder]
    assert names == [1, 3]


# ── 与真实 example 文件的对齐测试 ─────────────────────────


def test_real_scenarios_example_parses(tmp_yaml, fake_call_recorder):
    """configs/scenarios.example.yml 能被真实 dispatch 解析 (3 个 scenario, 1 enabled)。"""
    example = os.path.join(_RAG_DIR, "configs", "scenarios.example.yml")
    if not os.path.exists(example):
        pytest.skip("scenarios.example.yml 不存在, 跳过对齐测试")
    # watchlist=false, hs300=true, recent-quarterly=false → 1 个 enabled
    run.dispatch_from_config(example, _FakeDb(), _FakeCfg())
    # hs300-2024-annual 配 doc_type=ann, type=select → 1 次 process_announcements
    assert len(fake_call_recorder) == 1
    name, kwargs = fake_call_recorder[0]
    assert name == "process_announcements"
    assert kwargs["ts_code"]["type"] == "select"
    assert kwargs["ts_code"]["conditions"]["index_codes"] == ["000300.SH"]
