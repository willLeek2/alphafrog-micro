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
import importlib.util
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
        "finance.crossover.ah_premium",
        "finance.forecast.forward_pe_peg",
        "finance.growth.cagr",
        "finance.momentum.price_momentum",
        "finance.quality.dupont_roe",
        "finance.return.cb_daily_return",
        "finance.return.etf_adj_return",
        "finance.return.fund_accum_nav_return",
        "finance.risk.annualized_volatility",
        "finance.risk.drawdown_sortino_calmar",
        "finance.risk.sharpe_ratio",
        "finance.trend.moving_average",
        "finance.valuation.cb_premium",
        "finance.valuation.dividend_yield",
        "finance.valuation.free_float_turnover",
        "finance.valuation.index_pe_pb",
        "finance.valuation.price_to_book",
        "finance.valuation.rolling_pe",
    }
)

# Frozen Spec §6 digests, VERBATIM as delivered in A's final index.json /
# resolver-catalog.json / spec files (codex 97ea103a).
_FROZEN_SPEC_DIGESTS = {
    "finance.crossover.ah_premium": (
        "sha256:177a8c04110c31fc5c7a8e6f05f21cadfe9f56f4015cbbec2e245d15370611e3"
    ),
    "finance.forecast.forward_pe_peg": (
        "sha256:f91c13bbb90f741f347b73788123fe51f718f3d2dbd8a097dbe5fb1997968226"
    ),
    "finance.growth.cagr": (
        "sha256:cff05d88e83b787478edfd0252c414ded02b8236b9b1032126f5cd51c4d7b25e"
    ),
    "finance.momentum.price_momentum": (
        "sha256:7628e0d4df23b4f89002bf0900ea52b1e471b4a780c756bdb7a0101429f4a3e2"
    ),
    "finance.quality.dupont_roe": (
        "sha256:d73018ebeacf1bc1f465fce51b5005fb46f20009ae9440ae3b6c5252e9e30b09"
    ),
    "finance.return.cb_daily_return": (
        "sha256:2bb39c43b399586ac7957e94eb7c773d632d38e4a96bb5f31b3ff8ea63f723e7"
    ),
    "finance.return.etf_adj_return": (
        "sha256:3370e81c12c064cb00bf8953c7cad074136ca357d9d6697091113678b0bec8ab"
    ),
    "finance.return.fund_accum_nav_return": (
        "sha256:53b7613839e145715964ae3777e400f9748a367b862400a3809794b52f41bed9"
    ),
    "finance.risk.annualized_volatility": (
        "sha256:2843745f0c4903083430ef0b4eef6be253b09a4c014c28decbf5884466f0d668"
    ),
    "finance.risk.drawdown_sortino_calmar": (
        "sha256:178380bd64306f323409394ac706782516847c39ea438cb58d48527992bb8c3b"
    ),
    "finance.risk.sharpe_ratio": (
        "sha256:fccc1f0f9264dc90730f7a3b6a35abce2c6f2884c79a3e3b9ce0a7190058db90"
    ),
    "finance.trend.moving_average": (
        "sha256:57d985c96639eeb72a1206bce503d4a57bf1caa83922f261f42779007198e823"
    ),
    "finance.valuation.cb_premium": (
        "sha256:36597d40c478c978358e61325615a64290ac4a0ff36eeee96ac70577b78abf15"
    ),
    "finance.valuation.dividend_yield": (
        "sha256:8c003d379da4532358c366a5a74f7ccbef854dabcf7588cad87fd959ab5eb3db"
    ),
    "finance.valuation.free_float_turnover": (
        "sha256:a849e7ed36b10948dcfdbfffc671196fbc7b6cbfd60b8a76e72a030ef7a50564"
    ),
    "finance.valuation.index_pe_pb": (
        "sha256:cb3e6ae575342747b74b21468a52f6c5352ef6dec4a467a6c345454cb24503c6"
    ),
    "finance.valuation.price_to_book": (
        "sha256:924468f3ba30e8343d988d1e9b7cca6072b5df0be1bffafd3fbd46bfc0ccbf20"
    ),
    "finance.valuation.rolling_pe": (
        "sha256:8b1e5c4dc7a2e3f46895f4907ff63043a4e20e6ab0f3b89bd86dbdfa9a85bcb3"
    ),
}

