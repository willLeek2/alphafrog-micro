"""§7.1 bounded execution wrapper for the Python sandbox service.

Runs user code via ``subprocess.Popen`` in its OWN process group
(``start_new_session``), continuously drains stdout and stderr into SEPARATE
bounded files, splits the §4.1 finance record channel out of stdout, enforces
the four output limits from ``effectiveOutputLimits`` — while ALWAYS keeping
the pipes drained so the child never blocks on backpressure — kills the
ENTIRE process group on timeout, and writes the ``capture-result.json``
summary before any cleanup.

Invocation (pinned by ``tests/test_bounded_exec_wrapper.py``)::

    python3 -m app.bounded_exec_wrapper <wrapper-input.json>

Wrapper input (§7.1 example shape; limit key names frozen by contract §13,
line 644)::

    {
      "scriptPath": "...",
      "timeoutSeconds": 30,
      "effectiveOutputLimits": {
        "stdoutMaxBytes": ..., "stderrMaxBytes": ...,
        "recordChannelMaxBytes": ..., "recordChannelMaxRecords": ...
      },
      "runtimeEnvironmentPath": "..."
    }

``runtimeEnvironmentPath`` is accepted and deliberately ignored here: the
runtime-environment schema belongs to work package D (runtime_environment.py);
the wrapper only needs the capture semantics.

Bounded outputs land under ``<wrapper-input dir>/capture/``::

    stdout.bin                            ordinary stdout, marker lines removed,
                                          capped at stdoutMaxBytes
    stderr.bin                            capped at stderrMaxBytes
    finance-records.jsonl                 v1 rawPayloads, one per line (§4.1),
                                          marker and trailing newline stripped;
                                          DELETED when the record channel
                                          exceeds its count limit or the JOINT
                                          recordChannelMaxBytes budget
    finance-records-unknown-marker.jsonl  marker-family lines with an unknown
                                          version (plus malformed/unterminated
                                          v1 lines), kept verbatim for the
                                          backend format audit (§4.1/§4.2:
                                          Java audits UNSUPPORTED_MARKER_VERSION
                                          from these), never mixed into
                                          stdout.bin; created lazily on the
                                          first stored line, so it exists iff
                                          at least one line was stored.  Shares
                                          the SAME single recordChannelMaxBytes
                                          budget as finance-records.jsonl —
                                          the combined stored bytes of both
                                          files never exceed it.
    capture-result.json                   the §7.1 summary: the ten frozen
                                          fields plus three internal
                                          unknown-marker counters
                                          (unknownMarkerLines /
                                          unknownMarkerBytes /
                                          unknownMarkerTruncated) that stay
                                          out of the §5.1 channel mapping; the
                                          summary's values feed the §5.1
                                          snake_case
                                          ExecuteResult.finance_record_channel

Over-limit semantics (§7.1 实施方式 4-5, contract §4.1/§4.2): when a limit is
hit the wrapper keeps draining (the child must never see a full pipe) but
stops storing.  Known v1 records and unknown-marker audit lines share ONE
recordChannelMaxBytes budget (counted as written: payload+newline per known
record line, line+newline per audit line); recordChannelMaxRecords counts v1
records ONLY.  A v1 record that would exceed the count limit or the joint
byte budget drops the WHOLE v1 batch (delete finance-records.jsonl,
``recordSetComplete=false``, non-empty ``dropReason``) without failing the
task itself — but the audit file is KEPT, because format-audit transport is
independent of the record batch.  After a byte-budget drop the budget is
exhausted and BOTH classes stop storing (``unknownMarkerTruncated=true``);
after a count-only drop, unknown lines keep storing while the joint budget
allows.  The reported metrics keep their frozen meanings: emittedRecordCount/
emittedRecordBytes count v1 records only (raw payload bytes, no newline, no
marker) and recordDigest stays the §4.2 batch digest over v1 raw payloads
only.  Timeout (§7.1 实施方式 6): SIGKILL the entire process group so no orphan
grandchild survives in the reused container, then still write
``capture-result.json`` — the timeout fact is carried by the non-zero
``exitCode`` (negative signal translation, e.g. -9 for SIGKILL).

Wrapper-tail envelope (work-package-C rework, P0 fix): after the user child
exits and the capture files are finalized, the wrapper performs the bounded
readback IN MEMORY through ``capture_reader`` and emits the returned
envelope on its OWN stdout.  ``capture_reader`` is imported at wrapper
process start, BEFORE the spawn (PIN 1): the staged copy lives in the
user-writable task workspace, and binding it pre-spawn — while no adversary
is alive — is what makes it trusted; after user code exits, NOTHING located
in the task workspace is ever executed or re-imported again, so overwriting
the staged files post-spawn is harmless.  The wrapper's stdout carries
EXACTLY ONE bounded envelope JSON document and zero other bytes (PIN 2);
the wrapper still NEVER writes user output, record lines or payloads to its
own streams in readable form — the envelope is base64 and bounded by
``capture_reader._envelope_ceiling`` (§18 stop condition).  On readback
failure the wrapper writes a SHORT diagnostic (names/sizes/caps only, never
capture content, §18) to stderr and exits non-zero; the host fails closed
on the nonzero terminal.  The wrapper exit code reports wrapper success
(capture-result.json written AND envelope emitted); the child's fate lives
in capture-result.json.

Stdlib only; importable without pydantic (``app/__init__.py`` stays empty).
"""

