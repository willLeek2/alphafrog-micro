"""Smoke tests for af_light_client.__main__ helpers.

Covers ``_print_credits_summary`` behaviour for the three observable cases
that matter for the 260612-01-04 requirement:

- ``interrupted=True`` (Ctrl+C during SSE): no GET, no print.
- Normal completion with a valid credits payload: one ``[credits]`` line
  containing run total, currency, and settlement counts.
- GET failure (network / HTTP error): prints a single ``[credits]`` warning
  line and does **not** raise into the caller (the TUI is already in the
  finally block when this runs).
"""
from __future__ import annotations

import io
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import MagicMock, patch

from af_light_client.__main__ import (
    _needs_cost_refresh,
    _print_credits_summary,
    _resolve_output_dir,
)
from af_light_client.config import LightClientConfig
from af_light_client.debug import DebugRunLogger


def _cfg() -> LightClientConfig:
    return LightClientConfig(
        base_url="http://example.com",
        username="u",
        password="p",
        question="q",
        credits_endpoint_template="/api/agent/runs/{run_id}/credits",
        credits_fetch_timeout_seconds=2.0,
        debug_logs=False,
    )


def _debug() -> DebugRunLogger:
    return DebugRunLogger(enabled=False, output_root="/tmp/af_light_client_unused")


def _credits_payload() -> dict:
    return {
        "runId": "run-7",
        "ownerUserId": "u-1",
        "totalCredits": "12.34",
        "currency": "USD",
        "summary": {
            "immediateCount": 8,
            "delayedCount": 2,
            "pendingCount": 0,
            "missingCount": 1,
            "totalCallCount": 11,
            "currency": "USD",
            "totalCredits": "12.34",
        },
        "records": [],
        "updatedAt": "2026-06-12T20:00:00Z",
    }


class PrintCreditsSummaryTest(unittest.TestCase):
    def test_interrupted_skips_fetch_and_print(self) -> None:
        cfg = _cfg()
        client = MagicMock()
        debug = _debug()
        buf = io.StringIO()
        with redirect_stdout(buf):
            _print_credits_summary(cfg, client, "run-7", interrupted=True, debug=debug)
        client.get_run_credits.assert_not_called()
        self.assertEqual(buf.getvalue(), "")

    def test_normal_completion_prints_summary_line(self) -> None:
        cfg = _cfg()
        client = MagicMock()
        client.get_run_credits.return_value = _credits_payload()
        debug = _debug()
        buf = io.StringIO()
        with redirect_stdout(buf):
            _print_credits_summary(cfg, client, "run-7", interrupted=False, debug=debug)
        client.get_run_credits.assert_called_once_with(
            cfg.credits_endpoint_template,
            "run-7",
            timeout=cfg.credits_fetch_timeout_seconds,
        )
        output = buf.getvalue().strip()
        self.assertTrue(output.startswith("[credits]"), output)
        self.assertIn("12.34 USD", output)
        self.assertIn("8 immediate", output)
        self.assertIn("2 delayed", output)
        self.assertIn("0 pending", output)
        self.assertIn("1 missing", output)
        self.assertIn("total calls 11", output)

    def test_fetch_failure_prints_warning_and_does_not_raise(self) -> None:
        cfg = _cfg()
        client = MagicMock()
        client.get_run_credits.side_effect = RuntimeError("net down")
        debug = _debug()
        buf = io.StringIO()
        with redirect_stdout(buf):
            # 不应向外抛异常
            _print_credits_summary(cfg, client, "run-7", interrupted=False, debug=debug)
        output = buf.getvalue().strip()
        self.assertIn("[credits] 查询失败", output)
        self.assertIn("net down", output)
        # debug 应该记录到 credits_error.json
        with patch.object(debug, "write_json") as wj:
            _print_credits_summary(
                _cfg(), MagicMock(get_run_credits=MagicMock(side_effect=RuntimeError("x"))),
                "run-7", interrupted=False, debug=debug,
            )
        wj.assert_called_once()
        self.assertEqual(wj.call_args.args[0], "credits_error.json")


class NeedsCostRefreshTest(unittest.TestCase):
    def _record(self, call_id: str, endpoint: str, cost_source: str, status: str, attempt: int) -> dict:
        return {
            "callId": call_id,
            "endpoint": endpoint,
            "costSource": cost_source,
            "settlementStatus": status,
            "settlementAttempt": attempt,
        }

    def test_no_calls_never_refresh(self) -> None:
        self.assertFalse(_needs_cost_refresh({"summary": {"totalCallCount": 0}, "records": []}))

    def test_non_openrouter_run_never_refresh(self) -> None:
        credits = {
            "summary": {"totalCallCount": 1},
            "records": [self._record("c1", "fireworks", "PER_CALL", "SETTLED", 1)],
        }
        self.assertFalse(_needs_cost_refresh(credits))

    def test_all_openrouter_settled_no_refresh(self) -> None:
        credits = {
            "summary": {"totalCallCount": 2},
            "records": [
                self._record("c1", "openrouter", "OPENROUTER_ACTUAL", "SETTLED", 1),
                self._record("c2", "openrouter", "OPENROUTER_ACTUAL", "SETTLED", 2),
            ],
        }
        self.assertFalse(_needs_cost_refresh(credits))

    def test_old_pending_with_new_settled_no_refresh(self) -> None:
        credits = {
            "summary": {"totalCallCount": 1},
            "records": [
                self._record("c1", "openrouter", "OPENROUTER_ACTUAL", "PENDING_RETRY", 1),
                self._record("c1", "openrouter", "OPENROUTER_ACTUAL", "SETTLED", 3),
            ],
        }
        self.assertFalse(_needs_cost_refresh(credits))

    def test_openrouter_pending_needs_refresh(self) -> None:
        credits = {
            "summary": {"totalCallCount": 1},
            "records": [self._record("c1", "openrouter", "OPENROUTER_ACTUAL", "PENDING_RETRY", 1)],
        }
        self.assertTrue(_needs_cost_refresh(credits))

    def test_openrouter_missing_needs_refresh(self) -> None:
        credits = {
            "summary": {"totalCallCount": 1},
            "records": [self._record("c1", "openrouter", "OPENROUTER_ACTUAL", "MISSING", 2)],
        }
        self.assertTrue(_needs_cost_refresh(credits))

    def test_openrouter_non_actual_settled_needs_refresh(self) -> None:
        credits = {
            "summary": {"totalCallCount": 1},
            "records": [self._record("c1", "openrouter", "PER_CALL", "SETTLED", 1)],
        }
        self.assertTrue(_needs_cost_refresh(credits))


class ResolveOutputDirTest(unittest.TestCase):
    def test_relative_path_resolves_to_project_root(self) -> None:
        path = _resolve_output_dir("af_light_client/output/foo")
        self.assertIsNotNone(path)
        self.assertTrue(path.is_absolute())
        self.assertTrue(path.name, "foo")

    def test_absolute_path_unchanged(self) -> None:
        path = _resolve_output_dir("/tmp/absolute/dir")
        self.assertEqual(path, Path("/tmp/absolute/dir").resolve())


if __name__ == "__main__":
    unittest.main()
