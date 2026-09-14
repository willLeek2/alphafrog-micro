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
    def test_default_limits_equal_spec_shape_values(self) -> None:
        self.assertEqual(self.config_module.DEFAULT_OUTPUT_LIMITS, SHAPE_OUTPUT_LIMITS)
        config = self._make_base_config()
        self.assertEqual(config.stdout_max_bytes, SHAPE_OUTPUT_LIMITS["stdoutMaxBytes"])
        self.assertEqual(config.stderr_max_bytes, SHAPE_OUTPUT_LIMITS["stderrMaxBytes"])
        self.assertEqual(config.record_channel_max_bytes, SHAPE_OUTPUT_LIMITS["recordChannelMaxBytes"])
        self.assertEqual(config.record_channel_max_records, SHAPE_OUTPUT_LIMITS["recordChannelMaxRecords"])

    def test_hard_ceiling_constants_exist_and_never_below_defaults(self) -> None:
        ceilings = self.config_module.HARD_OUTPUT_LIMIT_CEILINGS
        self.assertEqual(set(ceilings), set(SHAPE_OUTPUT_LIMITS))
        for key, default in self.config_module.DEFAULT_OUTPUT_LIMITS.items():
            # Until the four-stage tests land, ceilings are pinned AT the
            # defaults; the invariant is ceiling >= default, and Nacos can
            # never push a value above the ceiling.
            self.assertGreaterEqual(ceilings[key], default, key)

    def test_output_limit_keys_are_verbatim_contract_keys(self) -> None:
        self.assertEqual(
            set(self.config_module.OUTPUT_LIMIT_KEYS), set(SHAPE_OUTPUT_LIMITS)
        )


class SnapshotDefaultsTest(NacosConfigTest):
    def test_initial_snapshot_is_static_default_with_exactly_five_keys(self) -> None:
        dyn = self._make_dynamic()
        snapshot = dyn.output_limits_snapshot()
        self.assertEqual(set(snapshot), SNAPSHOT_KEYS)
        self._assert_last_known_good(dyn, SHAPE_OUTPUT_LIMITS, "static-default")


