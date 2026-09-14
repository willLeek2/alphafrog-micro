"""TitleMatchFilter unit tests.

No DB / HTTP / external services.
"""
import os
import sys

import pytest

_RAG_DIR = os.path.dirname(os.path.abspath(__file__))
if _RAG_DIR not in sys.path:
    sys.path.insert(0, _RAG_DIR)

from title_match_filter import TitleMatchFilter, normalize_title_filters
from ts_code_filter import ConfigError


def test_title_patterns_legacy_normalizes_to_patterns():
    patterns, title_match = normalize_title_filters(
        title_patterns_present=True,
        title_patterns_raw=["年度报告", "年报"],
        title_match_present=False,
        title_match_raw=None,
        scenario_name="S",
    )
    assert patterns == ["年度报告", "年报"]
    assert title_match is None


def test_title_match_contains_include_exclude_payload():
    patterns, title_match = normalize_title_filters(
        title_patterns_present=False,
        title_patterns_raw=None,
        title_match_present=True,
        title_match_raw={
            "mode": "contains",
            "include_mode": "any",
            "include": ["年度报告"],
            "exclude": ["摘要", "英文版"],
        },
        scenario_name="S",
    )
    assert patterns is None
    assert title_match == {
        "mode": "contains",
        "includeMode": "any",
        "include": ["年度报告"],
        "exclude": ["摘要", "英文版"],
    }


def test_title_match_include_mode_all_payload():
    f = TitleMatchFilter.from_yaml(
        {
            "mode": "contains",
            "include_mode": "all",
            "include": ["2024", "年度报告"],
        },
        scenario_name="S",
    )
    assert f.to_http_payload() == {
        "mode": "contains",
        "includeMode": "all",
        "include": ["2024", "年度报告"],
    }


def test_title_match_supports_exclude_only():
    f = TitleMatchFilter.from_yaml(
        {"mode": "contains", "exclude": ["摘要"]},
        scenario_name="S",
    )
    assert f.to_http_payload() == {
        "mode": "contains",
        "includeMode": "any",
        "exclude": ["摘要"],
    }


def test_title_patterns_and_title_match_are_mutually_exclusive():
    with pytest.raises(ConfigError, match="互斥"):
        normalize_title_filters(
            title_patterns_present=True,
            title_patterns_raw=[],
            title_match_present=True,
            title_match_raw={"mode": "contains", "include": ["年度报告"]},
            scenario_name="S",
        )


def test_title_match_unknown_mode_raises():
    with pytest.raises(ConfigError, match="只支持 contains"):
        TitleMatchFilter.from_yaml(
            {"mode": "regex", "include": [".*年度报告"]},
            scenario_name="S",
        )


def test_title_match_unknown_include_mode_raises():
    with pytest.raises(ConfigError, match="include_mode 必须是 any 或 all"):
        TitleMatchFilter.from_yaml(
            {"mode": "contains", "include_mode": "none", "include": ["年度报告"]},
            scenario_name="S",
        )


def test_title_match_empty_config_raises():
    with pytest.raises(ConfigError, match="至少填一个"):
        TitleMatchFilter.from_yaml({"mode": "contains"}, scenario_name="S")


def test_title_match_non_list_include_raises():
    with pytest.raises(ConfigError, match="title_match.include 必须是 list"):
        TitleMatchFilter.from_yaml(
            {"mode": "contains", "include": "年度报告"},
            scenario_name="S",
        )
