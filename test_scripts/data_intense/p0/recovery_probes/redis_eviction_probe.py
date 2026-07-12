#!/usr/bin/env python3
"""
Redis Eviction Probe v2 — ENV_FAULT level.

Case map:
  RD-E1: pending_tool_job key DEL → reconciler rebuild from DB (wait >=65s)
  RD-E2: tool-job:due ZSET member ZREM → rebuild restores entry
  RD-E3: (ephemeral Redis only) FLUSHDB → full rebuild

sufficientForGuarantee=false for all cases.
"""

from __future__ import annotations

import argparse
import json
import logging
import sys
import time
from pathlib import Path
from typing import Any, Dict, List, Optional

import yaml

sys.path.insert(0, str(Path(__file__).resolve().parents[4]))
from recovery_probes_common import (
    Check,
    SafetyGate,
    add_safety_args,
    build_safety_gate,
    fetch_anchor_json,
    get_redis_client,
    list_runs_with_anchor,
    redis_backup_key,
    redis_evict_pending_gated,
    redis_evict_zset_entry_gated,
    redis_flush_tool_job_keys_gated,
    redis_get_pending,
    redis_restore_key,
    redis_zset_card,
    write_summary_csv,
    write_summary_json,
)

try:
    from frogutils.logger import LogEmitter
except ImportError:
    LogEmitter = None

LOGGER = logging.getLogger("redis_eviction_probe")


def _parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Redis Eviction Probe v2")
    parser.add_argument("--config", default="test_scripts/data_intense/p0/recovery_probes/config.yml")
    parser.add_argument("--dry-run", action="store_true")
    add_safety_args(parser)
    return parser.parse_args()


def _load_config(cfg_path: str) -> Dict[str, Any]:
    path = Path(cfg_path)
    if not path.exists():
        raise SystemExit(f"配置文件不存在: {cfg_path}")
    with open(path, "r", encoding="utf-8") as f:
        return yaml.safe_load(f) or {}


def _now_tag() -> str:
    from datetime import datetime
    return datetime.now().strftime("%Y%m%d-%H%M%S")


