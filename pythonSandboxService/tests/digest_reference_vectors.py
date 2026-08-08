"""Shared accept/reject vectors for the unified digest-reference semantics.

Work package H hardening (MethodSpec V5 §12): ALL entry points that validate
a pinned runtime image ref or a bare sha256 digest value must implement the
SAME anchored, lowercase-only semantics:

* ``app/config.py``                       (is_digest_reference / validate_sandbox_image)
* ``scripts/build_runtime_manifest.py``   (is_digest_reference / validate_af_sandbox_image)
* ``deploy_latest.sh``                    (via scripts/af_digest_reference.sh)
* ``pythonSandboxService/docker_build.sh`` (via scripts/af_digest_reference.sh)

This module is the single source of truth for the accept/reject vectors used
by the H test files; the shell entry points receive these exact strings via
subprocess, so all four entry points are pinned to identical semantics.

Semantics: a pinned runtime image ref must be EXACTLY
``<repo>@sha256:<64 lowercase hex>`` -- anchored full match over the entire
string, lowercase-only hex. A bare digest value must be EXACTLY
``sha256:<64 lowercase hex>``.
"""

from __future__ import annotations

from pathlib import Path

SANDBOX_SERVICE_ROOT = Path(__file__).resolve().parents[1]
SHARED_SHELL_VALIDATOR = SANDBOX_SERVICE_ROOT / "scripts" / "af_digest_reference.sh"

HEX64 = "ef" * 32
HEX64_ALT = "0123456789abcdef" * 4
REPO = "registry.local/alphafrog/runtime"

# ACCEPT: <repo>@sha256: + exactly 64 lowercase hex chars.
ACCEPT_REFS = [
    f"{REPO}@sha256:{HEX64}",
    f"{REPO}@sha256:{HEX64_ALT}",
    # Single-component repository (implicit library/ namespace).
    f"runtime@sha256:{HEX64}",
    # Registry host with numeric port and nested path components.
    f"registry.local:5000/alphafrog/team/runtime@sha256:{HEX64}",
    # Component-internal '.', '_' and '-' separators.
    f"alphafrog_sandbox/runtime.v2-x@sha256:{HEX64}",
]

# REJECT: (value, reason). Reasons double as diagnostic labels in failures.
REJECT_REFS = [
    (f"{REPO}@sha256:{HEX64.upper()}", "uppercase hex digits"),
    (f"{REPO}@sha256:{'E' + HEX64[1:]}", "one uppercase hex digit"),
    (f"Registry.local/alphafrog/runtime@sha256:{HEX64}", "uppercase repository"),
    (f"{REPO}@sha256:{HEX64[:-1]}", "63 hex chars"),
    (f"{REPO}@sha256:{HEX64}a", "65 hex chars (trailing extra hex char)"),
    (f"{REPO}@sha256:{HEX64}:latest", "trailing tag after the digest"),
    (f"{REPO}@sha256:{HEX64} ", "trailing whitespace"),
    (f" {REPO}@sha256:{HEX64}", "leading whitespace"),
    (f"repo/name:latest", "bare tag, missing @sha256:"),
    (f"alphafrog-sandbox-runtime:latest", "bare local tag, missing @sha256:"),
    ("", "empty string"),
    (f"@sha256:{HEX64}", "empty repository"),
    (f"{REPO}/@sha256:{HEX64}", "trailing slash before @"),
    (f"registry.local//runtime@sha256:{HEX64}", "empty path component"),
    (f"registry.local:5000@sha256:{HEX64}", "registry port without path component"),
    (f"{REPO}@sha256:{'g' * 64}", "non-hex digits"),
    (f"{REPO}@sha256", "missing ':<hex>' after @sha256"),
    (f"junk\n{REPO}@sha256:{HEX64}", "newline-embedded value"),
]

# Bare sha256 digest values (docker_build.sh require_sha256_value semantics).
SHA256_ACCEPT = [
    f"sha256:{HEX64}",
    f"sha256:{HEX64_ALT}",
]

SHA256_REJECT = [
    (f"sha256:{HEX64.upper()}", "uppercase hex digits"),
    (f"sha256:{HEX64[:-1]}", "63 hex chars"),
    (f"sha256:{HEX64}a", "65 hex chars"),
    (HEX64, "missing sha256: prefix"),
    ("", "empty string"),
    ("sha256:", "no hex digits"),
    (f"sha256:{HEX64}:latest", "trailing content"),
    (f" sha256:{HEX64}", "leading whitespace"),
    (f"sha256:{HEX64}\n", "trailing newline"),
]

# --- dev-allow switch vectors (Spec §12 round-2 R2-4) ----------------------
# The explicit dev-allow switch admits ONLY syntactically VALID bare
# tag/references (no digest). These vector sets are the single source of
# truth shared by the config surface (app/config.py), the manifest surface
# (scripts/build_runtime_manifest.py) and the deploy surface
# (deploy_latest.sh via scripts/af_digest_reference.sh).

# ACCEPTED under the explicit dev switch (never without it).
VALID_DEV_REFERENCES = [
    "alphafrog-sandbox-runtime:latest",
    "alphafrog-sandbox-runtime:dev",
    "repo/name:v1.2.3-rc_1",
    "registry.local:5000/alphafrog/team/runtime:test-tag",
    # Single-component repository, no tag (implicit :latest).
    "runtime",
    # Component-internal '.', '_' and '-' separators with a tag.
    "alphafrog_sandbox/runtime.v2-x:tag_1",
]

# REJECTED ALWAYS -- under the dev switch ON as well as OFF. Malformed
# classes: empty/whitespace, whitespace/control characters inside, wrong
# digest lengths, uppercase digests, digest-shaped-but-invalid values, and
# arbitrary garbage. (value, reason); reasons double as diagnostic labels.
MALFORMED_UNDER_DEV_REFS = [
    ("", "empty string"),
    ("   ", "whitespace only"),
    ("repo/name:tag with space", "whitespace inside the reference"),
    ("repo/name:tag\tdev", "tab inside the reference"),
    ("repo/name:ta\ng", "newline inside the reference"),
    ("repo/name:tag\x01ctl", "control character inside the reference"),
    ("\x00", "NUL byte garbage (unrepresentable in argv; python surfaces only)"),
    (f"repo/name@sha256:{HEX64[:-1]}", "digest-shaped, 63 hex chars"),
    (f"repo/name@sha256:{HEX64.upper()}", "digest-shaped, uppercase hex"),
    (f"repo/name@sha256:{'E' + HEX64[1:]}", "digest-shaped, one uppercase hex digit"),
    (f"repo/name@sha256:{HEX64}a", "digest-shaped, 65 hex chars"),
    (f"repo/name@sha256:{HEX64}:latest", "digest with trailing tag"),
    ("Repo/name:latest", "uppercase repository"),
    ("repo/name:", "empty tag"),
    ("repo/name:-bad", "tag starting with a separator"),
    ("repo/name:tag@sha256:", "tag followed by digest-shaped junk"),
    ("///", "garbage slashes"),
    (":latest", "tag without repository"),
]
