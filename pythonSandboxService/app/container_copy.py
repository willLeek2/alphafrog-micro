"""Non-root container file copy + runtime no-root enforcement (260818).

Why this module exists (grace review on 874b77ad): llm-sandbox 0.3.33's
``copy_to_runtime`` ends with ``_ensure_ownership``, which executes
``chown -R ...`` **as root** inside the container whenever a non-root
``runtime_configs["user"]`` is configured — and ``session.open()``'s
``environment_setup`` (active when AF_SANDBOX_SKIP_ENVIRONMENT_SETUP=false)
calls it too. frog's decision for the 260818 simplification is that
NOTHING ever runs as root inside a sandbox container, so this module:

* replaces the library copy path for every file the service stages:
  a tar whose single entry carries the CONTAINER USER's uid/gid and the
  source file's mode is handed to docker ``put_archive`` directly — no
  chown, no root exec, correct ownership at extraction time;
* installs runtime guards that make "no root process in the container"
  an enforced, testable contract instead of a code-review promise: the
  backend session's ``_ensure_ownership`` is replaced with a raiser and
  the container proxy's ``exec_run`` is wrapped so any keyword
  ``user=`` request that still means root on either side (per the
  shared strict parser from app.config) fails loudly.  Guards FAIL
  CLOSED: if they cannot be installed at all, that is a contract break
  (grace round-3 MUST-FIX 2), never a silent skip;
* verifies the container's LIVE identity (``id -u``/``id -g``) right
  after ``session.open()``: uid/gid 0 is rejected for both config forms,
  and a numeric ``uid:gid`` spec must match the live values exactly
  (grace round-3 MUST-FIX 1) — identity is a checked runtime fact, not
  a config assumption.
"""

from __future__ import annotations

import io
import os
import posixpath
import shlex
import tarfile
from pathlib import Path

from .config import reject_root_container_user

CONTAINER_IDENTITY_ATTR = "af_container_uid_gid"


class NonRootContractError(RuntimeError):
    """The 'no root process inside the sandbox container' contract broke."""


def _session_container(session):
    """Best-effort docker container object behind an llm-sandbox session."""
    for owner in (session, getattr(session, "_backend_session", None)):
        if owner is None:
            continue
        container = getattr(owner, "container", None) or getattr(
            owner, "_container", None
        )
        if container is not None and hasattr(container, "put_archive"):
            return container
    return None


def resolve_container_identity(session) -> tuple:
    """Resolve the container user to a numeric ``(uid, gid)`` pair.

    Priority: the session-cached pair (installed by
    ``verify_container_identity`` at session creation) → ``id -u`` /
    ``id -g`` executed INSIDE the container as the container user (the
    container's passwd database is authoritative for username specs).
    Resolution runs as the container user, never as root, and the result
    is cached on the session object.

    grace round-3 MUST-FIX 1: a resolved identity with uid 0 or gid 0 is
    REJECTED — "which user does the container run as" must be a checked
    runtime fact, never a cached assumption.
    """
    cached = getattr(session, CONTAINER_IDENTITY_ATTR, None)
    if cached is not None:
        _reject_zero_identity(cached)
        return cached

    def _read_id(flag: str) -> int:
        result = session.execute_command(f"id {flag}")
        text = (getattr(result, "stdout", "") or "").strip()
        if getattr(result, "exit_code", 1) != 0 or not text.isdigit():
            raise NonRootContractError(
                f"cannot resolve the container user's {flag} inside the "
                f"container (exit={getattr(result, 'exit_code', '?')})"
            )
        return int(text)

    identity = (_read_id("-u"), _read_id("-g"))
    _reject_zero_identity(identity)
    setattr(session, CONTAINER_IDENTITY_ATTR, identity)
    return identity


def _reject_zero_identity(identity: tuple) -> None:
    uid, gid = identity
    if uid == 0 or gid == 0:
        raise NonRootContractError(
            f"the sandbox container reports identity uid={uid} gid={gid}: "
            "a root uid or gid violates the non-root container contract "
            "(grace round-3 MUST-FIX 1 — identity is a runtime-checked "
            "fact, not a config assumption)"
        )


def verify_container_identity(session, container_user: str) -> tuple:
    """VERIFY the container's live identity right after ``session.open()``.

    grace round-3 MUST-FIX 1: both config forms (username and numeric
    ``uid:gid``) must be validated against the container's ACTUAL running
    identity, read live via ``id -u`` / ``id -g`` (any stale cache is
    ignored).  Rules:

    * either side reading 0 → NonRootContractError (root is never
      acceptable, whatever the config said);
    * a numeric ``uid:gid`` spec must match the live values EXACTLY — a
      mismatch means the container runtime did not adopt the configured
      identity, which is a contract break, not a cosmetic difference.

    On success the verified pair is cached on the session for the staging
    path (``copy_file_to_container`` reads it and re-checks non-zero).
    """
    spec = container_user.strip()
    expected = None
    if ":" in spec:
        uid_text, _, gid_text = spec.partition(":")
        if uid_text.isdigit() and gid_text.isdigit():
            expected = (int(uid_text), int(gid_text))

    # Read LIVE state: drop any cached value so the verification cannot
    # be satisfied by an assumption.
    if getattr(session, CONTAINER_IDENTITY_ATTR, None) is not None:
        delattr(session, CONTAINER_IDENTITY_ATTR)

    def _read_id(flag: str) -> int:
        result = session.execute_command(f"id {flag}")
        text = (getattr(result, "stdout", "") or "").strip()
        if getattr(result, "exit_code", 1) != 0 or not text.isdigit():
            raise NonRootContractError(
                f"cannot verify the container user's {flag} inside the "
                f"container (exit={getattr(result, 'exit_code', '?')})"
            )
        return int(text)

    identity = (_read_id("-u"), _read_id("-g"))
    _reject_zero_identity(identity)
    if expected is not None and identity != expected:
        raise NonRootContractError(
            f"container identity mismatch: configured uid:gid="
            f"{expected[0]}:{expected[1]} but the container reports "
            f"uid={identity[0]} gid={identity[1]} — the container runtime "
            "did not adopt the configured user (grace round-3 MUST-FIX 1)"
        )
    setattr(session, CONTAINER_IDENTITY_ATTR, identity)
    return identity