from __future__ import annotations

import hashlib
import json
import os
import signal
import struct
import subprocess
import sys
import threading
import time
from pathlib import Path

from app.output_capture import (
    MARKER_FAMILY_PREFIX_BYTES,
    MARKER_V1_PREFIX_BYTES,
    record_batch_digest,  # noqa: F401  re-exported: §4.1 cross-check binds it here
)

# === work-package-C: PIN 1 — pre-spawn capture_reader binding ==============
# The readback module is imported HERE, at wrapper process start, BEFORE the
# user child is spawned.  The staged capture_reader.py lives in the
# user-writable task workspace, so user code may overwrite it while it runs;
# this top-level import binds the trusted copy into memory while no
# adversary is alive.  After the child exits the wrapper calls the IN-MEMORY
# module only — it must NEVER lazily (re-)import it and NEVER execute
# anything from the task workspace again; overwriting the staged file
# post-spawn is harmless.
from app import capture_reader
# === end work-package-C =====================================================

__all__ = [
    "CAPTURE_DIR_NAME",
    "STDOUT_FILE_NAME",
    "STDERR_FILE_NAME",
    "RECORDS_FILE_NAME",
    "UNKNOWN_MARKER_AUDIT_FILE_NAME",
    "CAPTURE_RESULT_FILE_NAME",
    "WrapperInputError",
    "record_batch_digest",
    "parse_wrapper_input",
    "run_bounded_capture",
    "main",
]

CAPTURE_DIR_NAME = "capture"
STDOUT_FILE_NAME = "stdout.bin"
STDERR_FILE_NAME = "stderr.bin"
RECORDS_FILE_NAME = "finance-records.jsonl"
UNKNOWN_MARKER_AUDIT_FILE_NAME = "finance-records-unknown-marker.jsonl"
CAPTURE_RESULT_FILE_NAME = "capture-result.json"

# Contract §13 line 644: the four frozen Python-side limit snapshot keys.
LIMIT_KEYS = (
    "stdoutMaxBytes",
    "stderrMaxBytes",
    "recordChannelMaxBytes",
    "recordChannelMaxRecords",
)

# Pipe read size: large enough to keep a flooding child from ever filling the
# 64 KiB pipe buffer between scheduler turns.
_READ_CHUNK_SIZE = 1024 * 1024

_EMPTY_BATCH_DIGEST = hashlib.sha256(b"").hexdigest()


class WrapperInputError(ValueError):
    """The wrapper-input.json file is missing or malformed."""


