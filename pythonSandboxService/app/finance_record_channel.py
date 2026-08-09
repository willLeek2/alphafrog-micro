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
  the separated channels, in three segments: (1) the wrapper's ALREADY-CAPPED
  ordinary bytes first, (2) then every known record line re-prefixed with the
  v1 marker and newline-terminated in original emission order, appended IN
  FULL, (3) then the unknown-marker audit lines VERBATIM (original marker
  prefix kept), each newline-terminated, in original order — so the Java side
  can audit UNSUPPORTED_MARKER_VERSION (contract §4.1/§4.2).  This is exactly
  the layout contract §4.2 sanctions (the ONLY reordering it allows): when
  ordinary stdout exceeded its limit, report the bounded ordinary stdout
  first, then attach the complete record lines in original order — the
  ``stdout_truncated`` flag expresses the ordinary-channel truncation, and
  the record channel has its OWN joint ``recordChannelMaxBytes`` /
  ``recordChannelMaxRecords`` budget already enforced by the wrapper, so
  neither known record lines nor unknown audit lines may consume the ordinary
  stdout budget again (a second cap here would silently drop complete records
  whenever ordinary stdout sits at its limit).  The total reassembled length
  is therefore bounded by ordinary cap + joint record-channel cap + framing.
  When nothing was truncated the same layout preserves all original bytes
  (with one framing exception: when the wrapper's ordinary capture ends in an
  unterminated final line, reassembly terminates it before the first marker
  line — known OR unknown — so every marker line starts on its own line).
  Cross-CLASS interleaving order (ordinary vs known-record vs unknown-marker
  lines) is not recoverable from the §7.1 four-file capture layout by design
  — each class individually keeps its original order.  The record payloads
  reach the Java side INSIDE this stdout stream (F strips/parses the marker
  family there); proto field 10 carries metadata only.

