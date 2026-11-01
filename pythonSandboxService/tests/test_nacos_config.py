"""Tests for MethodSpec V5 §7.2 Python Nacos snapshot semantics.

Authoritative texts:
  - 金融MethodSpec-V5-源码实施与Agent分工计划 §7.2 (Python Nacos 快照) and
    §7.1 (wrapper input example shape; L510 静态硬上限只能被 Nacos 调低或调到
    硬上限，不能提高硬上限).
  - Frozen contract §13: 静态硬上限只能被 Nacos 调低，不能被动态配置提高;
    配置加载顺序是应用默认值 → 整份合法动态值 → 代码硬上限缩小；非法动态值
    保留 last-known-good；Python task 快照固定 ``recordChannelMaxRecords``/
    ``recordChannelMaxBytes``/``stdoutMaxBytes``/``stderrMaxBytes`` 和 source
    revision；执行中不得读取更新后的配置；正式数字必须由工作包 C/D 的四段测试
    确认，本协议不编造生产值.

Covered behavior:
  - Application defaults equal the Spec §7.1 example shape values and the
    static hard ceilings exist (currently pinned at the defaults).
  - Whole-object validation: an invalid payload (bad JSON, non-object, wrong
    type, negative value) keeps the complete last-known-good snapshot; no
    partial application.
  - Values above the static hard ceilings are clamped DOWN with a logged
    event; values below are accepted as-is.
  - ``output_limits_snapshot()`` returns exactly the five contract keys and is
    isolated from later dynamic updates.
  - ``sourceRevision`` is ``static-default`` initially and changes with the
    applied payload (deterministically).
  - ``containerMaxConcurrency`` hot-reload regression (direct update method
    and whole-payload path, invalid values ignored) under the UNCONDITIONAL
    cmc==1 safety invariant (codex c72db8f6 item 4 / 56d28076, refined by
    D15 §4.2 on 2026-08-10): cmc>1 is fail-fast rejected at construction,
    in single-field hot updates and in whole payloads, for EVERY
    configuration. D15 §4.2 made the per-task AF_TASK_* bootstrap
    task-local (it now travels in wrapper-input.json instead of the
    shared global sitecustomize.py); the cmc==1 rule still holds, driven
    by the dynamic-install venv mutation race (S3B-04 governs lifting).
  - Lane -> main Beta data-id fallback chain for the Nacos listener
    (2026-09-10 beta needs, 改动二): a non-blank AF_LANE_TRAFFIC_SCOPE_ID
    makes the candidate chain ["{lane}.{data_id}", data_id]; the first
    non-blank candidate wins; the whole chain empty applies nothing and
    logs an error; a config watcher on ANY chain member re-resolves the
    full chain (pushed bodies are not trusted), so deleting the lane item
    falls back to the main Beta data-id automatically. No group fallback:
    the group comes from AF_CONFIG_NACOS_GROUP only.

Constructed directly from a ``SandboxConfig`` instance; stdlib unittest only,
no nacos SDK or network required (the listener wiring tests patch
``_get_nacos_client`` with an in-memory fake client class).

Run: ``cd pythonSandboxService && python3 -m unittest tests.test_nacos_config -v``
"""

from __future__ import annotations

import importlib
import json
import os
import threading
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

# Spec §7.1 wrapper-input example shape values (numbers only denote shape;
# production numbers await the four-stage tests, contract §13).
SHAPE_OUTPUT_LIMITS = {
    "stdoutMaxBytes": 1048576,
    "stderrMaxBytes": 262144,
    "recordChannelMaxBytes": 262144,
    "recordChannelMaxRecords": 128,
}

SNAPSHOT_KEYS = {"stdoutMaxBytes", "stderrMaxBytes", "recordChannelMaxBytes",
                 "recordChannelMaxRecords", "sourceRevision"}


