# === work-package-B/C (ccqwen) ===
"""Canonical-fixture recomputation checker (B/C branch, spec §4.1 + §2.5).

Independent consumption of the frozen canonical fixture
(pythonSandboxService/tests/fixtures/finance-record-channel-v1.json,
SHA-256 19559b46… at CONTRACT_BASE_SHA 7c695371): this test recomputes every
§4.1 byte/digest fact with its own inline implementation and asserts the
fixture's expected values, instead of trusting them or generating a second
source of truth (codex directive msg 7e8182c9).

The inline digest code is deliberately the reference for the wrapper's record
channel: rawDigest = SHA-256(rawPayload UTF-8), recordDigest = SHA-256 over
per-record [4-byte big-endian length || rawPayload] in emission order, empty
batch = SHA-256(b"").
"""
import hashlib
import json
import os
import struct
import unittest

MARKER = "__AF_FINANCE_RESULT_v1__"
FAMILY_PREFIX = "__AF_FINANCE_RESULT_"

_FIXTURE_PATH = os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "fixtures",
    "finance-record-channel-v1.json",
)


def raw_digest(payload: str) -> str:
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def record_digest(payloads) -> str:
    h = hashlib.sha256()
    for payload in payloads:
        data = payload.encode("utf-8")
        h.update(struct.pack(">I", len(data)))
        h.update(data)
    return h.hexdigest()


def split_lines(stdout_lines):
    """Return (ordinary_lines, record_payloads) classified by the fixed marker.

    Classification is by exact v1 marker only; family-prefix lines of unknown
    versions are neither ordinary stdout nor v1 records (audit bucket) — the
    canonical fixture contains none, so any such line here is a failure.
    """
    ordinary = []
    payloads = []
    for line in stdout_lines:
        if line.startswith(MARKER):
            payloads.append(line[len(MARKER):])
        elif line.startswith(FAMILY_PREFIX):
            raise AssertionError(f"unexpected unknown-version marker line: {line!r}")
        else:
            ordinary.append(line)
    return ordinary, payloads


class FinanceRecordDigestRecomputationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with open(_FIXTURE_PATH, encoding="utf-8") as fh:
            cls.fixture = json.load(fh)
        cls.by_name = {case["case"]: case for case in cls.fixture["cases"]}

    def test_fixture_schema_version_is_1(self):
        self.assertEqual(self.fixture["schemaVersion"], "1")

    def test_all_cases_recompute(self):
        for case in self.fixture["cases"]:
            with self.subTest(case=case["case"]):
                expected = case["expected"]
                ordinary, payloads = split_lines(case["stdoutLines"])
                self.assertEqual(len(payloads), expected["resultRecordCount"])
                self.assertEqual(
                    sum(len(p.encode("utf-8")) for p in payloads),
                    expected["emittedRecordBytes"],
                )
                self.assertEqual([raw_digest(p) for p in payloads], expected["rawDigests"])
                self.assertEqual(record_digest(payloads), expected["recordDigest"])
                self.assertTrue(expected["recordSetComplete"])
                self.assertEqual("\n".join(ordinary), expected["ordinaryStdout"])

    def test_spec_2_5_example_constants(self):
        """Spec §2.5: the CAGR example is pinned to 401 bytes and two digests."""
        expected = self.by_name["one-valid-cagr-result"]["expected"]
        self.assertEqual(expected["emittedRecordBytes"], 401)
        self.assertEqual(
            expected["rawDigests"],
            ["eb4382d97e74ff45f9b2a28d967f44af2f083404ac535287e70bc1d9e36a8a20"],
        )
        self.assertEqual(
            expected["recordDigest"],
            "d8df42125c1d75224cb9a91b7e254c9dedd342bcca4084ab66bfa2979396bdb9",
        )

    def test_mixed_batch_constants_and_order_sensitivity(self):
        """§4.1: batch digest is order-dependent (length-prefixed concatenation)."""
        case = self.by_name["two-valid-results-preserve-order"]
        _, payloads = split_lines(case["stdoutLines"])
        self.assertEqual(len(payloads), 2)
        self.assertEqual(case["expected"]["emittedRecordBytes"], 759)
        self.assertEqual(
            case["expected"]["recordDigest"],
            "eda820b1ccf54b2d58d9d41d97298eb2af5e1c291e21c94855571ccde2f804f1",
        )
        self.assertNotEqual(record_digest(payloads), record_digest(list(reversed(payloads))))

    def test_schema_invalid_case_still_transports(self):
        """Python capture transports the schema-invalid record (byte facts valid);
        schema rejection is the Java side's job (expected.schemaValid=False)."""
        case = self.by_name["one-schema-invalid-custom-result"]
        self.assertIs(case["expected"]["schemaValid"], False)
        _, payloads = split_lines(case["stdoutLines"])
        record = json.loads(payloads[0])
        self.assertNotIsInstance(record["value"], (int, float))  # "not-a-number"

    def test_empty_batch_digest_is_sha256_of_empty_bytes(self):
        self.assertEqual(record_digest([]), hashlib.sha256(b"").hexdigest())


if __name__ == "__main__":
    unittest.main()
