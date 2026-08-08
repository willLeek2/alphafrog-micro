"""Failing TDD skeletons for the future module ``app.bounded_exec_wrapper``.

Authoritative spec: 金融MethodSpec-V5-源码实施与Agent分工计划
  - §7.1 新增执行包装器 — wrapper behavior plus the input/output JSON shapes
    (wrapper input keys ``scriptPath``/``timeoutSeconds``/``effectiveOutputLimits``/
    ``runtimeEnvironmentPath``; bounded files ``capture/stdout.bin``,
    ``capture/stderr.bin``, ``capture/finance-records.jsonl`` and the
    ``capture/capture-result.json`` summary).
  - §4.1 stdout 记录字节规则 — marker family ``__AF_FINANCE_RESULT_``, first
    version marker ``__AF_FINANCE_RESULT_v1__``, batch digest rule.
  - §18 停止条件 — the wrapper must never let full user stdout/stderr flow
    back into the ``session.run()`` return value.

All tests in ``BoundedExecWrapperTest`` import the module inside ``setUp`` so
they fail loudly with ``ModuleNotFoundError`` until
``pythonSandboxService/app/bounded_exec_wrapper.py`` lands.
``RecordDigestReferenceTest`` is pure §4.1 logic and must PASS even before the
wrapper exists (its reference digest helper is defined inline below).

Invocation pinned here (final binding awaits CONTRACT_BASE_SHA): the wrapper is
started as ``python3 -m app.bounded_exec_wrapper <wrapper-input.json>`` with
cwd at the pythonSandboxService root; the input JSON has exactly the §7.1
example shape and the four bounded outputs land under ``<task-dir>/capture/``.

Run: ``cd pythonSandboxService && python3 -m unittest tests.test_bounded_exec_wrapper -v``
"""

from __future__ import annotations

import hashlib
import importlib
import json
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path

SERVICE_ROOT = Path(__file__).resolve().parents[1]

MARKER_FAMILY_PREFIX = "__AF_FINANCE_RESULT_"  # §4.1 标记族前缀
MARKER_V1_PREFIX = "__AF_FINANCE_RESULT_v1__"  # §4.1 首版标记

EMPTY_BATCH_DIGEST = hashlib.sha256(b"").hexdigest()  # §4.1 空记录批次摘要

# Shape mirrors the §7.1 effectiveOutputLimits example (values are test-local).
DEFAULT_LIMITS = {
    "stdoutMaxBytes": 1024 * 1024,
    "stderrMaxBytes": 256 * 1024,
    "recordChannelMaxBytes": 256 * 1024,
    "recordChannelMaxRecords": 128,
}


