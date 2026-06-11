from __future__ import annotations

import json
from collections import deque
from dataclasses import dataclass, field
from typing import Any, Deque, Dict, List, Tuple


@dataclass
class NormalizedEvent:
    seq: int = 0
    kind: str = "unknown"
    event_type: str = ""
    workflow: str = "unknown"
    node_id: str = ""
    node_label: str = ""
    call_id: str = ""
    name: str = ""
    status: str = "running"
    summary: str = ""
    metrics: Dict[str, Any] = field(default_factory=dict)
    raw: Dict[str, Any] = field(default_factory=dict)


@dataclass
class ViewSnapshot:
    run_id: str
    status: str
    phase: str
    workflow: str
    last_seq: int
    trace: List[str]
    dag_nodes: Dict[str, List[str]]
    warnings: List[str]
    final_answer: str = ""


class RunViewState:
    def __init__(self, *, max_trace_lines: int = 14, max_node_lines: int = 3, max_warnings: int = 4) -> None:
        self.run_id = ""
        self.status = "CREATED"
        self.phase = ""
        self.workflow = "unknown"
        self.last_seq = 0
        self.final_answer = ""
        self._trace: Deque[str] = deque(maxlen=max_trace_lines)
        self._dag_nodes: Dict[str, Deque[str]] = {}
        self._warnings: Deque[str] = deque(maxlen=max_warnings)
        self._max_node_lines = max_node_lines
        self._seen: set[Tuple[int, str]] = set()
        self._seen_order: Deque[Tuple[int, str]] = deque()
        self._seen_limit = 2000

    def add_warning(self, text: str) -> None:
        text = " ".join(str(text).split())
        if text and text not in self._warnings:
            self._warnings.append(text)

    def set_run_id(self, run_id: str) -> None:
        self.run_id = run_id

    def set_final_answer(self, answer: str) -> None:
        self.final_answer = (answer or "").strip()

    def ingest_sse(self, event_type: str, data: Any) -> None:
        if isinstance(data, str):
            try:
                data = json.loads(data)
            except json.JSONDecodeError:
                data = {"message": data}
        if not isinstance(data, dict):
            return
        if event_type == "snapshot":
            self._ingest_snapshot(data)
            return
        if event_type == "agent.event":
            self.ingest_agent_event(data)
            return
        if event_type == "run.status":
            self.status = str(data.get("status") or self.status)
            self.phase = str(data.get("phase") or self.phase)
            seq = _to_int(data.get("lastSeq") or data.get("seq"))
            if seq:
                self.last_seq = max(self.last_seq, seq)
            return
        if event_type == "run.done":
            self.status = str(data.get("status") or self.status)
            self.phase = "done"
            self.last_seq = max(self.last_seq, _to_int(data.get("lastSeq")))
            self._append_line("run done: " + self.status, node_id="")
            return
        if event_type == "error":
            self.add_warning("SSE error: " + str(data.get("message") or data))

    def ingest_agent_event(self, envelope: Dict[str, Any]) -> None:
        event = normalize_agent_event(envelope, current_workflow=self.workflow)
        dedup_key = (event.seq, event.event_type)
        if event.seq and dedup_key in self._seen:
            return
        if event.seq:
            self._remember_seen(dedup_key)
            self.last_seq = max(self.last_seq, event.seq)
        if event.workflow in ("linear", "dag"):
            self.workflow = event.workflow
        if event.kind == "status":
            self.status = event.status or self.status
        elif event.kind == "warning":
            self.add_warning(event.summary)
        elif event.event_type == "LLM_CALL_DELTA":
            return
        elif event.summary:
            self._append_line(event.summary, node_id=event.node_id if self.workflow == "dag" else "")

    def snapshot(self) -> ViewSnapshot:
        return ViewSnapshot(
            run_id=self.run_id,
            status=self.status,
            phase=self.phase,
            workflow=self.workflow,
            last_seq=self.last_seq,
            trace=list(self._trace),
            dag_nodes={key: list(lines) for key, lines in self._dag_nodes.items()},
            warnings=list(self._warnings),
            final_answer=self.final_answer,
        )

    def _ingest_snapshot(self, data: Dict[str, Any]) -> None:
        self.run_id = str(data.get("runId") or self.run_id)
        self.status = str(data.get("status") or self.status)
        self.phase = str(data.get("phase") or self.phase)
        self.last_seq = max(self.last_seq, _to_int(data.get("lastSeq")))
        events = data.get("events")
        if isinstance(events, list):
            for item in events:
                if isinstance(item, dict):
                    self.ingest_agent_event(item)

    def _append_line(self, line: str, *, node_id: str) -> None:
        if node_id:
            if node_id not in self._dag_nodes:
                self._dag_nodes[node_id] = deque(maxlen=self._max_node_lines)
            self._dag_nodes[node_id].append(line)
        else:
            self._trace.append(line)

    def _remember_seen(self, key: Tuple[int, str]) -> None:
        self._seen.add(key)
        self._seen_order.append(key)
        while len(self._seen_order) > self._seen_limit:
            old = self._seen_order.popleft()
            self._seen.discard(old)


