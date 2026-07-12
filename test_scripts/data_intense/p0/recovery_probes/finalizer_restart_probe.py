#!/usr/bin/env python3
"""
Finalizer Restart Probe v2 — ENV_SMOKE level (read-only diagnostics).

Case map:
  FS-R1: finalizerStep 分布 + anchorState 分布 → CSV
  FS-R2: stuck detection (updated_at > threshold, non-terminal)
  FS-R3: PREPARING anchor identification
  FS-R4: finalizerError classification

All read-only. sufficientForGuarantee=false.
"""

from __future__ import annotations

import argparse
import csv
import json
import logging
import sys
from collections import Counter
from datetime import datetime, timezone, timedelta
from pathlib import Path
from typing import Any, Dict, List

import yaml

sys.path.insert(0, str(Path(__file__).resolve().parents[4]))
from recovery_probes_common import (
    Check,
    fetch_anchor_json,
    fetch_run_status,
    get_pg_conn,
    write_summary_csv,
    write_summary_json,
)

try:
    from frogutils.logger import LogEmitter
except ImportError:
    LogEmitter = None

LOGGER = logging.getLogger("finalizer_restart_probe")


def _parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Finalizer Restart Probe v2 (read-only)")
    parser.add_argument("--config", default="test_scripts/data_intense/p0/recovery_probes/config.yml")
    parser.add_argument("--dump-run-id", help="dump full anchor JSON for specific run")
    parser.add_argument("--stuck-threshold-minutes", type=int, default=30)
    parser.add_argument("--dry-run", action="store_true")
    return parser.parse_args()


def _load_config(cfg_path: str) -> Dict[str, Any]:
    path = Path(cfg_path)
    if not path.exists():
        raise SystemExit(f"配置文件不存在: {cfg_path}")
    with open(path, "r", encoding="utf-8") as f:
        return yaml.safe_load(f) or {}


def _now_tag() -> str:
    return datetime.now().strftime("%Y%m%d-%H%M%S")


