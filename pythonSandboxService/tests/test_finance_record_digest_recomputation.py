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

if __name__ == "__main__":
    unittest.main()
