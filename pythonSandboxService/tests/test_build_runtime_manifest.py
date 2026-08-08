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
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SANDBOX_SERVICE_ROOT = Path(__file__).resolve().parents[1]
FUTURE_SCRIPT = SANDBOX_SERVICE_ROOT / "scripts" / "build_runtime_manifest.py"
SMOKE_SCRIPT = SANDBOX_SERVICE_ROOT / "scripts" / "smoke_runtime_image.py"
INVENTORY_SCRIPT = SANDBOX_SERVICE_ROOT / "scripts" / "runtime_image_inventory.py"
RUNTIME_FINANCE_INIT = (
    SANDBOX_SERVICE_ROOT / "runtime" / "src" / "alphafrog_finance" / "__init__.py"
)

# Shared accept/reject vectors (single source of truth pinning identical
# semantics at every digest-validation entry point, Spec §12 hardening).
sys.path.insert(0, str(Path(__file__).resolve().parent))
from digest_reference_vectors import (  # noqa: E402
    ACCEPT_REFS,
    MALFORMED_UNDER_DEV_REFS,
    REJECT_REFS,
    VALID_DEV_REFERENCES,
)

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


def _load_script(name: str, path: Path) -> "object":
    """Load a stdlib-only H script as a module (never executed as __main__)."""
    if not path.is_file():
        raise AssertionError("pending implementation: %s does not exist yet (spec §12)" % path)
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def require_build_runtime_manifest() -> "object":
    """Load the future script or fail the test with an actionable message."""
    return _load_script("build_runtime_manifest", FUTURE_SCRIPT)


def require_smoke_module() -> "object":
    return _load_script("smoke_runtime_image", SMOKE_SCRIPT)


def require_inventory_module() -> "object":
    return _load_script("runtime_image_inventory", INVENTORY_SCRIPT)


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
                # Release gate (Spec §12 hardening): no incomplete inputs ->
                # releasable=true, no incompleteInputs.
                "releasable": True,
                "incompleteInputs": [],
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


class DigestReferenceAnchoringTest(unittest.TestCase):
    """is_digest_reference() must full-match <repository>@sha256:<64hex>.

    Regression tests for the review finding that an unanchored ``.search()``
    wrongly accepted references with trailing content after the digest.
    """

    HEX64 = "ef" * 32
    REPO = "registry.local/alphafrog/runtime"

    def module(self) -> "object":
        return require_build_runtime_manifest()

    def test_trailing_tag_after_digest_rejected(self) -> None:
        module = self.module()
        value = f"{self.REPO}@sha256:{self.HEX64}:latest"
        self.assertFalse(module.is_digest_reference(value))
        # Not a digest reference -> rejected without the dev-allow switch.
        with self.assertRaises(ValueError):
            module.validate_af_sandbox_image(value, allow_dev_tag=False)

    def test_trailing_garbage_after_digest_rejected(self) -> None:
        module = self.module()
        self.assertFalse(module.is_digest_reference(f"garbage@sha256:{self.HEX64}:latest"))
        self.assertFalse(module.is_digest_reference(f"{self.REPO}@sha256:{self.HEX64} "))
        self.assertFalse(module.is_digest_reference(f"{self.REPO}@sha256:{self.HEX64}\n"))

    def test_63_hex_chars_rejected(self) -> None:
        module = self.module()
        self.assertFalse(module.is_digest_reference(f"{self.REPO}@sha256:{self.HEX64[:-1]}"))

    def test_65_hex_chars_rejected(self) -> None:
        module = self.module()
        self.assertFalse(module.is_digest_reference(f"{self.REPO}@sha256:{self.HEX64}a"))

    def test_empty_repository_rejected(self) -> None:
        module = self.module()
        self.assertFalse(module.is_digest_reference(f"@sha256:{self.HEX64}"))

    def test_whitespace_inside_reference_rejected(self) -> None:
        module = self.module()
        self.assertFalse(module.is_digest_reference(f"registry.local/alpha frog/runtime@sha256:{self.HEX64}"))
        self.assertFalse(module.is_digest_reference(f"{self.REPO}@sha256: {self.HEX64}"))
        self.assertFalse(module.is_digest_reference(f"{self.REPO}\t@sha256:{self.HEX64}"))

    def test_trailing_slash_before_at_rejected(self) -> None:
        module = self.module()
        self.assertFalse(module.is_digest_reference(f"{self.REPO}/@sha256:{self.HEX64}"))

    def test_empty_path_component_rejected(self) -> None:
        module = self.module()
        self.assertFalse(module.is_digest_reference(f"registry.local//runtime@sha256:{self.HEX64}"))

    def test_uppercase_rejected_documented_conservative_choice(self) -> None:
        module = self.module()
        # Uppercase repository and uppercase digest hex are both rejected
        # (Docker's reference grammar is lowercase-only; see docstring).
        self.assertFalse(module.is_digest_reference(f"Registry.local/alphafrog/runtime@sha256:{self.HEX64}"))
        self.assertFalse(module.is_digest_reference(f"{self.REPO}@sha256:{self.HEX64.upper()}"))

    def test_non_string_rejected(self) -> None:
        module = self.module()
        self.assertFalse(module.is_digest_reference(None))
        self.assertFalse(module.is_digest_reference(12345))
        self.assertFalse(module.is_digest_reference(b"repo@sha256:" + b"ef" * 32))

    def test_registry_port_and_nested_path_accepted(self) -> None:
        module = self.module()
        value = f"registry.local:5000/alphafrog/team/runtime@sha256:{self.HEX64}"
        self.assertTrue(module.is_digest_reference(value))
        module.validate_af_sandbox_image(value, allow_dev_tag=False)

    def test_single_component_repository_accepted(self) -> None:
        module = self.module()
        self.assertTrue(module.is_digest_reference(f"runtime@sha256:{self.HEX64}"))

    def test_port_without_path_component_rejected(self) -> None:
        module = self.module()
        self.assertFalse(module.is_digest_reference(f"registry.local:5000@sha256:{self.HEX64}"))


