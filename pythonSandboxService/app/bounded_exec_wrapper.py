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

Bounded outputs land under ``<wrapper-input dir>/capture/`` (created mode
0700)::

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
                                          fields, the D11 cancelObserved
                                          cancellation-evidence flag, plus
                                          three internal unknown-marker
                                          counters
                                          (unknownMarkerLines /
                                          unknownMarkerBytes /
                                          unknownMarkerTruncated) that stay
                                          out of the §5.1 channel mapping; the
                                          summary's values feed the §5.1
                                          snake_case
                                          ExecuteResult.finance_record_channel

All capture files are opened BEFORE the child is spawned (``O_NOFOLLOW``,
mode 0600) and the wrapper KEEPS the fds: the summary is written through its
pre-opened fd and the readback (§7.1 step 7) reads EXACTLY those fds —
``capture_reader.read_capture_files_from_fds`` — so there is ZERO path
resolution after the spawn (P0-4, codex 03b4d034 / d384119d): a malicious
child that renames, unlinks, replaces or symlinks anything under the capture
directory while it runs cannot influence the readback, because the paths are
never consulted again.

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

Cancel marker (260809-26Q3 D11, task #108, d6841a2e rules): when the wrapper
input carries ``cancelMarkerPath``, the deadline loop polls that path every
iteration.  OBSERVING the marker while the child is still alive is the ONLY
valid cancellation evidence (rule 2): the wrapper kills the entire process
group exactly like a timeout kill and reports ``cancelObserved=true`` in the
summary.  A marker that appears only after the child already exited normally
changes nothing (rule 3 — the genuine SUCCEEDED/FAILED result stands), and a
kill issued anywhere outside this observation (or a stop request that never
produced a marker) is NOT evidence (rule 4).  Before the spawn, main()
fail-closed verifies that the marker path equals
``<control_root>/<taskId>/cancel`` derived from ``scriptPath`` — the control
root is ``/run/alphafrog-task-control`` inside the container, overridable via
``AF_TASK_CONTROL_ROOT`` so host-side tests and the runner agree on ONE
location (the historical root-ownership chain check was removed with the
privilege-drop machinery — containers no longer contain root).

Process-tree cleanup (P0-2, codex b39f5e6b / 1d81ca85): a child that exits
promptly can leave grandchildren that inherited the stdout/stderr pipes
(with or without their own session), and the drain threads only reach EOF
when the LAST pipe holder dies — so after the main child exits (or the
timeout kill lands) the wrapper runs a BOUNDED sweep: reap zombies
(``waitpid -1 WNOHANG``), enumerate live descendants (Linux: ``/proc`` ppid
chains, with ``prctl(PR_SET_CHILD_SUBREAPER)`` set pre-spawn so orphans are
reparented to the wrapper — a HARD spawn gate since codex 02953ca7, because
without subreaper a setsid grandchild that also closes its inherited pipes
is invisible to BOTH sweep signals (descendant enumeration AND pipe EOF),
so any prctl failure aborts the run with no child and no summary; macOS:
best-effort ppid chains plus an ``lsof`` pipe-holder correlation for
session-escaped holders), SIGTERM them, a short grace, then SIGKILL the
rest.  The sweep repeats until BOTH drain threads
have joined AND no live descendant remains, within
``PROCESS_SWEEP_BUDGET_SECONDS``; if the budget is exceeded the wrapper exits
non-zero WITHOUT emitting the envelope (a success summary would be a lie
while a runaway grandchild still holds the stage).

Streaming stdout classification (P0-3, codex 21aaf3b8): stdout is classified
by a bounded line state machine (``_StreamingStdoutClassifier``) instead of
the old ``pending + chunk`` accumulation: at line start at most the marker
family prefix is buffered to decide marker-vs-ordinary; ordinary bytes stream
immediately to the bounded sink (never accumulated); confirmed marker lines
buffer up to the remaining joint record budget plus a small constant, and an
overrun applies the SAME whole-batch-drop / unknown-truncated semantics as a
completed over-limit line and then discards without buffering until the
newline.  Pending bytes never exceed ``max(len(marker family prefix),
remaining record budget + slack)`` regardless of how long an unterminated
line grows.

UID model (260817 simplification, frog 9dab5e2d): the sandbox container
itself is CREATED as the unprivileged user (docker ``--user`` semantics,
set by the runner via ``AF_SANDBOX_CHILD_USER`` at container creation —
uid 10000/gid 10001 ``alphafrog-sandbox`` in the runtime image).  Nothing
inside the container ever runs as root, so the wrapper spawns the user
child with its own (already unprivileged) identity and there is NO
privilege-drop machinery here (no preexec_fn, no capset/prctl UID chain —
the previous root->child drop was removed wholesale).

Wrapper-tail envelope (work-package-C rework, P0 fix): after the user child
exits and the capture files are finalized, the wrapper performs the bounded
readback IN MEMORY through ``capture_reader`` and emits the returned
envelope on its OWN stdout.  ``capture_reader`` is
imported at wrapper process start, BEFORE the spawn (PIN 1): the staged
copy lives in the user-writable task workspace, and binding it pre-spawn —
while no adversary is alive — is what makes it trusted; after user code
exits, NOTHING located in the task workspace is ever executed or re-imported
again, so overwriting the staged files post-spawn is harmless.  The wrapper's
stdout carries EXACTLY ONE bounded envelope JSON document and zero other
bytes (PIN 2); the wrapper still NEVER writes user output, record lines or
payloads to its own streams in readable form — the envelope is base64 and
bounded by ``capture_reader._envelope_ceiling`` (§18 stop condition).  On
readback failure the wrapper writes a SHORT diagnostic (names/sizes/caps
only, never capture content, §18) to stderr and exits non-zero; the host
fails closed on the nonzero terminal.  The wrapper exit code reports wrapper
success (capture-result.json written AND envelope emitted); the child's fate
lives in capture-result.json.

Stdlib only; importable without pydantic (``app/__init__.py`` stays empty).
"""

from __future__ import annotations

import ctypes
import hashlib
import json
import os
import signal
import stat
import struct
import subprocess
import sys
import threading
import time
from pathlib import Path, PurePosixPath

from app.output_capture import (
    MARKER_FAMILY_PREFIX_BYTES,
    MARKER_V1_PREFIX_BYTES,
    record_batch_digest,  # noqa: F401  re-exported: §4.1 cross-check binds it here
)

# === work-package-C: PIN 1 — pre-spawn binding of trusted modules ===========
# The readback module and the child-identity parser are imported HERE, at
# wrapper process start, BEFORE the user child is spawned.  The staged copies
# live in the user-writable task workspace, so user code may overwrite them
# while it runs; these top-level imports bind the trusted copies into memory
# while no adversary is alive.  After the child exits the wrapper calls the
# IN-MEMORY modules only — it must NEVER lazily (re-)import them and NEVER
# execute anything from the task workspace again; overwriting the staged
# files post-spawn is harmless.
from app import capture_reader
# D15 §4.2.3 round-4 (codex 56976668 MUST-FIX #3): single payload contract
# shared with models.BoundedExecRequest. payload_contract.py is stdlib-only
# so importing it here does NOT break this wrapper's stdlib-only invariant
# (it does NOT drag pydantic into the wrapper's import graph — pydantic is
# only pulled in if app.models is imported, which this wrapper never does).
from app.payload_contract import (
    ALLOWED_TASK_ENV_KEYS,
    PayloadContractError,
    validate_payload_contract,
)
# === end work-package-C =====================================================

__all__ = [
    "CAPTURE_DIR_NAME",
    "STDOUT_FILE_NAME",
    "STDERR_FILE_NAME",
    "RECORDS_FILE_NAME",
    "UNKNOWN_MARKER_AUDIT_FILE_NAME",
    "CAPTURE_RESULT_FILE_NAME",
    "LIMIT_KEYS",
    "CANCEL_MARKER_FILE_NAME",
    "TASK_CONTROL_ROOT_ENV_NAME",
    "TASK_CONTROL_ROOT_DEFAULT",
    "PROCESS_SWEEP_BUDGET_SECONDS",
    "SWEEP_TERM_GRACE_SECONDS",
    "SWEEP_POLL_INTERVAL_SECONDS",
    "WrapperInputError",
    "record_batch_digest",
    "parse_wrapper_input",
    "expected_cancel_marker_path",
    "run_bounded_capture",
    "main",
]

CAPTURE_DIR_NAME = "capture"
STDOUT_FILE_NAME = "stdout.bin"
STDERR_FILE_NAME = "stderr.bin"
RECORDS_FILE_NAME = "finance-records.jsonl"
UNKNOWN_MARKER_AUDIT_FILE_NAME = "finance-records-unknown-marker.jsonl"
CAPTURE_RESULT_FILE_NAME = "capture-result.json"

# D15 §4.2.3 (Scenario B) round-2 (codex fe54d9f0 MUST-FIX core bug): the
# wrapper writes a task-local LOADER bootstrap (loader_bootstrap.py) into
# {task_workspace}/_bootstrap/ and runs the user script THROUGH that
# bootstrap. The bootstrap inserts the loader workdir into sys.path AFTER
# the Python interpreter's site init phase has finished, so a stale
# sitecustomize.py left over in the loader workdir from a previous task can
# NEVER be auto-imported at startup. See _write_loader_bootstrap and the
# Popen argv in run_bounded_capture.
LOADER_BOOTSTRAP_DIR_NAME = "_bootstrap"
LOADER_BOOTSTRAP_FILE_NAME = "loader_bootstrap.py"

# Contract §13 line 644: the four frozen Python-side limit snapshot keys.
LIMIT_KEYS = (
    "stdoutMaxBytes",
    "stderrMaxBytes",
    "recordChannelMaxBytes",
    "recordChannelMaxRecords",
)

# === 260809-26Q3-stage1-w2 D11 (task #108): cancel marker polling ==========
# The runner creates the control chain <control_root>/<taskId>/ root:root
# 0700 inside the container and hands the EXACT marker path via the wrapper
# input.  While the child runs, the wrapper polls that path on every timeout
# loop turn; when it OBSERVES the marker it kills its OWN child process
# group and reports cancelObserved=true (d6841a2e rule 2 — the only
# evidence that justifies CANCELED for a running child).  The binding is
# verified fail-closed BEFORE spawn: the supplied path must equal the
# task-local derivation <control_root>/<scriptDirName>/cancel, and under
# root the whole parent chain must be real root-owned directories with no
# write path for the child identity (lstat-based, symlinks rejected —
# codex 4334bc9d constraint 2).  AF_TASK_CONTROL_ROOT overrides the default
# for host-side tests; the runner derives the same path from the same env.
CANCEL_MARKER_FILE_NAME = "cancel"
TASK_CONTROL_ROOT_ENV_NAME = "AF_TASK_CONTROL_ROOT"
TASK_CONTROL_ROOT_DEFAULT = "/run/alphafrog-task-control"
# === end D11 cancel marker polling ==========================================

# Pipe read size: large enough to keep a flooding child from ever filling the
# 64 KiB pipe buffer between scheduler turns.
_READ_CHUNK_SIZE = 1024 * 1024

# P0-2 process-tree sweep budget: a FEW seconds.  The sweep repeats
# reap/enumerate/kill cycles until both drain threads joined and no live
# descendant remains; exceeding the budget fails the wrapper (no envelope).
PROCESS_SWEEP_BUDGET_SECONDS = 3.0
SWEEP_TERM_GRACE_SECONDS = 1.0
SWEEP_POLL_INTERVAL_SECONDS = 0.05

# P0-3: extra buffering allowed past the remaining joint record budget while a
# marker line is still terminated: the v1 marker prefix itself (24 bytes) is
# never counted against the budget, so a storeable v1 line can legitimately
# run that many bytes past the remaining budget, plus its newline.
_MARKER_LINE_SLACK = len(MARKER_V1_PREFIX_BYTES) + 1

# prctl operations (Linux only; guarded everywhere else).
_PR_SET_CHILD_SUBREAPER = 36

_EMPTY_BATCH_DIGEST = hashlib.sha256(b"").hexdigest()

_libc_cache = None


def _libc():
    """Best-effort libc handle for prctl (Linux); cached; None on failure."""
    global _libc_cache
    if _libc_cache is None:
        try:
            _libc_cache = ctypes.CDLL(None, use_errno=True)
        except OSError:
            return None
    return _libc_cache


def _set_child_subreaper() -> None:
    """Linux: make the wrapper the subreaper of its descendant tree.

    Orphaned grandchildren (their parent died, including setsid escapees —
    a new session does NOT change reparenting) are then reparented to the
    wrapper instead of pid 1, which keeps them enumerable via ``/proc``
    ppid chains and reapable via ``waitpid``.

    Codex 02953ca7 stop condition: on the Linux/container security path a
    SUCCESSFUL ``prctl(PR_SET_CHILD_SUBREAPER, 1)`` is a HARD gate that runs
    BEFORE the spawn.  Without subreaper, a setsid grandchild whose parent
    died is adopted by pid 1, and if it also closed the inherited capture
    pipes BOTH sweep signals go empty — ``_live_descendant_pids`` finds
    nothing and the drains hit EOF — so the wrapper would report
    sweep_ok=true while a runaway process keeps running in the reused
    container.  Therefore: libc unavailable, a nonzero prctl return, or any
    prctl exception raises ``OSError`` before any Popen; the wrapper then
    exits non-zero with NO child and NO summary.  Non-Linux (macOS dev)
    keeps the best-effort ppid/lsof sweep and claims no security boundary.
    """
    if sys.platform != "linux":
        return
    libc = _libc()
    if libc is None:
        raise OSError("libc unavailable for prctl(PR_SET_CHILD_SUBREAPER)")
    try:
        result = libc.prctl(_PR_SET_CHILD_SUBREAPER, 1, 0, 0, 0)
    except OSError as exc:
        raise OSError("prctl(PR_SET_CHILD_SUBREAPER) raised") from exc
    if result != 0:
        raise OSError(
            f"prctl(PR_SET_CHILD_SUBREAPER) failed: errno {ctypes.get_errno()}"
        )


class WrapperInputError(ValueError):
    """The wrapper-input.json file is missing or malformed."""


def _open_capture_file(path: Path, *, text: bool = False):
    """Open one capture file fail-closed BEFORE the child is spawned.

    ``O_NOFOLLOW`` rejects a symlink planted at the path (ELOOP); the file
    is created 0600 and opened READ-WRITE because the same fd is later
    re-read by the fd-pinned readback (zero path resolution after spawn).
    """
    fd = os.open(
        str(path),
        os.O_RDWR | os.O_CREAT | os.O_TRUNC | os.O_NOFOLLOW,
        0o600,
    )
    return os.fdopen(fd, "w" if text else "wb")


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
        self._file = _open_capture_file(self._path)
        self._hasher = hashlib.sha256()
        self._dropped = False
        self.emitted_count = 0
        self.emitted_bytes = 0
        self.drop_reason = ""

    @property
    def complete(self) -> bool:
        return not self._dropped

    @property
    def dropped(self) -> bool:
        return self._dropped

    @property
    def fileobj(self):
        """The still-open capture fd (None after a drop closed+unlinked it)."""
        return self._file

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

    def reject_overlong_v1_line(self) -> None:
        """P0-3 overflow: an UNTERMINATED v1 line already proved too long to
        ever fit (it ran past the remaining budget + slack without a newline).

        Mirrors EXACTLY the failure branches of ``add()`` — same order (count
        limit first), same dropReason strings — so streaming classification
        cannot change the whole-batch-drop semantics.
        """
        if self._dropped:
            return
        if self.emitted_count + 1 > self._max_records:
            self._drop(
                f"recordChannelMaxRecords exceeded: limit={self._max_records}"
            )
            return
        self._drop(
            f"recordChannelMaxBytes exceeded: limit={self._budget.max_bytes}"
        )
        self._budget.exhausted = True

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

    @property
    def fileobj(self):
        """The lazily-created capture fd (None iff no line was stored)."""
        return self._file

    def add(self, line: bytes) -> None:
        size = len(line) + 1  # stored line: marker line + newline
        if not self._budget.fits(size):
            self.truncated = True
            return
        if self._file is None:
            self._file = _open_capture_file(self._path)
        self._file.write(line + b"\n")
        self._budget.consume(size)
        self.stored_lines += 1
        self.stored_bytes += size

    def finalize(self) -> None:
        if self._file is not None and not self._file.closed:
            self._file.flush()
            self._file.close()


# === P0-3: bounded streaming line state machine (codex 21aaf3b8) ============
# States for the stdout classifier.
_LINE_START = 0    # at a line boundary; buffering at most the family prefix
_ORDINARY = 1      # inside an ordinary line; bytes stream straight to the sink
_MARKER = 2        # inside a confirmed marker line; bounded buffering
_MARKER_DISCARD = 3  # an over-limit marker line; discard (counted) to newline


class _StreamingStdoutClassifier:
    """Bounded streaming replacement for the old ``pending + chunk`` splitter.

    Semantics are EXACTLY the old ``classify_line`` semantics (marker
    classification is by fixed byte prefix at line start; v1 records lose the
    marker and the newline; unknown/malformed marker lines go verbatim to the
    audit bucket; everything else is ordinary stdout) — but no unterminated
    line can ever accumulate unbounded bytes:

    * ``_LINE_START``: buffer ONLY while the bytes keep matching the marker
      family prefix (at most ``len(MARKER_FAMILY_PREFIX_BYTES)`` bytes); a
      mismatch flushes the buffered prefix to the ordinary sink and streams
      on; a full match enters ``_MARKER``.
    * ``_ORDINARY``: bytes stream IMMEDIATELY to the bounded sink (a newline
      returns to ``_LINE_START``); ordinary data is never accumulated.
    * ``_MARKER``: buffer up to the remaining joint record budget plus
      ``_MARKER_LINE_SLACK``; a newline within the window routes the complete
      line exactly like the old classifier; running past the window applies
      the SAME whole-batch-drop / unknown-truncated decision the completed
      over-limit line would have triggered, then discards.
    * ``_MARKER_DISCARD``: count (never buffer) discarded bytes up to the
      next newline.

    Invariant: ``len(self._pending)`` never exceeds
    ``max(len(MARKER_FAMILY_PREFIX_BYTES), recordChannelMaxBytes +
    _MARKER_LINE_SLACK)``; ``max_pending_bytes`` records the observed maximum
    for tests to instrument.  Single-threaded by construction: only the
    stdout drain thread feeds it, and ``finalize()`` runs after that thread
    has joined.
    """

    __slots__ = (
        "_sink",
        "_records",
        "_audit",
        "_budget",
        "_pending",
        "_state",
        "max_pending_bytes",
        "discarded_marker_bytes",
    )

    def __init__(self, sink, records, audit, budget: _JointByteBudget) -> None:
        self._sink = sink
        self._records = records
        self._audit = audit
        self._budget = budget
        self._pending = b""
        self._state = _LINE_START
        self.max_pending_bytes = 0
        self.discarded_marker_bytes = 0

    def _track(self) -> None:
        if len(self._pending) > self.max_pending_bytes:
            self.max_pending_bytes = len(self._pending)

    def _route_marker_line(self, line: bytes, terminated: bool) -> None:
        """Marker branch of the frozen ``classify_line`` semantics."""
        if terminated and line.startswith(MARKER_V1_PREFIX_BYTES):
            self._records.add(line[len(MARKER_V1_PREFIX_BYTES):])
        else:
            self._audit.add(line)

    def _overflow_marker_line(self) -> None:
        """An unterminated marker line outran its bounded window.

        The line can now never be stored (its length already exceeds the
        remaining joint budget, and v1 payloads only shrink it by the 24-byte
        marker, which the slack covers), so apply the exact decision the
        completed over-limit line would have triggered, then discard to the
        next newline without buffering.
        """
        if self._pending.startswith(MARKER_V1_PREFIX_BYTES):
            self._records.reject_overlong_v1_line()
        else:
            self._audit.truncated = True
        self._pending = b""
        self._state = _MARKER_DISCARD

    def feed(self, chunk: bytes) -> None:
        prior = self._pending
        data = prior + chunk if prior else chunk
        n = len(data)
        # Bytes below ``pos`` are already accounted for: in _MARKER the held
        # bytes live in ``_pending`` (new bytes start AFTER them); at
        # _LINE_START the held probe is re-matched from byte 0 because it was
        # never emitted anywhere; _ORDINARY/_MARKER_DISCARD never hold bytes.
        pos = len(prior) if self._state == _MARKER else 0
        fam = MARKER_FAMILY_PREFIX_BYTES
        fam_len = len(fam)
        while pos < n:
            state = self._state
            if state == _LINE_START:
                lim = min(fam_len, n - pos)
                k = 0
                while k < lim and data[pos + k] == fam[k]:
                    k += 1
                if k == fam_len:
                    # Whole family prefix matched: confirmed marker line.
                    self._pending = data[pos:pos + k]
                    self._state = _MARKER
                    pos += k
                elif k == lim:
                    # Chunk ended mid-prefix: keep matching on the next feed.
                    self._pending = data[pos:pos + k]
                    pos = n
                else:
                    # Mismatch: ordinary line.  Flush the buffered prefix and
                    # stream on (the ORDINARY state never accumulates).
                    if k:
                        self._sink.write(data[pos:pos + k])
                    self._pending = b""  # probe bytes are now emitted
                    self._state = _ORDINARY
                    pos += k
                self._track()
            elif state == _ORDINARY:
                nl = data.find(b"\n", pos)
                if nl == -1:
                    self._sink.write(data[pos:])
                    pos = n
                else:
                    self._sink.write(data[pos:nl + 1])
                    pos = nl + 1
                    self._state = _LINE_START
            elif state == _MARKER:
                cap = (
                    self._budget.max_bytes - self._budget.used
                ) + _MARKER_LINE_SLACK
                room = cap - len(self._pending)
                if room <= 0:
                    self._overflow_marker_line()
                    continue
                nl = data.find(b"\n", pos)
                if nl != -1 and (nl - pos) <= room:
                    line = self._pending + data[pos:nl]
                    self._pending = b""
                    pos = nl + 1
                    self._state = _LINE_START
                    self._route_marker_line(line, True)
                elif nl != -1:
                    # Newline exists but beyond the window: fill the window,
                    # then overflow.
                    self._pending += data[pos:pos + room]
                    pos += room
                    self._overflow_marker_line()
                else:
                    take = min(room, n - pos)
                    self._pending += data[pos:pos + take]
                    pos += take
                    if len(self._pending) >= cap:
                        self._overflow_marker_line()
                    else:
                        self._track()
            else:  # _MARKER_DISCARD
                nl = data.find(b"\n", pos)
                if nl == -1:
                    self.discarded_marker_bytes += n - pos
                    pos = n
                else:
                    self.discarded_marker_bytes += nl - pos
                    pos = nl + 1
                    self._state = _LINE_START
                    self._pending = b""

    def finalize(self) -> None:
        """EOF: route the held remainder exactly like the old classifier did."""
        state = self._state
        pending = self._pending
        self._pending = b""
        self._state = _LINE_START
        if state == _MARKER:
            # Unterminated marker line (incl. a bare family prefix) is
            # malformed -> audit bucket, as classify_line(line, False) did.
            self._route_marker_line(pending, False)
        elif pending:
            # Partial family prefix or an ordinary tail: ordinary bytes.
            self._sink.write(pending)
        # Frozen post-EOF rule (the old wrapper applied it right after the
        # final classify_line): a byte-budget drop exhausts the joint budget
        # and freezes the audit class, so unknownMarkerTruncated becomes true
        # even when no further audit line ever arrives (§4.1/§4.2).
        if self._budget.exhausted:
            self._audit.truncated = True
# === end P0-3 ================================================================


# === P0-2: bounded process-tree sweep (codex b39f5e6b / 1d81ca85) ===========
def _reap_zombies() -> None:
    """Reap every already-dead child of the wrapper (``waitpid -1 WNOHANG``).

    With ``PR_SET_CHILD_SUBREAPER`` active (Linux), orphaned descendants are
    reparented to the wrapper, so this collects them; on macOS only direct
    children are reapable (orphans go to pid 1 — documented dev-mode limit).
    """
    while True:
        try:
            pid, _ = os.waitpid(-1, os.WNOHANG)
        except OSError:  # ChildProcessError: no children left (or ECHILD)
            return
        if pid == 0:
            return


def _run_utility(argv: list, timeout: float = 5.0) -> bytes:
    """Run an enumeration helper; empty stdout on any failure (best effort)."""
    try:
        proc = subprocess.Popen(
            argv, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL
        )
    except OSError:
        return b""
    try:
        stdout = proc.communicate(timeout=timeout)[0]
    except subprocess.TimeoutExpired:
        proc.kill()
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            pass
        return b""
    return stdout or b""


def _pid_parent_map() -> dict:
    """``pid -> ppid`` for all live non-zombie processes.

    Linux: ``/proc/<pid>/stat`` (field parse after the ``)`` of comm — comm
    may contain spaces/parentheses).  Other platforms: ``ps -axo``.
    """
    mapping: dict = {}
    if sys.platform == "linux":
        try:
            entries = os.listdir("/proc")
        except OSError:
            return mapping
        for entry in entries:
            if not entry.isdigit():
                continue
            try:
                with open(f"/proc/{entry}/stat", "rb") as fh:
                    raw = fh.read()
            except OSError:
                continue
            cut = raw.rfind(b")")
            if cut == -1:
                continue
            fields = raw[cut + 2:].split()
            if len(fields) < 2 or fields[0] == b"Z":
                continue
            try:
                mapping[int(entry)] = int(fields[1])
            except ValueError:
                continue
        return mapping
    out = _run_utility(["ps", "-axo", "pid=,ppid=,stat="])
    for line in out.decode("utf-8", "replace").splitlines():
        parts = line.split()
        if len(parts) < 3 or parts[2].startswith("Z"):
            continue
        try:
            mapping[int(parts[0])] = int(parts[1])
        except ValueError:
            continue
    return mapping


def _descendants_via_ppid(roots) -> set:
    """Live processes whose ppid chain is rooted at ANY pid in ``roots``."""
    parents = _pid_parent_map()
    children: dict = {}
    for pid, ppid in parents.items():
        children.setdefault(ppid, []).append(pid)
    found: set = set()
    stack = list(roots)
    while stack:
        current = stack.pop()
        for pid in children.get(current, ()):
            if pid not in found:
                found.add(pid)
                stack.append(pid)
    return found


def _pipe_holder_pids(pipe_fds) -> set:
    """macOS fallback: pids (other than ours) holding our pipe endpoints.

    macOS reparents orphans to pid 1, so a session-escaped grandchild whose
    parent died is invisible to ppid chains; but while it holds a write end
    of the capture pipes it keeps the drain threads from EOF, so finding it
    is exactly what the sweep needs.  Correlation (empirically pinned): our
    read ends report ``n->0xPEER`` where ``0xPEER`` is the write endpoint's
    own address, and every holder's PIPE record reports that endpoint's own
    address in its ``d`` field (== the endpoint's ``fstat().st_ino`` in hex).
    A full ``lsof -w -n`` scan costs ~0.2s; callers stay within the sweep
    budget.  Linux never needs this (subreaper + /proc is complete).
    """
    if sys.platform == "linux" or not pipe_fds:
        return set()
    fd_arg = ",".join(str(fd) for fd in pipe_fds)
    self_out = _run_utility(
        [
            "lsof", "-w", "-n", "-F", "tdn",
            "-a", "-p", str(os.getpid()), "-d", fd_arg,
        ]
    )
    targets: set = set()
    cur_type = None
    for line in self_out.decode("utf-8", "replace").splitlines():
        if line.startswith("t"):
            cur_type = line[1:]
        elif line.startswith("f"):
            cur_type = None
        elif cur_type == "PIPE":
            if line.startswith("d"):
                targets.add(line[1:])
            elif line.startswith("n->"):
                targets.add(line[3:])
    if not targets:
        return set()
    full_out = _run_utility(["lsof", "-w", "-n", "-F", "ptd"])
    holders: set = set()
    cur_pid = None
    cur_type = None
    self_pid = str(os.getpid())
    for line in full_out.decode("utf-8", "replace").splitlines():
        if not line:
            continue
        key, value = line[0], line[1:]
        if key == "p":
            cur_pid = value
            cur_type = None
        elif key == "f":
            cur_type = None
        elif key == "t":
            cur_type = value
        elif key == "d" and cur_type == "PIPE" and value in targets:
            if cur_pid is not None and cur_pid != self_pid:
                try:
                    holders.add(int(cur_pid))
                except ValueError:
                    pass
    return holders


def _live_descendant_pids(root_pid: int, pipe_fds) -> set:
    """Every live process that belongs to the user child's tree.

    Linux: roots are BOTH the user child (normal descendants) and the
    wrapper itself — ``PR_SET_CHILD_SUBREAPER`` reparents orphans to the
    wrapper, and the ``/proc`` enumeration spawns no helper processes, so
    the wrapper root cannot pick up phantom children.  macOS: the root is
    the user child ONLY (orphans go to launchd, never to the wrapper, and
    rooting at the wrapper would match the enumeration helpers themselves);
    session-escaped pipe holders are added by the ``lsof`` correlation.
    """
    if sys.platform == "linux":
        roots = (root_pid, os.getpid())
    else:
        roots = (root_pid,)
    found = _descendants_via_ppid(roots)
    found.update(_pipe_holder_pids(pipe_fds))
    found.discard(os.getpid())
    return found


def _signal_all(pids, signum) -> None:
    for pid in pids:
        try:
            os.kill(pid, signum)
        except OSError:
            pass  # already dead (or a pid that vanished mid-sweep)


def _sweep_process_tree(root_pid: int, threads, pipe_fds) -> bool:
    """Bounded post-exit cleanup of the whole descendant tree (P0-2).

    Repeats: reap zombies, enumerate live descendants, SIGTERM (first
    sighting), SIGKILL after ``SWEEP_TERM_GRACE_SECONDS`` — until BOTH drain
    threads have joined (EOF on both pipes, i.e. no holder left) AND no live
    descendant remains.  Returns False when ``PROCESS_SWEEP_BUDGET_SECONDS``
    is exceeded: the caller must then fail the wrapper without an envelope.
    """
    deadline = time.monotonic() + PROCESS_SWEEP_BUDGET_SECONDS
    term_deadline = None
    while True:
        _reap_zombies()
        live = _live_descendant_pids(root_pid, pipe_fds)
        now = time.monotonic()
        if live:
            if term_deadline is None:
                _signal_all(live, signal.SIGTERM)
                term_deadline = now + SWEEP_TERM_GRACE_SECONDS
            elif now >= term_deadline:
                _signal_all(live, signal.SIGKILL)
        pipes_eof = all(not thread.is_alive() for thread in threads)
        if pipes_eof:
            _reap_zombies()
            if not _live_descendant_pids(root_pid, pipe_fds):
                return True
        if now >= deadline:
            return False
        time.sleep(SWEEP_POLL_INTERVAL_SECONDS)
# === end P0-2 ================================================================


def _kill_process_group(pgid: int) -> bool:
    """SIGKILL every current member of the process group (§7.1 实施方式 6).

    Returns True iff the signal was delivered to a still-existing process
    group.  ProcessLookupError means the group was already dead, which in
    the cancel-marker loop is the narrow rule-3 window: the child exited
    on its own between ``poll()`` and ``killpg()``, so the wrapper must
    NOT claim ``cancelObserved=true``.
    """
    try:
        os.killpg(pgid, signal.SIGKILL)
        return True
    except ProcessLookupError:
        return False
    except OSError:
        return False  # cannot signal at all


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


def _flush_quietly(fileobj) -> None:
    if fileobj is None:
        return
    try:
        if not fileobj.closed:
            fileobj.flush()
    except (OSError, ValueError):
        pass


# D15 §4.2 (Scenario B): the four AF_TASK_* env vars that the wrapper must
# see in taskEnvironment before it will spawn the user child. Missing any of
# them is fail-closed (no spawn, no global sitecustomize fallback) per
# D15 §6 red line 4.
#
# D15 §4.2.3 round-4 (codex 56976668 MUST-FIX #3): the canonical constant
# and the validation logic now live in app.payload_contract (single source
# of truth shared with models.BoundedExecRequest). The line below re-exports
# the constant under this module's old name so existing imports keep
# resolving during the transition; new code should import from
# app.payload_contract directly.
REQUIRED_TASK_ENV_KEYS = tuple(sorted(ALLOWED_TASK_ENV_KEYS))


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

    # D15 §4.2 (Scenario B): taskWorkspace + taskEnvironment are the
    # task-local replacement for the old global /sandbox/sitecustomize.py.
    # The wrapper resolves them here and rejects anything that would force a
    # silent fallback to writing a global bootstrap file (D15 §6 red line 4).
    task_workspace = payload.get("taskWorkspace")
    if not isinstance(task_workspace, str) or not task_workspace:
        raise WrapperInputError(
            "taskWorkspace must be a non-empty string "
            "(D15 §4.2: AF_TASK_* isolation requires a task-local workspace "
            "path; missing it is fail-closed, not a fallback to the legacy "
            "global sitecustomize.py)"
        )

    task_env_payload = payload.get("taskEnvironment")
    if not isinstance(task_env_payload, dict):
        raise WrapperInputError(
            "taskEnvironment must be a JSON object of strings "
            "(D15 §4.2: AF_TASK_* must travel in the task-local wrapper "
            "input, not the shared global sitecustomize.py)"
        )
    task_environment: dict[str, str] = {}
    for key, value in task_env_payload.items():
        if not isinstance(value, str):
            raise WrapperInputError(
                f"taskEnvironment.{key} must be a string"
            )
        task_environment[key] = value
    missing_keys = [
        key for key in REQUIRED_TASK_ENV_KEYS if not task_environment.get(key)
    ]
    if missing_keys:
        raise WrapperInputError(
            "taskEnvironment is missing required keys: "
            f"{', '.join(missing_keys)} (D15 §4.2: each AF_TASK_* variable "
            "MUST be present and non-empty before the wrapper will spawn; "
            "no silent fallback to a global sitecustomize.py is permitted)"
        )

    # D15 §4.2 (Scenario B) round-2 (codex fe54d9f0 MUST-FIX #1 + #2):
    # loaderPythonPath is the workdir the legacy sitecustomize used to
    # prepend to sys.path so the user child can import af_dataset_loader
    # and friends. It is REQUIRED: if it is missing or empty the user
    # child would silently lose visibility of af_dataset_loader, which
    # violates the D15 §4.2 "task environment cannot be established ->
    # fail-closed" rule. There is no backwards-compat path: D15 is a new
    # feature, every input must carry a real loader path.
    loader_python_path = payload.get("loaderPythonPath")
    if not isinstance(loader_python_path, str) or not loader_python_path:
        raise WrapperInputError(
            "loaderPythonPath must be a non-empty string "
            "(D15 §4.2: the user child needs the loader workdir on "
            "sys.path so it can import af_dataset_loader; missing or "
            "empty is fail-closed, not a silent loss of import visibility)"
        )

    # D15 §4.2.3 round-4 (codex 56976668 MUST-FIX #3): single contract.
    # Replaces the round-2 _validate_task_env_consistency helper. Calls
    # the shared validate_payload_contract function in app.payload_contract
    # so both this runtime parser and the pydantic model
    # (models.BoundedExecRequest) enforce identical field-level invariants
    # AND the wrapper-only filesystem-anchored invariants (workspace must
    # equal wrapper-input.json parent dir; scriptPath must live at-or-inside
    # workspace). The model validator calls the same function without
    # wrapper_input_path (no fs context at HTTP validation time).
    #
    # What the contract closes (vs round-3):
    #   * Whitelist: taskEnvironment may only carry the four AF_TASK_* keys;
    #     PYTHONPATH/PYTHONHOME/PYTHONSTARTUP or any unknown key is rejected
    #     so a stale sitecustomize cannot be re-activated via smuggled env.
    #   * Containment anchor: taskWorkspace's realpath MUST equal the
    #     wrapper-input.json parent dir's realpath. Without this, the
    #     workspace could be "/", "..", or a symlink to an external target,
    #     defeating every sub-path check below it.
    #   * Strict-beneath: AF_TASK_ARTIFACT_DIR / TMP_DIR / METRICS_PATH must
    #     be STRICTLY inside workspace (not equal to it).
    #   * scriptPath at-or-inside workspace (filesystem-anchored).
    try:
        validate_payload_contract(payload, wrapper_input_path=str(path))
    except PayloadContractError as exc:
        raise WrapperInputError(str(exc)) from exc

    # D15 §4.2.3 round-4 (codex 56976668 MUST-FIX #2): filesystem existence
    # and type checks the contract cannot do (it only resolves realpath;
    # it does not assert the resolved path exists with the right type).
    # These run AFTER the contract so the error message a caller sees is
    # the contract violation first (whitelist / anchor / containment),
    # then the filesystem evidence.
    script_path_real = os.path.realpath(script_path)
    if not os.path.isfile(script_path_real):
        raise WrapperInputError(
            f"scriptPath={script_path!r} must be an existing regular "
            f"file inside taskWorkspace (D15 §4.2.3 round-4 codex "
            f"56976668 MUST-FIX #2: a missing path, a directory, or a "
            f"non-regular file is fail-closed — without this, a smuggled "
            f"directory or special file could let the wrapper bootstrap "
            f"an attacker-controlled loader)"
        )
    loader_python_path_real = os.path.realpath(loader_python_path)
    if not os.path.isdir(loader_python_path_real):
        raise WrapperInputError(
            f"loaderPythonPath={loader_python_path!r} must be an existing "
            f"directory (D15 §4.2.3 round-4 codex 56976668 MUST-FIX #2: "
            f"loaderPythonPath is the workdir the user child needs on "
            f"sys.path so it can import af_dataset_loader; a missing path "
            f"or a non-directory path is fail-closed — without this the "
            f"user child would either silently lose import visibility or "
            f"import from an attacker-controlled file)"
        )

    # runtimeEnvironmentPath is part of the §7.1 input shape but belongs to
    # work package D's schema (runtime_environment.py); the wrapper does not
    # consume it.
    # D11 (task #108): cancelMarkerPath is OPTIONAL for backward
    # compatibility with pre-D11 inputs; when present it must be a
    # non-empty string and is exact-bound-verified against the task-local
    # control derivation in main() before anything runs.
    cancel_marker_path = payload.get("cancelMarkerPath")
    if cancel_marker_path is not None and (
        not isinstance(cancel_marker_path, str) or not cancel_marker_path
    ):
        raise WrapperInputError(
            "cancelMarkerPath must be a non-empty string when present"
        )
    return {
        "script_path": script_path,
        "timeout_seconds": timeout_seconds,
        "limits": limits,
        "task_workspace": task_workspace,
        "task_environment": task_environment,
        "loader_python_path": loader_python_path,
        "cancel_marker_path": cancel_marker_path,
    }


def _write_loader_bootstrap(
    *, task_workspace: str, loader_path: str
) -> Path:
    """D15 §4.2.3 (Scenario B) round-2 (codex fe54d9f0 MUST-FIX core bug).

    Write a per-task ``loader_bootstrap.py`` into
    ``{task_workspace}/_bootstrap/`` and return its path. The bootstrap is
    the entry point the user child runs as ``__main__``; AFTER Python's
    site init phase has finished it inserts ``loader_path`` into
    ``sys.path`` and then runs the user script via ``runpy.run_path``.

    Why this design (and not "just delete the stale sitecustomize before
    spawn"): the loader workdir is typically a CONTAINER-GLOBAL directory
    like ``/sandbox``. The previous task may have left a
    ``sitecustomize.py`` there if its cleanup failed. Deleting that file
    right before spawn would still be (a) a write to a shared global path
    racing with any sibling wrapper invocation and (b) a TOCTOU window
    between unlink() and the child's site init. codex fe54d9f0 explicitly
    forbids that "rm before spawn" pattern as a correctness fix.

    The bootstrap approach instead makes the stale sitecustomize
    HARMLESS: by giving the interpreter a bootstrap file that lives under
    the per-task workspace (which the wrapper itself freshly created and
    which therefore cannot host any prior task's sitecustomize), the
    loader workdir is NEVER on the site-init sys.path. Site init finishes
    with no auto-import of any sitecustomize. Only AFTER site init does
    the bootstrap add ``loader_path`` to sys.path, which is enough for
    ``import af_dataset_loader`` to work, while never exposing the
    interpreter's startup to a stale sitecustomize.

    Failure to write the bootstrap is fail-closed: the wrapper raises
    rather than spawning the user child with a direct
    ``[python, script]`` invocation (the latter would silently re-introduce
    the very sitecustomize auto-import race this fix closes).
    """
    if not task_workspace:
        raise WrapperInputError(
            "taskWorkspace is required to stage the loader bootstrap "
            "(D15 §4.2.3 round-2: the bootstrap lives under the per-task "
            "workspace, never a shared global path)"
        )
    if not loader_path:
        raise WrapperInputError(
            "loaderPythonPath is required to stage the loader bootstrap "
            "(D15 §4.2.3 round-2: the user child needs the loader workdir "
            "on sys.path; missing or empty is fail-closed)"
        )

    bootstrap_dir = Path(task_workspace) / LOADER_BOOTSTRAP_DIR_NAME
    # D15 §4.2.3 round-4 (codex 56976668 MUST-FIX #2): if `_bootstrap` is
    # already a symlink, do NOT follow it. The wrapper runs as root in
    # production; a caller-planted symlink at `_bootstrap -> /etc/cron.d`
    # would let mkdir(exist_ok=True) succeed (the target dir exists) and
    # then let bootstrap_path.write_text / chmod land on an attacker-chosen
    # EXTERNAL path. The is_symlink() check MUST run before mkdir because
    # mkdir would silently follow the symlink.
    if bootstrap_dir.is_symlink():
        raise WrapperInputError(
            f"refusing to stage loader bootstrap: {bootstrap_dir} is a "
            f"symlink (D15 §4.2.3 round-4 codex 56976668 MUST-FIX #2: a "
            f"pre-planted symlink would let the root wrapper chmod/write "
            f"an attacker-chosen external target via the symlink; "
            f"fail-closed, no follow)"
        )
    bootstrap_dir.mkdir(parents=True, exist_ok=True)
    # D15 §4.2.3 round-3 (codex c9fee2f9 MUST-FIX #1): bootstrap dir stays
    # world-traversable (0o755) — a hardened permission here has no meaning
    # since the wrapper and the user child share the same (non-root, container
    # level) uid; the mode is kept for plain filesystem hygiene.
    os.chmod(bootstrap_dir, 0o755)
    bootstrap_path = bootstrap_dir / LOADER_BOOTSTRAP_FILE_NAME
    # D15 §4.2.3 round-4 (codex 56976668 MUST-FIX #2): defense in depth —
    # also reject a pre-planted symlink at the bootstrap FILE path. mkdir
    # already created _bootstrap as a real dir above, so the only way for
    # loader_bootstrap.py to be a symlink now is if a concurrent actor
    # planted it between mkdir and this check. The wrapper is pre-spawn
    # at this point (no user child exists yet), so the attacker would have
    # to be a sibling wrapper invocation or a process outside the sandbox
    # — but the check is cheap and the consequence of following such a
    # symlink (root write to attacker-chosen path) is severe enough to
    # justify the belt-and-suspenders.
    if bootstrap_path.is_symlink():
        raise WrapperInputError(
            f"refusing to stage loader bootstrap: {bootstrap_path} is a "
            f"symlink (D15 §4.2.3 round-4 codex 56976668 MUST-FIX #2: a "
            f"pre-planted file symlink would let the root wrapper write "
            f"attacker-chosen content to an external target; fail-closed, "
            f"no follow)"
        )

    # Build the bootstrap body by plain string concatenation. Do NOT use
    # textwrap.dedent on an f-string here: any change in indentation of
    # the surrounding literal would silently shift the embedded code and
    # break Python syntax. A flat template with a single .replace() for
    # the loader path keeps the body readable AND indentation-safe.
    body = (
        "# Auto-generated by bounded_exec_wrapper "
        "(D15 §4.2.3 Scenario B round-2 + round-3 fix).\n"
        "#\n"
        "# This bootstrap runs AFTER Python's site initialization phase\n"
        "# has completed, so any stale sitecustomize.py that may still\n"
        "# live in the loader workdir is NOT auto-imported at startup.\n"
        "# Only AFTER site init does this bootstrap insert the loader\n"
        "# workdir into sys.path, then run the user script via runpy.\n"
        "#\n"
        "# Round-3 (codex c9fee2f9 MUST-FIX #2): also restore direct-script\n"
        "# sys.path semantics — `python user_script.py` puts user_script's\n"
        "# parent directory at sys.path[0], enabling sibling imports like\n"
        "# `import sibling_module` to find same-directory modules.\n"
        "# runpy.run_path does NOT do this automatically; we replicate it\n"
        "# here. User script dir wins over loader modules of same name\n"
        "# (matches direct `python user_script.py` priority).\n"
        "import sys\n"
        "import runpy\n"
        "import os\n"
        "\n"
        'LOADER_PATH = "__LOADER_PATH__"\n'
        "USER_SCRIPT = sys.argv[1]\n"
        "USER_ARGS = sys.argv[2:]\n"
        "sys.argv = [USER_SCRIPT] + USER_ARGS\n"
        "# Insert in reverse priority order so final order is\n"
        "# [user_script_dir, LOADER_PATH, ...] — user script siblings win.\n"
        "if LOADER_PATH:\n"
        "    sys.path.insert(0, LOADER_PATH)\n"
        "user_script_dir = os.path.dirname(os.path.abspath(USER_SCRIPT))\n"
        "if user_script_dir:\n"
        "    sys.path.insert(0, user_script_dir)\n"
        'runpy.run_path(USER_SCRIPT, run_name="__main__")\n'
    )
    # The loader path may contain characters that need escaping inside a
    # Python string literal (backslashes on Windows, quotes in pathological
    # paths). Use repr() to obtain a safe Python-source representation,
    # then strip the outer single quotes so we can embed it inside our own
    # double-quoted literal deterministically. Re-validate by parsing the
    # final body before writing: if ast.parse fails we abort the spawn.
    safe_loader_literal = repr(loader_path)[1:-1]
    body = body.replace("__LOADER_PATH__", safe_loader_literal)

    import ast as _ast
    try:
        _ast.parse(body)
    except SyntaxError as exc:
        # Should be impossible given the construction above, but a
        # pathological loader_path (e.g. one containing a literal newline
        # after repr) could still trip us. Fail-closed: do NOT spawn with
        # a broken bootstrap, do NOT silently fall back to direct mode.
        raise WrapperInputError(
            "internal error: generated loader bootstrap is not valid "
            f"Python (loader_path={loader_path!r}): {exc}"
        ) from exc

    try:
        # unlink before write so a stale bootstrap from a previous run in
        # the same task_workspace (defensive: tests do this, production
        # gets a fresh task_workspace each task) cannot survive. Errors
        # from a missing file are ignored by unlink(missing_ok=True).
        bootstrap_path.unlink(missing_ok=True)
        bootstrap_path.write_text(body, encoding="utf-8")
    except OSError as exc:
        raise WrapperInputError(
            "failed to stage the loader bootstrap at "
            f"{bootstrap_path}: {exc} (D15 §4.2.3 round-2: staging the "
            "task-local bootstrap is a hard spawn gate, no fallback)"
        ) from exc

    # D15 §4.2.3 round-3 legacy hardening: keep the bootstrap read-only
    # (0o444). The wrapper and the user child share the same container-level
    # uid, so this is hygiene (accidental overwrite protection), not a
    # privilege boundary.
    try:
        os.chmod(bootstrap_path, 0o444)
    except OSError as exc:
        raise WrapperInputError(
            "failed to set read-only permissions on the loader bootstrap at "
            f"{bootstrap_path}: {exc} (D15 §4.2.3 round-3: bootstrap stays "
            "read-only for hygiene, fail-closed if chmod fails)"
        ) from exc

    return bootstrap_path


def _task_control_root() -> str:
    """The control root for cancel markers (env override aware)."""
    override = os.environ.get(TASK_CONTROL_ROOT_ENV_NAME)
    if override and override.strip():
        return override.strip().rstrip("/")
    return TASK_CONTROL_ROOT_DEFAULT


def expected_cancel_marker_path(script_path: str) -> str:
    """The ONLY cancel marker path this wrapper run may accept (D11).

    The runner builds every task workspace as ``<workspace_root>/<taskId>``
    (sandbox_runner ``_prepare_task_workspace``) and stages the user script
    directly inside it, so the script's parent directory name IS the
    taskId.  The marker must be exactly ``<control_root>/<taskId>/cancel`` —
    any other path (another task's marker, a child-suggested location) is
    rejected fail-closed by the binding check in main().
    """
    task_id = PurePosixPath(script_path).parent.name
    return f"{_task_control_root()}/{task_id}/{CANCEL_MARKER_FILE_NAME}"


def _cancel_marker_exists(marker_path: str) -> bool:
    """True iff the cancel marker file is observable right now (D11).

    Any error is treated as "no marker": cancellation is fail-observe — a
    marker that cannot be stat'ed must not change the run, because only an
    OBSERVED marker is cancellation evidence (d6841a2e rules 2/3).
    """
    try:
        return os.path.exists(marker_path)
    except OSError:
        return False


def run_bounded_capture(
    *,
    script_path: str,
    timeout_seconds: float,
    limits: dict,
    capture_dir: Path,
    task_workspace: str | None = None,
    task_environment: dict[str, str] | None = None,
    workdir_for_pythonpath: str | None = None,
    cancel_marker_path: str | None = None,
) -> tuple:
    """Run the user script under bounded capture.

    Returns ``(summary, capture_files, sweep_ok)``:

    * ``summary`` — the 14 frozen capture-result.json fields;
    * ``capture_files`` — the STILL-OPEN pre-spawn capture file objects
      (name -> file object) the caller must read via
      ``capture_reader.read_capture_files_from_fds`` and then close;
    * ``sweep_ok`` — False when the post-exit process-tree sweep exceeded its
      budget: the caller must exit non-zero WITHOUT emitting the envelope.

    ``cancel_marker_path`` (260809-26Q3 D11, task #108): when not None the
    deadline loop polls this path on every iteration.  OBSERVING the marker
    while the child is still alive is the ONLY valid cancellation evidence
    (d6841a2e rule 2): the wrapper then kills the entire process group
    exactly like a timeout and reports ``cancelObserved=true``.  A marker
    observed only AFTER the child already exited changes nothing (rule 3 —
    the genuine exit result stands, ``cancelObserved`` stays false).

    Writes ``capture-result.json`` through its pre-opened fd before
    returning.  If the spawn itself failed the wrapper exits non-zero and
    leaves NO summary — no child ever ran, so no result may be reported
    (codex 02953ca7 "no child, no summary").
    On any internal exception everything is closed and the exception is
    re-raised.

    D15 §4.2 (Scenario B): ``task_workspace`` and ``task_environment`` carry
    the AF_TASK_* variables that previously lived in the shared global
    /sandbox/sitecustomize.py. The wrapper performs makedirs / chdir /
    sys.path setup itself pre-spawn, then injects the env into the user
    child via Popen(env=...). Fail-closed: caller guarantees both are
    non-empty (parse_wrapper_input rejects missing required keys).
    """
    capture_path = Path(capture_dir)
    capture_path.mkdir(parents=True, exist_ok=True)
    os.chmod(capture_path, 0o700)

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
        # 260809-26Q3 D11 (task #108): the cancellation-evidence flag.  True
        # ONLY when this wrapper OBSERVED the cancel marker while the child
        # was still alive and therefore killed its process group itself
        # (d6841a2e rule 2).  A kill issued anywhere else (or a marker that
        # appeared after the child's normal exit) leaves it false — rules 3/4.
        "cancelObserved": False,
    }

    capture_files: dict = {}
    result_file = stdout_file = stderr_file = None
    records: _RecordChannel | None = None
    audit: _UnknownMarkerAudit | None = None
    proc: subprocess.Popen | None = None
    timed_out = False
    # D11: set ONLY by the deadline loop when it observes the cancel marker
    # while the child is still alive — the wrapper's own kill of its own
    # process group is the cancellation evidence (d6841a2e rule 2).
    canceled = False
    spawned = False
    success = False

    try:
        # --- pre-spawn capture file creation (fd-pinned readback, P0-4) ----
        result_file = _open_capture_file(
            capture_path / CAPTURE_RESULT_FILE_NAME, text=True
        )
        capture_files[CAPTURE_RESULT_FILE_NAME] = result_file
        stdout_file = _open_capture_file(capture_path / STDOUT_FILE_NAME)
        capture_files[STDOUT_FILE_NAME] = stdout_file
        stderr_file = _open_capture_file(capture_path / STDERR_FILE_NAME)
        capture_files[STDERR_FILE_NAME] = stderr_file

        stdout_sink = _BoundedByteSink(stdout_file, limits["stdoutMaxBytes"])
        stderr_sink = _BoundedByteSink(stderr_file, limits["stderrMaxBytes"])
        # ONE joint recordChannelMaxBytes budget for the two record-channel
        # files: known v1 records and unknown-marker audit lines (§4.1/§4.2).
        joint_budget = _JointByteBudget(limits["recordChannelMaxBytes"])
        records = _RecordChannel(
            capture_path / RECORDS_FILE_NAME,
            limits["recordChannelMaxRecords"],
            joint_budget,
        )
        capture_files[RECORDS_FILE_NAME] = records.fileobj
        audit = _UnknownMarkerAudit(
            capture_path / UNKNOWN_MARKER_AUDIT_FILE_NAME, joint_budget
        )

        classifier = _StreamingStdoutClassifier(
            stdout_sink, records, audit, joint_budget
        )

        def on_stdout_chunk(chunk: bytes) -> None:
            # Single-threaded: only the stdout drain thread ever calls this.
            classifier.feed(chunk)

        def on_stderr_chunk(chunk: bytes) -> None:
            stderr_sink.write(chunk)

        def drain_pipe(fd: int, on_chunk) -> None:
            # os.read returns whatever is available without waiting for a full
            # buffer, so the child's pipe is drained continuously from the
            # first byte to EOF — the child can never block on a full pipe.
            while True:
                try:
                    chunk = os.read(fd, _READ_CHUNK_SIZE)
                except OSError:
                    break
                if not chunk:
                    break
                on_chunk(chunk)

        # P0-2 + codex 02953ca7: becoming the subreaper is a HARD spawn
        # gate on Linux — without it a setsid grandchild that closes its
        # inherited pipes hides from BOTH sweep signals.  On prctl failure
        # this raises BEFORE any Popen: no child, no summary.
        _set_child_subreaper()

        # D15 §4.2 (Scenario B): the wrapper performs the makedirs/chdir/
        # sys.path setup that the legacy global sitecustomize.py used to do
        # at import time. makedirs is idempotent; chdir is achieved by
        # passing cwd= to Popen (the child resolves its own cwd on exec);
        # sys.path gets the loader-module dir (workdir) via PYTHONPATH on
        # the child env. All three are now per-task, in-wrapper, with no
        # global file write.
        if task_environment is not None:
            for sub_dir in (
                task_environment.get("AF_TASK_ARTIFACT_DIR"),
                task_environment.get("AF_TASK_TMP_DIR"),
            ):
                if sub_dir:
                    Path(sub_dir).mkdir(parents=True, exist_ok=True)
        spawn_cwd = (
            task_workspace
            if task_workspace
            else str(Path(script_path).resolve().parent)
        )

        # Build the child env: wrapper's own env + AF_TASK_* (task-scoped).
        # D15 §4.2.3 (Scenario B) round-2 (codex fe54d9f0 MUST-FIX core bug):
        # the loader workdir is NO LONGER placed on PYTHONPATH here. A
        # directory on PYTHONPATH that contains a stale sitecustomize.py
        # would have its sitecustomize auto-imported by the Python
        # interpreter DURING site init (BEFORE any user code runs), and
        # that legacy sitecustomize could overwrite AF_TASK_* back to a
        # previous task's values. The user child instead receives the
        # loader workdir via a task-local bootstrap (see below), which
        # inserts the workdir into sys.path AFTER site init has finished.
        child_env = os.environ.copy()
        if task_environment:
            for key, value in task_environment.items():
                child_env[key] = value

        # D15 §4.2.3 (Scenario B) round-2: write a task-local loader
        # bootstrap into {task_workspace}/_bootstrap/loader_bootstrap.py
        # and run the user script THROUGH it. The bootstrap is generated
        # per-task, lives under the per-task workspace (which is freshly
        # created for THIS task and can never host a stale sitecustomize
        # from a previous task), and its only job is: AFTER Python's site
        # init phase has ended (so any stale sitecustomize in the loader
        # workdir has lost its chance to be auto-imported at startup),
        # insert the loader workdir into sys.path, then run the user
        # script via runpy.run_path under __main__ so user code sees the
        # same __name__ / argv it would have seen under the direct
        # `[python, script]` invocation. Failure to write the bootstrap
        # is fail-closed: no Popen, no spawn, no silent fallback.
        bootstrap_path = _write_loader_bootstrap(
            task_workspace=task_workspace,
            loader_path=workdir_for_pythonpath,
        )

        try:
            # The child's stdout/stderr are the capture pipes ONLY: the child
            # never inherits or shares the wrapper's own stdout fd, which
            # later carries the single bounded envelope (PIN 2).
            #
            # D15 §4.2.3 round-2: the Python interpreter is given
            # ``loader_bootstrap.py`` as __main__, NOT the user script. The
            # user script path travels as bootstrap's argv[1]; the bootstrap
            # runs it via runpy.run_path(..., run_name="__main__") so the
            # user code still observes __name__ == "__main__" and the same
            # sys.argv shape it would have seen under direct invocation.
            proc = subprocess.Popen(
                [sys.executable, str(bootstrap_path), str(script_path)],
                stdin=subprocess.DEVNULL,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                cwd=spawn_cwd,
                env=child_env,
                start_new_session=True,  # child owns a new process group
            )
        except OSError:
            proc = None
            raise
        spawned = True
        pgid = proc.pid  # after setsid(), the child is its own group leader

        # Each drain thread owns its own objects (classifier/sinks/records/
        # audit are touched by the stdout thread only, stderr_sink by the
        # stderr thread only), so no lock is needed between them.
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

        # Timeout + cancel-marker monitoring on the main thread while the
        # readers drain.  The marker is polled at the head of every loop
        # iteration (≤0.2s granularity, bounded by proc.wait below).
        deadline = time.monotonic() + float(timeout_seconds)
        while True:
            if cancel_marker_path is not None and _cancel_marker_exists(
                cancel_marker_path
            ):
                # d6841a2e rule 2: the wrapper OBSERVED the marker.  That
                # observation is cancellation evidence ONLY when it causes
                # this wrapper to kill its own still-running child group.
                # Rule 3 narrow window: poll() says alive, but between
                # poll() and killpg() the child exited — the kill returns
                # False and canceled stays False so the genuine result
                # stands (codex c6c49248 review).
                if proc.poll() is None:
                    if _kill_process_group(pgid):
                        canceled = True
                # Rule 3: the child already exited on its own (poll() is not
                # None) — the marker arrived too late to matter.  Break with
                # canceled still False so the genuine exit result stands; a
                # late marker must NEVER rewrite a real SUCCEEDED/FAILED.
                break
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

        if timed_out or canceled:
            # Same reap discipline for timeout kills and cancel kills: both
            # SIGKILL the whole process group and must leave no survivor.
            try:
                proc.wait(timeout=PROCESS_SWEEP_BUDGET_SECONDS)
            except subprocess.TimeoutExpired:
                pass
            # Sweep any process spawned between the kill and the reap.
            _kill_process_group(pgid)

        # P0-2: bounded sweep — EOF on both pipes follows only the death of
        # the LAST pipe holder, so join the drains AND kill every surviving
        # descendant within the budget (or fail without an envelope).
        sweep_ok = _sweep_process_tree(
            proc.pid,
            (stdout_thread, stderr_thread),
            (proc.stdout.fileno(), proc.stderr.fileno()),
        )
        _close_quietly(proc.stdout)
        _close_quietly(proc.stderr)

        classifier.finalize()

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
                # D11: whether THIS wrapper observed the marker and killed
                # its own child group (the only cancellation evidence).
                "cancelObserved": canceled,
            }
        )

        exit_code = proc.returncode
        if exit_code is None:
            exit_code = 1
        elif (timed_out or canceled) and exit_code == 0:
            # Defensive: a run the wrapper force-killed (timeout or cancel)
            # never reports success, even if the kill raced a clean exit.
            exit_code = 124
        summary["exitCode"] = exit_code

        # Presence in the envelope means "the wrapper held that fd": a
        # dropped record batch closed+unlinked its file, and the audit file
        # exists iff at least one audit line was stored.
        if records.dropped:
            capture_files.pop(RECORDS_FILE_NAME, None)
        if not (audit.fileobj is not None and audit.stored_lines > 0):
            capture_files.pop(UNKNOWN_MARKER_AUDIT_FILE_NAME, None)

        success = True
        return summary, capture_files, sweep_ok
    finally:
        # No child, no summary (codex 02953ca7 stop condition): when the
        # spawn never happened a summary would fabricate a result.  Once a
        # child ran, the summary is mandatory — even on internal failure it
        # reports the frozen ``exitCode: 127`` spawn-failure state.
        if result_file is not None and spawned:
            try:
                _write_capture_result_to_handle(result_file, summary)
            except (OSError, ValueError):
                pass
        _flush_quietly(stdout_file)
        _flush_quietly(stderr_file)
        if records is not None:
            _flush_quietly(records.fileobj)
        if audit is not None:
            _flush_quietly(audit.fileobj)
        if not success:
            for handle in capture_files.values():
                _close_quietly(handle)
            if proc is not None:
                _close_quietly(proc.stdout)
                _close_quietly(proc.stderr)


def _write_capture_result_to_handle(handle, summary: dict) -> None:
    """Write the §7.1 summary through its PRE-OPENED capture fd.

    The fd was opened before the child spawned, so the write lands in the
    genuine capture file even if the child renamed or replaced the capture
    directory while it ran (fd-pinned readback, P0-4).
    """
    text = json.dumps(summary, ensure_ascii=False, indent=2) + "\n"
    handle.seek(0)
    handle.write(text)
    handle.flush()
    os.ftruncate(handle.fileno(), handle.tell())


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

    # === 260809-26Q3 D11 (task #108): cancel-marker binding gate ============
    # The runner passed the marker path it created for THIS task.  EXACT
    # BINDING: the path must equal the control path derived from scriptPath
    # (the script's parent directory name IS the taskId).  A mismatched path
    # (another task's marker, a child-suggested location, a stale value) is
    # rejected: exit 2, no child, no summary.
    cancel_marker_path = parsed["cancel_marker_path"]
    if cancel_marker_path is not None:
        expected_marker_path = expected_cancel_marker_path(parsed["script_path"])
        if cancel_marker_path != expected_marker_path:
            # Diagnostics only — never user content (§18 stop condition).
            sys.stderr.write(
                "bounded_exec_wrapper: cancelMarkerPath does not match the "
                "task control path derived from scriptPath\n"
            )
            return 2
    # === end D11 gate ========================================================

    capture_dir = input_path.resolve().parent / CAPTURE_DIR_NAME
    try:
        summary, capture_files, sweep_ok = run_bounded_capture(
            script_path=parsed["script_path"],
            timeout_seconds=parsed["timeout_seconds"],
            limits=parsed["limits"],
            capture_dir=capture_dir,
            task_workspace=parsed["task_workspace"],
            task_environment=parsed["task_environment"],
            workdir_for_pythonpath=parsed.get("loader_python_path"),
            cancel_marker_path=cancel_marker_path,
        )
    except WrapperInputError as exc:
        # D15 §4.2.3 round-2: _write_loader_bootstrap raises WrapperInputError
        # on staging failure (no spawn gate). Surface the diagnostic verbatim
        # so the operator sees the underlying cause; never user content
        # (§18 stop condition — paths only).
        sys.stderr.write(f"bounded_exec_wrapper: {exc}\n")
        return 2
    except Exception as exc:  # last-resort guard; type name only (§18)
        sys.stderr.write(
            f"bounded_exec_wrapper: internal error: {type(exc).__name__}\n"
        )
        return 1

    if not sweep_ok:
        # P0-2: a runaway descendant outlived the sweep budget — reporting
        # success now would be a lie.  The summary is on disk; no envelope.
        sys.stderr.write(
            "bounded_exec_wrapper: process-tree sweep incomplete within "
            "budget\n"
        )
        for handle in capture_files.values():
            _close_quietly(handle)
        return 1

    # === work-package-C: wrapper-tail readback (PIN 1 + PIN 2) =============
    # The user child has exited and the capture files are finalized.  The
    # trusted reader — bound into memory BEFORE the spawn (PIN 1, top-level
    # import) — performs the bounded readback IN MEMORY with the four frozen
    # §13 limits, through EXACTLY the fds opened before the spawn (zero path
    # resolution after spawn).  Nothing located in the user-writable task
    # workspace is executed or re-imported after user code ran: a user
    # overwrite of the staged capture_reader.py (or of this wrapper file) is
    # harmless.
    try:
        envelope = capture_reader.read_capture_files_from_fds(
            {
                name: handle.fileno()
                for name, handle in capture_files.items()
            },
            stdout_max_bytes=parsed["limits"]["stdoutMaxBytes"],
            stderr_max_bytes=parsed["limits"]["stderrMaxBytes"],
            record_channel_max_bytes=parsed["limits"]["recordChannelMaxBytes"],
            record_channel_max_records=parsed["limits"][
                "recordChannelMaxRecords"
            ],
        )
    except (OSError, ValueError) as exc:
        # Fail closed: SHORT diagnostic only — file names/sizes/caps, never
        # capture CONTENT (§18).  The host fails the task on this nonzero
        # terminal exit; stdout stays EMPTY on the failure path.
        sys.stderr.write(
            f"bounded_exec_wrapper: capture readback failed: {exc}\n"
        )
        return 1
    finally:
        for handle in capture_files.values():
            _close_quietly(handle)
    # PIN 2: stdout carries EXACTLY ONE bounded envelope JSON document and
    # zero other bytes before or after it (no trailing newline).
    sys.stdout.write(json.dumps(envelope))
    sys.stdout.flush()
    # === end work-package-C =================================================
    return 0


if __name__ == "__main__":
    sys.exit(main())