class _BoundedByteSink:
    """Byte sink storing at most ``max_bytes``.

    After the cap is hit, further ``write()`` calls are accepted and discarded
    (the drain loop must never apply backpressure to the child): ``truncated``
    records that stored bytes were left out, ``stored_bytes`` counts only what
    was actually written to the file.
    """

    __slots__ = ("_file", "_max_bytes", "stored_bytes", "truncated")

    def __init__(self, fileobj, max_bytes: int) -> None:
        self._file = fileobj
        self._max_bytes = max_bytes
        self.stored_bytes = 0
        self.truncated = False

    def write(self, data: bytes) -> None:
        if not data:
            return
        room = self._max_bytes - self.stored_bytes
        if room <= 0:
            self.truncated = True
            return
        if len(data) > room:
            data = data[:room]
            self.truncated = True
        self._file.write(data)
        self.stored_bytes += len(data)


class _JointByteBudget:
    """The SINGLE ``recordChannelMaxBytes`` budget shared by both record-channel
    files (contract §4.1/§4.2).

    Known v1 record lines (``finance-records.jsonl``) and unknown-marker audit
    lines (``finance-records-unknown-marker.jsonl``) draw from the SAME budget;
    the combined bytes stored across both files never exceed ``max_bytes``.
    Bytes are counted exactly as written to the files: ``len(payload) + 1``
    (payload + newline) per known record line, ``len(line) + 1`` (line +
    newline) per audit line.

    ``exhausted`` is set when the v1 batch is dropped ON THE BYTE BUDGET: the
    budget is then frozen for BOTH classes (the audit truncation flag is set
    and no further audit line is stored).  A count-only drop leaves the budget
    open for audit lines.
    """

    __slots__ = ("max_bytes", "used", "exhausted")

    def __init__(self, max_bytes: int) -> None:
        self.max_bytes = max_bytes
        self.used = 0
        self.exhausted = False

    def fits(self, size: int) -> bool:
        return not self.exhausted and self.used + size <= self.max_bytes

    def consume(self, size: int) -> None:
        self.used += size


class _RecordChannel:
    """The v1 finance record batch channel (§4.1/§4.2).

    Stores rawPayloads one line each in ``finance-records.jsonl`` while
    maintaining the §4.2 counters (``emittedRecordCount`` /
    ``emittedRecordBytes`` — rawPayload bytes only, no markers, no newlines)
    and the incremental batch digest.  Each stored line consumes
    ``len(payload) + 1`` bytes of the JOINT ``recordChannelMaxBytes`` budget
    it shares with the unknown-marker audit file.  Exceeding the count limit
    OR the joint byte budget drops the WHOLE batch (§7.1 实施方式 5, §17
    整批放弃): the batch file is deleted, ``recordSetComplete`` becomes false
    with a non-empty ``dropReason``; draining continues and later records are
    discarded without touching the filesystem.  The audit file is KEPT across
    a drop (format-audit transport is independent of the record batch); a
    byte-budget drop additionally exhausts the joint budget so BOTH classes
    stop storing.  Ordinary stdout/stderr are unaffected and the task itself
    does not fail because of this.
    """

    def __init__(
        self, path: Path, max_records: int, budget: _JointByteBudget
    ) -> None:
        self._path = Path(path)
        self._max_records = max_records
        self._budget = budget
        self._file = open(self._path, "wb")
        self._hasher = hashlib.sha256()
        self._dropped = False
        self.emitted_count = 0
        self.emitted_bytes = 0
        self.drop_reason = ""

    @property
    def complete(self) -> bool:
        return not self._dropped

    @property
    def record_digest(self) -> str:
        """§4.2 recordDigest over the records stored before any drop."""
        return self._hasher.hexdigest()

    def add(self, payload: bytes) -> None:
        if self._dropped:
            return
        if self.emitted_count + 1 > self._max_records:
            self._drop(
                f"recordChannelMaxRecords exceeded: limit={self._max_records}"
            )
            return
        size = len(payload) + 1  # stored line: payload + newline
        if not self._budget.fits(size):
            self._drop(
                f"recordChannelMaxBytes exceeded: limit={self._budget.max_bytes}"
            )
            # Budget exhausted by the drop: freeze BOTH classes (§4.1/§4.2).
            self._budget.exhausted = True
            return
        self._file.write(payload + b"\n")
        self._budget.consume(size)
        self.emitted_count += 1
        self.emitted_bytes += len(payload)
        self._hasher.update(struct.pack(">I", len(payload)))
        self._hasher.update(payload)

    def _drop(self, reason: str) -> None:
        self._dropped = True
        self.drop_reason = reason
        try:
            self._file.close()
        finally:
            try:
                os.unlink(self._path)
            except OSError:
                pass

    def finalize(self) -> None:
        if not self._file.closed:
            self._file.flush()
            self._file.close()


