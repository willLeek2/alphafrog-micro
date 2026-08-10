# === work-package-C (ccqwen) ===
"""§7.1 step 7 capture readback: trusted in-memory artifact dump.

Wrapper-tail model (P0 fix: the reader is NEVER executed as a process
inside the container).  ``app.bounded_exec_wrapper`` imports this module at
wrapper process start, BEFORE the user child is spawned; after the child
exits and the capture files are finalized the wrapper calls
``read_capture_files(...)`` IN MEMORY with the four frozen §13 limits and
emits the returned envelope on its OWN stdout.  After user code exits,
NOTHING located in the user-writable task workspace is ever executed again,
so user code cannot hijack the readback (the old CLI entry point is
retired: no ``main()``, no ``__main__`` — the wrapper's nonzero-exit
contract supersedes the old CLI exit-2/exit-1 contract).

The four limit arguments are the FROZEN §13 snapshot the runner already
validated (codex f86c66f5 / e083e181): the reader never trusts artifact
self-reported summaries for limits — every present file is bounded against
those caps BEFORE any content is read or encoded (§7.1 stop condition:
outputs are bounded before anything else happens).

``read_capture_files`` returns ONE JSON-able document::

    {"files": {"capture-result.json": "<base64>", "stdout.bin": "<base64>", ...}}

containing exactly the capture files that exist under the capture dir,
base64-encoded (pure ASCII, safe to transport through the container exec
stdout channel).  File PRESENCE is significant: the host-side fail-closed
reader (``app.finance_record_channel.read_capture_artifacts``) validates
presence, byte lengths and record-channel consistency, so this module is a
deliberately dumb byte mover — it never interprets, truncates, filters or
fabricates artifacts, and an absent file is simply absent from the JSON.

The user script runs with cwd = the task workspace, so MALICIOUS user code
can rewrite, replace or symlink the capture files while it runs; the
readback happens only after it exits.  Every present file is therefore
lstat'd WITHOUT following symlinks, opened with ``O_NOFOLLOW``, fstat-
checked to be a regular file (never a symlink, directory, FIFO or device),
and its fstat size is checked against the caps BEFORE any read — through
the SAME fd that is read, so there is no stat/read TOCTOU window.  The
whole envelope is additionally bounded by the worst-case serialized JSON
size (``_envelope_ceiling``) both projected from the fstat sizes and
re-checked on the real document (belt and braces).

The file-name whitelist is the §7.1 fixed capture layout; it is pinned to
``app.bounded_exec_wrapper``'s constants by
``tests/test_bounded_wrapper_wiring.py`` so the two sides cannot drift.

P0-4 (codex 03b4d034): FD-PINNED readback.  ``read_capture_files_from_fds``
is the production entry point used by the wrapper: the wrapper pre-opens the
capture files BEFORE the child is spawned and keeps the fds open; after the
child exits the readback re-reads EXACTLY those fds (fstat on the fd, cap
checks BEFORE the first read byte, lseek to 0, size-bounded read) — there is
ZERO path resolution after spawn, so a malicious child that renames,
unlinks, replaces or symlinks anything under the capture directory while it
runs cannot influence the readback at all.  The fds are caller-owned and are
never closed here.  The path-based ``read_capture_files`` remains as the
equally fail-closed unit/host surface.

Stdlib only; the module itself writes nothing anywhere — the WRAPPER emits
the envelope (exactly one JSON document on its stdout, zero other bytes)
and, on failure, a SHORT diagnostic (type/message only, never artifact
CONTENT — §18) to stderr plus a non-zero exit.
"""

from __future__ import annotations

import base64
import errno
import json
import os
import stat
from pathlib import Path

# §7.1 fixed capture layout (verbatim file names; keep in sync with
# app.bounded_exec_wrapper constants — pinned by the wiring tests).
CAPTURE_FILE_NAMES = (
    "capture-result.json",
    "stdout.bin",
    "stderr.bin",
    "finance-records.jsonl",
    "finance-records-unknown-marker.jsonl",
)

