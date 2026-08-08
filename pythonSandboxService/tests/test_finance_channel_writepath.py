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
    # The full frozen 13-key capture-result.json shape: the ten §7.1 fields
    # plus the three internal unknown-marker audit counters (§4.1/§4.2).
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
    }
    base.update(overrides)
    return base


class TestChannelMapping(unittest.TestCase):
    def test_full_summary_maps_to_snake_case_in_5_1_order(self):
        channel = finance_channel_from_capture(_summary())
        self.assertEqual(list(channel.keys()), list(CHANNEL_FIELD_ORDER))
        self.assertEqual(
            channel,
            {
                "emitted_record_count": 1,
                "emitted_record_bytes": 401,
                "record_set_complete": True,
                "drop_reason": "",
                "record_digest": (
                    "d8df42125c1d75224cb9a91b7e254c9dedd342bcca4084ab66bfa2979396bdb9"
                ),
                "stdout_truncated": False,
                "stderr_truncated": False,
            },
        )

    def test_none_summary_is_presence_aware_absent(self):
        # §5.1/§5.2: no capture -> field absent (producer predates protocol);
        # this is NOT the empty-but-active batch.
        self.assertIsNone(finance_channel_from_capture(None))

    def test_empty_batch_uses_empty_bytes_digest(self):
        channel = finance_channel_from_capture(
            _summary(
                emittedRecordCount=0,
                emittedRecordBytes=0,
                recordDigest=EMPTY_BATCH_DIGEST,
            )
        )
        self.assertEqual(channel["emitted_record_count"], 0)
        self.assertEqual(channel["record_digest"], EMPTY_BATCH_DIGEST)
        self.assertTrue(channel["record_set_complete"])

    def test_missing_source_field_raises(self):
        for key in (
            "emittedRecordCount",
            "emittedRecordBytes",
            "recordSetComplete",
            "dropReason",
            "recordDigest",
            "stdoutTruncated",
            "stderrTruncated",
        ):
            with self.subTest(key=key):
                summary = _summary()
                del summary[key]
                with self.assertRaisesRegex(ValueError, key):
                    finance_channel_from_capture(summary)

    def test_malformed_types_raise(self):
        cases = {
            "bool count": _summary(emittedRecordCount=True),
            "negative count": _summary(emittedRecordCount=-1),
            "float bytes": _summary(emittedRecordBytes=401.0),
            "non-bool complete": _summary(recordSetComplete="yes"),
            "non-str dropReason": _summary(dropReason=None),
            "short digest": _summary(recordDigest="abc"),
            "uppercase digest": _summary(recordDigest="D" * 64),
            "empty-batch wrong digest": _summary(
                emittedRecordCount=0,
                emittedRecordBytes=0,
                recordDigest="a" * 64,
            ),
        }
        for name, summary in cases.items():
            with self.subTest(case=name):
                with self.assertRaises(ValueError):
                    finance_channel_from_capture(summary)

    def test_non_object_summary_raises(self):
        with self.assertRaises(ValueError):
            finance_channel_from_capture([1, 2, 3])