class DigestReferenceSharedVectorsTest(unittest.TestCase):
    """Identical semantics at every entry point, pinned by shared vectors.

    The vectors live in tests/digest_reference_vectors.py and are the same
    ones used against app/config.py (test_data_intense_runtime.py) and the
    shell entry points deploy_latest.sh / docker_build.sh
    (test_runtime_image_retention.py).
    """

    def module(self) -> "object":
        return require_build_runtime_manifest()

    def test_shared_accept_vectors_fullmatch(self) -> None:
        module = self.module()
        for ref in ACCEPT_REFS:
            self.assertTrue(module.is_digest_reference(ref), f"accept vector rejected: {ref!r}")
            module.validate_af_sandbox_image(ref, allow_dev_tag=False)

    def test_shared_reject_vectors_fullmatch(self) -> None:
        module = self.module()
        for value, reason in REJECT_REFS:
            self.assertFalse(
                module.is_digest_reference(value),
                f"reject vector accepted ({reason}): {value!r}",
            )

    def test_shared_reject_vectors_fail_validation_without_dev_switch(self) -> None:
        module = self.module()
        for value, reason in REJECT_REFS:
            with self.assertRaises(ValueError, msg=f"not rejected ({reason}): {value!r}"):
                module.validate_af_sandbox_image(value, allow_dev_tag=False)


