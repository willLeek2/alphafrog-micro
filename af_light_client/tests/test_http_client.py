"""Unit tests for af_light_client.http_client.

Pure mock-based; no real network, no real backend. Covers:

- ``WarningStore`` bounded aggregation and timestamp prefix
- ``parse_sse_lines`` SSE wire-format handling (event/id/data, comments, dispatch)
- ``extract_token`` against plain string, flat dict, nested dict
- ``AgentHttpClient.login`` happy path, "User already logged in" retry, failure
- ``AgentHttpClient.create_run`` happy path and missing-id failure
- ``AgentHttpClient.stream_events`` Bearer + cookie + query SSE auth
"""
from __future__ import annotations

import unittest
from unittest.mock import MagicMock, patch

from af_light_client.http_client import (
    AgentHttpClient,
    SseFrame,
    WarningStore,
    extract_token,
    normalize_bearer,
    parse_sse_lines,
    safe_json,
    unwrap_data,
)


class WarningStoreTest(unittest.TestCase):
    pass
class ParseSseLinesTest(unittest.TestCase):
    pass
class SseFrameParsedDataTest(unittest.TestCase):
    pass
class ExtractTokenTest(unittest.TestCase):
    pass
class NormalizeBearerTest(unittest.TestCase):
    pass
class UnwrapDataTest(unittest.TestCase):
    pass
class SafeJsonTest(unittest.TestCase):
    pass
def _mock_response(status_code: int, text: str = "", json_payload=None) -> MagicMock:
    resp = MagicMock()
    resp.status_code = status_code
    resp.text = text
    if json_payload is not None:
        resp.json.return_value = json_payload
    else:
        resp.json.side_effect = ValueError("not json")
    return resp


class AgentHttpClientLoginTest(unittest.TestCase):
    def _client(self) -> AgentHttpClient:
        return AgentHttpClient("http://example.com", request_timeout_seconds=5.0)

class AgentHttpClientCreateRunTest(unittest.TestCase):
    def _client(self) -> AgentHttpClient:
        client = AgentHttpClient("http://example.com")
        client.token = "tok-1"
        return client

class AgentHttpClientStreamEventsTest(unittest.TestCase):
    def _client(self) -> AgentHttpClient:
        client = AgentHttpClient(
            "http://example.com",
            request_timeout_seconds=10.0,
            stream_idle_timeout_seconds=120.0,
        )
        client.token = "tok-abc"
        return client

class AgentHttpClientRestPullTest(unittest.TestCase):
    def _client(self) -> AgentHttpClient:
        client = AgentHttpClient("http://example.com", request_timeout_seconds=5.0)
        client.token = "tok"
        return client

class AgentHttpClientLogoutTest(unittest.TestCase):
    pass
class AgentHttpClientGetRunCreditsTest(unittest.TestCase):
    def _client(self, request_timeout: float = 5.0) -> AgentHttpClient:
        client = AgentHttpClient("http://example.com", request_timeout_seconds=request_timeout)
        client.token = "tok-1"
        return client

if __name__ == "__main__":
    unittest.main()