class NacosConfigTest(unittest.TestCase):
    """Behavioral contract for ``app.nacos_config`` (Spec §7.2, contract §13)."""

    def setUp(self) -> None:
        # Import inside setUp, mirroring the convention of the other
        # work-package C test modules.
        self.config_module = importlib.import_module("app.config")
        self.nacos_module = importlib.import_module("app.nacos_config")

    # ------------------------------------------------------------------
    # helpers
    # ------------------------------------------------------------------

    def _make_base_config(self, **overrides):
        kwargs = dict(
            data_dir=Path("data/agent_datasets"),
            max_concurrency=2,
            execution_timeout_seconds=5.0,
            memory_limit="512m",
            memswap_limit="512m",
            docker_backend="docker",
            workdir="/sandbox",
            log_level="INFO",
            sandbox_image="alphafrog-sandbox-runtime:latest",
            skip_environment_setup=True,
            preinstalled_libraries=frozenset(),
            container_max_concurrency=1,
            pool_enabled=False,
            pool_min_size=2,
            pool_max_size=2,
            pool_acquire_timeout_seconds=30.0,
            pool_idle_timeout_seconds=None,
            pool_max_container_uses=None,
            workspace_root="/sandbox/runs",
            compat_input_path_enabled=True,
        )
        kwargs.update(overrides)
        return self.config_module.SandboxConfig(**kwargs)

    def _make_dynamic(self, **overrides):
        return self.nacos_module.DynamicSandboxConfig(self._make_base_config(**overrides))

    def _assert_last_known_good(self, dyn, expected_values, expected_revision) -> None:
        snapshot = dyn.output_limits_snapshot()
        for key, value in expected_values.items():
            self.assertEqual(snapshot[key], value, key)
        self.assertEqual(snapshot["sourceRevision"], expected_revision)
        self.assertEqual(dyn.source_revision, expected_revision)


class DefaultsAndCeilingsTest(NacosConfigTest):
    pass
class SnapshotDefaultsTest(NacosConfigTest):
    pass
class WholeObjectValidationTest(NacosConfigTest):
    pass
class HardCeilingClampTest(NacosConfigTest):
    pass
class SnapshotIsolationTest(NacosConfigTest):
    pass
class SourceRevisionTest(NacosConfigTest):
    pass
class ContainerMaxConcurrencyRegressionTest(NacosConfigTest):
    pass
class UnconditionalConcurrencyInvariantTest(NacosConfigTest):
    """Unconditional cmc==1 safety invariant (codex c72db8f6 item 4 / 56d28076),
    refined by D15 §4.2 (2026-08-10).

    D15 §4.2 made the per-task AF_TASK_* bootstrap task-local: it now travels
    inside the wrapper-input.json (taskWorkspace + taskEnvironment) and the
    wrapper injects it via Popen(env=...); the legacy per-task write of the
    GLOBAL /sandbox/sitecustomize.py is gone. The cmc==1 rule STILL holds,
    now driven solely by the dynamic-install venv mutation race
    (PoolWorker.execution_environment captured once and never refreshed).
    Lifting cmc>1 is gated by S3B-04 and remains out of scope.

    The base config fails fast at construction; a violating single-field hot
    update and a violating whole payload are both rejected, keeping the
    last-known-good snapshot wholesale (an invalid hot update never changes
    the current value or the source revision). All cases hold regardless of
    ``skip_environment_setup``.
    """

class AtomicReplacementTest(NacosConfigTest):
    pass
class ExampleConfigFileTest(NacosConfigTest):
    pass
class FakeNacosConfigClient:
    """In-memory stand-in for the nacos SDK client used by the listener.

    ``get_config`` serves a plain dict and records every (data_id, group)
    call so tests can assert the chain resolution order. ``add_config_watcher``
    records (data_id, group, callback) triples so tests can assert
    per-candidate watcher registration and fire pushes by hand via ``fire``.
    No nacos SDK import, no network.
    """

    def __init__(self, contents=None) -> None:
        self.contents = dict(contents or {})
        self.get_config_calls = []
        self.watchers = []  # list of (data_id, group, callback)

    def get_config(self, data_id, group):
        self.get_config_calls.append((data_id, group))
        return self.contents.get(data_id)

    def add_config_watcher(self, data_id, group, callback) -> None:
        self.watchers.append((data_id, group, callback))

    def delete(self, data_id) -> None:
        self.contents.pop(data_id, None)

    def fire(self, data_id, raw) -> None:
        """Invoke the watcher registered for data_id like the SDK would push."""
        registered = [cb for d, _, cb in self.watchers if d == data_id]
        assert registered, f"no watcher registered for {data_id}"
        registered[0](SimpleNamespace(raw=raw))