class LibrarySetPackageValidationTest(unittest.TestCase):
    """Item 4: build_library_set_manifest must fail CLOSED on bad packages.

    Unknown fields are never silently dropped; name/version are required
    non-empty strings; apiVersion must be non-empty when present; package
    names are unique. Every error names the offending package/field.
    """

    def build(self, packages: list) -> dict:
        module = require_build_runtime_manifest()
        return module.build_library_set_manifest(
            lock_digest=SAMPLE_BUILD_INPUT["lockDigest"],
            method_spec_index_digest=SAMPLE_BUILD_INPUT["methodSpecIndexDigest"],
            packages=packages,
        )

    def assert_invalid(self, packages: list, *message_fragments: str) -> ValueError:
        with self.assertRaises(ValueError) as ctx:
            self.build(packages)
        for fragment in message_fragments:
            self.assertIn(
                fragment,
                str(ctx.exception),
                f"error must name {fragment!r}; got: {ctx.exception}",
            )
        return ctx.exception

    def test_unknown_field_rejected_and_named(self) -> None:
        self.assert_invalid(
            [{"name": "numpy", "version": "2.1.3", "homepage": "https://numpy.org"}],
            "numpy",
            "homepage",
        )

    def test_missing_name_rejected(self) -> None:
        self.assert_invalid([{"version": "2.1.3"}], "name")

    def test_empty_name_rejected(self) -> None:
        self.assert_invalid([{"name": "", "version": "2.1.3"}], "name")

    def test_missing_version_rejected(self) -> None:
        self.assert_invalid([{"name": "numpy"}], "numpy", "version")

    def test_empty_version_rejected(self) -> None:
        self.assert_invalid([{"name": "numpy", "version": ""}], "numpy", "version")

    def test_non_string_version_rejected(self) -> None:
        self.assert_invalid([{"name": "numpy", "version": 2.1}], "numpy", "version")

    def test_empty_api_version_rejected(self) -> None:
        self.assert_invalid(
            [{"name": "alphafrog_finance", "version": "1.0.3", "apiVersion": ""}],
            "alphafrog_finance",
            "apiVersion",
        )

    def test_duplicate_package_name_rejected(self) -> None:
        self.assert_invalid(
            [
                {"name": "numpy", "version": "2.1.3"},
                {"name": "numpy", "version": "2.1.4"},
            ],
            "numpy",
            "duplicate",
        )

    def test_non_dict_package_rejected(self) -> None:
        self.assert_invalid(["numpy==2.1.3"], "index 0")

    def test_valid_packages_with_optional_api_version_still_accepted(self) -> None:
        manifest = self.build(SAMPLE_PACKAGES_UNORDERED)
        self.assertEqual(manifest["librarySetDigest"], GOLDEN_LIBRARY_SET_DIGEST)
        finance = manifest["packages"][0]
        self.assertEqual(finance["apiVersion"], "1.0")

    def test_cli_reports_invalid_packages_with_clean_error(self) -> None:
        module = require_build_runtime_manifest()
        with tempfile.TemporaryDirectory(prefix="af-manifest-cli-") as tmp:
            out_path = Path(tmp) / "library-set.json"
            with self.assertRaises(SystemExit) as ctx:
                module.main(
                    [
                        "--lock-digest",
                        SAMPLE_BUILD_INPUT["lockDigest"],
                        "--method-spec-index-digest",
                        SAMPLE_BUILD_INPUT["methodSpecIndexDigest"],
                        "--packages-json",
                        json.dumps([{"name": "numpy", "version": "2.1.3", "evil": True}]),
                        "--output",
                        str(out_path),
                    ]
                )
            self.assertIn("invalid library-set packages", str(ctx.exception))
            self.assertIn("numpy", str(ctx.exception))
            self.assertIn("evil", str(ctx.exception))