class WholeObjectValidationTest(NacosConfigTest):
    def test_valid_whole_object_payload_replaces_all_four_limits(self) -> None:
        dyn = self._make_dynamic()
        # cmc must stay 1 under the unconditional invariant; the four limits
        # are the mutable part of the payload.
        payload = {
            "containerMaxConcurrency": 1,
            "stdoutMaxBytes": 1000,
            "stderrMaxBytes": 2000,
            "recordChannelMaxBytes": 3000,
            "recordChannelMaxRecords": 10,
        }
        self.assertTrue(dyn.apply_dynamic_content(json.dumps(payload)))
        snapshot = dyn.output_limits_snapshot()
        self.assertEqual(snapshot["stdoutMaxBytes"], 1000)
        self.assertEqual(snapshot["stderrMaxBytes"], 2000)
        self.assertEqual(snapshot["recordChannelMaxBytes"], 3000)
        self.assertEqual(snapshot["recordChannelMaxRecords"], 10)
        self.assertEqual(dyn.container_max_concurrency, 1)
        self.assertNotEqual(snapshot["sourceRevision"], "static-default")

    def test_invalid_json_keeps_last_known_good(self) -> None:
        dyn = self._make_dynamic()
        self.assertTrue(dyn.apply_dynamic_content(json.dumps({"stdoutMaxBytes": 4096})))
        good = dyn.output_limits_snapshot()
        with self.assertLogs("app.nacos_config", level="WARNING") as captured:
            self.assertFalse(dyn.apply_dynamic_content('{"stdoutMaxBytes": oops'))
        self.assertTrue(any("REJECTED" in message for message in captured.output))
        self._assert_last_known_good(
            dyn,
            {k: good[k] for k in SHAPE_OUTPUT_LIMITS},
            good["sourceRevision"],
        )

    def test_non_object_payload_keeps_last_known_good(self) -> None:
        dyn = self._make_dynamic()
        for content in ("[1, 2, 3]", '"hello"', "null", "123"):
            with self.subTest(content=content):
                with self.assertLogs("app.nacos_config", level="WARNING"):
                    self.assertFalse(dyn.apply_dynamic_content(content))
        self._assert_last_known_good(dyn, SHAPE_OUTPUT_LIMITS, "static-default")

    def test_one_invalid_value_rejects_whole_payload_no_partial_apply(self) -> None:
        dyn = self._make_dynamic()
        baseline = dyn.output_limits_snapshot()
        bad_payloads = [
            # One valid key + one negative key: nothing may be applied.
            {"stdoutMaxBytes": 4096, "stderrMaxBytes": -1},
            # Wrong type (string instead of int).
            {"recordChannelMaxRecords": "128"},
            # bool is an int subclass but not a valid limit value.
            {"stdoutMaxBytes": True},
            # containerMaxConcurrency below its minimum poisons the payload.
            {"containerMaxConcurrency": 0, "stdoutMaxBytes": 4096},
            # containerMaxConcurrency above the unconditional cmc==1 ceiling
            # poisons the payload for ALL configs (codex c72db8f6 item 4).
            {"containerMaxConcurrency": 2, "stdoutMaxBytes": 4096},
        ]
        for payload in bad_payloads:
            with self.subTest(payload=payload):
                with self.assertLogs("app.nacos_config", level="WARNING"):
                    self.assertFalse(dyn.apply_dynamic_content(json.dumps(payload)))
                self.assertEqual(dyn.output_limits_snapshot(), baseline)
        # Explicitly: the valid stdoutMaxBytes=4096 from the first payload was
        # NOT partially applied.
        self.assertEqual(dyn.output_limits_snapshot()["stdoutMaxBytes"],
                         SHAPE_OUTPUT_LIMITS["stdoutMaxBytes"])

    def test_unknown_keys_ignored_known_keys_still_applied(self) -> None:
        # Judgment call: unknown keys are ignored (with a warning) for forward
        # compatibility; they do not invalidate known keys in the same payload.
        dyn = self._make_dynamic()
        payload = {"stdoutMaxBytes": 2048, "someFutureKey": 1}
        with self.assertLogs("app.nacos_config", level="WARNING") as captured:
            self.assertTrue(dyn.apply_dynamic_content(json.dumps(payload)))
        self.assertTrue(any("someFutureKey" in message for message in captured.output))
        self.assertEqual(dyn.output_limits_snapshot()["stdoutMaxBytes"], 2048)

    def test_empty_content_and_empty_object_are_noops(self) -> None:
        dyn = self._make_dynamic()
        for content in ("", "   ", "{}"):
            with self.subTest(content=content):
                self.assertFalse(dyn.apply_dynamic_content(content))
        self._assert_last_known_good(dyn, SHAPE_OUTPUT_LIMITS, "static-default")


class HardCeilingClampTest(NacosConfigTest):
    def test_value_above_hard_ceiling_is_clamped_down_and_logged(self) -> None:
        dyn = self._make_dynamic()
        ceiling = self.config_module.HARD_OUTPUT_LIMIT_CEILINGS["stdoutMaxBytes"]
        payload = {"stdoutMaxBytes": ceiling + 4096}
        with self.assertLogs("app.nacos_config", level="WARNING") as captured:
            self.assertTrue(dyn.apply_dynamic_content(json.dumps(payload)))
        self.assertEqual(dyn.output_limits_snapshot()["stdoutMaxBytes"], ceiling)
        clamp_messages = [m for m in captured.output if "DYNAMIC_CONFIG_CLAMPED" in m]
        self.assertTrue(clamp_messages, captured.output)
        self.assertTrue(any("stdoutMaxBytes" in m and str(ceiling) in m for m in clamp_messages))

    def test_value_below_ceiling_accepted_as_is(self) -> None:
        dyn = self._make_dynamic()
        self.assertTrue(dyn.apply_dynamic_content(json.dumps({"stdoutMaxBytes": 1024})))
        self.assertEqual(dyn.output_limits_snapshot()["stdoutMaxBytes"], 1024)

    def test_value_equal_to_ceiling_accepted_as_is(self) -> None:
        dyn = self._make_dynamic()
        ceiling = self.config_module.HARD_OUTPUT_LIMIT_CEILINGS["recordChannelMaxRecords"]
        self.assertTrue(
            dyn.apply_dynamic_content(json.dumps({"recordChannelMaxRecords": ceiling}))
        )
        self.assertEqual(dyn.output_limits_snapshot()["recordChannelMaxRecords"], ceiling)

    def test_base_config_above_ceiling_is_clamped_at_construction(self) -> None:
        ceiling = self.config_module.HARD_OUTPUT_LIMIT_CEILINGS["stderrMaxBytes"]
        with self.assertLogs("app.nacos_config", level="WARNING"):
            dyn = self._make_dynamic(stderr_max_bytes=ceiling + 1)
            # Construction already clamped; snapshot reads the clamped value.
            self.assertEqual(dyn.output_limits_snapshot()["stderrMaxBytes"], ceiling)


