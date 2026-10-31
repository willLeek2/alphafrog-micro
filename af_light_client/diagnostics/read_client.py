"""并行拉取一个既有 Agent Run 的管理员可观测数据。

除管理员登录和登出外，脚本只调用 GET 接口，不创建、取消、恢复或重跑 Agent Run。默认拉取
Run 概况、状态、事件、时间线、结果、消息、Artifact 元数据、完整
observability、trace 列表、逐条 trace 详情、完整原始详情和调用详情。
"""

from __future__ import annotations

import argparse
import atexit
import base64
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
import getpass
import gzip
import hashlib
import io
import json
import os
from pathlib import Path
import re
import sys
import tempfile
import threading
import time
from typing import Any, Callable, Iterable
from urllib.parse import parse_qsl, quote, urlparse
import uuid

import requests


DEFAULT_BASE_URL = "http://101.200.12.101:8090"
DEFAULT_REPO_ROOT = Path.home() / "workspace" / "alphafrog" / "alphafrog-micro"
DIAGNOSTIC_READ_CAPABILITIES_PATH = "/api/agent/diagnostics/read-capabilities"
REQUIRED_DIAGNOSTIC_READ_CAPABILITIES = (
    "adminCrossUserRead",
    "noTouchRunLifecycle",
    "artifactSkipLazyRegistration",
)
DEFAULT_SECTIONS = (
    "run",
    "status",
    "events",
    "timeline",
    "result",
    "credits",
    "messages",
    "artifacts",
    "observability",
    "traces",
    "trace-details",
    "trace-full",
    "call-details",
)
OPTIONAL_SECTIONS = ("artifact-parts",)
ALL_SECTIONS = frozenset((*DEFAULT_SECTIONS, *OPTIONAL_SECTIONS))
TRACE_DEPENDENT_SECTIONS = frozenset(("trace-details", "trace-full", "call-details"))
RETRYABLE_STATUSES = frozenset((429, 502, 503))
TERMINAL_RUN_STATUSES = frozenset(("COMPLETED", "FAILED", "CANCELED", "EXPIRED", "PARTIAL"))
RUN_ID_PATTERN = re.compile(r"(?:[0-9a-fA-F]{32}|[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12})")
SAFE_NAME_PATTERN = re.compile(r"[^A-Za-z0-9._-]+")
BEARER_VALUE = re.compile(r"(?i)(bearer\s+)[A-Za-z0-9._\-+/=]+")
BASIC_VALUE = re.compile(r"(?i)(basic\s+)[A-Za-z0-9._\-+/=]+")
SECRET_KEY_PATTERN = (
    r"(?:api[_-]?key|x[_-]?api[_-]?key|openai[_-]?api[_-]?key|access[_-]?key"
    r"|secret|client[_-]?secret|password|passwd|credential|credentials|authorization"
    r"|proxy[_-]?authorization|token|access[_-]?token|refresh[_-]?token|auth[_-]?token"
    r"|x[_-]?auth[_-]?token|bearer[_-]?token|id[_-]?token|session[_-]?token"
    r"|private[_-]?key|csrf[_-]?token|signature)"
)
SENSITIVE_HEADER_VALUE = re.compile(
    r"(?i)(\b(?:authorization|proxy-authorization|cookie|set-cookie|x-api-key|api-key|x-auth-token)"
    r"\s*:\s*)[^\r\n]*"
)
DOUBLE_QUOTED_SECRET_ASSIGNMENT = re.compile(
    r"(?i)([\"']?" + SECRET_KEY_PATTERN + r"[\"']?\s*[:=]\s*\")((?:\\.|[^\"])*)\""
)
SINGLE_QUOTED_SECRET_ASSIGNMENT = re.compile(
    r"(?i)([\"']?" + SECRET_KEY_PATTERN + r"[\"']?\s*[:=]\s*')((?:\\.|[^'])*)'"
)
UNQUOTED_SECRET_ASSIGNMENT = re.compile(
    r"(?i)([\"']?" + SECRET_KEY_PATTERN + r"[\"']?\s*[:=]\s*)(?![\"'])[^&;,\r\n]*"
)
URL_SECRET_PARAM = re.compile(
    r"(?i)([?&][^=&\s]*(?:api_?key|key|secret|password|passwd|access_?token|token|credential|signature)[^=]*=)[^&#\s]+"
)
JWT_VALUE = re.compile(r"\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b")
CREDENTIAL_SHAPED = re.compile(
    r"(?i)\b(?:sk|ak|pk|rk|ghp|xox[baprs]|ya29)_[A-Za-z0-9._\-]{12,}\b"
    r"|\bsk-[A-Za-z0-9._\-]{12,}\b|\bAKIA[0-9A-Z]{16}\b"
    r"|\bAIza[0-9A-Za-z_\-]{20,}\b|\bgithub_pat_[A-Za-z0-9_]{20,}\b"
)
SENSITIVE_EXACT_KEYS = frozenset(
    (
        "authorization",
        "proxyauthorization",
        "apikey",
        "xapikey",
        "secret",
        "clientsecret",
        "password",
        "passwd",
        "accesstoken",
        "refreshtoken",
        "authtoken",
        "bearertoken",
        "idtoken",
        "sessiontoken",
        "token",
        "credential",
        "credentials",
        "cookie",
        "setcookie",
        "xapikey",
        "privatekey",
        "signature",
    )
)


class CollectionError(RuntimeError):
    """可预期的采集错误。"""


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def safe_name(value: str, fallback: str = "item") -> str:
    cleaned = SAFE_NAME_PATTERN.sub("_", str(value or "").strip()).strip("._")
    if not cleaned:
        return fallback
    if len(cleaned) <= 160:
        return cleaned
    digest = hashlib.sha256(cleaned.encode("utf-8")).hexdigest()[:12]
    return f"{cleaned[:140]}-{digest}"


def sensitive_key(key: str) -> bool:
    normalized = re.sub(r"[^a-z0-9]", "", key.lower())
    if normalized in SENSITIVE_EXACT_KEYS:
        return True
    return normalized.endswith(
        (
            "password",
            "passwd",
            "apikey",
            "secret",
            "token",
            "authorization",
            "privatekey",
            "credential",
            "signature",
        )
    )


def scrub(value: Any) -> Any:
    """递归脱敏，同时保留 inputTokens/totalTokens 等计数指标。"""

    if value is None:
        return None
    if isinstance(value, dict):
        return {
            str(key): "***REDACTED***" if sensitive_key(str(key)) else scrub(child)
            for key, child in value.items()
        }
    if isinstance(value, list):
        return [scrub(child) for child in value]
    if isinstance(value, tuple):
        return [scrub(child) for child in value]
    if isinstance(value, str):
        text = BEARER_VALUE.sub(r"\1***REDACTED***", value)
        text = BASIC_VALUE.sub(r"\1***REDACTED***", text)
        text = SENSITIVE_HEADER_VALUE.sub(r"\1***REDACTED***", text)
        text = DOUBLE_QUOTED_SECRET_ASSIGNMENT.sub(r'\1***REDACTED***"', text)
        text = SINGLE_QUOTED_SECRET_ASSIGNMENT.sub(r"\1***REDACTED***'", text)
        text = UNQUOTED_SECRET_ASSIGNMENT.sub(r"\1***REDACTED***", text)
        text = URL_SECRET_PARAM.sub(r"\1***REDACTED***", text)
        text = JWT_VALUE.sub("***REDACTED***", text)
        return CREDENTIAL_SHAPED.sub("***REDACTED***", text)
    return value


