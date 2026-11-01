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

if __name__ == "__main__":
    unittest.main()
