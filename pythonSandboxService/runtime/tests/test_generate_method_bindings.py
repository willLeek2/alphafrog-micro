# === work-package-B (ccqwen) ===
"""Tests for runtime/scripts/generate_method_bindings.py and the packaging
metadata (Spec §6, codex f1ed6ea9; index.json consumption and strict
bidirectional coverage per codex 97ea103a). The generator is the only
sanctioned path from work package A's canonical generated JSON into
alphafrog_finance/_generated/method_specs.json; hand-copied method triples
are forbidden. The committed fixtures under tests/fixtures/
a-generated-resources-v1/ are VERBATIM copies of A's FINAL generated
directory (per-method spec files + resolver-catalog.json + index.json) and
are never mutated — every failure-mode test operates on a throwaway copy.

Run from pythonSandboxService/:

    python3 -m unittest discover -s runtime/tests -p 'test_*.py' -v
"""
import hashlib
import importlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import tomllib
import unittest

_SRC = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "src")
if _SRC not in sys.path:
    sys.path.insert(0, _SRC)

from alphafrog_finance import reporting  # noqa: E402  (_method_specs loader)

_TESTS_DIR = os.path.dirname(os.path.abspath(__file__))
_RUNTIME_DIR = os.path.dirname(_TESTS_DIR)
_GENERATOR = os.path.join(_RUNTIME_DIR, "scripts", "generate_method_bindings.py")
_FIXTURE_DIR = os.path.join(_TESTS_DIR, "fixtures", "a-generated-resources-v1")
_CATALOG_NAME = "resolver-catalog.json"
_INDEX_NAME = "index.json"
_RESERVED_NAMES = frozenset({_CATALOG_NAME, _INDEX_NAME})
_PYPROJECT = os.path.join(_RUNTIME_DIR, "pyproject.toml")

# Spec §6 frozen identities (>=3 gate).
_FROZEN_IDS = frozenset(
    {
        "finance.growth.cagr",
        "finance.risk.annualized_volatility",
        "finance.risk.sharpe_ratio",
    }
)

# Frozen Spec §6 digests, VERBATIM as delivered in A's final index.json /
# resolver-catalog.json / spec files (codex 97ea103a).
_FROZEN_SPEC_DIGESTS = {
    "finance.growth.cagr": (
        "sha256:cff05d88e83b787478edfd0252c414ded02b8236b9b1032126f5cd51c4d7b25e"
    ),
    "finance.risk.annualized_volatility": (
        "sha256:2843745f0c4903083430ef0b4eef6be253b09a4c014c28decbf5884466f0d668"
    ),
    "finance.risk.sharpe_ratio": (
        "sha256:fccc1f0f9264dc90730f7a3b6a35abce2c6f2884c79a3e3b9ce0a7190058db90"
    ),
}

# Env gate for the live e2e run against A's final generated directory.
_LIVE_DIR_ENV = "AF_A_FINAL_GENERATED_DIR"


def _run_generator(args):
    return subprocess.run(
        [sys.executable, _GENERATOR] + list(args),
        capture_output=True,
        text=True,
    )


def _sha256_file(path):
    with open(path, "rb") as fh:
        return hashlib.sha256(fh.read()).hexdigest()


def _expected_methods_from_fixtures():
    """methodId -> loader entry, derived from the committed fixture specs."""
    methods = {}
    for name in sorted(os.listdir(_FIXTURE_DIR)):
        if not name.endswith(".json") or name in _RESERVED_NAMES:
            continue
        with open(os.path.join(_FIXTURE_DIR, name), encoding="utf-8") as fh:
            spec = json.load(fh)
        methods[spec["methodId"]] = {
            "methodVersion": spec["version"],
            "specDigest": spec["specDigest"],
        }
    return methods


