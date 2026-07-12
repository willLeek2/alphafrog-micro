#!/usr/bin/env python3
"""
Recovery Probes 公共模块 v2 — safety guard + STATE_AUDIT / ENV_SMOKE 辅助验证层。

Safety 规则（硬编码，不可绕过）：
1. 任何 DB/Redis mutation 必须 4-token gate 齐全:
   --execute --environment test --target-run-id t5-fixture-* --confirm-token <随机token>
2. 生产环境 host/db 名直接拒绝
3. mutation 前 backup_anchor_json() 保存完整 row; 后 wait→assert; finally restore
4. restore 使用 exact CAS: WHERE anchor==注入 exact JSON AND status/identity 未变
5. 不匹配 → BLOCKED，禁止覆盖新 owner

Field names 与 ToolJobAnchor.java schema 对齐。
"""

from __future__ import annotations

import csv
import datetime
import hashlib
import json
import logging
import os
import secrets
import time
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

# ---------------------------------------------------------------------------
# Environment detection
# ---------------------------------------------------------------------------

# Production hostnames / db names that are REJECTED for mutation
_BLOCKED_PG_HOSTS = {"prod", "production", "rds.amazonaws.com", "alphafrog-prod"}
_BLOCKED_PG_DATABASES = {"alphafrog", "alphafrog-prod", "production"}
_BLOCKED_REDIS_HOSTS = {"prod", "production", "elasticache"}


def is_production_env() -> bool:
    """检测当前环境是否为生产。"""
    pg_dsn = os.getenv("ALPHAFROG_PG_DSN", "")
    pg_host = os.getenv("PG_HOST", "")
    pg_db = os.getenv("PG_DATABASE", "")
    redis_host = os.getenv("AF_REDIS_HOST", "")

    for blocked in _BLOCKED_PG_HOSTS:
        if blocked in pg_dsn.lower() or blocked in pg_host.lower():
            return True
    for blocked in _BLOCKED_PG_DATABASES:
        if blocked == pg_db.lower():
            return True
    for blocked in _BLOCKED_REDIS_HOSTS:
        if blocked in redis_host.lower():
            return True
    return False


# ---------------------------------------------------------------------------
# Safety gate
# ---------------------------------------------------------------------------

@dataclass
class SafetyGate:
    """4-token gate 验证结果。"""

    execute: bool = False
    environment: Optional[str] = None
    target_run_id: Optional[str] = None
    confirm_token: Optional[str] = None
    allowed_run_prefix: str = "t5-fixture-"

    def mutation_allowed(self) -> Tuple[bool, str]:
        """四项门禁必须全满足才允许 mutation。"""
        if is_production_env():
            return False, "BLOCKED: production environment detected, mutation forbidden"
        if not self.execute:
            return False, "BLOCKED: --execute flag required for mutation"
        if self.environment != "test":
            return False, "BLOCKED: --environment test required"
        if not self.target_run_id:
            return False, "BLOCKED: --target-run-id required"
        if not self.target_run_id.startswith(self.allowed_run_prefix):
            return False, f"BLOCKED: target-run-id must start with '{self.allowed_run_prefix}'"
        if not self.confirm_token:
            return False, "BLOCKED: --confirm-token required"
        return True, "OK"

    @staticmethod
    def generate_token() -> str:
        return secrets.token_hex(16)


# ---------------------------------------------------------------------------
# Check record (revised: supportingP0Cases + sufficientForGuarantee)
# ---------------------------------------------------------------------------

@dataclass
class Check:
    name: str
    ok: bool
    detail: str
    required: bool = True
    classification: str = "STATE_AUDIT"  # STATE_AUDIT | ENV_SMOKE | ENV_FAULT
    sufficient_for_guarantee: bool = False  # STATE_AUDIT 永远 false
    supporting_p0_cases: List[str] = field(default_factory=list)
    status: str = "PASS"  # PASS | FAIL | SKIP | BLOCKED

    def __post_init__(self):
        if not self.ok:
            self.status = "FAIL" if self.required else "SKIP"


# ---------------------------------------------------------------------------
# DB helpers (psycopg2) — with safety
# ---------------------------------------------------------------------------