class CandidateDataIdsTest(NacosConfigTest):
    """Chain construction: lane prefix first, plain data-id (main Beta) last."""

    DATA_ID = "python-sandbox.json"

class ResolveConfigChainTest(NacosConfigTest):
    """Pure resolver: order, first non-blank wins, empty-chain result."""

    GROUP = "alphafrog-beta-config"
    LANE_ID = "lane-demo.python-sandbox.json"
    MAIN_ID = "python-sandbox.json"

class ApplyResolvedChainContentTest(NacosConfigTest):
    """Resolve-and-apply helper against a real DynamicSandboxConfig.

    Scenarios from the 2026-09-10 beta needs (改动二):
      (a) no lane, main has content -> main applied;
      (b) lane set, both present -> lane applied (order asserted);
      (c) whole chain empty -> nothing applied + ERROR log.
    """

    GROUP = "alphafrog-beta-config"
    LANE_ID = "lane-demo.python-sandbox.json"
    MAIN_ID = "python-sandbox.json"

class ChainConfigWatcherTest(NacosConfigTest):
    """Watcher callback: never trust the push, re-resolve the full chain.

    Scenario (d) from the 2026-09-10 beta needs (改动二): the watcher fires
    after the lane item was deleted in Nacos, so re-resolution only finds
    the main Beta data-id -> main content is applied.
    """

    GROUP = "alphafrog-beta-config"
    LANE_ID = "lane-demo.python-sandbox.json"
    MAIN_ID = "python-sandbox.json"

    def _make_watcher(self, dyn, client):
        return self.nacos_module._make_chain_config_watcher(
            dyn, client.get_config, [self.LANE_ID, self.MAIN_ID], self.GROUP
        )

class StartNacosListenerChainTest(NacosConfigTest):
    """Listener wiring end to end (SDK patched out, no network, no sleep).

    ``_get_nacos_client`` is patched to return a factory that builds an
    in-memory FakeNacosConfigClient; the fake's add_config_watcher sets an
    Event so the test synchronizes with the listener thread without sleeping.
    """

    GROUP = "alphafrog-beta-config"
    LANE_ID = "lane-demo.python-sandbox.json"
    MAIN_ID = "python-sandbox.json"

    def _start_listener(self, dyn, contents, env, expected_watchers):
        """Run start_nacos_listener with a fake client; wait for watcher setup.

        The fake client's add_config_watcher sets an Event once the expected
        number of chain watchers is registered, so the test synchronizes
        with the listener thread without sleeping.
        """
        created = {}
        registered = threading.Event()

        def fake_client_factory(**kwargs):
            client = FakeNacosConfigClient(contents)
            created["client"] = client

            class _SignalingClient:
                # The listener only uses get_config / add_config_watcher.
                def get_config(self, data_id, group):
                    return client.get_config(data_id, group)

                def add_config_watcher(self, data_id, group, cb):
                    client.add_config_watcher(data_id, group, cb)
                    if len(client.watchers) >= expected_watchers:
                        registered.set()

            return _SignalingClient()

        with mock.patch.dict(os.environ, env), \
                mock.patch.object(
                    self.nacos_module, "_get_nacos_client",
                    return_value=fake_client_factory,
                ), \
                self.assertLogs("app.nacos_config", level="INFO"):
            self.nacos_module.start_nacos_listener(self._make_base_config(), dyn)
            self.assertTrue(
                registered.wait(timeout=5),
                "listener thread did not register all config watchers in time",
            )
        return created["client"]

if __name__ == "__main__":
    unittest.main()