class _UnknownMarkerAudit:
    """Unknown-version marker-line audit store (contract §4.1/§4.2).

    Stores marker-FAMILY lines that are not well-formed v1 records (unknown
    version prefix, or a v1 line missing its terminating newline) VERBATIM —
    original marker prefix kept, one newline-terminated line each — in
    ``finance-records-unknown-marker.jsonl``, so the Java side can audit
    UNSUPPORTED_MARKER_VERSION.  Shares the JOINT ``recordChannelMaxBytes``
    budget with the v1 record batch: a line is stored only while
    ``len(line) + 1`` fits the remaining budget; otherwise the line is
    discarded whole and ``truncated`` is set (a partial line would break the
    line-oriented audit format).  After a v1 byte-budget drop the budget is
    exhausted and ``truncated`` is set IMMEDIATELY — the audit channel is
    frozen from that moment even if no further audit line arrives; after a
    count-only drop, audit lines keep storing while the joint budget allows.

    The file is created lazily on the first STORED line, so a run with no
    stored audit lines leaves no file behind — the reader relies on the
    invariant: audit file exists iff ``unknownMarkerLines > 0``.
    """

    __slots__ = ("_path", "_budget", "_file", "stored_lines", "stored_bytes",
                 "truncated")

    def __init__(self, path: Path, budget: _JointByteBudget) -> None:
        self._path = Path(path)
        self._budget = budget
        self._file = None
        self.stored_lines = 0
        self.stored_bytes = 0
        self.truncated = False

    def add(self, line: bytes) -> None:
        size = len(line) + 1  # stored line: marker line + newline
        if not self._budget.fits(size):
            self.truncated = True
            return
        if self._file is None:
            self._file = open(self._path, "wb")
        self._file.write(line + b"\n")
        self._budget.consume(size)
        self.stored_lines += 1
        self.stored_bytes += size

    def finalize(self) -> None:
        if self._file is not None and not self._file.closed:
            self._file.flush()
            self._file.close()


def _kill_process_group(pgid: int) -> None:
    """SIGKILL every current member of the process group (§7.1 实施方式 6)."""
    try:
        os.killpg(pgid, signal.SIGKILL)
    except OSError:
        pass  # group already gone (or never fully started)


def _close_quietly(fileobj) -> None:
    if fileobj is None:
        return
    try:
        fileobj.flush()
    except (OSError, ValueError):
        pass
    try:
        fileobj.close()
    except (OSError, ValueError):
        pass


