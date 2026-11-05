"""整理一次采集的清单、报告和来源缺口。"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any, Callable

from .deployment_source import attach_deployment
from .log_source import attach_log_result
from .manifest import build_manifest, write_manifest
from .read_client import CollectionError, run_collection
from .report import write_report


def central_logs_query(
    *,
    environment: str | None,
    since: str | None,
    until: str | None,
    run_id: str | None,
) -> Callable[[str], dict[str, Any]]:
    """用已经通过审查的集中日志客户端读取一个日志轨迹编号。"""

    def query(trace_id: str) -> dict[str, Any]:
        from af_light_client.central_logs import read_logs

        return read_logs(
            environment=environment,
            since=since,
            until=until,
            trace_id=trace_id,
            run_id=run_id,
        )

    return query


def _load_json(path: Path) -> Any:
    if not path.is_file():
        return None
    return json.loads(path.read_text(encoding="utf-8"))


def _business_result(output_dir: Path, summary: dict[str, Any]) -> dict[str, Any]:
    run_payload = _load_json(output_dir / "run.json")
    run_errors = [
        error for error in (summary.get("errors") or [])
        if isinstance(error, dict) and error.get("section") == "run"
    ]
    if run_payload:
        return {"status": "已取得基础运行事实", "runId": summary.get("runId"), "run": run_payload}
    reason = str(run_errors[0].get("error")) if run_errors else "运行记录不可读"
    return {"status": "未取得运行事实", "runId": summary.get("runId"), "reason": reason}


def finalize_package(
    output_dir: Path,
    summary: dict[str, Any],
    *,
    correspondence: list[dict[str, Any]] | None = None,
    log_query: Callable[[str], dict[str, Any]] | None = None,
    log_environment: str | None = None,
    log_since: str | None = None,
    log_until: str | None = None,
    deployment_payload: dict[str, Any] | None = None,
) -> dict[str, Any]:
    if log_query is None:
        log_query = central_logs_query(
            environment=log_environment,
            since=log_since,
            until=log_until,
            run_id=str(summary.get("runId") or "") or None,
        )
    log_attached = attach_log_result(correspondence=correspondence, query=log_query)
    deployment_attached = attach_deployment(deployment_payload)
    gaps = list(log_attached["gaps"]) + list(deployment_attached["gaps"])
    for error in summary.get("errors") or []:
        gaps.append({"item": str(error.get("section") or "采集"), "reason": str(error.get("error") or error)})
    if log_attached["association"] == "proven-call-association":
        (output_dir / "log_rows.json").write_text(
            json.dumps(log_attached["rows"], ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
    if deployment_attached["records"]:
        (output_dir / "deployment.json").write_text(
            json.dumps(deployment_attached["records"], ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
    facts = {"businessResult": _business_result(output_dir, summary)}
    write_report(output_dir, run_facts=facts, gaps=gaps)
    manifest = build_manifest(output_dir, collected_at=str(summary.get("collectedAt") or ""))
    write_manifest(output_dir, manifest)
    return {"summary": summary, "gaps": gaps, "logAssociation": log_attached["association"]}


def collect_run(
    args: argparse.Namespace,
    *,
    correspondence: list[dict[str, Any]] | None = None,
    log_query=None,
    log_environment: str | None = None,
    log_since: str | None = None,
    log_until: str | None = None,
    deployment_payload=None,
) -> dict[str, Any]:
    if not getattr(args, "output_dir", None):
        raise CollectionError("采集必须提供 --output-dir，诊断采集不查找压测批次目录")
    summary = run_collection(args)
    output_dir = Path(summary["outputDir"])
    return finalize_package(
        output_dir,
        summary,
        correspondence=correspondence,
        log_query=log_query,
        log_environment=log_environment,
        log_since=log_since,
        log_until=log_until,
        deployment_payload=deployment_payload,
    )
