"""读取容器生命周期结果。缺记录不解释为未发生。"""

from __future__ import annotations

from typing import Any


def attach_deployment(
    payload: dict[str, Any] | None,
    *,
    generation_id: str | None = None,
    task_id: str | None = None,
) -> dict[str, Any]:
    if payload is None:
        return {
            "records": [],
            "gaps": [{"item": "部署与生命周期", "reason": "lifecycle query 尚未提供"}],
        }
    records = payload.get("records") or []
    gaps: list[dict[str, str]] = []
    for name in payload.get("missingSources") or []:
        gaps.append({"item": "部署与生命周期", "reason": f"missingSources 记录缺少 {name}"})
    for gap in payload.get("collectorGaps") or []:
        gaps.append({"item": "部署与生命周期", "reason": f"collector_gap: {gap}"})
    for note in payload.get("notes") or []:
        gaps.append({"item": "部署与生命周期", "reason": f"notes: {note}"})
    normalized = []
    dropped = 0
    for record in records:
        if not isinstance(record, dict):
            continue
        if task_id and record.get("taskId") == task_id and record.get("containerKind") != "DEPLOYMENT_SERVICE":
            normalized.append(_normalize(record))
            continue
        if record.get("deploymentId") or record.get("containerKind") == "DEPLOYMENT_SERVICE":
            sources = record.get("missingSources") or []
            generation = str(record.get("generationId") or "")
            if "generationId" in sources or not generation_id or generation != str(generation_id):
                dropped += 1
            else:
                normalized.append(_normalize(record))
    if dropped:
        gaps.append({
            "item": "部署与生命周期",
            "reason": f"{dropped} 条部署容器记录因代际不一致、缺少代际或 missingSources 含 generationId 而未写入",
        })
    missing_count = payload.get("generationMissingCount")
    if isinstance(missing_count, int) and missing_count > 0:
        if payload.get("truncated"):
            reason = f"generationMissingCount 是 {missing_count}，同时结果被截断，这个计数是下界"
        else:
            reason = f"generationMissingCount 是 {missing_count}，这些记录因代际为空被排除"
        gaps.append({"item": "部署与生命周期", "reason": reason})
    if not records and not gaps:
        gaps.append({"item": "部署与生命周期", "reason": "没有读到记录。缺记录不解释为未发生"})
    return {"records": normalized, "gaps": gaps}


def _normalize(record: dict[str, Any]) -> dict[str, Any]:
    return {
        "machineId": record.get("machineId"),
        "deploymentId": record.get("deploymentId"),
        "trafficScopeId": record.get("trafficScopeId"),
        "generationId": record.get("generationId"),
        "instanceId": record.get("instanceId"),
        "serviceName": record.get("serviceName"),
        "releaseId": record.get("releaseId"),
        "gitCommit": record.get("gitCommit"),
        "containerId": record.get("containerId"),
        "containerName": record.get("containerName"),
        "containerKind": record.get("containerKind"),
        "taskId": record.get("taskId"),
        "imageDigest": record.get("imageDigest"),
        "imageReference": record.get("imageReference"),
        "eventTime": record.get("eventTime"),
        "observedAt": record.get("observedAt"),
        "exitReason": record.get("exitReason"),
        "evidence": record.get("evidence"),
        "missingSources": record.get("missingSources") or [],
    }
