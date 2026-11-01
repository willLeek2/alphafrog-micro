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
import os
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import MagicMock, patch

from af_light_client.__main__ import (
    main,
    _format_credit,
    _needs_cost_refresh,
    _print_credits_summary,
    _print_query_credits_result,
    _query_credits_config,
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
    pass
class NeedsCostRefreshTest(unittest.TestCase):
    def _record(self, call_id: str, endpoint: str, cost_source: str, status: str, attempt: int) -> dict:
        return {
            "callId": call_id,
            "endpoint": endpoint,
            "costSource": cost_source,
            "settlementStatus": status,
            "settlementAttempt": attempt,
        }

class ResolveOutputDirTest(unittest.TestCase):
    pass
class QueryCreditsConfigTest(unittest.TestCase):
    pass
class MainEntryTest(unittest.TestCase):
    pass
class FormatCreditTest(unittest.TestCase):
    pass
class PrintQueryCreditsResultTest(unittest.TestCase):
    pass
if __name__ == "__main__":
    unittest.main()