# capture-result.json is wrapper-produced (the 14 frozen summary keys, a few
# hundred bytes at most); it can never LEGITIMATELY exceed this fixed small
# cap, so the reader bounds it without any external limit argument.
CAPTURE_SUMMARY_MAX_BYTES = 65536

# The two record-channel files share ONE joint budget (§4.1/§4.2).
_RECORD_CHANNEL_FILE_NAMES = (
    "finance-records.jsonl",
    "finance-records-unknown-marker.jsonl",
)

# Structural JSON overhead of the envelope document: computed once from the
# exact serialization of an all-empty files map.  Every real document is
# precisely this overhead plus the base64 payload lengths (pure ASCII, so no
# JSON escaping ever changes a length).
_ENVELOPE_OVERHEAD = len(
    json.dumps({"files": {name: "" for name in CAPTURE_FILE_NAMES}})
)


def _base64_length(size: int) -> int:
    """base64 inflates ``size`` bytes to exactly ``4 * ceil(size / 3)``."""
    return 4 * ((size + 2) // 3)


def _envelope_ceiling(
    stdout_max_bytes: int,
    stderr_max_bytes: int,
    record_channel_max_bytes: int,
) -> int:
    """Worst-case serialized length of the envelope JSON document.

    Every file contributes its base64 inflation at its cap: the summary at
    ``CAPTURE_SUMMARY_MAX_BYTES``, stdout/stderr at their frozen caps, and
    the two record-channel files draw from the SINGLE joint budget.  A joint
    budget split across two files can carry one extra base64 padding group
    more than a single file of the same total size, hence the final ``+ 4``.
    Monotonic in each argument.
    """
    return (
        _ENVELOPE_OVERHEAD
        + _base64_length(CAPTURE_SUMMARY_MAX_BYTES)
        + _base64_length(stdout_max_bytes)
        + _base64_length(stderr_max_bytes)
        + _base64_length(record_channel_max_bytes)
        + 4
    )


def _validate_limit(name: str, value) -> int:
    """Reject anything that is not an int >= 0 (bool is an int subclass)."""
    if isinstance(value, bool) or not isinstance(value, int):
        raise ValueError(
            f"{name} must be an integer, got {type(value).__name__}"
        )
    if value < 0:
        raise ValueError(f"{name} must be >= 0, got {value}")
    return value


def _open_regular_file(capture_path: Path, name: str):
    """Open one capture entry fail-closed; return ``(fd, size)``.

    Raises ``FileNotFoundError`` when the entry is ABSENT (lstat does not
    follow symlinks) — an absent file is simply absent from the JSON.
    Otherwise the entry must be a regular file: symlinks are rejected by the
    lstat mode check AND by ``O_NOFOLLOW`` (ELOOP on macOS/Linux), and
    directories,
    FIFOs and devices are rejected by the mode checks.  ``O_NONBLOCK``
    guarantees the open can never block on a FIFO swapped in between lstat
    and open.  The size comes from ``fstat`` on the SAME fd that is later
    read — no TOCTOU window between the size check and the read.
    """
    path = capture_path / name
    entry = os.lstat(path)  # raises FileNotFoundError -> absent
    if stat.S_ISLNK(entry.st_mode):
        raise ValueError(f"capture file {name} is a symlink; rejected")
    if not stat.S_ISREG(entry.st_mode):
        raise ValueError(f"capture file {name} is not a regular file")
    try:
        fd = os.open(
            str(path), os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK
        )
    except OSError as exc:
        # ELOOP: a symlink slipped in under O_NOFOLLOW; ENOENT: the entry
        # disappeared between lstat and open (racing tamper).  Fail closed.
        if exc.errno in (errno.ELOOP, errno.ENOENT, errno.ENOTDIR):
            raise ValueError(
                f"capture file {name} rejected at open "
                f"(errno={exc.errno})"
            ) from exc
        raise ValueError(
            f"capture file {name} rejected at open (errno={exc.errno})"
        ) from exc
    try:
        info = os.fstat(fd)
    except OSError as exc:
        os.close(fd)
        raise ValueError(f"capture file {name} is not stat-able") from exc
    if not stat.S_ISREG(info.st_mode):
        os.close(fd)
        raise ValueError(f"capture file {name} is not a regular file")
    return fd, info.st_size


def _finalize_capture_envelope(
    contents: dict,
    *,
    stdout_max_bytes: int,
    stderr_max_bytes: int,
    record_channel_max_bytes: int,
    record_channel_max_records: int,
) -> dict:
    """Shared fail-closed tail used by BOTH readback entry points.

    Runs AFTER the size-bounded reads only (every entry point has already
    bounded each file against its cap, and the joint pair against the joint
    budget, BEFORE the first read byte).  Re-validates the joint budget on
    the real content lengths, bounds the record line count, base64-encodes,
    and re-checks the serialized envelope against the ceiling — belt and
    braces on top of the pre-read projections.  Raises ``ValueError`` on any
    violation; messages carry names/sizes/caps only, never content (§18).
    """
    ceiling = _envelope_ceiling(
        stdout_max_bytes, stderr_max_bytes, record_channel_max_bytes
    )
    joint_size = sum(
        len(contents[name])
        for name in _RECORD_CHANNEL_FILE_NAMES
        if name in contents
    )
    if joint_size > record_channel_max_bytes:
        raise ValueError(
            f"record channel files are {joint_size} bytes jointly, "
            f"over record_channel_max_bytes {record_channel_max_bytes}"
        )
    records_content = contents.get("finance-records.jsonl")
    if records_content is not None:
        line_count = records_content.count(b"\n")
        if records_content and not records_content.endswith(b"\n"):
            line_count += 1  # trailing non-newline-terminated fragment
        if line_count > record_channel_max_records:
            raise ValueError(
                "capture file finance-records.jsonl holds "
                f"{line_count} line(s), over "
                f"record_channel_max_records {record_channel_max_records}"
            )
    projected = _ENVELOPE_OVERHEAD + sum(
        _base64_length(len(content)) for content in contents.values()
    )
    if projected > ceiling:
        raise ValueError(
            f"capture envelope is {projected} bytes, over ceiling {ceiling}"
        )
    document = {
        "files": {
            name: base64.b64encode(contents[name]).decode("ascii")
            for name in CAPTURE_FILE_NAMES
            if name in contents
        }
    }
    serialized_length = len(json.dumps(document))
    if serialized_length > ceiling:
        raise ValueError(
            f"capture envelope is {serialized_length} bytes, over "
            f"ceiling {ceiling}"
        )
    return document


def read_capture_files(
    capture_dir,
    *,
    stdout_max_bytes,
    stderr_max_bytes,
    record_channel_max_bytes,
    record_channel_max_records,
) -> dict:
    """Return ``{"files": {name: base64}}`` for every present capture file.

    Fail-closed BEFORE any content is read (§7.1 stop condition, codex
    f86c66f5 / e083e181): every present file must open ``O_NOFOLLOW``, be a
    regular file per ``fstat`` on the read fd, and fit its cap by ``st_size``
    BEFORE the first read byte:

    * ``capture-result.json`` <= ``CAPTURE_SUMMARY_MAX_BYTES`` (fixed cap);
    * ``stdout.bin`` <= ``stdout_max_bytes``, ``stderr.bin`` <=
      ``stderr_max_bytes``;
    * ``finance-records.jsonl`` + ``finance-records-unknown-marker.jsonl``
      JOINT ``st_size`` <= ``record_channel_max_bytes``;
    * the records file holds at most ``record_channel_max_records`` lines
      (counted after the size-bounded read);
    * the projected and the actual envelope document size both stay within
      ``_envelope_ceiling(...)``.

    All four limits are keyword-only, required ints >= 0 (ValueError
    otherwise).  Raises ``ValueError`` when ``capture_dir`` is not a
    directory: the wrapper always creates it before writing anything, so its
    absence means the capture itself never ran and the host must fail the
    task.  Error messages carry type/message only — never artifact content
    (§18).
    """
    stdout_max_bytes = _validate_limit("stdout_max_bytes", stdout_max_bytes)
    stderr_max_bytes = _validate_limit("stderr_max_bytes", stderr_max_bytes)
    record_channel_max_bytes = _validate_limit(
        "record_channel_max_bytes", record_channel_max_bytes
    )
    record_channel_max_records = _validate_limit(
        "record_channel_max_records", record_channel_max_records
    )

    capture_path = Path(capture_dir)
    if not capture_path.is_dir():
        raise ValueError(f"capture directory missing: {capture_dir}")

    opened: dict[str, tuple[int, int]] = {}
    try:
        for name in CAPTURE_FILE_NAMES:
            try:
                handle = _open_regular_file(capture_path, name)
            except FileNotFoundError:
                continue  # absent file = absent from the JSON
            opened[name] = handle

        # --- cap checks on the fstat sizes, BEFORE any content is read ----
        for name, cap in (
            ("capture-result.json", CAPTURE_SUMMARY_MAX_BYTES),
            ("stdout.bin", stdout_max_bytes),
            ("stderr.bin", stderr_max_bytes),
        ):
            if name in opened and opened[name][1] > cap:
                raise ValueError(
                    f"capture file {name} is {opened[name][1]} bytes, "
                    f"over cap {cap}"
                )
        joint_size = sum(
            opened[name][1]
            for name in _RECORD_CHANNEL_FILE_NAMES
            if name in opened
        )
        if joint_size > record_channel_max_bytes:
            raise ValueError(
                f"record channel files are {joint_size} bytes jointly, "
                f"over record_channel_max_bytes {record_channel_max_bytes}"
            )

        # --- envelope bound projected from the fstat sizes (pre-read) -----
        ceiling = _envelope_ceiling(
            stdout_max_bytes, stderr_max_bytes, record_channel_max_bytes
        )
        projected = _ENVELOPE_OVERHEAD + sum(
            _base64_length(size) for _, size in opened.values()
        )
        if projected > ceiling:
            raise ValueError(
                f"projected capture envelope is {projected} bytes, over "
                f"ceiling {ceiling}"
            )

        # --- size-bounded reads through the already-validated fds ---------
        contents: dict[str, bytes] = {}
        for name in CAPTURE_FILE_NAMES:
            if name not in opened:
                continue
            fd, size = opened.pop(name)
            try:
                handle = os.fdopen(fd, "rb")
            except Exception:
                opened[name] = (fd, size)  # keep the fd closeable
                raise
            with handle:
                # Read at most the validated size: a racing writer can never
                # grow the readback past the cap (never read_bytes() after a
                # separate stat — the fd IS the validation).
                contents[name] = handle.read(size)

        return _finalize_capture_envelope(
            contents,
            stdout_max_bytes=stdout_max_bytes,
            stderr_max_bytes=stderr_max_bytes,
            record_channel_max_bytes=record_channel_max_bytes,
            record_channel_max_records=record_channel_max_records,
        )
    finally:
        for fd, _ in opened.values():
            try:
                os.close(fd)
            except OSError:
                pass


def _bounded_fd_read(fd: int, size: int) -> bytes:
    """Read AT MOST ``size`` bytes from ``fd`` (already seeked to 0).

    Never reads past the validated size, so a racing writer can never grow
    the readback past a cap; returns fewer bytes only when the file hits EOF
    early.
    """
    chunks = []
    remaining = size
    while remaining > 0:
        chunk = os.read(fd, min(remaining, 1 << 20))
        if not chunk:
            break
        chunks.append(chunk)
        remaining -= len(chunk)
    return b"".join(chunks)


def read_capture_files_from_fds(
    fds,
    *,
    stdout_max_bytes,
    stderr_max_bytes,
    record_channel_max_bytes,
    record_channel_max_records,
) -> dict:
    """FD-pinned readback: ``{"files": {name: base64}}`` over PRE-OPENED fds.

    ``fds`` maps capture file name -> already-open fd (int).  P0-4 (codex
    03b4d034): the wrapper pre-opens the capture files before the child is
    spawned and passes the still-open fds here after the child exits, so the
    readback performs ZERO path resolution — rename/unlink/replace/symlink
    attacks on the capture directory are structurally impossible to notice
    because the paths are never consulted again.

    Same fail-closed discipline as ``read_capture_files``: every held fd is
    fstat-checked to be a regular file, bounded against its cap and the joint
    record-channel budget BEFORE the first read byte, then seeked to 0 and
    read through exactly that fd for at most the validated size; the envelope
    ceiling is projected from the fstat sizes, then re-checked on the real
    document.  The fds are CALLER-OWNED: this function never closes them
    (closing here could let an error path double-close a fd the caller has
    already reassigned).  Presence in the envelope means exactly "the caller
    held that fd".

    Unexpectedly invalid input (unknown name, non-int fd, dead fd, non-
    regular file, unseekable fd) fails closed with ``ValueError``; messages
    carry names/sizes/caps only, never artifact content (§18).
    """
    stdout_max_bytes = _validate_limit("stdout_max_bytes", stdout_max_bytes)
    stderr_max_bytes = _validate_limit("stderr_max_bytes", stderr_max_bytes)
    record_channel_max_bytes = _validate_limit(
        "record_channel_max_bytes", record_channel_max_bytes
    )
    record_channel_max_records = _validate_limit(
        "record_channel_max_records", record_channel_max_records
    )
    if not isinstance(fds, dict):
        raise ValueError("fd map must be a dict of capture name -> fd")

    caps = {
        "capture-result.json": CAPTURE_SUMMARY_MAX_BYTES,
        "stdout.bin": stdout_max_bytes,
        "stderr.bin": stderr_max_bytes,
    }
    sizes: dict = {}
    for name, fd in fds.items():
        if name not in CAPTURE_FILE_NAMES:
            # §18: an unknown name is untrusted input — never echo it.
            raise ValueError("fd map holds an unknown capture file name")
        if isinstance(fd, bool) or not isinstance(fd, int):
            raise ValueError(
                f"capture file {name} fd is not an integer file descriptor"
            )
        try:
            info = os.fstat(fd)
        except OSError as exc:
            raise ValueError(
                f"capture file {name} fd is not stat-able"
            ) from exc
        if not stat.S_ISREG(info.st_mode):
            raise ValueError(
                f"capture file {name} fd is not a regular file"
            )
        sizes[name] = info.st_size

    # --- cap checks on the fstat sizes, BEFORE any content is read --------
    for name, cap in caps.items():
        if name in sizes and sizes[name] > cap:
            raise ValueError(
                f"capture file {name} is {sizes[name]} bytes, "
                f"over cap {cap}"
            )
    joint_size = sum(
        sizes[name] for name in _RECORD_CHANNEL_FILE_NAMES if name in sizes
    )
    if joint_size > record_channel_max_bytes:
        raise ValueError(
            f"record channel files are {joint_size} bytes jointly, "
            f"over record_channel_max_bytes {record_channel_max_bytes}"
        )

    # --- envelope bound projected from the fstat sizes (pre-read) ---------
    ceiling = _envelope_ceiling(
        stdout_max_bytes, stderr_max_bytes, record_channel_max_bytes
    )
    projected = _ENVELOPE_OVERHEAD + sum(
        _base64_length(size) for size in sizes.values()
    )
    if projected > ceiling:
        raise ValueError(
            f"projected capture envelope is {projected} bytes, over "
            f"ceiling {ceiling}"
        )

    # --- size-bounded reads through the ALREADY-VALIDATED fds -------------
    contents: dict = {}
    for name in CAPTURE_FILE_NAMES:
        if name not in fds:
            continue
        fd = fds[name]
        try:
            os.lseek(fd, 0, os.SEEK_SET)
        except OSError as exc:
            raise ValueError(
                f"capture file {name} fd is not seekable"
            ) from exc
        contents[name] = _bounded_fd_read(fd, sizes[name])

    return _finalize_capture_envelope(
        contents,
        stdout_max_bytes=stdout_max_bytes,
        stderr_max_bytes=stderr_max_bytes,
        record_channel_max_bytes=record_channel_max_bytes,
        record_channel_max_records=record_channel_max_records,
    )
