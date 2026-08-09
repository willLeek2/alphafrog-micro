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
0700; P0-4: when the child runs unprivileged it must not be able to tamper
with the capture artifacts at all)::

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

UID privilege separation (P0-4, codex 03b4d034 / 76ee7296 / 691341d2):
when ``AF_SANDBOX_CHILD_USER`` is set (the runner exports it into the exec
environment), the wrapper resolves it via ``app.child_identity`` BEFORE the
spawn and drops the child into that identity in ``preexec_fn`` in the
kernel-mandated order ``setgroups([]) -> PR_SET_NO_NEW_PRIVS=1 ->
PR_CAP_AMBIENT_CLEAR_ALL -> setgid -> setuid -> capset(empty) -> exec``
(NO_NEW_PRIVS MUST succeed before any UID drop or exec; never attempt prctl
only after setuid).  The capability drop is EXPLICIT, never left to setuid's
implicit clearing (codex 02953ca7): after setuid the child writes empty
inheritable/permitted/effective sets with ``capset`` — the inheritable set
in particular is not covered by the uid transition's implicit behavior —
and the drop is then VERIFIED, not assumed, by reading
``/proc/self/status`` back in the child before exec: uid/gid must
match, ``CapInh/CapPrm/CapEff/CapAmb`` must all be zero and ``NoNewPrivs``
must be 1 — any mismatch raises, so Popen fails and the wrapper exits
non-zero with NO child and NO summary (the verification is Linux-only;
macOS dev mode has no ``/proc`` and claims no security boundary).  The
capability BOUNDING set is deliberately left in place: it can only be
dropped with CAP_SETPCAP, which the setuid drop itself removes, and with
NoNewPrivs=1 plus no file-capability binaries it is unexploitable.  Running
as root REQUIRES a resolvable non-root identity (uid AND gid both nonzero):
refusal is a short stderr diagnostic and a non-zero exit, with no child and
no summary.  When the wrapper is NOT root (dev mode), an unset variable
keeps the historical same-UID behavior (no security boundary is claimed
there), and a set variable is applied on a best-effort basis.