class ExternalMappingReleaseGateTest(unittest.TestCase):
    """Item 3: the external mapping must carry the releasable flag."""

    IMAGE_DIGEST = "sha256:" + "ab" * 32

    def build_mapping(self, incomplete_inputs=()) -> dict:
        module = require_build_runtime_manifest()
        return module.build_external_digest_mapping(
            image_digest=self.IMAGE_DIGEST,
            base_image_digest=SAMPLE_BUILD_INPUT["baseImageDigest"],
            lock_digest=SAMPLE_BUILD_INPUT["lockDigest"],
            library_set_digest=GOLDEN_LIBRARY_SET_DIGEST,
            sbom_digest="sha256:sbom-example",
            method_spec_index_digest=SAMPLE_BUILD_INPUT["methodSpecIndexDigest"],
            build_revision=SAMPLE_BUILD_INPUT["buildRevision"],
            incomplete_inputs=incomplete_inputs,
        )

    def test_complete_build_is_releasable(self) -> None:
        entry = self.build_mapping()["images"][self.IMAGE_DIGEST]
        self.assertTrue(entry["releasable"])
        self.assertEqual(entry["incompleteInputs"], [])

    def test_incomplete_inputs_force_releasable_false(self) -> None:
        entry = self.build_mapping(
            ["SBOM_DIGEST", "BASE_IMAGE_DIGEST", "BASE_IMAGE_DIGEST"]
        )["images"][self.IMAGE_DIGEST]
        self.assertFalse(entry["releasable"])
        # Sorted, de-duplicated input names (diagnostics, no secrets).
        self.assertEqual(entry["incompleteInputs"], ["BASE_IMAGE_DIGEST", "SBOM_DIGEST"])

    def test_cli_wires_incomplete_input_flag_into_mapping(self) -> None:
        module = require_build_runtime_manifest()
        with tempfile.TemporaryDirectory(prefix="af-manifest-cli-") as tmp:
            out_path = Path(tmp) / "library-set.json"
            mapping_path = Path(tmp) / "image-digest-mapping.json"
            exit_code = module.main(
                [
                    "--lock-digest",
                    SAMPLE_BUILD_INPUT["lockDigest"],
                    "--method-spec-index-digest",
                    SAMPLE_BUILD_INPUT["methodSpecIndexDigest"],
                    "--packages-json",
                    json.dumps(SAMPLE_PACKAGES_UNORDERED),
                    "--output",
                    str(out_path),
                    "--mapping-output",
                    str(mapping_path),
                    "--image-digest",
                    self.IMAGE_DIGEST,
                    "--base-image-digest",
                    "REPLACE_WITH_VERIFIED_BASE_IMAGE_DIGEST",
                    "--sbom-digest",
                    "REPLACE_WITH_VERIFIED_SBOM_DIGEST",
                    "--build-revision",
                    SAMPLE_BUILD_INPUT["buildRevision"],
                    "--incomplete-input",
                    "BASE_IMAGE_DIGEST",
                    "--incomplete-input",
                    "SBOM_DIGEST",
                ]
            )
            self.assertEqual(exit_code, 0)
            mapping = json.loads(mapping_path.read_text(encoding="utf-8"))
            entry = mapping["images"][self.IMAGE_DIGEST]
            self.assertFalse(entry["releasable"])
            self.assertEqual(entry["incompleteInputs"], ["BASE_IMAGE_DIGEST", "SBOM_DIGEST"])


class ManifestDevReferenceSharedVectorsTest(unittest.TestCase):
    """R2-4 at the manifest surface: the explicit dev-allow switch admits
    ONLY syntactically VALID bare tag/references (shared vector set); the
    malformed classes are rejected with the switch ON as well as OFF."""

    def module(self) -> "object":
        return require_build_runtime_manifest()

    def test_valid_dev_references_accepted_only_with_switch(self) -> None:
        module = self.module()
        for ref in VALID_DEV_REFERENCES:
            self.assertTrue(
                module.is_valid_dev_reference(ref),
                f"valid dev reference rejected: {ref!r}",
            )
            module.validate_af_sandbox_image(ref, allow_dev_tag=True)
            with self.assertRaises(
                ValueError, msg=f"bare reference admitted WITHOUT the switch: {ref!r}"
            ):
                module.validate_af_sandbox_image(ref, allow_dev_tag=False)

    def test_malformed_under_dev_rejected_with_switch_on_and_off(self) -> None:
        module = self.module()
        for value, reason in MALFORMED_UNDER_DEV_REFS:
            self.assertFalse(
                module.is_valid_dev_reference(value),
                f"malformed dev reference passed the grammar ({reason}): {value!r}",
            )
            for switch in (True, False):
                with self.assertRaises(
                    ValueError,
                    msg=f"not rejected ({reason}), allow_dev_tag={switch}: {value!r}",
                ):
                    module.validate_af_sandbox_image(value, allow_dev_tag=switch)

    def test_digest_shaped_reject_refs_rejected_even_under_dev_switch(self) -> None:
        # Anything '@'-bearing must satisfy the anchored lowercase digest
        # grammar; malformed digest-shaped values never ride the dev switch.
        module = self.module()
        for value, reason in REJECT_REFS:
            if "@" not in value:
                continue
            with self.assertRaises(
                ValueError, msg=f"digest-shaped value admitted under dev switch ({reason}): {value!r}"
            ):
                module.validate_af_sandbox_image(value, allow_dev_tag=True)


