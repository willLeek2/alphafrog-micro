from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from af_light_client.config import LightClientConfig


class LightClientConfigDebugTest(unittest.TestCase):
    def test_debug_defaults_disabled(self) -> None:
        cfg = _load("""
base_url: "http://localhost:8090"
username: "u"
password: "p"
question: "q"
""")
        self.assertFalse(cfg.debug_logs)
        self.assertEqual(cfg.debug_output_root, "af_light_client/output")

    def test_debug_config_parsed(self) -> None:
        cfg = _load("""
base_url: "http://localhost:8090"
username: "u"
password: "p"
question: "q"
debug:
  logs: true
  output_root: "output/sample"
""")
        self.assertTrue(cfg.debug_logs)
        self.assertEqual(cfg.debug_output_root, "output/sample")
        self.assertFalse(cfg.debug_tui_snapshots.enabled)

    def test_tui_snapshots_config_parsed_with_milliseconds(self) -> None:
        cfg = _load("""
base_url: "http://localhost:8090"
username: "u"
password: "p"
question: "q"
debug:
  logs: false
  output_root: "output/sample"
  tui_snapshots:
    enabled: true
    interval_ms: 250
    batch_interval_ms: 5000
""")
        self.assertTrue(cfg.debug_tui_snapshots.enabled)
        self.assertEqual(cfg.debug_tui_snapshots.interval_ms, 250)
        self.assertEqual(cfg.debug_tui_snapshots.batch_interval_ms, 5000)

    def test_tui_snapshots_config_parsed_with_seconds(self) -> None:
        cfg = _load("""
base_url: "http://localhost:8090"
username: "u"
password: "p"
question: "q"
debug:
  tui_snapshots:
    enabled: true
    interval_seconds: 2
    batch_interval_seconds: 60
""")
        self.assertEqual(cfg.debug_tui_snapshots.interval_ms, 2000)
        self.assertEqual(cfg.debug_tui_snapshots.batch_interval_ms, 60000)

    def test_tui_snapshot_interval_seconds_must_be_greater_than_one(self) -> None:
        with self.assertRaisesRegex(ValueError, "seconds must be > 1"):
            _load("""
base_url: "http://localhost:8090"
username: "u"
password: "p"
question: "q"
debug:
  tui_snapshots:
    enabled: true
    interval_seconds: 1
    batch_interval_ms: 5000
""")

    def test_tui_snapshot_interval_ms_must_be_greater_than_200(self) -> None:
        with self.assertRaisesRegex(ValueError, "ms must be > 200"):
            _load("""
base_url: "http://localhost:8090"
username: "u"
password: "p"
question: "q"
debug:
  tui_snapshots:
    enabled: true
    interval_ms: 200
    batch_interval_ms: 5000
""")

    def test_tui_snapshot_batch_interval_required_when_enabled(self) -> None:
        with self.assertRaisesRegex(ValueError, "batch interval must be configured"):
            _load("""
base_url: "http://localhost:8090"
username: "u"
password: "p"
question: "q"
debug:
  tui_snapshots:
    enabled: true
    interval_ms: 250
""")

    def test_as_log_dict_redacts_password(self) -> None:
        cfg = _load("""
base_url: "http://localhost:8090"
username: "u"
password: "p"
question: "q"
""")
        self.assertEqual(cfg.as_log_dict()["password"], "<redacted>")

    def test_llm_config_merges_into_create_request_body(self) -> None:
        cfg = _load("""
base_url: "http://localhost:8090"
username: "u"
password: "p"
question: "q"
llm:
  endpointName: openrouter
  modelName: qwen/qwen3.7-max
  provider: alibaba
""")
        body = cfg.create_request_body()
        self.assertEqual(body["message"], "q")
        self.assertEqual(body["endpointName"], "openrouter")
        self.assertEqual(body["modelName"], "qwen/qwen3.7-max")
        self.assertEqual(body["provider"], "alibaba")

    def test_llm_stage_config_is_preserved(self) -> None:
        cfg = _load("""
base_url: "http://localhost:8090"
username: "u"
password: "p"
question: "q"
llm:
  endpointName: openrouter
  modelName: qwen/qwen3.7-max
  provider: alibaba
  stage_config:
    planning:
      endpointName: openrouter
      modelName: moonshotai/kimi-k2.6
      providerOrder: [fireworks, moonshotai/int4]
    final_answer:
      endpointName: openrouter
      modelName: moonshotai/kimi-k2.6
      providerOrder: [fireworks, moonshotai/int4]
""")
        body = cfg.create_request_body()
        stage_config = json.loads(body["stage_config_json"])
        self.assertNotIn("stage_config", body)
        self.assertEqual(
            stage_config["planning"]["providerOrder"],
            ["fireworks", "moonshotai/int4"],
        )
        self.assertEqual(
            stage_config["final_answer"]["modelName"],
            "moonshotai/kimi-k2.6",
        )
        self.assertEqual(
            body["stage_config_json"],
            '{"planning":{"endpointName":"openrouter","modelName":"moonshotai/kimi-k2.6",'
            '"providerOrder":["fireworks","moonshotai/int4"]},"final_answer":'
            '{"endpointName":"openrouter","modelName":"moonshotai/kimi-k2.6",'
            '"providerOrder":["fireworks","moonshotai/int4"]}}',
        )

    def test_provider_list_is_normalized_to_comma_separated_string(self) -> None:
        cfg = _load("""
base_url: "http://localhost:8090"
username: "u"
password: "p"
question: "q"
llm:
  endpointName: openrouter
  modelName: qwen/qwen3.7-max
  provider: [alibaba, fireworks]
""")
        body = cfg.create_request_body()
        self.assertEqual(body["provider"], "alibaba,fireworks")

    def test_provider_order_is_normalized_to_comma_separated_string(self) -> None:
        cfg = _load("""
base_url: "http://localhost:8090"
username: "u"
password: "p"
question: "q"
llm:
  endpointName: openrouter
  modelName: qwen/qwen3.7-max
  providerOrder: [alibaba, fireworks]
""")
        body = cfg.create_request_body()
        self.assertEqual(body["provider"], "alibaba,fireworks")
        self.assertNotIn("providerOrder", body)

    def test_provider_order_takes_precedence_over_provider(self) -> None:
        cfg = _load("""
base_url: "http://localhost:8090"
username: "u"
password: "p"
question: "q"
llm:
  endpointName: openrouter
  modelName: qwen/qwen3.7-max
  provider: moonshotai
  providerOrder: [alibaba, fireworks]
""")
        body = cfg.create_request_body()
        self.assertEqual(body["provider"], "alibaba,fireworks")

    def test_provider_string_is_preserved(self) -> None:
        cfg = _load("""
base_url: "http://localhost:8090"
username: "u"
password: "p"
question: "q"
llm:
  endpointName: openrouter
  modelName: qwen/qwen3.7-max
  provider: alibaba
""")
        body = cfg.create_request_body()
        self.assertEqual(body["provider"], "alibaba")

    def test_create_body_overrides_llm_config(self) -> None:
        cfg = _load("""
base_url: "http://localhost:8090"
username: "u"
password: "p"
question: "q"
llm:
  endpointName: openrouter
  modelName: qwen/qwen3.7-max
  provider: alibaba
  stage_config:
    planning:
      modelName: moonshotai/kimi-k2.6
      providerOrder: [fireworks]
create_body:
  modelName: moonshotai/kimi-k2.6
  stage_config:
    planning:
      providerOrder: [moonshotai/int4]
""")
        body = cfg.create_request_body()
        stage_config = json.loads(body["stage_config_json"])
        self.assertEqual(body["endpointName"], "openrouter")
        self.assertEqual(body["modelName"], "moonshotai/kimi-k2.6")
        self.assertEqual(body["provider"], "alibaba")
        self.assertNotIn("stage_config", body)
        self.assertEqual(stage_config["planning"]["modelName"], "moonshotai/kimi-k2.6")
        self.assertEqual(stage_config["planning"]["providerOrder"], ["moonshotai/int4"])

    def test_create_body_stage_config_json_overrides_llm_stage_config(self) -> None:
        cfg = _load("""
base_url: "http://localhost:8090"
username: "u"
password: "p"
question: "q"
llm:
  stage_config:
    planning:
      providerOrder: [fireworks]
create_body:
  stage_config_json: '{"planning":{"providerOrder":["alibaba"]}}'
""")
        body = cfg.create_request_body()
        self.assertNotIn("stage_config", body)
        self.assertEqual(
            json.loads(body["stage_config_json"])["planning"]["providerOrder"],
            ["alibaba"],
        )


def _load(text: str) -> LightClientConfig:
    with tempfile.TemporaryDirectory() as td:
        path = Path(td) / "config.yml"
        path.write_text(text, encoding="utf-8")
        return LightClientConfig.from_file(path)


if __name__ == "__main__":
    unittest.main()