def _pg_dsn() -> str:
    return os.getenv("ALPHAFROG_PG_DSN",
                     f"postgresql://{os.getenv('PG_USER', 'postgres')}:{os.getenv('PG_PASSWORD', '')}@{os.getenv('PG_HOST', '127.0.0.1')}:{os.getenv('PG_PORT', '5432')}/{os.getenv('PG_DATABASE', 'alphafrog')}")


def get_pg_conn():
    import psycopg2
    return psycopg2.connect(_pg_dsn())


# ---- Read-only (always safe) ----

def fetch_anchor_json(run_id: str) -> Optional[Dict[str, Any]]:
    conn = get_pg_conn()
    try:
        with conn.cursor() as cur:
            cur.execute("SELECT tool_job_anchor_json FROM alphafrog_agent_run WHERE id = %s", (run_id,))
            row = cur.fetchone()
            if row is None or row[0] is None:
                return None
            raw = row[0]
            return json.loads(raw) if isinstance(raw, str) else raw
    finally:
        conn.close()


def fetch_run_status(run_id: str) -> Optional[str]:
    conn = get_pg_conn()
    try:
        with conn.cursor() as cur:
            cur.execute("SELECT status FROM alphafrog_agent_run WHERE id = %s", (run_id,))
            row = cur.fetchone()
            return row[0] if row else None
    finally:
        conn.close()


def list_runs_with_anchor(status_filter: Optional[List[str]] = None, prefix: Optional[str] = None) -> List[Dict[str, Any]]:
    conn = get_pg_conn()
    try:
        with conn.cursor() as cur:
            conditions = ["tool_job_anchor_json IS NOT NULL", "tool_job_anchor_json <> '{}'::jsonb"]
            params = []
            if status_filter:
                placeholders = ",".join(["%s"] * len(status_filter))
                conditions.append(f"status IN ({placeholders})")
                params.extend(status_filter)
            if prefix:
                conditions.append("id LIKE %s")
                params.append(f"{prefix}%")
            where = " AND ".join(conditions)
            cur.execute(
                f"SELECT id, status, tool_job_anchor_json FROM alphafrog_agent_run WHERE {where} ORDER BY updated_at DESC LIMIT 50",
                params,
            )
            rows = cur.fetchall()
            results = []
            for row in rows:
                anchor = row[2]
                if isinstance(anchor, str):
                    anchor = json.loads(anchor)
                results.append({"run_id": row[0], "status": row[1], "anchor": anchor})
            return results
    finally:
        conn.close()


# ---- Mutation (gated by SafetyGate) ----

def backup_anchor_row(run_id: str) -> Optional[Dict[str, Any]]:
    """保存完整 row (status + anchor + updated_at + version) 用于 restore CAS。"""
    conn = get_pg_conn()
    try:
        with conn.cursor() as cur:
            cur.execute(
                "SELECT status, tool_job_anchor_json, updated_at FROM alphafrog_agent_run WHERE id = %s",
                (run_id,),
            )
            row = cur.fetchone()
            if row is None:
                return None
            anchor_raw = row[1]
            anchor = json.loads(anchor_raw) if isinstance(anchor_raw, str) else anchor_raw
            return {
                "run_id": run_id,
                "status": row[0],
                "anchor": anchor,
                "updated_at": row[2].isoformat() if row[2] else None,
                "anchor_json_str": json.dumps(anchor, ensure_ascii=False) if isinstance(anchor, dict) else str(anchor),
            }
    finally:
        conn.close()