class SnapshotIsolationTest(NacosConfigTest):
    def test_snapshot_returns_exactly_five_verbatim_keys(self) -> None:
        dyn = self._make_dynamic()
        snapshot = dyn.output_limits_snapshot()
        self.assertEqual(
            set(snapshot),
            {"stdoutMaxBytes", "stderrMaxBytes", "recordChannelMaxBytes",
             "recordChannelMaxRecords", "sourceRevision"},
        )

    def test_later_updates_do_not_mutate_earlier_snapshot(self) -> None:
        dyn = self._make_dynamic()
        before = dyn.output_limits_snapshot()
        self.assertTrue(
            dyn.apply_dynamic_content(json.dumps({"stdoutMaxBytes": 4096, "stderrMaxBytes": 1024}))
        )
        after = dyn.output_limits_snapshot()
        # Earlier snapshot is untouched by the later update.
        self.assertEqual(before["sourceRevision"], "static-default")
        self.assertEqual(before["stdoutMaxBytes"], SHAPE_OUTPUT_LIMITS["stdoutMaxBytes"])
        self.assertEqual(before["stderrMaxBytes"], SHAPE_OUTPUT_LIMITS["stderrMaxBytes"])
        self.assertEqual(after["stdoutMaxBytes"], 4096)
        self.assertEqual(after["stderrMaxBytes"], 1024)
        # Mutating the handed-out dict cannot leak back into the config.
        before["stdoutMaxBytes"] = 1
        self.assertEqual(dyn.output_limits_snapshot()["stdoutMaxBytes"], 4096)


class SourceRevisionTest(NacosConfigTest):
    def test_revision_is_static_default_initially_and_changes_on_apply(self) -> None:
        dyn = self._make_dynamic()
        self.assertEqual(dyn.source_revision, "static-default")
        self.assertEqual(dyn.output_limits_snapshot()["sourceRevision"], "static-default")
        self.assertTrue(dyn.apply_dynamic_content(json.dumps({"stdoutMaxBytes": 4096})))
        self.assertNotEqual(dyn.source_revision, "static-default")
        self.assertTrue(dyn.source_revision.startswith("nacos-sha256:"))

    def test_revision_is_deterministic_per_payload(self) -> None:
        dyn_a = self._make_dynamic()
        dyn_b = self._make_dynamic()
        payload_a = {"stdoutMaxBytes": 4096}
        payload_b = {"stdoutMaxBytes": 8192}
        self.assertTrue(dyn_a.apply_dynamic_content(json.dumps(payload_a)))
        self.assertTrue(dyn_b.apply_dynamic_content(json.dumps(payload_a)))
        self.assertEqual(dyn_a.source_revision, dyn_b.source_revision)
        self.assertTrue(dyn_b.apply_dynamic_content(json.dumps(payload_b)))
        self.assertNotEqual(dyn_a.source_revision, dyn_b.source_revision)

    def test_rejected_payload_does_not_change_revision(self) -> None:
        dyn = self._make_dynamic()
        self.assertTrue(dyn.apply_dynamic_content(json.dumps({"stdoutMaxBytes": 4096})))
        revision = dyn.source_revision
        self.assertFalse(dyn.apply_dynamic_content(json.dumps({"stdoutMaxBytes": -1})))
        self.assertEqual(dyn.source_revision, revision)