class TestReassembly(unittest.TestCase):
    def test_no_records_returns_ordinary_unchanged(self):
        ordinary = b"rows=5\n"
        self.assertEqual(
            reassemble_bounded_stdout(ordinary, [], 1048576), ordinary
        )

    def test_records_are_remarked_and_appended_in_order(self):
        ordinary = b"head\n"
        payloads = [b'{"schemaVersion":"1","value":1}', b'{"second":true}']
        result = reassemble_bounded_stdout(ordinary, payloads, 1048576)
        self.assertEqual(
            result,
            ordinary
            + MARKER_V1_PREFIX_BYTES
            + payloads[0]
            + b"\n"
            + MARKER_V1_PREFIX_BYTES
            + payloads[1]
            + b"\n",
        )

    def test_payload_with_newline_rejected(self):
        with self.assertRaises(ValueError):
            reassemble_bounded_stdout(b"", [b'{"a":1}\n'], 1024)

    def test_records_survive_when_ordinary_is_exactly_at_cap(self):
        # §4.2: bounded ordinary stdout first, then the COMPLETE record lines.
        # The record channel has its own recordChannelMaxBytes/Records budget
        # (already enforced by the wrapper), so record lines must NOT consume
        # the ordinary stdout budget a second time.  When ordinary bytes sit
        # exactly at the cap, every record line is still appended in full and
        # the total length legitimately exceeds stdout_max_bytes.
        ordinary = b"x" * 99 + b"\n"  # exactly 100 bytes, newline-terminated
        payloads = [b'{"v":1}']
        result = reassemble_bounded_stdout(ordinary, payloads, 100)
        expected = ordinary + MARKER_V1_PREFIX_BYTES + b'{"v":1}' + b"\n"
        self.assertEqual(result, expected)
        self.assertGreater(len(result), 100)
        # Deterministic (idempotent) — re-running gives the same bytes.
        self.assertEqual(result, reassemble_bounded_stdout(ordinary, payloads, 100))

    def test_ordinary_over_cap_raises(self):
        # The wrapper is the sole consumer of the stdoutMaxBytes budget; if
        # the ordinary bytes it hands over already exceed the cap, that is a
        # wrapper invariant violation and must fail loudly, not be re-capped.
        with self.assertRaisesRegex(ValueError, "stdout_max_bytes"):
            reassemble_bounded_stdout(b"x" * 101, [], 100)

    def test_unterminated_ordinary_tail_is_separated_from_marker_lines(self):
        # The wrapper flushes a child's final unterminated line without a
        # newline.  A re-marked record line glued onto that tail would be a
        # mid-line marker (framing violation the consumer transport check
        # rejects), so reassembly terminates the ordinary tail first.
        result = reassemble_bounded_stdout(b"done", [b'{"v":1}'], 100)
        self.assertEqual(
            result, b"done\n" + MARKER_V1_PREFIX_BYTES + b'{"v":1}' + b"\n"
        )

    def test_no_separator_added_when_ordinary_ends_with_newline(self):
        result = reassemble_bounded_stdout(b"done\n", [b'{"v":1}'], 100)
        self.assertEqual(
            result, b"done\n" + MARKER_V1_PREFIX_BYTES + b'{"v":1}' + b"\n"
        )

    # --- §4.1/§4.2 unknown-marker audit segment -------------------------

    def test_unknown_lines_appended_after_known_records_verbatim(self):
        # Layout: ordinary, then known records re-marked with v1, then
        # unknown marker lines VERBATIM (original prefix kept), each
        # newline-terminated, in original order.
        ordinary = b"head\n"
        payloads = [b'{"v":1}']
        unknown = [
            b"__AF_FINANCE_RESULT_v2__" + b'{"value":9}',
            b"__AF_FINANCE_RESULT_v9__" + b'{"other":1}',
        ]
        result = reassemble_bounded_stdout(ordinary, payloads, 1048576, unknown)
        self.assertEqual(
            result,
            ordinary
            + MARKER_V1_PREFIX_BYTES
            + b'{"v":1}'
            + b"\n"
            + unknown[0]
            + b"\n"
            + unknown[1]
            + b"\n",
        )

    def test_unknown_only_batch_appended_verbatim(self):
        unknown = [b"__AF_FINANCE_RESULT_v2__" + b'{"value":9}']
        result = reassemble_bounded_stdout(b"rows=1\n", [], 1048576, unknown)
        self.assertEqual(result, b"rows=1\n" + unknown[0] + b"\n")

    def test_separator_applies_when_only_unknown_lines_follow(self):
        # The separator-newline rule for an unterminated ordinary tail
        # applies when ANY marker line follows — here only unknown lines.
        unknown = [b"__AF_FINANCE_RESULT_v2__" + b'{"value":9}']
        result = reassemble_bounded_stdout(b"done", [], 100, unknown)
        self.assertEqual(result, b"done\n" + unknown[0] + b"\n")

    def test_unknown_lines_do_not_consume_stdout_cap_a_second_time(self):
        # The record channel (joint budget, enforced by the wrapper) is
        # separate from stdoutMaxBytes: with ordinary exactly at the cap,
        # known records AND unknown lines are still appended in full.
        ordinary = b"x" * 99 + b"\n"  # exactly 100 bytes
        unknown = [b"__AF_FINANCE_RESULT_v2__" + b'{"value":9}']
        result = reassemble_bounded_stdout(ordinary, [b'{"v":1}'], 100, unknown)
        self.assertEqual(
            result,
            ordinary
            + MARKER_V1_PREFIX_BYTES
            + b'{"v":1}'
            + b"\n"
            + unknown[0]
            + b"\n",
        )
        self.assertGreater(len(result), 100)

    def test_unknown_line_with_newline_rejected(self):
        with self.assertRaises(ValueError):
            reassemble_bounded_stdout(
                b"", [], 1024, [b"__AF_FINANCE_RESULT_v2__x\ny"]
            )

    def test_empty_unknown_line_rejected(self):
        with self.assertRaises(ValueError):
            reassemble_bounded_stdout(b"", [], 1024, [b""])

    def test_reassembled_line_is_byte_identical_to_canonical_fixture(self):
        fixture = _load_fixture()
        for case in fixture["cases"]:
            expected_marker_lines = [
                line
                for line in case["stdoutLines"]
                if line.startswith(MARKER_V1_PREFIX_BYTES.decode("utf-8"))
            ]
            payloads = [
                line.encode("utf-8")[len(MARKER_V1_PREFIX_BYTES):]
                for line in expected_marker_lines
            ]
            if not payloads:
                continue
            with self.subTest(case=case.get("name", "?")):
                result = reassemble_bounded_stdout(b"", payloads, 1048576)
                expected = "".join(line + "\n" for line in expected_marker_lines)
                self.assertEqual(result, expected.encode("utf-8"))
                for payload, case_digest in zip(
                    payloads, case["expected"]["rawDigests"]
                ):
                    self.assertEqual(raw_digest(payload), case_digest)

    def test_negative_cap_rejected(self):
        with self.assertRaises(ValueError):
            reassemble_bounded_stdout(b"x", [], -1)


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

    def test_end_to_end_read_and_reassemble(self):
        payload = b'{"schemaVersion":"1","value":0.5}'
        self._write_capture(
            _summary(
                emittedRecordBytes=len(payload),
                # Consistency check recomputes the §4.2 batch digest, so the
                # summary must carry the REAL digest of the stored payloads.
                recordDigest=record_batch_digest([payload]),
            ),
            stdout=b"rows=5\n",
            stderr=b"warn\n",
            records=[payload],
        )
        artifacts = read_capture_artifacts(
            self._tmp, stdout_max_bytes=1048576, stderr_max_bytes=1048576
        )
        self.assertEqual(artifacts["exit_code"], 0)
        self.assertEqual(
            artifacts["stdout_bytes"],
            b"rows=5\n" + MARKER_V1_PREFIX_BYTES + payload + b"\n",
        )
        self.assertEqual(artifacts["stderr_bytes"], b"warn\n")
        self.assertEqual(
            artifacts["channel"]["emitted_record_bytes"], len(payload)
        )
        self.assertEqual(artifacts["summary"]["exitCode"], 0)

    def test_empty_batch_with_empty_records_file(self):
        # The wrapper ALWAYS creates finance-records.jsonl (even for a
        # 0-record batch), so the reader requires it when
        # recordSetComplete=true — here as an empty file.
        self._write_capture(
            _summary(
                emittedRecordCount=0,
                emittedRecordBytes=0,
                recordDigest=EMPTY_BATCH_DIGEST,
            ),
            stdout=b"only-ordinary\n",
            records=[],
        )
        artifacts = read_capture_artifacts(
            self._tmp, stdout_max_bytes=1048576, stderr_max_bytes=1048576
        )
        self.assertEqual(artifacts["stdout_bytes"], b"only-ordinary\n")
        self.assertEqual(artifacts["channel"]["emitted_record_count"], 0)
        self.assertEqual(artifacts["channel"]["record_digest"], EMPTY_BATCH_DIGEST)

    def test_empty_batch_without_records_file_rejected(self):
        # recordSetComplete=true but the records file is absent (even with a
        # declared 0-record batch): the wrapper always creates it, so absence
        # is corruption — fail closed.
        self._write_capture(
            _summary(
                emittedRecordCount=0,
                emittedRecordBytes=0,
                recordDigest=EMPTY_BATCH_DIGEST,
            ),
            stdout=b"only-ordinary\n",
            records=None,
        )
        with self.assertRaisesRegex(ValueError, "records file"):
            read_capture_artifacts(
                self._tmp, stdout_max_bytes=1048576, stderr_max_bytes=1048576
            )

    def test_missing_summary_raises(self):
        with self.assertRaisesRegex(ValueError, "capture summary"):
            read_capture_artifacts(self._tmp, stdout_max_bytes=1024, stderr_max_bytes=1024)

    def test_malformed_summary_raises(self):
        with open(os.path.join(self._tmp, "capture-result.json"), "w") as fh:
            fh.write("{not json")
        with self.assertRaisesRegex(ValueError, "not valid JSON"):
            read_capture_artifacts(self._tmp, stdout_max_bytes=1024, stderr_max_bytes=1024)

    def test_summary_without_exit_code_raises(self):
        summary = _summary()
        del summary["exitCode"]
        with open(os.path.join(self._tmp, "capture-result.json"), "w") as fh:
            json.dump(summary, fh)
        with self.assertRaisesRegex(ValueError, "exitCode"):
            read_capture_artifacts(self._tmp, stdout_max_bytes=1024, stderr_max_bytes=1024)

    # --- record-channel consistency (records never silently lost) ---------

    def test_complete_summary_with_missing_records_file_raises(self):
        # Declares one record but the records file is absent: must NOT be
        # reported as a successful complete write path.
        self._write_capture(
            _summary(
                emittedRecordBytes=9,
                recordDigest=record_batch_digest([b'{"v":1}']),
            ),
            stdout=b"ok\n",
            records=None,  # no finance-records.jsonl on disk
        )
        with self.assertRaisesRegex(ValueError, "records file"):
            read_capture_artifacts(self._tmp, stdout_max_bytes=1024, stderr_max_bytes=1024)

    def test_complete_summary_with_wrong_record_count_raises(self):
        payload = b'{"v":1}'
        self._write_capture(
            _summary(
                emittedRecordCount=2,  # declares two, only one on disk
                emittedRecordBytes=len(payload),
                recordDigest=record_batch_digest([payload]),
            ),
            stdout=b"ok\n",
            records=[payload],
        )
        with self.assertRaisesRegex(ValueError, "emittedRecordCount"):
            read_capture_artifacts(self._tmp, stdout_max_bytes=1024, stderr_max_bytes=1024)

    def test_complete_summary_with_wrong_record_bytes_raises(self):
        payload = b'{"v":1}'
        self._write_capture(
            _summary(
                emittedRecordBytes=len(payload) + 100,  # byte count lies
                recordDigest=record_batch_digest([payload]),
            ),
            stdout=b"ok\n",
            records=[payload],
        )
        with self.assertRaisesRegex(ValueError, "emittedRecordBytes"):
            read_capture_artifacts(self._tmp, stdout_max_bytes=1024, stderr_max_bytes=1024)

    def test_complete_summary_with_digest_mismatch_raises(self):
        payload = b'{"v":1}'
        self._write_capture(
            _summary(
                emittedRecordBytes=len(payload),
                # Shape-valid 64-hex digest that does not match the payload.
                recordDigest="ab" * 32,
            ),
            stdout=b"ok\n",
            records=[payload],
        )
        with self.assertRaisesRegex(ValueError, "recordDigest mismatch"):
            read_capture_artifacts(self._tmp, stdout_max_bytes=1024, stderr_max_bytes=1024)

    def test_incomplete_batch_with_leftover_records_file_raises(self):
        # recordSetComplete=false means the wrapper deleted the batch file;
        # finding record lines still on disk is a corrupt capture.
        payload = b'{"v":1}'
        self._write_capture(
            _summary(
                emittedRecordCount=0,
                emittedRecordBytes=0,
                recordSetComplete=False,
                dropReason="recordChannelMaxRecords exceeded: limit=0",
                recordDigest=EMPTY_BATCH_DIGEST,
            ),
            stdout=b"ok\n",
            records=[payload],
        )
        with self.assertRaisesRegex(ValueError, "deleted the batch file"):
            read_capture_artifacts(self._tmp, stdout_max_bytes=1024, stderr_max_bytes=1024)

    def test_incomplete_batch_without_drop_reason_raises(self):
        self._write_capture(
            _summary(
                emittedRecordCount=0,
                emittedRecordBytes=0,
                recordSetComplete=False,
                dropReason="",  # no explanation for the drop
                recordDigest=EMPTY_BATCH_DIGEST,
            ),
            stdout=b"ok\n",
        )
        with self.assertRaisesRegex(ValueError, "dropReason"):
            read_capture_artifacts(self._tmp, stdout_max_bytes=1024, stderr_max_bytes=1024)

    def test_complete_summary_with_nonempty_drop_reason_raises(self):
        self._write_capture(
            _summary(
                emittedRecordCount=0,
                emittedRecordBytes=0,
                recordSetComplete=True,
                dropReason="some drop reason",  # contradicts completeness
                recordDigest=EMPTY_BATCH_DIGEST,
            ),
            stdout=b"ok\n",
            records=[],
        )
        with self.assertRaisesRegex(ValueError, "dropReason"):
            read_capture_artifacts(self._tmp, stdout_max_bytes=1024, stderr_max_bytes=1024)

    def test_incomplete_batch_with_no_records_is_readable(self):
        # A legitimately dropped batch: complete=false, dropReason set, and
        # no records left on disk.  This must read through (the channel
        # surfaces the drop; Java discards the batch on complete=false).
        self._write_capture(
            _summary(
                emittedRecordCount=0,
                emittedRecordBytes=0,
                recordSetComplete=False,
                dropReason="recordChannelMaxBytes exceeded: limit=262144",
                recordDigest=EMPTY_BATCH_DIGEST,
            ),
            stdout=b"ordinary only\n",
        )
        artifacts = read_capture_artifacts(self._tmp, stdout_max_bytes=1024, stderr_max_bytes=1024)
        self.assertEqual(artifacts["stdout_bytes"], b"ordinary only\n")
        self.assertFalse(artifacts["channel"]["record_set_complete"])
        self.assertTrue(artifacts["channel"]["drop_reason"])

    # --- unknown-marker audit transport (§4.1/§4.2) ----------------------

    def test_unknown_only_batch_transport(self):
        # No known records; unknown marker lines must reach the reassembled
        # stream VERBATIM (Java audits UNSUPPORTED_MARKER_VERSION), and the
        # internal counters stay out of the §5.1 channel mapping.
        unknown = [
            b"__AF_FINANCE_RESULT_v2__" + b'{"value":9}',
            b"__AF_FINANCE_RESULT_v1__" + b'{"unterminated":true}',
        ]
        audit_bytes = sum(len(line) + 1 for line in unknown)
        self._write_capture(
            _summary(
                emittedRecordCount=0,
                emittedRecordBytes=0,
                recordDigest=EMPTY_BATCH_DIGEST,
                unknownMarkerLines=len(unknown),
                unknownMarkerBytes=audit_bytes,
            ),
            stdout=b"rows=2\n",
            stderr=b"",
            records=[],
            audit=unknown,
        )
        artifacts = read_capture_artifacts(self._tmp, stdout_max_bytes=1024, stderr_max_bytes=1024)
        self.assertEqual(
            artifacts["stdout_bytes"],
            b"rows=2\n" + unknown[0] + b"\n" + unknown[1] + b"\n",
        )
        self.assertEqual(artifacts["summary"]["unknownMarkerLines"], 2)
        self.assertEqual(artifacts["summary"]["unknownMarkerBytes"], audit_bytes)
        self.assertFalse(artifacts["summary"]["unknownMarkerTruncated"])
        # §5.1 channel stays exactly its 7 fields — no audit counters leak.
        self.assertEqual(list(artifacts["channel"].keys()), list(CHANNEL_FIELD_ORDER))
        self.assertEqual(artifacts["channel"]["emitted_record_count"], 0)

    def test_mixed_known_and_unknown_stream_layout(self):
        # Layout order: bounded ordinary stdout first, then known records
        # re-marked with the v1 marker in original order, then unknown lines
        # verbatim in original order.
        payload = b'{"schemaVersion":"1","value":0.5}'
        unknown = [b"__AF_FINANCE_RESULT_v2__" + b'{"value":9}']
        self._write_capture(
            _summary(
                emittedRecordBytes=len(payload),
                recordDigest=record_batch_digest([payload]),
                unknownMarkerLines=1,
                unknownMarkerBytes=len(unknown[0]) + 1,
            ),
            stdout=b"rows=5\n",
            stderr=b"warn\n",
            records=[payload],
            audit=unknown,
        )
        artifacts = read_capture_artifacts(self._tmp, stdout_max_bytes=1024, stderr_max_bytes=1024)
        self.assertEqual(
            artifacts["stdout_bytes"],
            b"rows=5\n"
            + MARKER_V1_PREFIX_BYTES
            + payload
            + b"\n"
            + unknown[0]
            + b"\n",
        )

    def test_unterminated_ordinary_tail_separated_from_unknown_only(self):
        # Separator-newline rule applies when ANY marker line follows.
        unknown = [b"__AF_FINANCE_RESULT_v2__" + b'{"value":9}']
        self._write_capture(
            _summary(
                emittedRecordCount=0,
                emittedRecordBytes=0,
                recordDigest=EMPTY_BATCH_DIGEST,
                unknownMarkerLines=1,
                unknownMarkerBytes=len(unknown[0]) + 1,
            ),
            stdout=b"done",  # unterminated ordinary tail
            records=[],
            audit=unknown,
        )
        artifacts = read_capture_artifacts(self._tmp, stdout_max_bytes=1024, stderr_max_bytes=1024)
        self.assertEqual(
            artifacts["stdout_bytes"], b"done\n" + unknown[0] + b"\n"
        )

    def test_unknown_lines_do_not_consume_stdout_cap_in_reader(self):
        # Ordinary sits exactly at stdout_max_bytes; known records and
        # unknown lines (joint record-channel budget, already enforced by
        # the wrapper) must still be appended in full.
        ordinary = b"x" * 1023 + b"\n"  # exactly 1024 bytes
        payload = b'{"v":1}'
        unknown = [b"__AF_FINANCE_RESULT_v2__" + b'{"value":9}']
        self._write_capture(
            _summary(
                emittedRecordBytes=len(payload),
                recordDigest=record_batch_digest([payload]),
                unknownMarkerLines=1,
                unknownMarkerBytes=len(unknown[0]) + 1,
            ),
            stdout=ordinary,
            records=[payload],
            audit=unknown,
        )
        artifacts = read_capture_artifacts(self._tmp, stdout_max_bytes=1024, stderr_max_bytes=1024)
        self.assertEqual(
            artifacts["stdout_bytes"],
            ordinary
            + MARKER_V1_PREFIX_BYTES
            + payload
            + b"\n"
            + unknown[0]
            + b"\n",
        )

    # --- fail-closed artifact checks (§7.1) ------------------------------

    def test_stdout_bin_shorter_than_declared_raises(self):
        self._write_capture(
            _summary(),
            stdout=b"ok\n",
            records=[b'{"v":1}'],
            align_byte_counters=False,  # declares ordinaryStdoutBytes=7
        )
        with self.assertRaisesRegex(ValueError, "ordinaryStdoutBytes"):
            read_capture_artifacts(self._tmp, stdout_max_bytes=1024, stderr_max_bytes=1024)

    def test_stdout_bin_longer_than_declared_raises(self):
        summary = _summary(ordinaryStdoutBytes=2)
        self._write_capture(
            summary, stdout=b"ok\n", records=[b'{"v":1}'], align_byte_counters=False
        )
        with self.assertRaisesRegex(ValueError, "ordinaryStdoutBytes"):
            read_capture_artifacts(self._tmp, stdout_max_bytes=1024, stderr_max_bytes=1024)

    def test_missing_stdout_bin_raises(self):
        summary = _summary(ordinaryStdoutBytes=0)
        os.makedirs(self._tmp, exist_ok=True)
        with open(os.path.join(self._tmp, "capture-result.json"), "w") as fh:
            json.dump(summary, fh)
        with open(os.path.join(self._tmp, "stderr.bin"), "wb") as fh:
            fh.write(b"")
        # stdout.bin deliberately NOT written
        with self.assertRaisesRegex(ValueError, "stdout.bin"):
            read_capture_artifacts(self._tmp, stdout_max_bytes=1024, stderr_max_bytes=1024)

    def test_missing_stderr_bin_raises(self):
        summary = _summary(ordinaryStdoutBytes=0)
        os.makedirs(self._tmp, exist_ok=True)
        with open(os.path.join(self._tmp, "capture-result.json"), "w") as fh:
            json.dump(summary, fh)
        with open(os.path.join(self._tmp, "stdout.bin"), "wb") as fh:
            fh.write(b"")
        # stderr.bin deliberately NOT written
        with self.assertRaisesRegex(ValueError, "stderr.bin"):
            read_capture_artifacts(self._tmp, stdout_max_bytes=1024, stderr_max_bytes=1024)

    def test_stderr_over_stderr_max_bytes_raises(self):
        stderr = b"e" * 2048
        self._write_capture(_summary(), stdout=b"ok\n", stderr=stderr, records=[])
        with self.assertRaisesRegex(ValueError, "stderr_max_bytes"):
            read_capture_artifacts(self._tmp, stdout_max_bytes=1024, stderr_max_bytes=1024)

    def test_stdout_over_stdout_max_bytes_raises(self):
        stdout = b"o" * 2048
        self._write_capture(_summary(), stdout=stdout, records=[])
        with self.assertRaisesRegex(ValueError, "stdout_max_bytes"):
            read_capture_artifacts(self._tmp, stdout_max_bytes=1024, stderr_max_bytes=1024)

    def test_audit_bytes_mismatch_raises(self):
        unknown = [b"__AF_FINANCE_RESULT_v2__" + b'{"value":9}']
        self._write_capture(
            _summary(
                emittedRecordCount=0,
                emittedRecordBytes=0,
                recordDigest=EMPTY_BATCH_DIGEST,
                unknownMarkerLines=1,
                unknownMarkerBytes=len(unknown[0]) + 99,  # byte count lies
            ),
            stdout=b"ok\n",
            records=[],
            audit=unknown,
        )
        with self.assertRaisesRegex(ValueError, "unknownMarkerBytes"):
            read_capture_artifacts(self._tmp, stdout_max_bytes=1024, stderr_max_bytes=1024)

    def test_audit_line_count_mismatch_raises(self):
        unknown = [
            b"__AF_FINANCE_RESULT_v2__" + b'{"value":9}',
            b"__AF_FINANCE_RESULT_v3__" + b'{"value":10}',
        ]
        audit_bytes = sum(len(line) + 1 for line in unknown)
        self._write_capture(
            _summary(
                emittedRecordCount=0,
                emittedRecordBytes=0,
                recordDigest=EMPTY_BATCH_DIGEST,
                unknownMarkerLines=3,  # declares three, file holds two
                unknownMarkerBytes=audit_bytes,
            ),
            stdout=b"ok\n",
            records=[],
            audit=unknown,
        )
        with self.assertRaisesRegex(ValueError, "unknownMarkerLines"):
            read_capture_artifacts(self._tmp, stdout_max_bytes=1024, stderr_max_bytes=1024)

    def test_audit_line_lacking_marker_prefix_raises(self):
        unknown = [b"not-a-marker-line-at-all"]
        self._write_capture(
            _summary(
                emittedRecordCount=0,
                emittedRecordBytes=0,
                recordDigest=EMPTY_BATCH_DIGEST,
                unknownMarkerLines=1,
                unknownMarkerBytes=len(unknown[0]) + 1,
            ),
            stdout=b"ok\n",
            records=[],
            audit=unknown,
        )
        with self.assertRaisesRegex(ValueError, "marker family prefix"):
            read_capture_artifacts(self._tmp, stdout_max_bytes=1024, stderr_max_bytes=1024)

    def test_audit_file_present_but_zero_declared_lines_raises(self):
        unknown = [b"__AF_FINANCE_RESULT_v2__" + b'{"value":9}']
        self._write_capture(
            _summary(
                emittedRecordCount=0,
                emittedRecordBytes=0,
                recordDigest=EMPTY_BATCH_DIGEST,
                unknownMarkerLines=0,
                unknownMarkerBytes=0,
            ),
            stdout=b"ok\n",
            records=[],
            audit=unknown,  # exists iff lines > 0 — violation
        )
        with self.assertRaisesRegex(ValueError, "unknownMarkerLines=0"):
            read_capture_artifacts(self._tmp, stdout_max_bytes=1024, stderr_max_bytes=1024)

    def test_declared_audit_lines_but_missing_file_raises(self):
        self._write_capture(
            _summary(
                emittedRecordCount=0,
                emittedRecordBytes=0,
                recordDigest=EMPTY_BATCH_DIGEST,
                unknownMarkerLines=1,
                unknownMarkerBytes=10,
            ),
            stdout=b"ok\n",
            records=[],
            audit=None,  # file absent despite declared lines
        )
        with self.assertRaisesRegex(ValueError, "unknown-marker"):
            read_capture_artifacts(self._tmp, stdout_max_bytes=1024, stderr_max_bytes=1024)

    def test_zero_audit_lines_but_nonzero_audit_bytes_raises(self):
        self._write_capture(
            _summary(
                emittedRecordCount=0,
                emittedRecordBytes=0,
                recordDigest=EMPTY_BATCH_DIGEST,
                unknownMarkerLines=0,
                unknownMarkerBytes=5,  # contradicts zero lines
            ),
            stdout=b"ok\n",
            records=[],
        )
        with self.assertRaisesRegex(ValueError, "unknownMarkerBytes"):
            read_capture_artifacts(self._tmp, stdout_max_bytes=1024, stderr_max_bytes=1024)

    def test_incomplete_batch_with_leftover_empty_records_file_raises(self):
        # recordSetComplete=false means the wrapper DELETED the batch file;
        # even an EMPTY leftover finance-records.jsonl is corruption.
        self._write_capture(
            _summary(
                emittedRecordCount=0,
                emittedRecordBytes=0,
                recordSetComplete=False,
                dropReason="recordChannelMaxRecords exceeded: limit=0",
                recordDigest=EMPTY_BATCH_DIGEST,
            ),
            stdout=b"ok\n",
            records=[],  # empty leftover file
        )
        with self.assertRaisesRegex(ValueError, "deleted the batch file"):
            read_capture_artifacts(self._tmp, stdout_max_bytes=1024, stderr_max_bytes=1024)

    # --- fail-closed whole-summary validation ----------------------------

    def test_exit_code_as_bool_rejected(self):
        self._write_capture(
            _summary(exitCode=True), stdout=b"ok\n", records=[]
        )
        with self.assertRaisesRegex(ValueError, "exitCode"):
            read_capture_artifacts(self._tmp, stdout_max_bytes=1024, stderr_max_bytes=1024)

    def test_counter_as_bool_rejected(self):
        self._write_capture(
            _summary(unknownMarkerLines=True), stdout=b"ok\n", records=[]
        )
        with self.assertRaisesRegex(ValueError, "unknownMarkerLines"):
            read_capture_artifacts(self._tmp, stdout_max_bytes=1024, stderr_max_bytes=1024)

    def test_negative_counter_rejected(self):
        self._write_capture(
            _summary(stderrBytes=-1), stdout=b"ok\n", records=[],
            align_byte_counters=False,
        )
        with self.assertRaisesRegex(ValueError, "stderrBytes"):
            read_capture_artifacts(self._tmp, stdout_max_bytes=1024, stderr_max_bytes=1024)

    def test_non_bool_truncation_flag_rejected(self):
        self._write_capture(
            _summary(unknownMarkerTruncated="yes"), stdout=b"ok\n", records=[]
        )
        with self.assertRaisesRegex(ValueError, "unknownMarkerTruncated"):
            read_capture_artifacts(self._tmp, stdout_max_bytes=1024, stderr_max_bytes=1024)

    def test_unknown_extra_summary_key_rejected(self):
        self._write_capture(
            _summary(surpriseKey=1), stdout=b"ok\n", records=[]
        )
        with self.assertRaisesRegex(ValueError, "unknown key"):
            read_capture_artifacts(self._tmp, stdout_max_bytes=1024, stderr_max_bytes=1024)

    def test_missing_summary_key_rejected(self):
        summary = _summary()
        del summary["unknownMarkerLines"]
        self._write_capture(summary, stdout=b"ok\n", records=[])
        with self.assertRaisesRegex(ValueError, "unknownMarkerLines"):
            read_capture_artifacts(self._tmp, stdout_max_bytes=1024, stderr_max_bytes=1024)

    def test_negative_caps_rejected(self):
        self._write_capture(_summary(), stdout=b"ok\n", records=[])
        with self.assertRaises(ValueError):
            read_capture_artifacts(self._tmp, stdout_max_bytes=-1, stderr_max_bytes=1024)
        with self.assertRaises(ValueError):
            read_capture_artifacts(self._tmp, stdout_max_bytes=1024, stderr_max_bytes=-1)


class TestDecode(unittest.TestCase):
    def test_valid_utf8_round_trips(self):
        text = "交易日加权\n"
        self.assertEqual(decode_capture_text(text.encode("utf-8")), text)

    def test_invalid_bytes_become_replacement_char(self):
        self.assertEqual(decode_capture_text(b"\xff\xfe"), "��")


if __name__ == "__main__":
    unittest.main()
