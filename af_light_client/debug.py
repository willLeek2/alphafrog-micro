from __future__ import annotations

import json
import re
import threading
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, Optional

from .config import LightClientConfig, TuiSnapshotDebugConfig
from .http_client import SseFrame


SENSITIVE_KEY_PARTS = (
    "password",
    "token",
    "authorization",
    "cookie",
    "secret",
    "apikey",
    "api_key",
)


class DebugRunLogger:
    def __init__(self, *, enabled: bool, output_root: str, now: Optional[datetime] = None) -> None:
        self.enabled = enabled
        self.output_root = Path(output_root)
        self.run_dir: Optional[Path] = None
        self.started_at = now or datetime.now()
        if not enabled:
            return
        stamp = self.started_at.strftime("%Y%m%d-%H%M%S")
        self.run_dir = self.output_root / stamp
        self.run_dir.mkdir(parents=True, exist_ok=True)

    @classmethod
    def from_config(cls, cfg: LightClientConfig) -> "DebugRunLogger":
        return cls(enabled=cfg.debug_logs, output_root=cfg.debug_output_root)

    def write_json(self, name: str, data: Any) -> None:
        if not self.enabled or self.run_dir is None:
            return
        path = self.run_dir / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(
            json.dumps(redact(data), ensure_ascii=False, indent=2, sort_keys=True),
            encoding="utf-8",
        )

    def write_text(self, name: str, text: str) -> None:
        if not self.enabled or self.run_dir is None:
            return
        path = self.run_dir / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(_redact_string(text), encoding="utf-8")

    def append_jsonl(self, name: str, data: Any) -> None:
        if not self.enabled or self.run_dir is None:
            return
        path = self.run_dir / name
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("a", encoding="utf-8") as fh:
            fh.write(json.dumps(redact(data), ensure_ascii=False, sort_keys=True) + "\n")

    def log_sse_frame(self, frame: SseFrame, parsed: Any) -> None:
        payload = {
            "ts": datetime.now().isoformat(timespec="seconds"),
            "event_type": frame.event_type,
            "event_id": frame.event_id,
            "data": parsed,
        }
        self.append_jsonl("sse_events.jsonl", payload)

    def log_warning_snapshot(self, warnings: list[str]) -> None:
        self.write_json("warnings.json", {"items": warnings})
        for warning in warnings:
            self.append_jsonl(
                "warnings.jsonl",
                {"ts": datetime.now().isoformat(timespec="seconds"), "message": warning},
            )

    def list_files(self) -> list[str]:
        if not self.enabled or self.run_dir is None:
            return []
        return sorted(str(path.relative_to(self.run_dir)) for path in self.run_dir.rglob("*") if path.is_file())

    @property
    def output_dir(self) -> Optional[Path]:
        return self.run_dir


class TuiBatchSnapshotWriter:
    def __init__(
        self,
        *,
        enabled: bool,
        output_root: str,
        started_at: datetime,
        config: TuiSnapshotDebugConfig,
    ) -> None:
        self.enabled = enabled
        self.interval_ms = config.interval_ms
        self.batch_interval_ms = config.batch_interval_ms
        self._next_snapshot_at: Optional[float] = None
        self._current_batch_started_at: Optional[float] = None
        self._batch_index = 0
        self._batch_path: Optional[Path] = None
        self._lock = threading.Lock()
        self.output_dir: Optional[Path] = None
        if not enabled:
            return
        stamp = started_at.strftime("%Y%m%d-%H%M%S")
        self.output_dir = Path(output_root) / stamp / "debug"

    @classmethod
    def from_config(cls, cfg: LightClientConfig, *, started_at: datetime) -> "TuiBatchSnapshotWriter":
        snapshots = cfg.debug_tui_snapshots
        return cls(
            enabled=snapshots.enabled,
            output_root=cfg.debug_output_root,
            started_at=started_at,
            config=snapshots,
        )

    def write_if_due(
        self,
        snapshot_text: str,
        *,
        monotonic_now: float,
        wall_now: Optional[datetime] = None,
        force: bool = False,
    ) -> None:
        if not self.enabled or self.output_dir is None:
            return
        with self._lock:
            if not force and self._next_snapshot_at is not None and monotonic_now < self._next_snapshot_at:
                return
            self._ensure_batch(monotonic_now)
            if self._batch_path is None:
                return
            wall = wall_now or datetime.now()
            with self._batch_path.open("a", encoding="utf-8") as fh:
                fh.write(f"time {wall.strftime('%Y%m%d-%H%M%S')}\n")
                fh.write("snapshot:\n\n")
                fh.write(_redact_string(snapshot_text.rstrip("\n")))
                fh.write("\n\n---\n")
            self._next_snapshot_at = monotonic_now + (self.interval_ms / 1000.0)

    def _ensure_batch(self, monotonic_now: float) -> None:
        if (
            self._batch_path is not None
            and self._current_batch_started_at is not None
            and monotonic_now - self._current_batch_started_at < self.batch_interval_ms / 1000.0
        ):
            return
        self._batch_index += 1
        self._current_batch_started_at = monotonic_now
        if self.output_dir is not None:
            self.output_dir.mkdir(parents=True, exist_ok=True)
        self._batch_path = self.output_dir / f"tui_batch{self._batch_index:03d}.txt"


def redact(value: Any) -> Any:
    if isinstance(value, dict):
        out: Dict[str, Any] = {}
        for key, item in value.items():
            key_text = str(key)
            if _is_sensitive_key(key_text):
                out[key_text] = "<redacted>"
            else:
                out[key_text] = redact(item)
        return out
    if isinstance(value, list):
        return [redact(item) for item in value]
    if isinstance(value, tuple):
        return [redact(item) for item in value]
    if isinstance(value, str):
        return _redact_string(value)
    return value


def _is_sensitive_key(key: str) -> bool:
    normalized = key.replace("-", "_").lower()
    return any(part in normalized for part in SENSITIVE_KEY_PARTS)


def _redact_string(value: str) -> str:
    text = re.sub(r"(?i)bearer\s+[A-Za-z0-9._~+/=-]+", "Bearer <redacted>", value)
    text = re.sub(r"(?i)(access_token=)[^;&\s]+", r"\1<redacted>", text)
    text = re.sub(r"(?i)([?&]token=)[^&#\s]+", r"\1<redacted>", text)
    return text
