#!/usr/bin/env python3
"""核对一次 headless 父子 Run 样本及当次服务端只读证据。"""

from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime
from pathlib import Path
from typing import Any


SCENARIOS = ("single_child", "out_of_order", "wait_timeout")


def read_object(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"JSON 对象缺失: {path}")
    return value


def read_time(raw: Any) -> datetime | None:
    if not isinstance(raw, str) or not raw.strip():
        return None
    try:
        value = datetime.fromisoformat(raw.replace("Z", "+00:00"))
    except ValueError:
        return None
    return value if value.tzinfo is not None else None


def text(raw: Any) -> str:
    return str(raw or "").strip()


def objects(raw: Any) -> list[dict[str, Any]]:
    return [item for item in raw if isinstance(item, dict)] if isinstance(raw, list) else []


def event_types(events: list[dict[str, Any]]) -> list[str]:
    return sorted({text(event.get("eventType") or event.get("type")) for event in events
                   if text(event.get("eventType") or event.get("type"))})


class Audit:
    def __init__(self) -> None:
        self.missing: list[str] = []
        self.failed: list[str] = []
        self.checks: list[str] = []

    def need(self, condition: bool, label: str) -> bool:
        if condition:
            self.checks.append(label)
            return True
        self.missing.append(label)
        return False

    def expect(self, condition: bool, label: str) -> bool:
        if condition:
            self.checks.append(label)
            return True
        self.failed.append(label)
        return False


def client_events(run_dir: Path, manifest: dict[str, Any], root_id: str,
                  audit: Audit) -> tuple[list[dict[str, Any]], str]:
    files = manifest.get("eventFiles")
    if not audit.need(isinstance(files, list) and bool(files), "headless_event_files_present"):
        return [], ""
    found: list[dict[str, Any]] = []
    answer = ""
    base = run_dir.resolve()
    for raw_name in files:
        path = (run_dir / text(raw_name)).resolve()
        if not audit.need(path.is_relative_to(base) and path.is_file(),
                          f"headless_event_file_readable:{raw_name}"):
            continue
        for line_no, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if not line.strip():
                continue
            try:
                row = json.loads(line)
            except json.JSONDecodeError:
                audit.missing.append(f"headless_event_json_invalid:{path.name}:{line_no}")
                continue
            if isinstance(row, dict) and text(row.get("runId")) == root_id:
                found.extend(objects(row.get("events")))
                answer = text(row.get("answer")) or answer
    audit.need(bool(found), "parent_client_events_present")
    return found, answer