def parse_wrapper_input(path: Path) -> dict:
    """Parse and validate ``<wrapper-input.json>`` (§7.1 example shape)."""
    try:
        raw = Path(path).read_text(encoding="utf-8")
    except OSError as exc:
        raise WrapperInputError(f"cannot read wrapper input file: {exc}") from exc
    try:
        payload = json.loads(raw)
    except ValueError as exc:
        raise WrapperInputError(f"wrapper input is not valid JSON: {exc}") from exc
    if not isinstance(payload, dict):
        raise WrapperInputError("wrapper input must be a JSON object")

    script_path = payload.get("scriptPath")
    if not isinstance(script_path, str) or not script_path:
        raise WrapperInputError("scriptPath must be a non-empty string")

    timeout_seconds = payload.get("timeoutSeconds")
    if (
        isinstance(timeout_seconds, bool)
        or not isinstance(timeout_seconds, (int, float))
        or timeout_seconds < 0
    ):
        raise WrapperInputError("timeoutSeconds must be a non-negative number")

    limits_payload = payload.get("effectiveOutputLimits")
    if not isinstance(limits_payload, dict):
        raise WrapperInputError("effectiveOutputLimits must be a JSON object")
    limits: dict[str, int] = {}
    for key in LIMIT_KEYS:
        value = limits_payload.get(key)
        if isinstance(value, bool) or not isinstance(value, int) or value < 0:
            raise WrapperInputError(
                f"effectiveOutputLimits.{key} must be a non-negative integer"
            )
        limits[key] = value

    # runtimeEnvironmentPath is part of the §7.1 input shape but belongs to
    # work package D's schema (runtime_environment.py); the wrapper does not
    # consume it.
    return {
        "script_path": script_path,
        "timeout_seconds": timeout_seconds,
        "limits": limits,
    }