class SmokeGateVerificationLogicTest(unittest.TestCase):
    """R2-1 pure-logic core: verify_runtime_metadata over FABRICATED metadata
    (no import of the real distribution). The frozen triples are contract
    values pinned verbatim here too."""

    FROZEN_VERSION = "1.0.0"
    FROZEN_API_VERSION = "1.0"
    FROZEN_BINDINGS = {
        "finance.growth.cagr": {
            "methodVersion": "1.0.0",
            "specDigest": (
                "sha256:cff05d88e83b787478edfd0252c414ded02b8236b9b1032126f5cd51c4d7b25e"
            ),
        },
        "finance.risk.annualized_volatility": {
            "methodVersion": "1.0.0",
            "specDigest": (
                "sha256:2843745f0c4903083430ef0b4eef6be253b09a4c014c28decbf5884466f0d668"
            ),
        },
        "finance.risk.sharpe_ratio": {
            "methodVersion": "1.0.0",
            "specDigest": (
                "sha256:fccc1f0f9264dc90730f7a3b6a35abce2c6f2884c79a3e3b9ce0a7190058db90"
            ),
        },
    }

    def module(self) -> "object":
        return require_smoke_module()

    def bindings(self) -> dict:
        return {method_id: dict(fields) for method_id, fields in self.FROZEN_BINDINGS.items()}

    def test_frozen_contract_constants_are_pinned(self) -> None:
        module = self.module()
        self.assertEqual(module.EXPECTED_VERSION, self.FROZEN_VERSION)
        self.assertEqual(module.EXPECTED_API_VERSION, self.FROZEN_API_VERSION)
        self.assertEqual(module.EXPECTED_BINDINGS, self.FROZEN_BINDINGS)

    def test_correct_metadata_passes(self) -> None:
        module = self.module()
        problems = module.verify_runtime_metadata(
            self.FROZEN_VERSION, self.FROZEN_API_VERSION, self.bindings()
        )
        self.assertEqual(problems, [])

    def test_wrong_version_fails(self) -> None:
        module = self.module()
        problems = module.verify_runtime_metadata("1.0.1", self.FROZEN_API_VERSION, self.bindings())
        self.assertTrue(any("__version__" in problem for problem in problems), problems)

    def test_wrong_api_version_fails(self) -> None:
        module = self.module()
        problems = module.verify_runtime_metadata(self.FROZEN_VERSION, "2.0", self.bindings())
        self.assertTrue(any("__api_version__" in problem for problem in problems), problems)

    def test_missing_method_binding_fails(self) -> None:
        module = self.module()
        bindings = self.bindings()
        del bindings["finance.risk.sharpe_ratio"]
        problems = module.verify_runtime_metadata(self.FROZEN_VERSION, self.FROZEN_API_VERSION, bindings)
        self.assertTrue(
            any("finance.risk.sharpe_ratio" in problem for problem in problems), problems
        )

    def test_wrong_method_version_fails(self) -> None:
        module = self.module()
        bindings = self.bindings()
        bindings["finance.growth.cagr"]["methodVersion"] = "1.0.1"
        problems = module.verify_runtime_metadata(self.FROZEN_VERSION, self.FROZEN_API_VERSION, bindings)
        self.assertTrue(
            any("finance.growth.cagr" in problem and "methodVersion" in problem for problem in problems),
            problems,
        )

    def test_wrong_spec_digest_fails(self) -> None:
        module = self.module()
        bindings = self.bindings()
        digest = bindings["finance.risk.annualized_volatility"]["specDigest"]
        bindings["finance.risk.annualized_volatility"]["specDigest"] = (
            "sha256:0" + digest[len("sha256:0") + 1:]  # one hex digit changed
        )
        problems = module.verify_runtime_metadata(self.FROZEN_VERSION, self.FROZEN_API_VERSION, bindings)
        self.assertTrue(
            any("specDigest" in problem for problem in problems), problems
        )

    def test_bindings_unavailable_is_a_problem(self) -> None:
        module = self.module()
        problems = module.verify_runtime_metadata(self.FROZEN_VERSION, self.FROZEN_API_VERSION, None)
        self.assertTrue(any("unavailable" in problem for problem in problems), problems)

    def test_extra_bindings_beyond_frozen_three_are_not_a_smoke_concern(self) -> None:
        module = self.module()
        bindings = self.bindings()
        bindings["finance.extra.method"] = {"methodVersion": "9.9.9", "specDigest": "sha256:" + "00" * 32}
        problems = module.verify_runtime_metadata(self.FROZEN_VERSION, self.FROZEN_API_VERSION, bindings)
        self.assertEqual(problems, [])