def validate_common(run_dir: Path, scenario_id: str, child_evidence: dict[str, Any],
                    audit: Audit) -> tuple[str, dict[str, Any], list[dict[str, Any]],
                                           list[dict[str, Any]], list[dict[str, Any]],
                                           list[dict[str, Any]]]:
    summary = read_object(run_dir / "load_summary.json")
    headless_evidence = read_object(run_dir / "evidence_manifest.json")
    run_manifest = read_object(run_dir / "run_manifest.json")
    all_roots = objects(run_manifest.get("runs"))
    audit.need(len(all_roots) == 1, "single_root_run_in_batch")
    roots = [item for item in all_roots
             if text(item.get("scenarioId")) == scenario_id]
    audit.need(len(roots) == 1, "exactly_one_root_run_for_scenario")
    root = roots[0] if len(roots) == 1 else {}
    root_id = text(root.get("runId"))
    audit.need(bool(root_id), "root_run_id_present")
    if root_id:
        audit.expect(text(root.get("status")).upper() == "COMPLETED", "parent_run_completed")
    audit.expect(summary.get("status") == "passed" and summary.get("exit_code") == 0,
                 "headless_exit_passed")
    audit.need(summary.get("evidence_complete") is True, "headless_evidence_complete")
    audit.need(headless_evidence.get("environmentTier") in ("lane", "beta"),
               "headless_environment_tier_recorded")
    headless_manifest = headless_evidence.get("runManifest")
    headless_manifest = headless_manifest if isinstance(headless_manifest, dict) else {}
    audit.need(headless_manifest.get("runIds") == [root_id] and bool(root_id),
               "headless_manifest_matches_root")
    server = headless_evidence.get("serverEvidence")
    server = server if isinstance(server, dict) else {}
    server_payload = server.get("payload")
    server_payload = server_payload if isinstance(server_payload, dict) else {}
    audit.need(server.get("validationIssues") == [] and server_payload.get("source") == "server_read_only"
               and server_payload.get("runIds") == [root_id],
               "headless_server_evidence_matches_root")
    if headless_evidence.get("environmentTier") == "lane":
        lane = headless_evidence.get("laneEvidence")
        lane = lane if isinstance(lane, dict) else {}
        trace = lane.get("traceAudit")
        trace = trace if isinstance(trace, dict) else {}
        routing = server_payload.get("laneRouting")
        routing = routing if isinstance(routing, dict) else {}
        audit.need(trace.get("allMatched") is True and routing.get("verified") is True,
                   "lane_header_and_server_routing_verified")
    audit.need(child_evidence.get("source") == "server_read_only", "child_evidence_server_source")
    audit.need(child_evidence.get("complete") is True, "child_evidence_complete")
    audit.need(text(child_evidence.get("rootRunId")) == root_id and bool(root_id),
               "child_evidence_matches_root")
    start = read_time(summary.get("started_at"))
    end = read_time(summary.get("ended_at"))
    window = child_evidence.get("window")
    window = window if isinstance(window, dict) else {}
    server_start = read_time(window.get("startedAt"))
    server_end = read_time(window.get("endedAt"))
    audit.need(bool(start and end and server_start and server_end
                    and server_start <= start <= end <= server_end),
               "server_window_covers_headless_run")
    if root_id:
        _, answer = client_events(run_dir, headless_evidence, root_id, audit)
        root["answer"] = answer
        audit.need(bool(answer), "parent_answer_recorded")

    children = objects(child_evidence.get("childRuns"))
    intents = objects(child_evidence.get("creationIntents"))
    waits = objects(child_evidence.get("waitGroups"))
    events = objects(child_evidence.get("runEvents"))
    for name, raw in (("childRuns", child_evidence.get("childRuns")),
                      ("creationIntents", child_evidence.get("creationIntents")),
                      ("waitGroups", child_evidence.get("waitGroups")),
                      ("runEvents", child_evidence.get("runEvents"))):
        audit.need(isinstance(raw, list) and bool(raw), f"server_{name}_present")
    return root_id, root, children, intents, waits, events


def validate_identities(root_id: str, children: list[dict[str, Any]],
                        intents: list[dict[str, Any]], events: list[dict[str, Any]],
                        expected_children: int, audit: Audit) -> list[str]:
    audit.need(len(children) == expected_children, f"child_count_{expected_children}")
    child_ids = [text(row.get("runId")) for row in children]
    audit.need(all(child_ids) and len(set(child_ids)) == len(child_ids), "unique_child_run_ids")
    intent_by_child = {text(row.get("childRunId")): row for row in intents}
    audit.need(len(intent_by_child) == len(intents), "unique_creation_intents")
    for child in children:
        child_id = text(child.get("runId"))
        audit.need(text(child.get("parentRunId")) == root_id
                   and text(child.get("rootRunId")) == root_id,
                   f"child_parent_root_relation:{child_id}")
        audit.expect(text(child.get("status")).upper() == "COMPLETED",
                     f"child_completed:{child_id}")
        intent = intent_by_child.get(child_id)
        if not audit.need(intent is not None, f"creation_intent_for_child:{child_id}"):
            continue
        for field in ("intentId", "operationId", "outboxId", "toolCallId", "parentNodeId"):
            audit.need(bool(text(intent.get(field))), f"creation_{field}:{child_id}")
        audit.need(text(intent.get("parentRunId")) == root_id
                   and text(intent.get("rootRunId")) == root_id,
                   f"intent_parent_root_relation:{child_id}")
        audit.need(text(intent.get("status")).upper() == "ACCEPTED",
                   f"creation_accepted:{child_id}")
    for run_id in [root_id, *child_ids]:
        audit.need(any(text(event.get("runId")) == run_id for event in events),
                   f"server_run_events:{run_id}")
    return child_ids


