from __future__ import annotations

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

    def test_as_log_dict_redacts_password(self) -> None:
        cfg = _load("""
base_url: "http://localhost:8090"
username: "u"
password: "p"
question: "q"
""")
        self.assertEqual(cfg.as_log_dict()["password"], "<redacted>")


def _load(text: str) -> LightClientConfig:
    with tempfile.TemporaryDirectory() as td:
        path = Path(td) / "config.yml"
        path.write_text(text, encoding="utf-8")
        return LightClientConfig.from_file(path)


if __name__ == "__main__":
    unittest.main()
