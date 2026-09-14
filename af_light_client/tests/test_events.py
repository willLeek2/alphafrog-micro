from __future__ import annotations

import unittest

from af_light_client.events import RunViewState, is_terminal, normalize_sse_frame, resolve_agent_payload


class RunStatusLastSeqTest(unittest.TestCase):
    def test_run_view_state_uses_last_seq_from_run_status(self) -> None:
        state = RunViewState()
        state.ingest_sse("run.status", {"status": "EXECUTING", "phase": "execution", "lastSeq": 17})
        snap = state.snapshot()
        self.assertEqual(snap.status, "EXECUTING")
        self.assertEqual(snap.phase, "execution")
        self.assertEqual(snap.last_seq, 17)

    def test_normalize_sse_frame_uses_last_seq_from_run_status(self) -> None:
        events = normalize_sse_frame(
            "run.status",
            {"status": "EXECUTING", "phase": "execution", "lastSeq": 23},
            current_workflow="dag",
        )
        self.assertEqual(len(events), 1)
        self.assertEqual(events[0].seq, 23)
        self.assertEqual(events[0].status, "EXECUTING")
        self.assertEqual(events[0].workflow, "dag")


class RunViewStateLayoutRegressionTest(unittest.TestCase):
    def test_payload_dual_read_wraps_canonical_and_legacy_scalars(self) -> None:
        self.assertEqual(resolve_agent_payload({"payload": True}), ({"value": True}, "payload"))
        self.assertEqual(
            resolve_agent_payload({"payloadJson": "[1, 2]"}),
            ({"value": [1, 2]}, "payloadJson"),
        )

    def test_durable_identity_is_run_id_and_seq_not_event_type(self) -> None:
        state = RunViewState(max_trace_lines=5)
        state.set_run_id("run-1")
        state.ingest_agent_event(
            {"runId": "run-1", "seq": 2, "eventType": "A", "payload": {"message": "first"}}
        )
        state.ingest_agent_event(
            {"runId": "run-1", "seq": 2, "eventType": "B", "payload": {"message": "duplicate"}}
        )
        self.assertEqual(state.snapshot().trace, ["first"])

    def test_terminal_aliases_are_normalized_but_canceling_is_not_terminal(self) -> None:
        state = RunViewState()
        state.ingest_sse("run.status", {"status": " cancelled "})
        self.assertEqual(state.snapshot().status, "CANCELED")
        self.assertTrue(is_terminal("timeout"))
        self.assertFalse(is_terminal("CANCELING"))

    def test_warning_copy_is_deduped_across_renders(self) -> None:
        state = RunViewState(max_warnings=4)
        state.add_warning("login ok")
        state.add_warning("run created")
        state.add_warning("login ok")
        self.assertEqual(state.snapshot().warnings, ["login ok", "run created"])

    def test_llm_delta_updates_seq_without_spamming_trace(self) -> None:
        state = RunViewState(max_trace_lines=5)
        state.ingest_agent_event({"seq": 1, "eventType": "LLM_CALL_STARTED", "payload": {"model": "m"}})
        state.ingest_agent_event(
            {
                "seq": 2,
                "eventType": "LLM_CALL_DELTA",
                "payload": {"chunk_count": 100, "estimated_output_tokens": 50},
            }
        )
        state.ingest_agent_event(
            {
                "seq": 3,
                "eventType": "LLM_CALL_FINISHED",
                "payload": {"chunk_count": 100, "total_tokens": 50, "duration_ms": 12},
            }
        )
        snap = state.snapshot()
        self.assertEqual(snap.last_seq, 3)
        self.assertEqual(len(snap.trace), 2)
        self.assertTrue(any("llm call开始" in line for line in snap.trace))
        self.assertTrue(any("llm call完成" in line for line in snap.trace))
        self.assertFalse(any("进行中" in line for line in snap.trace))


if __name__ == "__main__":
    unittest.main()
