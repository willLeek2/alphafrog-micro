# === work-package-B (ccqwen) ===
"""Tests for runtime/scripts/generate_method_bindings.py and the packaging
metadata (Spec §6, codex f1ed6ea9). The generator is the only sanctioned
path from work package A's canonical generated JSON into
alphafrog_finance/_generated/method_specs.json; hand-copied method triples
are forbidden. The committed fixtures under tests/fixtures/
a-canonical-method-specs/ are VERBATIM copies of A's output and are never
mutated — every failure-mode test operates on a throwaway copy.

Run from pythonSandboxService/:

    python3 -m unittest discover -s runtime/tests -p 'test_*.py' -v
"""
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
_FIXTURE_DIR = os.path.join(_TESTS_DIR, "fixtures", "a-canonical-method-specs")
_CATALOG_NAME = "resolver-catalog.json"
_PYPROJECT = os.path.join(_RUNTIME_DIR, "pyproject.toml")

# Spec §6 frozen identities (>=3 gate).
_FROZEN_IDS = frozenset(
    {
        "finance.growth.cagr",
        "finance.risk.annualized_volatility",
        "finance.risk.sharpe_ratio",
    }
)


def _run_generator(args):
    return subprocess.run(
        [sys.executable, _GENERATOR] + list(args),
        capture_output=True,
        text=True,
    )


def _expected_methods_from_fixtures():
    """methodId -> loader entry, derived from the committed fixture specs."""
    methods = {}
    for name in sorted(os.listdir(_FIXTURE_DIR)):
        if not name.endswith(".json") or name == _CATALOG_NAME:
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
        # verbatim version/specDigest values from A's canonical fixtures.
        self.assertIsInstance(payload, dict)
        self.assertEqual(payload, {"methods": expected})
        self.assertEqual(set(payload["methods"]), _FROZEN_IDS)
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
        # Drop the sharpe_ratio spec AND its catalog entry so catalog and
        # specs stay mutually consistent — the failure must come from the
        # Spec §6 frozen-identity (>=3) gate itself.
        def mutate(dst):
            os.remove(os.path.join(dst, "sharpe_ratio.json"))
            catalog_path = os.path.join(dst, _CATALOG_NAME)
            with open(catalog_path, encoding="utf-8") as fh:
                entries = json.load(fh)
            entries = [
                e for e in entries if e["methodId"] != "finance.risk.sharpe_ratio"
            ]
            with open(catalog_path, "w", encoding="utf-8") as fh:
                json.dump(entries, fh, ensure_ascii=False, indent=2)

        result = self._assert_fails_closed(self._mutated_copy(mutate))
        self.assertIn("frozen method ids missing", result.stderr)

    def test_missing_canonical_dir_fails(self):
        self._assert_fails_closed(os.path.join(self.tmpdir, "does-not-exist"))


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


if __name__ == "__main__":
    unittest.main()
