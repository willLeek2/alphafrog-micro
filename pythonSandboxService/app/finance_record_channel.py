# === work-package-C (ccqwen) ===
"""§5.1 finance_record_channel write path: capture -> HTTP DTO mapping and
§4.2 bounded-stdout reassembly (Spec §7.1 steps 7-8, contract §4.2/§5.1).

The bounded execution wrapper (``app.bounded_exec_wrapper``) captures the
child's output into SEPARATE bounded files (§7.1 fixed four-file layout):
ordinary stdout (marker lines removed), stderr, v1 record payloads, and the
camelCase ``capture-result.json`` summary.  This module is the single place
that turns those artifacts back into the shapes the frozen consumer surface
requires:

* ``finance_channel_from_capture`` maps the wrapper summary (camelCase, §7.1
  wrapper layer) onto the contract §5.1 snake_case ``finance_record_channel``
  fields — presence-aware: no capture summary means the field stays absent
  (``None``), which §5.1/§5.2 define as "producer has not implemented the
  protocol", distinct from an empty-but-active batch.

* ``reassemble_bounded_stdout`` rebuilds the reportable stdout stream from
  the separated channels: the wrapper's ALREADY-CAPPED ordinary bytes first,
  then every record line re-prefixed with the v1 marker and newline-
  terminated in original emission order, appended IN FULL.  This is exactly
  the layout contract §4.2 sanctions (the ONLY reordering it allows): when
  ordinary stdout exceeded its limit, report the bounded ordinary stdout
  first, then attach the complete record lines in original order — the
  ``stdout_truncated`` flag expresses the ordinary-channel truncation, and
  the record channel has its OWN ``recordChannelMaxBytes/Records`` budget
  already enforced by the wrapper, so record lines must never consume the
  ordinary stdout budget again (a second cap here would silently drop
  complete records whenever ordinary stdout sits at its limit).  The total
  reassembled length is therefore bounded by ordinary cap + record-channel
  cap + framing.  When nothing was truncated the same layout preserves all
  original bytes (with one framing exception: when the wrapper's ordinary
  capture ends in an unterminated final line, reassembly terminates it
  before the first marker line so every record line starts on its own
  line).  Cross-channel interleaving order is not recoverable from the
  §7.1 four-file capture layout by design — each channel individually
  keeps its original order.  The record payloads reach the Java side INSIDE
  this stdout stream (F strips/parses the marker family there); proto field
  10 carries metadata only.

* ``read_capture_artifacts`` reads all four artifacts before task-directory
  cleanup (§7.1 step 7) so ``main.py`` only ever writes bounded results to
  the Task (§7.1 step 8).

Stdlib only (no pydantic) so the mapping/reassembly semantics stay testable
in every environment; the Pydantic DTO assignment lives in the caller
(work package D owns the ``FinanceRecordChannel`` class definition).
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Dict, List, Optional

from app.bounded_exec_wrapper import (
    CAPTURE_RESULT_FILE_NAME,
    RECORDS_FILE_NAME,
    STDERR_FILE_NAME,
    STDOUT_FILE_NAME,
)
from app.output_capture import MARKER_V1_PREFIX_BYTES, record_batch_digest

__all__ = [
    "CHANNEL_FIELD_ORDER",
    "finance_channel_from_capture",
    "reassemble_bounded_stdout",
    "decode_capture_text",
    "read_capture_artifacts",
]

# Contract §5.1 field order (example block, lines 293-301).
CHANNEL_FIELD_ORDER = (
    "emitted_record_count",
    "emitted_record_bytes",
    "record_set_complete",
    "drop_reason",
    "record_digest",
    "stdout_truncated",
    "stderr_truncated",
)

# capture-result.json (camelCase, §7.1) -> §5.1 snake_case, in §5.1 order.
_CAPTURE_TO_CHANNEL = (
    ("emittedRecordCount", "emitted_record_count"),
    ("emittedRecordBytes", "emitted_record_bytes"),
    ("recordSetComplete", "record_set_complete"),
    ("dropReason", "drop_reason"),
    ("recordDigest", "record_digest"),
    ("stdoutTruncated", "stdout_truncated"),
    ("stderrTruncated", "stderr_truncated"),
)

_INT_FIELDS = ("emitted_record_count", "emitted_record_bytes")
_BOOL_FIELDS = ("record_set_complete", "stdout_truncated", "stderr_truncated")
_STR_FIELDS = ("drop_reason", "record_digest")

# §4.2: the empty batch digest is SHA-256 of the empty byte string.
EMPTY_BATCH_DIGEST = (
    "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
)


def finance_channel_from_capture(capture_summary: Optional[dict]) -> Optional[Dict]:
    """Build the §5.1 snake_case ``finance_record_channel`` dict.

    Returns ``None`` when ``capture_summary`` is ``None`` (execution did not
    go through the bounded wrapper -> the DTO field stays absent; §5.1
    presence semantics).  A summary that exists must carry all seven source
    fields with correct types — the wrapper always writes them, so anything
    less is a malformed capture and raises ``ValueError`` rather than
    emitting a half-formed channel.
    """
    if capture_summary is None:
        return None
    if not isinstance(capture_summary, dict):
        raise ValueError("capture summary must be a JSON object")

    channel: Dict = {}
    for camel_key, snake_key in _CAPTURE_TO_CHANNEL:
        if camel_key not in capture_summary:
            raise ValueError(f"capture summary lacks {camel_key!r}")
        channel[snake_key] = capture_summary[camel_key]

    for key in _INT_FIELDS:
        value = channel[key]
        if isinstance(value, bool) or not isinstance(value, int) or value < 0:
            raise ValueError(f"{key} must be a non-negative integer, got {value!r}")
    for key in _BOOL_FIELDS:
        if not isinstance(channel[key], bool):
            raise ValueError(f"{key} must be a boolean, got {channel[key]!r}")
    for key in _STR_FIELDS:
        if not isinstance(channel[key], str):
            raise ValueError(f"{key} must be a string, got {channel[key]!r}")
    digest = channel["record_digest"]
    if len(digest) != 64 or any(c not in "0123456789abcdef" for c in digest):
        raise ValueError(f"record_digest must be lowercase sha256 hex, got {digest!r}")
    if channel["emitted_record_count"] == 0 and digest != EMPTY_BATCH_DIGEST:
        # §4.2/§5.2: an empty batch digests to SHA-256 of the empty bytes.
        raise ValueError("empty batch must carry the empty-bytes record digest")
    return channel


def reassemble_bounded_stdout(
    ordinary_bytes: bytes,
    record_payloads: List[bytes],
    stdout_max_bytes: int,
) -> bytes:
    """Rebuild the reportable stdout stream per contract §4.2.

    ``ordinary_bytes`` is the wrapper's ALREADY-CAPPED ordinary stdout and
    is trusted as the sole consumer of the ``stdoutMaxBytes`` budget; this
    function raises if it ever exceeds the cap (wrapper invariant) instead
    of re-capping anything.  Each record payload is re-prefixed with the v1
    marker and a terminating newline (§4.1 framing: rawPayload excludes
    both) and appended IN FULL in original emission order: records have
    their own ``recordChannelMaxBytes/Records`` budget that the wrapper
    already enforced (over-limit drops the whole batch rather than
    truncating lines), so applying the ordinary cap a second time would
    silently strip complete record lines whenever ordinary stdout sits at
    its limit — forbidden by §4.2, which says the bounded ordinary stdout
    comes first and the COMPLETE record lines follow, with the ordinary
    truncation expressed by ``stdout_truncated=true``.  The total length is
    therefore at most ordinary cap + record-channel cap + framing.
    """
    if stdout_max_bytes < 0:
        raise ValueError("stdout_max_bytes must be >= 0")
    ordinary = bytes(ordinary_bytes)
    if len(ordinary) > stdout_max_bytes:
        raise ValueError(
            "ordinary stdout bytes exceed stdout_max_bytes; the bounded "
            "wrapper must cap ordinary stdout before reassembly"
        )
    stream = ordinary
    if record_payloads and ordinary and not ordinary.endswith(b"\n"):
        # Channel-separation framing: the wrapper flushes a child's final
        # unterminated line as ordinary bytes without adding a newline, so
        # the ordinary capture can end mid-line.  Every re-marked record
        # line must START on its own line — otherwise consumer-side marker
        # detection sees a mid-line marker (framing violation) and the
        # transport check rejects the record block.  Terminating the
        # ordinary tail here is part of the §4.2 reassembly layout, not a
        # mutation of the ordinary bytes themselves.
        stream += b"\n"
    for payload in record_payloads:
        payload = bytes(payload)
        if b"\n" in payload:
            raise ValueError("record payload must be a single line (no newline)")
        stream += MARKER_V1_PREFIX_BYTES + payload + b"\n"
    return stream


def decode_capture_text(data: bytes) -> str:
    """Decode captured bytes for the str-typed HTTP fields.

    Well-formed UTF-8 (the contract case) round-trips exactly; definitively
    invalid bytes become U+FFFD, mirroring the wrapper-side
    IncrementalUtf8Decoder policy so a malformed user byte stream can never
    break the write path.
    """
    return bytes(data).decode("utf-8", errors="replace")


def _verify_capture_consistency(summary: Dict, record_payloads: List[bytes]) -> None:
    """Cross-check the on-disk record channel against the summary.

    The write path must never report a record set as complete while the
    artifacts silently disagree (lost/short/extra/tampered records): the
    wrapper is the only producer of these four files and always keeps them
    consistent, so ANY divergence means a corrupt capture and raises
    ``ValueError`` (the caller fails the task instead of forwarding a
    half-formed channel to Java).

    * ``recordSetComplete=true``: the records file must hold exactly
      ``emittedRecordCount`` lines totalling ``emittedRecordBytes`` raw
      bytes, and their §4.2 batch digest must equal ``recordDigest``
      (covers content corruption, not just counts).
    * ``recordSetComplete=false``: the wrapper deleted the batch file on
      the over-limit drop, so no record payload may remain on disk, and a
      non-empty ``dropReason`` must explain the drop.
    """
    complete = summary["recordSetComplete"]
    declared_count = summary["emittedRecordCount"]
    declared_bytes = summary["emittedRecordBytes"]
    declared_digest = summary["recordDigest"]
    drop_reason = summary["dropReason"]

    if complete:
        if drop_reason != "":
            raise ValueError(
                "capture inconsistent: recordSetComplete=true but dropReason "
                f"is non-empty ({drop_reason!r})"
            )
        if len(record_payloads) != declared_count:
            raise ValueError(
                "capture inconsistent: summary declares emittedRecordCount="
                f"{declared_count} but the records file holds "
                f"{len(record_payloads)} record line(s)"
            )
        actual_bytes = sum(len(payload) for payload in record_payloads)
        if actual_bytes != declared_bytes:
            raise ValueError(
                "capture inconsistent: summary declares emittedRecordBytes="
                f"{declared_bytes} but the records file holds {actual_bytes}"
            )
        actual_digest = record_batch_digest(record_payloads)
        if actual_digest != declared_digest:
            raise ValueError(
                "capture inconsistent: recordDigest mismatch (summary "
                f"{declared_digest}, artifacts {actual_digest})"
            )
    else:
        if not drop_reason:
            raise ValueError(
                "capture inconsistent: recordSetComplete=false requires a "
                "non-empty dropReason"
            )
        if record_payloads:
            raise ValueError(
                "capture inconsistent: recordSetComplete=false means the "
                "wrapper deleted the batch file, but "
                f"{len(record_payloads)} record line(s) remain on disk"
            )


def read_capture_artifacts(capture_dir, *, stdout_max_bytes: int) -> Dict:
    """Read the wrapper's bounded outputs (§7.1 step 7, BEFORE cleanup).

    Returns a dict with:
      ``exit_code``    child exit code from the summary
      ``stdout_bytes`` reassembled §4.2 bounded stream (ordinary + records)
      ``stderr_bytes`` bounded stderr bytes
      ``channel``      §5.1 snake_case finance_record_channel dict
      ``summary``      the raw camelCase capture-result.json payload

    Raises ``ValueError`` when ``capture-result.json`` is missing or
    malformed (a wrapper run must always end by writing it, so absence
    means the capture itself failed and the caller must treat the run as
    such — never fabricate a channel), or when the on-disk record channel
    contradicts the summary (see ``_verify_capture_consistency``): records
    are never silently lost while the channel claims completeness.
    """
    capture_path = Path(capture_dir)
    summary_file = capture_path / CAPTURE_RESULT_FILE_NAME
    try:
        raw_summary = summary_file.read_text(encoding="utf-8")
    except OSError as exc:
        raise ValueError(
            f"capture summary {summary_file} is unreadable: {exc}"
        ) from exc
    try:
        summary = json.loads(raw_summary)
    except ValueError as exc:
        raise ValueError(f"capture summary is not valid JSON: {exc}") from exc
    if not isinstance(summary, dict):
        raise ValueError("capture summary must be a JSON object")
    if "exitCode" not in summary:
        raise ValueError("capture summary lacks exitCode")

    def _read_bytes(name: str) -> bytes:
        path = capture_path / name
        try:
            return path.read_bytes()
        except FileNotFoundError:
            return b""
        except OSError as exc:
            raise ValueError(f"capture file {path} is unreadable: {exc}") from exc

    stdout_bin = _read_bytes(STDOUT_FILE_NAME)
    stderr_bin = _read_bytes(STDERR_FILE_NAME)

    record_payloads: List[bytes] = []
    records_raw = _read_bytes(RECORDS_FILE_NAME)
    if records_raw:
        lines = records_raw.split(b"\n")
        if lines and lines[-1] == b"":
            lines = lines[:-1]
        record_payloads = [line for line in lines if line]

    channel = finance_channel_from_capture(summary)
    _verify_capture_consistency(summary, record_payloads)
    stdout_view = reassemble_bounded_stdout(
        stdout_bin, record_payloads, stdout_max_bytes
    )
    return {
        "exit_code": summary["exitCode"],
        "stdout_bytes": stdout_view,
        "stderr_bytes": stderr_bin,
        "channel": channel,
        "summary": summary,
    }
