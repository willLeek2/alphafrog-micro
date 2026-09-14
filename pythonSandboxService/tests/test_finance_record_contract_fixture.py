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

    def test_python_and_java_fixtures_are_byte_identical(self) -> None:
        self.assertEqual(
            self.python_fixture.read_bytes(),
            self.java_fixture.read_bytes(),
        )

    def test_expected_bytes_and_digests_match_raw_payloads(self) -> None:
        fixture = json.loads(self.python_fixture.read_text(encoding="utf-8"))

        for case in fixture["cases"]:
            with self.subTest(case=case["case"]):
                payloads = [
                    line[len(MARKER) :].encode("utf-8")
                    for line in case["stdoutLines"]
                    if line.startswith(MARKER)
                ]
                expected = case["expected"]
                ordinary_stdout = "\n".join(
                    line
                    for line in case["stdoutLines"]
                    if not line.startswith(MARKER)
                )

                self.assertEqual(ordinary_stdout, expected["ordinaryStdout"])
                self.assertEqual(len(payloads), expected["resultRecordCount"])
                self.assertEqual(
                    sum(len(payload) for payload in payloads),
                    expected["emittedRecordBytes"],
                )
                self.assertEqual(
                    [hashlib.sha256(payload).hexdigest() for payload in payloads],
                    expected["rawDigests"],
                )

                digest_input = b"".join(
                    struct.pack(">I", len(payload)) + payload for payload in payloads
                )
                self.assertEqual(
                    hashlib.sha256(digest_input).hexdigest(),
                    expected["recordDigest"],
                )

    def test_fixture_covers_non_annual_and_schema_invalid_records(self) -> None:
        fixture = json.loads(self.python_fixture.read_text(encoding="utf-8"))
        cases = {case["case"]: case for case in fixture["cases"]}

        non_annual_payload = cases["one-valid-custom-non-annual-result"][
            "stdoutLines"
        ][1][len(MARKER) :]
        non_annual_record = json.loads(non_annual_payload)
        self.assertEqual(37, non_annual_record["parameters"]["lookbackTradingDays"])
        self.assertNotIn("periods", non_annual_record["parameters"])
        self.assertFalse(
            cases["one-schema-invalid-custom-result"]["expected"]["schemaValid"]
        )


if __name__ == "__main__":
    unittest.main()