Wrapper-tail envelope (work-package-C rework, P0 fix): after the user child
exits and the capture files are finalized, the wrapper performs the bounded
readback IN MEMORY through ``capture_reader`` and emits the returned
envelope on its OWN stdout.  ``capture_reader`` and ``child_identity`` are
imported at wrapper process start, BEFORE the spawn (PIN 1): the staged
copies live in the user-writable task workspace, and binding them pre-spawn —
while no adversary is alive — is what makes them trusted; after user code
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
from app.child_identity import (
    CHILD_USER_ENV_NAME,
    ChildIdentityError,
    parse_child_spec,
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
    "PROCESS_SWEEP_BUDGET_SECONDS",
    "SWEEP_TERM_GRACE_SECONDS",
    "SWEEP_POLL_INTERVAL_SECONDS",
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
_PR_SET_NO_NEW_PRIVS = 38
# P0-4 capability floor (codex 76ee7296 + 02953ca7): clear the ambient set
# BEFORE the gid/uid drop; AFTER setuid an explicit capset empties the
# inheritable/permitted/effective sets — never rely on setuid's implicit
# clearing, which does not cover the inheritable set by assumption — and
# the drop is then VERIFIED (never assumed) by reading /proc/self/status.
_PR_CAP_AMBIENT = 47
_PR_CAP_AMBIENT_CLEAR_ALL = 2

# Linux capability structures for the explicit post-setuid capset (codex
# 02953ca7).  Version 3 addresses capability bits 0..63, so the kernel
# expects the version-3 header plus TWO ``_CapData`` words (bits 0-31 and
# 32-63).  All-zero data words = empty effective/permitted/inheritable.
_LINUX_CAPABILITY_VERSION_3 = 0x20080522


class _CapHeader(ctypes.Structure):
    _fields_ = [("version", ctypes.c_uint32), ("pid", ctypes.c_int)]


class _CapData(ctypes.Structure):
    _fields_ = [
        ("effective", ctypes.c_uint32),
        ("permitted", ctypes.c_uint32),
        ("inheritable", ctypes.c_uint32),
    ]

# /proc/self/status fields that MUST all read zero hex after the root-path
# privilege drop, plus the NoNewPrivs flag that must read 1.
_CAP_STATUS_FIELDS = ("CapInh", "CapPrm", "CapEff", "CapAmb")

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


def _set_no_new_privs() -> None:
    """Linux: PR_SET_NO_NEW_PRIVS for the child (blocks setuid re-escalation).

    Raises ``OSError`` when the kernel refuses — under root that failure must
    abort the spawn (the privilege drop is mandatory there).
    """
    if sys.platform != "linux":
        return
    libc = _libc()
    if libc is None:
        raise OSError("libc unavailable for prctl(PR_SET_NO_NEW_PRIVS)")
    if libc.prctl(_PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0:
        raise OSError("prctl(PR_SET_NO_NEW_PRIVS) failed")


def _clear_ambient_caps() -> None:
    """Linux: empty the ambient capability set BEFORE the gid/uid drop.

    Ambient caps survive execve for unprivileged binaries and would hand the
    child privileges the identity must not have.  NEVER clear the
    permitted/effective sets explicitly before the drop: setuid itself
    requires CAP_SETUID, so an early capset would break the mandatory drop
    (ordering trap, codex 76ee7296).  The explicit emptying of the
    inheritable/permitted/effective sets happens AFTER setuid instead, in
    ``_drop_caps_explicit`` (codex 02953ca7).  Raises ``OSError`` when the
    kernel refuses — under root that failure aborts the spawn.
    """
    if sys.platform != "linux":
        return
    libc = _libc()
    if libc is None:
        raise OSError("libc unavailable for prctl(PR_CAP_AMBIENT)")
    if libc.prctl(_PR_CAP_AMBIENT, _PR_CAP_AMBIENT_CLEAR_ALL, 0, 0, 0) != 0:
        raise OSError("prctl(PR_CAP_AMBIENT, PR_CAP_AMBIENT_CLEAR_ALL) failed")


def _drop_caps_explicit() -> None:
    """Linux: explicitly empty CapInh/CapPrm/CapEff with ``capset``.

    Codex 02953ca7 stop condition: the capability drop must NOT rely on
    setuid's implicit clearing — the inheritable set in particular is not
    guaranteed to be emptied by the root->non-root uid transition alone
    (keepcaps-style semantics), and a security drop must be written, not
    assumed.  So AFTER ``setuid`` (never before: the drop itself needs
    CAP_SETUID) the child calls ``capset`` with all-zero data words —
    dropping one's own capabilities requires no privilege — and the
    subsequent ``_assert_privilege_drop_complete`` re-reads all four sets
    from ``/proc/self/status`` in kernel truth.  Raises ``OSError`` when
    libc is unavailable or the syscall refuses: under root the child then
    never execs and the wrapper fails closed with no child and no summary.
    """
    if sys.platform != "linux":
        return
    libc = _libc()
    if libc is None:
        raise OSError("libc unavailable for capset")
    header = _CapHeader(version=_LINUX_CAPABILITY_VERSION_3, pid=0)
    data = (_CapData * 2)()  # zeroed: empty effective/permitted/inheritable
    if libc.capset(ctypes.byref(header), ctypes.byref(data)) != 0:
        raise OSError(f"capset failed: errno {ctypes.get_errno()}")


def _assert_privilege_drop_complete(uid: int, gid: int) -> None:
    """Verify the root-path privilege drop in KERNEL truth, never assume it.

    Codex 76ee7296 stop condition: after ``setuid`` the child reads
    ``/proc/self/status`` back BEFORE exec; ``CapInh/CapPrm/CapEff/CapAmb``
    must ALL be zero hex and ``NoNewPrivs`` must be 1 (plus euid/egid
    matching the requested identity).  Any mismatch raises — the forked
    child then never execs and the wrapper fails closed with no child and
    no summary.  Linux-only: macOS dev mode has no ``/proc`` and claims no
    security boundary, so the check is skipped there.
    """
    if sys.platform != "linux":
        return
    if os.geteuid() != uid or os.getegid() != gid:
        raise ChildIdentityError("post-drop uid/gid mismatch")
    try:
        with open("/proc/self/status", "r", encoding="ascii") as handle:
            status_text = handle.read()
    except OSError:
        raise ChildIdentityError("cannot verify privilege drop") from None
    fields: dict[str, str] = {}
    for line in status_text.splitlines():
        name, sep, value = line.partition(":")
        if sep:
            fields[name.strip()] = value.strip()
    for key in _CAP_STATUS_FIELDS:
        value = fields.get(key)
        if value is None:
            raise ChildIdentityError("cannot verify privilege drop")
        try:
            remaining = int(value, 16)
        except ValueError:
            raise ChildIdentityError("cannot verify privilege drop") from None
        if remaining != 0:
            raise ChildIdentityError("capabilities remain after drop")
    if fields.get("NoNewPrivs") != "1":
        raise ChildIdentityError("NoNewPrivs not set after drop")


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


def _kill_process_group(pgid: int) -> None:
    """SIGKILL every current member of the process group (§7.1 实施方式 6)."""
    try:
        os.killpg(pgid, signal.SIGKILL)
    except OSError:
        pass  # group already gone (or never fully started)


def _make_preexec(child_identity):
    """Build the child privilege-drop closure (P0-4, codex 03b4d034).

    Runs in the forked child BEFORE exec, in the kernel-mandated order
    (codex 76ee7296 + 02953ca7): ``setgroups([])`` (dump supplementary
    groups first), ``prctl(PR_SET_NO_NEW_PRIVS, 1)`` (MUST succeed before
    any UID drop or exec — it blocks setuid-binary re-escalation after the
    capability drop), ``prctl(PR_CAP_AMBIENT, PR_CAP_AMBIENT_CLEAR_ALL)``
    (ambient caps survive execve and must be empty), ``setgid``, ``setuid``
    (the point of no return; itself needs CAP_SETUID — never capset before
    it), then ``capset`` with all-zero data words to EXPLICITLY empty the
    inheritable/permitted/effective sets (never rely on setuid's implicit
    clearing, which does not cover the inheritable set by assumption).
    Under root every step is mandatory and the result is then VERIFIED by
    ``_assert_privilege_drop_complete`` (all four cap sets zero +
    NoNewPrivs=1 in kernel truth) — any failure raises and ``Popen`` fails,
    so the wrapper exits non-zero with NO child and NO summary.  The
    capability bounding set stays: dropping it needs CAP_SETPCAP, which the
    setuid drop removes, and with NoNewPrivs=1 plus no file-capability
    binaries it is unexploitable.  Not root (dev mode): best-effort — each
    step that the kernel refuses is skipped (a non-root process can only
    drop to identities it is already entitled to; no boundary is claimed).
    """
    if child_identity is None:
        return None
    uid, gid = child_identity

    def _preexec() -> None:
        if os.geteuid() == 0:
            os.setgroups([])
            _set_no_new_privs()
            _clear_ambient_caps()
            os.setgid(gid)
            os.setuid(uid)
            _drop_caps_explicit()
            _assert_privilege_drop_complete(uid, gid)
            return
        for step in (
            lambda: os.setgroups([]),
            _set_no_new_privs,
            _clear_ambient_caps,
            lambda: os.setgid(gid),
            lambda: os.setuid(uid),
            _drop_caps_explicit,
        ):
            try:
                step()
            except OSError:
                pass  # best-effort drop in dev mode

    return _preexec


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
REQUIRED_TASK_ENV_KEYS = (
    "AF_TASK_WORKSPACE",
    "AF_TASK_ARTIFACT_DIR",
    "AF_TASK_TMP_DIR",
    "AF_TASK_METRICS_PATH",
)


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

    # D15 §4.2: loaderPythonPath is the workdir the legacy sitecustomize
    # prepended to sys.path so the user child can import af_dataset_loader
    # and friends. Optional for backward-compat with old wrapper inputs in
    # tests, but production always sets it.
    loader_python_path = payload.get("loaderPythonPath")
    if loader_python_path is not None and (
        not isinstance(loader_python_path, str) or not loader_python_path
    ):
        raise WrapperInputError(
            "loaderPythonPath must be a non-empty string when present"
        )

    # runtimeEnvironmentPath is part of the §7.1 input shape but belongs to
    # work package D's schema (runtime_environment.py); the wrapper does not
    # consume it.
    return {
        "script_path": script_path,
        "timeout_seconds": timeout_seconds,
        "limits": limits,
        "task_workspace": task_workspace,
        "task_environment": task_environment,
        "loader_python_path": loader_python_path,
    }


def run_bounded_capture(
    *,
    script_path: str,
    timeout_seconds: float,
    limits: dict,
    capture_dir: Path,
    child_identity: tuple | None = None,
    task_workspace: str | None = None,
    task_environment: dict[str, str] | None = None,
    workdir_for_pythonpath: str | None = None,
) -> tuple:
    """Run the user script under bounded capture.

    Returns ``(summary, capture_files, sweep_ok)``:

    * ``summary`` — the 13 frozen capture-result.json fields;
    * ``capture_files`` — the STILL-OPEN pre-spawn capture file objects
      (name -> file object) the caller must read via
      ``capture_reader.read_capture_files_from_fds`` and then close;
    * ``sweep_ok`` — False when the post-exit process-tree sweep exceeded its
      budget: the caller must exit non-zero WITHOUT emitting the envelope.

    Writes ``capture-result.json`` through its pre-opened fd before
    returning, EXCEPT when the spawn fails while a child identity is active
    (P0-4: no child, no summary).  On any internal exception everything is
    closed and the exception is re-raised.

    D15 §4.2 (Scenario B): ``task_workspace`` and ``task_environment`` carry
    the AF_TASK_* variables that previously lived in the shared global
    /sandbox/sitecustomize.py. The wrapper performs makedirs / chdir /
    sys.path setup itself pre-spawn, then injects the env into the user
    child via Popen(env=...). Fail-closed: caller guarantees both are
    non-empty (parse_wrapper_input rejects missing required keys).
    """
    capture_path = Path(capture_dir)
    capture_path.mkdir(parents=True, exist_ok=True)
    # P0-4: when the child runs unprivileged it must not be able to enter
    # the capture directory at all (it is root-owned in that mode).
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
    }

    capture_files: dict = {}
    result_file = stdout_file = stderr_file = None
    records: _RecordChannel | None = None
    audit: _UnknownMarkerAudit | None = None
    proc: subprocess.Popen | None = None
    timed_out = False
    spawned = False
    success = False
    # P0-4: with an active identity a FAILED spawn must leave no summary.
    suppress_summary = child_identity is not None

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

        # Build the child env: wrapper's own env + AF_TASK_* (task-scoped) +
        # PYTHONPATH extended with the loader-module workdir so af_dataset_
        # loader etc. remain importable from the user child exactly as they
        # were under the old sitecustomize regime.
        child_env = os.environ.copy()
        if task_environment:
            for key, value in task_environment.items():
                child_env[key] = value
        if workdir_for_pythonpath:
            existing_pythonpath = child_env.get("PYTHONPATH", "")
            if existing_pythonpath:
                child_env["PYTHONPATH"] = (
                    f"{workdir_for_pythonpath}:{existing_pythonpath}"
                )
            else:
                child_env["PYTHONPATH"] = workdir_for_pythonpath

        try:
            # The child's stdout/stderr are the capture pipes ONLY: the child
            # never inherits or shares the wrapper's own stdout fd, which
            # later carries the single bounded envelope (PIN 2).
            proc = subprocess.Popen(
                [sys.executable, str(script_path)],
                stdin=subprocess.DEVNULL,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                cwd=spawn_cwd,
                env=child_env,
                start_new_session=True,  # child owns a new process group
                preexec_fn=_make_preexec(child_identity),
            )
        except OSError:
            proc = None
            raise
        spawned = True
        suppress_summary = False  # a child ran: the summary is mandatory
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
            }
        )

        exit_code = proc.returncode
        if exit_code is None:
            exit_code = 1
        elif timed_out and exit_code == 0:
            exit_code = 124  # defensive: a timed-out run never reports success
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
        if not suppress_summary and result_file is not None:
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
            # A failed spawn with an active identity leaves no summary.
            _ = spawned


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

    # === P0-4 (codex 03b4d034): child identity gate, BEFORE anything runs ==
    # Root MUST have a resolvable non-root identity: refusal is a short
    # diagnostic and a non-zero exit — no child, no summary.  Not root:
    # unset keeps dev-mode same-UID behavior; a set spec is parsed
    # (fail-closed on garbage) and applied best-effort at spawn.
    child_identity = None
    spec = os.environ.get(CHILD_USER_ENV_NAME)
    if spec is not None:
        try:
            child_identity = parse_child_spec(spec)
        except ChildIdentityError as exc:
            sys.stderr.write(f"bounded_exec_wrapper: {exc}\n")
            return 1
    elif os.geteuid() == 0:
        sys.stderr.write(
            "bounded_exec_wrapper: refusing to run the child as root: "
            f"{CHILD_USER_ENV_NAME} is required\n"
        )
        return 1

    capture_dir = input_path.resolve().parent / CAPTURE_DIR_NAME
    try:
        summary, capture_files, sweep_ok = run_bounded_capture(
            script_path=parsed["script_path"],
            timeout_seconds=parsed["timeout_seconds"],
            limits=parsed["limits"],
            capture_dir=capture_dir,
            child_identity=child_identity,
            task_workspace=parsed["task_workspace"],
            task_environment=parsed["task_environment"],
            workdir_for_pythonpath=parsed.get("loader_python_path"),
        )
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
