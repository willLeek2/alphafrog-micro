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
    def test_add_appends_with_timestamp(self) -> None:
        store = WarningStore(max_lines=4)
        store.add("first warning")
        snap = store.snapshot()
        self.assertEqual(len(snap), 1)
        # HH:MM:SS prefix is 9 chars including the space
        self.assertRegex(snap[0], r"^\d{2}:\d{2}:\d{2} first warning$")

    def test_bounded_to_max_lines(self) -> None:
        store = WarningStore(max_lines=2)
        store.add("a")
        store.add("b")
        store.add("c")
        snap = store.snapshot()
        self.assertEqual(len(snap), 2)
        # Oldest dropped, newest two kept
        self.assertTrue(all(line.endswith(("b", "c")) for line in snap))
        self.assertNotIn("a", " ".join(snap))

    def test_empty_and_whitespace_are_skipped(self) -> None:
        store = WarningStore()
        store.add("")
        store.add("   ")
        store.add("\n\t")
        self.assertEqual(store.snapshot(), [])

    def test_snapshot_returns_independent_list(self) -> None:
        store = WarningStore()
        store.add("x")
        snap = store.snapshot()
        snap.append("mutated")
        self.assertEqual(len(store.snapshot()), 1)


class ParseSseLinesTest(unittest.TestCase):
    def test_single_event(self) -> None:
        frames = list(parse_sse_lines(iter([
            "event: snapshot",
            "id: 1",
            'data: {"a": 1}',
            "",
        ])))
        self.assertEqual(len(frames), 1)
        self.assertEqual(frames[0].event_type, "snapshot")
        self.assertEqual(frames[0].event_id, "1")
        self.assertEqual(frames[0].data, '{"a": 1}')

    def test_multiple_events(self) -> None:
        frames = list(parse_sse_lines(iter([
            'data: first',
            "",
            "event: agent.event",
            'data: {"x": 1}',
            "",
        ])))
        self.assertEqual(len(frames), 2)
        self.assertEqual(frames[0].event_type, "message")
        self.assertEqual(frames[0].data, "first")
        self.assertEqual(frames[1].event_type, "agent.event")
        self.assertEqual(frames[1].data, '{"x": 1}')

    def test_comments_skipped(self) -> None:
        frames = list(parse_sse_lines(iter([
            ": this is a comment",
            "data: only-real",
            "",
        ])))
        self.assertEqual(len(frames), 1)
        self.assertEqual(frames[0].data, "only-real")

    def test_no_blank_line_yields_anyway(self) -> None:
        # Forgiving: trailing event with no blank terminator is still emitted.
        frames = list(parse_sse_lines(iter([
            "data: trailing-without-blank",
        ])))
        self.assertEqual(len(frames), 1)
        self.assertEqual(frames[0].data, "trailing-without-blank")

    def test_data_with_leading_space(self) -> None:
        frames = list(parse_sse_lines(iter([
            "data:hello",
            "",
        ])))
        self.assertEqual(len(frames), 1)
        self.assertEqual(frames[0].data, "hello")


class SseFrameParsedDataTest(unittest.TestCase):
    def test_parses_json(self) -> None:
        self.assertEqual(SseFrame("e", '{"a": 1}').parsed_data(), {"a": 1})

    def test_returns_string_for_invalid_json(self) -> None:
        self.assertEqual(SseFrame("e", "not json").parsed_data(), "not json")

    def test_returns_none_for_empty(self) -> None:
        self.assertIsNone(SseFrame("e", "").parsed_data())


class ExtractTokenTest(unittest.TestCase):
    def test_plain_string(self) -> None:
        self.assertEqual(extract_token("abc.def.ghi", ""), "abc.def.ghi")

    def test_string_with_quotes(self) -> None:
        self.assertEqual(extract_token('"abc.def.ghi"', ""), "abc.def.ghi")

    def test_dict_with_token_key(self) -> None:
        self.assertEqual(extract_token({"token": "tok-1"}, ""), "tok-1")

    def test_dict_with_access_token_key(self) -> None:
        self.assertEqual(extract_token({"accessToken": "tok-2"}, ""), "tok-2")

    def test_dict_with_access_token_underscore(self) -> None:
        self.assertEqual(extract_token({"access_token": "tok-3"}, ""), "tok-3")

    def test_nested_data_wrapper(self) -> None:
        self.assertEqual(extract_token({"data": {"token": "tok-4"}}, ""), "tok-4")

    def test_falls_back_to_raw_text(self) -> None:
        self.assertEqual(extract_token({"other": "x"}, "raw-fallback"), "raw-fallback")

    def test_empty_returns_empty(self) -> None:
        self.assertEqual(extract_token({}, ""), "")


