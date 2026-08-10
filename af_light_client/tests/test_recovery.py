from __future__ import annotations

import unittest

from af_light_client.events import RunViewState
from af_light_client.recovery import AgentStreamRecovery, RecoveryFailure


class _Warnings:
    def __init__(self) -> None:
        self.items: list[str] = []

    def add(self, text: str) -> None:
        self.items.append(text)


class _Client:
    def __init__(self) -> None:
        self.pages: list[object] = []
        self.status = {"status": "EXECUTING", "lastSeq": 0}
        self.after_seq: list[int] = []

    def get_events(self, template: str, run_id: str, *, after_seq: int, limit: int) -> dict:
        self.after_seq.append(after_seq)
        value = self.pages.pop(0) if self.pages else {"items": [], "hasMore": False, "nextAfterSeq": after_seq}
        if isinstance(value, Exception):
            raise value
        return value  # type: ignore[return-value]

    def get_status(self, template: str, run_id: str) -> dict:
        return self.status


def _recovery(client: _Client, *, sleep=lambda _: None, monotonic=lambda: 0.0) -> AgentStreamRecovery:
    return AgentStreamRecovery(
        client,
        events_endpoint_template="/runs/{run_id}/events",
        status_endpoint_template="/runs/{run_id}/status",
        run_id="run-1",
        state=RunViewState(max_trace_lines=20),
        warnings=_Warnings(),
        sleep=sleep,
        monotonic=monotonic,
    )


class AgentStreamRecoveryTest(unittest.TestCase):
    def test_live_only_reaches_view_without_advancing_cursor(self) -> None:
        recovery = _recovery(_Client())
        recovery.confirmed_cursor = 7

        recovery.ingest_sse(
            "agent.event",
            {"schemaVersion": 1, "runId": "run-1", "seq": 0, "durable": False,
             "eventType": "NOTICE", "payload": {"message": "live-only"}},
        )

        self.assertEqual(recovery.confirmed_cursor, 7)
        self.assertTrue(any("live-only" in line for line in recovery.state.snapshot().trace))

    def test_gap_repairs_from_confirmed_cursor_and_accepts_legal_rest_gaps(self) -> None:
        client = _Client()
        client.pages = [{
            "items": [
                {"runId": "run-1", "seq": 3, "eventType": "THREE", "payload": {}, "durable": True},
                {"runId": "run-1", "seq": 5, "eventType": "FIVE", "payload": {}, "durable": True},
            ],
            "nextAfterSeq": 5,
            "hasMore": False,
        }]
        recovery = _recovery(client)
        recovery.confirmed_cursor = 1

        recovery.ingest_sse(
            "agent.event",
            {"runId": "run-1", "seq": 5, "eventType": "FIVE", "payload": {}, "durable": True},
        )

        self.assertEqual(client.after_seq, [1])
        self.assertEqual(recovery.confirmed_cursor, 5)
        self.assertEqual(recovery.state.snapshot().last_seq, 5)
        self.assertEqual(recovery.state.snapshot().trace.count("FIVE"), 1)

    def test_rest_failures_retry_250_500_1000_then_stop(self) -> None:
        client = _Client()
        client.pages = [RuntimeError("down")] * 4
        sleeps: list[float] = []
        recovery = _recovery(client, sleep=sleeps.append)

        with self.assertRaises(RecoveryFailure) as ctx:
            recovery.repair(target_seq=10)

        self.assertEqual(ctx.exception.code, "RECOVERY_FAILED")
        self.assertEqual(sleeps, [0.25, 0.5, 1.0])
        self.assertFalse(recovery.auto_retry_enabled)

    def test_healthy_recovery_over_30_seconds_has_explicit_sla_error(self) -> None:
        times = iter([0.0, 31.0])
        recovery = _recovery(_Client(), monotonic=lambda: next(times))

        with self.assertRaises(RecoveryFailure) as ctx:
            recovery.repair(target_seq=1)

        self.assertEqual(ctx.exception.code, "RECOVERY_SLA_EXCEEDED")
        self.assertFalse(recovery.auto_retry_enabled)

    def test_large_run_temporarily_uses_rest_only_without_sla_then_reconnects(self) -> None:
        client = _Client()
        client.pages = [{"items": [], "nextAfterSeq": 0, "hasMore": False}]
        recovery = _recovery(client, monotonic=lambda: 9999.0)
        recovery.ingest_sse(
            "snapshot",
            {"runId": "run-1", "eventCount": 5001, "lastSeq": 0,
             "status": "EXECUTING", "events": []},
        )

        self.assertTrue(recovery.degraded_large_run)
        recovery.recover_degraded_large_run_once()

        self.assertFalse(recovery.degraded_large_run)
        self.assertTrue(recovery.reconnect_requested)

    def test_snapshot_dual_reads_legacy_event_without_schema_version(self) -> None:
        recovery = _recovery(_Client())
        recovery.ingest_sse(
            "snapshot",
            {"runId": "run-1", "lastSeq": 9, "eventCount": 1, "events": [
                {"runId": "run-1", "seq": 9, "eventType": "LEGACY",
                 "payloadJson": "{\"message\":\"old\"}"},
            ]},
        )

        self.assertEqual(recovery.confirmed_cursor, 9)
        self.assertTrue(any("old" in line for line in recovery.state.snapshot().trace))


if __name__ == "__main__":
    unittest.main()