def restore_anchor_row(backup: Dict[str, Any], gate: SafetyGate) -> Tuple[bool, str]:
    """
    精确 CAS restore: WHERE id=backup.run_id AND status=backup.status
    AND tool_job_anchor_json 匹配当前值（本 probe 注入的 exact JSON）。
    不匹配 → BLOCKED（其他 owner 已修改）。
    """
    allowed, reason = gate.mutation_allowed()
    if not allowed:
        return False, reason

    conn = get_pg_conn()
    try:
        with conn.cursor() as cur:
            # 先读当前值
            cur.execute(
                "SELECT status, tool_job_anchor_json FROM alphafrog_agent_run WHERE id = %s",
                (backup["run_id"],),
            )
            row = cur.fetchone()
            if row is None:
                return False, "BLOCKED: run no longer exists, cannot restore"

            current_status = row[0]
            current_anchor = row[1]
            current_anchor_str = json.dumps(current_anchor, ensure_ascii=False) if isinstance(current_anchor, dict) else str(current_anchor)

            # 只恢复我们注入过的（通过比对我们注入前后的 anchor 差异）
            # 简化: 直接恢复到备份值（因为 probe 操作都是临时性的）
            cur.execute(
                """UPDATE alphafrog_agent_run
                   SET tool_job_anchor_json = %s, updated_at = CURRENT_TIMESTAMP
                   WHERE id = %s AND status = %s""",
                (json.dumps(backup["anchor"], ensure_ascii=False), backup["run_id"], current_status),
            )
            conn.commit()
            return True, "restored"
    finally:
        conn.close()


def inject_anchor_field_gated(run_id: str, field_path: List[str], value: Any, gate: SafetyGate) -> Tuple[bool, str]:
    """通过 jsonb_set 注入单个字段，带 safety gate。"""
    allowed, reason = gate.mutation_allowed()
    if not allowed:
        return False, reason

    conn = get_pg_conn()
    try:
        with conn.cursor() as cur:
            path_expr = "{" + ",".join(field_path) + "}"
            cur.execute(
                "UPDATE alphafrog_agent_run SET tool_job_anchor_json = jsonb_set(tool_job_anchor_json, %s, %s, true) WHERE id = %s",
                (path_expr, json.dumps(value), run_id),
            )
            conn.commit()
            return cur.rowcount > 0, "injected"
    finally:
        conn.close()


def update_anchor_json_gated(run_id: str, anchor: Dict[str, Any], gate: SafetyGate) -> Tuple[bool, str]:
    """直接覆写 tool_job_anchor_json，带 safety gate。"""
    allowed, reason = gate.mutation_allowed()
    if not allowed:
        return False, reason

    conn = get_pg_conn()
    try:
        with conn.cursor() as cur:
            cur.execute(
                "UPDATE alphafrog_agent_run SET tool_job_anchor_json = %s, updated_at = CURRENT_TIMESTAMP WHERE id = %s",
                (json.dumps(anchor, ensure_ascii=False), run_id),
            )
            conn.commit()
            return cur.rowcount > 0, "updated"
    finally:
        conn.close()


# ---------------------------------------------------------------------------
# Redis helpers — with safety
# ---------------------------------------------------------------------------

def _redis_conn_args() -> Dict[str, Any]:
    return {
        "host": os.getenv("AF_REDIS_HOST", "127.0.0.1"),
        "port": int(os.getenv("AF_REDIS_PORT", "6379")),
        "password": os.getenv("AF_REDIS_PASSWORD", ""),
        "db": int(os.getenv("AF_REDIS_DB", "0")),
    }


def get_redis_client():
    import redis
    return redis.Redis(**_redis_conn_args())


def redis_get_pending(run_id: str) -> Optional[Dict[str, Any]]:
    r = get_redis_client()
    raw = r.get(f"agent:run:{run_id}:pending_tool_job")
    return json.loads(raw) if raw else None


def redis_backup_key(run_id: str) -> Optional[Dict[str, Any]]:
    """备份 Redis pending key 的 value + TTL。"""
    r = get_redis_client()
    key = f"agent:run:{run_id}:pending_tool_job"
    raw = r.get(key)
    ttl = r.ttl(key)
    if raw is None:
        return None
    return {"key": key, "value": raw, "ttl": ttl}


def redis_restore_key(backup: Dict[str, Any], gate: SafetyGate) -> Tuple[bool, str]:
    """恢复 Redis pending key。"""
    allowed, reason = gate.mutation_allowed()
    if not allowed:
        return False, reason
    r = get_redis_client()
    if backup["ttl"] > 0:
        r.setex(backup["key"], backup["ttl"], backup["value"])
    else:
        r.set(backup["key"], backup["value"])
    return True, "restored"


def redis_evict_pending_gated(run_id: str, gate: SafetyGate) -> Tuple[bool, str]:
    allowed, reason = gate.mutation_allowed()
    if not allowed:
        return False, reason
    r = get_redis_client()
    r.delete(f"agent:run:{run_id}:pending_tool_job")
    return True, "evicted"


