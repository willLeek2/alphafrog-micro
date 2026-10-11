#!/usr/bin/env python3
"""从只读 PostgreSQL 快照导出一轮父子 Run 的持久事实。"""

from __future__ import annotations

import argparse
import json
import os
import sys
from datetime import datetime
from pathlib import Path
from typing import Any


def timestamp(value: Any) -> str | None:
    return value.isoformat() if isinstance(value, datetime) else None


def object_value(value: Any) -> dict[str, Any]:
    if isinstance(value, dict):
        return value
    if isinstance(value, str):
        try:
            decoded = json.loads(value)
            return decoded if isinstance(decoded, dict) else {}
        except json.JSONDecodeError:
            return {}
    return {}


def read_object(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"需要 JSON 对象：{path}")
    return value


def connect_read_only():
    dsn = os.environ.get("AF_STAGE5A_PG_DSN", "").strip()
    if not dsn:
        raise ValueError("缺少 AF_STAGE5A_PG_DSN；无法读取服务端证据")
    try:
        import psycopg2
        from psycopg2.extras import RealDictCursor
    except ImportError as exc:
        raise ValueError("缺少 psycopg2；无法读取服务端证据") from exc
    connection = psycopg2.connect(dsn, cursor_factory=RealDictCursor)
    connection.autocommit = False
    with connection.cursor() as cursor:
        cursor.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY")
        cursor.execute("SET LOCAL statement_timeout = '15s'")
    return connection


def database_identity(cursor) -> dict[str, str]:
    cursor.execute("SELECT current_database() AS db, current_schema() AS schema")
    row = cursor.fetchone()
    return {"database": row["db"], "schema": row["schema"]}


def database_now(cursor) -> str:
    cursor.execute("SELECT clock_timestamp() AS server_time")
    return timestamp(cursor.fetchone()["server_time"]) or ""


def start_window(output: Path) -> dict[str, Any]:
    with connect_read_only() as connection, connection.cursor() as cursor:
        value = {"source": "server_read_only", "schemaVersion": 1,
                 "database": database_identity(cursor), "startedAt": database_now(cursor)}
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return value


def root_from_run_dir(run_dir: Path) -> tuple[str, dict[str, Any]]:
    manifest = read_object(run_dir / "run_manifest.json")
    runs = manifest.get("runs")
    if not isinstance(runs, list) or len(runs) != 1 or not isinstance(runs[0], dict):
        raise ValueError("本次输出目录必须恰有一个根 Run")
    run_id = runs[0].get("runId")
    if not isinstance(run_id, str) or not run_id.strip():
        raise ValueError("run_manifest.json 缺少根 Run ID")
    return run_id, read_object(run_dir / "load_summary.json")


def collect_intents(cursor, root_id: str) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    cursor.execute("""
        SELECT i.id AS intent_id, i.operation_id, o.id AS outbox_id,
               i.tool_call_id, i.parent_node_id, i.child_run_id,
               i.parent_run_id, i.root_run_id, i.state AS intent_state,
               i.accepted_at, o.state AS outbox_state,
               child.status AS child_status, child.completed_at AS child_terminal_at
          FROM alphafrog_agent_run_child_intent i
          JOIN alphafrog_agent_run_child_outbox o ON o.intent_id = i.id
          LEFT JOIN alphafrog_agent_run child ON child.id = i.child_run_id
         WHERE i.root_run_id = %s
         ORDER BY i.created_at, i.id
    """, (root_id,))
    intents: list[dict[str, Any]] = []
    children: list[dict[str, Any]] = []
    for row in cursor.fetchall():
        intents.append({
            "intentId": row["intent_id"], "operationId": row["operation_id"],
            "outboxId": row["outbox_id"], "toolCallId": row["tool_call_id"],
            "parentNodeId": row["parent_node_id"], "childRunId": row["child_run_id"],
            "parentRunId": row["parent_run_id"], "rootRunId": row["root_run_id"],
            "status": row["intent_state"], "acceptedAt": timestamp(row["accepted_at"]),
            "outboxStatus": row["outbox_state"],
        })
        if row["child_status"] is not None:
            children.append({
                "runId": row["child_run_id"], "parentRunId": row["parent_run_id"],
                "rootRunId": row["root_run_id"], "status": row["child_status"],
                "terminalAt": timestamp(row["child_terminal_at"]),
            })
    return intents, children


def wait_results(result_ref: Any) -> list[dict[str, Any]]:
    payload = object_value(result_ref)
    output = object_value(payload.get("output"))
    data = object_value(output.get("data"))
    raw = data.get("results")
    if not isinstance(raw, list):
        return []
    return [{"childRunId": item.get("subAgentId"), "status": item.get("status"),
             "resultText": item.get("result")}
            for item in raw if isinstance(item, dict)]


