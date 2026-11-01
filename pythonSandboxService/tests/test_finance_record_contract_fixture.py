from __future__ import annotations

import hashlib
import json
import struct
import unittest
from pathlib import Path


MARKER = "__AF_FINANCE_RESULT_v1__"


class FinanceRecordContractFixtureTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        repo_root = Path(__file__).resolve().parents[2]
        cls.python_fixture = (
            repo_root
            / "pythonSandboxService"
            / "tests"
            / "fixtures"
            / "finance-record-channel-v1.json"
        )
        cls.java_fixture = (
            repo_root
            / "agentPlatformShared"
            / "src"
            / "test"
            / "resources"
            / "finance"
            / "finance-record-channel-v1.json"
        )

if __name__ == "__main__":
    unittest.main()