def validate_wait(wait: dict[str, Any], root_id: str, expected_ids: list[str],
                  audit: Audit) -> list[dict[str, Any]]:
    call_id = text(wait.get("toolCallId"))
    audit.need(bool(text(wait.get("groupId")) and call_id
                    and text(wait.get("resumeNotificationId"))),
               f"wait_identity_and_resume:{call_id or '<missing>'}")
    audit.need(text(wait.get("parentRunId")) == root_id, f"wait_parent:{call_id}")
    requested = wait.get("requestedChildRunIds")
    audit.need(isinstance(requested, list) and requested == expected_ids,
               f"wait_requested_order:{call_id}")
    results = objects(wait.get("results"))
    audit.expect(len(results) == len(expected_ids)
                 and [text(item.get("childRunId")) for item in results] == expected_ids,
                 f"wait_result_order:{call_id}")
    audit.need(bool(read_time(wait.get("completedAt"))), f"wait_completed_at:{call_id}")
    return results


def validate_scenario(scenario: str, root: dict[str, Any], root_id: str,
                      children: list[dict[str, Any]], intents: list[dict[str, Any]],
                      waits: list[dict[str, Any]], events: list[dict[str, Any]],
                      child_evidence: dict[str, Any], audit: Audit) -> None:
    expected_count = 1 if scenario in ("single_child", "wait_timeout") else 2
    child_ids = validate_identities(root_id, children, intents, events, expected_count, audit)
    if len(child_ids) != expected_count or not all(child_ids):
        return
    if scenario == "single_child":
        audit.need(len(waits) == 1, "one_result_wait")
        if len(waits) != 1:
            return
        results = validate_wait(waits[0], root_id, child_ids, audit)
        result = results[0] if len(results) == 1 else {}
        audit.expect(text(result.get("status")).upper() in ("SUCCEEDED", "COMPLETED"),
                     "child_wait_result_succeeded")
        audit.expect("42" in text(result.get("resultText"))
                     and "42" in text(root.get("answer")), "real_child_result_reached_parent_answer")
    elif scenario == "out_of_order":
        audit.need(len(waits) == 1, "one_multi_child_wait")
        if len(waits) != 1:
            return
        wait = waits[0]
        requested = wait.get("requestedChildRunIds")
        requested_valid = isinstance(requested, list) and len(requested) == 2 \
            and all(isinstance(item, str) for item in requested) \
            and set(requested) == set(child_ids)
        audit.need(requested_valid, "multi_wait_has_two_children")
        if not requested_valid:
            return
        results = validate_wait(wait, root_id, requested, audit)
        by_id = {text(item.get("runId")): item for item in children}
        first_done = read_time(by_id[requested[0]].get("terminalAt"))
        second_done = read_time(by_id[requested[1]].get("terminalAt"))
        audit.need(bool(first_done and second_done), "both_child_terminal_times")
        if first_done and second_done:
            audit.expect(second_done < first_done, "second_child_finished_first")
        audit.expect(len(results) == 2 and all(text(item.get("status")).upper()
                                                in ("SUCCEEDED", "COMPLETED") for item in results),
                     "both_wait_results_succeeded")
        control = child_evidence.get("externalResultControl")
        control = control if isinstance(control, dict) else {}
        released_at = read_time(control.get("releasedAt"))
        audit.need(control.get("realSandbox") is True
                   and text(control.get("heldChildRunId")) == requested[0]
                   and bool(released_at),
                   "real_sandbox_result_hold_proven")
        if second_done and released_at:
            audit.expect(second_done < released_at, "first_sandbox_released_after_second_child")
        if first_done and released_at:
            audit.expect(released_at <= first_done, "first_child_finished_after_sandbox_release")
    else:
        audit.need(len(waits) >= 2, "timeout_then_second_wait")
        if len(waits) < 2:
            return
        audit.need(all(read_time(row.get("startedAt")) for row in waits),
                   "wait_start_times_present")
        if not all(read_time(row.get("startedAt")) for row in waits):
            return
        ordered = sorted(waits, key=lambda row: read_time(row.get("startedAt")))
        first, second = ordered[0], ordered[1]
        first_results = validate_wait(first, root_id, child_ids, audit)
        second_results = validate_wait(second, root_id, child_ids, audit)
        deadline = read_time(first.get("deadlineAt"))
        first_done = read_time(first.get("completedAt"))
        child_done = read_time(children[0].get("terminalAt"))
        audit.need(bool(deadline and first_done and child_done), "timeout_timestamps_present")
        if deadline and first_done and child_done:
            audit.expect(first_done >= deadline and child_done > deadline,
                         "wait_expired_before_child_terminal")
        second_started = read_time(second.get("startedAt"))
        if first_done and second_started:
            audit.expect(second_started >= first_done, "second_wait_after_first_timeout")
        audit.expect(len(first_results) == 1
                     and text(first_results[0].get("status")).upper() == "WAIT_TIMEOUT",
                     "first_wait_returns_current_timeout_state")
        audit.expect(len(second_results) == 1
                     and text(second_results[0].get("status")).upper() in ("SUCCEEDED", "COMPLETED"),
                     "second_wait_returns_terminal_result")
        permit = objects(child_evidence.get("parentWaitPermitSamples"))
        first_started = read_time(first.get("startedAt"))
        audit.need(any(text(item.get("parentRunId")) == root_id
                       and item.get("nodePermits") == 0
                       and item.get("coordinationPermits") == 0
                       and first_started and first_done and read_time(item.get("at"))
                       and first_started <= read_time(item.get("at")) <= first_done
                       for item in permit), "parent_wait_released_both_permits")


