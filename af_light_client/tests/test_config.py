from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from af_light_client.config import LightClientConfig


class LightClientConfigDebugTest(unittest.TestCase):
    pass
def _load(text: str) -> LightClientConfig:
    with tempfile.TemporaryDirectory() as td:
        path = Path(td) / "config.yml"
        path.write_text(text, encoding="utf-8")
        return LightClientConfig.from_file(path)


if __name__ == "__main__":
    unittest.main()
