"""Shared output-capture primitives for the §7.1 bounded execution wrapper.

Authoritative texts:
  - 金融MethodSpec-V5-源码实施与Agent分工计划 §4.1 固定标记 / §4.2 字节和摘要 —
    marker family prefix, first-version marker, rawPayload/rawDigest/batch
    recordDigest rules, and the ordinary-vs-record byte rules for stdout.
  - 金融MethodSpec-V5-源码实施与Agent分工计划 §7.1 新增执行包装器 — continuous
    capture, including the Unicode-chunk risk (line 864): multi-byte UTF-8
    codepoints split across read-chunk boundaries must reassemble byte-exact.
  - Frozen contract ``agentLangchainService/docs/finance-methodspec-v5-contract.md``
    §4.1/§4.2 (markers, byte and digest rules, lines 209-235), §5.1 (the
    snake_case HTTP ExecuteResult fields the capture summary feeds, lines
    287-313), §13 (limit key names, line 644).

Everything here is a pure/stateful stdlib primitive with no I/O of its own;
the subprocess machinery lives in ``app.bounded_exec_wrapper``.  Importing
this module must never require third-party packages (no pydantic).
"""

from __future__ import annotations

import codecs
import hashlib
import struct

__all__ = [
    "MARKER_FAMILY_PREFIX",
    "MARKER_V1_PREFIX",
    "MARKER_FAMILY_PREFIX_BYTES",
    "MARKER_V1_PREFIX_BYTES",
    "IncrementalUtf8Decoder",
    "split_lines_keepends",
    "raw_digest",
    "record_batch_digest",
    "bounded_ordinary_stdout",
]

# §4.1 标记族前缀 / 首版标记 (contract lines 213-220).
MARKER_FAMILY_PREFIX = "__AF_FINANCE_RESULT_"
MARKER_V1_PREFIX = "__AF_FINANCE_RESULT_v1__"

MARKER_FAMILY_PREFIX_BYTES = MARKER_FAMILY_PREFIX.encode("utf-8")
MARKER_V1_PREFIX_BYTES = MARKER_V1_PREFIX.encode("utf-8")


class IncrementalUtf8Decoder:
    """Incrementally decode UTF-8 chunks with byte-exact reassembly.

    Multi-byte codepoints split across ``feed()`` chunk boundaries are held in
    an internal buffer until complete (§7.1 风险清单, Unicode 分块); a partial
    tail is never replaced or corrupted between ``feed()`` calls.  Definitively
    invalid bytes decode to U+FFFD so a malformed user byte stream can never
    raise inside the capture path — well-formed streams (the contract case)
    reassemble bit-for-bit.
    """

    __slots__ = ("_decoder",)

    def __init__(self) -> None:
        # ``errors="replace"`` applies only to definitively invalid sequences;
        # an incomplete trailing sequence is retained by the incremental
        # decoder across feed() calls until it completes or flush() finalizes.
        self._decoder = codecs.getincrementaldecoder("utf-8")(errors="replace")

    def feed(self, chunk: bytes) -> str:
        """Decode the next chunk, buffering a trailing partial codepoint."""
        if not chunk:
            return ""
        return self._decoder.decode(bytes(chunk), False)

    def flush(self) -> str:
        """Finalize the stream; a dangling partial codepoint becomes U+FFFD."""
        return self._decoder.decode(b"", True)


def split_lines_keepends(raw: bytes) -> list[bytes]:
    """Split ``raw`` into lines, keeping each terminating ``b"\\n"``.

    Only ``b"\\n"`` terminates a line (never ``\\r``, ``\\x85`` etc.) so
    arbitrary binary-ish user output stays byte-exact.  Because ``0x0A`` can
    never occur inside a multi-byte UTF-8 sequence, byte-level line splitting
    is UTF-8-safe and classification by byte prefix is sound.
    """
    if not raw:
        return []
    parts = raw.split(b"\n")
    lines = [part + b"\n" for part in parts[:-1]]
    if parts[-1]:
        lines.append(parts[-1])
    return lines


def raw_digest(payload: str | bytes) -> str:
    """§4.2: ``rawDigest = SHA-256(rawPayload)`` over the UTF-8 bytes."""
    raw = payload.encode("utf-8") if isinstance(payload, str) else bytes(payload)
    return hashlib.sha256(raw).hexdigest()


def record_batch_digest(payloads) -> str:
    """§4.2 批次摘要规则 (contract lines 230-232).

    ``recordDigest = SHA-256(批次摘要输入)`` where the input is the
    concatenation, in original emission order, of
    ``uint32be(rawPayload.length) || rawPayload`` for every record.  The empty
    batch digest is SHA-256 of the empty byte string.
    """
    hasher = hashlib.sha256()
    for payload in payloads:
        raw = payload.encode("utf-8") if isinstance(payload, str) else bytes(payload)
        hasher.update(struct.pack(">I", len(raw)))
        hasher.update(raw)
    return hasher.hexdigest()


def bounded_ordinary_stdout(raw: bytes, max_ordinary_bytes: int) -> tuple[bytes, bool]:
    """§4.2 ordinary-stdout byte rule (contract line 235).

    Returns ``(bounded_stream, truncated)``:

    - Not truncated (``len(ordinary) <= max_ordinary_bytes``): the original
      bytes in the original line order — record lines stay inline exactly
      where the user code printed them.
    - Truncated: the ordinary (non marker-family) bytes capped at
      ``max_ordinary_bytes``, followed by the complete marker-family lines in
      their original relative order.  This is the ONLY reordering §4.2 allows,
      so a complete record batch stays discoverable.  Unknown-version marker
      lines are appended too — never mixed back into the bounded ordinary
      prefix (§4.1, contract line 223/234) — so the backend format audit can
      still pick them up.
    """
    if max_ordinary_bytes < 0:
        raise ValueError("max_ordinary_bytes must be >= 0")
    ordinary_parts: list[bytes] = []
    marker_lines: list[bytes] = []
    ordinary_bytes = 0
    for line in split_lines_keepends(raw):
        if line.startswith(MARKER_FAMILY_PREFIX_BYTES):
            marker_lines.append(line)
        else:
            ordinary_parts.append(line)
            ordinary_bytes += len(line)
    if ordinary_bytes <= max_ordinary_bytes:
        return raw, False
    bounded = b"".join(ordinary_parts)[:max_ordinary_bytes] + b"".join(marker_lines)
    return bounded, True
