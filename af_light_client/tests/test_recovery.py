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
    pass
if __name__ == "__main__":
    unittest.main()