class NormalizeBearerTest(unittest.TestCase):
    def test_strips_prefix(self) -> None:
        self.assertEqual(normalize_bearer("Bearer abc"), "abc")

    def test_idempotent(self) -> None:
        self.assertEqual(normalize_bearer("abc"), "abc")

    def test_handles_empty(self) -> None:
        self.assertEqual(normalize_bearer(""), "")


class UnwrapDataTest(unittest.TestCase):
    def test_unwraps_data_key(self) -> None:
        self.assertEqual(unwrap_data({"data": {"x": 1}}), {"x": 1})

    def test_unwraps_success_response_wrapper(self) -> None:
        self.assertEqual(unwrap_data({"code": 0, "data": {"x": 1}}), {"x": 1})
        self.assertEqual(unwrap_data({"code": 200, "data": {"x": 2}}), {"x": 2})

    def test_raises_on_business_error_response_wrapper(self) -> None:
        with self.assertRaises(RuntimeError) as ctx:
            unwrap_data({"code": 500, "message": "observability 过大，请使用 /timeline"})
        self.assertIn("business error 500", str(ctx.exception))
        self.assertIn("observability", str(ctx.exception))

    def test_passthrough_when_no_data_key(self) -> None:
        self.assertEqual(unwrap_data({"x": 1}), {"x": 1})

    def test_non_dict_passthrough(self) -> None:
        self.assertEqual(unwrap_data([1, 2, 3]), [1, 2, 3])


class SafeJsonTest(unittest.TestCase):
    def test_parses_valid_json(self) -> None:
        resp = MagicMock()
        resp.json.return_value = {"a": 1}
        self.assertEqual(safe_json(resp), {"a": 1})

    def test_falls_back_to_text_on_value_error(self) -> None:
        resp = MagicMock()
        resp.json.side_effect = ValueError("not json")
        resp.text = "raw text"
        self.assertEqual(safe_json(resp), "raw text")


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

    def test_login_happy_path(self) -> None:
        client = self._client()
        ok = _mock_response(200, json_payload={"token": "tok-1"})
        with patch.object(client, "_request", return_value=ok) as req:
            token = client.login("/login", "/logout", "u", "p")
        self.assertEqual(token, "tok-1")
        self.assertEqual(client.token, "tok-1")
        # 1 call: login only (no retry)
        self.assertEqual(req.call_count, 1)

    def test_login_already_logged_in_triggers_retry(self) -> None:
        client = self._client()
        bad = _mock_response(400, text="User already logged in")
        ok = _mock_response(200, json_payload={"token": "tok-2"})
        with patch.object(client, "_request", side_effect=[bad, None, ok]) as req:
            token = client.login("/login", "/logout", "u", "p")
        self.assertEqual(token, "tok-2")
        self.assertEqual(req.call_count, 3)
        # Middle call is logout
        logout_call = req.call_args_list[1]
        self.assertEqual(logout_call.args[0], "post")
        self.assertEqual(logout_call.args[1], "/logout")
        # Warning was added
        self.assertTrue(any("already logged in" in w for w in client.warnings.snapshot()))

    def test_login_http_failure_raises(self) -> None:
        client = self._client()
        bad = _mock_response(500, text="server boom")
        with patch.object(client, "_request", return_value=bad):
            with self.assertRaises(RuntimeError) as ctx:
                client.login("/login", "/logout", "u", "p")
        self.assertIn("HTTP 500", str(ctx.exception))

    def test_login_no_token_raises(self) -> None:
        client = self._client()
        no_token = _mock_response(200, json_payload={"other": "x"})
        with patch.object(client, "_request", return_value=no_token):
            with self.assertRaises(RuntimeError) as ctx:
                client.login("/login", "/logout", "u", "p")
        self.assertIn("no token", str(ctx.exception))


class AgentHttpClientCreateRunTest(unittest.TestCase):
    def _client(self) -> AgentHttpClient:
        client = AgentHttpClient("http://example.com")
        client.token = "tok-1"
        return client

    def test_create_run_happy_path(self) -> None:
        client = self._client()
        ok = _mock_response(200, json_payload={"data": {"runId": "run-42"}})
        with patch.object(client, "_request", return_value=ok):
            data = client.create_run("/runs", {"message": "hi"})
        self.assertEqual(data, {"runId": "run-42"})

    def test_create_run_unwraps_run_id(self) -> None:
        client = self._client()
        ok = _mock_response(200, json_payload={"data": {"run_id": "run-99"}})
        with patch.object(client, "_request", return_value=ok):
            data = client.create_run("/runs", {"message": "hi"})
        self.assertEqual(data, {"run_id": "run-99"})

    def test_create_run_missing_id_raises(self) -> None:
        client = self._client()
        ok = _mock_response(200, json_payload={"data": {"other": "x"}})
        with patch.object(client, "_request", return_value=ok):
            with self.assertRaises(RuntimeError) as ctx:
                client.create_run("/runs", {"message": "hi"})
        self.assertIn("missing run id", str(ctx.exception))

    def test_create_run_non_200_raises(self) -> None:
        client = self._client()
        bad = _mock_response(401, text="unauthorized")
        with patch.object(client, "_request", return_value=bad):
            with self.assertRaises(RuntimeError):
                client.create_run("/runs", {"message": "hi"})


