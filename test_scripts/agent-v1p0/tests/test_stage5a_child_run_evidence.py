"""父子证据判定器的正常、乱序、超时与缺失证据行为。"""

from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "stage5a_child_run_evidence.py"
SPEC = importlib.util.spec_from_file_location("stage5a_child_run_evidence", SCRIPT)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def write(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")


def child(run_id: str, at: str) -> dict:
    return {"runId": run_id, "parentRunId": "parent", "rootRunId": "parent",
            "status": "COMPLETED", "terminalAt": at}


def intent(run_id: str) -> dict:
    return {"intentId": f"intent-{run_id}", "operationId": f"op-{run_id}",
            "outboxId": f"ob-{run_id}", "toolCallId": f"spawn-{run_id}",
            "parentNodeId": "N4", "childRunId": run_id, "parentRunId": "parent",
            "rootRunId": "parent", "status": "TERMINAL",
            "acceptedAt": "2026-09-24T10:01:00Z", "outboxStatus": "ACKED"}


def wait(ids: list[str], results: list[dict], started: str, completed: str,
         deadline: str | None = None) -> dict:
    return {"groupId": f"wg-{started}", "parentRunId": "parent", "toolCallId": "wait-1",
            "resumeNotificationId": f"rn-{started}", "requestedChildRunIds": ids,
            "results": results, "startedAt": started, "completedAt": completed,
            "deadlineAt": deadline}


class Stage5AChildRunEvidenceTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.run_dir = Path(self.temp.name)
        write(self.run_dir / "load_summary.json", {
            "status": "passed", "exit_code": 0, "evidence_complete": True,
            "started_at": "2026-09-24T10:00:00Z", "ended_at": "2026-09-24T10:06:00Z"})
        write(self.run_dir / "run_manifest.json", {"runs": [
            {"runId": "parent", "scenarioId": "case", "status": "COMPLETED"}]})
        write(self.run_dir / "evidence_manifest.json", {
            "environmentTier": "lane", "eventFiles": ["agent-runs-batch000.jsonl"],
            "runManifest": {"runIds": ["parent"]},
            "serverEvidence": {"validationIssues": [], "payload": {
                "source": "server_read_only", "runIds": ["parent"],
                "laneRouting": {"verified": True}}},
            "laneEvidence": {"traceAudit": {"allMatched": True}},
        })
        (self.run_dir / "agent-runs-batch000.jsonl").write_text(json.dumps({
            "runId": "parent", "answer": "子代理得到 42",
            "events": [{"type": "RUN_RECEIVED"}, {"type": "MESSAGE_COMPLETED"}]}) + "\n",
            encoding="utf-8")
        self.evidence_path = self.run_dir / "child-evidence.json"
        self.base = {
            "source": "server_read_only", "complete": True, "rootRunId": "parent",
            "window": {"startedAt": "2026-09-24T09:59:00Z",
                       "endedAt": "2026-09-24T10:07:00Z"},
            "childRuns": [], "creationIntents": [], "waitGroups": [], "runEvents": [],
        }

    def audit(self, scenario: str, payload: dict | None = None) -> dict:
        if payload is not None:
            write(self.evidence_path, payload)
        return MODULE.audit_run(self.run_dir, scenario, "case", self.evidence_path)

    def test_single_real_child_result_has_complete_proof(self) -> None:
        proof = self.base | {
            "childRuns": [child("child-1", "2026-09-24T10:04:00Z")],
            "creationIntents": [intent("child-1")],
            "waitGroups": [wait(["child-1"], [
                {"childRunId": "child-1", "status": "SUCCEEDED", "resultText": "42"}],
                "2026-09-24T10:02:00Z", "2026-09-24T10:05:00Z")],
            "runEvents": [{"runId": "parent", "eventType": "RUN_RECEIVED"},
                          {"runId": "child-1", "eventType": "RUN_RECEIVED"}],
        }
        result = self.audit("single_child", proof)
        self.assertEqual(result["status"], "passed")
        self.assertEqual(result["childRunIds"], ["child-1"])
        self.assertEqual(result["creationIntentIds"], ["intent-child-1"])
        output = self.run_dir / "stage5a_evidence.json"
        self.assertEqual(MODULE.main([
            "--run-dir", str(self.run_dir), "--scenario", "single_child",
            "--scenario-id", "case", "--child-evidence-file", str(self.evidence_path),
            "--output", str(output)]), 0)
        self.assertEqual(json.loads(output.read_text(encoding="utf-8"))["status"], "passed")

        proof["creationIntents"][0]["status"] = "ACCEPTED"
        self.assertEqual(self.audit("single_child", proof)["status"], "passed")
        proof["creationIntents"][0]["acceptedAt"] = None
        result = self.audit("single_child", proof)
        self.assertEqual(result["status"], "evidence_incomplete")
        self.assertIn("creation_accepted:child-1", result["missingEvidence"])

    def test_second_child_finishes_first_but_results_keep_request_order(self) -> None:
        proof = self.base | {
            "childRuns": [child("first", "2026-09-24T10:04:00Z"),
                          child("second", "2026-09-24T10:03:00Z")],
            "creationIntents": [intent("first"), intent("second")],
            "waitGroups": [wait(["first", "second"], [
                {"childRunId": "first", "status": "SUCCEEDED"},
                {"childRunId": "second", "status": "SUCCEEDED"}],
                "2026-09-24T10:02:00Z", "2026-09-24T10:05:00Z")],
            "runEvents": [{"runId": run_id, "eventType": "RUN_RECEIVED"}
                          for run_id in ("parent", "first", "second")],
            "externalResultControl": {"realSandbox": True, "heldChildRunId": "first",
                                      "releasedAt": "2026-09-24T10:03:30Z"},
        }
        self.assertEqual(self.audit("out_of_order", proof)["status"], "passed")
        proof["waitGroups"][0]["results"].reverse()
        result = self.audit("out_of_order", proof)
        self.assertEqual(result["status"], "criteria_failed")
        self.assertIn("wait_result_order:wait-1", result["failedCriteria"])

    def test_timeout_then_second_wait_uses_persisted_deadline(self) -> None:
        proof = self.base | {
            "childRuns": [child("child-1", "2026-09-24T10:04:00Z")],
            "creationIntents": [intent("child-1")],
            "waitGroups": [
                wait(["child-1"], [{"childRunId": "child-1", "status": "WAIT_TIMEOUT"}],
                     "2026-09-24T10:01:00Z", "2026-09-24T10:02:00Z",
                     "2026-09-24T10:02:00Z"),
                wait(["child-1"], [{"childRunId": "child-1", "status": "SUCCEEDED"}],
                     "2026-09-24T10:03:00Z", "2026-09-24T10:05:00Z")],
            "runEvents": [{"runId": run_id, "eventType": "RUN_RECEIVED"}
                          for run_id in ("parent", "child-1")],
            "parentWaitPermitSamples": [{"parentRunId": "parent",
                                         "at": "2026-09-24T10:01:30Z",
                                         "nodePermits": 0, "coordinationPermits": 0}],
        }
        self.assertEqual(self.audit("wait_timeout", proof)["status"], "passed")
        del proof["parentWaitPermitSamples"]
        result = self.audit("wait_timeout", proof)
        self.assertEqual(result["status"], "evidence_incomplete")
        self.assertIn("parent_wait_released_both_permits", result["missingEvidence"])

    def test_missing_server_read_only_file_cannot_pass(self) -> None:
        result = self.audit("single_child")
        self.assertEqual(result["status"], "evidence_incomplete")
        self.assertIn("child_evidence_server_source", result["missingEvidence"])

    def test_lane_route_must_be_verified_by_headless_evidence(self) -> None:
        path = self.run_dir / "evidence_manifest.json"
        manifest = json.loads(path.read_text(encoding="utf-8"))
        manifest["serverEvidence"]["payload"]["laneRouting"]["verified"] = False
        write(path, manifest)
        result = self.audit("single_child")
        self.assertEqual(result["status"], "evidence_incomplete")
        self.assertIn("lane_header_and_server_routing_verified", result["missingEvidence"])


if __name__ == "__main__":
    unittest.main()
