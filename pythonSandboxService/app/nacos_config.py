from __future__ import annotations

import hashlib
import json
import logging
import os
import threading
from dataclasses import dataclass, replace
from typing import Callable, Mapping

from .config import HARD_OUTPUT_LIMIT_CEILINGS, OUTPUT_LIMIT_KEYS, SandboxConfig

logger = logging.getLogger(__name__)


# Verbatim dynamic keys accepted in python-sandbox.json / Nacos payloads
# (Spec §7.2, frozen contract §13).
KEY_CONTAINER_MAX_CONCURRENCY = "containerMaxConcurrency"
KNOWN_DYNAMIC_KEYS = frozenset({KEY_CONTAINER_MAX_CONCURRENCY, *OUTPUT_LIMIT_KEYS})

# Source revision of the effective dynamic config before any Nacos payload has
# been applied (contract §13: the task snapshot fixes the source revision).
STATIC_DEFAULT_SOURCE_REVISION = "static-default"

# JSON key -> _DynamicConfigSnapshot field for the four output limits.
_LIMIT_FIELD_BY_KEY: dict[str, str] = {
    "stdoutMaxBytes": "stdout_max_bytes",
    "stderrMaxBytes": "stderr_max_bytes",
    "recordChannelMaxBytes": "record_channel_max_bytes",
    "recordChannelMaxRecords": "record_channel_max_records",
}


@dataclass(frozen=True)
class _DynamicConfigSnapshot:
    """Complete, immutable last-known-good dynamic config state.

    One instance holds every dynamically tunable value plus the source
    revision. Updates build a new instance and swap it atomically under
    ``DynamicSandboxConfig._lock``, so readers never observe a half-applied
    config. Instances are never mutated after creation; handed-out snapshots
    (``output_limits_snapshot()``) are fresh plain dicts of immutable values.
    """

    container_max_concurrency: int
    stdout_max_bytes: int
    stderr_max_bytes: int
    record_channel_max_bytes: int
    record_channel_max_records: int
    source_revision: str


def _is_plain_int(value: object) -> bool:
    # bool is a subclass of int in Python; true/false are not valid limits.
    return isinstance(value, int) and not isinstance(value, bool)


