# === work-package-C (ccqwen) ===
"""Tests for the §5.1 finance_record_channel write path (Spec §7.1 steps 7-8,
contract §4.2/§5.1): capture-summary -> snake_case channel mapping, §4.2
bounded-stdout reassembly, and the capture-directory reader.

The canonical fixture pythonSandboxService/tests/fixtures/finance-record-channel-v1.json
(SHA-256 19559b46…) supplies the record payloads: reassembled lines must be
byte-identical to the fixture's stdoutLines (marker framing restored).

Run from pythonSandboxService/:

    python3 -m unittest tests.test_finance_channel_writepath -v
"""
import json
import os
import shutil
import sys
import tempfile
import unittest

_SERVICE_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _SERVICE_ROOT not in sys.path:
    sys.path.insert(0, _SERVICE_ROOT)

from app.finance_record_channel import (  # noqa: E402
    CHANNEL_FIELD_ORDER,
    EMPTY_BATCH_DIGEST,
    decode_capture_text,
    finance_channel_from_capture,
    read_capture_artifacts,
    reassemble_bounded_stdout,
)
from app.output_capture import (  # noqa: E402
    MARKER_V1_PREFIX_BYTES,
    raw_digest,
    record_batch_digest,
)

_FIXTURE = os.path.join(
    _SERVICE_ROOT, "tests", "fixtures", "finance-record-channel-v1.json"
)


def _load_fixture():
    with open(_FIXTURE, encoding="utf-8") as fh:
        return json.load(fh)


def _summary(**overrides):
    # The full frozen 14-key capture-result.json shape: the ten §7.1 fields,
    # the D11 cancelObserved cancellation-evidence flag (task #108), plus the
    # three internal unknown-marker audit counters (§4.1/§4.2).
    base = {
        "exitCode": 0,
        "ordinaryStdoutBytes": 7,
        "stderrBytes": 0,
        "stdoutTruncated": False,
        "stderrTruncated": False,
        "emittedRecordCount": 1,
        "emittedRecordBytes": 401,
        "recordSetComplete": True,
        "dropReason": "",
        "recordDigest": "d8df42125c1d75224cb9a91b7e254c9dedd342bcca4084ab66bfa2979396bdb9",
        "unknownMarkerLines": 0,
        "unknownMarkerBytes": 0,
        "unknownMarkerTruncated": False,
        "cancelObserved": False,
    }
    base.update(overrides)
    return base


class TestChannelMapping(unittest.TestCase):
    pass
class TestReassembly(unittest.TestCase):
    pass
    # --- §4.1/§4.2 unknown-marker audit segment -------------------------

class TestReadCaptureArtifacts(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.mkdtemp(prefix="af-writepath-test-")

    def tearDown(self):
        shutil.rmtree(self._tmp, ignore_errors=True)

    def _write_capture(
        self,
        summary,
        stdout=b"",
        stderr=b"",
        records=None,
        audit=None,
        align_byte_counters=True,
    ):
        """Write a capture directory.  ``records``/``audit`` are lists of
        lines (payload without marker / verbatim marker line); ``None`` means
        the file stays ABSENT.  With ``align_byte_counters`` (default) the
        declared stdout/stderr byte counters are synced to the written bytes,
        so tests can focus on the single invariant under test."""
        os.makedirs(self._tmp, exist_ok=True)
        if align_byte_counters:
            summary = dict(summary)
            summary["ordinaryStdoutBytes"] = len(stdout)
            summary["stderrBytes"] = len(stderr)
        with open(os.path.join(self._tmp, "capture-result.json"), "w") as fh:
            json.dump(summary, fh)
        with open(os.path.join(self._tmp, "stdout.bin"), "wb") as fh:
            fh.write(stdout)
        with open(os.path.join(self._tmp, "stderr.bin"), "wb") as fh:
            fh.write(stderr)
        if records is not None:
            with open(os.path.join(self._tmp, "finance-records.jsonl"), "wb") as fh:
                for payload in records:
                    fh.write(payload + b"\n")
        if audit is not None:
            with open(
                os.path.join(self._tmp, "finance-records-unknown-marker.jsonl"),
                "wb",
            ) as fh:
                for line in audit:
                    fh.write(line + b"\n")

    # --- record-channel consistency (records never silently lost) ---------

    # --- unknown-marker audit transport (§4.1/§4.2) ----------------------

    # --- fail-closed artifact checks (§7.1) ------------------------------

    # --- fail-closed whole-summary validation ----------------------------

    # --- host-side record-channel cap re-validation (§13, codex f86c66f5 /
    # e083e181: never trust the container reader alone) --------------------

class TestDecode(unittest.TestCase):
    pass
if __name__ == "__main__":
    unittest.main()