def audit_run(run_dir: Path, scenario: str, scenario_id: str,
              child_evidence_file: Path) -> dict[str, Any]:
    audit = Audit()
    child_evidence = read_object(child_evidence_file) if child_evidence_file.is_file() else {}
    root_id, root, children, intents, waits, events = validate_common(
        run_dir, scenario_id, child_evidence, audit)
    validate_scenario(scenario, root, root_id, children, intents, waits, events,
                      child_evidence, audit)
    status = "evidence_incomplete" if audit.missing else (
        "criteria_failed" if audit.failed else "passed")
    return {
        "schemaVersion": 1,
        "scenario": scenario,
        "scenarioId": scenario_id,
        "status": status,
        "rootRunId": root_id,
        "childRunIds": [text(child.get("runId")) for child in children],
        "creationIntentIds": [text(item.get("intentId")) for item in intents],
        "waitGroupIds": [text(item.get("groupId")) for item in waits],
        "runEventTypes": {run_id: event_types([event for event in events
                                            if text(event.get("runId")) == run_id])
                          for run_id in [root_id, *[text(child.get("runId")) for child in children]]},
        "checksPassed": sorted(set(audit.checks)),
        "missingEvidence": sorted(set(audit.missing)),
        "failedCriteria": sorted(set(audit.failed)),
        "sources": {"headlessRunDir": str(run_dir), "childEvidenceFile": str(child_evidence_file)},
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-dir", type=Path, required=True,
                        help="已有 headless 执行器的本次独立输出目录")
    parser.add_argument("--scenario", choices=SCENARIOS, required=True)
    parser.add_argument("--scenario-id", required=True,
                        help="run_manifest.json 中本次唯一的场景名")
    parser.add_argument("--child-evidence-file", type=Path, required=True,
                        help="当次服务端只读采集器输出，不能手工编造")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        result = audit_run(args.run_dir, args.scenario, args.scenario_id,
                           args.child_evidence_file)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        result = {"schemaVersion": 1, "status": "precondition_error", "reason": str(exc)}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": result["status"], "output": str(args.output)}, ensure_ascii=False))
    return {"passed": 0, "criteria_failed": 1, "evidence_incomplete": 2,
            "precondition_error": 3}[result["status"]]


if __name__ == "__main__":
    sys.exit(main())