class GenerateMethodBindingsTests(unittest.TestCase):
    """Success-path contract of the build-time generator."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory(prefix="gen-method-bindings-")
        self.addCleanup(self._tmp.cleanup)
        self.tmpdir = self._tmp.name

    def _generate(self, canonical_dir=None, out_name="method_specs.json"):
        out = os.path.join(self.tmpdir, out_name)
        result = _run_generator(
            ["--canonical-dir", canonical_dir or _FIXTURE_DIR, "--out", out]
        )
        return result, out

    def test_success_payload_values_and_loader_shape(self):
        result, out = self._generate()
        self.assertEqual(result.returncode, 0, result.stderr)
        with open(out, encoding="utf-8") as fh:
            payload = json.load(fh)
        expected = _expected_methods_from_fixtures()
        self.assertEqual(set(expected), _FROZEN_IDS)
        # Exactly the loader shape: {"methods": {methodId: {...}}} with
        # verbatim version/specDigest values from A's canonical fixtures —
        # and EXACTLY the three frozen bindings, no more, no fewer.
        self.assertIsInstance(payload, dict)
        self.assertEqual(payload, {"methods": expected})
        self.assertEqual(set(payload["methods"]), _FROZEN_IDS)
        self.assertEqual(len(payload["methods"]), 3)
        for entry in payload["methods"].values():
            self.assertEqual(set(entry), {"methodVersion", "specDigest"})
        # Direct loader-compat check: reporting._method_specs() must accept it.
        old_path, old_cache = reporting._METHOD_SPECS_PATH, reporting._METHOD_SPECS_CACHE
        reporting._METHOD_SPECS_PATH = out
        reporting._METHOD_SPECS_CACHE = None
        try:
            self.assertEqual(dict(reporting._method_specs()), expected)
        finally:
            reporting._METHOD_SPECS_PATH = old_path
            reporting._METHOD_SPECS_CACHE = old_cache

    def test_success_matches_frozen_spec_digests_verbatim(self):
        result, out = self._generate()
        self.assertEqual(result.returncode, 0, result.stderr)
        with open(out, encoding="utf-8") as fh:
            payload = json.load(fh)
        # Exactly 3 bindings, each carrying the frozen Spec §6 triple values
        # VERBATIM as delivered in A's final generated directory.
        self.assertEqual(len(payload["methods"]), 3)
        for method_id, digest in _FROZEN_SPEC_DIGESTS.items():
            self.assertIn(method_id, payload["methods"])
            self.assertEqual(payload["methods"][method_id]["specDigest"], digest)
            self.assertEqual(payload["methods"][method_id]["methodVersion"], "1.0.0")

    def test_two_consecutive_runs_are_sha256_identical(self):
        first, out1 = self._generate(out_name="run1.json")
        second, out2 = self._generate(out_name="run2.json")
        self.assertEqual(first.returncode, 0, first.stderr)
        self.assertEqual(second.returncode, 0, second.stderr)
        self.assertEqual(_sha256_file(out1), _sha256_file(out2))
        with open(out1, encoding="utf-8") as fh:
            self.assertEqual(len(json.load(fh)["methods"]), 3)

    def test_byte_deterministic_serialization(self):
        first, out1 = self._generate(out_name="first.json")
        second, out2 = self._generate(out_name="second.json")
        self.assertEqual(first.returncode, 0, first.stderr)
        self.assertEqual(second.returncode, 0, second.stderr)
        with open(out1, "rb") as fh:
            bytes1 = fh.read()
        with open(out2, "rb") as fh:
            bytes2 = fh.read()
        self.assertEqual(bytes1, bytes2)
        # Byte contract: json.dumps(ensure_ascii=False, sort_keys=True,
        # separators=(",", ":")) + single trailing newline, UTF-8.
        payload = json.loads(bytes1.decode("utf-8"))
        expected_bytes = (
            json.dumps(
                payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")
            )
            + "\n"
        ).encode("utf-8")
        self.assertEqual(bytes1, expected_bytes)
        self.assertTrue(bytes1.endswith(b"\n"))
        self.assertFalse(bytes1.endswith(b"\n\n"))

    def test_success_summary_line(self):
        result, out = self._generate()
        self.assertEqual(result.returncode, 0, result.stderr)
        summary = result.stdout.strip()
        self.assertEqual(summary.count("\n"), 0)  # one line
        self.assertIn("3 method spec(s)", summary)
        for method_id in sorted(_FROZEN_IDS):
            self.assertIn(method_id, summary)
        self.assertIn(out, summary)

    def test_help_exits_zero(self):
        result = _run_generator(["--help"])
        self.assertEqual(result.returncode, 0)
        self.assertIn("--canonical-dir", result.stdout)
        self.assertIn("--out", result.stdout)


class GenerateMethodBindingsFailureTests(unittest.TestCase):
    """Each failure mode exits non-zero and leaves no output file behind."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory(prefix="gen-method-bindings-fail-")
        self.addCleanup(self._tmp.cleanup)
        self.tmpdir = self._tmp.name
        self.out = os.path.join(self.tmpdir, "method_specs.json")

    def _mutated_copy(self, mutate):
        dst = os.path.join(self.tmpdir, "canonical")
        shutil.copytree(_FIXTURE_DIR, dst)
        mutate(dst)
        return dst

    @staticmethod
    def _dump_spec(directory, name, patch):
        path = os.path.join(directory, name)
        with open(path, encoding="utf-8") as fh:
            spec = json.load(fh)
        spec.update(patch)
        with open(path, "w", encoding="utf-8") as fh:
            json.dump(spec, fh, ensure_ascii=False, indent=2)

    @staticmethod
    def _write_json(directory, name, payload):
        with open(os.path.join(directory, name), "w", encoding="utf-8") as fh:
            json.dump(payload, fh, ensure_ascii=False, indent=2)

    def _mutate_json(self, directory, name, mutate):
        path = os.path.join(directory, name)
        with open(path, encoding="utf-8") as fh:
            payload = json.load(fh)
        mutate(payload)
        self._write_json(directory, name, payload)

    def _assert_fails_closed(self, canonical_dir):
        result = _run_generator(
            ["--canonical-dir", canonical_dir, "--out", self.out]
        )
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn("error", result.stderr)
        self.assertFalse(
            os.path.exists(self.out),
            "generator must not leave a partial output file behind",
        )
        return result

    def test_missing_catalog_fails(self):
        def mutate(dst):
            os.remove(os.path.join(dst, _CATALOG_NAME))

        self._assert_fails_closed(self._mutated_copy(mutate))

    def test_catalog_entry_without_spec_file_fails(self):
        def mutate(dst):
            os.remove(os.path.join(dst, "sharpe_ratio.json"))

        self._assert_fails_closed(self._mutated_copy(mutate))

    def test_tampered_spec_digest_fails(self):
        def mutate(dst):
            self._dump_spec(
                dst, "cagr.json", {"specDigest": "sha256:" + "0" * 64}
            )

        self._assert_fails_closed(self._mutated_copy(mutate))

    def test_version_mismatch_fails(self):
        def mutate(dst):
            self._dump_spec(dst, "cagr.json", {"version": "1.0.1"})

        self._assert_fails_closed(self._mutated_copy(mutate))

    def test_duplicate_method_id_fails(self):
        def mutate(dst):
            shutil.copy(
                os.path.join(dst, "annualized_volatility.json"),
                os.path.join(dst, "zz_duplicate.json"),
            )

        self._assert_fails_closed(self._mutated_copy(mutate))

    def test_empty_method_id_fails(self):
        def mutate(dst):
            self._dump_spec(dst, "cagr.json", {"methodId": ""})

        self._assert_fails_closed(self._mutated_copy(mutate))

    def test_missing_frozen_id_fails(self):
        # Drop the sharpe_ratio spec AND its catalog entry AND its index
        # entry so catalog, index, and specs stay mutually consistent — the
        # failure must come from the Spec §6 frozen-identity (>=3) gate
        # itself.
        def mutate(dst):
            os.remove(os.path.join(dst, "sharpe_ratio.json"))

            def drop_sharpe(entries):
                entries[:] = [
                    e for e in entries if e["methodId"] != "finance.risk.sharpe_ratio"
                ]

            self._mutate_json(dst, _CATALOG_NAME, drop_sharpe)
            self._mutate_json(dst, _INDEX_NAME, drop_sharpe)

        result = self._assert_fails_closed(self._mutated_copy(mutate))
        self.assertIn("frozen method ids missing", result.stderr)

    def test_missing_canonical_dir_fails(self):
        self._assert_fails_closed(os.path.join(self.tmpdir, "does-not-exist"))

    # --- index.json shape (codex 97ea103a) -------------------------------

    def test_index_missing_fails(self):
        def mutate(dst):
            os.remove(os.path.join(dst, _INDEX_NAME))

        result = self._assert_fails_closed(self._mutated_copy(mutate))
        self.assertIn("missing index", result.stderr)

    def test_index_not_an_array_fails(self):
        def mutate(dst):
            self._write_json(dst, _INDEX_NAME, {"methodId": "finance.growth.cagr"})

        result = self._assert_fails_closed(self._mutated_copy(mutate))
        self.assertIn("must be a JSON array", result.stderr)

    def test_index_entry_not_an_object_fails(self):
        def mutate(dst):
            def corrupt(entries):
                entries[0] = "not-an-object"

            self._mutate_json(dst, _INDEX_NAME, corrupt)

        result = self._assert_fails_closed(self._mutated_copy(mutate))
        self.assertIn("index entry must be a JSON object", result.stderr)

    def test_index_entry_missing_key_fails(self):
        def mutate(dst):
            def drop_key(entries):
                del entries[0]["version"]

            self._mutate_json(dst, _INDEX_NAME, drop_key)

        result = self._assert_fails_closed(self._mutated_copy(mutate))
        self.assertIn("missing key(s)", result.stderr)

    def test_index_entry_extra_key_fails(self):
        def mutate(dst):
            def add_key(entries):
                entries[0]["aliases"] = []

            self._mutate_json(dst, _INDEX_NAME, add_key)

        result = self._assert_fails_closed(self._mutated_copy(mutate))
        self.assertIn("unknown key(s)", result.stderr)

    def test_index_duplicate_method_id_fails(self):
        def mutate(dst):
            def duplicate(entries):
                entries[1]["methodId"] = entries[0]["methodId"]

            self._mutate_json(dst, _INDEX_NAME, duplicate)

        result = self._assert_fails_closed(self._mutated_copy(mutate))
        self.assertIn("duplicate methodId", result.stderr)

    # --- entry <-> spec files ---------------------------------------------

    def test_index_wrong_version_fails(self):
        def mutate(dst):
            def wrong_version(entries):
                entries[0]["version"] = "9.9.9"

            self._mutate_json(dst, _INDEX_NAME, wrong_version)

        result = self._assert_fails_closed(self._mutated_copy(mutate))
        self.assertIn("version mismatch", result.stderr)

    def test_index_wrong_spec_digest_fails(self):
        def mutate(dst):
            def wrong_digest(entries):
                entries[0]["specDigest"] = "sha256:" + "0" * 64

            self._mutate_json(dst, _INDEX_NAME, wrong_digest)

        result = self._assert_fails_closed(self._mutated_copy(mutate))
        self.assertIn("specDigest mismatch", result.stderr)

    def test_index_resource_path_basename_wrong_fails(self):
        # The entry points at a spec file that EXISTS but belongs to a
        # different method — the verbatim methodId check must reject it.
        def mutate(dst):
            def wrong_basename(entries):
                entries[0]["resourcePath"] = "finance/method-specs/v1/sharpe_ratio.json"

            self._mutate_json(dst, _INDEX_NAME, wrong_basename)

        result = self._assert_fails_closed(self._mutated_copy(mutate))
        self.assertIn("methodId mismatch", result.stderr)

    def test_index_resource_path_absolute_fails(self):
        def mutate(dst):
            def absolute(entries):
                entries[0]["resourcePath"] = "/etc/alphafrog/cagr.json"

            self._mutate_json(dst, _INDEX_NAME, absolute)

        result = self._assert_fails_closed(self._mutated_copy(mutate))
        self.assertIn("must be a relative path", result.stderr)

    def test_index_resource_path_traversal_fails(self):
        def mutate(dst):
            def traversal(entries):
                entries[0]["resourcePath"] = (
                    "finance/method-specs/v1/../../../cagr.json"
                )

            self._mutate_json(dst, _INDEX_NAME, traversal)

        result = self._assert_fails_closed(self._mutated_copy(mutate))
        self.assertIn("must not traverse", result.stderr)

    def test_index_referenced_spec_file_missing_fails(self):
        def mutate(dst):
            os.remove(os.path.join(dst, "cagr.json"))

        result = self._assert_fails_closed(self._mutated_copy(mutate))
        self.assertIn("does not", result.stderr)
        self.assertIn("exist in the canonical directory", result.stderr)

    def test_extra_spec_file_not_referenced_by_index_fails(self):
        def mutate(dst):
            shutil.copy(
                os.path.join(dst, "cagr.json"),
                os.path.join(dst, "zz_extra_unreferenced.json"),
            )

        result = self._assert_fails_closed(self._mutated_copy(mutate))
        self.assertIn("not referenced by index.json", result.stderr)

    def test_spec_method_id_differs_from_index_entry_fails(self):
        def mutate(dst):
            self._dump_spec(dst, "cagr.json", {"methodId": "finance.growth.other"})

        result = self._assert_fails_closed(self._mutated_copy(mutate))
        self.assertIn("methodId mismatch", result.stderr)

    # --- entry <-> resolver-catalog.json -----------------------------------

    def test_index_spec_digest_differs_from_catalog_fails(self):
        def mutate(dst):
            def tamper(entries):
                for entry in entries:
                    if entry["methodId"] == "finance.growth.cagr":
                        entry["specDigest"] = "sha256:" + "1" * 64

            self._mutate_json(dst, _CATALOG_NAME, tamper)

        result = self._assert_fails_closed(self._mutated_copy(mutate))
        self.assertIn("specDigest mismatch between index.json", result.stderr)

    def test_catalog_missing_index_method_fails(self):
        def mutate(dst):
            def drop_cagr(entries):
                entries[:] = [
                    e for e in entries if e["methodId"] != "finance.growth.cagr"
                ]

            self._mutate_json(dst, _CATALOG_NAME, drop_cagr)

        result = self._assert_fails_closed(self._mutated_copy(mutate))
        self.assertIn("missing from resolver-catalog.json", result.stderr)

    def test_catalog_extra_method_fails(self):
        def mutate(dst):
            def add_extra(entries):
                entries.append(
                    {
                        "methodId": "finance.extra.method",
                        "version": "1.0.0",
                        "specDigest": "sha256:" + "ab" * 32,
                    }
                )

            self._mutate_json(dst, _CATALOG_NAME, add_extra)

        result = self._assert_fails_closed(self._mutated_copy(mutate))
        self.assertIn("has no corresponding index.json entry", result.stderr)


