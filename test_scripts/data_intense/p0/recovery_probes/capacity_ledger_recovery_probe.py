#!/usr/bin/env python3
"""
Capacity Ledger Recovery Probe v2 — ENV_SMOKE level (read-only).

Case map:
  CL-C1: reservation state 一致性扫描 (RELEASED → finalizerStep ≥ RELEASE)
  CL-C2: PENDING_TRANSFERRED + terminal 共存检查（应已转 TERMINAL_CONFIRMED）
  CL-C3: reservationJson 缺失 terminal run 统计 (info)

All read-only SELECT. sufficientForGuarantee=false.
"""

from __future__ import annotations

import argparse
import json
import logging
import sys
from collections import Counter
from pathlib import Path
from typing import Any, Dict, List

import yaml

sys.path.insert(0, str(Path(__file__).resolve().parents[4]))
from recovery_probes_common import (
    Check,
    fetch_anchor_json,
    get_pg_conn,
    write_summary_csv,
    write_summary_json,
)

try:
    from frogutils.logger import LogEmitter
except ImportError:
    LogEmitter = None

LOGGER = logging.getLogger("capacity_ledger_probe")


def _parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Capacity Ledger Recovery Probe v2 (read-only)")
    parser.add_argument("--config", default="test_scripts/data_intense/p0/recovery_probes/config.yml")
    parser.add_argument("--dry-run", action="store_true")
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


def _scan_reservation_states() -> List[Dict[str, Any]]:
    conn = get_pg_conn()
    try:
        with conn.cursor() as cur:
            cur.execute("""
                SELECT id, status, tool_job_anchor_json
                FROM alphafrog_agent_run
                WHERE tool_job_anchor_json IS NOT NULL
                  AND tool_job_anchor_json <> '{}'::jsonb
                ORDER BY updated_at DESC LIMIT 100
            """)
            rows = cur.fetchall()
            results = []
            for row in rows:
                anchor = row[2]
                if isinstance(anchor, str):
                    anchor = json.loads(anchor)
                res_raw = anchor.get("reservationJson") if isinstance(anchor, dict) else None
                res_state = None
                res_id = None
                res_units = None
                if res_raw:
                    try:
                        r = json.loads(res_raw)
                        if isinstance(r, dict):
                            res_state = r.get("state")
                            res_id = r.get("reservationId")
                            res_units = r.get("capacityUnits")
                    except (json.JSONDecodeError, TypeError):
                        pass
                results.append({
                    "run_id": row[0], "status": row[1],
                    "reservation_state": res_state, "reservation_id": res_id,
                    "reservation_units": res_units,
                    "finalizer_step": anchor.get("finalizerStep") if isinstance(anchor, dict) else None,
                    "finalizer_error": anchor.get("finalizerError") if isinstance(anchor, dict) else None,
                    "terminal_status": anchor.get("terminalStatus") if isinstance(anchor, dict) else None,
                    "anchor_state": anchor.get("anchorState") if isinstance(anchor, dict) else None,
                })
            return results
    finally:
        conn.close()


def main() -> None:
    global LOGGER
    args = _parse_args()
    cfg = _load_config(args.config)
    run_tag = _now_tag()

    log_dir = cfg.get("log_dir", "output/logs")
    if LogEmitter:
        emitter = LogEmitter("capacity_ledger_probe", cfg.get("log_level", "INFO"), log_dir, run_tag)
        LOGGER = emitter.build()

    if args.dry_run:
        print(json.dumps({"ok": True, "mode": "dry_run", "probe": "capacity_ledger"}, ensure_ascii=False, indent=2))
        return

    checks: List[Check] = []
    all_anchors = _scan_reservation_states()
    LOGGER.info("Scanned %d active anchors (read-only)", len(all_anchors))

    # ---- CL-C1: RELEASED runs must have finalizerStep >= RELEASE ----
    released_runs = [r for r in all_anchors if r["reservation_state"] == "RELEASED"]
    released_stuck = [r for r in released_runs
                      if r["finalizer_step"] not in ("RELEASE", "USAGE", "EVENT", "CAS_STATUS", "RESUME_READY")
                      and r["terminal_status"] is not None]
    checks.append(Check(
        name="CL-C1-RELEASED-CONSISTENT",
        ok=len(released_stuck) == 0,
        detail=f"RELEASED={len(released_runs)}, stuck={len(released_stuck)}"
               + (f" ({released_stuck[0]['run_id']})" if released_stuck else ""),
        required=len(released_runs) > 0,
        classification="ENV_SMOKE",
        supporting_p0_cases=["P0-06", "P0-07"],
    ))

    # ---- CL-C2: PENDING_TRANSFERRED + terminalStatus 不应共存 ----
    pt_terminal = [r for r in all_anchors
                   if r["reservation_state"] == "PENDING_TRANSFERRED" and r["terminal_status"] is not None]
    checks.append(Check(
        name="CL-C2-PENDING-TERMINAL-INCONSISTENT",
        ok=len(pt_terminal) == 0,
        detail=f"PENDING_TRANSFERRED+terminal: {len(pt_terminal)}"
               + (f" ({pt_terminal[0]['run_id']})" if pt_terminal else ""),
        required=True,
        classification="ENV_SMOKE",
        supporting_p0_cases=["P0-02", "P0-06"],
    ))

    # ---- CL-C3: reservationJson missing (info) ----
    no_res = [r for r in all_anchors if r["reservation_state"] is None and r["terminal_status"] is not None]
    checks.append(Check(
        name="CL-C3-NO-RESERVATION-TERMINAL",
        ok=True,
        detail=f"terminal runs without reservationJson: {len(no_res)}",
        required=False,
        classification="ENV_SMOKE",
        supporting_p0_cases=["P0-08"],
    ))

    # State distribution
    state_counts = Counter(r["reservation_state"] for r in all_anchors)
    status_counts = Counter(r["status"] for r in all_anchors)
    LOGGER.info("Reservation states: %s", dict(state_counts))
    LOGGER.info("Run statuses: %s", dict(status_counts))

    output_dir = Path(cfg.get("output_root", "output/data")) / f"{run_tag}-capacity_ledger"
    summary = write_summary_json(checks, output_dir / "summary.json", extra={
        "total_active_anchors": len(all_anchors),
        "reservation_states": {str(k): v for k, v in state_counts.items()},
        "run_statuses": {str(k): v for k, v in status_counts.items()},
    })
    write_summary_csv(checks, output_dir / "summary.csv")
    LOGGER.info("Capacity Ledger Probe complete: %s", output_dir / "summary.json")
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
