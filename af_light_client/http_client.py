"""HTTP + auth + SSE plumbing for the af_light_client TUI.

Owns a single ``requests.Session`` so login cookies and Bearer tokens ride
the same TCP connection across the create / stream / status / result calls.
Login handles the "User already logged in" race by logging out first and
retrying once. SSE opens with Bearer header + access_token cookie + token
query param so the request survives gateways that strip either channel.
HTTP errors, idle timeouts and reconnect limits surface as entries in a
shared ``WarningStore`` instead of crashing the TUI.
"""
from __future__ import annotations

import json
import time
from dataclasses import dataclass
from typing import Any, Dict, Iterator, Optional
from urllib.parse import quote

import requests


TERMINAL_STATUSES = {
    "COMPLETED",
    "PARTIAL",
    "FAILED",
    "CANCELED",
    "CANCELLED",
    "EXPIRED",
    "TIMEOUT",
    "TIMED_OUT",
}


@dataclass
class SseFrame:
    event_type: str
    data: str
    event_id: str = ""

    def parsed_data(self) -> Any:
        if not self.data:
            return None
        try:
            return json.loads(self.data)
        except json.JSONDecodeError:
            return self.data


class WarningStore:
    def __init__(self, max_lines: int = 4) -> None:
        self.max_lines = max(1, int(max_lines))
        self._items: list[str] = []

    def add(self, message: str) -> None:
        text = " ".join(str(message).split())
        if not text:
            return
        stamp = time.strftime("%H:%M:%S")
        self._items.append(f"{stamp} {text}")
        if len(self._items) > self.max_lines:
            self._items = self._items[-self.max_lines :]

    def snapshot(self) -> list[str]:
        return list(self._items)


class AgentHttpClient:
    def __init__(
        self,
        base_url: str,
        *,
        request_timeout_seconds: float = 30.0,
        stream_idle_timeout_seconds: float = 300.0,
        warnings: Optional[WarningStore] = None,
    ) -> None:
        self.base_url = base_url.rstrip("/")
        self.request_timeout_seconds = float(request_timeout_seconds)
        self.stream_idle_timeout_seconds = float(stream_idle_timeout_seconds)
        self.session = requests.Session()
        self.token = ""
        self.warnings = warnings or WarningStore()

    def login(self, login_endpoint: str, logout_endpoint: str, username: str, password: str) -> str:
        resp = self._request(
            "post",
            login_endpoint,
            json_body={"username": username, "password": password},
            add_auth=False,
            expected=None,
        )
        if resp.status_code == 400 and "User already logged in" in resp.text:
            self.warnings.add("login returned already logged in; logout then retry")
            self.logout(logout_endpoint, username=username, expected=None)
            resp = self._request(
                "post",
                login_endpoint,
                json_body={"username": username, "password": password},
                add_auth=False,
                expected=None,
            )
        if resp.status_code != 200:
            raise RuntimeError(f"login failed: HTTP {resp.status_code} {resp.text[:300]}")
        self.token = extract_token(safe_json(resp), resp.text)
        if not self.token:
            raise RuntimeError("login succeeded but no token was found in response")
        return self.token

    def logout(self, logout_endpoint: str, *, username: str, expected: Optional[set[int]] = None) -> None:
        """Best-effort logout; never raises into the TUI."""
        try:
            self._request(
                "post",
                logout_endpoint,
                json_body={"username": username},
                add_auth=False,
                expected=expected,
            )
        except Exception as exc:
            self.warnings.add(f"logout failed: {exc}")

    def create_run(self, create_endpoint: str, body: Dict[str, Any]) -> Dict[str, Any]:
        resp = self._request("post", create_endpoint, json_body=body, expected={200})
        payload = safe_json(resp)
        data = unwrap_data(payload)
        if not isinstance(data, dict):
            raise RuntimeError(f"create run returned unexpected payload: {str(payload)[:300]}")
        run_id = data.get("runId") or data.get("run_id") or data.get("id")
        if not run_id:
            raise RuntimeError(f"create run response missing run id: {str(payload)[:300]}")
        return data

    def get_status(self, template: str, run_id: str) -> Dict[str, Any]:
        resp = self._request("get", template.format(run_id=run_id), expected={200})
        payload = safe_json(resp)
        data = unwrap_data(payload)
        return data if isinstance(data, dict) else {}

    def get_events(self, template: str, run_id: str, *, after_seq: int = 0, limit: int = 500) -> Dict[str, Any]:
        resp = self._request(
            "get",
            template.format(run_id=run_id),
            params={"after_seq": max(0, int(after_seq)), "limit": max(1, min(500, int(limit)))},
            expected={200},
        )
        payload = safe_json(resp)
        data = unwrap_data(payload)
        return data if isinstance(data, dict) else {"items": data}

    def get_timeline(self, template: str, run_id: str, *, after_seq: int = 0, limit: int = 500) -> Dict[str, Any]:
        resp = self._request(
            "get",
            template.format(run_id=run_id),
            params={"after_seq": max(0, int(after_seq)), "limit": max(1, min(500, int(limit)))},
            expected={200},
        )
        payload = safe_json(resp)
        data = unwrap_data(payload)
        return data if isinstance(data, dict) else {"items": data}

    def get_result(self, template: str, run_id: str) -> Dict[str, Any]:
        resp = self._request("get", template.format(run_id=run_id), expected={200, 202})
        payload = safe_json(resp)
        data = unwrap_data(payload)
        return data if isinstance(data, dict) else {}

    def get_observability_full(self, template: str, run_id: str) -> Any:
        resp = self._request("get", template.format(run_id=run_id), expected={200})
        return unwrap_data(safe_json(resp))

    def get_run_credits(self, template: str, run_id: str, *, timeout: Optional[float] = None) -> Dict[str, Any]:
        prev_timeout = self.request_timeout_seconds
        if timeout is not None and timeout > 0:
            self.request_timeout_seconds = float(timeout)
        try:
            resp = self._request("get", template.format(run_id=run_id), expected={200})
        finally:
            self.request_timeout_seconds = prev_timeout
        payload = safe_json(resp)
        data = unwrap_data(payload)
        return data if isinstance(data, dict) else {}

    def refresh_run_credits(self, template: str, run_id: str, *, timeout: Optional[float] = None) -> Dict[str, Any]:
        prev_timeout = self.request_timeout_seconds
        if timeout is not None and timeout > 0:
            self.request_timeout_seconds = float(timeout)
        try:
            resp = self._request("post", template.format(run_id=run_id), expected={200})
        finally:
            self.request_timeout_seconds = prev_timeout
        payload = safe_json(resp)
        data = unwrap_data(payload)
        return data if isinstance(data, dict) else {}

    def get_user_credits(self, endpoint: str, *, timeout: Optional[float] = None) -> Dict[str, Any]:
        prev_timeout = self.request_timeout_seconds
        if timeout is not None and timeout > 0:
            self.request_timeout_seconds = float(timeout)
        try:
            resp = self._request("get", endpoint, expected={200})
        finally:
            self.request_timeout_seconds = prev_timeout
        payload = safe_json(resp)
        data = unwrap_data(payload)
        return data if isinstance(data, dict) else {}

    def cancel_run(self, template: str, run_id: str) -> None:
        try:
            self._request("post", template.format(run_id=run_id), expected={200})
        except Exception as exc:  # best-effort on Ctrl+C
            self.warnings.add(f"cancel failed: {exc}")

    def stream_events(self, template: str, run_id: str) -> Iterator[SseFrame]:
        path = template.format(run_id=run_id)
        token = normalize_bearer(self.token)
        sep = "&" if "?" in path else "?"
        path = f"{path}{sep}token={quote(token, safe='')}" if token else path
        url = self._url(path)
        headers = {
            "Accept": "text/event-stream",
            "Cache-Control": "no-cache",
        }
        if token:
            headers["Authorization"] = f"Bearer {token}"
            headers["Cookie"] = f"access_token={quote(token, safe='')}"
        timeout = (min(10.0, self.request_timeout_seconds), self.stream_idle_timeout_seconds)
        with self.session.get(url, headers=headers, stream=True, timeout=timeout) as resp:
            if resp.status_code != 200:
                raise RuntimeError(f"SSE stream failed: HTTP {resp.status_code} {resp.text[:300]}")
            resp.encoding = "utf-8"
            yield from parse_sse_lines(resp.iter_lines(decode_unicode=True))

    def _request(
        self,
        method: str,
        path: str,
        *,
        json_body: Optional[Dict[str, Any]] = None,
        params: Optional[Dict[str, Any]] = None,
        add_auth: bool = True,
        expected: Optional[set[int]] = None,
    ) -> requests.Response:
        headers: Dict[str, str] = {}
        if add_auth and self.token:
            headers["Authorization"] = f"Bearer {normalize_bearer(self.token)}"
        resp = self.session.request(
            method.upper(),
            self._url(path),
            json=json_body,
            params=params,
            headers=headers,
            timeout=self.request_timeout_seconds,
        )
        if expected is not None and resp.status_code not in expected:
            raise RuntimeError(f"{method.upper()} {path} failed: HTTP {resp.status_code} {resp.text[:300]}")
        return resp

    def _url(self, path: str) -> str:
        return f"{self.base_url}/{path.lstrip('/')}"