class TestPackagingMetadata(unittest.TestCase):
    """pyproject.toml exists and pins the real distribution identity."""

    def test_distribution_name_and_dynamic_version_source(self):
        self.assertTrue(os.path.isfile(_PYPROJECT), _PYPROJECT)
        with open(_PYPROJECT, "rb") as fh:
            data = tomllib.load(fh)
        self.assertEqual(data["project"]["name"], "alphafrog_finance")
        self.assertIn("version", data["project"].get("dynamic", []))
        # The declared dynamic version source must resolve to exactly the
        # package's own __version__ attribute (verbatim equality, Spec §6).
        source = data["tool"]["setuptools"]["dynamic"]["version"]
        self.assertEqual(source, {"attr": "alphafrog_finance.__version__"})
        module_name, _, attr_name = source["attr"].rpartition(".")
        module = importlib.import_module(module_name)
        resolved = getattr(module, attr_name)
        import alphafrog_finance

        self.assertEqual(resolved, alphafrog_finance.__version__)
        self.assertEqual(resolved, "1.0.0")


@unittest.skipUnless(
    os.environ.get(_LIVE_DIR_ENV),
    f"live e2e against A's final generated dir; enable by setting {_LIVE_DIR_ENV}",
)
class LiveAFinalGeneratedDirTests(unittest.TestCase):
    """Opt-in end-to-end run against work package A's FINAL generated dir.

    Gated on AF_A_FINAL_GENERATED_DIR (codex 97ea103a): unset -> skip. When
    set (to e.g. .../generated-resources/finance/method-specs/v1), the
    generator must consume the real index.json + catalog + specs, exit 0
    with exactly the three frozen bindings, and emit output byte-identical
    (sha256) to the committed verbatim fixture run.
    """

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory(prefix="gen-method-bindings-live-")
        self.addCleanup(self._tmp.cleanup)
        self.tmpdir = self._tmp.name

    def test_live_directory_matches_fixture_byte_for_byte(self):
        live_dir = os.environ[_LIVE_DIR_ENV]
        live_out = os.path.join(self.tmpdir, "live_method_specs.json")
        fixture_out = os.path.join(self.tmpdir, "fixture_method_specs.json")
        live_result = _run_generator(
            ["--canonical-dir", live_dir, "--out", live_out]
        )
        self.assertEqual(live_result.returncode, 0, live_result.stderr)
        fixture_result = _run_generator(
            ["--canonical-dir", _FIXTURE_DIR, "--out", fixture_out]
        )
        self.assertEqual(fixture_result.returncode, 0, fixture_result.stderr)
        with open(live_out, encoding="utf-8") as fh:
            payload = json.load(fh)
        self.assertEqual(set(payload["methods"]), _FROZEN_IDS)
        self.assertEqual(len(payload["methods"]), 3)
        self.assertEqual(_sha256_file(live_out), _sha256_file(fixture_out))


if __name__ == "__main__":
    unittest.main()
