"""按结构化条件调用 af-observe logs，并把返回解释成六种状态。

三种调用是 read_logs、count_by_time、list_field_values。
本模块不连接日志库，不拼查询语句，不写审计文件。
运行编号只写进结果里的 request.runId，不传给日志命令。
本模块不读取运行的 traces 接口。调用方传入的 trace_id 是日志轨迹编号。
没有 trace_id 时，结果标成「时间接近的搜索」。
有 trace_id 时，结果只表示按这个编号过滤，不表示已经证实的调用关联。
"""

from __future__ import annotations

import datetime as dt
import json
import math
import os
import re
import shutil
import subprocess
from typing import Any, Callable

UTC = dt.timezone.utc

INSTALLED_BINARY = "/opt/alphafrog-observe/bin/af-observe"
COMMAND_TIMEOUT_SECONDS = 25
MAX_WINDOW_SECONDS = 2 * 60 * 60
FUTURE_TOLERANCE_SECONDS = 5 * 60
READ_MAX_ROWS = 200
VALUES_MAX_VALUES = 100
STATS_MAX_BUCKETS = 120
KEYWORD_MAX_CHARS = 128
MAX_TEXT_CHARS = 256
BUCKET_SECONDS = {"1m": 60, "5m": 300, "10m": 600, "60m": 3600}
ALLOWED_LEVELS = ("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL")
VALUE_FIELDS = ("deployment", "service.name", "trace_id", "level", "logger_name")
SERVICE_RE = re.compile(r"^[A-Za-z0-9._-]{1,128}$")
TRACE_RE = re.compile(r"^[0-9a-fA-F]{32}$")
DEPLOYMENT_RE = re.compile(r"^[A-Za-z0-9._-]{1,128}$")
RFC3339_RE = re.compile(r"^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.\d+)?Z$")
CONTROL_RE = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f]")
LEAK_MARKERS = (
    "172.27.49.45",
    ":9428",
    "select/logsql",
    "logsql",
    "bearer ",
    "postgres://",
    "postgresql://",
    "redis://",
    "rediss://",
)

CommandRunner = Callable[[list[str], float], tuple[int, str]]


class _InputError(Exception):
    def __init__(self, status: str, message: str, reason: str) -> None:
        super().__init__(message)
        self.status = status
        self.message = message
        self.reason = reason


def read_logs(
    *,
    environment: str | None,
    since: str | None,
    until: str | None,
    deployment: str | None = None,
    service: str | None = None,
    trace_id: str | None = None,
    level: str | None = None,
    logger_name: str | None = None,
    thread: str | None = None,
    keyword: str | None = None,
    limit: int | None = None,
    run_id: str | None = None,
    binary: str | None = None,
    runner: CommandRunner | None = None,
    now: dt.datetime | None = None,
) -> dict[str, Any]:
    """读取日志行。limit 省略时由日志命令使用自己的默认行数。"""

    return _invoke(
        action="read",
        environment=environment,
        since=since,
        until=until,
        deployment=deployment,
        service=service,
        trace_id=trace_id,
        level=level,
        logger_name=logger_name,
        thread=thread,
        keyword=keyword,
        limit=limit,
        bucket=None,
        field=None,
        run_id=run_id,
        binary=binary,
        runner=runner,
        now=now,
    )


def count_by_time(
    *,
    environment: str | None,
    since: str | None,
    until: str | None,
    bucket: str | None,
    deployment: str | None = None,
    service: str | None = None,
    trace_id: str | None = None,
    level: str | None = None,
    logger_name: str | None = None,
    thread: str | None = None,
    keyword: str | None = None,
    run_id: str | None = None,
    binary: str | None = None,
    runner: CommandRunner | None = None,
    now: dt.datetime | None = None,
) -> dict[str, Any]:
    """按时间桶计数。bucket 取 1m、5m、10m、60m。全是 0 的窗口仍是成功。"""

    return _invoke(
        action="stats",
        environment=environment,
        since=since,
        until=until,
        deployment=deployment,
        service=service,
        trace_id=trace_id,
        level=level,
        logger_name=logger_name,
        thread=thread,
        keyword=keyword,
        limit=None,
        bucket=bucket,
        field=None,
        run_id=run_id,
        binary=binary,
        runner=runner,
        now=now,
    )