def normalize_agent_event(envelope: Dict[str, Any], *, current_workflow: str = "unknown") -> NormalizedEvent:
    event_type = str(envelope.get("eventType") or envelope.get("event_type") or "")
    seq = _to_int(envelope.get("seq"))
    payload, _source = resolve_agent_payload(envelope)
    workflow = _workflow(payload, current_workflow)
    node_id = str(payload.get("todo_id") or payload.get("node_id") or payload.get("id") or "")
    node_label = node_id or str(payload.get("phase") or "")

    if event_type == "PLAN_READY":
        plan = payload.get("plan") if isinstance(payload.get("plan"), dict) else {}
        items = plan.get("items") if isinstance(plan, dict) else []
        inferred = _infer_workflow_from_plan(plan, current_workflow)
        count = len(items) if isinstance(items, list) else 0
        return NormalizedEvent(
            seq=seq,
            kind="plan",
            event_type=event_type,
            workflow=inferred,
            status="finished",
            summary=f"计划生成完成，workflow={inferred}，todo={count}",
            raw=payload,
        )

    if event_type.startswith("TODO_NODE_"):
        status = {
            "TODO_NODE_STARTED": "started",
            "TODO_NODE_COMPLETED": "finished",
            "TODO_NODE_FAILED": "failed",
            "TODO_NODE_SKIPPED": "skipped",
        }.get(event_type, "running")
        if status == "started":
            summary = f"开始 {node_label or 'todo'}"
        elif status == "finished":
            duration = _duration(payload)
            summary = f"{node_label or 'todo'} 完成{duration}"
        elif status == "skipped":
            summary = f"{node_label or 'todo'} 跳过：{_short(payload.get('reason'), 80)}"
        else:
            summary = f"{node_label or 'todo'} 失败：{_short(payload.get('failure_reason') or payload.get('reason'), 120)}"
        return NormalizedEvent(seq, "todo", event_type, workflow, node_id, node_label, "", "", status, summary, {}, payload)

    if event_type.startswith("LLM_CALL"):
        call_id = str(payload.get("llm_call_id") or payload.get("trace_id") or "")
        name = str(payload.get("model") or payload.get("endpoint") or "llm")
        if event_type == "LLM_CALL_STARTED":
            summary = f"llm call开始，{name}"
            status = "started"
        elif event_type == "LLM_CALL_DELTA":
            chunks = _to_int(payload.get("chunk_count"))
            tokens = _to_int(payload.get("estimated_output_tokens") or payload.get("output_tokens"))
            summary = f"llm call进行中，{chunks} chunks，{tokens} tokens"
            status = "running"
        else:
            chunks = _to_int(payload.get("chunk_count"))
            tokens = _to_int(payload.get("total_tokens") or payload.get("output_tokens"))
            duration = _duration(payload)
            ok = payload.get("success", True)
            summary = f"llm call完成，{chunks} chunks，{tokens} tokens{duration}"
            if ok is False:
                summary = "llm call失败：" + _short(payload.get("error_preview") or payload.get("error"), 120)
            status = "finished" if ok is not False else "failed"
        return NormalizedEvent(
            seq,
            "llm",
            event_type,
            workflow,
            node_id,
            node_label,
            call_id,
            name,
            status,
            summary,
            {
                "chunk_count": payload.get("chunk_count"),
                "tokens": payload.get("total_tokens") or payload.get("output_tokens"),
                "duration_ms": payload.get("duration_ms"),
            },
            payload,
        )

    if event_type.startswith("TOOL_CALL"):
        call_id = str(payload.get("tool_call_id") or payload.get("toolCallId") or "")
        name = str(payload.get("tool_name") or payload.get("toolName") or "unknown")
        if event_type == "TOOL_CALL_STARTED":
            args = _short(payload.get("arguments") or payload.get("args") or "", 120)
            summary = f"使用工具 {name}，等待结果" + (f"：{args}" if args else "")
            status = "started"
        else:
            result = _short(payload.get("result_preview") or payload.get("result") or payload.get("output") or "", 140)
            ok = payload.get("success", True)
            summary = f"使用工具 {name}，结果（截取前140字符）：{result}"
            if ok is False:
                summary = f"工具 {name} 失败：{_short(payload.get('error') or result, 140)}"
            status = "finished" if ok is not False else "failed"
        return NormalizedEvent(seq, "tool", event_type, workflow, node_id, node_label, call_id, name, status, summary, {}, payload)

    if event_type in ("RUN_STATUS_CHANGED", "PHASE_CHANGED", "RUN_STATUS"):
        status = str(payload.get("status") or envelope.get("status") or "")
        return NormalizedEvent(seq, "status", event_type, workflow, node_id, node_label, "", "", status, "", {}, payload)

    summary = _short(payload.get("message") or payload.get("reason") or event_type, 160)
    return NormalizedEvent(seq, "unknown", event_type, workflow, node_id, node_label, "", "", "running", summary, {}, payload)