def _scan_all() -> List[Dict[str, Any]]:
    conn = get_pg_conn()
    try:
        with conn.cursor() as cur:
            cur.execute("""
                SELECT id, status, tool_job_anchor_json, updated_at
                FROM alphafrog_agent_run
                WHERE tool_job_anchor_json IS NOT NULL
                  AND tool_job_anchor_json <> '{}'::jsonb
                ORDER BY updated_at DESC
            """)
            rows = cur.fetchall()
            results = []
            for row in rows:
                anchor = row[2]
                if isinstance(anchor, str):
                    try:
                        anchor = json.loads(anchor)
                    except json.JSONDecodeError:
                        anchor = {"_parse_error": True}
                results.append({"run_id": row[0], "status": row[1], "anchor": anchor, "updated_at": row[3]})
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
        emitter = LogEmitter("finalizer_restart_probe", cfg.get("log_level", "INFO"), log_dir, run_tag)
        LOGGER = emitter.build()

    if args.dry_run:
        print(json.dumps({"ok": True, "mode": "dry_run", "probe": "finalizer_restart"}, ensure_ascii=False, indent=2))
        return

    checks: List[Check] = []
    output_dir = Path(cfg.get("output_root", "output/data")) / f"{run_tag}-finalizer_restart"
    output_dir.mkdir(parents=True, exist_ok=True)

    all_runs = _scan_all()
    LOGGER.info("Scanned %d runs (read-only)", len(all_runs))

    # ---- FS-R1: distribution snapshot ----
    step_counts = Counter()
    state_counts = Counter()
    status_counts = Counter()

    for item in all_runs:
        anchor = item["anchor"]
        if isinstance(anchor, dict):
            step_counts[anchor.get("finalizerStep") or "(none)"] += 1
            state_counts[anchor.get("anchorState") or "(none)"] += 1
            status_counts[item["status"]] += 1

    with open(output_dir / "step_distribution.csv", "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(["key", "count"])
        for k, v in step_counts.most_common():
            writer.writerow([k, v])

    checks.append(Check(
        name="FS-R1-DISTRIBUTION",
        ok=len(all_runs) > 0,
        detail=f"{len(all_runs)} anchors, steps={len(step_counts)}, states={len(state_counts)}, statuses={len(status_counts)}",
        required=True,
        classification="ENV_SMOKE",
        supporting_p0_cases=["P0-10"],
    ))

    # ---- FS-R2: stuck detection ----
    threshold = datetime.now(timezone.utc) - timedelta(minutes=args.stuck_threshold_minutes)
    stuck_runs = []
    non_terminal = {"RECEIVED", "PLANNING", "EXECUTING", "WAITING", "WAITING_TOOL_JOB", "SUMMARIZING", "CANCELING"}
    for item in all_runs:
        anchor = item["anchor"]
        updated_at = item["updated_at"]
        if isinstance(anchor, dict) and updated_at and item["status"] in non_terminal:
            step = anchor.get("finalizerStep")
            if step == "RESUME_READY":
                continue
            if updated_at.replace(tzinfo=timezone.utc) < threshold:
                stuck_runs.append({
                    "run_id": item["run_id"], "status": item["status"],
                    "finalizer_step": step,
                    "finalizer_error": anchor.get("finalizerError"),
                    "updated_at": updated_at.isoformat(),
                })

    with open(output_dir / "stuck_runs.csv", "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(["run_id", "status", "finalizer_step", "finalizer_error", "updated_at"])
        for r in stuck_runs:
            writer.writerow([r["run_id"], r["status"], r["finalizer_step"], r["finalizer_error"], r["updated_at"]])

    checks.append(Check(
        name="FS-R2-STUCK",
        ok=len(stuck_runs) == 0,
        detail=f"stuck runs: {len(stuck_runs)}"
               + (f" ({stuck_runs[0]['run_id']} at {stuck_runs[0]['finalizer_step']})" if stuck_runs else ""),
        required=False,
        classification="ENV_SMOKE",
        supporting_p0_cases=["P0-03", "P0-10"],
    ))

    # ---- FS-R3: PREPARING anchors ----
    preparing = [item for item in all_runs
                 if isinstance(item["anchor"], dict) and item["anchor"].get("anchorState") == "PREPARING"]
    checks.append(Check(
        name="FS-R3-PREPARING",
        ok=len(preparing) == 0,
        detail=f"PREPARING anchors: {len(preparing)}"
               + (f" ({preparing[0]['run_id']})" if preparing else ""),
        required=False,
        classification="ENV_SMOKE",
        supporting_p0_cases=["P0-03"],
    ))

    # ---- FS-R4: finalizerError ----
    error_counts = Counter()
    for item in all_runs:
        anchor = item["anchor"]
        if isinstance(anchor, dict):
            err = anchor.get("finalizerError")
            if err:
                error_counts[err] += 1

    checks.append(Check(
        name="FS-R4-ERRORS",
        ok=len(error_counts) == 0,
        detail=f"errors: {dict(error_counts)}" if error_counts else "no errors",
        required=False,
        classification="ENV_SMOKE",
        supporting_p0_cases=["P0-06"],
    ))

    # ---- Dump specific run ----
    if args.dump_run_id:
        anchor = fetch_anchor_json(args.dump_run_id)
        status = fetch_run_status(args.dump_run_id)
        dump = {"run_id": args.dump_run_id, "status": status, "anchor": anchor, "dumped_at": datetime.now(timezone.utc).isoformat()}
        dump_path = output_dir / f"anchor_dump_{args.dump_run_id}.json"
        dump_path.write_text(json.dumps(dump, ensure_ascii=False, indent=2, default=str), encoding="utf-8")
        checks.append(Check(
            name="FS-DUMP", ok=anchor is not None,
            detail=f"dumped: {dump_path}" if anchor else "anchor null",
            required=False, classification="ENV_SMOKE",
        ))

    summary = write_summary_json(checks, output_dir / "summary.json", extra={
        "total_anchors": len(all_runs),
        "step_distribution": {str(k): v for k, v in step_counts.items()},
        "state_distribution": {str(k): v for k, v in state_counts.items()},
        "status_distribution": {str(k): v for k, v in status_counts.items()},
        "stuck_count": len(stuck_runs), "preparing_count": len(preparing),
    })
    write_summary_csv(checks, output_dir / "summary.csv")
    LOGGER.info("Finalizer Restart Probe complete: %s", output_dir / "summary.json")
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