def list_field_values(
    *,
    environment: str | None,
    since: str | None,
    until: str | None,
    field: str | None,
    deployment: str | None = None,
    service: str | None = None,
    trace_id: str | None = None,
    level: str | None = None,
    logger_name: str | None = None,
    thread: str | None = None,
    keyword: str | None = None,
    limit: int | None = None,
    run_id: str | None = None,
    binary: str | None = None,
    runner: CommandRunner | None = None,
    now: dt.datetime | None = None,
) -> dict[str, Any]:
    """读取允许字段的取值和数量。超过 100 个时沿用工具返回的 truncated 和 countsComplete，适配模块不自行截取前 100 个。"""

    return _invoke(
        action="values",
        environment=environment,
        since=since,
        until=until,
        deployment=deployment,
        service=service,
        trace_id=trace_id,
        level=level,
        logger_name=logger_name,
        thread=thread,
        keyword=keyword,
        limit=limit,
        bucket=None,
        field=field,
        run_id=run_id,
        binary=binary,
        runner=runner,
        now=now,
    )


def resolve_binary(explicit: str | None) -> str | None:
    if explicit and explicit.strip():
        return explicit.strip()
    from_env = os.environ.get("AF_OBSERVE_BIN", "").strip()
    if from_env:
        return from_env
    if os.path.isfile(INSTALLED_BINARY) and os.access(INSTALLED_BINARY, os.X_OK):
        return INSTALLED_BINARY
    return shutil.which("af-observe")


def _invoke(
    *,
    action: str,
    environment: str | None,
    since: str | None,
    until: str | None,
    deployment: str | None,
    service: str | None,
    trace_id: str | None,
    level: str | None,
    logger_name: str | None,
    thread: str | None,
    keyword: str | None,
    limit: int | None,
    bucket: str | None,
    field: str | None,
    run_id: str | None,
    binary: str | None,
    runner: CommandRunner | None,
    now: dt.datetime | None,
) -> dict[str, Any]:
    association, association_label = _association(trace_id)
    try:
        prepared = _prepare_inputs(
            action=action,
            environment=environment,
            since=since,
            until=until,
            deployment=deployment,
            service=service,
            trace_id=trace_id,
            level=level,
            logger_name=logger_name,
            thread=thread,
            keyword=keyword,
            limit=limit,
            bucket=bucket,
            field=field,
            now=now,
        )
    except _InputError as error:
        return _local_result(
            action=action,
            status=error.status,
            message=error.message,
            reason=error.reason,
            run_id=run_id,
            association=association,
            association_label=association_label,
            environment=environment,
            since=since,
            until=until,
            trace_id=trace_id,
        )
    command_name = resolve_binary(binary)
    if runner is None and not command_name:
        return _local_result(
            action=action,
            status="unavailable",
            message="本机没有可执行的 af-observe",
            reason="command_missing",
            run_id=run_id,
            association=association,
            association_label=association_label,
            environment=prepared["environment"],
            since=since,
            until=until,
            trace_id=prepared["trace_id"],
        )
    argv = _argv(command_name or "af-observe", action, prepared)
    try:
        if runner is None:
            return_code, stdout = _run_process(argv, COMMAND_TIMEOUT_SECONDS)
        else:
            return_code, stdout = runner(argv, COMMAND_TIMEOUT_SECONDS)
    except subprocess.TimeoutExpired:
        return _local_result(
            action=action,
            status="unavailable",
            message="日志命令在限定时间内没有返回",
            reason="backend_unavailable",
            run_id=run_id,
            association=association,
            association_label=association_label,
            environment=prepared["environment"],
            since=since,
            until=until,
            trace_id=prepared["trace_id"],
        )
    except FileNotFoundError:
        return _local_result(
            action=action,
            status="unavailable",
            message="本机没有可执行的 af-observe",
            reason="command_missing",
            run_id=run_id,
            association=association,
            association_label=association_label,
            environment=prepared["environment"],
            since=since,
            until=until,
            trace_id=prepared["trace_id"],
        )
    payload = _parse_stdout(stdout)
    if payload is None:
        return _local_result(
            action=action,
            status="unavailable",
            message="日志命令没有返回可解析的结果",
            reason="invalid_response",
            run_id=run_id,
            association=association,
            association_label=association_label,
            environment=prepared["environment"],
            since=since,
            until=until,
            trace_id=prepared["trace_id"],
        )
    return _from_payload(
        action=action,
        payload=payload,
        return_code=return_code,
        run_id=run_id,
        association=association,
        association_label=association_label,
    )