def _derive_source_revision(payload: Mapping[str, object]) -> str:
    """Revision of an applied payload: short sha256 of its canonical JSON.

    Derived from the validated payload as published (not from the clamped
    effective values), so the same Nacos content always maps to the same
    revision; clamping is this service's code-side policy, not part of the
    published content identity.
    """
    canonical = json.dumps(payload, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    digest = hashlib.sha256(canonical.encode("utf-8")).hexdigest()[:12]
    return f"nacos-sha256:{digest}"


class DynamicSandboxConfig:
    """Mutable holder for config values that may be updated at runtime.

    The base ``SandboxConfig`` dataclass is frozen and loaded once at startup.
    Values that need Nacos hot-reload are mirrored here; new container workers
    pick up the current value when they are created.

    Whole-object semantics (Spec §7.2, contract §13):
      - A complete, immutable last-known-good snapshot is always retained.
      - An incoming payload is applied only if the ENTIRE payload validates
        (JSON object; every present known key has a valid type/range). Any
        invalid payload keeps the last-known-good wholesale.
      - Values above the static hard ceilings (``HARD_OUTPUT_LIMIT_CEILINGS``)
        are clamped DOWN to the ceiling and an event is logged; dynamic config
        can never raise a limit above its ceiling.
      - Config load order: application defaults -> whole valid dynamic values
        -> shrink to code hard ceilings.
      - Tasks freeze ``output_limits_snapshot()`` at creation time and must
        not re-read this hot config during execution.
      - Concurrency safety invariant (UNCONDITIONAL, codex c72db8f6 item 4 /
        56d28076): ``containerMaxConcurrency`` must be 1 for EVERY
        configuration (dynamic-install and preinstalled alike), because the
        runner's task bootstrap still writes/deletes a task-specific GLOBAL
        /sandbox/sitecustomize.py shared across concurrent tasks in the same
        container, which is not yet task-local. A violating base config fails
        startup (constructor raises); a violating hot update is rejected
        keeping the last-known-good value.
    """

    def __init__(self, config: SandboxConfig) -> None:
        self._lock = threading.Lock()
        # Unconditional concurrency invariant (codex c72db8f6 item 4 /
        # 56d28076): the sandbox runner's task bootstrap still writes/deletes
        # a task-specific GLOBAL /sandbox/sitecustomize.py shared across
        # concurrent tasks in the same container, for ALL configurations (not
        # only dynamic install). codex 56d28076 wording: 现有 task-specific
        # 全局 sitecustomize.py 写/删尚未 task-local 化，因此选择全配置
        # fail-fast 的安全限制. Container concurrency must therefore stay
        # exactly 1 for EVERY configuration: if this layer accepted a hot
        # update to >1 in preinstalled mode, new worker creation would fail
        # afterwards, breaking last-known-good semantics. The base config is
        # fail-fast rejected here at construction; hot updates are rejected
        # by update_container_max_concurrency/apply_dynamic_payload below,
        # keeping the last-known-good value instead of breaking later at
        # worker creation.
        #
        # Restoration path: once the wrapper AF_TASK_* bootstrap becomes
        # subprocess task-local env with no shared sitecustomize writes,
        # preinstalled-mode (skip_environment_setup=true) cmc>1 may be
        # restored behind concurrency-isolation tests.
        if config.container_max_concurrency > 1:
            raise ValueError(
                "container_max_concurrency must be 1 for ALL configurations: "
                "the task-specific global /sandbox/sitecustomize.py "
                "write/delete is not yet task-local (codex 56d28076)"
            )
        self._snapshot = self._initial_snapshot(config)

    # ------------------------------------------------------------------
    # construction
    # ------------------------------------------------------------------

    @staticmethod
    def _initial_snapshot(config: SandboxConfig) -> _DynamicConfigSnapshot:
        values: dict[str, int] = {}
        for key, field in _LIMIT_FIELD_BY_KEY.items():
            value = getattr(config, field)
            ceiling = HARD_OUTPUT_LIMIT_CEILINGS[key]
            if not _is_plain_int(value) or value < 0:
                raise ValueError(f"Invalid {field}={value!r}: must be an int >= 0.")
            if value > ceiling:
                logger.warning(
                    "DYNAMIC_CONFIG_CLAMPED %s: application default %s -> ceiling %s "
                    "(static hard ceilings can only shrink values)",
                    key,
                    value,
                    ceiling,
                )
                value = ceiling
            values[field] = value
        return _DynamicConfigSnapshot(
            container_max_concurrency=config.container_max_concurrency,
            source_revision=STATIC_DEFAULT_SOURCE_REVISION,
            **values,
        )

    # ------------------------------------------------------------------
    # readers
    # ------------------------------------------------------------------

    @property
    def container_max_concurrency(self) -> int:
        with self._lock:
            return self._snapshot.container_max_concurrency

    @property
    def source_revision(self) -> str:
        """Source revision of the currently effective dynamic config."""
        with self._lock:
            return self._snapshot.source_revision

    def output_limits_snapshot(self) -> dict[str, object]:
        """Freeze the four output limits plus the source revision.

        Returns a fresh dict with exactly the contract §13 keys
        ``stdoutMaxBytes`` / ``stderrMaxBytes`` / ``recordChannelMaxBytes`` /
        ``recordChannelMaxRecords`` / ``sourceRevision`` at their current
        effective (default -> dynamic -> clamp-resolved) values. The dict is a
        copy: later Nacos updates never mutate previously returned snapshots.
        ``main.py`` freezes this into ``Task.effective_output_limits`` at
        create_task; idempotent re-create returns the original snapshot.
        """
        with self._lock:
            snapshot = self._snapshot
        return {
            "stdoutMaxBytes": snapshot.stdout_max_bytes,
            "stderrMaxBytes": snapshot.stderr_max_bytes,
            "recordChannelMaxBytes": snapshot.record_channel_max_bytes,
            "recordChannelMaxRecords": snapshot.record_channel_max_records,
            "sourceRevision": snapshot.source_revision,
        }

    # ------------------------------------------------------------------
    # writers
    # ------------------------------------------------------------------

    def update_container_max_concurrency(self, value: int) -> None:
        if not _is_plain_int(value) or value < 1:
            logger.warning("Ignoring invalid container_max_concurrency=%s (must be >= 1)", value)
            return
        with self._lock:
            old = self._snapshot.container_max_concurrency
            if value > 1:
                # Unconditional safety invariant (codex c72db8f6 item 4 /
                # 56d28076): the task-specific global /sandbox/sitecustomize.py
                # write/delete is not yet task-local, so cmc>1 is rejected for
                # ALL configs. Fail-fast reject the hot update and KEEP the
                # safe value: a later worker creation error would break new
                # workers, whereas rejecting here leaves the running config
                # intact (last-known-good).
                logger.warning(
                    "DYNAMIC_CONFIG_REJECTED container_max_concurrency=%s: the "
                    "task-specific global sitecustomize.py bootstrap is not yet "
                    "task-local, so every config is restricted to concurrency 1; "
                    "keeping %s",
                    value,
                    old,
                )
                return
            self._snapshot = replace(self._snapshot, container_max_concurrency=value)
        logger.info("DYNAMIC_CONFIG container_max_concurrency %s -> %s", old, value)

    def apply_dynamic_content(self, content: str) -> bool:
        """Parse Nacos ``python-sandbox.json`` content and whole-object apply.

        Returns True only if the entire payload validated and replaced the
        last-known-good snapshot. Invalid JSON / non-object content keeps the
        last-known-good wholesale and logs a warning.
        """
        if content is None or not content.strip():
            return False
        try:
            payload = json.loads(content)
        except ValueError as exc:  # includes json.JSONDecodeError
            logger.warning(
                "DYNAMIC_CONFIG_REJECTED content is not valid JSON (%s); "
                "keeping last-known-good (sourceRevision=%s)",
                exc,
                self.source_revision,
            )
            return False
        return self.apply_dynamic_payload(payload)

    def apply_dynamic_payload(self, payload: object) -> bool:
        """Whole-object validation + atomic replacement (contract §13).

        The payload is applied only if it is a JSON object and EVERY present
        known key validates; otherwise the last-known-good snapshot is kept
        wholesale (no partial application). Unknown keys are ignored with a
        warning (forward compatibility; the data id is service-specific).
        Present output-limit values above the static hard ceilings are clamped
        down to the ceiling and each clamp is logged. An empty payload (no
        known keys) is a no-op that keeps the current revision.
        """
        warnings: list[str] = []
        infos: list[str] = []
        if not isinstance(payload, dict):
            logger.warning(
                "DYNAMIC_CONFIG_REJECTED payload is not a JSON object (got %s); "
                "keeping last-known-good (sourceRevision=%s)",
                type(payload).__name__,
                self.source_revision,
            )
            return False

        unknown_keys = sorted(set(payload) - KNOWN_DYNAMIC_KEYS)
        if unknown_keys:
            warnings.append(f"DYNAMIC_CONFIG ignored unknown keys: {unknown_keys}")

        # Phase 1: validate every present known key. Any failure rejects the
        # WHOLE payload (last-known-good retained, nothing partially applied).
        errors: list[str] = []
        if KEY_CONTAINER_MAX_CONCURRENCY in payload:
            raw = payload[KEY_CONTAINER_MAX_CONCURRENCY]
            if not _is_plain_int(raw) or raw < 1:
                errors.append(f"containerMaxConcurrency={raw!r} must be an int >= 1")
            elif raw > 1:
                # Unconditional safety invariant (codex c72db8f6 item 4 /
                # 56d28076): whole-object rejection keeps the last-known-good
                # concurrency rather than letting a hot payload raise it above
                # the only safe value (1) for ANY configuration, until the
                # task-specific global sitecustomize.py bootstrap is task-local.
                errors.append(
                    f"containerMaxConcurrency={raw!r} must be 1 for all "
                    "configurations until the task-specific global "
                    "sitecustomize.py bootstrap is task-local"
                )
        for key in OUTPUT_LIMIT_KEYS:
            if key in payload:
                raw = payload[key]
                if not _is_plain_int(raw) or raw < 0:
                    errors.append(f"{key}={raw!r} must be an int >= 0")
        if errors:
            logger.warning(
                "DYNAMIC_CONFIG_REJECTED whole payload, %d invalid key(s): %s; "
                "keeping last-known-good (sourceRevision=%s)",
                len(errors),
                "; ".join(errors),
                self.source_revision,
            )
            for message in warnings:
                logger.warning("%s", message)
            return False

        # Phase 2: merge over last-known-good, clamp down to hard ceilings,
        # swap atomically under the lock.
        changes: dict[str, int] = {}
        clamps: list[tuple[str, int, int, int]] = []  # (key, payload, previous, ceiling)
        with self._lock:
            base = self._snapshot
            if KEY_CONTAINER_MAX_CONCURRENCY in payload:
                changes["container_max_concurrency"] = payload[KEY_CONTAINER_MAX_CONCURRENCY]
            for key, field in _LIMIT_FIELD_BY_KEY.items():
                if key in payload:
                    value = payload[key]
                    ceiling = HARD_OUTPUT_LIMIT_CEILINGS[key]
                    if value > ceiling:
                        clamps.append((key, value, getattr(base, field), ceiling))
                        value = ceiling
                    changes[field] = value
            if not changes:
                # No known keys present (empty or unknown-only payload): keep
                # the last-known-good snapshot including its revision.
                infos.append(
                    "DYNAMIC_CONFIG payload has no known keys; keeping last-known-good "
                    f"(sourceRevision={base.source_revision})"
                )
                new_snapshot = base
            else:
                new_snapshot = replace(
                    base, source_revision=_derive_source_revision(payload), **changes
                )
                self._snapshot = new_snapshot

        for message in warnings:
            logger.warning("%s", message)
        for key, raw_value, previous, ceiling in clamps:
            logger.warning(
                "DYNAMIC_CONFIG_CLAMPED %s: payload value %s -> %s (static ceiling; previous effective %s)",
                key,
                raw_value,
                ceiling,
                previous,
            )
        for message in infos:
            logger.info("%s", message)
        if changes:
            if "container_max_concurrency" in changes and new_snapshot.container_max_concurrency != base.container_max_concurrency:
                logger.info(
                    "DYNAMIC_CONFIG container_max_concurrency %s -> %s",
                    base.container_max_concurrency,
                    new_snapshot.container_max_concurrency,
                )
            logger.info(
                "DYNAMIC_CONFIG_APPLIED whole payload; sourceRevision %s -> %s",
                base.source_revision,
                new_snapshot.source_revision,
            )
        return bool(changes)

    def apply_to(self, config: SandboxConfig) -> SandboxConfig:
        """Return a copy of `config` with current dynamic values applied."""
        with self._lock:
            snapshot = self._snapshot
        new_config = config
        if snapshot.container_max_concurrency != config.container_max_concurrency:
            new_config = replace(new_config, container_max_concurrency=snapshot.container_max_concurrency)
        for key, field in _LIMIT_FIELD_BY_KEY.items():
            if getattr(new_config, field) != getattr(snapshot, field):
                new_config = replace(new_config, **{field: getattr(snapshot, field)})
        # container_max_concurrency > 1 is incompatible with the global input symlink.
        if new_config.container_max_concurrency > 1 and new_config.compat_input_path_enabled:
            new_config = replace(new_config, compat_input_path_enabled=False)
        return new_config


_NacosClient = Callable[..., object] | None


def _get_nacos_client() -> _NacosClient:
    try:
        from nacos import NacosClient

        return NacosClient
    except Exception as exc:
        logger.warning("Nacos SDK not available: %s", exc)
        return None


def start_nacos_listener(
    base_config: SandboxConfig,
    dynamic_config: DynamicSandboxConfig,
) -> None:
    """Start a background Nacos config listener if Nacos is configured.

    Environment variables (mirroring the Java services):
      - AF_CONFIG_NACOS_ENABLED: "true" to enable
      - NACOS_ADDRESS: host or host:port (default "nacos:8848")
      - NACOS_USER / NACOS_PASSWORD
      - AF_CONFIG_NACOS_NAMESPACE: namespace id (default "")
      - AF_CONFIG_NACOS_GROUP: group (default "alphafrog-config")
      - AF_CONFIG_NACOS_DATA_ID: data id (default "python-sandbox.json")

    Expected config content is a single JSON object, e.g.
    {"containerMaxConcurrency": 1, "stdoutMaxBytes": 1048576,
     "stderrMaxBytes": 262144, "recordChannelMaxBytes": 262144,
     "recordChannelMaxRecords": 128}. The whole object must validate before
    any of it is applied (contract §13 whole-object semantics).
    """
    if os.getenv("AF_CONFIG_NACOS_ENABLED", "").lower() != "true":
        logger.info("Nacos config listener disabled (AF_CONFIG_NACOS_ENABLED != true)")
        return

    NacosClient = _get_nacos_client()
    if NacosClient is None:
        logger.warning("Nacos listener not started: nacos-sdk-python unavailable")
        return

    address = os.getenv("NACOS_ADDRESS", "nacos:8848")
    if ":" not in address:
        address = f"{address}:8848"
    server_addresses = f"http://{address}"

    user = os.getenv("NACOS_USER", "nacos")
    password = os.getenv("NACOS_PASSWORD", "nacos")
    namespace = os.getenv("AF_CONFIG_NACOS_NAMESPACE", "")
    group = os.getenv("AF_CONFIG_NACOS_GROUP", "alphafrog-config")
    data_id = os.getenv("AF_CONFIG_NACOS_DATA_ID", "python-sandbox.json")

    def _parse_content(content: str) -> None:
        # Whole-object parse/validate/apply lives in DynamicSandboxConfig so
        # it is testable without the Nacos SDK or network.
        dynamic_config.apply_dynamic_content(content)

    def _on_config_change(config_response: object) -> None:
        content = getattr(config_response, "raw", None)
        if content is None:
            content = str(config_response)
        _parse_content(content)

    def _listen() -> None:
        try:
            client = NacosClient(
                server_addresses=server_addresses,
                namespace=namespace,
                username=user,
                password=password,
            )
            # Fetch initial config.
            initial = client.get_config(data_id, group)
            if initial:
                _parse_content(initial)
            # Add listener for subsequent changes.
            client.add_config_watcher(data_id, group, _on_config_change)
            logger.info(
                "NACOS_LISTENER_STARTED server=%s data_id=%s group=%s namespace=%s",
                server_addresses,
                data_id,
                group,
                namespace,
            )
        except Exception:
            logger.exception("Nacos listener failed to start")

    thread = threading.Thread(target=_listen, name="sandbox-nacos-config-listener", daemon=True)
    thread.start()
