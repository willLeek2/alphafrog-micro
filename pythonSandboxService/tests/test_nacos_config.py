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
    and whole-payload path, invalid values ignored).

Constructed directly from a ``SandboxConfig`` instance; stdlib unittest only,
no nacos SDK or network required.

Run: ``cd pythonSandboxService && python3 -m unittest tests.test_nacos_config -v``
"""

from __future__ import annotations

import importlib
import json
import threading
import unittest
from pathlib import Path

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
        payload = {
            "containerMaxConcurrency": 3,
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
        self.assertEqual(dyn.container_max_concurrency, 3)
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
        dyn.update_container_max_concurrency(5)
        self.assertEqual(dyn.container_max_concurrency, 5)
        with self.assertLogs("app.nacos_config", level="WARNING") as captured:
            dyn.update_container_max_concurrency(0)
        self.assertEqual(dyn.container_max_concurrency, 5)
        self.assertTrue(any("Ignoring invalid container_max_concurrency" in m
                            for m in captured.output))
        dyn.update_container_max_concurrency(-3)
        self.assertEqual(dyn.container_max_concurrency, 5)

    def test_hot_reload_via_payload_content(self) -> None:
        dyn = self._make_dynamic()
        self.assertTrue(dyn.apply_dynamic_content('{"containerMaxConcurrency": 7}'))
        self.assertEqual(dyn.container_max_concurrency, 7)
        # Invalid value rejects the whole payload; previous value retained.
        self.assertFalse(dyn.apply_dynamic_content('{"containerMaxConcurrency": 0}'))
        self.assertEqual(dyn.container_max_concurrency, 7)

    def test_apply_to_mirrors_dynamic_values(self) -> None:
        dyn = self._make_dynamic()
        self.assertTrue(
            dyn.apply_dynamic_content(
                json.dumps({"containerMaxConcurrency": 2, "stdoutMaxBytes": 4096})
            )
        )
        effective = dyn.apply_to(self._make_base_config())
        self.assertEqual(effective.container_max_concurrency, 2)
        self.assertEqual(effective.stdout_max_bytes, 4096)
        # cmc > 1 disables the global compat input symlink.
        self.assertFalse(effective.compat_input_path_enabled)
        # No-change path returns the same object.
        unchanged_dyn = self._make_dynamic()
        base = self._make_base_config()
        self.assertIs(unchanged_dyn.apply_to(base), base)


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


if __name__ == "__main__":
    unittest.main()