* ``read_capture_artifacts`` reads all capture artifacts before task-directory
  cleanup (§7.1 step 7) so ``main.py`` only ever writes bounded results to
  the Task (§7.1 step 8).  It is FAIL-CLOSED about artifact consistency: the
  wrapper is the sole producer of these files, so any missing/extra/tampered
  artifact (strictly typed whole-summary validation, byte-length agreement
  with the declared counters, marker-prefix checks, host-side re-validation
  of the frozen §13 record-channel caps — codex f86c66f5 / e083e181) raises
  ``ValueError`` and fails the task instead of silently forwarding a
  half-formed channel.

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
    UNKNOWN_MARKER_AUDIT_FILE_NAME,
)
from app.output_capture import (
    MARKER_FAMILY_PREFIX_BYTES,
    MARKER_V1_PREFIX_BYTES,
    record_batch_digest,
)

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
    unknown_marker_lines: List[bytes] = (),
) -> bytes:
    """Rebuild the reportable stdout stream per contract §4.2.

    Layout (the ONLY reordering §4.2 allows): bounded ordinary stdout first,
    then the known record lines re-marked with the v1 marker in original
    order, then the unknown-marker audit lines VERBATIM (original marker
    prefix kept) in original order, so the Java side can audit
    UNSUPPORTED_MARKER_VERSION (§4.1/§4.2).  Cross-class interleaving order
    is not recoverable from the §7.1 four-file layout by design.

    ``ordinary_bytes`` is the wrapper's ALREADY-CAPPED ordinary stdout and
    is trusted as the sole consumer of the ``stdoutMaxBytes`` budget; this
    function raises if it ever exceeds the cap (wrapper invariant) instead
    of re-capping anything.  Each record payload is re-prefixed with the v1
    marker and a terminating newline (§4.1 framing: rawPayload excludes
    both) and appended IN FULL in original emission order: records have
    their own joint ``recordChannelMaxBytes`` / ``recordChannelMaxRecords``
    budget that the wrapper already enforced (over-limit drops the whole
    batch rather than truncating lines), so applying the ordinary cap a
    second time would silently strip complete record lines whenever ordinary
    stdout sits at its limit — forbidden by §4.2, which says the bounded
    ordinary stdout comes first and the COMPLETE record lines follow, with
    the ordinary truncation expressed by ``stdout_truncated=true``.  Unknown
    audit lines share that same joint record-channel budget (already enforced
    by the wrapper) and likewise never consume the ordinary cap a second
    time.  The total length is therefore at most ordinary cap + joint
    record-channel cap + framing.
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
    if (
        (record_payloads or unknown_marker_lines)
        and ordinary
        and not ordinary.endswith(b"\n")
    ):
        # Channel-separation framing: the wrapper flushes a child's final
        # unterminated line as ordinary bytes without adding a newline, so
        # the ordinary capture can end mid-line.  Every marker line (a
        # re-marked known record OR an unknown audit line) must START on its
        # own line — otherwise consumer-side marker detection sees a mid-line
        # marker (framing violation) and the transport check rejects the
        # record block.  The separator applies when ANY marker line follows.
        # Terminating the ordinary tail here is part of the §4.2 reassembly
        # layout, not a mutation of the ordinary bytes themselves.
        stream += b"\n"
    for payload in record_payloads:
        payload = bytes(payload)
        if b"\n" in payload:
            raise ValueError("record payload must be a single line (no newline)")
        stream += MARKER_V1_PREFIX_BYTES + payload + b"\n"
    for line in unknown_marker_lines:
        line = bytes(line)
        if not line:
            raise ValueError("unknown marker line must not be empty")
        if b"\n" in line:
            raise ValueError("unknown marker line must be a single line")
        # VERBATIM transport: the original marker-family prefix is already
        # part of the stored line; only the terminating newline is restored.
        stream += line + b"\n"
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
    wrapper is the only producer of these files and always keeps them
    consistent, so ANY divergence means a corrupt capture and raises
    ``ValueError`` (the caller fails the task instead of forwarding a
    half-formed channel to Java).

    * ``recordSetComplete=true``: the records file (whose EXISTENCE the
      caller already enforced) must hold exactly ``emittedRecordCount``
      lines totalling ``emittedRecordBytes`` raw bytes, and their §4.2
      batch digest must equal ``recordDigest`` (covers content corruption,
      not just counts).
    * ``recordSetComplete=false``: the wrapper deleted the batch file on
      the over-limit drop (the caller enforces its absence), and a
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


# capture-result.json is wrapper-produced and frozen at exactly these keys:
# the ten §7.1 summary fields plus the three internal unknown-marker audit
# counters (§4.1/§4.2).  The reader is fail-closed: missing OR extra keys are
# corruption, never silently tolerated.
_SUMMARY_KEYS = frozenset(
    (
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
        "unknownMarkerLines",
        "unknownMarkerBytes",
        "unknownMarkerTruncated",
    )
)

# Non-negative counters; bool is rejected (bool is an int subclass in Python).
_SUMMARY_COUNT_KEYS = (
    "ordinaryStdoutBytes",
    "stderrBytes",
    "unknownMarkerLines",
    "unknownMarkerBytes",
)
_SUMMARY_BOOL_KEYS = (
    "stdoutTruncated",
    "stderrTruncated",
    "recordSetComplete",
    "unknownMarkerTruncated",
)


def _validate_summary_shape(summary: Dict) -> None:
    """Strict typed validation of the WHOLE capture-result.json summary.

    Not just key presence: every field must carry its exact type (bool is
    rejected where an int is declared, since ``isinstance(True, int)``),
    unknown/extra keys are rejected, and missing keys are rejected.  The
    wrapper is the sole producer, so any deviation means a corrupt capture.
    (The seven channel fields get their own strict check inside
    ``finance_channel_from_capture``.)
    """
    keys = set(summary)
    missing = _SUMMARY_KEYS - keys
    if missing:
        raise ValueError(
            f"capture summary lacks key(s): {', '.join(sorted(missing))}"
        )
    extra = keys - _SUMMARY_KEYS
    if extra:
        raise ValueError(
            f"capture summary has unknown key(s): {', '.join(sorted(extra))}"
        )
    exit_code = summary["exitCode"]
    if isinstance(exit_code, bool) or not isinstance(exit_code, int):
        raise ValueError(f"exitCode must be an integer, got {exit_code!r}")
    for key in _SUMMARY_COUNT_KEYS:
        value = summary[key]
        if isinstance(value, bool) or not isinstance(value, int) or value < 0:
            raise ValueError(
                f"{key} must be a non-negative integer, got {value!r}"
            )
    for key in _SUMMARY_BOOL_KEYS:
        if not isinstance(summary[key], bool):
            raise ValueError(f"{key} must be a boolean, got {summary[key]!r}")


def read_capture_artifacts(
    capture_dir,
    *,
    stdout_max_bytes: int,
    stderr_max_bytes: int,
    record_channel_max_bytes: int,
    record_channel_max_records: int,
) -> Dict:
    """Read the wrapper's bounded outputs (§7.1 step 7, BEFORE cleanup).

    Returns a dict with:
      ``exit_code``    child exit code from the summary
      ``stdout_bytes`` reassembled §4.2 bounded stream (ordinary stdout,
                       then known records re-marked with the v1 marker, then
                       unknown-marker audit lines verbatim — each class in
                       original order)
      ``stderr_bytes`` bounded stderr bytes
      ``channel``      §5.1 snake_case finance_record_channel dict (exactly
                       its 7 fields; the unknown-marker counters stay out)
      ``summary``      the raw camelCase capture-result.json payload

    FAIL-CLOSED artifact consistency (§7.1): the wrapper is the sole producer
    of these files, so anything missing/extra/tampered raises ``ValueError``
    and the caller fails the task — never silent success:

    * ``capture-result.json`` must exist, parse as a JSON object, and match
      the frozen 13-key shape with exact types (``_validate_summary_shape``);
      the seven channel fields are re-validated by
      ``finance_channel_from_capture``.
    * ``stdout.bin`` / ``stderr.bin`` MUST exist; their lengths must equal
      ``ordinaryStdoutBytes`` / ``stderrBytes`` and stay within
      ``stdout_max_bytes`` / ``stderr_max_bytes``.
    * ``finance-records.jsonl``: REQUIRED when ``recordSetComplete=true``
      (may be empty for a 0-record batch — the wrapper always creates it);
      REQUIRED ABSENT when ``recordSetComplete=false`` (the wrapper deleted
      it on the drop; even an empty leftover is corruption).  Its content
      must match the declared count/bytes/digest
      (``_verify_capture_consistency``).
    * ``finance-records-unknown-marker.jsonl`` exists IFF
      ``unknownMarkerLines > 0``; then its length equals
      ``unknownMarkerBytes``, it holds exactly ``unknownMarkerLines``
      newline-terminated lines, and every line starts with the §4.1 marker
      family prefix.  An absent audit file requires zero declared
      lines/bytes.
    * Host-side RE-validation of the frozen §13 record-channel caps (codex
      f86c66f5 / e083e181 — the container reader alone is never trusted):
      ``summary["emittedRecordCount"]`` <= ``record_channel_max_records``,
      and the records file + unknown-marker audit file raw bytes JOINTLY <=
      ``record_channel_max_bytes`` (the §4.1/§4.2 single joint budget).
    """
    if stdout_max_bytes < 0:
        raise ValueError("stdout_max_bytes must be >= 0")
    if stderr_max_bytes < 0:
        raise ValueError("stderr_max_bytes must be >= 0")
    if record_channel_max_bytes < 0:
        raise ValueError("record_channel_max_bytes must be >= 0")
    if record_channel_max_records < 0:
        raise ValueError("record_channel_max_records must be >= 0")

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

    _validate_summary_shape(summary)
    # §5.1 channel fields: strict presence + type validation (raises on any
    # malformed channel field).
    channel = finance_channel_from_capture(summary)

    # Host-side RE-validation of the frozen §13 count cap (codex f86c66f5 /
    # e083e181): never trust the container reader alone.  The wrapper keeps
    # emittedRecordCount within the count limit (an over-limit record drops
    # the whole batch), so any excess here is a tampered summary.
    if summary["emittedRecordCount"] > record_channel_max_records:
        raise ValueError(
            "capture inconsistent: summary declares emittedRecordCount="
            f"{summary['emittedRecordCount']}, over "
            f"record_channel_max_records={record_channel_max_records}"
        )

    def _read_capture_file(name: str, *, required: bool):
        path = capture_path / name
        try:
            return path.read_bytes()
        except FileNotFoundError:
            if required:
                raise ValueError(
                    f"capture file {path} is missing (corrupt capture)"
                ) from None
            return None
        except OSError as exc:
            raise ValueError(f"capture file {path} is unreadable: {exc}") from exc

    stdout_bin = _read_capture_file(STDOUT_FILE_NAME, required=True)
    if len(stdout_bin) != summary["ordinaryStdoutBytes"]:
        raise ValueError(
            "capture inconsistent: stdout.bin holds "
            f"{len(stdout_bin)} bytes but the summary declares "
            f"ordinaryStdoutBytes={summary['ordinaryStdoutBytes']}"
        )
    if len(stdout_bin) > stdout_max_bytes:
        raise ValueError(
            f"capture inconsistent: stdout.bin holds {len(stdout_bin)} "
            f"bytes, over stdout_max_bytes={stdout_max_bytes}"
        )

    stderr_bin = _read_capture_file(STDERR_FILE_NAME, required=True)
    if len(stderr_bin) != summary["stderrBytes"]:
        raise ValueError(
            "capture inconsistent: stderr.bin holds "
            f"{len(stderr_bin)} bytes but the summary declares "
            f"stderrBytes={summary['stderrBytes']}"
        )
    if len(stderr_bin) > stderr_max_bytes:
        raise ValueError(
            f"capture inconsistent: stderr.bin holds {len(stderr_bin)} "
            f"bytes, over stderr_max_bytes={stderr_max_bytes}"
        )

    # Records file: existence is dictated by recordSetComplete.
    records_raw = _read_capture_file(RECORDS_FILE_NAME, required=False)
    if summary["recordSetComplete"]:
        if records_raw is None:
            raise ValueError(
                "capture inconsistent: recordSetComplete=true requires the "
                f"records file ({RECORDS_FILE_NAME}) to exist — the wrapper "
                "always creates it, even for a 0-record batch"
            )
        if records_raw and not records_raw.endswith(b"\n"):
            raise ValueError(
                f"capture inconsistent: {RECORDS_FILE_NAME} must be "
                "newline-terminated (corrupt capture)"
            )
        record_payloads: List[bytes] = []
        if records_raw:
            record_payloads = records_raw.split(b"\n")[:-1]
    else:
        if records_raw is not None:
            raise ValueError(
                "capture inconsistent: recordSetComplete=false means the "
                f"wrapper deleted the batch file, but {RECORDS_FILE_NAME} "
                "is still present (even an empty leftover is corruption)"
            )
        record_payloads = []

    # Audit file: exists iff unknownMarkerLines > 0 (wrapper creates it
    # lazily on the first stored line).
    declared_audit_lines = summary["unknownMarkerLines"]
    declared_audit_bytes = summary["unknownMarkerBytes"]
    audit_raw = _read_capture_file(UNKNOWN_MARKER_AUDIT_FILE_NAME, required=False)
    unknown_lines: List[bytes] = []
    if declared_audit_lines > 0:
        if audit_raw is None:
            raise ValueError(
                "capture inconsistent: summary declares unknownMarkerLines="
                f"{declared_audit_lines} but {UNKNOWN_MARKER_AUDIT_FILE_NAME} "
                "is missing"
            )
        if len(audit_raw) != declared_audit_bytes:
            raise ValueError(
                "capture inconsistent: "
                f"{UNKNOWN_MARKER_AUDIT_FILE_NAME} holds {len(audit_raw)} "
                f"bytes but the summary declares unknownMarkerBytes="
                f"{declared_audit_bytes}"
            )
        if not audit_raw.endswith(b"\n"):
            raise ValueError(
                "capture inconsistent: "
                f"{UNKNOWN_MARKER_AUDIT_FILE_NAME} must be newline-terminated "
                "(corrupt capture)"
            )
        unknown_lines = audit_raw.split(b"\n")[:-1]
        if len(unknown_lines) != declared_audit_lines:
            raise ValueError(
                "capture inconsistent: "
                f"{UNKNOWN_MARKER_AUDIT_FILE_NAME} holds "
                f"{len(unknown_lines)} line(s) but the summary declares "
                f"unknownMarkerLines={declared_audit_lines}"
            )
        for line in unknown_lines:
            if not line.startswith(MARKER_FAMILY_PREFIX_BYTES):
                raise ValueError(
                    "capture inconsistent: audit line does not start with "
                    f"the marker family prefix: {line[:40]!r}"
                )
    else:
        if audit_raw is not None:
            raise ValueError(
                "capture inconsistent: unknownMarkerLines=0 but "
                f"{UNKNOWN_MARKER_AUDIT_FILE_NAME} is present"
            )
        if declared_audit_bytes != 0:
            raise ValueError(
                "capture inconsistent: unknownMarkerLines=0 but "
                f"unknownMarkerBytes={declared_audit_bytes}"
            )

    # JOINT record-channel budget re-validation (§4.1/§4.2, codex f86c66f5 /
    # e083e181): the records file and the unknown-marker audit file together
    # must stay within recordChannelMaxBytes — measured on the raw payloads
    # actually on disk, never on self-reported counters.
    joint_bytes = len(records_raw or b"") + len(audit_raw or b"")
    if joint_bytes > record_channel_max_bytes:
        raise ValueError(
            "capture inconsistent: record-channel files hold "
            f"{joint_bytes} bytes jointly, over "
            f"record_channel_max_bytes={record_channel_max_bytes}"
        )

    _verify_capture_consistency(summary, record_payloads)
    stdout_view = reassemble_bounded_stdout(
        stdout_bin, record_payloads, stdout_max_bytes, unknown_lines
    )
    return {
        "exit_code": summary["exitCode"],
        "stdout_bytes": stdout_view,
        "stderr_bytes": stderr_bin,
        "channel": channel,
        "summary": summary,
    }