def parse_sse_lines(lines: Iterator[str]) -> Iterator[SseFrame]:
    event_type = "message"
    event_id = ""
    data_lines: list[str] = []
    for raw in lines:
        line = raw or ""
        if line == "":
            if data_lines:
                yield SseFrame(event_type=event_type, event_id=event_id, data="\n".join(data_lines))
            event_type = "message"
            event_id = ""
            data_lines = []
            continue
        if line.startswith(":"):
            continue
        if line.startswith("event:"):
            event_type = line[len("event:") :].strip() or "message"
        elif line.startswith("id:"):
            event_id = line[len("id:") :].strip()
        elif line.startswith("data:"):
            data_lines.append(line[len("data:") :].lstrip())
    if data_lines:
        yield SseFrame(event_type=event_type, event_id=event_id, data="\n".join(data_lines))


def normalize_bearer(raw: str) -> str:
    return (raw or "").removeprefix("Bearer ").strip()


def safe_json(resp: requests.Response) -> Any:
    try:
        return resp.json()
    except ValueError:
        return resp.text


def unwrap_data(payload: Any) -> Any:
    if isinstance(payload, dict):
        if "code" in payload:
            code = payload.get("code")
            if str(code) not in ("0", "200"):
                message = payload.get("message") or payload.get("msg") or payload
                raise RuntimeError(f"business error {code}: {message}")
            return payload.get("data")
        if "data" in payload:
            return payload["data"]
    return payload


def extract_token(payload: Any, raw_text: str) -> str:
    if isinstance(payload, str):
        return payload.strip().strip('"')
    if isinstance(payload, dict):
        for key in ("token", "accessToken", "access_token", "jwt"):
            value = payload.get(key)
            if isinstance(value, str) and value.strip():
                return value.strip()
        data = payload.get("data")
        if data is not None:
            token = extract_token(data, raw_text)
            if token:
                return token
    return (raw_text or "").strip().strip('"')