def copy_file_to_container(session, source, dest_path: str) -> None:
    """Stage one file into the container WITHOUT any root exec.

    * the parent directory is created (if missing) via the session's
      normal exec — which runs as the container user;
    * the file travels as a tar entry whose uid/gid are the container
      user's and whose mode is the source file's, so docker's archive
      extraction lands it already owned by the container user — the
      library's root ``chown -R`` pass becomes unnecessary;
    * an existing file at ``dest_path`` is REPLACED (tar extraction
      overwrites), so re-staging never degrades into append/partial
      writes.
    """
    source = Path(source)
    container = _session_container(session)
    if container is None:
        raise NonRootContractError(
            "session has no container object for the non-root copy path; "
            "refusing to fall back to llm-sandbox copy_to_runtime (it "
            "execs chown as root — see module docstring)"
        )
    dest_path = posixpath.normpath(dest_path)
    parent = posixpath.dirname(dest_path)
    name = posixpath.basename(dest_path)
    if not name:
        raise ValueError(f"dest_path must name a file: {dest_path!r}")

    mkdir = session.execute_command(f"mkdir -p {shlex.quote(parent)}")
    if getattr(mkdir, "exit_code", 0) != 0:
        raise NonRootContractError(
            f"mkdir -p {parent!r} failed as the container user "
            f"(exit={getattr(mkdir, 'exit_code', '?')})"
        )

    uid, gid = resolve_container_identity(session)
    stat = source.stat()
    payload = source.read_bytes()
    stream = io.BytesIO()
    with tarfile.open(fileobj=stream, mode="w") as tar:
        info = tarfile.TarInfo(name=name)
        info.size = len(payload)
        info.mode = stat.st_mode & 0o7777
        info.uid = uid
        info.gid = gid
        info.mtime = int(stat.st_mtime)
        info.uname = ""
        info.gname = ""
        tar.addfile(info, io.BytesIO(payload))
    container.put_archive(parent, stream.getvalue())


def _forbidden_ensure_ownership(paths) -> None:
    raise NonRootContractError(
        "llm-sandbox _ensure_ownership invoked: it execs chown as root, "
        "which the non-root container contract forbids"
    )


def _is_root_exec_user(user) -> bool:
    """True iff a ``user=`` exec request still means root on either side.

    Reuses the startup config parser (``reject_root_container_user``) so
    the runtime guard and the config check share ONE grammar: ``root``,
    ``0``, ``root:10001``, ``0:10001``, ``10000:root``, ``10000:0``,
    ``root:root`` (and case variants) are all root spellings.  ``None``
    or ``""`` means "the container's default user" — the container is
    CREATED as the unprivileged user, so that stays allowed.
    """
    if user is None or user == "":
        return False
    if isinstance(user, int):
        return user == 0
    try:
        reject_root_container_user(str(user))
    except ValueError:
        return True
    return False


def install_no_root_guards(session) -> None:
    """Enforce the no-root contract on an OPEN session — FAIL CLOSED.

    grace round-3 MUST-FIX 2: "no root process at runtime" is a contract,
    so a guard that cannot be INSTALLED is a contract break, not a
    degraded-but-okay state:

    * the backend session's ``_ensure_ownership`` is replaced by a
      raiser — if the assignment itself fails (exotic proxy), raise;
    * the docker container proxy MUST be reachable and MUST expose
      ``exec_run``; its ``exec_run`` is wrapped so any keyword
      ``user=`` request that still means root on either side (per the
      shared strict parser) is rejected.  Scope note: the guard covers
      the keyword form used by the docker SDK and llm-sandbox; a
      positional ``user`` argument is outside this contract and not
      claimed.

    Idempotent: installing twice keeps exactly one wrapper.
    """
    backend = getattr(session, "_backend_session", None) or session
    try:
        backend._ensure_ownership = _forbidden_ensure_ownership
    except (AttributeError, TypeError) as error:
        raise NonRootContractError(
            "cannot install the _ensure_ownership root-chown guard on "
            f"the session backend {type(backend).__name__!r}: the "
            "non-root contract is enforced, not best-effort"
        ) from error

    container = _session_container(session)
    if container is None:
        raise NonRootContractError(
            "session has no container object for the root-exec guard: "
            "refusing to run without the non-root contract in force"
        )
    original = getattr(container, "exec_run", None)
    if original is None:
        raise NonRootContractError(
            "the container proxy exposes no exec_run to guard: refusing "
            "to run without the non-root contract in force"
        )
    if getattr(original, "_af_no_root_guard", False):
        return  # already guarded — idempotent install

    def guarded_exec_run(*args, **kwargs):
        user = kwargs.get("user")
        if _is_root_exec_user(user):
            raise NonRootContractError(
                f"exec_run requested as root inside the sandbox container "
                f"(user={user!r})"
            )
        return original(*args, **kwargs)

    guarded_exec_run._af_no_root_guard = True  # type: ignore[attr-defined]
    try:
        container.exec_run = guarded_exec_run
    except (AttributeError, TypeError) as error:
        raise NonRootContractError(
            "cannot wrap the container proxy's exec_run with the root "
            "guard: the non-root contract is enforced, not best-effort"
        ) from error
