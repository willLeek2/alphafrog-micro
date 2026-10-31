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


def trace_ids_from_traces(traces: Any) -> list[str]:
    rows = traces
    if isinstance(traces, dict):
        data = traces.get("data", traces)
        rows = data if isinstance(data, list) else data.get("items") if isinstance(data, dict) else []
    found: list[str] = []
    for row in rows or []:
        if not isinstance(row, dict):
            continue
        raw = str(row.get("traceId") or row.get("trace_id") or "").replace("-", "")
        if TRACE_ID_32.fullmatch(raw) and raw.lower() not in {item.lower() for item in found}:
            found.append(raw.lower())
    return found


def _project_row(row: dict[str, Any]) -> dict[str, Any]:
    projected = {key: row.get(key) for key in ROW_KEYS}
    projected["message"] = None
    projected["messageOmittedReason"] = "导出权限未确认"
    return projected


def attach_log_result(
    *,
    traces: Any,
    query: Callable[[str], dict[str, Any]] | None,
) -> dict[str, Any]:
    """query 为 None 表示集中日志入口尚未提供。"""

    if query is None:
        return {
            "association": "source-missing",
            "rows": [],
            "gaps": [{"item": "集中日志", "reason": "入口尚未提供"}],
        }
    trace_ids = trace_ids_from_traces(traces)
    if not trace_ids:
        return {
            "association": "time-proximity-search",
            "rows": [],
            "gaps": [{"item": "集中日志", "reason": "没有可送入的 32 位十六进制 trace 标识，时间接近的搜索不写入这次运行的诊断文件"}],
        }
    rows: list[dict[str, Any]] = []
    gaps: list[dict[str, str]] = []
    for trace_id in trace_ids:
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
        if status == "success" and (count == 0 or got == []):
            gaps.append({"item": "集中日志", "reason": f"trace {trace_id} 的查询为空结果"})
            continue
        for row in got:
            if isinstance(row, dict):
                rows.append(_project_row(row))
        truncation = result.get("truncation") or {}
        reason = truncation.get("reason")
        if reason in {"row_limit", "byte_limit", "timeout"}:
            gaps.append({"item": "集中日志", "reason": f"trace {trace_id} 截断，原因是 {reason}"})
        if result.get("countsComplete") is False:
            gaps.append({"item": "集中日志", "reason": f"trace {trace_id} 的取值计数不完整，只返回计数最大的前 100 个"})
    return {"association": "proven-call-association", "rows": rows, "gaps": gaps}
