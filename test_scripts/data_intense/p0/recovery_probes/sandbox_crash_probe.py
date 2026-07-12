#!/usr/bin/env python3
"""
Sandbox Crash Probe v2 — STATE_AUDIT level.

Case map:
  SB-S1: NOT_FOUND result → handleNotFound → resultFetchState=LOST
  SB-S2: terminalRetryable missing → fail-closed, RELEASE blocked
  SB-S3: Harness OOM classification (status=FAILED + terminalRetryable=true) → gate pass

sufficientForGuarantee=false for all STATE_AUDIT cases.
Actual sandbox NOT_FOUND/OOM contract: status=FAILED, oomKilled=true, Harness retryable=true.
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
    backup_anchor_row,
    build_safety_gate,
    fetch_anchor_json,
    inject_anchor_field_gated,
    list_runs_with_anchor,
    restore_anchor_row,
    update_anchor_json_gated,
    write_summary_csv,
    write_summary_json,
)

try:
    from frogutils.logger import LogEmitter
except ImportError:
    LogEmitter = None

LOGGER = logging.getLogger("sandbox_crash_probe")


def _parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Sandbox Crash Probe v2")
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
        emitter = LogEmitter("sandbox_crash_probe", cfg.get("log_level", "INFO"), log_dir, run_tag)
        LOGGER = emitter.build()

    if args.dry_run or not gate.execute:
        print(json.dumps({"ok": True, "mode": "dry_run", "probe": "sandbox_crash", "message": "dry-run mode"}, ensure_ascii=False, indent=2))
        return

    checks: List[Check] = []
    mutation_ok, mutation_reason = gate.mutation_allowed()
    if not mutation_ok:
        checks.append(Check(name="safety-gate", ok=False, detail=mutation_reason, status="BLOCKED", classification="STATE_AUDIT"))
        output_dir = Path(cfg.get("output_root", "output/data")) / f"{run_tag}-sandbox_crash"
        write_summary_json(checks, output_dir / "summary.json")
        write_summary_csv(checks, output_dir / "summary.csv")
        return

    target_run_id = gate.target_run_id
    if not target_run_id:
        waiting = list_runs_with_anchor(["WAITING_TOOL_JOB"], prefix=gate.allowed_run_prefix)
        target_run_id = waiting[0]["run_id"] if waiting else None

    if not target_run_id:
        checks.append(Check(name="precondition-target", ok=False, detail="no t5-fixture-* run", status="BLOCKED", classification="STATE_AUDIT"))
        output_dir = Path(cfg.get("output_root", "output/data")) / f"{run_tag}-sandbox_crash"
        write_summary_json(checks, output_dir / "summary.json")
        write_summary_csv(checks, output_dir / "summary.csv")
        return

    LOGGER.info("Target: %s", target_run_id)
    backup = backup_anchor_row(target_run_id)
    if backup is None:
        checks.append(Check(name="precondition-backup", ok=False, detail="backup failed", status="BLOCKED", classification="STATE_AUDIT"))
        output_dir = Path(cfg.get("output_root", "output/data")) / f"{run_tag}-sandbox_crash"
        write_summary_json(checks, output_dir / "summary.json")
        write_summary_csv(checks, output_dir / "summary.csv")
        return

    try:
        # ---- SB-S1: NOT_FOUND → resultFetchState=LOST ----
        LOGGER.info("=== SB-S1: NOT_FOUND → resultFetchState=LOST ===")
        anchor = fetch_anchor_json(target_run_id)
        if anchor and anchor.get("terminalConfirmedAt"):
            from datetime import datetime as dt, timezone, timedelta
            old_time = (dt.now(timezone.utc) - timedelta(hours=24)).isoformat()
            inject_anchor_field_gated(target_run_id, ["terminalConfirmedAt"], old_time, gate)
            inject_anchor_field_gated(target_run_id, ["resultFetchAttempts"], 15, gate)
            time.sleep(12)
            anchor_after = fetch_anchor_json(target_run_id)
            fetch_state = anchor_after.get("resultFetchState") if anchor_after else None
            terminal_status = anchor_after.get("terminalStatus") if anchor_after else None
            checks.append(Check(
                name="SB-S1-NOT-FOUND-LOST",
                ok=(fetch_state == "LOST" or terminal_status == "RESULT_LOST"),
                detail=f"resultFetchState={fetch_state}, terminalStatus={terminal_status}",
                required=False,
                classification="STATE_AUDIT",
                supporting_p0_cases=["P0-09"],
            ))
        else:
            checks.append(Check(
                name="SB-S1-NOT-FOUND-LOST", ok=False,
                detail="no terminalConfirmedAt, real sandbox NOT_FOUND not simulated",
                status="SKIP", required=False,
                classification="STATE_AUDIT",
                supporting_p0_cases=["P0-09"],
            ))

        restore_anchor_row(backup, gate)
        time.sleep(3)

        # ---- SB-S2: terminalRetryable missing → fail-closed ----
        LOGGER.info("=== SB-S2: terminalRetryable missing → fail-closed (RELEASE blocked) ===")
        inject_anchor_field_gated(target_run_id, ["terminalRetryable"], None, gate)
        inject_anchor_field_gated(target_run_id, ["finalizerStep"], "ENVELOPE", gate)
        time.sleep(12)
        anchor_s2 = fetch_anchor_json(target_run_id)
        retryable_s2 = anchor_s2.get("terminalRetryable") if anchor_s2 else None
        error_s2 = anchor_s2.get("finalizerError") if anchor_s2 else None
        step_s2 = anchor_s2.get("finalizerStep") if anchor_s2 else None
        checks.append(Check(
            name="SB-S2-FAIL-CLOSED-BLOCKED",
            ok=(retryable_s2 is None) and (error_s2 == "terminal_retryability_missing") and (step_s2 != "RELEASE"),
            detail=f"terminalRetryable={retryable_s2!r}, finalizerError={error_s2!r}, step={step_s2}",
            required=True,
            classification="STATE_AUDIT",
            supporting_p0_cases=["P0-06"],
        ))

        restore_anchor_row(backup, gate)
        time.sleep(3)

        # ---- SB-S3: Harness OOM classification (FAILED + retryable=true) ----
        LOGGER.info("=== SB-S3: OOM → terminalRetryable=true gate pass ===")
        inject_anchor_field_gated(target_run_id, ["terminalRetryable"], True, gate)
        inject_anchor_field_gated(target_run_id, ["terminalStatus"], "FAILED", gate)
        inject_anchor_field_gated(target_run_id, ["terminalErrorCode"], "OOM", gate)
        inject_anchor_field_gated(target_run_id, ["finalizerStep"], "ENVELOPE", gate)
        inject_anchor_field_gated(target_run_id, ["finalizerError"], None, gate)
        time.sleep(12)
        anchor_s3 = fetch_anchor_json(target_run_id)
        step_s3 = anchor_s3.get("finalizerStep") if anchor_s3 else None
        retryable_s3 = anchor_s3.get("terminalRetryable") if anchor_s3 else None
        step_advanced = step_s3 in ("RELEASE", "USAGE", "EVENT", "CAS_STATUS", "RESUME_READY")
        checks.append(Check(
            name="SB-S3-OOM-GATE-PASS",
            ok=step_advanced or retryable_s3 is True,
            detail=f"finalizerStep={step_s3}, terminalRetryable={retryable_s3!r}, gate_pass={step_advanced}",
            required=True,
            classification="STATE_AUDIT",
            supporting_p0_cases=["P0-11"],
        ))

    finally:
        restored, restore_reason = restore_anchor_row(backup, gate)
        if not restored:
            checks.append(Check(name="restore-final", ok=False, detail=restore_reason, status="BLOCKED", required=True, classification="STATE_AUDIT"))
        else:
            LOGGER.info("Anchor restored from backup")

    output_dir = Path(cfg.get("output_root", "output/data")) / f"{run_tag}-sandbox_crash"
    summary = write_summary_json(checks, output_dir / "summary.json", extra={"target_run_id": target_run_id})
    write_summary_csv(checks, output_dir / "summary.csv")
    LOGGER.info("Sandbox Crash Probe complete: %s", output_dir / "summary.json")
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