def collect_waits(cursor, root_id: str) -> list[dict[str, Any]]:
    cursor.execute("""
        SELECT g.id AS group_id, g.run_id AS parent_run_id,
               m.tool_call_id, m.dispatch_proof_json, m.result_ref_json,
               m.created_at, m.finished_at,
               (SELECT n.id FROM alphafrog_agent_run_recovery_notification n
                 WHERE n.group_id = g.id ORDER BY n.id LIMIT 1) AS notification_id
          FROM alphafrog_agent_run_wait_group g
          JOIN alphafrog_agent_run_wait_member m ON m.group_id = g.id
         WHERE g.run_id = %s AND m.tool_name = 'waitForSubAgent'
         ORDER BY m.created_at, m.id
    """, (root_id,))
    rows = []
    for row in cursor.fetchall():
        proof = object_value(row["dispatch_proof_json"])
        rows.append({
            "groupId": row["group_id"], "parentRunId": row["parent_run_id"],
            "toolCallId": row["tool_call_id"],
            "requestedChildRunIds": proof.get("requestedChildRunIds"),
            "results": wait_results(row["result_ref_json"]),
            "startedAt": timestamp(row["created_at"]),
            "completedAt": timestamp(row["finished_at"]),
            "deadlineAt": proof.get("deadlineAt"),
            "resumeNotificationId": row["notification_id"],
        })
    return rows


def collect_events(cursor, run_ids: list[str]) -> list[dict[str, Any]]:
    cursor.execute("""
        SELECT run_id, seq, event_type, created_at
          FROM alphafrog_agent_run_event
         WHERE run_id = ANY(%s)
         ORDER BY run_id, seq
    """, (run_ids,))
    return [{"runId": row["run_id"], "seq": row["seq"],
             "eventType": row["event_type"], "createdAt": timestamp(row["created_at"])}
            for row in cursor.fetchall()]


def collect(run_dir: Path, receipt_file: Path, output: Path) -> dict[str, Any]:
    receipt = read_object(receipt_file)
    if receipt.get("source") != "server_read_only" or not receipt.get("startedAt"):
        raise ValueError("采集起点不是服务端只读时间回执")
    root_id, summary = root_from_run_dir(run_dir)
    with connect_read_only() as connection, connection.cursor() as cursor:
        identity = database_identity(cursor)
        if identity != receipt.get("database"):
            raise ValueError("采集起点与终点来自不同数据库或 schema")
        cursor.execute("SELECT status FROM alphafrog_agent_run WHERE id = %s", (root_id,))
        root = cursor.fetchone()
        if root is None:
            raise ValueError("服务端数据库找不到本次根 Run")
        intents, children = collect_intents(cursor, root_id)
        waits = collect_waits(cursor, root_id)
        events = collect_events(cursor, [root_id, *[row["runId"] for row in children]])
        ended_at = database_now(cursor)
    started_at = receipt["startedAt"]
    client_start = summary.get("started_at")
    client_end = summary.get("ended_at")
    times = [datetime.fromisoformat(str(value).replace("Z", "+00:00"))
             for value in (started_at, client_start, client_end, ended_at)]
    if any(value.tzinfo is None for value in times) or not times[0] <= times[1] <= times[2] <= times[3]:
        raise ValueError("服务端采集窗口未覆盖无界面测试的完整时间窗")
    payload = {
        "source": "server_read_only", "schemaVersion": 1,
        "complete": root["status"] in ("COMPLETED", "PARTIAL", "FAILED", "CANCELED", "EXPIRED"),
        "rootRunId": root_id, "window": {"startedAt": started_at, "endedAt": ended_at},
        "database": identity,
        "childRuns": children, "creationIntents": intents,
        "waitGroups": waits, "runEvents": events,
        "unavailableEvidence": ["externalResultControl", "parentWaitPermitSamples"],
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return payload


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    begin = sub.add_parser("start", help="在无界面测试前读取数据库时钟")
    begin.add_argument("--output", required=True, type=Path)
    finish = sub.add_parser("collect", help="在无界面测试后采集父子持久事实")
    finish.add_argument("--run-dir", required=True, type=Path)
    finish.add_argument("--window-receipt", required=True, type=Path)
    finish.add_argument("--output", required=True, type=Path)
    args = parser.parse_args(argv)
    try:
        if args.command == "start":
            result = start_window(args.output)
        else:
            result = collect(args.run_dir, args.window_receipt, args.output)
    except (OSError, ValueError) as exc:
        print(json.dumps({"status": "evidence_unavailable", "reason": str(exc)}, ensure_ascii=False))
        return 2
    except Exception as exc:
        # 不打印连接异常正文：驱动可能把 DSN 放进错误消息。
        reason = ("数据库缺少父子运行取证表或字段" if getattr(exc, "pgcode", None)
                  in ("42P01", "42703") else type(exc).__name__)
        print(json.dumps({"status": "evidence_unavailable", "reason": reason},
                         ensure_ascii=False))
        return 2
    print(json.dumps({"status": "collected", "output": str(args.output),
                      "rootRunId": result.get("rootRunId")}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