class AgentHttpClientStreamEventsTest(unittest.TestCase):
    def _client(self) -> AgentHttpClient:
        client = AgentHttpClient(
            "http://example.com",
            request_timeout_seconds=10.0,
            stream_idle_timeout_seconds=120.0,
        )
        client.token = "tok-abc"
        return client

    def test_stream_sends_bearer_cookie_query(self) -> None:
        client = self._client()
        session = MagicMock()
        resp_ctx = MagicMock()
        resp_ctx.status_code = 200
        resp_ctx.__enter__.return_value = resp_ctx
        resp_ctx.__exit__.return_value = False
        resp_ctx.iter_lines.return_value = iter([
            "event: snapshot",
            'data: {"hello": "world"}',
            "",
        ])
        session.get.return_value = resp_ctx
        client.session = session

        frames = list(client.stream_events("/runs/{run_id}/stream", "run-1"))

        self.assertEqual(len(frames), 1)
        self.assertEqual(frames[0].event_type, "snapshot")
        self.assertEqual(resp_ctx.encoding, "utf-8")
        resp_ctx.iter_lines.assert_called_once_with(decode_unicode=True)
        sent = session.get.call_args
        url = sent.args[0]
        self.assertIn("token=tok-abc", url)
        self.assertIn("Bearer tok-abc", sent.kwargs["headers"]["Authorization"])
        self.assertIn("access_token=tok-abc", sent.kwargs["headers"]["Cookie"])
        self.assertEqual(sent.kwargs["headers"]["Accept"], "text/event-stream")

    def test_stream_non_200_raises(self) -> None:
        client = self._client()
        session = MagicMock()
        resp_ctx = MagicMock()
        resp_ctx.status_code = 502
        resp_ctx.text = "bad gateway"
        resp_ctx.__enter__.return_value = resp_ctx
        resp_ctx.__exit__.return_value = False
        session.get.return_value = resp_ctx
        client.session = session

        with self.assertRaises(RuntimeError) as ctx:
            list(client.stream_events("/runs/{run_id}/stream", "run-1"))
        self.assertIn("HTTP 502", str(ctx.exception))

    def test_request_adds_bearer_header_when_token_set(self) -> None:
        client = self._client()
        session = MagicMock()
        session.request.return_value = _mock_response(200, json_payload={"token": "x"})
        client.session = session
        client._request("get", "/status", expected={200})
        headers = session.request.call_args.kwargs["headers"]
        self.assertEqual(headers.get("Authorization"), "Bearer tok-abc")

    def test_request_omits_bearer_when_add_auth_false(self) -> None:
        client = self._client()
        session = MagicMock()
        session.request.return_value = _mock_response(200, json_payload={"token": "x"})
        client.session = session
        client._request("post", "/login", json_body={"u": "p"}, add_auth=False, expected={200})
        self.assertNotIn("Authorization", session.request.call_args.kwargs["headers"])

    def test_request_unexpected_status_raises(self) -> None:
        client = self._client()
        session = MagicMock()
        session.request.return_value = _mock_response(503, text="oops")
        client.session = session
        with self.assertRaises(RuntimeError) as ctx:
            client._request("get", "/status", expected={200})
        self.assertIn("HTTP 503", str(ctx.exception))