# Generator output byte pin (build reproducibility): running the current
# generator over the committed fixture dir must produce a method_specs.json
# with EXACTLY this sha256. Any drift is a regression.
_FROZEN_METHOD_SPECS_SHA256 = (
    "9c9c5d1a83c126fd2e7fa9b5b53a93b12077f0701681ab6980079944a40e29b6"
)

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


def _load_module(path, name):
    """Load a generated .py module by path (it has no package context)."""
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _fixture_spec_payloads():
    """methodId -> full canonical spec payload, from the committed fixtures."""
    payloads = {}
    for name in sorted(os.listdir(_FIXTURE_DIR)):
        if not name.endswith(".json") or name in _RESERVED_NAMES:
            continue
        with open(os.path.join(_FIXTURE_DIR, name), encoding="utf-8") as fh:
            spec = json.load(fh)
        payloads[spec["methodId"]] = spec
    return payloads


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

    # --- index.json shape (codex 97ea103a) -------------------------------

    # --- entry <-> spec files ---------------------------------------------

    # --- entry <-> resolver-catalog.json -----------------------------------

class GenerateLibraryBindingGateTests(unittest.TestCase):
    """Spec §6 registry swap: every spec's libraryBinding is ALWAYS validated.

    Each mutation exits non-zero and writes NO output (all-or-nothing), with
    the three requested outputs asserted absent.
    """

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory(prefix="gen-lib-binding-gate-")
        self.addCleanup(self._tmp.cleanup)
        self.tmpdir = self._tmp.name
        self.out = os.path.join(self.tmpdir, "method_specs.json")
        self.doc = os.path.join(self.tmpdir, "docstrings.py")
        self.cs = os.path.join(self.tmpdir, "call_samples.py")

    def _mutated_copy(self, mutate):
        dst = os.path.join(self.tmpdir, "canonical")
        shutil.copytree(_FIXTURE_DIR, dst)
        mutate(dst)
        return dst

    @staticmethod
    def _mutate_binding(directory, name, mutate):
        path = os.path.join(directory, name)
        with open(path, encoding="utf-8") as fh:
            spec = json.load(fh)
        mutate(spec["libraryBinding"])
        with open(path, "w", encoding="utf-8") as fh:
            json.dump(spec, fh, ensure_ascii=False, indent=2)

    def _assert_fails_closed(self, canonical_dir, extra_args=()):
        result = _run_generator(
            [
                "--canonical-dir",
                canonical_dir,
                "--out",
                self.out,
                "--docstrings-out",
                self.doc,
                "--call-samples-out",
                self.cs,
            ]
            + list(extra_args)
        )
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn("error", result.stderr)
        for path in (self.out, self.doc, self.cs):
            self.assertFalse(
                os.path.exists(path),
                f"generator must not leave {os.path.basename(path)} behind",
            )
        return result

class GenerateDocstringsCallSamplesTests(unittest.TestCase):
    """Determinism and payload-correctness of the optional docstrings.py and
    call_samples.py outputs (Spec §6 registry swap)."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory(prefix="gen-doc-call-samples-")
        self.addCleanup(self._tmp.cleanup)
        self.tmpdir = self._tmp.name

    def _generate_all(self, suffix=""):
        out = os.path.join(self.tmpdir, f"method_specs{suffix}.json")
        doc = os.path.join(self.tmpdir, f"docstrings{suffix}.py")
        cs = os.path.join(self.tmpdir, f"call_samples{suffix}.py")
        result = _run_generator(
            [
                "--canonical-dir",
                _FIXTURE_DIR,
                "--out",
                out,
                "--docstrings-out",
                doc,
                "--call-samples-out",
                cs,
            ]
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        return out, doc, cs

class TestPackagingMetadata(unittest.TestCase):
    """pyproject.toml exists and pins the real distribution identity."""

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

if __name__ == "__main__":
    unittest.main()