class ContainerMaxConcurrencyRegressionTest(NacosConfigTest):
    def test_direct_update_method_semantics(self) -> None:
        dyn = self._make_dynamic()
        self.assertEqual(dyn.container_max_concurrency, 1)
        # cmc>1 is rejected for ALL configs under the unconditional invariant
        # (codex c72db8f6 item 4 / 56d28076); the current value is retained.
        with self.assertLogs("app.nacos_config", level="WARNING") as captured:
            dyn.update_container_max_concurrency(5)
        self.assertEqual(dyn.container_max_concurrency, 1)
        self.assertTrue(any("DYNAMIC_CONFIG_REJECTED" in m for m in captured.output))
        with self.assertLogs("app.nacos_config", level="WARNING") as captured:
            dyn.update_container_max_concurrency(0)
        self.assertEqual(dyn.container_max_concurrency, 1)
        self.assertTrue(any("Ignoring invalid container_max_concurrency" in m
                            for m in captured.output))
        dyn.update_container_max_concurrency(-3)
        self.assertEqual(dyn.container_max_concurrency, 1)
        # The only applicable value is 1 itself (idempotent apply).
        dyn.update_container_max_concurrency(1)
        self.assertEqual(dyn.container_max_concurrency, 1)

    def test_hot_reload_via_payload_content(self) -> None:
        dyn = self._make_dynamic()
        # cmc>1 now rejects the WHOLE payload for ALL configs; value retained.
        with self.assertLogs("app.nacos_config", level="WARNING") as captured:
            self.assertFalse(dyn.apply_dynamic_content('{"containerMaxConcurrency": 7}'))
        self.assertTrue(any("DYNAMIC_CONFIG_REJECTED" in m for m in captured.output))
        self.assertEqual(dyn.container_max_concurrency, 1)
        # Invalid value rejects the whole payload; previous value retained.
        self.assertFalse(dyn.apply_dynamic_content('{"containerMaxConcurrency": 0}'))
        self.assertEqual(dyn.container_max_concurrency, 1)
        # cmc=1 is the only applicable value and applies cleanly.
        self.assertTrue(dyn.apply_dynamic_content('{"containerMaxConcurrency": 1}'))
        self.assertEqual(dyn.container_max_concurrency, 1)

    def test_apply_to_mirrors_dynamic_values(self) -> None:
        dyn = self._make_dynamic()
        # cmc=1 is the only dynamic concurrency that can ever be applied under
        # the unconditional invariant; mirror it alongside a lowered limit.
        self.assertTrue(
            dyn.apply_dynamic_content(
                json.dumps({"containerMaxConcurrency": 1, "stdoutMaxBytes": 4096})
            )
        )
        effective = dyn.apply_to(self._make_base_config())
        self.assertEqual(effective.container_max_concurrency, 1)
        self.assertEqual(effective.stdout_max_bytes, 4096)
        # cmc==1 keeps the global compat input symlink enabled.
        self.assertTrue(effective.compat_input_path_enabled)
        # No-change path returns the same object.
        unchanged_dyn = self._make_dynamic()
        base = self._make_base_config()
        self.assertIs(unchanged_dyn.apply_to(base), base)


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

    def test_base_config_above_one_fails_fast_for_all_configs(self) -> None:
        for skip in (True, False):
            with self.subTest(skip_environment_setup=skip):
                with self.assertRaisesRegex(
                    ValueError, "container_max_concurrency must be 1"
                ):
                    self._make_dynamic(
                        skip_environment_setup=skip, container_max_concurrency=2
                    )

    def test_base_config_one_is_accepted_for_all_configs(self) -> None:
        for skip in (True, False):
            with self.subTest(skip_environment_setup=skip):
                dyn = self._make_dynamic(
                    skip_environment_setup=skip, container_max_concurrency=1
                )
                self.assertEqual(dyn.container_max_concurrency, 1)

    def test_single_field_hot_update_above_one_rejected_for_all_configs(self) -> None:
        for skip in (True, False):
            with self.subTest(skip_environment_setup=skip):
                dyn = self._make_dynamic(skip_environment_setup=skip)
                self.assertEqual(dyn.container_max_concurrency, 1)
                with self.assertLogs("app.nacos_config", level="WARNING") as captured:
                    dyn.update_container_max_concurrency(2)
                # The invalid hot update must not change the current value.
                self.assertEqual(dyn.container_max_concurrency, 1)
                self.assertTrue(
                    any("DYNAMIC_CONFIG_REJECTED" in m for m in captured.output)
                )

    def test_single_field_hot_update_to_one_applies_for_all_configs(self) -> None:
        for skip in (True, False):
            with self.subTest(skip_environment_setup=skip):
                dyn = self._make_dynamic(skip_environment_setup=skip)
                dyn.update_container_max_concurrency(1)
                self.assertEqual(dyn.container_max_concurrency, 1)

    def test_whole_payload_above_one_rejected_snapshot_unchanged(self) -> None:
        for skip in (True, False):
            with self.subTest(skip_environment_setup=skip):
                dyn = self._make_dynamic(skip_environment_setup=skip)
                # Establish a known-good snapshot (a lowered limit) so the
                # rejection must preserve a non-default revision too.
                self.assertTrue(
                    dyn.apply_dynamic_content(json.dumps({"stdoutMaxBytes": 2048}))
                )
                snapshot_before = dyn.output_limits_snapshot()
                # The violating cmc rejects the WHOLE payload, including the
                # valid stdoutMaxBytes carried alongside it; the ENTIRE
                # snapshot stays unchanged, including sourceRevision.
                with self.assertLogs("app.nacos_config", level="WARNING") as captured:
                    self.assertFalse(
                        dyn.apply_dynamic_payload(
                            {"containerMaxConcurrency": 2, "stdoutMaxBytes": 100}
                        )
                    )
                self.assertTrue(
                    any("DYNAMIC_CONFIG_REJECTED" in m for m in captured.output)
                )
                self.assertEqual(dyn.container_max_concurrency, 1)
                self.assertEqual(dyn.output_limits_snapshot(), snapshot_before)
                self.assertEqual(dyn.source_revision, snapshot_before["sourceRevision"])

    def test_whole_payload_cmc_one_applies_and_updates_revision(self) -> None:
        for skip in (True, False):
            with self.subTest(skip_environment_setup=skip):
                dyn = self._make_dynamic(skip_environment_setup=skip)
                self.assertEqual(dyn.source_revision, "static-default")
                self.assertTrue(
                    dyn.apply_dynamic_payload(
                        {"containerMaxConcurrency": 1, "stdoutMaxBytes": 100}
                    )
                )
                self.assertEqual(dyn.container_max_concurrency, 1)
                self.assertEqual(dyn.output_limits_snapshot()["stdoutMaxBytes"], 100)
                self.assertNotEqual(dyn.source_revision, "static-default")
                self.assertTrue(dyn.source_revision.startswith("nacos-sha256:"))


