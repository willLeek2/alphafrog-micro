"""TDD skeletons for pythonSandboxService/scripts/build_runtime_manifest.py (spec §12).

Work package H (金融MethodSpec-V5 §12, lines 1277-1361): runtime image manifest
generation. The future script must:

* emit ``library-set.json`` with ``schemaVersion``, ``lockDigest``,
  ``methodSpecIndexDigest``, ``packages[]`` (name/version/optional apiVersion)
  and ``librarySetDigest``;
* sort ``packages`` by name and compute ``librarySetDigest`` over canonical
  JSON (sorted keys, compact separators, UTF-8);
* generate the external ``imageDigest -> {...digests, buildRevision}`` mapping
  only after the image ID is known, never writing it back into the image;
* reject a bare-tag ``AF_SANDBOX_IMAGE`` in production unless an explicit
  dev-allow switch is set.

The canonical-digest reference computation below is fully specified and is
written inline so the golden-vector tests PASS immediately. Tests marked
"pending implementation" fail until scripts/build_runtime_manifest.py exists.
"""

from __future__ import annotations

import hashlib
import importlib.util
import json
import re
import unittest
from pathlib import Path

SANDBOX_SERVICE_ROOT = Path(__file__).resolve().parents[1]
FUTURE_SCRIPT = SANDBOX_SERVICE_ROOT / "scripts" / "build_runtime_manifest.py"

_SHA256_RE = re.compile(r"^sha256:[0-9a-f]{64}$")

# Fixed sample from the spec §12 library-set.json example (packages given in a
# deliberately UNSORTED order to prove sorting happens before digesting).
SAMPLE_PACKAGES_UNORDERED = [
    {"name": "numpy", "version": "2.1.3"},
    {"name": "alphafrog_finance", "version": "1.0.3", "apiVersion": "1.0"},
    {"name": "pandas", "version": "2.2.3"},
]

SAMPLE_BUILD_INPUT = {
    "baseImageDigest": "sha256:base-image-example",
    "lockDigest": "sha256:requirements-lock-example",
    "methodSpecIndexDigest": "sha256:method-spec-index-example",
    "buildRevision": "git:implementation-commit-example",
}

# Precomputed golden vector (see test_reference_digest_matches_golden_vector).
GOLDEN_LIBRARY_SET_DIGEST = (
    "sha256:a69375e78e4f57c2c20bb82e9b6f0170fc91831b2ca659a958cdea266e56bd53"
)
GOLDEN_CANONICAL_BYTES = (
    '[{"apiVersion":"1.0","name":"alphafrog_finance","version":"1.0.3"},'
    '{"name":"numpy","version":"2.1.3"},'
    '{"name":"pandas","version":"2.2.3"}]'
).encode("utf-8")


