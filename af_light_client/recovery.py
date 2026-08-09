from __future__ import annotations

import json
import time
from contextlib import nullcontext
from typing import Any, Callable, Dict, Optional

from .events import RunViewState, is_terminal


class RecoveryFailure(RuntimeError):
    """Recoverable stream repair failure surfaced to the caller with a stable code."""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(f"{code}: {message}")
        self.code = code


class AgentStreamRecovery:
    """D20 SSE/REST cursor and recovery state machine used by ``af_light_client``.

    The cursor is the greatest durable seq actually applied from a server-confirmed
    frame/page. It is never advanced by ``+1`` arithmetic, status event counts, or
    live-only ``durable=false`` events.
    """

    LARGE_RUN_EVENT_LIMIT = 5000
    RETRY_DELAYS_SECONDS = (0.25, 0.5, 1.0)
    HEALTHY_WARN_SECONDS = 10.0
    HEALTHY_DEADLINE_SECONDS = 30.0

    def __init__(
        self,
        client: Any,
        *,
        events_endpoint_template: str,
        status_endpoint_template: str,
        run_id: str,
        state: RunViewState,
        warnings: Any,
        state_lock: Any = None,
        sleep: Callable[[float], None] = time.sleep,
        monotonic: Callable[[], float] = time.monotonic,
    ) -> None:
        self.client = client
        self.events_endpoint_template = events_endpoint_template
        self.status_endpoint_template = status_endpoint_template
        self.run_id = run_id
        self.state = state
        self.warnings = warnings
        self.state_lock = state_lock
        self.sleep = sleep
        self.monotonic = monotonic
        self.confirmed_cursor = 0
        self.latest_server_seq = 0
        self.degraded_large_run = False
        self.reconnect_requested = False
        self.auto_retry_enabled = True
        self._large_run_degraded_once = False
        self._slow_warning_emitted = False

    def ingest_sse(self, event_type: str, data: Any) -> None:
        payload = self._dict_payload(data)
        if payload is None:
            return
        if event_type == "snapshot":
            self._ingest_snapshot(payload)
            return
        if event_type == "agent.event":
            self._ingest_live_event(payload)
            return
        if event_type in {"run.status", "run.done"}:
            self.latest_server_seq = max(self.latest_server_seq, self._to_int(payload.get("lastSeq")))
            self._apply_state(event_type, payload)
            return
        if event_type == "error":
            self._apply_state(event_type, payload)
            if payload.get("code") == "LIVE_REPLAY_BUFFER_OVERFLOW":
                self.repair(target_seq=self.latest_server_seq or None)
                self.reconnect_requested = True
            return
        self._apply_state(event_type, payload)

    def repair(self, *, target_seq: Optional[int] = None, enforce_sla: bool = True) -> None:
        if not self.auto_retry_enabled:
            raise RecoveryFailure("RECOVERY_RETRY_DISABLED", "automatic recovery has already stopped")
        started_at = self.monotonic()
        failures = 0
        while True:
            if enforce_sla:
                self._check_sla(started_at)
            try:
                page = self.client.get_events(
                    self.events_endpoint_template,
                    self.run_id,
                    after_seq=self.confirmed_cursor,
                    limit=500,
                )
            except Exception as exc:
                failures += 1
                if failures > len(self.RETRY_DELAYS_SECONDS):
                    self.auto_retry_enabled = False
                    raise RecoveryFailure("RECOVERY_FAILED", f"REST events failed after 3 retries: {exc}") from exc
                self.sleep(self.RETRY_DELAYS_SECONDS[failures - 1])
                continue

            if enforce_sla:
                self._check_sla(started_at)

            items = page.get("items") if isinstance(page, dict) else None
            items = items if isinstance(items, list) else []
            if items:
                failures = 0
                self._apply_rest_page(items, page)
                if bool(page.get("hasMore")):
                    continue
                if target_seq is None or self.confirmed_cursor >= target_seq:
                    return

            status_last_seq = self._load_status_last_seq()
            expected_seq = max(target_seq or 0, status_last_seq)
            if expected_seq <= self.confirmed_cursor:
                return
            failures += 1
            if failures > len(self.RETRY_DELAYS_SECONDS):
                self.auto_retry_enabled = False
                raise RecoveryFailure(
                    "RECOVERY_FAILED",
                    "REST events remained empty while status reported newer durable events",
                )
            self.sleep(self.RETRY_DELAYS_SECONDS[failures - 1])

    def recover_degraded_large_run_once(self) -> None:
        """Temporarily switch to REST-only without applying the healthy-run 10/30s SLA."""
        if not self.degraded_large_run:
            return
        self.repair(target_seq=self.latest_server_seq or None, enforce_sla=False)
        self.degraded_large_run = False
        self.reconnect_requested = True

    def _ingest_snapshot(self, payload: Dict[str, Any]) -> None:
        self._apply_state("snapshot", payload)
        events = payload.get("events")
        if isinstance(events, list):
            for item in events:
                if isinstance(item, dict) and self._is_durable(item):
                    self.confirmed_cursor = max(self.confirmed_cursor, self._to_int(item.get("seq")))
        self.latest_server_seq = max(self.latest_server_seq, self._to_int(payload.get("lastSeq")))
        event_count = self._to_int(payload.get("eventCount"))
        if event_count > self.LARGE_RUN_EVENT_LIMIT and not self._large_run_degraded_once:
            self._large_run_degraded_once = True
            self.degraded_large_run = True
            self.warnings.add("degraded_large_run: durable events > 5000; temporarily using REST-only repair")

    def _ingest_live_event(self, event: Dict[str, Any]) -> None:
        if not self._is_durable(event):
            self._apply_state("agent.event", event)
            return
        seq = self._to_int(event.get("seq"))
        if seq <= 0 or seq <= self.confirmed_cursor:
            return
        if seq > self.confirmed_cursor + 1:
            self.repair(target_seq=seq)
        if seq <= self.confirmed_cursor:
            return
        self._apply_state("agent.event", event)
        self.confirmed_cursor = seq

    def _apply_rest_page(self, items: list[Any], page: Dict[str, Any]) -> None:
        applied_max = self.confirmed_cursor
        durable_items = sorted(
            (item for item in items if isinstance(item, dict) and self._is_durable(item)),
            key=lambda item: self._to_int(item.get("seq")),
        )
        for item in durable_items:
            seq = self._to_int(item.get("seq"))
            if seq <= self.confirmed_cursor:
                continue
            self._apply_state("agent.event", item)
            applied_max = max(applied_max, seq)
        next_after_seq = self._to_int(page.get("nextAfterSeq"))
        # nextAfterSeq is accepted only when this page also supplied applied durable data.
        if durable_items:
            applied_max = max(applied_max, next_after_seq)
        self.confirmed_cursor = applied_max
        self.latest_server_seq = max(self.latest_server_seq, applied_max)

    def _load_status_last_seq(self) -> int:
        try:
            status = self.client.get_status(self.status_endpoint_template, self.run_id)
        except Exception:
            return self.latest_server_seq
        if not isinstance(status, dict):
            return self.latest_server_seq
        self.latest_server_seq = max(self.latest_server_seq, self._to_int(status.get("lastSeq")))
        if is_terminal(str(status.get("status") or "")):
            self._apply_state("run.status", status)
        return self.latest_server_seq

    def _check_sla(self, started_at: float) -> None:
        elapsed = self.monotonic() - started_at
        if elapsed > self.HEALTHY_DEADLINE_SECONDS:
            self.auto_retry_enabled = False
            raise RecoveryFailure(
                "RECOVERY_SLA_EXCEEDED",
                f"healthy-run repair exceeded {self.HEALTHY_DEADLINE_SECONDS:.0f}s",
            )
        if elapsed > self.HEALTHY_WARN_SECONDS and not self._slow_warning_emitted:
            self._slow_warning_emitted = True
            self.warnings.add("SSE recovery exceeded healthy p95 target (10s)")

    def _apply_state(self, event_type: str, payload: Dict[str, Any]) -> None:
        context = self.state_lock if self.state_lock is not None else nullcontext()
        with context:
            self.state.ingest_sse(event_type, payload)

    @staticmethod
    def _dict_payload(data: Any) -> Optional[Dict[str, Any]]:
        if isinstance(data, str):
            try:
                data = json.loads(data)
            except json.JSONDecodeError:
                return None
        return data if isinstance(data, dict) else None

    @classmethod
    def _is_durable(cls, event: Dict[str, Any]) -> bool:
        durable = event.get("durable")
        if isinstance(durable, bool):
            return durable
        if isinstance(durable, str) and durable.lower() in {"true", "false"}:
            return durable.lower() == "true"
        # Legacy envelopes had no durable field; positive seq was always persisted.
        return cls._to_int(event.get("seq")) >= 1

    @staticmethod
    def _to_int(value: Any) -> int:
        try:
            return int(value or 0)
        except (TypeError, ValueError):
            return 0