def _prepare_inputs(
    *,
    action: str,
    environment: str | None,
    since: str | None,
    until: str | None,
    deployment: str | None,
    service: str | None,
    trace_id: str | None,
    level: str | None,
    logger_name: str | None,
    thread: str | None,
    keyword: str | None,
    limit: int | None,
    bucket: str | None,
    field: str | None,
    now: dt.datetime | None,
) -> dict[str, Any]:
    if environment is None or str(environment).strip() == "":
        raise _InputError("rejected", "environment 必填", "missing_environment")
    environment_text = str(environment).strip()
    if environment_text != "beta":
        raise _InputError("unauthorized", "environment 不在允许范围内", "environment_not_allowed")
    since_at = _parse_time(since, "since")
    until_at = _parse_time(until, "until")
    current = now or dt.datetime.now(UTC)
    if until_at <= since_at:
        raise _InputError("rejected", "until 必须晚于 since", "invalid_time_range")
    if (until_at - since_at).total_seconds() > MAX_WINDOW_SECONDS:
        raise _InputError("rejected", "时间窗最长 2 小时", "window_too_large")
    if until_at > current + dt.timedelta(seconds=FUTURE_TOLERANCE_SECONDS):
        raise _InputError("rejected", "until 不能超过当前时间 5 分钟", "future_time")
    deployment_text = _optional_text(deployment)
    if deployment_text and not DEPLOYMENT_RE.fullmatch(deployment_text):
        raise _InputError(
            "rejected",
            "deployment 只允许字母、数字、点、下划线和连字符，最长 128 个字符",
            "invalid_deployment",
        )
    service_text = _optional_text(service)
    if service_text and not SERVICE_RE.fullmatch(service_text):
        raise _InputError("rejected", "service 只允许字母、数字、点、下划线和连字符，最长 128 个字符", "invalid_service")
    trace_text = _optional_text(trace_id)
    if trace_text:
        if not TRACE_RE.fullmatch(trace_text):
            raise _InputError("rejected", "trace_id 必须是 32 位十六进制", "invalid_trace_id")
        trace_text = trace_text.lower()
    level_text = _optional_text(level)
    if level_text:
        level_text = level_text.upper()
        if level_text not in ALLOWED_LEVELS:
            raise _InputError("rejected", "level 只接受 TRACE/DEBUG/INFO/WARN/ERROR/FATAL", "invalid_level")
    logger_text = _bounded_text(_optional_text(logger_name), "logger")
    thread_text = _bounded_text(_optional_text(thread), "thread")
    keyword_text = _keyword(keyword)
    limit_value = _limit_for(action, limit)
    bucket_text = None
    field_text = None
    if action == "stats":
        bucket_text = (bucket or "").strip()
        if bucket_text not in BUCKET_SECONDS:
            raise _InputError("rejected", "bucket 只接受 1m/5m/10m/60m", "invalid_bucket")
        step = BUCKET_SECONDS[bucket_text]
        expected = math.ceil(until_at.timestamp() / step) - math.floor(since_at.timestamp() / step)
        if expected > STATS_MAX_BUCKETS:
            raise _InputError("rejected", f"桶数 {expected} 超过上限 {STATS_MAX_BUCKETS}", "too_many_buckets")
    if action == "values":
        field_text = (field or "").strip()
        if not field_text:
            raise _InputError("rejected", "field 必填", "missing_field")
        if field_text not in VALUE_FIELDS:
            raise _InputError("rejected", "field 不在允许取值范围内", "invalid_field")
    return {
        "environment": environment_text,
        "deployment": deployment_text,
        "service": service_text,
        "trace_id": trace_text,
        "level": level_text,
        "logger": logger_text,
        "thread": thread_text,
        "keyword": keyword_text,
        "since": (since or "").strip(),
        "until": (until or "").strip(),
        "limit": limit_value,
        "bucket": bucket_text,
        "field": field_text,
    }


