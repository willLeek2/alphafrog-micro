from __future__ import annotations

import json
import tempfile
import unittest
from datetime import datetime
from pathlib import Path
from typing import Optional

from af_light_client.__main__ import _finish_with_rest
from af_light_client.config import LightClientConfig
from af_light_client.debug import DebugRunLogger, redact
from af_light_client.events import RunViewState
from af_light_client.http_client import WarningStore


class RedactTest(unittest.TestCase):
    def test_redacts_sensitive_keys_and_auth_strings(self) -> None:
        payload = {
            "password": "p",
            "Authorization": "Bearer abc.def",
            "url": "http://x/stream?token=abc&other=1",
            "headers": {"Cookie": "access_token=secret; Path=/"},
            "nested": [{"api_key": "k"}],
        }
        redacted = redact(payload)
        self.assertEqual(redacted["password"], "<redacted>")
        self.assertEqual(redacted["Authorization"], "<redacted>")
        self.assertEqual(redacted["headers"]["Cookie"], "<redacted>")
        self.assertEqual(redacted["nested"][0]["api_key"], "<redacted>")
        self.assertIn("token=<redacted>", redacted["url"])


class DebugRunLoggerTest(unittest.TestCase):
    def test_disabled_logger_creates_no_directory(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            logger = DebugRunLogger(enabled=False, output_root=td)
            logger.write_json("x.json", {"a": 1})
            self.assertEqual(list(Path(td).iterdir()), [])

    def test_enabled_logger_writes_timestamped_redacted_files(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            logger = DebugRunLogger(
                enabled=True,
                output_root=td,
                now=datetime(2026, 6, 11, 21, 40, 1),
            )
            logger.write_json("config.json", {"password": "secret", "ok": 1})
            logger.append_jsonl("events.jsonl", {"url": "http://x?a=1&token=secret"})
            run_dir = Path(td) / "20260611-214001"
            self.assertTrue(run_dir.is_dir())
            config = json.loads((run_dir / "config.json").read_text())
            self.assertEqual(config["password"], "<redacted>")
            self.assertEqual(config["ok"], 1)
            line = (run_dir / "events.jsonl").read_text().strip()
            self.assertIn("token=<redacted>", line)


class FinishWithRestDebugTest(unittest.TestCase):
    def test_debug_disabled_skips_debug_only_rest_fetches(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            cfg = _config(debug_logs=False, debug_output_root=td)
            client = _RestClient()
            state = RunViewState()
            warnings = WarningStore()
            debug = DebugRunLogger(enabled=False, output_root=td)

            _finish_with_rest(cfg, client, state, warnings, "run-1", debug)

            self.assertEqual(client.calls, ["status", "result"])
            self.assertEqual(state.snapshot().status, "COMPLETED")
            self.assertEqual(state.snapshot().final_answer, "final text")
            self.assertEqual(list(Path(td).iterdir()), [])

    def test_debug_enabled_writes_rest_payloads_and_observability_fallback(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            cfg = _config(debug_logs=True, debug_output_root=td)
            client = _RestClient(observability_error=RuntimeError("too large"))
            state = RunViewState()
            warnings = WarningStore()
            debug = DebugRunLogger(
                enabled=True,
                output_root=td,
                now=datetime(2026, 6, 11, 21, 40, 1),
            )

            _finish_with_rest(cfg, client, state, warnings, "run-1", debug)

            self.assertEqual(client.calls, ["status", "events", "result", "observability", "timeline"])
            run_dir = Path(td) / "20260611-214001"
            self.assertTrue((run_dir / "status_fallback.json").is_file())
            self.assertTrue((run_dir / "events_fallback.json").is_file())
            self.assertTrue((run_dir / "result_fallback.json").is_file())
            self.assertTrue((run_dir / "observability_error.json").is_file())
            self.assertTrue((run_dir / "timeline_fallback.json").is_file())
            self.assertTrue(any("observability fallback failed" in item for item in warnings.snapshot()))


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
