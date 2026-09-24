"""只读采集映射和缺连接时的失败行为。"""

from __future__ import annotations

import importlib.util
import json
import os
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[1] / "collect_stage5a_child_evidence.py"
SPEC = importlib.util.spec_from_file_location("collect_stage5a_child_evidence", SCRIPT)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class Cursor:
    def __init__(self, rows):
        self.rows = rows
        self.query = ""
        self.args = None

    def execute(self, query, args):
        self.query = query
        self.args = args

    def fetchall(self):
        return self.rows


class CollectStage5AChildEvidenceTest(unittest.TestCase):
    def test_terminal_intent_and_outbox_are_reported_without_rewriting_state(self):
        at = datetime(2026, 9, 24, 10, 0, tzinfo=timezone.utc)
        cursor = Cursor([{
            "intent_id": 9, "operation_id": "operation-9", "outbox_id": 12,
            "tool_call_id": "call-1", "parent_node_id": "todo-1",
            "child_run_id": "child-1", "parent_run_id": "parent", "root_run_id": "parent",
            "intent_state": "TERMINAL", "accepted_at": at, "outbox_state": "ACKED",
            "child_status": "COMPLETED", "child_terminal_at": at,
        }])

        intents, children = MODULE.collect_intents(cursor, "parent")

        self.assertEqual(cursor.args, ("parent",))
        self.assertIn("SELECT", cursor.query)
        self.assertEqual(intents[0]["status"], "TERMINAL")
        self.assertEqual(intents[0]["outboxStatus"], "ACKED")
        self.assertEqual(intents[0]["acceptedAt"], at.isoformat())
        self.assertEqual(children[0]["terminalAt"], at.isoformat())

    def test_wait_result_is_read_from_persisted_member_output(self):
        at = datetime(2026, 9, 24, 10, 0, tzinfo=timezone.utc)
        cursor = Cursor([{
            "group_id": 5, "parent_run_id": "parent", "tool_call_id": "wait-1",
            "dispatch_proof_json": {"requestedChildRunIds": ["child-1"],
                                    "deadlineAt": at.isoformat()},
            "result_ref_json": {"output": json.dumps({"ok": True, "data": {"results": [
                {"subAgentId": "child-1", "status": "COMPLETED", "result": "42"}
            ]}})},
            "created_at": at, "finished_at": at, "notification_id": 8,
        }])

        waits = MODULE.collect_waits(cursor, "parent")

        self.assertEqual(cursor.args, ("parent",))
        self.assertEqual(waits[0]["requestedChildRunIds"], ["child-1"])
        self.assertEqual(waits[0]["results"], [{
            "childRunId": "child-1", "status": "COMPLETED", "resultText": "42"}])
        self.assertEqual(waits[0]["resumeNotificationId"], 8)

    def test_missing_connection_cannot_create_server_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "receipt.json"
            with patch.dict(os.environ, {}, clear=True):
                exit_code = MODULE.main(["start", "--output", str(output)])
            self.assertEqual(exit_code, 2)
            self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
