"""
Title matching config normalization for RAG ingestion.

Supported shapes:
- legacy title_patterns: ["年度报告", "年报"]
- title_match:
    mode: contains
    include_mode: any  # any | all, optional, default any
    include: ["年度报告"]
    exclude: ["摘要", "英文版"]

The new and legacy forms are mutually exclusive so operators do not get
surprising mixed semantics.
"""
from dataclasses import dataclass, field
from typing import Any

from ts_code_filter import ConfigError


@dataclass
class TitleMatchFilter:
    mode: str = "contains"
    include_mode: str = "any"
    include: list[str] = field(default_factory=list)
    exclude: list[str] = field(default_factory=list)

    @classmethod
    def from_yaml(cls, raw: Any, *, scenario_name: str = "(未命名)") -> "TitleMatchFilter":
        if raw is None:
            return cls()
        if not isinstance(raw, dict):
            raise ConfigError(
                f"scenario {scenario_name!r}: title_match 必须是 dict, 收到 {type(raw).__name__}"
            )

        mode = str(raw.get("mode", "contains")).strip()
        if mode != "contains":
            raise ConfigError(
                f"scenario {scenario_name!r}: title_match.mode 目前只支持 contains, 收到 {mode!r}"
            )

        include_mode = str(raw.get("include_mode", raw.get("includeMode", "any"))).strip()
        if include_mode not in ("any", "all"):
            raise ConfigError(
                f"scenario {scenario_name!r}: title_match.include_mode 必须是 any 或 all, 收到 {include_mode!r}"
            )

        include = _string_list(raw.get("include"), "title_match.include", scenario_name)
        exclude = _string_list(raw.get("exclude"), "title_match.exclude", scenario_name)
        if not include and not exclude:
            raise ConfigError(
                f"scenario {scenario_name!r}: title_match.include/title_match.exclude 至少填一个"
            )

        return cls(mode=mode, include_mode=include_mode, include=include, exclude=exclude)

    def to_http_payload(self) -> dict | None:
        if not self.include and not self.exclude:
            return None
        payload = {
            "mode": self.mode,
            "includeMode": self.include_mode,
        }
        if self.include:
            payload["include"] = list(self.include)
        if self.exclude:
            payload["exclude"] = list(self.exclude)
        return payload


def normalize_title_filters(
    *,
    title_patterns_present: bool,
    title_patterns_raw: Any,
    title_match_present: bool,
    title_match_raw: Any,
    scenario_name: str = "(未命名)",
) -> tuple[list[str] | None, dict | None]:
    """Return (legacy_title_patterns, title_match_payload).

    `*_present` matters because `title_patterns: []` together with
    `title_match: ...` should still fail closed instead of silently ignoring
    the empty legacy key.
    """
    if title_patterns_present and title_match_present:
        raise ConfigError(
            f"scenario {scenario_name!r}: title_patterns 和 title_match 互斥, 请只保留一个"
        )

    if title_patterns_present:
        patterns = _string_list(title_patterns_raw, "title_patterns", scenario_name)
        return (patterns or None), None

    if title_match_present:
        return None, TitleMatchFilter.from_yaml(
            title_match_raw, scenario_name=scenario_name
        ).to_http_payload()

    return None, None


def _string_list(raw: Any, field_name: str, scenario_name: str) -> list[str]:
    if raw is None:
        return []
    if not isinstance(raw, list):
        raise ConfigError(
            f"scenario {scenario_name!r}: {field_name} 必须是 list, 收到 {type(raw).__name__}"
        )
    result: list[str] = []
    for i, value in enumerate(raw):
        if value is None:
            raise ConfigError(
                f"scenario {scenario_name!r}: {field_name}[{i}] 不能为空"
            )
        s = str(value).strip()
        if not s:
            raise ConfigError(
                f"scenario {scenario_name!r}: {field_name}[{i}] 不能为空"
            )
        result.append(s)
    return result