class RuntimeInventoryComparisonTest(unittest.TestCase):
    """R2-2 pure-logic core over FABRICATED inventories: PEP 503 names, lock
    parsing, the bidirectional EXACT comparator and the fail-closed verifier.
    """

    def module(self) -> "object":
        return require_inventory_module()

    def test_pep503_normalization_collapses_runs_and_lowercases(self) -> None:
        module = self.module()
        self.assertEqual(module.normalize_package_name("AlphaFrog.Finance"), "alphafrog-finance")
        self.assertEqual(module.normalize_package_name("Foo__Bar..Baz--Qux"), "foo-bar-baz-qux")
        self.assertEqual(module.normalize_package_name("python_dateutil"), "python-dateutil")
        for bad in ("", "   ", None, 123, "-"):
            with self.assertRaises(ValueError, msg=f"non-fail-closed name: {bad!r}"):
                module.normalize_package_name(bad)

    def test_parse_lock_packages_pins_and_fail_closed_shapes(self) -> None:
        module = self.module()
        parsed = module.parse_lock_packages(
            "# whole-line comment\n\nnumpy==2.4.1\n  pandas==2.3.3  \nmatplotlib==3.10.8\n"
        )
        self.assertEqual(
            parsed,
            {"numpy": "2.4.1", "pandas": "2.3.3", "matplotlib": "3.10.8"},
        )
        with self.assertRaises(ValueError):
            module.parse_lock_packages("numpy>=2.4.1")  # not a == pin
        with self.assertRaises(ValueError):
            module.parse_lock_packages("numpy==2.4.1\nnumpy==2.4.2")  # duplicate pin
        with self.assertRaises(ValueError):
            module.parse_lock_packages("==2.4.1")  # empty name
        with self.assertRaises(ValueError):
            module.parse_lock_packages("numpy==")  # empty version

    def test_compare_exact_inventories_pass(self) -> None:
        module = self.module()
        expected = {"numpy": "2.4.1", "pandas": "2.3.3", "alphafrog-finance": "1.0.0"}
        report = module.compare_library_inventories(dict(expected), dict(expected))
        self.assertTrue(report["ok"])
        self.assertEqual(report["missing"], [])
        self.assertEqual(report["extra"], [])
        self.assertEqual(report["versionMismatch"], [])
        self.assertFalse(report["apiVersionMismatch"])

    def test_compare_reports_missing_extra_and_version_mismatch(self) -> None:
        module = self.module()
        expected = {"numpy": "2.4.1", "pandas": "2.3.3"}
        actual = {"numpy": "2.4.0", "scipy": "1.17.0"}
        report = module.compare_library_inventories(expected, actual)
        self.assertFalse(report["ok"])
        self.assertEqual(report["missing"], ["pandas"])
        self.assertEqual(report["extra"], ["scipy"])
        self.assertEqual(report["versionMismatch"], [["numpy", "2.4.1", "2.4.0"]])

    def test_compare_api_version_mismatch_only_when_expected_given(self) -> None:
        module = self.module()
        report = module.compare_library_inventories({}, {}, expected_api_version="1.0", actual_api_version="2.0")
        self.assertTrue(report["apiVersionMismatch"])
        self.assertFalse(report["ok"])
        report = module.compare_library_inventories({}, {}, expected_api_version=None, actual_api_version="2.0")
        self.assertFalse(report["apiVersionMismatch"])
        report = module.compare_library_inventories({}, {}, expected_api_version="1.0", actual_api_version="1.0")
        self.assertFalse(report["apiVersionMismatch"])

    def test_verify_duplicates_are_extra_managed_packages(self) -> None:
        module = self.module()
        inventory = {
            "packages": [{"name": "numpy", "version": "2.4.1"}],
            "apiVersion": "1.0",
            "duplicateNames": ["numpy"],
        }
        problems = module.verify_runtime_inventory({"numpy": "2.4.1"}, inventory, "1.0")
        self.assertTrue(any("duplicate" in problem for problem in problems), problems)

    def test_verify_missing_and_version_mismatch_fail_closed(self) -> None:
        module = self.module()
        inventory = {
            "packages": [{"name": "numpy", "version": "2.4.0"}],
            "apiVersion": "1.0",
            "duplicateNames": [],
        }
        expected = {"numpy": "2.4.1", "alphafrog-finance": "1.0.0"}
        problems = module.verify_runtime_inventory(expected, inventory, "1.0")
        self.assertTrue(any("alphafrog-finance" in problem for problem in problems), problems)
        self.assertTrue(any("numpy" in problem for problem in problems), problems)

    def test_verify_ignores_packages_outside_the_managed_namespace(self) -> None:
        module = self.module()
        inventory = {
            "packages": [
                {"name": "numpy", "version": "2.4.1"},
                {"name": "pip", "version": "24.0"},          # base tooling
                {"name": "python-dateutil", "version": "2.9.0"},  # transitive
            ],
            "apiVersion": "1.0",
            "duplicateNames": [],
        }
        problems = module.verify_runtime_inventory({"numpy": "2.4.1"}, inventory, "1.0")
        self.assertEqual(problems, [])

    def test_verify_api_version_mismatch_fails_closed(self) -> None:
        module = self.module()
        inventory = {
            "packages": [{"name": "numpy", "version": "2.4.1"}],
            "apiVersion": "2.0",
            "duplicateNames": [],
        }
        problems = module.verify_runtime_inventory({"numpy": "2.4.1"}, inventory, "1.0")
        self.assertTrue(any("apiVersion" in problem for problem in problems), problems)

    def test_read_finance_constants_from_real_runtime_source(self) -> None:
        module = self.module()
        self.assertTrue(RUNTIME_FINANCE_INIT.is_file(), f"missing: {RUNTIME_FINANCE_INIT}")
        version, api_version = module.read_finance_constants(str(RUNTIME_FINANCE_INIT))
        self.assertEqual(version, "1.0.0")
        self.assertEqual(api_version, "1.0")

    def test_read_finance_constants_missing_fails_closed(self) -> None:
        module = self.module()
        with tempfile.TemporaryDirectory(prefix="af-inventory-") as tmp:
            init_py = Path(tmp) / "__init__.py"
            init_py.write_text("__version__ = '1.0.0'\n", encoding="utf-8")
            with self.assertRaises(ValueError):
                module.read_finance_constants(str(init_py))

    def test_packages_for_library_set_attaches_api_version_to_finance_only(self) -> None:
        module = self.module()
        inventory = {
            "packages": [
                {"name": "numpy", "version": "2.4.1"},
                {"name": "alphafrog-finance", "version": "1.0.0"},
            ],
            "apiVersion": "1.0",
            "duplicateNames": [],
        }
        entries = module.packages_for_library_set(inventory)
        self.assertEqual(
            entries,
            [
                {"name": "alphafrog-finance", "version": "1.0.0", "apiVersion": "1.0"},
                {"name": "numpy", "version": "2.4.1"},
            ],
        )

    def test_cli_verify_pass_and_drift(self) -> None:
        module = self.module()
        with tempfile.TemporaryDirectory(prefix="af-inventory-cli-") as tmp:
            lock = Path(tmp) / "requirements-image.lock"
            lock.write_text("numpy==2.4.1\n", encoding="utf-8")
            init_py = Path(tmp) / "__init__.py"
            init_py.write_text(
                "__version__ = '1.0.0'\n__api_version__ = '1.0'\n", encoding="utf-8"
            )
            actual = Path(tmp) / "inventory.json"
            actual.write_text(
                json.dumps(
                    {
                        "packages": [
                            {"name": "numpy", "version": "2.4.1"},
                            {"name": "alphafrog-finance", "version": "1.0.0"},
                        ],
                        "apiVersion": "1.0",
                        "duplicateNames": [],
                    }
                ),
                encoding="utf-8",
            )
            packages_out = Path(tmp) / "verified-packages.json"
            exit_code = module.main(
                [
                    "--verify",
                    "--lock",
                    str(lock),
                    "--actual-json",
                    str(actual),
                    "--finance-init",
                    str(init_py),
                    "--packages-out",
                    str(packages_out),
                ]
            )
            self.assertEqual(exit_code, 0)
            verified = json.loads(packages_out.read_text(encoding="utf-8"))
            self.assertEqual(
                verified,
                [
                    {"name": "alphafrog-finance", "version": "1.0.0", "apiVersion": "1.0"},
                    {"name": "numpy", "version": "2.4.1"},
                ],
            )
            # Version drift fails closed (non-zero exit).
            drifted = json.loads(actual.read_text(encoding="utf-8"))
            drifted["packages"][0]["version"] = "9.9.9"
            actual.write_text(json.dumps(drifted), encoding="utf-8")
            self.assertEqual(
                module.main(
                    [
                        "--verify",
                        "--lock",
                        str(lock),
                        "--actual-json",
                        str(actual),
                        "--finance-init",
                        str(init_py),
                    ]
                ),
                1,
            )