class AtomicReplacementTest(NacosConfigTest):
    def test_concurrent_readers_never_observe_half_applied_config(self) -> None:
        dyn = self._make_dynamic()
        payload_a = {
            "stdoutMaxBytes": 1000,
            "stderrMaxBytes": 2000,
            "recordChannelMaxBytes": 3000,
            "recordChannelMaxRecords": 10,
        }
        payload_b = {
            "stdoutMaxBytes": 4000,
            "stderrMaxBytes": 5000,
            "recordChannelMaxBytes": 6000,
            "recordChannelMaxRecords": 20,
        }
        content_a = json.dumps(payload_a)
        content_b = json.dumps(payload_b)
        self.assertTrue(dyn.apply_dynamic_content(content_a))
        revision_a = dyn.source_revision
        self.assertTrue(dyn.apply_dynamic_content(content_b))
        revision_b = dyn.source_revision

        allowed_states = {
            (SHAPE_OUTPUT_LIMITS["stdoutMaxBytes"], SHAPE_OUTPUT_LIMITS["stderrMaxBytes"],
             SHAPE_OUTPUT_LIMITS["recordChannelMaxBytes"], SHAPE_OUTPUT_LIMITS["recordChannelMaxRecords"],
             "static-default"),
            (1000, 2000, 3000, 10, revision_a),
            (4000, 5000, 6000, 20, revision_b),
        }
        violations = []
        stop = threading.Event()

        def reader() -> None:
            while not stop.is_set():
                snap = dyn.output_limits_snapshot()
                observed = (
                    snap["stdoutMaxBytes"],
                    snap["stderrMaxBytes"],
                    snap["recordChannelMaxBytes"],
                    snap["recordChannelMaxRecords"],
                    snap["sourceRevision"],
                )
                if observed not in allowed_states:
                    violations.append(observed)

        readers = [threading.Thread(target=reader, daemon=True) for _ in range(2)]
        for thread in readers:
            thread.start()
        try:
            for index in range(200):
                dyn.apply_dynamic_content(content_a if index % 2 == 0 else content_b)
        finally:
            stop.set()
            for thread in readers:
                thread.join(timeout=5)
        self.assertEqual(violations, [])


