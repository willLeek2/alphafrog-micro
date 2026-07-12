#!/usr/bin/env python3
"""
PostgreSQL Anchor Fault Probe v2 — STATE_AUDIT level.

Case map:
  PG-A1: ROBUSTNESS-INVALID-ANCHOR (non-required) — anchor JSON corrupt → reconciler no panic
  PG-A2: terminalRetryable missing + ENVELOPE → fail-closed gate → finalizerError
  PG-A3: finalizerStep rewind (non-required) — robustness check only
  PG-A4: reservationJson PENDING→RELEASED rewind → ALREADY_RELEASED idempotent

sufficientForGuarantee=false for all STATE_AUDIT cases.
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
    fetch_run_status,
    get_pg_conn,
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

LOGGER = logging.getLogger("anchor_fault_probe")


def _parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="PostgreSQL Anchor Fault Probe v2")
    parser.add_argument("--config", default="test_scripts/data_intense/p0/recovery_probes/config.yml")
    parser.add_argument("--dry-run", action="store_true", help="仅校验配置，不执行任何操作")
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
        emitter = LogEmitter("anchor_fault_probe", cfg.get("log_level", "INFO"), log_dir, run_tag)
        LOGGER = emitter.build()

    if args.dry_run or not gate.execute:
        print(json.dumps({"ok": True, "mode": "dry_run", "probe": "postgresql_anchor_fault", "message": "dry-run mode, no mutations performed"}, ensure_ascii=False, indent=2))
        return

    checks: List[Check] = []

    # Validate mutation gate
    mutation_ok, mutation_reason = gate.mutation_allowed()
    if not mutation_ok:
        checks.append(Check(name="safety-gate", ok=False, detail=mutation_reason, status="BLOCKED", classification="STATE_AUDIT"))
        output_dir = Path(cfg.get("output_root", "output/data")) / f"{run_tag}-anchor_fault"
        write_summary_json(checks, output_dir / "summary.json")
        write_summary_csv(checks, output_dir / "summary.csv")
        print(json.dumps({"blocked": True, "reason": mutation_reason}, ensure_ascii=False, indent=2))
        return

    target_run_id = gate.target_run_id
    if not target_run_id:
        waiting = list_runs_with_anchor(["WAITING_TOOL_JOB"], prefix=gate.allowed_run_prefix)
        target_run_id = waiting[0]["run_id"] if waiting else None

    if not target_run_id:
        checks.append(Check(name="precondition-target", ok=False, detail="no matching t5-fixture-* run found", status="BLOCKED", classification="STATE_AUDIT"))
        output_dir = Path(cfg.get("output_root", "output/data")) / f"{run_tag}-anchor_fault"
        write_summary_json(checks, output_dir / "summary.json")
        write_summary_csv(checks, output_dir / "summary.csv")
        return

    LOGGER.info("Target: %s", target_run_id)

    # Backup full row before any mutation
    backup = backup_anchor_row(target_run_id)
    if backup is None:
        checks.append(Check(name="precondition-backup", ok=False, detail="failed to backup anchor row", status="BLOCKED", classification="STATE_AUDIT"))
        output_dir = Path(cfg.get("output_root", "output/data")) / f"{run_tag}-anchor_fault"
        write_summary_json(checks, output_dir / "summary.json")
        write_summary_csv(checks, output_dir / "summary.csv")
        return

    try:
        # ---- PG-A1: ROBUSTNESS-INVALID-ANCHOR (non-required) ----
        LOGGER.info("=== PG-A1: INVALID anchor → reconciler no panic ===")
        corrupt_anchor = {"schemaVersion": 1, "operationId": "corrupted", "anchorState": "INVALID_STATE"}
        ok, _ = update_anchor_json_gated(target_run_id, corrupt_anchor, gate)
        if ok:
            time.sleep(12)
            status = fetch_run_status(target_run_id)
            valid_statuses = {"RECEIVED", "PLANNING", "EXECUTING", "WAITING", "WAITING_TOOL_JOB",
                              "SUMMARIZING", "COMPLETED", "PARTIAL", "FAILED", "CANCELING", "CANCELED", "EXPIRED"}
            checks.append(Check(
                name="PG-A1-INVALID-ANCHOR",
                ok=status in valid_statuses,
                detail=f"status after corrupt anchor: {status}",
                required=False,
                classification="STATE_AUDIT",
                supporting_p0_cases=["none/supporting-only"],
            ))
        else:
            checks.append(Check(name="PG-A1-INVALID-ANCHOR", ok=False, detail=ok, status="BLOCKED", required=False, classification="STATE_AUDIT"))

        # Restore from backup before next test
        restore_anchor_row(backup, gate)
        time.sleep(3)

        # ---- PG-A2: terminalRetryable missing → fail-closed ----
        LOGGER.info("=== PG-A2: terminalRetryable missing → fail-closed gate ===")
        anchor = fetch_anchor_json(target_run_id)
        if anchor and anchor.get("terminalStatus"):
            inject_anchor_field_gated(target_run_id, ["terminalRetryable"], None, gate)
            inject_anchor_field_gated(target_run_id, ["finalizerStep"], "ENVELOPE", gate)
            time.sleep(12)
            anchor_after = fetch_anchor_json(target_run_id)
            finalizer_error = anchor_after.get("finalizerError") if anchor_after else None
            checks.append(Check(
                name="PG-A2-FAIL-CLOSED",
                ok=finalizer_error == "terminal_retryability_missing",
                detail=f"finalizerError={finalizer_error!r}",
                required=True,
                classification="STATE_AUDIT",
                supporting_p0_cases=["P0-06"],
            ))
        else:
            checks.append(Check(
                name="PG-A2-FAIL-CLOSED",
                ok=False, detail="anchor missing terminalStatus, cannot trigger fail-closed",
                status="SKIP", required=False,
                classification="STATE_AUDIT",
                supporting_p0_cases=["P0-06"],
            ))

        restore_anchor_row(backup, gate)
        time.sleep(3)

        # ---- PG-A3: finalizerStep rewind robustness (non-required) ----
        LOGGER.info("=== PG-A3: finalizerStep rewind robustness ===")
        anchor_f3 = fetch_anchor_json(target_run_id)
        if anchor_f3 and anchor_f3.get("finalizerStep") in ("RELEASE", "USAGE", "EVENT", "CAS_STATUS", "RESUME_READY"):
            inject_anchor_field_gated(target_run_id, ["finalizerStep"], "ENVELOPE", gate)
            time.sleep(12)
            anchor_after_f3 = fetch_anchor_json(target_run_id)
            step_after = anchor_after_f3.get("finalizerStep") if anchor_after_f3 else None
            step_order = {"ENVELOPE": 0, "RELEASE": 1, "USAGE": 2, "EVENT": 3, "CAS_STATUS": 4, "RESUME_READY": 5}
            checks.append(Check(
                name="PG-A3-REWIND-ROBUSTNESS",
                ok=step_after and step_order.get(step_after, -1) >= step_order.get("RELEASE", 1),
                detail=f"step after rewind: {step_after}, monotonic forward: {step_order.get(step_after, -1) >= step_order.get('RELEASE', 1)}",
                required=False,
                classification="STATE_AUDIT",
                supporting_p0_cases=["P0-01", "P0-06"],
            ))
        else:
            checks.append(Check(
                name="PG-A3-REWIND-ROBUSTNESS", ok=False,
                detail=f"finalizerStep={anchor_f3.get('finalizerStep') if anchor_f3 else 'N/A'}, not suitable for rewind test",
                status="SKIP", required=False,
                classification="STATE_AUDIT",
                supporting_p0_cases=["P0-01", "P0-06"],
            ))

        restore_anchor_row(backup, gate)
        time.sleep(3)

        # ---- PG-A4: reservation rewind → ALREADY_RELEASED ----
        LOGGER.info("=== PG-A4: reservation rewind → ALREADY_RELEASED ===")
        anchor_f4 = fetch_anchor_json(target_run_id)
        if anchor_f4:
            try:
                reservation = json.loads(anchor_f4.get("reservationJson", "{}"))
                if isinstance(reservation, dict) and reservation.get("state") == "RELEASED":
                    # Rewind to PENDING_TRANSFERRED + RELEASE
                    reservation["state"] = "PENDING_TRANSFERRED"
                    anchor_f4["reservationJson"] = json.dumps(reservation)
                    update_anchor_json_gated(target_run_id, anchor_f4, gate)
                    inject_anchor_field_gated(target_run_id, ["finalizerStep"], "RELEASE", gate)
                    time.sleep(12)
                    anchor_after_f4 = fetch_anchor_json(target_run_id)
                    if anchor_after_f4:
                        res_after = json.loads(anchor_after_f4.get("reservationJson", "{}"))
                        state_after = res_after.get("state") if isinstance(res_after, dict) else None
                        checks.append(Check(
                            name="PG-A4-ALREADY-RELEASED",
                            ok=state_after == "RELEASED",
                            detail=f"reservation state after rewind+recovery: {state_after}",
                            required=True,
                            classification="STATE_AUDIT",
                            supporting_p0_cases=["P0-06"],
                        ))
                    else:
                        checks.append(Check(name="PG-A4-ALREADY-RELEASED", ok=False, detail="anchor after null", classification="STATE_AUDIT", supporting_p0_cases=["P0-06"]))
                else:
                    checks.append(Check(
                        name="PG-A4-ALREADY-RELEASED", ok=False,
                        detail=f"reservation state != RELEASED ({reservation.get('state') if isinstance(reservation, dict) else 'N/A'})",
                        status="SKIP", required=False,
                        classification="STATE_AUDIT",
                        supporting_p0_cases=["P0-06"],
                    ))
            except (json.JSONDecodeError, AttributeError):
                checks.append(Check(name="PG-A4-ALREADY-RELEASED", ok=False, detail="reservationJson parse failed", status="SKIP", required=False, classification="STATE_AUDIT", supporting_p0_cases=["P0-06"]))
        else:
            checks.append(Check(name="PG-A4-ALREADY-RELEASED", ok=False, detail="anchor null", status="SKIP", required=False, classification="STATE_AUDIT", supporting_p0_cases=["P0-06"]))

    finally:
        # Always restore
        restored, restore_reason = restore_anchor_row(backup, gate)
        if not restored:
            checks.append(Check(name="restore-final", ok=False, detail=f"restore failed: {restore_reason}", status="BLOCKED", required=True, classification="STATE_AUDIT"))
        else:
            LOGGER.info("Anchor restored from backup")

    # Output
    output_dir = Path(cfg.get("output_root", "output/data")) / f"{run_tag}-anchor_fault"
    summary = write_summary_json(checks, output_dir / "summary.json", extra={"target_run_id": target_run_id})
    write_summary_csv(checks, output_dir / "summary.csv")
    LOGGER.info("Anchor Fault Probe complete: %s", output_dir / "summary.json")
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