def _docker_e2e_enabled() -> bool:
    """Docker-gated E2E skeletons run ONLY when frog/CI explicitly opts in
    (AF_RUN_DOCKER_TESTS=1) AND a docker CLI is reachable; otherwise they
    skip cleanly (agent environments have no docker, Spec §12 rule)."""
    if os.environ.get("AF_RUN_DOCKER_TESTS") != "1":
        return False
    return shutil.which("docker") is not None


@unittest.skipUnless(
    _docker_e2e_enabled(),
    "docker-gated E2E: set AF_RUN_DOCKER_TESTS=1 with a reachable docker CLI",
)
class SmokeGateDockerE2ETest(unittest.TestCase):
    """R2-1 E2E skeleton: scripts/smoke_runtime_image.py inside the BUILT
    image, under BOTH the system python and the compat venv. The image under
    test defaults to alphafrog-sandbox-runtime:latest (override with
    AF_RUNTIME_E2E_IMAGE)."""

    def image(self) -> str:
        return os.environ.get("AF_RUNTIME_E2E_IMAGE", "alphafrog-sandbox-runtime:latest")

    def run_smoke(self, interpreter: str) -> subprocess.CompletedProcess:
        return subprocess.run(
            [
                "docker",
                "run",
                "--rm",
                self.image(),
                interpreter,
                "/opt/alphafrog/build/smoke_runtime_image.py",
            ],
            capture_output=True,
            text=True,
            timeout=300,
            check=False,
        )

    def test_smoke_passes_under_system_python_and_compat_venv(self) -> None:
        for interpreter in ("python", "/sandbox/.sandbox-venv/bin/python"):
            proc = self.run_smoke(interpreter)
            self.assertEqual(
                proc.returncode,
                0,
                f"smoke gate failed under {interpreter}\n"
                f"stdout={proc.stdout}\nstderr={proc.stderr}",
            )
            self.assertIn("smoke OK", proc.stdout)