def run_bounded_capture(
    *,
    script_path: str,
    timeout_seconds: float,
    limits: dict,
    capture_dir: Path,
) -> dict:
    """Run the user script under bounded capture; return the summary dict.

    Always writes ``capture-result.json`` (including on timeout and on
    internal error) before returning.
    """
    capture_dir = Path(capture_dir)
    capture_dir.mkdir(parents=True, exist_ok=True)

    summary = {
        "exitCode": 127,  # spawn failure until the child's fate is known
        "ordinaryStdoutBytes": 0,
        "stderrBytes": 0,
        "stdoutTruncated": False,
        "stderrTruncated": False,
        "emittedRecordCount": 0,
        "emittedRecordBytes": 0,
        "recordSetComplete": True,
        "dropReason": "",
        "recordDigest": _EMPTY_BATCH_DIGEST,
        # Internal unknown-marker audit counters (§4.1/§4.2).  These stay out
        # of the §5.1 channel mapping (exactly its 7 fields) and are consumed
        # by the fail-closed artifact reader only.
        "unknownMarkerLines": 0,
        "unknownMarkerBytes": 0,
        "unknownMarkerTruncated": False,
    }

    stdout_file = stderr_file = None
    stdout_sink = stderr_sink = None
    records: _RecordChannel | None = None
    audit: _UnknownMarkerAudit | None = None
    stdout_pending = b""  # bytes of the not-yet-terminated final stdout line
    lock = threading.Lock()
    proc: subprocess.Popen | None = None
    timed_out = False

    def classify_line(line: bytes, terminated: bool) -> None:
        """Route one stdout line: v1 record / unknown marker audit / ordinary.

        Classification is ONLY by the fixed byte prefix (§7.1 实施方式 4 +
        §16.2): report() and report_custom() both use the identical v1 marker,
        and a marker merely contained mid-line stays ordinary.  A marker line
        missing its trailing newline is malformed (§4.1 requires newline
        termination) and goes to the audit bucket as well.  Both classes draw
        from the SINGLE joint recordChannelMaxBytes budget (§4.1/§4.2).
        """
        if line.startswith(MARKER_FAMILY_PREFIX_BYTES):
            if terminated and line.startswith(MARKER_V1_PREFIX_BYTES):
                records.add(line[len(MARKER_V1_PREFIX_BYTES):])
            else:
                audit.add(line)
        else:
            stdout_sink.write(line + (b"\n" if terminated else b""))

    def on_stdout_chunk(chunk: bytes) -> None:
        nonlocal stdout_pending
        with lock:
            data = stdout_pending + chunk
            parts = data.split(b"\n")
            stdout_pending = parts[-1]
            for part in parts[:-1]:
                classify_line(part, True)

    def on_stderr_chunk(chunk: bytes) -> None:
        with lock:
            stderr_sink.write(chunk)

    def drain_pipe(fd: int, on_chunk) -> None:
        # os.read returns whatever is available without waiting for a full
        # buffer, so the child's pipe is drained continuously from the first
        # byte to EOF — the child can never block on a full pipe.
        while True:
            try:
                chunk = os.read(fd, _READ_CHUNK_SIZE)
            except OSError:
                break
            if not chunk:
                break
            on_chunk(chunk)

    try:
        stdout_file = open(capture_dir / STDOUT_FILE_NAME, "wb")
        stderr_file = open(capture_dir / STDERR_FILE_NAME, "wb")
        stdout_sink = _BoundedByteSink(stdout_file, limits["stdoutMaxBytes"])
        stderr_sink = _BoundedByteSink(stderr_file, limits["stderrMaxBytes"])
        # ONE joint recordChannelMaxBytes budget for the two record-channel
        # files: known v1 records and unknown-marker audit lines (§4.1/§4.2).
        joint_budget = _JointByteBudget(limits["recordChannelMaxBytes"])
        records = _RecordChannel(
            capture_dir / RECORDS_FILE_NAME,
            limits["recordChannelMaxRecords"],
            joint_budget,
        )
        audit = _UnknownMarkerAudit(
            capture_dir / UNKNOWN_MARKER_AUDIT_FILE_NAME, joint_budget
        )

        try:
            # The child's stdout/stderr are the capture pipes ONLY: the child
            # never inherits or shares the wrapper's own stdout fd, which
            # later carries the single bounded envelope (PIN 2).
            proc = subprocess.Popen(
                [sys.executable, str(script_path)],
                stdin=subprocess.DEVNULL,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                cwd=str(Path(script_path).resolve().parent),
                start_new_session=True,  # child owns a new process group
            )
        except OSError:
            proc = None
            raise
        pgid = proc.pid  # after setsid(), the child is its own group leader

        stdout_thread = threading.Thread(
            target=drain_pipe,
            args=(proc.stdout.fileno(), on_stdout_chunk),
            name="af-bounded-stdout-drain",
            daemon=True,
        )
        stderr_thread = threading.Thread(
            target=drain_pipe,
            args=(proc.stderr.fileno(), on_stderr_chunk),
            name="af-bounded-stderr-drain",
            daemon=True,
        )
        stdout_thread.start()
        stderr_thread.start()

        # Timeout monitoring on the main thread while the readers drain.
        deadline = time.monotonic() + float(timeout_seconds)
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                timed_out = True
                _kill_process_group(pgid)
                break
            try:
                proc.wait(timeout=min(remaining, 0.2))
                break
            except subprocess.TimeoutExpired:
                continue

        if timed_out:
            try:
                proc.wait(timeout=30)
            except subprocess.TimeoutExpired:
                pass
            # Sweep any process spawned between the kill and the reap.
            _kill_process_group(pgid)

        # EOF on both pipes follows the group's death / the child's exit.
        stdout_thread.join(timeout=30)
        stderr_thread.join(timeout=30)

        with lock:
            if stdout_pending:
                classify_line(stdout_pending, False)
                stdout_pending = b""

        # A byte-budget drop exhausts the joint budget and freezes the audit
        # class: ``unknownMarkerTruncated`` becomes true at the drop itself,
        # even when no further audit line arrives afterwards (§4.1/§4.2).
        if joint_budget.exhausted:
            audit.truncated = True

        summary.update(
            {
                "ordinaryStdoutBytes": stdout_sink.stored_bytes,
                "stderrBytes": stderr_sink.stored_bytes,
                "stdoutTruncated": stdout_sink.truncated,
                "stderrTruncated": stderr_sink.truncated,
                "emittedRecordCount": records.emitted_count,
                "emittedRecordBytes": records.emitted_bytes,
                "recordSetComplete": records.complete,
                "dropReason": records.drop_reason,
                "recordDigest": records.record_digest,
                "unknownMarkerLines": audit.stored_lines,
                # Stored audit-file bytes INCLUDING newlines, exactly as
                # written (== joint-budget bytes used by the audit class).
                "unknownMarkerBytes": audit.stored_bytes,
                "unknownMarkerTruncated": audit.truncated,
            }
        )

        exit_code = proc.returncode
        if exit_code is None:
            exit_code = 1
        elif timed_out and exit_code == 0:
            exit_code = 124  # defensive: a timed-out run never reports success
        summary["exitCode"] = exit_code
        return summary
    finally:
        _close_quietly(stdout_file)
        _close_quietly(stderr_file)
        if records is not None:
            records.finalize()
        if audit is not None:
            audit.finalize()
        _write_capture_result(capture_dir, summary)


