from __future__ import annotations

import json
import logging
import os
import threading
from dataclasses import replace
from typing import Callable

from .config import SandboxConfig

logger = logging.getLogger(__name__)


class DynamicSandboxConfig:
    """Mutable holder for config values that may be updated at runtime.

    The base `SandboxConfig` dataclass is frozen and loaded once at startup.
    Values that need Nacos hot-reload are mirrored here; new container workers
    pick up the current value when they are created.
    """

    def __init__(self, config: SandboxConfig) -> None:
        self._lock = threading.Lock()
        self._container_max_concurrency = config.container_max_concurrency

    @property
    def container_max_concurrency(self) -> int:
        with self._lock:
            return self._container_max_concurrency

    def update_container_max_concurrency(self, value: int) -> None:
        if value < 1:
            logger.warning("Ignoring invalid container_max_concurrency=%s (must be >= 1)", value)
            return
        with self._lock:
            old = self._container_max_concurrency
            self._container_max_concurrency = value
        logger.info("DYNAMIC_CONFIG container_max_concurrency %s -> %s", old, value)

    def apply_to(self, config: SandboxConfig) -> SandboxConfig:
        """Return a copy of `config` with current dynamic values applied."""
        current_cmc = self.container_max_concurrency
        if current_cmc == config.container_max_concurrency:
            return config
        new_config = replace(config, container_max_concurrency=current_cmc)
        # container_max_concurrency > 1 is incompatible with the global input symlink.
        if current_cmc > 1 and new_config.compat_input_path_enabled:
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

    Expected config content is JSON, e.g. {"containerMaxConcurrency": 5}.
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
        if not content or not content.strip():
            return
        try:
            payload = json.loads(content)
        except json.JSONDecodeError as exc:
            logger.warning("Nacos config content is not valid JSON: %s", exc)
            return
        if not isinstance(payload, dict):
            logger.warning("Nacos config content is not a JSON object")
            return
        raw = payload.get("containerMaxConcurrency")
        if raw is not None:
            try:
                dynamic_config.update_container_max_concurrency(int(raw))
            except (TypeError, ValueError) as exc:
                logger.warning("Invalid containerMaxConcurrency in Nacos config: %s", exc)

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
