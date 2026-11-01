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

class LibrarySetManifestShapeTest(unittest.TestCase):
    """library-set.json shape per spec §12 example (pending implementation)."""

class ExternalDigestMappingTest(unittest.TestCase):
    """imageDigest-keyed mapping generated after the image ID is known.

    Spec §12: the mapping is produced outside the image and must never be
    written back into image labels (no self-reference).
    """

class ExternalMappingImmutableEvidenceTest(unittest.TestCase):
    """Spec §12 immutable same-origin (reviewer codex 653674d9): the
    external mapping is DIGEST EVIDENCE. Its key must be the immutable
    iidfile image ID, and any recorded imageRef may only be the immutable
    ID or an anchored digest reference. A MUTABLE tag (repo:latest) is
    never evidence: between the build and any SBOM/deploy read the tag can
    be retargeted at a different image."""

    IMAGE_DIGEST = "sha256:" + "ab" * 32

    def build_mapping(self, module, **overrides) -> dict:
        kwargs = dict(
            image_digest=self.IMAGE_DIGEST,
            base_image_digest=SAMPLE_BUILD_INPUT["baseImageDigest"],
            lock_digest=SAMPLE_BUILD_INPUT["lockDigest"],
            library_set_digest=GOLDEN_LIBRARY_SET_DIGEST,
            sbom_digest="sha256:sbom-example",
            method_spec_index_digest=SAMPLE_BUILD_INPUT["methodSpecIndexDigest"],
            build_revision=SAMPLE_BUILD_INPUT["buildRevision"],
        )
        kwargs.update(overrides)
        return module.build_external_digest_mapping(**kwargs)

class AfSandboxImageReferenceValidationTest(unittest.TestCase):
    """Production AF_SANDBOX_IMAGE must be a sha256 digest reference."""

class DigestReferenceAnchoringTest(unittest.TestCase):
    """is_digest_reference() must full-match <repository>@sha256:<64hex>.

    Regression tests for the review finding that an unanchored ``.search()``
    wrongly accepted references with trailing content after the digest.
    """

    HEX64 = "ef" * 32
    REPO = "registry.local/alphafrog/runtime"

    def module(self) -> "object":
        return require_build_runtime_manifest()

class DigestReferenceSharedVectorsTest(unittest.TestCase):
    """Identical semantics at every entry point, pinned by shared vectors.

    The vectors live in tests/digest_reference_vectors.py and are the same
    ones used against app/config.py (test_data_intense_runtime.py) and the
    shell entry points deploy_latest.sh / docker_build.sh
    (test_runtime_image_retention.py).
    """

    def module(self) -> "object":
        return require_build_runtime_manifest()

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

class ManifestDevReferenceSharedVectorsTest(unittest.TestCase):
    """R2-4 at the manifest surface: the explicit dev-allow switch admits
    ONLY syntactically VALID bare tag/references (shared vector set); the
    malformed classes are rejected with the switch ON as well as OFF."""

    def module(self) -> "object":
        return require_build_runtime_manifest()

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

class RuntimeInventoryComparisonTest(unittest.TestCase):
    """R2-2 pure-logic core over FABRICATED inventories: PEP 503 names, lock
    parsing, the bidirectional EXACT comparator and the fail-closed verifier.
    """

    def module(self) -> "object":
        return require_inventory_module()

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

if __name__ == "__main__":
    unittest.main()