def _write_capture_result(capture_dir: Path, summary: dict) -> None:
    """Atomically write the §7.1 summary before any cleanup."""
    target = Path(capture_dir) / CAPTURE_RESULT_FILE_NAME
    tmp = target.with_name(target.name + ".tmp")
    with open(tmp, "w", encoding="utf-8") as handle:
        json.dump(summary, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    os.replace(tmp, target)


def main(argv: list[str] | None = None) -> int:
    args = list(sys.argv[1:] if argv is None else argv)
    if len(args) != 1:
        sys.stderr.write(
            "usage: python3 -m app.bounded_exec_wrapper <wrapper-input.json>\n"
        )
        return 2
    input_path = Path(args[0])
    try:
        parsed = parse_wrapper_input(input_path)
    except WrapperInputError as exc:
        # Diagnostics only — never user content (§18 stop condition).
        sys.stderr.write(f"bounded_exec_wrapper: {exc}\n")
        return 2

    capture_dir = input_path.resolve().parent / CAPTURE_DIR_NAME
    try:
        run_bounded_capture(
            script_path=parsed["script_path"],
            timeout_seconds=parsed["timeout_seconds"],
            limits=parsed["limits"],
            capture_dir=capture_dir,
        )
    except Exception as exc:  # last-resort guard; type name only (§18)
        sys.stderr.write(
            f"bounded_exec_wrapper: internal error: {type(exc).__name__}\n"
        )
        return 1

    # === work-package-C: wrapper-tail readback (PIN 1 + PIN 2) =============
    # The user child has exited and the capture files are finalized.  The
    # trusted reader — bound into memory BEFORE the spawn (PIN 1, top-level
    # import) — performs the bounded readback IN MEMORY with the four frozen
    # §13 limits.  Nothing located in the user-writable task workspace is
    # executed or re-imported after user code ran: a user overwrite of the
    # staged capture_reader.py (or of this wrapper file) is harmless.
    try:
        envelope = capture_reader.read_capture_files(
            capture_dir,
            stdout_max_bytes=parsed["limits"]["stdoutMaxBytes"],
            stderr_max_bytes=parsed["limits"]["stderrMaxBytes"],
            record_channel_max_bytes=parsed["limits"]["recordChannelMaxBytes"],
            record_channel_max_records=parsed["limits"]["recordChannelMaxRecords"],
        )
    except (OSError, ValueError) as exc:
        # Fail closed: SHORT diagnostic only — file names/sizes/caps, never
        # capture CONTENT (§18).  The host fails the task on this nonzero
        # terminal exit; stdout stays EMPTY on the failure path.
        sys.stderr.write(
            f"bounded_exec_wrapper: capture readback failed: {exc}\n"
        )
        return 1
    # PIN 2: stdout carries EXACTLY ONE bounded envelope JSON document and
    # zero other bytes before or after it (no trailing newline).
    sys.stdout.write(json.dumps(envelope))
    sys.stdout.flush()
    # === end work-package-C =================================================
    return 0


if __name__ == "__main__":
    sys.exit(main())
