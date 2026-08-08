#!/usr/bin/env python3
# === work-package-H (ccqwen) ===
"""Build the AlphaFrog runtime image manifest artifacts (spec section 12).

Work package H of the finance MethodSpec V5 plan. This script is the
build-time companion of ``Dockerfile.runtime`` / ``docker_build.sh`` and is
responsible for the two manifest artifacts described in spec section 12:

* ``library-set.json`` -- baked *into* the runtime image. It records the
  ``schemaVersion``, the ``lockDigest``, the ``methodSpecIndexDigest``, the
  sorted ``packages`` array (validated fail-closed against the exact
  allowlist ``name`` / ``version`` / optional ``apiVersion``) and the
  ``librarySetDigest``.
* the external ``imageDigest -> {...digests, buildRevision, releasable}``
  mapping -- this is generated *outside* the image and only **after** the
  immutable image ID is known. It must never be written back into image
  labels (that would be a self-reference). Its ``releasable`` flag
  implements the fail-closed release gate: any missing / placeholder release
  input forces ``releasable=false``.

Digest contract (fully specified, see ``library_set_digest`` below): sort the
package array by ``name``, serialise with canonical JSON (sorted object keys,
compact ``(",", ":")`` separators, ``ensure_ascii=False``), encode as UTF-8,
take sha256, and render as ``sha256:<hex>``.

Pure Python 3 standard library only -- no third-party imports.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from typing import Any, Dict, Iterable, List, Optional

# Package entries inside library-set.json are restricted to EXACTLY these keys
# (fail-closed allowlist: unknown fields are a hard error, never silently
# dropped). ``name`` and ``version`` are required and must be non-empty
# strings; ``apiVersion`` is optional but must be a non-empty string when
# present. Packages must be unique by name.
_PACKAGE_ALLOWED_KEYS = ("name", "version", "apiVersion")
_PACKAGE_REQUIRED_KEYS = ("name", "version")

# A digest reference must match this grammar over the ENTIRE string (used
# with ``re.fullmatch``; see ``is_digest_reference`` for the policy).
_PATH_COMPONENT = r"[a-z0-9]+(?:[._-][a-z0-9]+)*"
_DIGEST_REFERENCE_RE = re.compile(
    # Optional leading registry host component (may carry a numeric port),
    # e.g. "registry.local/" or "registry.local:5000/". A port is only
    # accepted when a path component follows it ("host:5000" alone is not a
    # plausible repository).
    r"(?:%s(?::[0-9]+)?/)?" % _PATH_COMPONENT
    # At least one path component, then any number of further components.
    # Components are non-empty, so there can be no leading/trailing slash,
    # no empty "//" components, no whitespace and no control characters.
    + _PATH_COMPONENT
    + r"(?:/%s)*" % _PATH_COMPONENT
    # Digest: "@sha256:" + exactly 64 lowercase hex chars, end-anchored by
    # the fullmatch (trailing content such as ":latest" is rejected).
    + r"@sha256:[0-9a-f]{64}"
)

# A syntactically VALID bare tag/reference (admitted ONLY under the explicit
# dev-allow switch; Spec §12 round-2 R2-4): ``<repo>[:<tag>]`` over the same
# conservative lowercase path grammar. No ``@`` anywhere -- anything
# digest-shaped must satisfy the anchored digest grammar EVEN UNDER the dev
# switch. Tag grammar per the conservative Docker reference form.
_BARE_TAG = r"[A-Za-z0-9_][A-Za-z0-9._-]{0,127}"
_DEV_TAG_REFERENCE_RE = re.compile(
    r"(?:%s(?::[0-9]+)?/)?" % _PATH_COMPONENT
    + _PATH_COMPONENT
    + r"(?:/%s)*" % _PATH_COMPONENT
    + r"(?::%s)?" % _BARE_TAG
)


def _canonical_json(value: Any) -> str:
    """Canonical JSON: sorted keys, compact separators, UTF-8 friendly."""
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def _package_label(index: int, package: Any) -> str:
    """Human-readable identifier for error messages (never embeds secrets)."""
    if isinstance(package, dict) and isinstance(package.get("name"), str) and package["name"]:
        return "package %r (index %d)" % (package["name"], index)
    return "package at index %d" % index


def _validate_package(package: Any, index: int) -> Dict[str, Any]:
    """Validate one package entry against the exact field allowlist.

    Fail-closed (Spec §12 hardening): unknown fields RAISE instead of being
    silently dropped; ``name`` and ``version`` are required non-empty strings;
    ``apiVersion`` is optional but must be a non-empty string when present.
    Every error names the offending package and field.
    """
    if not isinstance(package, dict):
        raise ValueError(
            "package at index %d is not a JSON object (got %s); a library-set "
            "package entry must be an object with fields: %s"
            % (index, type(package).__name__, ", ".join(_PACKAGE_ALLOWED_KEYS))
        )
    label = _package_label(index, package)
    for key in sorted(package):
        if key not in _PACKAGE_ALLOWED_KEYS:
            raise ValueError(
                "%s carries unexpected field %r; exact allowed fields are: %s "
                "(unknown fields are never silently dropped, Spec §12)"
                % (label, key, ", ".join(_PACKAGE_ALLOWED_KEYS))
            )
    for key in _PACKAGE_REQUIRED_KEYS:
        if key not in package:
            raise ValueError("%s is missing required field %r" % (label, key))
        value = package[key]
        if not isinstance(value, str) or not value:
            raise ValueError(
                "%s field %r must be a non-empty string (got %r)"
                % (label, key, value)
            )
    if "apiVersion" in package:
        api_version = package["apiVersion"]
        if not isinstance(api_version, str) or not api_version:
            raise ValueError(
                "%s field 'apiVersion' must be a non-empty string when "
                "present (got %r)" % (label, api_version)
            )
    return {key: package[key] for key in _PACKAGE_ALLOWED_KEYS if key in package}


def library_set_digest(packages: Iterable[Dict[str, Any]]) -> str:
    """Compute ``librarySetDigest`` over the canonical JSON of the sorted set.

    Sort the package array by ``name``, then digest the canonical JSON of the
    sorted array: sorted object keys, compact ``(",", ":")`` separators, UTF-8
    encoding, sha256, rendered as ``sha256:<hex>``.
    """
    ordered = sorted(packages, key=lambda package: package["name"])
    canonical = _canonical_json(ordered)
    return "sha256:" + hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def build_library_set_manifest(
    *,
    lock_digest: str,
    method_spec_index_digest: str,
    packages: Iterable[Dict[str, Any]],
) -> Dict[str, Any]:
    """Build the ``library-set.json`` payload baked into the runtime image.

    Packages are sorted by ``name`` and each entry is validated against the
    exact field allowlist ``name`` / ``version`` / optional ``apiVersion``
    (fail-closed: unknown fields, missing/empty ``name`` or ``version``, an
    empty ``apiVersion``, or duplicate package names raise ``ValueError``
    naming the offending package/field). The ``librarySetDigest`` is computed
    over the canonical JSON of the sorted, validated array.
    """
    validated_packages: List[Dict[str, Any]] = [
        _validate_package(package, index) for index, package in enumerate(packages)
    ]
    seen_names: set = set()
    for package in validated_packages:
        if package["name"] in seen_names:
            raise ValueError(
                "duplicate package name %r in the library set; package names "
                "must be unique (Spec §12)" % package["name"]
            )
        seen_names.add(package["name"])
    ordered = sorted(validated_packages, key=lambda package: package["name"])
    return {
        "schemaVersion": "1",
        "lockDigest": lock_digest,
        "methodSpecIndexDigest": method_spec_index_digest,
        "packages": ordered,
        "librarySetDigest": library_set_digest(ordered),
    }


def build_external_digest_mapping(
    *,
    image_digest: str,
    base_image_digest: str,
    lock_digest: str,
    library_set_digest: str,
    sbom_digest: str,
    method_spec_index_digest: str,
    build_revision: str,
    incomplete_inputs: Iterable[str] = (),
    image_labels: Optional[Dict[str, str]] = None,
    image_ref: Optional[str] = None,
) -> Dict[str, Any]:
    """Build the external ``imageDigest -> {...}`` mapping.

    Generated only after the immutable image ID/digest is known, and keyed by
    that digest: the entry KEY is the recorded immutable image ID that
    deploy_latest.sh proves identical to the deploy target (R2-3 target
    binding). It is consumed by deploy config and audit queries. It never
    writes anything back into ``image_labels`` (no self-reference): the
    mapping lives outside the image. ``image_labels`` is accepted purely as
    informational input and is left untouched.

    ``image_ref``, when given, records the build-time image reference in the
    entry (informational binding aid; the immutable ID remains authoritative).

    Release gate (Spec §12 hardening): every entry carries ``releasable``.
    ``incomplete_inputs`` names the release inputs (e.g. ``BASE_IMAGE_DIGEST``,
    ``METHOD_SPEC_INDEX_DIGEST``, ``SBOM_DIGEST``) that were missing or still
    ``REPLACE_WITH_...`` placeholders at build time. Any incomplete input
    forces ``releasable`` to ``false``; deploy tooling must refuse to deploy a
    non-releasable build unless the explicit incomplete-dev switch is set.
    """
    # NOTE: image_labels is intentionally NOT mutated. The final image digest
    # cannot be baked back into the image without creating a self-reference.
    incomplete = sorted(dict.fromkeys(incomplete_inputs))
    entry = {
        "baseImageDigest": base_image_digest,
        "lockDigest": lock_digest,
        "librarySetDigest": library_set_digest,
        "sbomDigest": sbom_digest,
        "methodSpecIndexDigest": method_spec_index_digest,
        "buildRevision": build_revision,
        "releasable": not incomplete,
        "incompleteInputs": incomplete,
    }
    if image_ref:
        entry["imageRef"] = image_ref
    return {
        "schemaVersion": "1",
        "images": {
            image_digest: entry,
        },
    }


def is_digest_reference(value: Any) -> bool:
    """Return True iff ``value`` is a complete Docker sha256 digest reference.

    The ENTIRE string must be ``<repository>@sha256:<64 lowercase hex>``:
    matching is anchored to both ends (``re.fullmatch``), so trailing content
    such as ``repo@sha256:<64hex>:latest`` is rejected.

    Repository policy (conservative, mirrors the Docker reference grammar):

    * one or more ``/``-separated non-empty components, each ``[a-z0-9]+``
      with single ``.`` / ``_`` / ``-`` separators between alphanumeric runs
      (this excludes empty components, leading/trailing separators,
      whitespace and control characters);
    * the FIRST component may carry a numeric registry port
      (``registry.local:5000/path/...``). A port is accepted only when a
      path component follows it (``host:5000`` alone is not a plausible
      repository);
    * uppercase is rejected everywhere, including in the digest hex. DNS
      hostnames are case-insensitive, but the Docker reference grammar is
      lowercase-only; accepting uppercase would admit references the Docker
      CLI itself rejects, so this validator stays strict. A single-component
      repository (``runtime@sha256:...``, implicit ``library/``) is accepted.
    """
    if not isinstance(value, str):
        return False
    return _DIGEST_REFERENCE_RE.fullmatch(value) is not None


def is_valid_dev_reference(value: Any) -> bool:
    """Return True iff ``value`` is a syntactically VALID bare tag/reference.

    This is the ONLY shape the explicit dev-allow switch admits (Spec §12
    round-2 R2-4): ``<repo>[:<tag>]`` over the conservative lowercase path
    grammar, anchored full match. Empty values, whitespace or control
    characters, uppercase repositories and malformed tags are rejected;
    anything ``@``-bearing is digest-shaped and must satisfy the anchored
    lowercase digest grammar EVEN UNDER the dev switch (rejected here).
    """
    if not isinstance(value, str) or not value:
        return False
    return _DEV_TAG_REFERENCE_RE.fullmatch(value) is not None


def validate_af_sandbox_image(value: str, *, allow_dev_tag: bool) -> None:
    """Validate an ``AF_SANDBOX_IMAGE`` reference.

    Digest references are always accepted. The dev-allow switch admits ONLY a
    syntactically valid bare tag/reference (``is_valid_dev_reference``) -- it
    is NOT a blanket bypass (Spec §12 round-2 R2-4): empty values,
    whitespace/control characters, wrong digest lengths, uppercase digests
    and arbitrary garbage are rejected regardless of the switch, and anything
    digest-shaped but not an anchored-lowercase-64hex digest reference is
    rejected EVEN UNDER the switch. Raises ``ValueError`` otherwise; never
    falls back silently to ``latest``.
    """
    if not isinstance(value, str) or not value.strip():
        raise ValueError(
            "AF_SANDBOX_IMAGE must be a non-empty image reference; got %r. "
            "There is no implicit default and no silent fallback to 'latest' "
            "(Spec §12)." % (value,)
        )
    if is_digest_reference(value):
        return
    if "@" in value:
        raise ValueError(
            "AF_SANDBOX_IMAGE %r is digest-shaped but is NOT a valid anchored "
            "sha256:<64 lowercase hex> digest reference; such values are "
            "rejected EVEN UNDER the dev-allow switch (Spec §12 R2-4)." % (value,)
        )
    if allow_dev_tag and is_valid_dev_reference(value):
        return
    raise ValueError(
        "AF_SANDBOX_IMAGE must be a sha256 digest reference "
        "(e.g. repo/name@sha256:<64hex>); got bare/undigested reference %r. "
        "Set the explicit dev-allow switch to permit a development tag." % (value,)
    )


def _load_packages(packages_file: Optional[str], packages_json: Optional[str]) -> List[Dict[str, Any]]:
    """Load the packages array from a file path or an inline JSON string."""
    if packages_file:
        with open(packages_file, "r", encoding="utf-8") as handle:
            data = json.load(handle)
    elif packages_json:
        data = json.loads(packages_json)
    else:
        raise SystemExit("error: provide --packages-file or --packages-json")
    if not isinstance(data, list):
        raise SystemExit("error: packages input must be a JSON array of objects")
    return data


def _build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="build_runtime_manifest.py",
        description="Build library-set.json and the external image digest mapping (spec section 12).",
    )
    parser.add_argument("--lock-digest", required=True, help="Digest of the requirements lock file.")
    parser.add_argument(
        "--method-spec-index-digest", required=True, help="Digest of the MethodSpec index."
    )
    parser.add_argument("--packages-file", help="Path to a JSON array of package objects.")
    parser.add_argument("--packages-json", help="Inline JSON array of package objects.")
    parser.add_argument("--base-image-digest", help="Base image digest (for the external mapping).")
    parser.add_argument("--sbom-digest", help="SBOM digest (for the external mapping).")
    parser.add_argument("--build-revision", help="Build revision, e.g. git:<commit> (for the external mapping).")
    parser.add_argument("--image-digest", help="Immutable image digest (for the external mapping).")
    parser.add_argument(
        "--image-ref",
        help="Build-time image reference recorded in the mapping entry (R2-3 target binding aid).",
    )
    parser.add_argument(
        "--incomplete-input",
        action="append",
        default=[],
        metavar="NAME",
        help=(
            "Repeatable. Names a release input (e.g. BASE_IMAGE_DIGEST, "
            "METHOD_SPEC_INDEX_DIGEST, SBOM_DIGEST) that was missing or a "
            "REPLACE_WITH_... placeholder at build time. Any occurrence marks "
            "the mapping entry releasable=false (Spec §12 release gate)."
        ),
    )
    parser.add_argument(
        "--output",
        required=True,
        help="Write the library-set.json payload to this path.",
    )
    parser.add_argument(
        "--mapping-output",
        help="If set and --image-digest is provided, also write the external digest mapping here.",
    )
    return parser


def main(argv: Optional[List[str]] = None) -> int:
    parser = _build_arg_parser()
    args = parser.parse_args(argv)

    packages = _load_packages(args.packages_file, args.packages_json)
    try:
        manifest = build_library_set_manifest(
            lock_digest=args.lock_digest,
            method_spec_index_digest=args.method_spec_index_digest,
            packages=packages,
        )
    except ValueError as exc:
        # Fail-closed package validation: report which check failed (no
        # secrets involved -- only package/field names) and exit non-zero.
        raise SystemExit("error: invalid library-set packages: %s" % exc)
    with open(args.output, "w", encoding="utf-8") as handle:
        json.dump(manifest, handle, indent=2, ensure_ascii=False)
        handle.write("\n")

    if args.mapping_output:
        if not args.image_digest:
            raise SystemExit("error: --mapping-output requires --image-digest")
        mapping = build_external_digest_mapping(
            image_digest=args.image_digest,
            base_image_digest=args.base_image_digest or "",
            lock_digest=args.lock_digest,
            library_set_digest=manifest["librarySetDigest"],
            sbom_digest=args.sbom_digest or "",
            method_spec_index_digest=args.method_spec_index_digest,
            build_revision=args.build_revision or "",
            incomplete_inputs=args.incomplete_input,
            image_ref=args.image_ref,
        )
        with open(args.mapping_output, "w", encoding="utf-8") as handle:
            json.dump(mapping, handle, indent=2, ensure_ascii=False)
            handle.write("\n")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
