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


def test_from_yaml_unknown_type_raises():
    with pytest.raises(ConfigError, match="必须是 list 或 select"):
        TsCodeFilter.from_yaml({"type": "foo"}, scenario_name="S-unknown")


def test_from_yaml_unsupported_root_type_raises():
    with pytest.raises(ConfigError, match="格式不支持"):
        TsCodeFilter.from_yaml(12345, scenario_name="S-bad-root")


# ── to_sql_clause: list ─────────────────────────────────────


# ── to_sql_clause: select ───────────────────────────────────


# ── to_sql_clause: asset_code_column 透传 ──────────────────