class ExampleConfigFileTest(NacosConfigTest):
    def test_example_json_parses_and_is_whole_object_valid(self) -> None:
        example_path = (
            Path(__file__).resolve().parents[1] / "config" / "python-sandbox.example.json"
        )
        content = example_path.read_text(encoding="utf-8")
        payload = json.loads(content)
        self.assertIsInstance(payload, dict)
        dyn = self._make_dynamic()
        # The shipped example must pass whole-object validation unclamped.
        self.assertTrue(dyn.apply_dynamic_content(content))
        snapshot = dyn.output_limits_snapshot()
        for key, default in SHAPE_OUTPUT_LIMITS.items():
            self.assertEqual(snapshot[key], payload[key], key)
            self.assertLessEqual(
                payload[key], self.config_module.HARD_OUTPUT_LIMIT_CEILINGS[key], key
            )


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

    def test_absent_or_blank_lane_collapses_to_main_data_id_only(self) -> None:
        candidate_ids = self.nacos_module._candidate_data_ids
        for lane in ("", "   ", "\t"):
            with self.subTest(lane=lane):
                self.assertEqual(candidate_ids(self.DATA_ID, lane), [self.DATA_ID])

    def test_lane_prefixes_data_id_and_main_is_always_last(self) -> None:
        candidate_ids = self.nacos_module._candidate_data_ids
        chain = candidate_ids(self.DATA_ID, "lane-demo")
        self.assertEqual(chain, ["lane-demo.python-sandbox.json", self.DATA_ID])
        # Surrounding whitespace must not leak into the constructed data-id.
        self.assertEqual(candidate_ids(self.DATA_ID, "  lane-demo  "), chain)