class BoundedExecWrapperTest(unittest.TestCase):
    """Behavioral contract for ``app.bounded_exec_wrapper`` (spec §7.1)."""

    def setUp(self) -> None:
        # Import inside setUp so every test fails loudly with
        # ModuleNotFoundError until the implementation lands (no repo
        # convention exists yet for not-yet-written modules).
        self.wrapper = importlib.import_module("app.bounded_exec_wrapper")
        self._tmp = tempfile.TemporaryDirectory(prefix="af-wrapper-test-")
        self.task_dir = Path(self._tmp.name)

    def tearDown(self) -> None:
        if hasattr(self, "_tmp"):
            self._tmp.cleanup()

    # ------------------------------------------------------------------
    # helpers
    # ------------------------------------------------------------------

    def _write_script(self, source: str, name: str = "user_code.py") -> Path:
        script = self.task_dir / name
        script.write_text(source, encoding="utf-8")
        return script

    def _run_wrapper(
        self,
        script: Path,
        *,
        timeout_seconds: int = 30,
        limits: dict | None = None,
    ) -> subprocess.CompletedProcess:
        """Run the wrapper the way §7.1 shapes its input.

        The exact CLI binding (argv path vs stdin) awaits CONTRACT_BASE_SHA;
        the JSON payload shape below is already fixed by the §7.1 example.
        """
        merged = dict(DEFAULT_LIMITS)
        if limits:
            merged.update(limits)
        runtime_env = self.task_dir / "runtime-environment.json"
        if not runtime_env.exists():
            # The runtime-environment.json schema belongs to another work
            # package; these tests only need the path to exist.
            runtime_env.write_text("{}", encoding="utf-8")
        payload = {
            "scriptPath": str(script),
            "timeoutSeconds": timeout_seconds,
            "effectiveOutputLimits": merged,
            "runtimeEnvironmentPath": str(runtime_env),
        }
        input_path = self.task_dir / "wrapper-input.json"
        input_path.write_text(json.dumps(payload), encoding="utf-8")
        return subprocess.run(
            [sys.executable, "-m", "app.bounded_exec_wrapper", str(input_path)],
            cwd=SERVICE_ROOT,
            capture_output=True,
            timeout=120,
        )

    def _capture_dir(self) -> Path:
        return self.task_dir / "capture"

    def _read_capture_result(self) -> dict:
        with open(self._capture_dir() / "capture-result.json", encoding="utf-8") as handle:
            return json.load(handle)

    def _read_capture_bytes(self, name: str) -> bytes:
        return (self._capture_dir() / name).read_bytes()

    # ------------------------------------------------------------------
    # tests
    # ------------------------------------------------------------------

    def test_streams_are_read_separately_into_their_own_bounded_files(self) -> None:
        """§7.1 实施方式 3-4: Popen the user code, read stdout/stderr
        separately and continuously, and write ordinary stdout, stderr and
        finance record lines each to their own bounded file."""
        script = self._write_script(
            "import sys\n"
            "sys.stdout.write('hello-out\\n')\n"
            "sys.stdout.flush()\n"
            "sys.stderr.write('hello-err\\n')\n"
            "sys.stderr.flush()\n"
        )
        self._run_wrapper(script)

        stdout_bin = self._read_capture_bytes("stdout.bin")
        stderr_bin = self._read_capture_bytes("stderr.bin")
        self.assertEqual(stdout_bin, b"hello-out\n")
        self.assertEqual(stderr_bin, b"hello-err\n")
        self.assertNotIn(b"hello-err", stdout_bin)
        self.assertNotIn(b"hello-out", stderr_bin)

    def test_capture_result_summary_values_for_plain_run(self) -> None:
        """§7.1 output example + §4.1: a small plain run produces exitCode 0,
        exact byte counters, no truncation, an empty record batch and the
        empty-batch digest (SHA-256 of empty bytes)."""
        script = self._write_script(
            "import sys\n"
            "sys.stdout.write('hello\\n')\n"
            "sys.stderr.write('warn\\n')\n"
            "sys.stdout.flush()\n"
            "sys.stderr.flush()\n"
        )
        self._run_wrapper(script)

        result = self._read_capture_result()
        self.assertEqual(result["exitCode"], 0)
        self.assertEqual(result["ordinaryStdoutBytes"], len(b"hello\n"))
        self.assertEqual(result["stderrBytes"], len(b"warn\n"))
        self.assertFalse(result["stdoutTruncated"])
        self.assertFalse(result["stderrTruncated"])
        self.assertEqual(result["emittedRecordCount"], 0)
        self.assertEqual(result["emittedRecordBytes"], 0)
        self.assertTrue(result["recordSetComplete"])
        self.assertEqual(result["dropReason"], "")
        self.assertEqual(result["recordDigest"], EMPTY_BATCH_DIGEST)
        # Internal unknown-marker counters: nothing to audit in a plain run.
        self.assertEqual(result["unknownMarkerLines"], 0)
        self.assertEqual(result["unknownMarkerBytes"], 0)
        self.assertFalse(result["unknownMarkerTruncated"])

        records_path = self._capture_dir() / "finance-records.jsonl"
        if records_path.exists():
            self.assertEqual(records_path.read_bytes(), b"")
        # The audit file is created lazily: no stored unknown lines, no file.
        self.assertFalse(
            (self._capture_dir() / "finance-records-unknown-marker.jsonl").exists()
        )

    def test_capture_result_json_matches_spec_field_shape(self) -> None:
        """§7.1 capture-result.json shape: exactly the ten frozen keys plus
        the three internal unknown-marker audit counters (§4.1/§4.2); nothing
        else may appear (the reader rejects unknown keys fail-closed)."""
        script = self._write_script("print('shape-probe')\n")
        self._run_wrapper(script)

        result = self._read_capture_result()
        # Field-name source: spec §7.1 example; final field binding awaits
        # CONTRACT_BASE_SHA.
        expected_fields = {
            "exitCode",
            "ordinaryStdoutBytes",
            "stderrBytes",
            "stdoutTruncated",
            "stderrTruncated",
            "emittedRecordCount",
            "emittedRecordBytes",
            "recordSetComplete",
            "dropReason",
            "recordDigest",
            # Internal unknown-marker counters — NOT part of the §5.1 channel.
            "unknownMarkerLines",
            "unknownMarkerBytes",
            "unknownMarkerTruncated",
        }
        self.assertEqual(set(result.keys()), expected_fields)

    def test_record_lines_classified_by_fixed_prefix_only_not_report_origin(self) -> None:
        """§7.1 实施方式 4 + §4.1 + §16.2: classification is ONLY by the fixed
        line prefix ``__AF_FINANCE_RESULT_v1__``; the wrapper does not care
        whether a record came from ``report()`` or ``report_custom()`` (both
        use the identical v1 marker). Lines that merely contain the marker
        mid-line stay ordinary."""
        payload_report = '{"sourceResolverToolCallId":"call-1","kind":"CAGR","value":0.05}'
        payload_custom = '{"sourceResolverToolCallId":"call-2","customKey":"开放参数键"}'
        script = self._write_script(
            "import sys\n"
            f"payload_report = '{payload_report}'\n"
            f"payload_custom = {json.dumps(payload_custom)}\n"
            "sys.stdout.write('plain-before\\n')\n"
            f"sys.stdout.write('{MARKER_V1_PREFIX}' + payload_report + '\\n')\n"
            f"sys.stdout.write('{MARKER_V1_PREFIX}' + payload_custom + '\\n')\n"
            f"sys.stdout.write('not-a-record {MARKER_V1_PREFIX} stays ordinary\\n')\n"
            "sys.stdout.write('plain-after\\n')\n"
            "sys.stdout.flush()\n"
        )
        self._run_wrapper(script)

        records = self._read_capture_bytes("finance-records.jsonl")
        record_lines = [
            line for line in records.decode("utf-8").split("\n") if line.strip()
        ]
        # Both report()-style and report_custom()-style payloads land in the
        # record channel, in original order, marker stripped, no trailing
        # newline inside the payload (§4.1 rawPayload definition).
        self.assertEqual(record_lines, [payload_report, payload_custom])

        stdout_bin = self._read_capture_bytes("stdout.bin")
        self.assertIn(b"plain-before\n", stdout_bin)
        self.assertIn(b"plain-after\n", stdout_bin)
        self.assertIn(b"not-a-record", stdout_bin)
        for line in stdout_bin.decode("utf-8").split("\n"):
            self.assertFalse(
                line.startswith(MARKER_FAMILY_PREFIX),
                f"marker line leaked into ordinary stdout.bin: {line!r}",
            )

    def test_stdout_over_limit_keeps_draining_but_stops_storing(self) -> None:
        """§7.1 实施方式 4: when ordinary stdout exceeds its byte limit the
        wrapper keeps draining the pipe (the child is never blocked by
        backpressure) but stops storing bytes and reports
        ``stdoutTruncated=true``. The child running to exit code 0 despite
        printing far more than the cap proves the pipe kept draining."""
        flood_lines = 20000  # 20000 * 64 bytes ~= 1.22 MiB >> 64 KiB cap
        script = self._write_script(
            "import sys\n"
            "chunk = 'x' * 63 + '\\n'\n"
            f"for _ in range({flood_lines}):\n"
            "    sys.stdout.write(chunk)\n"
            "sys.stdout.write('SENTINEL_END_OF_FLOOD\\n')\n"
            "sys.stdout.flush()\n"
        )
        cap = 65536
        self._run_wrapper(script, limits={"stdoutMaxBytes": cap})
        result = self._read_capture_result()

        # Child completed => the wrapper drained the pipe continuously.
        self.assertEqual(result["exitCode"], 0)
        self.assertTrue(result["stdoutTruncated"])
        stdout_bin = self._read_capture_bytes("stdout.bin")
        self.assertLessEqual(len(stdout_bin), cap)
        self.assertLessEqual(result["ordinaryStdoutBytes"], cap)
        # The tail of the flood was drained but never stored.
        self.assertNotIn(b"SENTINEL_END_OF_FLOOD", stdout_bin)

    def test_record_count_limit_deletes_batch_file_and_marks_incomplete(self) -> None:
        """§7.1 实施方式 5 + §17 整批放弃: exceeding the record count limit
        deletes this batch's record file and reports
        ``recordSetComplete=false`` with a non-empty ``dropReason``."""
        script = self._write_script(
            "import sys\n"
            "for i in range(5):\n"
            "    sys.stdout.write('__AF_FINANCE_RESULT_v1__'\n"
            "        + '{\"sourceResolverToolCallId\":\"call-%d\"}' % i + '\\n')\n"
            "sys.stdout.flush()\n"
        )
        self._run_wrapper(script, limits={"recordChannelMaxRecords": 2})

        result = self._read_capture_result()
        self.assertFalse(
            (self._capture_dir() / "finance-records.jsonl").exists(),
            "over-limit batch record file must be deleted (§7.1 实施方式 5)",
        )
        self.assertFalse(result["recordSetComplete"])
        self.assertNotEqual(result["dropReason"], "")

    def test_record_byte_limit_deletes_batch_file_and_marks_incomplete(self) -> None:
        """§7.1 实施方式 5: exceeding the record channel total byte limit
        also drops the whole batch (delete file, ``recordSetComplete=false``,
        non-empty ``dropReason``)."""
        script = self._write_script(
            "import sys\n"
            "payload = ('{\"sourceResolverToolCallId\":\"call-1\",\"blob\":\"'\n"
            "    + 'y' * 200 + '\"}')\n"
            "for _ in range(3):\n"
            "    sys.stdout.write('__AF_FINANCE_RESULT_v1__' + payload + '\\n')\n"
            "sys.stdout.flush()\n"
        )
        self._run_wrapper(script, limits={"recordChannelMaxBytes": 256})

        result = self._read_capture_result()
        self.assertFalse(
            (self._capture_dir() / "finance-records.jsonl").exists(),
            "over-limit batch record file must be deleted (§7.1 实施方式 5)",
        )
        self.assertFalse(result["recordSetComplete"])
        self.assertNotEqual(result["dropReason"], "")

    # ------------------------------------------------------------------
    # unknown-marker audit + the single joint recordChannelMaxBytes budget
    # (contract §4.1/§4.2)
    # ------------------------------------------------------------------

    def test_unknown_marker_lines_are_stored_verbatim_in_the_audit_file(self) -> None:
        """§4.1/§4.2: marker-family lines with an unknown version never enter
        stdout.bin or finance-records.jsonl; they are stored VERBATIM in the
        audit file so the Java side can audit UNSUPPORTED_MARKER_VERSION, and
        the internal summary counters track them."""
        unknown_v2 = MARKER_FAMILY_PREFIX + 'v2__{"value":9}'
        unknown_v9 = MARKER_FAMILY_PREFIX + 'v9__{"other":true}'
        script = self._write_script(
            "import sys\n"
            "sys.stdout.write('plain\\n')\n"
            f"sys.stdout.write('{unknown_v2}\\n')\n"
            f"sys.stdout.write('{unknown_v9}\\n')\n"
            "sys.stdout.flush()\n"
        )
        self._run_wrapper(script)

        result = self._read_capture_result()
        audit_bin = self._read_capture_bytes("finance-records-unknown-marker.jsonl")
        self.assertEqual(
            audit_bin, unknown_v2.encode() + b"\n" + unknown_v9.encode() + b"\n"
        )
        self.assertEqual(result["unknownMarkerLines"], 2)
        self.assertEqual(result["unknownMarkerBytes"], len(audit_bin))
        self.assertFalse(result["unknownMarkerTruncated"])
        # The v1 batch is untouched: empty-but-complete with frozen metrics.
        self.assertEqual(result["emittedRecordCount"], 0)
        self.assertEqual(result["emittedRecordBytes"], 0)
        self.assertTrue(result["recordSetComplete"])
        self.assertEqual(result["dropReason"], "")
        self.assertEqual(result["recordDigest"], EMPTY_BATCH_DIGEST)
        # Unknown lines never go into ordinary stdout or the records file.
        self.assertEqual(self._read_capture_bytes("stdout.bin"), b"plain\n")
        self.assertEqual(self._read_capture_bytes("finance-records.jsonl"), b"")

    def test_unterminated_v1_marker_line_goes_to_audit_verbatim(self) -> None:
        """§4.1: a v1 marker line missing its terminating newline is malformed
        and joins the unknown-marker audit bucket (with its marker prefix
        kept), while a well-formed v1 line stays a known record."""
        payload = '{"sourceResolverToolCallId":"call-1"}'
        unterminated = MARKER_V1_PREFIX + '{"unterminated":true}'
        script = self._write_script(
            "import sys\n"
            f"sys.stdout.write('{MARKER_V1_PREFIX}' + '{payload}' + '\\n')\n"
            f"sys.stdout.write('{unterminated}')\n"  # EOF without newline
            "sys.stdout.flush()\n"
        )
        self._run_wrapper(script)

        result = self._read_capture_result()
        self.assertEqual(
            self._read_capture_bytes("finance-records.jsonl"),
            payload.encode() + b"\n",
        )
        self.assertEqual(
            self._read_capture_bytes("finance-records-unknown-marker.jsonl"),
            unterminated.encode() + b"\n",
        )
        self.assertEqual(result["emittedRecordCount"], 1)
        self.assertEqual(result["unknownMarkerLines"], 1)
        self.assertEqual(
            result["unknownMarkerBytes"], len(unterminated.encode()) + 1
        )
        self.assertFalse(result["unknownMarkerTruncated"])

    def test_known_and_unknown_lines_share_one_joint_byte_budget(self) -> None:
        """§4.1/§4.2: there is ONE recordChannelMaxBytes budget for BOTH
        record-channel files; the combined stored bytes never exceed it and
        the counters account for each file as written (line + newline)."""
        unknown_line = MARKER_FAMILY_PREFIX + 'v2__{"value":9}'
        payload = '{"sourceResolverToolCallId":"call-1"}'
        cap = 200
        script = self._write_script(
            "import sys\n"
            f"sys.stdout.write('{unknown_line}\\n')\n"
            f"sys.stdout.write('{MARKER_V1_PREFIX}' + '{payload}' + '\\n')\n"
            "sys.stdout.flush()\n"
        )
        self._run_wrapper(script, limits={"recordChannelMaxBytes": cap})

        result = self._read_capture_result()
        records_bin = self._read_capture_bytes("finance-records.jsonl")
        audit_bin = self._read_capture_bytes("finance-records-unknown-marker.jsonl")
        self.assertEqual(records_bin, payload.encode() + b"\n")
        self.assertEqual(audit_bin, unknown_line.encode() + b"\n")
        # Joint accounting: stored bytes of both files together, within cap.
        self.assertEqual(result["unknownMarkerBytes"], len(audit_bin))
        self.assertEqual(result["emittedRecordBytes"], len(payload.encode()))
        self.assertLessEqual(len(records_bin) + len(audit_bin), cap)
        self.assertTrue(result["recordSetComplete"])
        self.assertFalse(result["unknownMarkerTruncated"])

    def test_unknown_lines_exhaust_joint_budget_and_drop_the_known_batch(self) -> None:
        """§4.1/§4.2: unknown audit lines consume the SAME budget.  When they
        exhaust it, the NEXT v1 record triggers the whole-batch drop — but
        the audit file is KEPT (format-audit transport is independent of the
        record batch) and the budget is frozen for both classes."""
        unknown_big = MARKER_FAMILY_PREFIX + 'v2__{"blob":"' + "u" * 100 + '"}'
        cap = len(unknown_big) + 1  # the unknown line alone fills the budget
        payload = '{"sourceResolverToolCallId":"call-1"}'
        unknown_small = MARKER_FAMILY_PREFIX + 'v3__{"late":true}'
        script = self._write_script(
            "import sys\n"
            f"sys.stdout.write('{unknown_big}\\n')\n"      # fits, fills budget
            f"sys.stdout.write('{MARKER_V1_PREFIX}' + '{payload}' + '\\n')\n"  # drop
            f"sys.stdout.write('{unknown_small}\\n')\n"    # frozen budget
            "sys.stdout.flush()\n"
        )
        self._run_wrapper(script, limits={"recordChannelMaxBytes": cap})

        result = self._read_capture_result()
        self.assertFalse(
            (self._capture_dir() / "finance-records.jsonl").exists(),
            "byte-budget drop must delete the batch file (§7.1 实施方式 5)",
        )
        self.assertFalse(result["recordSetComplete"])
        self.assertEqual(
            result["dropReason"], f"recordChannelMaxBytes exceeded: limit={cap}"
        )
        # Audit retained with only the first line; the post-drop line is out.
        self.assertEqual(
            self._read_capture_bytes("finance-records-unknown-marker.jsonl"),
            unknown_big.encode() + b"\n",
        )
        self.assertEqual(result["unknownMarkerLines"], 1)
        self.assertEqual(result["unknownMarkerBytes"], len(unknown_big.encode()) + 1)
        self.assertTrue(result["unknownMarkerTruncated"])
        # Frozen metrics: no v1 record was ever stored.
        self.assertEqual(result["emittedRecordCount"], 0)
        self.assertEqual(result["emittedRecordBytes"], 0)
        self.assertEqual(result["recordDigest"], EMPTY_BATCH_DIGEST)

    def test_count_limit_drop_keeps_unknown_lines_storing(self) -> None:
        """§7.1/§4.2: after a COUNT-only drop the joint byte budget is still
        open, so unknown audit lines keep storing while the budget allows."""
        payload = '{"sourceResolverToolCallId":"call-%d"}'
        unknown_line = MARKER_FAMILY_PREFIX + 'v2__{"value":9}'
        script = self._write_script(
            "import sys\n"
            f"sys.stdout.write('{MARKER_V1_PREFIX}' + ('{payload}' % 1) + '\\n')\n"
            f"sys.stdout.write('{MARKER_V1_PREFIX}' + ('{payload}' % 2) + '\\n')\n"  # drop
            f"sys.stdout.write('{unknown_line}\\n')\n"  # still stored
            "sys.stdout.flush()\n"
        )
        self._run_wrapper(script, limits={"recordChannelMaxRecords": 1})

        result = self._read_capture_result()
        self.assertFalse(
            (self._capture_dir() / "finance-records.jsonl").exists(),
            "count-limit drop must delete the batch file (§7.1 实施方式 5)",
        )
        self.assertFalse(result["recordSetComplete"])
        self.assertEqual(
            result["dropReason"], "recordChannelMaxRecords exceeded: limit=1"
        )
        self.assertEqual(
            self._read_capture_bytes("finance-records-unknown-marker.jsonl"),
            unknown_line.encode() + b"\n",
        )
        self.assertEqual(result["unknownMarkerLines"], 1)
        self.assertFalse(result["unknownMarkerTruncated"])

    def test_exact_boundary_joint_budget_stores_and_cap_plus_one_drops(self) -> None:
        """§4.1/§4.2 boundary semantics: combined stored bytes == cap stores
        everything; one byte over the cap (cap+1 needed) drops the batch."""
        unknown_line = MARKER_FAMILY_PREFIX + 'v2__{"value":9}'
        payload = '{"sourceResolverToolCallId":"call-1"}'
        size_unknown = len(unknown_line.encode()) + 1
        size_record = len(payload.encode()) + 1

        # Run A: cap == size_unknown + size_record (exact boundary) -> stored.
        cap = size_unknown + size_record
        script_a = self._write_script(
            "import sys\n"
            f"sys.stdout.write('{unknown_line}\\n')\n"
            f"sys.stdout.write('{MARKER_V1_PREFIX}' + '{payload}' + '\\n')\n"
            "sys.stdout.flush()\n",
            name="boundary_exact.py",
        )
        self._run_wrapper(script_a, limits={"recordChannelMaxBytes": cap})
        result = self._read_capture_result()
        self.assertTrue(result["recordSetComplete"])
        self.assertEqual(result["emittedRecordCount"], 1)
        self.assertEqual(result["unknownMarkerLines"], 1)
        self.assertFalse(result["unknownMarkerTruncated"])
        self.assertEqual(
            len(self._read_capture_bytes("finance-records.jsonl"))
            + len(self._read_capture_bytes("finance-records-unknown-marker.jsonl")),
            cap,
        )

        # Run B: same cap with one more record needing size_record bytes
        # (joint total would be cap + size_record > cap) -> whole-batch drop.
        shutil.rmtree(self._capture_dir(), ignore_errors=True)  # fresh capture
        script_b = self._write_script(
            "import sys\n"
            f"sys.stdout.write('{unknown_line}\\n')\n"
            f"sys.stdout.write('{MARKER_V1_PREFIX}' + '{payload}' + '\\n')\n"
            f"sys.stdout.write('{MARKER_V1_PREFIX}' + '{payload}' + '\\n')\n"
            "sys.stdout.flush()\n",
            name="boundary_over.py",
        )
        self._run_wrapper(script_b, limits={"recordChannelMaxBytes": cap})
        result = self._read_capture_result()
        self.assertFalse(result["recordSetComplete"])
        self.assertEqual(
            result["dropReason"], f"recordChannelMaxBytes exceeded: limit={cap}"
        )
        # The audit file survives the record-batch drop.  The byte-budget
        # drop exhausts the joint budget, so the audit truncation flag is
        # set at the drop itself — even though no further audit line
        # arrived afterwards (§4.1/§4.2).
        self.assertEqual(result["unknownMarkerLines"], 1)
        self.assertTrue(result["unknownMarkerTruncated"])
        self.assertEqual(
            self._read_capture_bytes("finance-records-unknown-marker.jsonl"),
            unknown_line.encode() + b"\n",
        )
        self.assertFalse(
            (self._capture_dir() / "finance-records.jsonl").exists()
        )

        # Run C: a lone record one byte over a tight cap -> dropped as well.
        shutil.rmtree(self._capture_dir(), ignore_errors=True)  # fresh capture
        cap_c = size_record - 1
        script_c = self._write_script(
            "import sys\n"
            f"sys.stdout.write('{MARKER_V1_PREFIX}' + '{payload}' + '\\n')\n"
            "sys.stdout.flush()\n",
            name="boundary_plus_one.py",
        )
        self._run_wrapper(script_c, limits={"recordChannelMaxBytes": cap_c})
        result = self._read_capture_result()
        self.assertFalse(result["recordSetComplete"])
        self.assertEqual(
            result["dropReason"], f"recordChannelMaxBytes exceeded: limit={cap_c}"
        )
        self.assertEqual(result["emittedRecordCount"], 0)

    def test_timeout_kills_entire_process_group_and_writes_capture_result(self) -> None:
        """§7.1 实施方式 6 + §7.1 风险清单 (line 864): on timeout the wrapper
        kills the ENTIRE process group (no orphan grandchild survives in the
        reused container) and still produces capture-result.json with a
        non-zero exit/signal translation."""
        script = self._write_script(
            "import os, subprocess, sys, time\n"
            "pid_file = os.path.join(\n"
            "    os.path.dirname(os.path.abspath(__file__)), 'grandchild.pid')\n"
            "grandchild = subprocess.Popen(\n"
            "    [sys.executable, '-c', 'import time\\nwhile True:\\n    time.sleep(1)'])\n"
            "with open(pid_file, 'w') as handle:\n"
            "    handle.write(str(grandchild.pid))\n"
            "while True:\n"
            "    time.sleep(0.1)\n"
        )
        # subprocess.run raises TimeoutExpired if the wrapper fails to
        # terminate itself around its own timeoutSeconds (§7.1 实施方式 6).
        self._run_wrapper(script, timeout_seconds=2)

        summary_path = self._capture_dir() / "capture-result.json"
        self.assertTrue(
            summary_path.exists(),
            "capture-result.json must still be produced on timeout (§7.1)",
        )
        result = self._read_capture_result()
        # Exact signal translation (negative exit code vs 128+signal) awaits
        # CONTRACT_BASE_SHA; the spec only fixes that it must be non-zero.
        self.assertNotEqual(result["exitCode"], 0)

        pid_path = self.task_dir / "grandchild.pid"
        self.assertTrue(pid_path.exists(), "user script did not record grandchild pid")
        grandchild_pid = int(pid_path.read_text(encoding="utf-8").strip())
        deadline = time.monotonic() + 10.0
        while True:
            try:
                os.kill(grandchild_pid, 0)
            except ProcessLookupError:
                break  # grandchild is gone: the whole process group was killed
            self.assertLess(
                time.monotonic(),
                deadline,
                f"grandchild pid {grandchild_pid} survived the wrapper timeout "
                "(process group was not killed, §7.1 实施方式 6)",
            )
            time.sleep(0.2)

    def test_wrapper_never_reprints_user_output_to_its_own_streams(self) -> None:
        """§7.1 实施方式 2 + §18 stop condition (line 1571): nothing
        user-produced may flow into the value ``session.run()`` returns, i.e.
        the wrapper's own stdout/stderr must not reprint user output, record
        lines, or payloads."""
        token = "AF_USER_TOKEN_9f2c7e"
        script = self._write_script(
            "import sys\n"
            f"token = '{token}'\n"
            "sys.stdout.write(token + '-out\\n')\n"
            "sys.stderr.write(token + '-err\\n')\n"
            "sys.stdout.write('__AF_FINANCE_RESULT_v1__'\n"
            "    + '{\"sourceResolverToolCallId\":\"call-1\",\"token\":\"' + token + '\"}\\n')\n"
            "sys.stdout.flush()\n"
            "sys.stderr.flush()\n"
        )
        proc = self._run_wrapper(script)

        wrapper_stdout = proc.stdout.decode("utf-8", errors="replace")
        wrapper_stderr = proc.stderr.decode("utf-8", errors="replace")
        self.assertNotIn(token, wrapper_stdout)
        self.assertNotIn(token, wrapper_stderr)
        self.assertNotIn(MARKER_V1_PREFIX, wrapper_stdout)
        self.assertNotIn(MARKER_V1_PREFIX, wrapper_stderr)


