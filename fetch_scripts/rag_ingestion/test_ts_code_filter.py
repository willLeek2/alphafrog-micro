"""TsCodeFilter 单元测试。

不依赖外部 DB/网络, 跑 `python -m pytest test_ts_code_filter.py` 即可。
"""
import pytest

from ts_code_filter import ConfigError, TsCodeFilter


# ── from_yaml: 旧式兼容 ──────────────────────────────────────


def test_from_yaml_none_returns_empty_filter():
    f = TsCodeFilter.from_yaml(None, scenario_name="S")
    assert f.type is None
    assert f.values == []
    assert f.conditions == {}


def test_from_yaml_str_auto_wraps_as_list():
    f = TsCodeFilter.from_yaml("000001.SZ", scenario_name="S")
    assert f.type == "list"
    assert f.values == ["000001.SZ"]


def test_from_yaml_list_passes_through():
    f = TsCodeFilter.from_yaml(["000001.SZ", "000002.SZ"], scenario_name="S")
    assert f.type == "list"
    assert f.values == ["000001.SZ", "000002.SZ"]


def test_from_yaml_empty_list_raises():
    with pytest.raises(ConfigError, match="list 不能为空"):
        TsCodeFilter.from_yaml([], scenario_name="S-empty-list")


# ── from_yaml: type=list 显式 ───────────────────────────────


def test_from_yaml_dict_type_list():
    f = TsCodeFilter.from_yaml(
        {"type": "list", "values": ["600519.SH", "601318.SH"]},
        scenario_name="S",
    )
    assert f.type == "list"
    assert f.values == ["600519.SH", "601318.SH"]


def test_from_yaml_dict_type_list_empty_values_raises():
    with pytest.raises(ConfigError, match="values 必填且非空"):
        TsCodeFilter.from_yaml(
            {"type": "list", "values": []}, scenario_name="S-empty-values"
        )


# ── from_yaml: type=select ──────────────────────────────────


def test_from_yaml_dict_type_select():
    f = TsCodeFilter.from_yaml(
        {
            "type": "select",
            "conditions": {
                "index_codes": ["000300.SH", "000905.SH"],
                "member_date_from": "20200101",
                "member_date_to": "20241231",
            },
        },
        scenario_name="S",
    )
    assert f.type == "select"
    assert f.conditions["index_codes"] == ["000300.SH", "000905.SH"]
    assert f.conditions["member_date_from"] == "20200101"
    assert f.conditions["member_date_to"] == "20241231"


def test_from_yaml_dict_type_select_empty_index_codes_raises():
    with pytest.raises(ConfigError, match="index_codes 必填且非空"):
        TsCodeFilter.from_yaml(
            {
                "type": "select",
                "conditions": {"index_codes": []},
            },
            scenario_name="S-empty-idx",
        )


def test_from_yaml_dict_type_select_missing_conditions_raises():
    with pytest.raises(ConfigError, match="index_codes 必填且非空"):
        TsCodeFilter.from_yaml(
            {"type": "select", "conditions": {}},
            scenario_name="S-empty-conds",
        )


def test_from_yaml_unknown_type_raises():
    with pytest.raises(ConfigError, match="必须是 list 或 select"):
        TsCodeFilter.from_yaml({"type": "foo"}, scenario_name="S-unknown")


def test_from_yaml_unsupported_root_type_raises():
    with pytest.raises(ConfigError, match="格式不支持"):
        TsCodeFilter.from_yaml(12345, scenario_name="S-bad-root")


# ── to_sql_clause: list ─────────────────────────────────────


def test_to_sql_clause_list_single_value():
    f = TsCodeFilter.from_yaml("000001.SZ", scenario_name="S")
    clause, params = f.to_sql_clause()
    assert clause == "d.ts_code IN (%s)"
    assert params == ["000001.SZ"]


def test_to_sql_clause_list_multiple_values():
    f = TsCodeFilter.from_yaml(["000001.SZ", "000002.SZ"], scenario_name="S")
    clause, params = f.to_sql_clause()
    assert clause == "d.ts_code IN (%s,%s)"
    assert params == ["000001.SZ", "000002.SZ"]


def test_to_sql_clause_empty_type_returns_empty():
    f = TsCodeFilter.from_yaml(None, scenario_name="S")
    clause, params = f.to_sql_clause()
    assert clause == ""
    assert params == []


# ── to_sql_clause: select ───────────────────────────────────


def test_to_sql_clause_select_with_dates():
    f = TsCodeFilter.from_yaml(
        {
            "type": "select",
            "conditions": {
                "index_codes": ["000300.SH", "000905.SH"],
                "member_date_from": "20200101",
                "member_date_to": "20241231",
            },
        },
        scenario_name="S",
    )
    clause, params = f.to_sql_clause()
    assert "EXISTS (SELECT 1 FROM alphafrog_index_weight w" in clause
    assert "w.con_code = d.ts_code" in clause
    assert "w.index_code IN (%s,%s)" in clause
    assert "w.trade_date >= %s" in clause
    assert "w.trade_date < %s" in clause
    # 2020-01-01 CST 00:00:00 = 1577808000 sec
    # 2024-12-31 CST 00:00:00 = 1735574400 sec; 加上 86_400_000 ms 作为 < 严格上界
    assert params == ["000300.SH", "000905.SH", 1577808000000, 1735574400000 + 86_400_000]


def test_to_sql_clause_select_only_index_codes():
    f = TsCodeFilter.from_yaml(
        {
            "type": "select",
            "conditions": {"index_codes": ["000300.SH"]},
        },
        scenario_name="S",
    )
    clause, params = f.to_sql_clause()
    assert "w.con_code = d.ts_code" in clause
    assert "w.index_code IN (%s)" in clause
    assert "w.trade_date" not in clause
    assert params == ["000300.SH"]


# ── to_sql_clause: asset_code_column 透传 ──────────────────


def test_to_sql_clause_uses_passed_column():
    f = TsCodeFilter.from_yaml(
        {
            "type": "select",
            "conditions": {"index_codes": ["000300.SH"]},
        },
        scenario_name="S",
    )
    clause, _ = f.to_sql_clause(asset_code_column="d.ts_code")
    assert "d.ts_code" in clause
    assert "a.ts_code" not in clause


def test_to_sql_clause_list_uses_passed_column():
    f = TsCodeFilter.from_yaml(["000001.SZ"], scenario_name="S")
    clause, _ = f.to_sql_clause(asset_code_column="d.ts_code")
    assert clause == "d.ts_code IN (%s)"


def test_to_sql_clause_custom_column_for_other_query():
    f = TsCodeFilter.from_yaml(
        {
            "type": "select",
            "conditions": {"index_codes": ["000300.SH"]},
        },
        scenario_name="S",
    )
    clause, _ = f.to_sql_clause(asset_code_column="d.ts_code")
    # 关键: 不应该写死成 a.ts_code
    assert "a.ts_code" not in clause
