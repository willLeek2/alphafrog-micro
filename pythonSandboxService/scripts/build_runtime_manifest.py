#!/usr/bin/env python3
# === work-package-H (ccqwen) ===
"""Build the AlphaFrog runtime image manifest artifacts (spec section 12).

Work package H of the finance MethodSpec V5 plan. This script is the
build-time companion of ``Dockerfile.runtime`` / ``docker_build.sh`` and is
responsible for the two manifest artifacts described in spec section 12:

* ``library-set.json`` -- baked *into* the runtime image. It records the
  ``schemaVersion``, the ``lockDigest``, the ``methodSpecIndexDigest``, the
  sorted ``packages`` array (restricted to ``name`` / ``version`` / optional
  ``apiVersion``) and the ``librarySetDigest``.
* the external ``imageDigest -> {...digests, buildRevision}`` mapping -- this
  is generated *outside* the image and only **after** the immutable image ID
  is known. It must never be written back into image labels (that would be a
  self-reference).

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

# Package entries inside library-set.json are restricted to these keys only.
_PACKAGE_ALLOWED_KEYS = ("name", "version", "apiVersion")

# "@sha256:" followed by *exactly* 64 hex characters (no further hex char).
_DIGEST_REFERENCE_RE = re.compile(r"@sha256:[0-9a-fA-F]{64}(?![0-9a-fA-F])")


def _canonical_json(value: Any) -> str:
    """Canonical JSON: sorted keys, compact separators, UTF-8 friendly."""
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def _restrict_package(package: Dict[str, Any]) -> Dict[str, Any]:
    """Restrict a package entry to name/version/optional apiVersion only."""
    restricted: Dict[str, Any] = {}
    for key in _PACKAGE_ALLOWED_KEYS:
        if key in package:
            restricted[key] = package[key]
    return restricted


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

    Packages are sorted by ``name`` and each entry is restricted to
    ``name`` / ``version`` / optional ``apiVersion``. The ``librarySetDigest``
    is computed over the canonical JSON of that sorted, restricted array.
    """
    restricted_packages: List[Dict[str, Any]] = [
        _restrict_package(package) for package in packages
    ]
    ordered = sorted(restricted_packages, key=lambda package: package["name"])
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
    image_labels: Optional[Dict[str, str]] = None,
) -> Dict[str, Any]:
    """Build the external ``imageDigest -> {...}`` mapping.

    Generated only after the immutable image ID/digest is known, and keyed by
    that digest. It is consumed by deploy config and audit queries. It never
    writes anything back into ``image_labels`` (no self-reference): the
    mapping lives outside the image. ``image_labels`` is accepted purely as
    informational input and is left untouched.
    """
    # NOTE: image_labels is intentionally NOT mutated. The final image digest
    # cannot be baked back into the image without creating a self-reference.
    entry = {
        "baseImageDigest": base_image_digest,
        "lockDigest": lock_digest,
        "librarySetDigest": library_set_digest,
        "sbomDigest": sbom_digest,
        "methodSpecIndexDigest": method_spec_index_digest,
        "buildRevision": build_revision,
    }
    return {
        "schemaVersion": "1",
        "images": {
            image_digest: entry,
        },
    }


def is_digest_reference(value: Any) -> bool:
    """Return True iff ``value`` references an image by sha256 digest.

    A digest reference contains ``@sha256:`` followed by exactly 64 hex
    characters, e.g. ``registry.local/alphafrog/runtime@sha256:<64hex>``.
    """
    if not isinstance(value, str):
        return False
    return _DIGEST_REFERENCE_RE.search(value) is not None


def validate_af_sandbox_image(value: str, *, allow_dev_tag: bool) -> None:
    """Validate an ``AF_SANDBOX_IMAGE`` reference.

    Digest references are always accepted. Bare-tag references are rejected
    unless ``allow_dev_tag`` is True (the explicit dev-allow switch). Raises
    ``ValueError`` for a bare tag when the dev switch is not set.
    """
    if is_digest_reference(value):
        return
    if allow_dev_tag:
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
    manifest = build_library_set_manifest(
        lock_digest=args.lock_digest,
        method_spec_index_digest=args.method_spec_index_digest,
        packages=packages,
    )
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
        )
        with open(args.mapping_output, "w", encoding="utf-8") as handle:
            json.dump(mapping, handle, indent=2, ensure_ascii=False)
            handle.write("\n")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