def normalize_sse_frame(event_type: str, data: Any, *, current_workflow: str = "unknown") -> List[NormalizedEvent]:
    """Normalize one SSE frame into zero or more display events."""
    if isinstance(data, str):
        try:
            data = json.loads(data)
        except json.JSONDecodeError:
            data = {"message": data}
    if not isinstance(data, dict):
        return []
    if event_type == "snapshot":
        out: List[NormalizedEvent] = []
        events = data.get("events")
        if isinstance(events, list):
            for item in events:
                if isinstance(item, dict):
                    out.append(normalize_agent_event(item, current_workflow=current_workflow))
        return out
    if event_type == "agent.event":
        return [normalize_agent_event(data, current_workflow=current_workflow)]
    if event_type == "run.status":
        status = str(data.get("status") or "")
        phase = str(data.get("phase") or "")
        summary = f"run status: {status}" + (f" / {phase}" if phase else "")
        return [
            NormalizedEvent(
                seq=_to_int(data.get("lastSeq") or data.get("seq")),
                kind="status",
                event_type=event_type,
                workflow=current_workflow,
                status=status,
                summary=summary,
                raw=data,
            )
        ]
    if event_type == "run.done":
        status = str(data.get("status") or "")
        return [
            NormalizedEvent(
                seq=_to_int(data.get("lastSeq")),
                kind="status",
                event_type=event_type,
                workflow=current_workflow,
                status=status,
                summary=f"run done: {status}",
                raw=data,
            )
        ]
    if event_type == "error":
        return [
            NormalizedEvent(
                kind="warning",
                event_type=event_type,
                workflow=current_workflow,
                summary="SSE error: " + str(data.get("message") or data),
                raw=data,
            )
        ]
    return []


def resolve_agent_payload(data: Dict[str, Any]) -> Tuple[Dict[str, Any], str]:
    payload = data.get("payload")
    if isinstance(payload, dict):
        return payload, "payload"
    if isinstance(payload, list):
        return {"value": payload}, "payload"
    raw = data.get("payloadJson") or data.get("payload_json")
    if isinstance(raw, dict):
        return raw, "payloadJson"
    if isinstance(raw, str) and raw.strip():
        try:
            parsed = json.loads(raw)
            if isinstance(parsed, dict):
                return parsed, "payloadJson"
        except json.JSONDecodeError:
            pass
    return {}, "none"


def final_answer_from_result(result: Dict[str, Any]) -> str:
    for key in ("answerMarkdown", "answer_markdown", "answer", "result"):
        value = result.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip()
    payload = result.get("payload")
    if isinstance(payload, dict):
        return final_answer_from_result(payload)
    return ""


def is_terminal(status: str) -> bool:
    return str(status or "").upper() in {
        "COMPLETED",
        "PARTIAL",
        "FAILED",
        "CANCELED",
        "CANCELLED",
        "EXPIRED",
        "TIMEOUT",
        "TIMED_OUT",
    }


def _workflow(payload: Dict[str, Any], current: str) -> str:
    value = str(payload.get("workflow") or "").lower()
    if value in ("linear", "dag"):
        return value
    return current if current in ("linear", "dag") else "unknown"


def _infer_workflow_from_plan(plan: Dict[str, Any], current: str) -> str:
    value = str(plan.get("workflow") or plan.get("executionMode") or "").lower()
    if value in ("linear", "dag"):
        return value
    items = plan.get("items")
    if isinstance(items, list):
        for item in items:
            if isinstance(item, dict) and (item.get("dependencies") or item.get("dependsOn") or item.get("depends_on")):
                return "dag"
        if len(items) > 0:
            return "linear"
    return current if current in ("linear", "dag") else "unknown"


def _duration(payload: Dict[str, Any]) -> str:
    duration = payload.get("duration_ms")
    try:
        value = int(duration)
    except (TypeError, ValueError):
        return ""
    if value <= 0:
        return ""
    return f"，{value}ms"


def _to_int(raw: Any) -> int:
    try:
        if raw is None or raw == "":
            return 0
        return int(raw)
    except (TypeError, ValueError):
        return 0


def _short(raw: Any, limit: int) -> str:
    if raw is None:
        return ""
    if isinstance(raw, (dict, list)):
        text = json.dumps(raw, ensure_ascii=False, separators=(",", ":"))
    else:
        text = str(raw)
    text = " ".join(text.split())
    if len(text) <= limit:
        return text
    return text[: max(0, limit - 1)] + "…"
