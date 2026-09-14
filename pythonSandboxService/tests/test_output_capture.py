"""Failing TDD skeletons for the future module ``app.output_capture``.

Authoritative spec: 金融MethodSpec-V5-源码实施与Agent分工计划
  - §7.1 新增执行包装器 — continuous stdout/stderr capture, including the
    Unicode-chunk risk called out at line 864.
  - §4.1 stdout 记录字节规则 — marker prefixes, original byte/line order when
    not truncated, and the single allowed reordering when ordinary output is
    truncated.

Import convention: ``app.output_capture`` is imported inside ``setUp`` so
every test fails loudly with ``ModuleNotFoundError`` until the implementation
lands (no repo convention exists yet for not-yet-written modules).

API pinned by these tests (final binding awaits CONTRACT_BASE_SHA):
  - ``MARKER_FAMILY_PREFIX == "__AF_FINANCE_RESULT_"`` (§4.1 标记族前缀)
  - ``MARKER_V1_PREFIX == "__AF_FINANCE_RESULT_v1__"`` (§4.1 首版标记)
  - ``IncrementalUtf8Decoder`` with ``feed(chunk: bytes) -> str`` and
    ``flush() -> str``: reassembles UTF-8 codepoints split across chunk
    boundaries without corruption.
  - ``bounded_ordinary_stdout(raw: bytes, max_ordinary_bytes: int)
    -> tuple[bytes, bool]``: returns ``(bounded_stream, truncated)`` where
    ``bounded_stream`` is the §4.1 rule applied to the captured stream.

Run: ``cd pythonSandboxService && python3 -m unittest tests.test_output_capture -v``
"""

from __future__ import annotations

import importlib
import unittest


