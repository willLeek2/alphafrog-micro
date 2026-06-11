from __future__ import annotations

import copy
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Dict, Union

import yaml


@dataclass
class LightClientConfig:
    base_url: str
    username: str
    password: str
    question: str
    login_endpoint: str = "/api/auth/login"
    logout_endpoint: str = "/api/auth/logout"
    create_endpoint: str = "/api/agent/runs"
    stream_endpoint_template: str = "/api/agent/runs/{run_id}/stream"
    status_endpoint_template: str = "/api/agent/runs/{run_id}/status"
    events_endpoint_template: str = "/api/agent/runs/{run_id}/events"
    timeline_endpoint_template: str = "/api/agent/runs/{run_id}/timeline"
    result_endpoint_template: str = "/api/agent/runs/{run_id}/result"
    cancel_endpoint_template: str = "/api/agent/runs/{run_id}:cancel"
    observability_full_endpoint_template: str = "/api/agent/runs/{run_id}/observability/full"
    request_timeout_seconds: float = 30.0
    stream_idle_timeout_seconds: float = 300.0
    refresh_seconds: float = 0.5
    max_trace_lines: int = 14
    max_warning_lines: int = 4
    result_preview_chars: int = 280
    create_body: Dict[str, Any] = field(default_factory=dict)
    debug_logs: bool = False
    debug_output_root: str = "af_light_client/output"

    @classmethod
    def from_file(cls, path: Union[str, Path]) -> "LightClientConfig":
        config_path = Path(path)
        with config_path.open("r", encoding="utf-8") as fh:
            raw = yaml.safe_load(fh) or {}
        if not isinstance(raw, dict):
            raise ValueError(f"config must be a YAML object: {config_path}")
        cfg = cls(
            base_url=str(raw.get("base_url") or "").rstrip("/"),
            username=str(raw.get("username") or raw.get("user") or ""),
            password=str(raw.get("password") or ""),
            question=str(raw.get("question") or raw.get("message") or ""),
            login_endpoint=str(raw.get("login_endpoint") or "/api/auth/login"),
            logout_endpoint=str(raw.get("logout_endpoint") or "/api/auth/logout"),
            create_endpoint=str(raw.get("create_endpoint") or "/api/agent/runs"),
            stream_endpoint_template=str(
                raw.get("stream_endpoint_template") or "/api/agent/runs/{run_id}/stream"
            ),
            status_endpoint_template=str(
                raw.get("status_endpoint_template") or "/api/agent/runs/{run_id}/status"
            ),
            events_endpoint_template=str(
                raw.get("events_endpoint_template") or "/api/agent/runs/{run_id}/events"
            ),
            timeline_endpoint_template=str(
                raw.get("timeline_endpoint_template") or "/api/agent/runs/{run_id}/timeline"
            ),
            result_endpoint_template=str(
                raw.get("result_endpoint_template") or "/api/agent/runs/{run_id}/result"
            ),
            cancel_endpoint_template=str(
                raw.get("cancel_endpoint_template") or "/api/agent/runs/{run_id}:cancel"
            ),
            observability_full_endpoint_template=str(
                raw.get("observability_full_endpoint_template")
                or "/api/agent/runs/{run_id}/observability/full"
            ),
            request_timeout_seconds=float(raw.get("request_timeout_seconds") or 30.0),
            stream_idle_timeout_seconds=float(raw.get("stream_idle_timeout_seconds") or 300.0),
            refresh_seconds=float(raw.get("refresh_seconds") or 0.5),
            max_trace_lines=int(raw.get("max_trace_lines") or 14),
            max_warning_lines=int(raw.get("max_warning_lines") or 4),
            result_preview_chars=int(raw.get("result_preview_chars") or 280),
            create_body=copy.deepcopy(raw.get("create_body") or {}),
            debug_logs=_debug_logs(raw.get("debug")),
            debug_output_root=_debug_output_root(raw.get("debug")),
        )
        cfg.validate()
        return cfg

    def validate(self) -> None:
        missing = [
            name
            for name in ("base_url", "username", "password", "question")
            if not str(getattr(self, name) or "").strip()
        ]
        if missing:
            raise ValueError("missing required config field(s): " + ", ".join(missing))
        if "{run_id}" not in self.stream_endpoint_template:
            raise ValueError("stream_endpoint_template must contain {run_id}")
        if "{run_id}" not in self.status_endpoint_template:
            raise ValueError("status_endpoint_template must contain {run_id}")
        if "{run_id}" not in self.events_endpoint_template:
            raise ValueError("events_endpoint_template must contain {run_id}")
        if "{run_id}" not in self.timeline_endpoint_template:
            raise ValueError("timeline_endpoint_template must contain {run_id}")
        if "{run_id}" not in self.result_endpoint_template:
            raise ValueError("result_endpoint_template must contain {run_id}")
        if "{run_id}" not in self.observability_full_endpoint_template:
            raise ValueError("observability_full_endpoint_template must contain {run_id}")
        if self.refresh_seconds <= 0:
            raise ValueError("refresh_seconds must be > 0")
        if self.debug_logs and not self.debug_output_root.strip():
            raise ValueError("debug.output_root must be non-empty when debug.logs is true")

    def create_request_body(self) -> Dict[str, Any]:
        body = copy.deepcopy(self.create_body)
        body.setdefault("message", self.question)
        return body

    def as_log_dict(self) -> Dict[str, Any]:
        data = asdict(self)
        data["password"] = "<redacted>"
        return data


def _debug_logs(raw: Any) -> bool:
    if not isinstance(raw, dict):
        return False
    return bool(raw.get("logs", False))


def _debug_output_root(raw: Any) -> str:
    if not isinstance(raw, dict):
        return "af_light_client/output"
    return str(raw.get("output_root") or "af_light_client/output")