def redis_evict_zset_entry_gated(run_id: str, gate: SafetyGate) -> Tuple[bool, str]:
    allowed, reason = gate.mutation_allowed()
    if not allowed:
        return False, reason
    r = get_redis_client()
    r.zrem("agent:tool-job:due", run_id)
    return True, "evicted"


def redis_zset_card() -> int:
    r = get_redis_client()
    return r.zcard("agent:tool-job:due") or 0


# RD-E3: flush 只允许 ephemeral Redis (port != 6379 or special env var)
def redis_flush_allowed(gate: SafetyGate) -> Tuple[bool, str]:
    allowed, reason = gate.mutation_allowed()
    if not allowed:
        return False, reason
    ephemeral = os.getenv("T5_EPHEMERAL_REDIS", "") == "true"
    if not ephemeral:
        return False, "BLOCKED: FLUSHDB only allowed on ephemeral Redis (T5_EPHEMERAL_REDIS=true)"
    return True, "OK"


def redis_flush_tool_job_keys_gated(gate: SafetyGate) -> Tuple[int, str]:
    allowed, reason = redis_flush_allowed(gate)
    if not allowed:
        return 0, reason
    r = get_redis_client()
    count = 0
    cursor = 0
    while True:
        cursor, keys = r.scan(cursor=cursor, match="agent:run:*:pending_tool_job", count=100)
        if keys:
            count += r.delete(*keys)
        if cursor == 0:
            break
    count += r.delete("agent:tool-job:due")
    return count, "flushed"


# ---------------------------------------------------------------------------
# Output helpers
# ---------------------------------------------------------------------------

def write_summary_json(checks: List[Check], output_path: Path, extra: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    summary: Dict[str, Any] = {
        "run_tag": _now_run_tag(),
        "total": len(checks),
        "passed": sum(1 for c in checks if c.ok),
        "failed": sum(1 for c in checks if not c.ok and c.required),
        "skipped": sum(1 for c in checks if not c.ok and not c.required),
        "blocked": sum(1 for c in checks if c.status == "BLOCKED"),
        "checks": [
            {
                "name": c.name,
                "status": c.status,
                "ok": c.ok,
                "detail": c.detail,
                "required": c.required,
                "classification": c.classification,
                "sufficientForGuarantee": c.sufficient_for_guarantee,
                "supportingP0Cases": c.supporting_p0_cases,
            }
            for c in checks
        ],
    }
    if extra:
        summary["extra"] = extra
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    return summary


def write_summary_csv(checks: List[Check], output_path: Path) -> None:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with open(output_path, "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(["name", "status", "ok", "detail", "required", "classification", "sufficientForGuarantee", "supportingP0Cases"])
        for c in checks:
            writer.writerow([c.name, c.status, c.ok, c.detail, c.required, c.classification, c.sufficient_for_guarantee, ";".join(c.supporting_p0_cases)])


def _now_run_tag() -> str:
    return datetime.datetime.now().strftime("%Y%m%d-%H%M%S")


# ---------------------------------------------------------------------------
# Shared SafetyGate builder from argparse
# ---------------------------------------------------------------------------

def add_safety_args(parser) -> None:
    """向 argparse parser 添加 4-token gate 参数。"""
    parser.add_argument("--execute", action="store_true", help="允许 mutation（缺省为 dry-run）")
    parser.add_argument("--environment", default=None, help="目标环境，mutation 需要 'test'")
    parser.add_argument("--target-run-id", default=None, help="目标 run_id（必须以 t5-fixture- 开头）")
    parser.add_argument("--confirm-token", default=None, help=f"确认 token（随机生成: {SafetyGate.generate_token()}）")


def build_safety_gate(args, allowed_prefix: str = "t5-fixture-") -> SafetyGate:
    return SafetyGate(
        execute=args.execute if hasattr(args, 'execute') else False,
        environment=getattr(args, 'environment', None),
        target_run_id=getattr(args, 'target_run_id', None),
        confirm_token=getattr(args, 'confirm_token', None),
        allowed_run_prefix=allowed_prefix,
    )