class ResolveConfigChainTest(NacosConfigTest):
    """Pure resolver: order, first non-blank wins, empty-chain result."""

    GROUP = "alphafrog-beta-config"
    LANE_ID = "lane-demo.python-sandbox.json"
    MAIN_ID = "python-sandbox.json"

    def test_single_candidate_with_content_resolves(self) -> None:
        client = FakeNacosConfigClient({self.MAIN_ID: '{"stdoutMaxBytes": 111}'})
        content, winner = self.nacos_module.resolve_config_chain(
            client.get_config, [self.MAIN_ID], self.GROUP
        )
        self.assertEqual(winner, self.MAIN_ID)
        self.assertEqual(content, '{"stdoutMaxBytes": 111}')
        # The group from the environment is passed through to get_config.
        self.assertEqual(client.get_config_calls, [(self.MAIN_ID, self.GROUP)])

    def test_lane_candidate_is_fetched_first_and_short_circuits(self) -> None:
        client = FakeNacosConfigClient({
            self.LANE_ID: '{"stdoutMaxBytes": 222}',
            self.MAIN_ID: '{"stdoutMaxBytes": 333}',
        })
        content, winner = self.nacos_module.resolve_config_chain(
            client.get_config, [self.LANE_ID, self.MAIN_ID], self.GROUP
        )
        self.assertEqual(winner, self.LANE_ID)
        self.assertEqual(json.loads(content)["stdoutMaxBytes"], 222)
        # Resolution order: the lane data-id is fetched first and wins, so
        # the main data-id is never fetched.
        self.assertEqual(client.get_config_calls, [(self.LANE_ID, self.GROUP)])

    def test_missing_or_blank_lane_content_falls_through_to_main(self) -> None:
        for lane_content in (None, "", "   "):
            with self.subTest(lane_content=lane_content):
                client = FakeNacosConfigClient({self.MAIN_ID: '{"stdoutMaxBytes": 444}'})
                if lane_content is not None:
                    client.contents[self.LANE_ID] = lane_content
                content, winner = self.nacos_module.resolve_config_chain(
                    client.get_config, [self.LANE_ID, self.MAIN_ID], self.GROUP
                )
                self.assertEqual(winner, self.MAIN_ID)
                self.assertEqual(json.loads(content)["stdoutMaxBytes"], 444)
                self.assertEqual(
                    client.get_config_calls,
                    [(self.LANE_ID, self.GROUP), (self.MAIN_ID, self.GROUP)],
                )

    def test_whole_chain_empty_returns_none_none(self) -> None:
        client = FakeNacosConfigClient({self.MAIN_ID: "   "})
        self.assertEqual(
            self.nacos_module.resolve_config_chain(
                client.get_config, [self.LANE_ID, self.MAIN_ID], self.GROUP
            ),
            (None, None),
        )
        # The whole chain was scanned before giving up.
        self.assertEqual(
            client.get_config_calls,
            [(self.LANE_ID, self.GROUP), (self.MAIN_ID, self.GROUP)],
        )


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

    def test_no_lane_applies_main_content(self) -> None:
        dyn = self._make_dynamic()
        client = FakeNacosConfigClient({self.MAIN_ID: json.dumps({"stdoutMaxBytes": 2048})})
        with self.assertLogs("app.nacos_config", level="INFO") as captured:
            winner = self.nacos_module._apply_resolved_chain_content(
                dyn, client.get_config, [self.MAIN_ID], self.GROUP
            )
        self.assertEqual(winner, self.MAIN_ID)
        self.assertEqual(dyn.output_limits_snapshot()["stdoutMaxBytes"], 2048)
        resolved = [m for m in captured.output if "NACOS_CONFIG_RESOLVED" in m]
        self.assertEqual(len(resolved), 1, captured.output)
        self.assertIn(f"data_id={self.MAIN_ID}", resolved[0])
        self.assertIn(self.GROUP, resolved[0])

    def test_lane_chain_applies_lane_content(self) -> None:
        dyn = self._make_dynamic()
        client = FakeNacosConfigClient({
            self.LANE_ID: json.dumps({"stdoutMaxBytes": 1111}),
            self.MAIN_ID: json.dumps({"stdoutMaxBytes": 2222}),
        })
        with self.assertLogs("app.nacos_config", level="INFO") as captured:
            winner = self.nacos_module._apply_resolved_chain_content(
                dyn, client.get_config, [self.LANE_ID, self.MAIN_ID], self.GROUP
            )
        self.assertEqual(winner, self.LANE_ID)
        # The LANE value was applied, not the main value.
        self.assertEqual(dyn.output_limits_snapshot()["stdoutMaxBytes"], 1111)
        self.assertTrue(any(
            "NACOS_CONFIG_RESOLVED" in m and f"data_id={self.LANE_ID}" in m
            for m in captured.output
        ))
        # The lane candidate was fetched first and short-circuited the chain.
        self.assertEqual(client.get_config_calls, [(self.LANE_ID, self.GROUP)])

    def test_whole_chain_empty_applies_nothing_and_logs_error(self) -> None:
        dyn = self._make_dynamic()
        client = FakeNacosConfigClient({self.MAIN_ID: "   "})
        with self.assertLogs("app.nacos_config", level="ERROR") as captured:
            winner = self.nacos_module._apply_resolved_chain_content(
                dyn, client.get_config, [self.LANE_ID, self.MAIN_ID], self.GROUP
            )
        self.assertIsNone(winner)
        self.assertTrue(
            any("NACOS_CONFIG_CHAIN_EMPTY" in m for m in captured.output),
            captured.output,
        )
        # The ERROR line carries the chain and the group.
        self.assertTrue(any(
            self.LANE_ID in m and self.MAIN_ID in m and self.GROUP in m
            for m in captured.output
        ))
        # Nothing was applied: still the complete static-default snapshot.
        self._assert_last_known_good(dyn, SHAPE_OUTPUT_LIMITS, "static-default")


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

    def test_lane_deleted_falls_back_to_main_and_push_body_is_not_trusted(self) -> None:
        dyn = self._make_dynamic()
        lane_payload = json.dumps({"stdoutMaxBytes": 1111})
        main_payload = json.dumps({"stdoutMaxBytes": 2222})
        client = FakeNacosConfigClient({
            self.LANE_ID: lane_payload,
            self.MAIN_ID: main_payload,
        })
        watcher = self._make_watcher(dyn, client)
        self.assertTrue(dyn.apply_dynamic_content(lane_payload))
        # The lane item is deleted in Nacos (e.g. `af-beta lane stop`), then a
        # push fires whose body still carries the OLD lane content. The
        # callback must ignore the pushed body and re-resolve the chain,
        # falling back to the main Beta content.
        client.delete(self.LANE_ID)
        with self.assertLogs("app.nacos_config", level="INFO") as captured:
            watcher(SimpleNamespace(raw=lane_payload))
        self.assertEqual(dyn.output_limits_snapshot()["stdoutMaxBytes"], 2222)
        self.assertTrue(any(
            "NACOS_CONFIG_RESOLVED" in m and f"data_id={self.MAIN_ID}" in m
            for m in captured.output
        ))

    def test_all_empty_push_keeps_last_known_good(self) -> None:
        dyn = self._make_dynamic()
        lane_payload = json.dumps({"stdoutMaxBytes": 1111})
        client = FakeNacosConfigClient({self.LANE_ID: lane_payload})
        watcher = self._make_watcher(dyn, client)
        self.assertTrue(dyn.apply_dynamic_content(lane_payload))
        revision = dyn.source_revision
        # Both chain members are now gone in Nacos.
        client.delete(self.LANE_ID)
        with self.assertLogs("app.nacos_config", level="ERROR") as captured:
            watcher(SimpleNamespace(raw=""))
        self.assertTrue(
            any("NACOS_CONFIG_CHAIN_EMPTY" in m for m in captured.output),
            captured.output,
        )
        # Last-known-good retained wholesale; no reset is attempted.
        self.assertEqual(dyn.output_limits_snapshot()["stdoutMaxBytes"], 1111)
        self.assertEqual(dyn.source_revision, revision)

    def test_get_config_failure_keeps_last_known_good_and_does_not_raise(self) -> None:
        dyn = self._make_dynamic()
        self.assertTrue(dyn.apply_dynamic_content(json.dumps({"stdoutMaxBytes": 4096})))
        revision = dyn.source_revision

        def broken_get_config(data_id, group):
            raise ConnectionError("nacos unreachable")

        watcher = self.nacos_module._make_chain_config_watcher(
            dyn, broken_get_config, [self.LANE_ID, self.MAIN_ID], self.GROUP
        )
        # The exception must not escape into the SDK's watcher thread.
        with self.assertLogs("app.nacos_config", level="ERROR") as captured:
            watcher(SimpleNamespace(raw="anything"))
        self.assertTrue(
            any("NACOS_CONFIG_WATCHER_FAILED" in m for m in captured.output),
            captured.output,
        )
        self.assertEqual(dyn.source_revision, revision)


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

    def test_lane_env_resolves_chain_and_watches_every_candidate(self) -> None:
        contents = {
            self.LANE_ID: json.dumps({"stdoutMaxBytes": 1111}),
            self.MAIN_ID: json.dumps({"stdoutMaxBytes": 2222}),
        }
        env = {
            "AF_CONFIG_NACOS_ENABLED": "true",
            "AF_CONFIG_NACOS_GROUP": self.GROUP,
            "AF_CONFIG_NACOS_DATA_ID": self.MAIN_ID,
            "AF_LANE_TRAFFIC_SCOPE_ID": "lane-demo",
        }
        dyn = self._make_dynamic()
        client = self._start_listener(dyn, contents, env, expected_watchers=2)
        # Initial resolution applied the LANE content (first chain member).
        self.assertEqual(dyn.output_limits_snapshot()["stdoutMaxBytes"], 1111)
        # One watcher per chain candidate, in chain order, in the env group.
        self.assertEqual(
            [(d, g) for d, g, _ in client.watchers],
            [(self.LANE_ID, self.GROUP), (self.MAIN_ID, self.GROUP)],
        )
        # Lane item deleted in Nacos; firing the MAIN watcher must re-resolve
        # the full chain and fall back to the main Beta content.
        client.delete(self.LANE_ID)
        with self.assertLogs("app.nacos_config", level="INFO") as captured:
            client.fire(self.MAIN_ID, contents[self.MAIN_ID])
        self.assertEqual(dyn.output_limits_snapshot()["stdoutMaxBytes"], 2222)
        self.assertTrue(any("NACOS_CONFIG_RESOLVED" in m for m in captured.output))

    def test_no_lane_env_uses_single_main_data_id(self) -> None:
        contents = {self.MAIN_ID: json.dumps({"stdoutMaxBytes": 3333})}
        env = {
            "AF_CONFIG_NACOS_ENABLED": "true",
            "AF_CONFIG_NACOS_GROUP": self.GROUP,
            "AF_CONFIG_NACOS_DATA_ID": self.MAIN_ID,
            "AF_LANE_TRAFFIC_SCOPE_ID": "",
        }
        dyn = self._make_dynamic()
        client = self._start_listener(dyn, contents, env, expected_watchers=1)
        self.assertEqual(dyn.output_limits_snapshot()["stdoutMaxBytes"], 3333)
        # No lane env var -> the chain (and the watcher set) is main only.
        self.assertEqual(
            [(d, g) for d, g, _ in client.watchers],
            [(self.MAIN_ID, self.GROUP)],
        )
        self.assertEqual(client.get_config_calls, [(self.MAIN_ID, self.GROUP)])


if __name__ == "__main__":
    unittest.main()