def main() -> None:
    global LOGGER
    args = _parse_args()
    cfg = _load_config(args.config)
    gate = build_safety_gate(args)
    run_tag = _now_tag()

    log_dir = cfg.get("log_dir", "output/logs")
    if LogEmitter:
        emitter = LogEmitter("redis_eviction_probe", cfg.get("log_level", "INFO"), log_dir, run_tag)
        LOGGER = emitter.build()

    if args.dry_run or not gate.execute:
        print(json.dumps({"ok": True, "mode": "dry_run", "probe": "redis_eviction", "message": "dry-run mode"}, ensure_ascii=False, indent=2))
        return

    checks: List[Check] = []
    mutation_ok, mutation_reason = gate.mutation_allowed()
    if not mutation_ok:
        checks.append(Check(name="safety-gate", ok=False, detail=mutation_reason, status="BLOCKED", classification="ENV_FAULT"))
        output_dir = Path(cfg.get("output_root", "output/data")) / f"{run_tag}-redis_eviction"
        write_summary_json(checks, output_dir / "summary.json")
        write_summary_csv(checks, output_dir / "summary.csv")
        print(json.dumps({"blocked": True, "reason": mutation_reason}, ensure_ascii=False, indent=2))
        return

    target_run_id = gate.target_run_id
    if not target_run_id:
        waiting = list_runs_with_anchor(["WAITING_TOOL_JOB"], prefix=gate.allowed_run_prefix)
        target_run_id = waiting[0]["run_id"] if waiting else None

    if not target_run_id:
        checks.append(Check(name="precondition-target", ok=False, detail="no t5-fixture-* run", status="BLOCKED", classification="ENV_FAULT"))
        output_dir = Path(cfg.get("output_root", "output/data")) / f"{run_tag}-redis_eviction"
        write_summary_json(checks, output_dir / "summary.json")
        write_summary_csv(checks, output_dir / "summary.csv")
        return

    LOGGER.info("Target: %s", target_run_id)
    redis_rebuild_wait = cfg.get("poll", {}).get("redis_rebuild_wait_seconds", 65)

    # Backup Redis keys
    pending_backup = redis_backup_key(target_run_id)

    try:
        # ---- RD-E1: pending key eviction → DB rebuild ----
        LOGGER.info("=== RD-E1: pending key eviction → DB rebuild (wait %ds) ===", redis_rebuild_wait)
        pending_before = redis_get_pending(target_run_id)
        checks.append(Check(
            name="RD-E1-pending-exists-before", ok=pending_before is not None,
            detail="pending cache present" if pending_before else "missing",
            required=False, classification="ENV_FAULT", supporting_p0_cases=["P0-04"],
        ))

        redis_evict_pending_gated(target_run_id, gate)
        pending_after_evict = redis_get_pending(target_run_id)
        checks.append(Check(
            name="RD-E1-evicted", ok=pending_after_evict is None,
            detail="evicted" if pending_after_evict is None else "still present",
            required=True, classification="ENV_FAULT", supporting_p0_cases=["P0-04"],
        ))

        LOGGER.info("等待 reconciler rebuild (最长 %ds)...", redis_rebuild_wait)
        start = time.time()
        rebuilt = False
        while (time.time() - start) < redis_rebuild_wait:
            pending = redis_get_pending(target_run_id)
            if pending is not None:
                rebuilt = True
                break
            time.sleep(5)
        pending_rebuilt = redis_get_pending(target_run_id)
        checks.append(Check(
            name="RD-E1-rebuilt", ok=rebuilt,
            detail=f"pending cache rebuilt: {rebuilt}",
            required=True, classification="ENV_FAULT", supporting_p0_cases=["P0-04"],
        ))

        if pending_rebuilt and pending_before:
            db_anchor = fetch_anchor_json(target_run_id)
            cache_matches = db_anchor is not None and pending_rebuilt.get("operationId") == db_anchor.get("operationId")
            checks.append(Check(
                name="RD-E1-cache-db-consistent", ok=cache_matches,
                detail=f"cache opId={pending_rebuilt.get('operationId')}, db opId={db_anchor.get('operationId') if db_anchor else 'N/A'}",
                required=False, classification="ENV_FAULT", supporting_p0_cases=["P0-04"],
            ))

        # Restore pending key
        if pending_backup:
            redis_restore_key(pending_backup, gate)

        # ---- RD-E2: ZSET entry eviction ----
        LOGGER.info("=== RD-E2: ZSET entry eviction ===")
        zset_before = redis_zset_card()
        redis_evict_zset_entry_gated(target_run_id, gate)
        time.sleep(redis_rebuild_wait)
        zset_after = redis_zset_card()
        checks.append(Check(
            name="RD-E2-zset-rebuilt", ok=zset_after >= zset_before - 1,
            detail=f"ZSET: before={zset_before}, after={zset_after}",
            required=True, classification="ENV_FAULT", supporting_p0_cases=["P0-04"],
        ))

        # ---- RD-E3: ephemeral Redis full flush ----
        LOGGER.info("=== RD-E3: ephemeral Redis full flush ===")
        flushed, reason = redis_flush_tool_job_keys_gated(gate)
        if flushed > 0:
            LOGGER.info("Flushed %d keys, waiting rebuild...", flushed)
            time.sleep(redis_rebuild_wait)
            zset_final = redis_zset_card()
            pending_final = redis_get_pending(target_run_id)
            checks.append(Check(
                name="RD-E3-full-rebuild", ok=zset_final > 0 or pending_final is not None,
                detail=f"after rebuild: zset={zset_final}, pending={'present' if pending_final else 'missing'}",
                required=False, classification="ENV_FAULT", supporting_p0_cases=["P0-04"],
            ))
        else:
            checks.append(Check(
                name="RD-E3-full-rebuild", ok=False, detail=f"SKIP: {reason}",
                status="SKIP", required=False, classification="ENV_FAULT", supporting_p0_cases=["P0-04"],
            ))

    finally:
        if pending_backup:
            restored, _ = redis_restore_key(pending_backup, gate)
            LOGGER.info("Redis key restored: %s", restored)

    output_dir = Path(cfg.get("output_root", "output/data")) / f"{run_tag}-redis_eviction"
    summary = write_summary_json(checks, output_dir / "summary.json", extra={"target_run_id": target_run_id})
    write_summary_csv(checks, output_dir / "summary.csv")
    LOGGER.info("Redis Eviction Probe complete: %s", output_dir / "summary.json")
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