class OutputCaptureTest(unittest.TestCase):
    """Behavioral contract for ``app.output_capture`` (spec §4.1 / §7.1)."""

    def setUp(self) -> None:
        # Import inside setUp so every test fails loudly with
        # ModuleNotFoundError until the implementation lands.
        self.capture = importlib.import_module("app.output_capture")

    # ------------------------------------------------------------------
    # tests
    # ------------------------------------------------------------------

    def test_marker_prefix_constants_match_spec(self) -> None:
        """§4.1 lines 429-430: the marker family prefix is
        ``__AF_FINANCE_RESULT_`` and the first version marker is
        ``__AF_FINANCE_RESULT_v1__``."""
        self.assertEqual(self.capture.MARKER_FAMILY_PREFIX, "__AF_FINANCE_RESULT_")
        self.assertEqual(self.capture.MARKER_V1_PREFIX, "__AF_FINANCE_RESULT_v1__")
        self.assertTrue(
            self.capture.MARKER_V1_PREFIX.startswith(self.capture.MARKER_FAMILY_PREFIX)
        )

    def test_utf8_split_across_chunk_boundaries_reassembles_byte_exact(self) -> None:
        """§7.1 风险清单 (line 864, Unicode 分块): multi-byte characters split
        across read chunk boundaries must be reassembled without corruption.
        A Chinese string is fed in 5-byte chunks, which necessarily split
        3-byte UTF-8 codepoints mid-sequence."""
        text = "金融计算结果通道必须按字节无损重组"
        raw = text.encode("utf-8")
        self.assertEqual(len(raw), 3 * len(text))  # every char is 3 bytes

        decoder = self.capture.IncrementalUtf8Decoder()
        parts = []
        chunk_size = 5  # not a multiple of 3 -> cuts through codepoints
        for start in range(0, len(raw), chunk_size):
            parts.append(decoder.feed(raw[start : start + chunk_size]))
        parts.append(decoder.flush())

        reassembled = "".join(parts)
        for part in parts:
            self.assertNotIn(
                "\ufffd",
                part,
                "partial codepoint must be buffered, never replaced/corrupted",
            )
        self.assertEqual(reassembled, text)
        # Byte-exact reassembly.
        self.assertEqual(reassembled.encode("utf-8"), raw)

    def test_interleaved_ordinary_and_marker_lines_keep_relative_order_when_not_truncated(
        self,
    ) -> None:
        """§4.1 line 437: when stdout is not truncated, Python keeps the
        original bytes and original line order — ordinary prints and result
        record lines keep their relative positions."""
        payload1 = '{"sourceResolverToolCallId":"call-1"}'
        payload2 = '{"sourceResolverToolCallId":"call-2","k":"自定义"}'
        marker = self.capture.MARKER_V1_PREFIX.encode("utf-8")
        raw = (
            b"line-one\n"
            + marker
            + payload1.encode("utf-8")
            + b"\n"
            + b"line-two\n"
            + marker
            + payload2.encode("utf-8")
            + b"\n"
            + b"line-three\n"
        )

        bounded, truncated = self.capture.bounded_ordinary_stdout(raw, 10**9)

        self.assertFalse(truncated)
        self.assertEqual(bounded, raw)

    def test_truncated_ordinary_output_appends_complete_record_lines_after_bounded_stdout(
        self,
    ) -> None:
        """§4.1 line 438: when ordinary output exceeds its limit, the wrapper
        returns bounded ordinary stdout and then appends the complete record
        lines in original record order with ``stdoutTruncated=true``. This is
        the ONLY allowed reordering of the original line order (so Java can
        still discover the complete record batch)."""
        marker = self.capture.MARKER_V1_PREFIX.encode("utf-8")
        rec1_line = marker + b'{"sourceResolverToolCallId":"call-1"}' + b"\n"
        rec2_line = marker + b'{"sourceResolverToolCallId":"call-2"}' + b"\n"
        ordinary_part1 = b"A" * 300 + b"\n"  # 301 ordinary bytes
        ordinary_part2 = b"B" * 50 + b"\n"  # 51 ordinary bytes
        raw = ordinary_part1 + rec1_line + ordinary_part2 + rec2_line

        cap = 100
        bounded, truncated = self.capture.bounded_ordinary_stdout(raw, cap)

        self.assertTrue(truncated)
        # Bounded ordinary prefix (byte cap, §7.1 停止保存字节), followed by
        # the complete record lines in original record order — the single
        # reordering §4.1 allows.
        self.assertEqual(bounded, (ordinary_part1 + ordinary_part2)[:cap] + rec1_line + rec2_line)
        self.assertTrue(bounded.endswith(rec1_line + rec2_line))
        # Complete record lines only: no partial record line may be appended.
        self.assertEqual(bounded.count(marker), 2)

    def test_unknown_version_marker_family_lines_never_mix_into_ordinary_stdout(
        self,
    ) -> None:
        """§4.1 line 436: any line starting with ``__AF_FINANCE_RESULT_`` but
        carrying an unrecognized version is kept for Java's malformed-record
        audit and must never be mixed back into ordinary stdout."""
        family = self.capture.MARKER_FAMILY_PREFIX.encode("utf-8")
        v9_line = family + b'v9__{"broken":true}\n'
        ordinary_part1 = b"C" * 200 + b"\n"
        ordinary_part2 = b"D" * 200 + b"\n"
        raw = ordinary_part1 + v9_line + ordinary_part2

        cap = 100
        bounded, truncated = self.capture.bounded_ordinary_stdout(raw, cap)

        self.assertTrue(truncated)
        ordinary_prefix = bounded[:cap]
        self.assertNotIn(
            family,
            ordinary_prefix,
            "unrecognized-version marker line must not be mixed back into "
            "ordinary stdout (§4.1 line 436)",
        )
        # The line must remain discoverable (appended after the bounded
        # ordinary prefix) so Java can save it as a malformed audit record;
        # exact placement detail awaits CONTRACT_BASE_SHA.
        self.assertEqual(bounded.count(v9_line), 1)
        self.assertTrue(bounded.endswith(v9_line))


if __name__ == "__main__":
    unittest.main()
