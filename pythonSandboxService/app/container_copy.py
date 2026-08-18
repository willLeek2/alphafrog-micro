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
  (when the docker container proxy accepts attribute assignment) the
  container's ``exec_run`` is wrapped so any ``user=root``/``user=0``
  execution fails loudly.

Stdlib only (io/os/posixpath/shlex/tarfile) — importable everywhere.
"""

from __future__ import annotations

import io
import os
import posixpath
import shlex
import tarfile
from pathlib import Path

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
    ``prime_container_identity`` at session creation, which understands
    the ``uid:gid`` config form) → ``id -u`` / ``id -g`` executed INSIDE
    the container as the container user (the container's passwd database
    is authoritative for username specs).  Resolution runs as the
    container user, never as root, and the result is cached on the
    session object.
    """
    cached = getattr(session, CONTAINER_IDENTITY_ATTR, None)
    if cached is not None:
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
    setattr(session, CONTAINER_IDENTITY_ATTR, identity)
    return identity


def prime_container_identity(session, container_user: str) -> tuple:
    """Pre-resolve a ``uid:gid`` config spec onto the session (fast path).

    Numeric specs are parsed without touching the container; any other
    form (username) is left to ``resolve_container_identity``'s in-container
    ``id`` lookup, which is the authoritative resolution for names.
    """
    spec = container_user.strip()
    if ":" in spec:
        uid_text, _, gid_text = spec.partition(":")
        if uid_text.isdigit() and gid_text.isdigit():
            identity = (int(uid_text), int(gid_text))
            setattr(session, CONTAINER_IDENTITY_ATTR, identity)
            return identity
    return resolve_container_identity(session)


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


def install_no_root_guards(session) -> None:
    """Enforce the no-root contract on an OPEN session (runtime checkable).

    * the backend session's ``_ensure_ownership`` is replaced by a raiser
      (instance attribute — the library's own ``copy_to_runtime`` /
      ``environment_setup`` path now fails loudly instead of silently
      starting a root process);
    * the docker container proxy's ``exec_run`` is wrapped so any call
      asking for ``user`` root (name or numeric 0) is rejected. When the
      proxy does not accept attribute assignment the exec guard is
      skipped — the ``_ensure_ownership`` guard still holds for every
      path llm-sandbox 0.3.33 actually uses.
    """
    backend = getattr(session, "_backend_session", None) or session
    try:
        backend._ensure_ownership = _forbidden_ensure_ownership
    except (AttributeError, TypeError):  # pragma: no cover - exotic proxy
        pass

    container = _session_container(session)
    if container is None:
        return
    original = getattr(container, "exec_run", None)
    if original is None or getattr(original, "_af_no_root_guard", False):
        return

    def guarded_exec_run(*args, **kwargs):
        user = kwargs.get("user")
        if user in ("root", "0", 0, "0:0"):
            raise NonRootContractError(
                "exec_run requested as root inside the sandbox container"
            )
        return original(*args, **kwargs)

    guarded_exec_run._af_no_root_guard = True  # type: ignore[attr-defined]
    try:
        container.exec_run = guarded_exec_run
    except (AttributeError, TypeError):  # pragma: no cover - exotic proxy
        pass