def _argv(binary: str, action: str, prepared: dict[str, Any]) -> list[str]:
    command = [binary, "logs", action, "--environment", prepared["environment"]]
    _append(command, "--deployment", prepared["deployment"])
    _append(command, "--service", prepared["service"])
    _append(command, "--trace-id", prepared["trace_id"])
    _append(command, "--level", prepared["level"])
    _append(command, "--logger", prepared["logger"])
    _append(command, "--thread", prepared["thread"])
    _append(command, "--keyword", prepared["keyword"])
    command.extend(["--since", prepared["since"], "--until", prepared["until"]])
    if action == "read" and prepared["limit"] is not None:
        command.extend(["--limit", str(prepared["limit"])])
    if action == "stats":
        command.extend(["--bucket", prepared["bucket"]])
    if action == "values":
        command.extend(["--field", prepared["field"]])
        if prepared["limit"] is not None:
            command.extend(["--limit", str(prepared["limit"])])
    return command


def _append(command: list[str], flag: str, value: str | None) -> None:
    if value:
        command.extend([flag, value])


def _run_process(argv: list[str], timeout: float) -> tuple[int, str]:
    completed = subprocess.run(
        argv,
        capture_output=True,
        text=True,
        timeout=timeout,
        check=False,
    )
    return completed.returncode, completed.stdout or ""


def _parse_stdout(stdout: str) -> dict[str, Any] | None:
    text = (stdout or "").strip()
    if not text:
        return None
    try:
        payload = json.loads(text)
    except ValueError:
        payload = None
        for line in reversed(text.splitlines()):
            line = line.strip()
            if not line.startswith("{"):
                continue
            try:
                payload = json.loads(line)
                break
            except ValueError:
                continue
    if not isinstance(payload, dict):
        return None
    return payload


def _from_payload(
    *,
    action: str,
    payload: dict[str, Any],
    return_code: int,
    run_id: str | None,
    association: str,
    association_label: str,
) -> dict[str, Any]:
    request = payload.get("request") if isinstance(payload.get("request"), dict) else {}
    request = dict(request)
    if run_id:
        request["runId"] = run_id
    query_id = payload.get("queryId")
    if payload.get("ok") is True:
        return _success_payload(
            action=action,
            payload=payload,
            request=request,
            query_id=query_id if isinstance(query_id, str) else None,
            association=association,
            association_label=association_label,
            return_code=return_code,
        )
    status = str(payload.get("error_code") or "")
    if status not in {"unauthorized", "unavailable", "rejected"}:
        status = "unavailable"
    reason = payload.get("reason")
    reason_text = reason if isinstance(reason, str) and reason else None
    message = _safe_text(payload.get("error")) or _default_error_message(status)
    return {
        "ok": False,
        "status": status,
        "action": action,
        "count": 0,
        "message": message,
        "reason": reason_text,
        "queryId": query_id if isinstance(query_id, str) else None,
        "request": request,
        "association": association,
        "associationLabel": association_label,
        "rows": [],
        "buckets": [],
        "values": [],
        "truncation": None,
        "countsComplete": None,
        "returnCode": return_code,
    }


def _success_payload(
    *,
    action: str,
    payload: dict[str, Any],
    request: dict[str, Any],
    query_id: str | None,
    association: str,
    association_label: str,
    return_code: int,
) -> dict[str, Any]:
    rows = payload.get("rows") if isinstance(payload.get("rows"), list) else []
    buckets = payload.get("buckets") if isinstance(payload.get("buckets"), list) else []
    values = payload.get("values") if isinstance(payload.get("values"), list) else []
    truncated = bool(payload.get("truncated"))
    truncation = payload.get("truncation") if isinstance(payload.get("truncation"), dict) else None
    if action == "read":
        count = len(rows)
        counts_complete = None
    elif action == "stats":
        count = len(buckets)
        counts_complete = None
        if truncation is None:
            truncation = {"reason": None, "effectiveLimits": None}
    else:
        count = len(values)
        counts_complete = payload.get("countsComplete")
        if not isinstance(counts_complete, bool):
            counts_complete = not truncated
    if truncated:
        status = "truncated"
        message = "截断"
    elif count == 0:
        status = "empty"
        message = "空结果"
    else:
        status = "success"
        message = "成功"
    return {
        "ok": True,
        "status": status,
        "action": action,
        "count": count,
        "message": message,
        "reason": None,
        "queryId": query_id,
        "request": request,
        "association": association,
        "associationLabel": association_label,
        "rows": rows,
        "buckets": buckets,
        "values": values,
        "truncation": truncation,
        "countsComplete": counts_complete,
        "returnCode": return_code,
    }