class RecordDigestReferenceTest(unittest.TestCase):
    """Pure-logic §4.1 batch-digest tests.

    These must PASS even before ``app.bounded_exec_wrapper`` exists; the
    reference helper below is the normative reading of the spec rule.
    """

    @staticmethod
    def _reference_batch_digest(payloads) -> str:
        # Reference implementation of spec §4.1 批次摘要规则:
        # batch digest = SHA-256 over the sequence of
        # [4-byte big-endian length || rawPayload UTF-8] for each record in
        # original order; empty batch = SHA-256 of empty bytes.
        hasher = hashlib.sha256()
        for payload in payloads:
            raw = payload.encode("utf-8")
            hasher.update(struct.pack(">I", len(raw)))
            hasher.update(raw)
        return hasher.hexdigest()

    def test_reference_batch_digest_matches_hardcoded_values(self) -> None:
        """§4.1: verify the reference rule against expected digests computed
        ahead of time with python3 hashlib/struct (two tiny payloads, one of
        them multi-byte UTF-8, plus the empty batch)."""
        self.assertEqual(
            self._reference_batch_digest([]),
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        )
        self.assertEqual(
            self._reference_batch_digest(['{"a":1}']),
            "3c08fd5e67b6124539ce88879c8cc4cbfd872432a07b202c8f0004a28ba1fb14",
        )
        self.assertEqual(
            self._reference_batch_digest(['{"a":1}', '{"名称":"净值"}']),
            "30c19f005bb51ff938853596a0cbd906090b2c4c131f23c43d8426615ba29d0c",
        )

    def test_wrapper_module_digest_matches_reference_when_available(self) -> None:
        """§4.1: once ``app.bounded_exec_wrapper`` lands, its batch digest
        must match the reference rule bit-for-bit. Fails with
        ModuleNotFoundError until then. Exact exported function name awaits
        CONTRACT_BASE_SHA."""
        try:
            wrapper = importlib.import_module("app.bounded_exec_wrapper")
        except ModuleNotFoundError as exc:
            self.fail(
                "app.bounded_exec_wrapper is not implemented yet (spec §7.1): "
                f"{exc}"
            )
        digest_fn = None
        for name in ("record_batch_digest", "batch_digest", "compute_record_digest"):
            candidate = getattr(wrapper, name, None)
            if callable(candidate):
                digest_fn = candidate
                break
        if digest_fn is None:
            self.fail(
                "no record batch digest function found on "
                "app.bounded_exec_wrapper; exported name awaits CONTRACT_BASE_SHA"
            )
        payloads = ['{"a":1}', '{"名称":"净值"}']
        self.assertEqual(
            digest_fn(payloads),
            self._reference_batch_digest(payloads),
        )
        self.assertEqual(digest_fn([]), EMPTY_BATCH_DIGEST)


if __name__ == "__main__":
    unittest.main()