def reference_library_set_digest(packages: list[dict]) -> str:
    """Reference implementation per spec §12.

    Sort the package array by name, then digest the canonical JSON of the
    sorted array: sorted object keys, compact ``(",", ":")`` separators,
    UTF-8 encoding, sha256, rendered as ``sha256:<hex>``.
    """
    ordered = sorted(packages, key=lambda package: package["name"])
    canonical = json.dumps(ordered, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    return "sha256:" + hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def require_build_runtime_manifest() -> "object":
    """Load the future script or fail the test with an actionable message."""
    if not FUTURE_SCRIPT.is_file():
        raise AssertionError(
            "pending implementation: %s does not exist yet (spec §12)" % FUTURE_SCRIPT
        )
    spec = importlib.util.spec_from_file_location("build_runtime_manifest", FUTURE_SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class LibrarySetReferenceDigestTest(unittest.TestCase):
    """Implementation-independent canonical digest reference (passes now)."""

    def test_reference_digest_matches_golden_vector(self) -> None:
        digest = reference_library_set_digest(SAMPLE_PACKAGES_UNORDERED)
        self.assertEqual(digest, GOLDEN_LIBRARY_SET_DIGEST)

    def test_reference_canonical_form_is_sorted_keys_compact_utf8(self) -> None:
        ordered = sorted(SAMPLE_PACKAGES_UNORDERED, key=lambda package: package["name"])
        canonical = json.dumps(
            ordered, sort_keys=True, separators=(",", ":"), ensure_ascii=False
        ).encode("utf-8")
        self.assertEqual(canonical, GOLDEN_CANONICAL_BYTES)
        # Compact separators: no spaces after ':' or ','; keys sorted per object.
        self.assertNotIn(b": ", canonical)
        self.assertNotIn(b", ", canonical)

    def test_reference_digest_invariant_to_input_order(self) -> None:
        forward = reference_library_set_digest(SAMPLE_PACKAGES_UNORDERED)
        reversed_order = reference_library_set_digest(list(reversed(SAMPLE_PACKAGES_UNORDERED)))
        rotated = reference_library_set_digest(
            SAMPLE_PACKAGES_UNORDERED[1:] + SAMPLE_PACKAGES_UNORDERED[:1]
        )
        self.assertEqual(forward, reversed_order)
        self.assertEqual(forward, rotated)

    def test_reference_digest_changes_when_package_changes(self) -> None:
        mutated = [dict(package) for package in SAMPLE_PACKAGES_UNORDERED]
        mutated[0] = {"name": "numpy", "version": "2.1.4"}
        self.assertNotEqual(
            reference_library_set_digest(SAMPLE_PACKAGES_UNORDERED),
            reference_library_set_digest(mutated),
        )


class LibrarySetManifestShapeTest(unittest.TestCase):
    """library-set.json shape per spec §12 example (pending implementation)."""

    def test_library_set_manifest_shape_per_spec(self) -> None:
        module = require_build_runtime_manifest()
        manifest = module.build_library_set_manifest(
            lock_digest=SAMPLE_BUILD_INPUT["lockDigest"],
            method_spec_index_digest=SAMPLE_BUILD_INPUT["methodSpecIndexDigest"],
            packages=SAMPLE_PACKAGES_UNORDERED,
        )
        self.assertEqual(manifest["schemaVersion"], "1")
        self.assertEqual(manifest["lockDigest"], SAMPLE_BUILD_INPUT["lockDigest"])
        self.assertEqual(
            manifest["methodSpecIndexDigest"], SAMPLE_BUILD_INPUT["methodSpecIndexDigest"]
        )
        self.assertEqual(
            manifest["librarySetDigest"], GOLDEN_LIBRARY_SET_DIGEST
        )
        names = [package["name"] for package in manifest["packages"]]
        self.assertEqual(names, sorted(names))
        for package in manifest["packages"]:
            self.assertIn("name", package)
            self.assertIn("version", package)
            extra = set(package) - {"name", "version", "apiVersion"}
            self.assertEqual(extra, set(), "unexpected package fields: %s" % extra)

    def test_script_sorts_packages_before_digesting(self) -> None:
        module = require_build_runtime_manifest()
        manifest_a = module.build_library_set_manifest(
            lock_digest=SAMPLE_BUILD_INPUT["lockDigest"],
            method_spec_index_digest=SAMPLE_BUILD_INPUT["methodSpecIndexDigest"],
            packages=SAMPLE_PACKAGES_UNORDERED,
        )
        manifest_b = module.build_library_set_manifest(
            lock_digest=SAMPLE_BUILD_INPUT["lockDigest"],
            method_spec_index_digest=SAMPLE_BUILD_INPUT["methodSpecIndexDigest"],
            packages=list(reversed(SAMPLE_PACKAGES_UNORDERED)),
        )
        self.assertEqual(manifest_a["librarySetDigest"], manifest_b["librarySetDigest"])
        self.assertEqual(manifest_a["packages"], manifest_b["packages"])


class ExternalDigestMappingTest(unittest.TestCase):
    """imageDigest-keyed mapping generated after the image ID is known.

    Spec §12: the mapping is produced outside the image and must never be
    written back into image labels (no self-reference).
    """

    def test_mapping_shape_keyed_by_image_digest(self) -> None:
        module = require_build_runtime_manifest()
        image_digest = "sha256:" + "ab" * 32
        mapping = module.build_external_digest_mapping(
            image_digest=image_digest,
            base_image_digest=SAMPLE_BUILD_INPUT["baseImageDigest"],
            lock_digest=SAMPLE_BUILD_INPUT["lockDigest"],
            library_set_digest=GOLDEN_LIBRARY_SET_DIGEST,
            sbom_digest="sha256:sbom-example",
            method_spec_index_digest=SAMPLE_BUILD_INPUT["methodSpecIndexDigest"],
            build_revision=SAMPLE_BUILD_INPUT["buildRevision"],
        )
        self.assertEqual(mapping["schemaVersion"], "1")
        entry = mapping["images"][image_digest]
        self.assertEqual(
            entry,
            {
                "baseImageDigest": SAMPLE_BUILD_INPUT["baseImageDigest"],
                "lockDigest": SAMPLE_BUILD_INPUT["lockDigest"],
                "librarySetDigest": GOLDEN_LIBRARY_SET_DIGEST,
                "sbomDigest": "sha256:sbom-example",
                "methodSpecIndexDigest": SAMPLE_BUILD_INPUT["methodSpecIndexDigest"],
                "buildRevision": SAMPLE_BUILD_INPUT["buildRevision"],
            },
        )

    def test_mapping_builder_takes_image_digest_as_input_not_label_mutation(self) -> None:
        module = require_build_runtime_manifest()
        image_digest = "sha256:" + "cd" * 32
        # Labels as they would exist on the built image BEFORE the final
        # digest is known; the builder must consume image_digest as input,
        # not mutate these labels to store the mapping.
        image_labels = {
            "com.alphafrog.runtime": "true",
            "com.alphafrog.lockDigest": SAMPLE_BUILD_INPUT["lockDigest"],
        }
        labels_before = dict(image_labels)
        module.build_external_digest_mapping(
            image_digest=image_digest,
            base_image_digest=SAMPLE_BUILD_INPUT["baseImageDigest"],
            lock_digest=SAMPLE_BUILD_INPUT["lockDigest"],
            library_set_digest=GOLDEN_LIBRARY_SET_DIGEST,
            sbom_digest="sha256:sbom-example",
            method_spec_index_digest=SAMPLE_BUILD_INPUT["methodSpecIndexDigest"],
            build_revision=SAMPLE_BUILD_INPUT["buildRevision"],
            image_labels=image_labels,
        )
        self.assertEqual(image_labels, labels_before)


class AfSandboxImageReferenceValidationTest(unittest.TestCase):
    """Production AF_SANDBOX_IMAGE must be a sha256 digest reference."""

    def test_sha256_digest_reference_accepted(self) -> None:
        module = require_build_runtime_manifest()
        value = "registry.local/alphafrog/runtime@sha256:" + "ef" * 32
        self.assertTrue(module.is_digest_reference(value))
        module.validate_af_sandbox_image(value, allow_dev_tag=False)

    def test_bare_tag_rejected_without_dev_allow_switch(self) -> None:
        module = require_build_runtime_manifest()
        with self.assertRaises(ValueError):
            module.validate_af_sandbox_image("repo/name:latest", allow_dev_tag=False)

    def test_bare_tag_allowed_only_with_explicit_dev_switch(self) -> None:
        module = require_build_runtime_manifest()
        module.validate_af_sandbox_image("repo/name:latest", allow_dev_tag=True)
        self.assertFalse(module.is_digest_reference("repo/name:latest"))
        self.assertTrue(_SHA256_RE.match("sha256:" + "ef" * 32))


if __name__ == "__main__":
    unittest.main()
