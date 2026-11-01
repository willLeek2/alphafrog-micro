from __future__ import annotations

import json
import tempfile
import unittest
from datetime import datetime
from pathlib import Path
from typing import Optional

import argparse
import threading
import time

from af_light_client.__main__ import PeriodicTuiSnapshotLoop, _finish_with_rest, _render
from af_light_client.config import LightClientConfig, TuiSnapshotDebugConfig
from af_light_client.debug import DebugRunLogger, TuiBatchSnapshotWriter, redact
from af_light_client.events import RunViewState, ViewSnapshot
from af_light_client.http_client import WarningStore
from af_light_client.render import TerminalRenderer


class RedactTest(unittest.TestCase):
    pass
class DebugRunLoggerTest(unittest.TestCase):
    pass
class TuiBatchSnapshotWriterTest(unittest.TestCase):
    pass
class SnapshotTextTest(unittest.TestCase):
    def _snapshot(self) -> ViewSnapshot:
        return ViewSnapshot(
            run_id="run-abcdef123456",
            status="RUNNING",
            phase="plan",
            workflow="linear",
            last_seq=3,
            trace=["step one failed", "step two completed"],
            dag_nodes={},
            warnings=["login ok"],
            final_answer="done",
        )

class RenderNoTuiTest(unittest.TestCase):
    pass
class PeriodicTuiSnapshotLoopTest(unittest.TestCase):
    pass
class FinishWithRestDebugTest(unittest.TestCase):
    pass
def _config(*, debug_logs: bool, debug_output_root: str) -> LightClientConfig:
    return LightClientConfig(
        base_url="http://example.com",
        username="u",
        password="p",
        question="q",
        debug_logs=debug_logs,
        debug_output_root=debug_output_root,
    )


class _RestClient:
    def __init__(self, *, observability_error: Optional[Exception] = None) -> None:
        self.calls: list[str] = []
        self.observability_error = observability_error

    def get_status(self, template: str, run_id: str) -> dict:
        self.calls.append("status")
        return {"status": "COMPLETED", "phase": "done", "lastSeq": 9}

    def get_events(self, template: str, run_id: str, *, after_seq: int = 0, limit: int = 500) -> dict:
        self.calls.append("events")
        return {"items": [{"seq": 9}]}

    def get_result(self, template: str, run_id: str) -> dict:
        self.calls.append("result")
        return {"answer": "final text"}

    def get_observability_full(self, template: str, run_id: str) -> dict:
        self.calls.append("observability")
        if self.observability_error is not None:
            raise self.observability_error
        return {"trace": []}

    def get_timeline(self, template: str, run_id: str, *, after_seq: int = 0, limit: int = 500) -> dict:
        self.calls.append("timeline")
        return {"items": [{"seq": 9}]}


if __name__ == "__main__":
    unittest.main()