@unittest.skipUnless(
    _docker_e2e_enabled(),
    "docker-gated E2E: set AF_RUN_DOCKER_TESTS=1 with a reachable docker CLI",
)
class InventoryGateDockerE2ETest(unittest.TestCase):
    """R2-2 E2E skeleton: query the image's ACTUAL executing interpreter via
    scripts/runtime_image_inventory.py --print-json, then verify it host-side
    against the real lockfile + runtime source (fail-closed compare)."""

    def image(self) -> str:
        return os.environ.get("AF_RUNTIME_E2E_IMAGE", "alphafrog-sandbox-runtime:latest")

    def test_actual_inventory_verifies_against_expected_set(self) -> None:
        module = require_inventory_module()
        proc = subprocess.run(
            [
                "docker",
                "run",
                "--rm",
                self.image(),
                "python",
                "/opt/alphafrog/build/runtime_image_inventory.py",
                "--print-json",
            ],
            capture_output=True,
            text=True,
            timeout=300,
            check=False,
        )
        self.assertEqual(
            proc.returncode,
            0,
            f"inventory query failed\nstdout={proc.stdout}\nstderr={proc.stderr}",
        )
        inventory = json.loads(proc.stdout)
        self.assertIsInstance(inventory.get("packages"), list)

        with tempfile.TemporaryDirectory(prefix="af-inventory-e2e-") as tmp:
            actual_path = Path(tmp) / "image-inventory.json"
            actual_path.write_text(json.dumps(inventory), encoding="utf-8")
            exit_code = module.main(
                [
                    "--verify",
                    "--lock",
                    str(SANDBOX_SERVICE_ROOT / "requirements-image.lock"),
                    "--actual-json",
                    str(actual_path),
                    "--finance-init",
                    str(RUNTIME_FINANCE_INIT),
                    "--packages-out",
                    str(Path(tmp) / "verified-packages.json"),
                ]
            )
            self.assertEqual(
                exit_code,
                0,
                "image inventory does not match the expected library set",
            )


if __name__ == "__main__":
    unittest.main()
