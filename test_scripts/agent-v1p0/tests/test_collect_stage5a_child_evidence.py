"""只读采集映射和缺连接时的失败行为。"""

from __future__ import annotations

import importlib.util
import json
import os
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[1] / "collect_stage5a_child_evidence.py"
SPEC = importlib.util.spec_from_file_location("collect_stage5a_child_evidence", SCRIPT)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class Cursor:
    def __init__(self, rows):
        self.rows = rows
        self.query = ""
        self.args = None

    def execute(self, query, args):
        self.query = query
        self.args = args

    def fetchall(self):
        return self.rows


class CollectStage5AChildEvidenceTest(unittest.TestCase):
    pass
if __name__ == "__main__":
    unittest.main()
