# === work-package-C (ccqwen) ===
"""P0-4 (codex 03b4d034): unprivileged child identity for the sandbox child.

Stdlib-only module imported by BOTH the runner (host-side gate before any
workspace preparation) and the bounded execution wrapper (in-container
enforcement before the child is spawned) — it ships inside the wrapper
staging bundle exactly like ``app.bounded_exec_wrapper``'s other PIN 1
imports, so it must stay stdlib-only and side-effect free on import.

The identity is carried by the environment variable
``AF_SANDBOX_CHILD_USER`` (set by the runner in the container exec command
itself), with two accepted forms:

* ``"<username>"``   — resolved against the CONTAINER's passwd database
  (``pwd.getpwnam``); the resolved uid AND gid MUST both be nonzero (a
  child whose primary group is root's group is not a fixed non-privileged
  identity — codex 691341d2).
* ``"<uid>:<gid>"``  — ASCII-digits-only decimal pair, both fields present,
  exactly one colon; uid AND gid MUST both be nonzero.

Everything else is rejected with ``ChildIdentityError``: empty or
whitespace-only specs, leading/trailing whitespace, control characters,
nonexistent users, uid 0, gid 0, unicode digits, extra colons.  Error
MESSAGES are categorical only — they never echo the raw spec value (§18:
diagnostics carry names/categories, never untrusted content).

``parse_child_spec`` is a PURE function (no environment access apart from
the passwd lookup, no OS state mutation) so the whole accept/reject matrix
is unit-testable without root.

Resolution happens ONLY inside the target container (codex 087da672): the
service process runs in a different uid/username namespace than the
container image, so a host-side ``pwd.getpwnam`` would reject identities
that only exist in the target image.  ``validate_child_spec_host`` is the
host-gate variant: identical shape and numeric checks, NO OS lookups — the
authoritative username resolution is done twice in-container, by the chown
snippet and by the wrapper's pre-spawn gate, both against the SAME target
passwd database.
"""

from __future__ import annotations

import pwd

CHILD_USER_ENV_NAME = "AF_SANDBOX_CHILD_USER"


class ChildIdentityError(ValueError):
    """The child identity spec is unusable; the message is categorical."""


def _categorical(reason: str) -> ChildIdentityError:
    # §18: never echo the raw spec — it is untrusted input; name the shape
    # of the failure only.
    return ChildIdentityError(f"AF_SANDBOX_CHILD_USER rejected: {reason}")


def _has_control_chars(spec: str) -> bool:
    return any(ord(ch) < 0x20 or ord(ch) == 0x7F for ch in spec)


def _check_spec_shape(spec) -> None:
    """Shape checks shared by every gate: string, non-empty, trimmed, no
    control characters.  Raises ``ChildIdentityError`` (categorical, §18)."""
    if not isinstance(spec, str):
        raise _categorical("spec is not a string")
    if spec == "" or spec.strip() == "":
        raise _categorical("spec is empty or whitespace-only")
    if spec != spec.strip():
        raise _categorical("spec has leading or trailing whitespace")
    if _has_control_chars(spec):
        raise _categorical("spec contains control characters")


def _parse_numeric_pair(spec: str) -> tuple:
    """Validate and parse the ``uid:gid`` form; both MUST be nonzero ints."""
    parts = spec.split(":")
    if len(parts) != 2:
        raise _categorical("numeric spec must be exactly uid:gid")
    uid_text, gid_text = parts
    if not uid_text or not gid_text:
        raise _categorical("numeric spec has an empty field")
    # ASCII digits only: str.isdigit() accepts unicode digits that
    # int() also accepts, but a passwd file never contains them.
    if not all("0" <= ch <= "9" for ch in uid_text) or not all(
        "0" <= ch <= "9" for ch in gid_text
    ):
        raise _categorical("numeric spec has a non-digit field")
    uid = int(uid_text, 10)
    gid = int(gid_text, 10)
    if uid == 0 or gid == 0:
        raise _categorical("child uid and gid must both be nonzero")
    return uid, gid


def parse_child_spec(spec) -> tuple:
    """Parse ``AF_SANDBOX_CHILD_USER`` into ``(uid, gid)``.

    Returns a tuple of two ints; BOTH are guaranteed nonzero (a child whose
    uid or primary gid is 0 is not a fixed non-privileged identity, codex
    691341d2).  Raises ``ChildIdentityError`` for every malformed or
    insecure form (see module docstring).  Runs ONLY inside the target
    container: the username lookup uses the container's passwd database,
    which is authoritative for the child identity.
    """
    _check_spec_shape(spec)
    if ":" in spec:
        return _parse_numeric_pair(spec)
    try:
        entry = pwd.getpwnam(spec)
    except KeyError:
        raise _categorical("username does not exist in this container")
    if entry.pw_uid == 0 or entry.pw_gid == 0:
        raise _categorical("child uid and gid must both be nonzero")
    return entry.pw_uid, entry.pw_gid


def validate_child_spec_host(spec) -> None:
    """Host-side gate variant of ``parse_child_spec`` (codex 087da672).

    Identical shape and numeric checks — including the nonzero uid AND gid
    floor — but NO OS lookups: the service process runs in a different
    uid/username namespace than the target container, so a host-side
    ``pwd.getpwnam`` would reject identities that exist only in the target
    image.  Username-form specs are accepted here on syntax alone; the
    AUTHORITATIVE resolution happens twice inside the container (chown
    snippet + wrapper pre-spawn gate) against the same target passwd
    database.  Raises ``ChildIdentityError`` on any malformed or insecure
    shape; returns None on acceptance (the caller passes the ORIGINAL spec
    verbatim into the container).
    """
    _check_spec_shape(spec)
    if ":" in spec:
        _parse_numeric_pair(spec)