class AgentHttpClientRestPullTest(unittest.TestCase):
    def _client(self) -> AgentHttpClient:
        client = AgentHttpClient("http://example.com", request_timeout_seconds=5.0)
        client.token = "tok"
        return client

    def test_get_events_uses_after_seq_and_limit(self) -> None:
        client = self._client()
        ok = _mock_response(200, json_payload={"data": {"items": []}})
        with patch.object(client.session, "request", return_value=ok) as req:
            payload = client.get_events("/runs/{run_id}/events", "run-1", after_seq=7, limit=999)
        self.assertEqual(payload, {"items": []})
        self.assertEqual(req.call_args.kwargs["params"], {"after_seq": 7, "limit": 500})

    def test_stream_events_explicit_resume_cursor_is_added_before_token(self) -> None:
        client = self._client()
        response = MagicMock()
        response.status_code = 200
        response.__enter__.return_value = response
        response.__exit__.return_value = False
        response.iter_lines.return_value = iter(["event: snapshot", "data: {}", ""])
        with patch.object(client.session, "get", return_value=response) as request:
            list(client.stream_events("/runs/{run_id}/stream", "run-1", after_seq=17))
        url = request.call_args.args[0]
        self.assertIn("after_seq=17", url)
        self.assertIn("token=tok", url)

    def test_get_timeline_uses_after_seq_and_limit(self) -> None:
        client = self._client()
        ok = _mock_response(200, json_payload={"data": {"items": []}})
        with patch.object(client.session, "request", return_value=ok) as req:
            payload = client.get_timeline("/runs/{run_id}/timeline", "run-1", after_seq=2, limit=3)
        self.assertEqual(payload, {"items": []})
        self.assertEqual(req.call_args.kwargs["params"], {"after_seq": 2, "limit": 3})

    def test_get_observability_full_unwraps_data(self) -> None:
        client = self._client()
        ok = _mock_response(200, json_payload={"data": {"summary": {"x": 1}}})
        with patch.object(client.session, "request", return_value=ok):
            payload = client.get_observability_full("/runs/{run_id}/observability/full", "run-1")
        self.assertEqual(payload, {"summary": {"x": 1}})

    def test_get_observability_full_raises_on_business_error(self) -> None:
        client = self._client()
        ok = _mock_response(
            200,
            json_payload={"code": 500, "message": "observability 过大，请使用 /traces 或 /timeline"},
        )
        with patch.object(client.session, "request", return_value=ok):
            with self.assertRaises(RuntimeError) as ctx:
                client.get_observability_full("/runs/{run_id}/observability/full", "run-1")
        self.assertIn("business error 500", str(ctx.exception))


class AgentHttpClientLogoutTest(unittest.TestCase):
    def test_logout_best_effort_records_warning_on_failure(self) -> None:
        client = AgentHttpClient("http://example.com")
        with patch.object(client, "_request", side_effect=RuntimeError("net")):
            client.logout("/logout", username="u")
        self.assertTrue(any("logout failed" in w for w in client.warnings.snapshot()))


class AgentHttpClientGetRunCreditsTest(unittest.TestCase):
    def _client(self, request_timeout: float = 5.0) -> AgentHttpClient:
        client = AgentHttpClient("http://example.com", request_timeout_seconds=request_timeout)
        client.token = "tok-1"
        return client

    def test_get_run_credits_substitutes_template_and_unwraps(self) -> None:
        client = self._client()
        ok = _mock_response(
            200,
            json_payload={
                "data": {
                    "runId": "run-7",
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
                }
            },
        )
        with patch.object(client.session, "request", return_value=ok) as req:
            payload = client.get_run_credits("/api/agent/runs/{run_id}/credits", "run-7")
        self.assertEqual(payload["runId"], "run-7")
        self.assertEqual(payload["totalCredits"], "12.34")
        # 模板替换后 path 不应残留 {run_id}
        self.assertNotIn("{run_id}", req.call_args.args[1])
        self.assertEqual(req.call_args.args[0], "GET")
        # 默认 timeout=None 时不覆盖 request_timeout_seconds，请求仍用原值
        self.assertEqual(req.call_args.kwargs["timeout"], 5.0)
        self.assertEqual(client.request_timeout_seconds, 5.0)

    def test_get_run_credits_overrides_timeout_and_restores(self) -> None:
        client = self._client(request_timeout=30.0)
        ok = _mock_response(200, json_payload={"data": {"runId": "run-1"}})
        with patch.object(client.session, "request", return_value=ok) as req:
            client.get_run_credits(
                "/api/agent/runs/{run_id}/credits",
                "run-1",
                timeout=2.5,
            )
        # 调用期间 request_timeout_seconds 应被覆盖，请求用的是 2.5
        self.assertEqual(req.call_args.kwargs["timeout"], 2.5)
        # 调用结束后恢复原值
        self.assertEqual(client.request_timeout_seconds, 30.0)

    def test_get_run_credits_zero_timeout_does_not_override(self) -> None:
        client = self._client(request_timeout=7.0)
        ok = _mock_response(200, json_payload={"data": {"runId": "run-2"}})
        with patch.object(client.session, "request", return_value=ok) as req:
            client.get_run_credits(
                "/api/agent/runs/{run_id}/credits",
                "run-2",
                timeout=0.0,
            )
        # 0/None/负值不覆盖，沿用原 request_timeout_seconds
        self.assertEqual(req.call_args.kwargs["timeout"], 7.0)
        self.assertEqual(client.request_timeout_seconds, 7.0)


if __name__ == "__main__":
    unittest.main()