def strip_encoded_blobs(value: Any) -> Any:
    """在响应副本中移除 base64 正文；解码后的脱敏内容会另存。"""

    if isinstance(value, dict):
        out: dict[str, Any] = {}
        for key, child in value.items():
            text_key = str(key)
            if text_key.lower().endswith("base64") and isinstance(child, str) and child:
                out[text_key] = f"***DECODED_SEPARATELY:{len(child)}_CHARS***"
            else:
                out[text_key] = strip_encoded_blobs(child)
        return out
    if isinstance(value, list):
        return [strip_encoded_blobs(child) for child in value]
    return value


def atomic_write_bytes(path: Path, content: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=path.parent, prefix=f".{path.name}.", delete=False) as handle:
        temp_path = Path(handle.name)
        handle.write(content)
        handle.flush()
        os.fsync(handle.fileno())
    os.replace(temp_path, path)


def write_json(path: Path, payload: Any, *, strip_base64: bool = False) -> None:
    safe_payload = scrub(payload)
    if strip_base64:
        safe_payload = strip_encoded_blobs(safe_payload)
    content = (json.dumps(safe_payload, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    atomic_write_bytes(path, content)


def write_jsonl(path: Path, rows: Iterable[Any]) -> None:
    content = "".join(
        json.dumps(scrub(row), ensure_ascii=False, separators=(",", ":")) + "\n"
        for row in rows
    ).encode("utf-8")
    atomic_write_bytes(path, content)


def unwrap(payload: Any) -> Any:
    if isinstance(payload, dict) and "data" in payload and ("code" in payload or "success" in payload):
        return payload.get("data")
    return payload


def wrapper_ok(payload: Any) -> bool:
    if not isinstance(payload, dict):
        return True
    if "success" in payload and not bool(payload.get("success")):
        return False
    code = payload.get("code")
    return code is None or str(code) == "200"


def response_message(payload: Any) -> str:
    if isinstance(payload, dict):
        return str(scrub(str(payload.get("message") or payload.get("error") or "")))[:500]
    return str(scrub(str(payload)))[:500]


def safe_error(exc: BaseException) -> str:
    return str(scrub(str(exc)))[:2000]


def page_items(payload: Any) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    data = unwrap(payload)
    if isinstance(data, list):
        return [row for row in data if isinstance(row, dict)], {}
    if not isinstance(data, dict):
        return [], {}
    for key in ("items", "spans", "traces", "list"):
        rows = data.get(key)
        if isinstance(rows, list):
            return [row for row in rows if isinstance(row, dict)], data
    return [], data


def parse_path_and_params(url: str, default_path: str, default_params: dict[str, Any]) -> tuple[str, dict[str, Any]]:
    if not url:
        return default_path, dict(default_params)
    parsed = urlparse(url)
    path = parsed.path or url.split("?", 1)[0]
    params = dict(parse_qsl(parsed.query, keep_blank_values=True))
    return path, params or dict(default_params)


class HttpClient:
    """带有界 GET 重试、线程本地连接池和脱敏计时记录的客户端。"""

    def __init__(
        self,
        *,
        base_url: str,
        timeout: float,
        retries: int,
        verify_ssl: bool,
        timing_path: Path,
    ) -> None:
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.retries = retries
        self.verify_ssl = verify_ssl
        self.timing_path = timing_path
        self.token = ""
        self._local = threading.local()
        self._timing_lock = threading.Lock()
        atomic_write_bytes(timing_path, b"")

    def set_token(self, token: str) -> None:
        self.token = token.strip()

    def _session(self) -> requests.Session:
        session = getattr(self._local, "session", None)
        if session is None:
            session = requests.Session()
            session.trust_env = True
            self._local.session = session
        return session

    def _record(self, record: dict[str, Any]) -> None:
        line = json.dumps(scrub(record), ensure_ascii=False, separators=(",", ":")) + "\n"
        with self._timing_lock:
            with self.timing_path.open("a", encoding="utf-8") as handle:
                handle.write(line)

    def request(
        self,
        method: str,
        path: str,
        *,
        params: dict[str, Any] | None = None,
        json_body: dict[str, Any] | None = None,
    ) -> requests.Response:
        method = method.upper()
        url = path if path.startswith(("http://", "https://")) else f"{self.base_url}/{path.lstrip('/')}"
        max_attempts = 1 + (self.retries if method == "GET" else 0)
        last_error: Exception | None = None
        for attempt in range(1, max_attempts + 1):
            headers = {"Authorization": f"Bearer {self.token}"} if self.token else {}
            started = time.perf_counter()
            response: requests.Response | None = None
            error_text: str | None = None
            try:
                response = self._session().request(
                    method,
                    url,
                    headers=headers,
                    params=params,
                    json=json_body,
                    timeout=self.timeout,
                    verify=self.verify_ssl,
                )
            except requests.RequestException as exc:
                last_error = exc
                error_text = f"{exc.__class__.__name__}: {exc}"
            finally:
                self._record(
                    {
                        "at": utc_now(),
                        "method": method,
                        "path": urlparse(url).path,
                        "params": params,
                        "status": response.status_code if response is not None else None,
                        "durationMs": round((time.perf_counter() - started) * 1000, 2),
                        "attempt": attempt,
                        "retried": attempt < max_attempts
                        and (response is None or response.status_code in RETRYABLE_STATUSES),
                        "error": error_text,
                    }
                )
            retry = attempt < max_attempts and (
                response is None or response.status_code in RETRYABLE_STATUSES
            )
            if not retry:
                if response is None:
                    raise CollectionError(f"{method} {urlparse(url).path} 请求失败: {last_error}")
                return response
            time.sleep(min(0.5 * (2 ** (attempt - 1)), 4.0))
        raise CollectionError(f"{method} {urlparse(url).path} 请求失败: {last_error}")

    def get_json(
        self,
        path: str,
        *,
        params: dict[str, Any] | None = None,
        expected: set[int] | None = None,
        require_wrapper_success: bool = True,
    ) -> tuple[int, Any]:
        response = self.request("GET", path, params=params)
        expected_statuses = expected or {200}
        try:
            payload = response.json()
        except ValueError:
            payload = {"text": response.text[:2000]}
        if response.status_code not in expected_statuses:
            raise CollectionError(
                f"GET {urlparse(response.url).path} 返回 HTTP {response.status_code}: {response_message(payload)}"
            )
        if require_wrapper_success and not wrapper_ok(payload):
            raise CollectionError(
                f"GET {urlparse(response.url).path} 返回业务错误: {response_message(payload)}"
            )
        return response.status_code, payload

    def get_bytes(self, path: str, *, params: dict[str, Any] | None = None) -> tuple[bytes, dict[str, str]]:
        response = self.request("GET", path, params=params)
        if response.status_code != 200:
            raise CollectionError(
                f"GET {urlparse(response.url).path} 返回 HTTP {response.status_code}: "
                f"{scrub(response.text[:300])}"
            )
        return response.content, dict(response.headers)


def extract_token(response: requests.Response) -> str:
    text = response.text.strip()
    if text.startswith("eyJ") and text.count(".") >= 2:
        return text
    try:
        payload = response.json()
    except ValueError:
        payload = None
    candidates = [payload]
    if isinstance(payload, dict) and isinstance(payload.get("data"), dict):
        candidates.append(payload["data"])
    for candidate in candidates:
        if not isinstance(candidate, dict):
            continue
        for key in ("token", "accessToken", "access_token", "jwt"):
            token = str(candidate.get(key) or "").strip()
            if token:
                return token
    match = re.search(r"eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+", text)
    return match.group(0) if match else ""


def login_admin(
    client: HttpClient,
    *,
    username: str,
    password: str,
    login_path: str,
    logout_path: str,
    force_relogin: bool,
) -> None:
    response = client.request(
        "POST",
        login_path,
        json_body={"username": username, "password": password},
    )
    if response.status_code == 400 and "User already logged in" in response.text:
        if not force_relogin:
            raise CollectionError(
                "管理员账号已有登录会话。为避免中断其他会话，脚本默认不自动登出；"
                "确认可以替换该会话后加 --force-relogin。"
            )
        logout = client.request("POST", logout_path, json_body={"username": username})
        if logout.status_code not in {200, 400}:
            raise CollectionError(f"清理旧登录会话失败: HTTP {logout.status_code}")
        response = client.request(
            "POST",
            login_path,
            json_body={"username": username, "password": password},
        )
    if response.status_code != 200:
        raise CollectionError(f"管理员登录失败: HTTP {response.status_code} {scrub(response.text[:300])}")
    token = extract_token(response)
    if not token:
        logout_admin(client, username=username, logout_path=logout_path)
        raise CollectionError("管理员登录成功，但响应中没有可识别的 token")
    client.set_token(token)


def logout_admin(client: HttpClient, *, username: str, logout_path: str) -> bool:
    """结束脚本创建的登录会话，避免账号被遗留为已登录状态。"""

    try:
        response = client.request("POST", logout_path, json_body={"username": username})
        return response.status_code in {200, 400}
    except Exception:  # noqa: BLE001
        return False


def require_diagnostic_read_capabilities(client: HttpClient) -> dict[str, bool]:
    """在触碰具体 Run 前确认整条后端链路支持无副作用管理员读取。"""

    _, payload = client.get_json(DIAGNOSTIC_READ_CAPABILITIES_PATH)
    data = unwrap(payload)
    if not isinstance(data, dict):
        raise CollectionError("后端诊断读取能力响应格式无效；已在读取任何 Run 前停止")
    missing = [name for name in REQUIRED_DIAGNOSTIC_READ_CAPABILITIES if data.get(name) is not True]
    if missing:
        raise CollectionError(
            "后端尚未确认无副作用管理员读取能力，缺少或未启用: "
            + ", ".join(missing)
            + "；已在读取任何 Run 前停止"
        )
    return {name: True for name in REQUIRED_DIAGNOSTIC_READ_CAPABILITIES}


def validate_run_id(run_id: str) -> str:
    value = run_id.strip()
    if not RUN_ID_PATTERN.fullmatch(value):
        raise CollectionError("Run ID 必须是 32 位十六进制字符串或标准 UUID，不能包含目录字符")
    return value


def validate_credential_transport(base_url: str, allow_insecure_http: bool) -> None:
    parsed = urlparse(base_url)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname:
        raise CollectionError("--base-url 必须是包含主机名的 http:// 或 https:// 地址")
    if parsed.username or parsed.password:
        raise CollectionError("--base-url 不能包含 userinfo；不要把账号或密码放进 URL")
    if parsed.query or parsed.fragment:
        raise CollectionError("--base-url 不能包含 query 或 fragment")
    if parsed.path not in {"", "/"}:
        raise CollectionError("--base-url 只能填写服务根地址，不能附带 API path")
    if parsed.scheme == "https":
        return
    host = parsed.hostname.lower()
    if host in {"127.0.0.1", "localhost", "::1"}:
        return
    if not allow_insecure_http:
        raise CollectionError(
            "当前 --base-url 使用非本机明文 HTTP，管理员密码会在网络中明文传输。"
            "确认这是受控测试网络并接受风险后，显式加 --allow-insecure-http；否则改用 HTTPS。"
        )


def find_repo_root(explicit: str | None) -> Path | None:
    if explicit:
        root = Path(explicit).expanduser().resolve()
        return root if (root / "test_scripts").exists() else None
    cwd = Path.cwd().resolve()
    for candidate in (cwd, *cwd.parents):
        if (candidate / "pom.xml").exists() and (candidate / "test_scripts").exists():
            return candidate
    if DEFAULT_REPO_ROOT.exists():
        return DEFAULT_REPO_ROOT.resolve()
    return None


def jsonl_contains_run(path: Path, run_id: str) -> bool:
    try:
        with path.open("r", encoding="utf-8") as handle:
            for line in handle:
                if not line.strip() or run_id not in line:
                    continue
                row = json.loads(line)
                if isinstance(row, dict) and str(row.get("runId") or "").strip() == run_id:
                    return True
    except (OSError, json.JSONDecodeError):
        return False
    return False


def discover_run_dir(repo_root: Path, run_id: str) -> Path:
    stress_root = repo_root / "test_scripts/agent-v1p0/output/agent-v1p0/stress-test"
    matches: set[Path] = set()
    for jsonl_path in stress_root.glob("*/agent-runs-batch*.jsonl"):
        if jsonl_contains_run(jsonl_path, run_id):
            matches.add(jsonl_path.parent.resolve())
    if not matches:
        raise CollectionError(
            f"在 {stress_root} 中没有找到包含 Run ID {run_id} 的压测目录。"
            "请用 --run-dir 指定原压测目录，或用 --output-dir 明确指定输出位置。"
        )
    if len(matches) > 1:
        joined = ", ".join(str(path) for path in sorted(matches))
        raise CollectionError(f"Run ID 同时出现在多个压测目录中，请用 --run-dir 明确选择: {joined}")
    return next(iter(matches))


def resolve_output_root(args: argparse.Namespace, run_id: str) -> tuple[Path, Path | None]:
    del run_id
    if not args.output_dir:
        raise CollectionError("采集必须提供 --output-dir，诊断采集不查找压测批次目录")
    return Path(args.output_dir).expanduser().resolve(), None


def parse_sections(values: list[str] | None, exclusions: list[str] | None) -> tuple[set[str], list[str]]:
    requested: set[str] = set()
    for value in values or []:
        requested.update(part.strip().lower() for part in value.split(",") if part.strip())
    if not requested:
        requested = set(DEFAULT_SECTIONS)
    elif "all" in requested:
        requested.discard("all")
        requested.update(DEFAULT_SECTIONS)
    unknown = requested - ALL_SECTIONS
    if unknown:
        raise CollectionError(f"未知 section: {', '.join(sorted(unknown))}")
    excluded: set[str] = set()
    for value in exclusions or []:
        excluded.update(part.strip().lower() for part in value.split(",") if part.strip())
    unknown_excluded = excluded - ALL_SECTIONS
    if unknown_excluded:
        raise CollectionError(f"未知排除 section: {', '.join(sorted(unknown_excluded))}")
    selected = requested - excluded
    implicit: list[str] = []
    if selected & TRACE_DEPENDENT_SECTIONS and "traces" not in selected:
        selected.add("traces")
        implicit.append("traces")
    if "artifact-parts" in selected and "artifacts" not in selected:
        selected.add("artifacts")
        implicit.append("artifacts")
    if not selected:
        raise CollectionError("section 选择结果为空")
    return selected, implicit


def normalize_run_status(raw_status: Any) -> str:
    """把不同 Run 响应里的状态统一成摘要使用的名称。"""

    status = str(raw_status or "").strip().upper()
    if status.startswith("RUN_"):
        status = status[4:]
    if status == "TIMED_OUT":
        status = "EXPIRED"
    return status


def observed_run_statuses(
    payloads: dict[str, Any],
    *,
    preflight_status: str | None = None,
) -> list[str]:
    """从基础响应提取状态，用于判断分页期间 Run 是否仍可能变化。"""

    statuses: set[str] = set()
    if preflight_status:
        statuses.add(preflight_status)
    for section in ("run", "status", "result"):
        data = unwrap(payloads.get(section))
        if not isinstance(data, dict):
            continue
        status = normalize_run_status(data.get("status"))
        if status:
            statuses.add(status)
    return sorted(statuses)


def fetch_cursor_pages(
    client: HttpClient,
    *,
    path: str,
    cursor_param: str,
    cursor_field: str,
    limit: int = 500,
) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    cursor = 0
    while True:
        _, payload = client.get_json(path, params={cursor_param: cursor, "limit": limit})
        page, data = page_items(payload)
        rows.extend(page)
        has_more = bool(data.get("hasMore") or data.get("has_more"))
        next_cursor = data.get(cursor_field)
        if next_cursor is None:
            next_cursor = data.get("next_after_seq")
        if next_cursor is None and page:
            next_cursor = max(int(row.get("seq") or cursor) for row in page)
        next_value = int(next_cursor or cursor)
        if not has_more:
            break
        if next_value <= cursor:
            raise CollectionError(f"{path} 声明还有下一页，但游标没有前进")
        cursor = next_value
    return rows


def fetch_messages(client: HttpClient, path: str) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    offset = 0
    while True:
        _, payload = client.get_json(
            path,
            params={"offset": offset, "limit": 200, "include_initial": "true"},
        )
        page, data = page_items(payload)
        rows.extend(page)
        if not bool(data.get("hasMore") or data.get("has_more")):
            break
        if not page:
            raise CollectionError(f"{path} 声明还有下一页，但当前页为空")
        offset += len(page)
    return rows


def fetch_traces(client: HttpClient, path: str) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    after = 0
    seen: set[str] = set()
    while True:
        _, payload = client.get_json(path, params={"after": after, "limit": 500})
        page, _ = page_items(payload)
        if not page:
            break
        max_seq = after
        for row in page:
            trace_id = str(row.get("traceId") or "").strip()
            if trace_id and trace_id not in seen:
                rows.append(row)
                seen.add(trace_id)
            max_seq = max(max_seq, int(row.get("seq") or after))
        if len(page) < 500:
            break
        if max_seq <= after:
            raise CollectionError(f"{path} trace 游标没有前进")
        after = max_seq
    return rows


def decode_base64_payload(value: Any) -> tuple[Any | None, str | None]:
    if not isinstance(value, str) or not value:
        return None, None
    try:
        raw = base64.b64decode(value, validate=True)
    except Exception as exc:  # noqa: BLE001
        return None, f"base64 解码失败: {exc}"
    try:
        return json.loads(raw.decode("utf-8")), None
    except (UnicodeDecodeError, json.JSONDecodeError):
        try:
            return raw.decode("utf-8"), None
        except UnicodeDecodeError:
            return None, f"解码后是 {len(raw)} 字节二进制内容，出于脱敏要求未保存"


def decompress_gzip_bounded(content: bytes, max_output_bytes: int, label: str) -> bytes:
    """流式解压 gzip，并在输出越界前停止，避免压缩炸弹占满内存。"""

    try:
        with gzip.GzipFile(fileobj=io.BytesIO(content), mode="rb") as handle:
            raw = handle.read(max_output_bytes + 1)
    except (OSError, EOFError) as exc:
        raise CollectionError(f"{label} gzip 解压失败: {exc}") from exc
    if len(raw) > max_output_bytes:
        raise CollectionError(f"{label} 解压后超过 --max-download-bytes={max_output_bytes}")
    return raw


def verify_parts_integrity(
    *,
    meta: dict[str, Any],
    compressed: bytes,
    raw: bytes,
    label: str,
    report_path: Path,
) -> None:
    """校验服务端分片元数据，防止错片、缺片或静默截断被当成成功。"""

    errors: list[str] = []
    checks: list[str] = []
    expected_compressed = int(meta.get("compressedSize") or meta.get("compressed_size") or 0)
    expected_uncompressed = int(meta.get("uncompressedSize") or meta.get("uncompressed_size") or 0)
    checksum = str(meta.get("checksum") or "").strip()
    report: dict[str, Any] = {
        "compressedSizeActual": len(compressed),
        "uncompressedSizeActual": len(raw),
        "checksumExpected": checksum or None,
    }
    if expected_compressed > 0:
        checks.append("compressedSize")
        report["compressedSizeExpected"] = expected_compressed
        if len(compressed) != expected_compressed:
            errors.append(f"compressedSize expected={expected_compressed} actual={len(compressed)}")
    if expected_uncompressed > 0:
        checks.append("uncompressedSize")
        report["uncompressedSizeExpected"] = expected_uncompressed
        if len(raw) != expected_uncompressed:
            errors.append(f"uncompressedSize expected={expected_uncompressed} actual={len(raw)}")
    if checksum:
        checks.append("checksum")
        if ":" in checksum:
            algorithm, expected_digest = checksum.split(":", 1)
            algorithm = algorithm.lower().strip()
        elif len(checksum) == 32:
            algorithm, expected_digest = "md5", checksum
        elif len(checksum) == 64:
            algorithm, expected_digest = "sha256", checksum
        else:
            algorithm, expected_digest = "unknown", checksum
        if algorithm == "md5":
            actual_digest = hashlib.md5(compressed).hexdigest()  # noqa: S324 - protocol checksum, not security
        elif algorithm == "sha256":
            actual_digest = hashlib.sha256(compressed).hexdigest()
        else:
            actual_digest = ""
            errors.append(f"unsupported checksum algorithm: {algorithm}")
        report["checksumAlgorithm"] = algorithm
        report["checksumActual"] = actual_digest or None
        if actual_digest and actual_digest.lower() != expected_digest.strip().lower():
            errors.append(f"checksum expected={expected_digest.strip().lower()} actual={actual_digest.lower()}")
    report["checksPerformed"] = checks
    report["valid"] = not errors
    report["errors"] = errors
    write_json(report_path, report)
    if errors:
        raise CollectionError(f"{label} 完整性校验失败: {'; '.join(errors)}")


def save_decoded_full_detail(full_detail: dict[str, Any], out_dir: Path) -> dict[str, int]:
    counts = {
        "httpRequestDecoded": 0,
        "httpResponseDecoded": 0,
        "toolParamsDecoded": 0,
        "toolOutputDecoded": 0,
        "decodeErrors": 0,
    }
    fields = (
        ("httpRequestBase64", "http_request", "httpRequestDecoded"),
        ("httpResponseBase64", "http_response", "httpResponseDecoded"),
        ("paramsBase64", "tool_params", "toolParamsDecoded"),
        ("outputBase64", "tool_output", "toolOutputDecoded"),
    )
    errors: list[dict[str, str]] = []
    for field, filename, count_key in fields:
        decoded, error = decode_base64_payload(full_detail.get(field))
        if error:
            counts["decodeErrors"] += 1
            errors.append({"field": field, "error": error})
            continue
        if decoded is None:
            continue
        if isinstance(decoded, str):
            atomic_write_bytes(out_dir / f"{filename}.txt", (str(scrub(decoded)) + "\n").encode("utf-8"))
        else:
            write_json(out_dir / f"{filename}.json", decoded)
        counts[count_key] += 1
    if errors:
        write_json(out_dir / "decode_errors.json", errors)
    return counts


def expand_full_detail_parts(
    client: HttpClient,
    *,
    run_id: str,
    trace_id: str,
    parts: dict[str, Any],
    out_dir: Path,
    max_part_size: int,
    max_download_bytes: int,
) -> dict[str, Any]:
    total = int(parts.get("totalParts") or parts.get("total_parts") or 0)
    if total <= 0:
        raise CollectionError("fullDetailParts 没有有效的 totalParts")
    default_path = f"/api/agent/runs/{run_id}/traces/{quote(trace_id, safe='')}/full/parts"
    parts_path, params = parse_path_and_params(
        str(parts.get("partsUrl") or ""),
        default_path,
        {"maxPartSize": max_part_size},
    )
    chunks: list[bytes] = []
    total_bytes = 0
    for index in range(total):
        chunk, _ = client.get_bytes(f"{parts_path.rstrip('/')}/{index}", params=params)
        total_bytes += len(chunk)
        if total_bytes > max_download_bytes:
            raise CollectionError(f"trace 分片总大小超过 --max-download-bytes={max_download_bytes}")
        chunks.append(chunk)
    compressed = b"".join(chunks)
    compression = str(parts.get("compression") or "").lower()
    raw = (
        decompress_gzip_bounded(compressed, max_download_bytes, "trace 分片")
        if compression == "gzip"
        else compressed
    )
    if len(raw) > max_download_bytes:
        raise CollectionError(f"trace 解压后超过 --max-download-bytes={max_download_bytes}")
    verify_parts_integrity(
        meta=parts,
        compressed=compressed,
        raw=raw,
        label="trace 分片",
        report_path=out_dir / "parts_integrity.json",
    )
    try:
        full_detail = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise CollectionError(f"trace 分片合并后不是 UTF-8 JSON: {exc}") from exc
    if not isinstance(full_detail, dict):
        raise CollectionError("trace 分片合并后的 JSON 不是对象")
    write_json(out_dir / "parts_meta.json", parts)
    return full_detail


def merge_counts(target: dict[str, int], source: dict[str, int]) -> None:
    for key, value in source.items():
        target[key] = target.get(key, 0) + int(value or 0)


def collect_one_trace(
    client: HttpClient,
    *,
    run_id: str,
    trace: dict[str, Any],
    selected: set[str],
    output_dir: Path,
    max_part_size: int,
    max_download_bytes: int,
) -> tuple[dict[str, int], list[dict[str, Any]]]:
    trace_id = str(trace.get("traceId") or "").strip()
    if not trace_id:
        return {}, [{"section": "traces", "error": "trace 缺少 traceId"}]
    trace_path_id = quote(trace_id, safe="")
    safe_trace_id = safe_name(trace_id, "trace")
    counts: dict[str, int] = {}
    errors: list[dict[str, Any]] = []

    if "trace-details" in selected:
        try:
            _, payload = client.get_json(f"/api/agent/runs/{run_id}/traces/{trace_path_id}")
            write_json(output_dir / "trace_details" / f"{safe_trace_id}.json", payload)
            counts["traceDetailsFetched"] = 1
        except Exception as exc:  # noqa: BLE001
            errors.append({"section": "trace-details", "traceId": trace_id, "error": safe_error(exc)})

    if "trace-full" in selected:
        full_dir = output_dir / "trace_full_details" / safe_trace_id
        try:
            _, payload = client.get_json(
                f"/api/agent/runs/{run_id}/traces/{trace_path_id}",
                params={"full": "true", "maxPartSize": max_part_size},
                expected={200, 404, 410},
                require_wrapper_success=False,
            )
            write_json(full_dir / "trace_full_response.json", payload, strip_base64=True)
            if not wrapper_ok(payload):
                message = response_message(payload)
                key = "traceFullExpired" if "EXPIRED" in message.upper() else "traceFullNotFound"
                counts[key] = 1
                errors.append(
                    {
                        "section": "trace-full",
                        "traceId": trace_id,
                        "error": message or "完整 trace 原始详情不存在或已过期",
                    }
                )
            else:
                data = unwrap(payload)
                if not isinstance(data, dict):
                    raise CollectionError("trace full 响应 data 不是对象")
                full_detail = data.get("fullDetail")
                if isinstance(full_detail, dict):
                    counts["traceFullInline"] = 1
                else:
                    parts = data.get("fullDetailParts")
                    if not isinstance(parts, dict):
                        raise CollectionError("trace full 响应既没有 fullDetail，也没有 fullDetailParts")
                    full_detail = expand_full_detail_parts(
                        client,
                        run_id=run_id,
                        trace_id=trace_id,
                        parts=parts,
                        out_dir=full_dir,
                        max_part_size=max_part_size,
                        max_download_bytes=max_download_bytes,
                    )
                    counts["traceFullParts"] = 1
                write_json(full_dir / "full_detail.json", full_detail, strip_base64=True)
                merge_counts(counts, save_decoded_full_detail(full_detail, full_dir))
                counts["traceFullFetched"] = 1
        except Exception as exc:  # noqa: BLE001
            errors.append({"section": "trace-full", "traceId": trace_id, "error": safe_error(exc)})

    if "call-details" in selected:
        trace_type = str(trace.get("type") or "").lower()
        try:
            if trace_type == "llm":
                path = f"/api/agent/runs/{run_id}/llm-calls/{trace_path_id}/detail"
                params = {"includeThinking": "true"}
                subdir = "llm"
            elif trace_type == "tool":
                path = f"/api/agent/runs/{run_id}/tool-calls/{trace_path_id}/detail"
                params = None
                subdir = "tool"
            else:
                raise CollectionError(f"未知 trace type: {trace_type or '<empty>'}")
            _, payload = client.get_json(path, params=params)
            write_json(output_dir / "call_details" / subdir / f"{safe_trace_id}.json", payload)
            counts["callDetailResponsesFetched"] = 1
            data = unwrap(payload)
            detail_kind = str(data.get("detailKind") or "").lower() if isinstance(data, dict) else ""
            reasoning_unavailable = bool(data.get("reasoningUnavailable")) if isinstance(data, dict) else False
            if detail_kind in {"unavailable", "expired"}:
                counts["callDetailsUnavailable"] = 1
                errors.append(
                    {
                        "section": "call-details",
                        "traceId": trace_id,
                        "error": f"调用详情不可用: detailKind={detail_kind}",
                    }
                )
            else:
                counts["callDetailsFetched"] = 1
                if detail_kind == "truncated":
                    counts["callDetailsTruncated"] = 1
            if reasoning_unavailable:
                counts["reasoningUnavailable"] = 1
        except Exception as exc:  # noqa: BLE001
            errors.append({"section": "call-details", "traceId": trace_id, "error": safe_error(exc)})
    return counts, errors


def collect_artifact_parts(
    client: HttpClient,
    *,
    run_id: str,
    artifact: dict[str, Any],
    output_dir: Path,
    max_part_size: int,
    max_download_bytes: int,
) -> tuple[dict[str, int], list[dict[str, Any]]]:
    artifact_id = str(artifact.get("artifactId") or "").strip()
    if not artifact_id:
        return {}, [{"section": "artifact-parts", "error": "artifact 缺少 artifactId"}]
    safe_id = safe_name(artifact_id, "artifact")
    artifact_path_id = quote(artifact_id, safe="")
    out_dir = output_dir / "artifact_parts" / safe_id
    try:
        _, meta_payload = client.get_json(
            f"/api/agent/runs/{run_id}/artifacts/{artifact_path_id}/parts",
            params={"maxPartSize": max_part_size},
        )
        write_json(out_dir / "parts_meta.json", meta_payload)
        meta = unwrap(meta_payload)
        if not isinstance(meta, dict):
            raise CollectionError("artifact parts 元数据不是对象")
        total = int(meta.get("totalParts") or 0)
        chunks: list[bytes] = []
        total_bytes = 0
        for index in range(total):
            chunk, _ = client.get_bytes(
                f"/api/agent/runs/{run_id}/artifacts/{artifact_path_id}/parts/{index}",
                params={"maxPartSize": max_part_size},
            )
            total_bytes += len(chunk)
            if total_bytes > max_download_bytes:
                raise CollectionError(f"artifact 分片超过 --max-download-bytes={max_download_bytes}")
            chunks.append(chunk)
        compressed = b"".join(chunks)
        raw = (
            decompress_gzip_bounded(compressed, max_download_bytes, "artifact 分片")
            if str(meta.get("compression") or "").lower() == "gzip"
            else compressed
        )
        if len(raw) > max_download_bytes:
            raise CollectionError(f"artifact 解压后超过 --max-download-bytes={max_download_bytes}")
        verify_parts_integrity(
            meta=meta,
            compressed=compressed,
            raw=raw,
            label="artifact 分片",
            report_path=out_dir / "parts_integrity.json",
        )
        digest = hashlib.sha256(raw).hexdigest()
        try:
            decoded: Any = json.loads(raw.decode("utf-8"))
            write_json(out_dir / "content.json", decoded, strip_base64=True)
            saved_as = "content.json"
        except (UnicodeDecodeError, json.JSONDecodeError):
            try:
                text = raw.decode("utf-8")
                atomic_write_bytes(out_dir / "content.txt", (str(scrub(text)) + "\n").encode("utf-8"))
                saved_as = "content.txt"
            except UnicodeDecodeError:
                write_json(
                    out_dir / "content_omitted.json",
                    {
                        "reason": "二进制 Artifact 无法可靠脱敏，因此没有保存原始正文",
                        "sizeBytes": len(raw),
                        "sha256": digest,
                    },
                )
                saved_as = "content_omitted.json"
        write_json(out_dir / "content_summary.json", {"sizeBytes": len(raw), "sha256": digest, "savedAs": saved_as})
        return {"artifactPartsFetched": 1}, []
    except Exception as exc:  # noqa: BLE001
        return {}, [{"section": "artifact-parts", "artifactId": artifact_id, "error": safe_error(exc)}]


def base_section_jobs(
    client: HttpClient,
    *,
    run_id: str,
    selected: set[str],
) -> dict[str, Callable[[], Any]]:
    prefix = f"/api/agent/runs/{run_id}"
    jobs: dict[str, Callable[[], Any]] = {}
    if "run" in selected:
        jobs["run"] = lambda: client.get_json(prefix)[1]
    if "status" in selected:
        jobs["status"] = lambda: client.get_json(f"{prefix}/status")[1]
    if "events" in selected:
        jobs["events"] = lambda: fetch_cursor_pages(
            client,
            path=f"{prefix}/events",
            cursor_param="after_seq",
            cursor_field="nextAfterSeq",
        )
    if "timeline" in selected:
        jobs["timeline"] = lambda: fetch_cursor_pages(
            client,
            path=f"{prefix}/timeline",
            cursor_param="after_seq",
            cursor_field="nextAfterSeq",
        )
    if "result" in selected:
        jobs["result"] = lambda: client.get_json(f"{prefix}/result", expected={200, 202})[1]
    if "credits" in selected:
        jobs["credits"] = lambda: client.get_json(f"{prefix}/credits")[1]
    if "messages" in selected:
        jobs["messages"] = lambda: fetch_messages(client, f"{prefix}/messages")
    if "artifacts" in selected:
        jobs["artifacts"] = lambda: client.get_json(
            f"{prefix}/artifacts",
            params={"skip_lazy_registration": "true"},
        )[1]
    if "observability" in selected:
        jobs["observability"] = lambda: client.get_json(f"{prefix}/observability/full")[1]
    if "traces" in selected:
        jobs["traces"] = lambda: fetch_traces(client, f"{prefix}/traces")
    return jobs


def save_base_section(output_dir: Path, section: str, payload: Any) -> int:
    if section == "events":
        rows = payload if isinstance(payload, list) else []
        write_json(output_dir / "events.json", rows)
        write_jsonl(output_dir / "events.jsonl", rows)
        return len(rows)
    if section == "timeline":
        rows = payload if isinstance(payload, list) else []
        write_json(output_dir / "timeline.json", rows)
        return len(rows)
    if section == "messages":
        rows = payload if isinstance(payload, list) else []
        write_json(output_dir / "messages.json", rows)
        return len(rows)
    if section == "traces":
        rows = payload if isinstance(payload, list) else []
        write_json(output_dir / "traces.json", rows)
        write_json(output_dir / "traces_llm.json", [row for row in rows if str(row.get("type") or "").lower() == "llm"])
        write_json(output_dir / "traces_tool.json", [row for row in rows if str(row.get("type") or "").lower() == "tool"])
        return len(rows)
    filename = "observability_full.json" if section == "observability" else f"{section}.json"
    write_json(output_dir / filename, payload)
    data = unwrap(payload)
    return len(data) if isinstance(data, list) else 1


def resolve_password(args: argparse.Namespace) -> str:
    if args.admin_password_stdin:
        password = sys.stdin.readline().rstrip("\r\n")
    elif args.admin_password is not None:
        password = args.admin_password
    else:
        password = os.environ.get("ALPHAFROG_ADMIN_PASSWORD", "")
    if not password and sys.stdin.isatty() and not args.admin_password_stdin:
        password = getpass.getpass("管理员密码: ")
    if not password:
        raise CollectionError(
            "缺少管理员密码。推荐设置 ALPHAFROG_ADMIN_PASSWORD，"
            "也可用 --admin-password-stdin 或 --admin-password。"
        )
    return password


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="并行拉取一个既有 Agent Run 的管理员可观测数据（只读）",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument("--run-id", help="32 位十六进制 Run ID 或标准 UUID；采集时必填")
    parser.add_argument(
        "--admin-username",
        default=os.environ.get("ALPHAFROG_ADMIN_USERNAME", ""),
        help="管理员用户名；也可用 ALPHAFROG_ADMIN_USERNAME",
    )
    password_group = parser.add_mutually_exclusive_group()
    password_group.add_argument(
        "--admin-password",
        help="管理员密码；会出现在 shell 历史/进程参数中，优先使用环境变量或标准输入",
    )
    password_group.add_argument(
        "--admin-password-stdin",
        action="store_true",
        help="从标准输入第一行读取管理员密码",
    )
    parser.add_argument(
        "--base-url",
        default=os.environ.get("ALPHAFROG_BASE_URL", DEFAULT_BASE_URL),
        help="Frontend 服务地址；也可用 ALPHAFROG_BASE_URL",
    )
    parser.add_argument(
        "--allow-insecure-http",
        action="store_true",
        help="允许把管理员凭据发送到非本机明文 HTTP；必须显式确认，优先使用 HTTPS",
    )
    parser.add_argument("--admin-login-path", default="/admin/login")
    parser.add_argument("--logout-path", default="/api/auth/logout")
    parser.add_argument(
        "--force-relogin",
        action="store_true",
        help="账号已有登录时先登出再登录；这会使旧会话失效，默认关闭",
    )
    parser.add_argument(
        "--allow-active-run",
        action="store_true",
        help="允许对仍在执行的 Run 做非一致性的尽力采集；默认把这种情况记为部分失败",
    )
    parser.add_argument("--sections", action="append", help="逗号分隔的 section；省略或 all 表示默认全量")
    parser.add_argument("--exclude-sections", action="append", help="从选择结果中排除 section，逗号分隔")
    parser.add_argument("--run-dir", help="原压测目录；不传时根据 Run ID 自动查找")
    parser.add_argument("--output-dir", help="明确输出目录；默认写入原压测目录的 observability-single-run/<Run ID>")
    parser.add_argument("--repo-root", help="用于自动查找原压测目录的 alphafrog-micro 仓库根目录")
    parser.add_argument("--jobs", type=int, default=12, help="并行请求工作数")
    parser.add_argument("--retries", type=int, default=2, help="只对 GET 的 429/502/503 和网络异常重试次数")
    parser.add_argument("--timeout-seconds", type=float, default=30.0)
    parser.add_argument("--max-part-size", type=int, default=262144)
    parser.add_argument("--max-download-bytes", type=int, default=64 * 1024 * 1024)
    parser.add_argument("--insecure", action="store_true", help="关闭 TLS 证书校验，仅用于明确的测试环境")
    parser.add_argument("--list-sections", action="store_true", help="列出可选 section 后退出")
    return parser


def validate_numeric_args(args: argparse.Namespace) -> None:
    if not 1 <= args.jobs <= 64:
        raise CollectionError("--jobs 必须在 1..64")
    if not 0 <= args.retries <= 8:
        raise CollectionError("--retries 必须在 0..8")
    if args.timeout_seconds <= 0:
        raise CollectionError("--timeout-seconds 必须大于 0")
    if not 16 * 1024 <= args.max_part_size <= 4 * 1024 * 1024:
        raise CollectionError("--max-part-size 必须在 16384..4194304 字节")
    if not 1024 * 1024 <= args.max_download_bytes <= 1024 * 1024 * 1024:
        raise CollectionError("--max-download-bytes 必须在 1 MiB..1 GiB")


def run_collection(args: argparse.Namespace) -> dict[str, Any]:
    validate_numeric_args(args)
    if not args.run_id:
        raise CollectionError("采集时必须提供 --run-id")
    run_id = validate_run_id(args.run_id)
    username = str(args.admin_username or "").strip()
    if not username:
        raise CollectionError("缺少管理员用户名；请传 --admin-username 或设置 ALPHAFROG_ADMIN_USERNAME")
    password = resolve_password(args)
    validate_credential_transport(str(args.base_url), bool(args.allow_insecure_http))
    selected, implicit = parse_sections(args.sections, args.exclude_sections)
    output_root, run_dir = resolve_output_root(args, run_id)
    collection_id = f"{datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S.%fZ')}-{uuid.uuid4().hex[:8]}"
    output_dir = output_root / "collections" / collection_id
    output_dir.mkdir(parents=True, exist_ok=True)
    client = HttpClient(
        base_url=str(args.base_url),
        timeout=float(args.timeout_seconds),
        retries=int(args.retries),
        verify_ssl=not bool(args.insecure),
        timing_path=output_dir / "request_timing.jsonl",
    )
    login_admin(
        client,
        username=username,
        password=password,
        login_path=args.admin_login_path,
        logout_path=args.logout_path,
        force_relogin=bool(args.force_relogin),
    )
    # 如果中途出现未捕获异常，进程退出时仍尝试清理本次登录会话。
    atexit.register(logout_admin, client, username=username, logout_path=args.logout_path)
    # 凭据只在登录请求的局部变量中使用；后续输出和摘要均不记录用户名、密码或 token。
    del password

    started = time.perf_counter()
    try:
        diagnostic_capabilities = require_diagnostic_read_capabilities(client)
    except Exception:
        # 能力握手失败时必须在触碰 Run 前退出，同时立即清理刚创建的管理员会话。
        if logout_admin(client, username=username, logout_path=args.logout_path):
            atexit.unregister(logout_admin)
        raise
    preflight_status_checked_at = utc_now()
    try:
        _, preflight_status_payload = client.get_json(f"/api/agent/runs/{run_id}/status")
        preflight_status_data = unwrap(preflight_status_payload)
        preflight_status = normalize_run_status(
            preflight_status_data.get("status") if isinstance(preflight_status_data, dict) else None
        )
        if not preflight_status:
            raise CollectionError("管理员诊断状态前置检查没有返回可识别的 Run 状态")
    except Exception as exc:
        # 无论用户选择哪些 section，活动 Run 判断都必须先成功；否则不能声称本次结果完整。
        if logout_admin(client, username=username, logout_path=args.logout_path):
            atexit.unregister(logout_admin)
        raise CollectionError(f"管理员诊断状态前置检查失败: {safe_error(exc)}") from exc
    payloads: dict[str, Any] = {}
    section_counts: dict[str, int] = {}
    errors: list[dict[str, Any]] = []
    jobs = base_section_jobs(client, run_id=run_id, selected=selected)
    with ThreadPoolExecutor(max_workers=args.jobs, thread_name_prefix="af-observe") as executor:
        future_to_section = {executor.submit(job): section for section, job in jobs.items()}
        for future in as_completed(future_to_section):
            section = future_to_section[future]
            try:
                payload = future.result()
                payloads[section] = payload
                section_counts[section] = save_base_section(output_dir, section, payload)
            except Exception as exc:  # noqa: BLE001
                errors.append({"section": section, "error": safe_error(exc)})

    observed_statuses = observed_run_statuses(payloads, preflight_status=preflight_status)
    active_statuses = [status for status in observed_statuses if status not in TERMINAL_RUN_STATUSES]
    status_inconsistent = len(observed_statuses) > 1
    if status_inconsistent:
        errors.append({
            "section": "collection-consistency",
            "error": "并行读取观察到不一致的 Run 状态；Run 在采集期间可能发生了状态变化，请在终态后重拉",
        })
    elif active_statuses and not args.allow_active_run:
        errors.append({
            "section": "collection-consistency",
            "error": (
                f"Run 仍处于 {active_statuses[0]}；事件、消息和 trace 可能在分页结束后继续新增。"
                "请等待终态后重拉，或显式加 --allow-active-run 接受尽力采集"
            ),
        })

    traces = payloads.get("traces") if isinstance(payloads.get("traces"), list) else []
    trace_counts: dict[str, int] = {}
    if traces and selected & TRACE_DEPENDENT_SECTIONS:
        with ThreadPoolExecutor(max_workers=args.jobs, thread_name_prefix="af-trace") as executor:
            futures = [
                executor.submit(
                    collect_one_trace,
                    client,
                    run_id=run_id,
                    trace=trace,
                    selected=selected,
                    output_dir=output_dir,
                    max_part_size=args.max_part_size,
                    max_download_bytes=args.max_download_bytes,
                )
                for trace in traces
            ]
            for future in as_completed(futures):
                counts, trace_errors = future.result()
                merge_counts(trace_counts, counts)
                errors.extend(trace_errors)

    artifacts_payload = payloads.get("artifacts")
    artifacts_data = unwrap(artifacts_payload)
    artifacts = artifacts_data if isinstance(artifacts_data, list) else []
    if "artifact-parts" in selected and artifacts:
        with ThreadPoolExecutor(max_workers=args.jobs, thread_name_prefix="af-artifact") as executor:
            futures = [
                executor.submit(
                    collect_artifact_parts,
                    client,
                    run_id=run_id,
                    artifact=artifact,
                    output_dir=output_dir,
                    max_part_size=args.max_part_size,
                    max_download_bytes=args.max_download_bytes,
                )
                for artifact in artifacts
            ]
            for future in as_completed(futures):
                counts, artifact_errors = future.result()
                merge_counts(trace_counts, counts)
                errors.extend(artifact_errors)

    session_logout_succeeded = logout_admin(client, username=username, logout_path=args.logout_path)
    if session_logout_succeeded:
        atexit.unregister(logout_admin)
    if not session_logout_succeeded:
        errors.append({"section": "admin-logout", "error": "采集完成，但清理本次管理员登录会话失败"})

    elapsed = round(time.perf_counter() - started, 3)
    summary = {
        "schemaVersion": 3,
        "collectedAt": utc_now(),
        "collectionId": collection_id,
        "runId": run_id,
        "runDir": str(run_dir) if run_dir else None,
        "outputRoot": str(output_root),
        "outputDir": str(output_dir),
        "baseUrl": str(args.base_url).rstrip("/"),
        "credentialTransport": urlparse(str(args.base_url)).scheme.lower(),
        "authMode": "admin",
        "readOnly": True,
        "diagnosticReadCapabilities": diagnostic_capabilities,
        "sessionLogoutSucceeded": session_logout_succeeded,
        "selectedSections": sorted(selected),
        "implicitlyAddedSections": implicit,
        "defaultSections": list(DEFAULT_SECTIONS),
        "collectionStability": {
            "statusPreflight": {
                "status": preflight_status,
                "checkedAt": preflight_status_checked_at,
                "persistedStatusSection": "status" in section_counts,
            },
            "observedRunStatuses": observed_statuses,
            "terminalStatusObserved": bool(observed_statuses)
            and not active_statuses
            and not status_inconsistent,
            "activeRunExplicitlyAllowed": bool(args.allow_active_run),
            "pointInTimeSnapshot": False,
            "note": (
                "终态 Run 的分页结果不会继续增长，但各 section 仍是独立请求，不是数据库级同一时刻快照。"
                if observed_statuses and not active_statuses and not status_inconsistent
                else "活动 Run 可能在采集期间继续新增事件、消息和 trace。"
            ),
        },
        "jobs": args.jobs,
        "getRetries": args.retries,
        "elapsedSeconds": elapsed,
        "sectionCounts": section_counts,
        "detailCounts": trace_counts,
        "errorCount": len(errors),
        "complete": not errors,
        "errors": errors,
        "security": {
            "providedAdminPasswordOrTokenStored": False,
            "knownCredentialFieldsRedacted": True,
            "traceFullBase64BlobsStored": False,
            "binaryArtifactsStored": False,
            "runLifecycleMutationRequested": False,
            "artifactLazyRegistrationSkipped": True,
            "diagnosticReadCapabilityPreflightPassed": True,
            "serverTransferCacheMayBePopulated": "artifact-parts" in selected,
            "note": "JSON/文本输出会按已知凭据字段和值形态脱敏；无法可靠脱敏的二进制 Artifact 只记录大小和 SHA-256。",
        },
    }
    write_json(output_dir / "summary.json", summary)
    write_json(
        output_root / "latest.json",
        {
            "collectionId": collection_id,
            "runId": run_id,
            "outputDir": str(output_dir),
            "summaryPath": str(output_dir / "summary.json"),
            "complete": summary["complete"],
            "collectedAt": summary["collectedAt"],
        },
    )
    return summary


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()
    if args.list_sections:
        print("默认全量 section:")
        for section in DEFAULT_SECTIONS:
            print(f"  {section}")
        print("需显式选择的可选 section:")
        for section in OPTIONAL_SECTIONS:
            print(f"  {section}")
        return 0
    try:
        summary = run_collection(args)
    except CollectionError as exc:
        print(f"ERROR: {safe_error(exc)}", file=sys.stderr)
        return 1
    print(json.dumps(scrub(summary), ensure_ascii=False, indent=2))
    return 0 if summary.get("complete") else 2


