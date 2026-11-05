"""把集中日志结果写入诊断包。一次查询只接受一个 32 位十六进制 trace 标识。"""

from __future__ import annotations

import re
from typing import Any, Callable

TRACE_ID_32 = re.compile(r"^[0-9a-fA-F]{32}$")
ROW_KEYS = (
    "time",
    "deployment",
    "service",
    "traceId",
    "level",
    "logger",
    "thread",
    "messageTruncated",
    "messageBytes",
)


def _project_row(row: dict[str, Any]) -> dict[str, Any]:
    projected = {key: row.get(key) for key in ROW_KEYS}
    projected["message"] = None
    projected["messageOmittedReason"] = "导出权限未确认"
    return projected


def attach_log_result(
    *,
    correspondence: list[dict[str, Any]] | None,
    query: Callable[[str], dict[str, Any]] | None,
) -> dict[str, Any]:
    """没有来源证明的日志轨迹编号时，不查询，也不写入日志。"""

    proven: list[str] = []
    for item in correspondence or []:
        raw = str(item.get("logTraceId") or "").strip()
        evidence = str(item.get("evidence") or "").strip()
        if TRACE_ID_32.fullmatch(raw) and evidence:
            lowered = raw.lower()
            if lowered not in proven:
                proven.append(lowered)
    if not proven:
        return {
            "association": "unassociated",
            "rows": [],
            "gaps": [{"item": "集中日志", "reason": "无法关联。业务调用编号不能当作日志轨迹编号"}],
        }
    if query is None:
        return {
            "association": "source-missing",
            "rows": [],
            "gaps": [{"item": "集中日志", "reason": "入口尚未提供"}],
        }
    rows: list[dict[str, Any]] = []
    gaps: list[dict[str, str]] = []
    for trace_id in proven:
        result = query(trace_id)
        status = str(result.get("status") or "")
        if status == "rejected":
            gaps.append({"item": "集中日志", "reason": f"trace {trace_id} 的查询在发出前被拒绝"})
            continue
        if status in {"unauthorized", "unavailable"}:
            gaps.append({"item": "集中日志", "reason": f"trace {trace_id} 的查询状态是 {status}"})
            continue
        count = result.get("count")
        got = result.get("rows") or []
        truncation = result.get("truncation") if isinstance(result.get("truncation"), dict) else {}
        reason = truncation.get("reason")
        zero_rows = count == 0 or got == []
        if status == "empty" or (
            status == "success" and zero_rows and reason not in {"row_limit", "byte_limit", "timeout"}
        ):
            gaps.append({"item": "集中日志", "reason": f"trace {trace_id} 的查询为空结果"})
            continue
        for row in got:
            if isinstance(row, dict):
                rows.append(_project_row(row))
        if reason in {"row_limit", "byte_limit", "timeout"}:
            gaps.append({"item": "集中日志", "reason": f"trace {trace_id} 截断，原因是 {reason}"})
        if result.get("countsComplete") is False:
            gaps.append({"item": "集中日志", "reason": f"trace {trace_id} 的取值计数不完整，只返回计数最大的前 100 个"})
    return {"association": "proven-call-association", "rows": rows, "gaps": gaps}