def _local_result(
    *,
    action: str,
    status: str,
    message: str,
    reason: str,
    run_id: str | None,
    association: str,
    association_label: str,
    environment: str | None,
    since: str | None,
    until: str | None,
    trace_id: str | None,
) -> dict[str, Any]:
    request: dict[str, Any] = {
        "environment": environment,
        "timeRange": {"start": since, "end": until},
    }
    if trace_id:
        request["trace_id"] = trace_id
    if run_id:
        request["runId"] = run_id
    return {
        "ok": status in {"success", "empty", "truncated"},
        "status": status,
        "action": action,
        "count": 0,
        "message": message,
        "reason": reason,
        "queryId": None,
        "request": request,
        "association": association,
        "associationLabel": association_label,
        "rows": [],
        "buckets": [],
        "values": [],
        "truncation": None,
        "countsComplete": None,
        "returnCode": None,
    }


def _association(trace_id: str | None) -> tuple[str, str]:
    if _optional_text(trace_id):
        return "trace-id-filter", "按调用方给出的日志轨迹编号过滤"
    return "time-near-search", "时间接近的搜索"


def _parse_time(raw: str | None, name: str) -> dt.datetime:
    text = (raw or "").strip()
    match = RFC3339_RE.fullmatch(text)
    if not match:
        raise _InputError("rejected", f"{name} 必须是 RFC3339 UTC 时间", "invalid_time")
    try:
        return dt.datetime.strptime(match.group(1), "%Y-%m-%dT%H:%M:%S").replace(tzinfo=UTC)
    except ValueError:
        raise _InputError("rejected", f"{name} 不是合法时间", "invalid_time") from None


def _optional_text(value: str | None) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    return text or None


def _bounded_text(value: str | None, name: str) -> str | None:
    if value is None:
        return None
    text = str(value)
    if text == "":
        return None
    if len(text) > MAX_TEXT_CHARS:
        raise _InputError("rejected", f"{name} 最长 {MAX_TEXT_CHARS} 个字符", "invalid_text")
    if CONTROL_RE.search(text):
        raise _InputError("rejected", f"{name} 含有非法控制字符", "invalid_text")
    return text


def _keyword(value: str | None) -> str | None:
    if value is None:
        return None
    text = str(value)
    if text == "":
        return None
    if len(text) > KEYWORD_MAX_CHARS:
        raise _InputError("rejected", f"keyword 最长 {KEYWORD_MAX_CHARS} 个字符", "invalid_keyword")
    if CONTROL_RE.search(text):
        raise _InputError("rejected", "keyword 含有非法控制字符", "invalid_keyword")
    return text


def _limit_for(action: str, limit: int | None) -> int | None:
    if limit is None:
        return None
    if isinstance(limit, bool) or not isinstance(limit, int):
        raise _InputError("rejected", "limit 必须是整数", "invalid_limit")
    maximum = READ_MAX_ROWS if action == "read" else VALUES_MAX_VALUES
    if limit < 1 or limit > maximum:
        raise _InputError("rejected", f"limit 必须在 1 到 {maximum} 之间", "invalid_limit")
    return limit


def _safe_text(value: Any) -> str | None:
    if not isinstance(value, str) or not value.strip():
        return None
    lowered = value.lower()
    for marker in LEAK_MARKERS:
        if marker.lower() in lowered:
            return None
    return value


def _default_error_message(status: str) -> str:
    if status == "unauthorized":
        return "没有查询权限"
    if status == "rejected":
        return "查询在发出前被拒绝"
    return "日志命令不可用"
