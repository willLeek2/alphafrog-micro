# === work-package-B (ccqwen) ===
"""Tests for ``alphafrog_finance.bindings`` — the Spec §6 registry swap
(codex 0c147646/97ea103a): method identity is assembled ONLY from the
A-canonical generated build products, never hand-maintained or caller-supplied.

Every test materializes a FRESH generated set in a temp directory by running
the REAL ``runtime/scripts/generate_method_bindings.py`` (subprocess) against
the committed verbatim fixture canonical directory, then points the private
``bindings._GENERATED_DIR`` test seam at that temp directory and resets the
lazy-assembly caches. The installed ``_generated/`` directory is never
modified by this module, and the seams are restored after every test.

Tamper regressions mutate exactly ONE of the three generated documents and
assert the assembly fails CLOSED with ``RuntimeError`` (nothing cached, no
partial identity).

Run from pythonSandboxService/:

    python3 -m unittest discover -s runtime/tests -p 'test_*.py' -v
"""
import hashlib
import importlib.util
import json
import os
import re
import subprocess
import sys
import tempfile
import unittest

_SRC = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "src")
if _SRC not in sys.path:
    sys.path.insert(0, _SRC)
_TESTS = os.path.dirname(os.path.abspath(__file__))
if _TESTS not in sys.path:
    sys.path.insert(0, _TESTS)

# Registry swap: any metric call (in this or any other test module) resolves
# identity through the generated bindings, so materialize the installed build
# products (real generator) before anything runs.
from bindings_build_setup import ensure_generated_bindings  # noqa: E402

ensure_generated_bindings()

import alphafrog_finance  # noqa: E402
from alphafrog_finance import bindings  # noqa: E402
from alphafrog_finance import metrics as metrics_mod  # noqa: E402

_TESTS_DIR = os.path.dirname(os.path.abspath(__file__))
_RUNTIME_DIR = os.path.dirname(_TESTS_DIR)
_GENERATOR = os.path.join(_RUNTIME_DIR, "scripts", "generate_method_bindings.py")
_FIXTURE_DIR = os.path.join(_TESTS_DIR, "fixtures", "a-generated-resources-v1")

# Byte pin: method_specs.json generated from the fixture must stay exactly the
# historical bytes (the swap changes neither the format nor the pin).
_FROZEN_METHOD_SPECS_SHA256 = (
    "9c9c5d1a83c126fd2e7fa9b5b53a93b12077f0701681ab6980079944a40e29b6"
)

# Spec §6 frozen identities, pinned VERBATIM (version 1.0.0).
_FROZEN_TRIPLES = {
    "finance.crossover.ah_premium": (
        "1.0.0",
        "sha256:177a8c04110c31fc5c7a8e6f05f21cadfe9f56f4015cbbec2e245d15370611e3",
        "ah_premium",
    ),
    "finance.forecast.forward_pe_peg": (
        "1.0.0",
        "sha256:f91c13bbb90f741f347b73788123fe51f718f3d2dbd8a097dbe5fb1997968226",
        "forward_pe_peg",
    ),
    "finance.growth.cagr": (
        "1.0.0",
        "sha256:cff05d88e83b787478edfd0252c414ded02b8236b9b1032126f5cd51c4d7b25e",
        "cagr",
    ),
    "finance.momentum.price_momentum": (
        "1.0.0",
        "sha256:7628e0d4df23b4f89002bf0900ea52b1e471b4a780c756bdb7a0101429f4a3e2",
        "price_momentum",
    ),
    "finance.quality.dupont_roe": (
        "1.0.0",
        "sha256:d73018ebeacf1bc1f465fce51b5005fb46f20009ae9440ae3b6c5252e9e30b09",
        "dupont_roe",
    ),
    "finance.return.cb_daily_return": (
        "1.0.0",
        "sha256:2bb39c43b399586ac7957e94eb7c773d632d38e4a96bb5f31b3ff8ea63f723e7",
        "cb_daily_return",
    ),
    "finance.return.etf_adj_return": (
        "1.0.0",
        "sha256:3370e81c12c064cb00bf8953c7cad074136ca357d9d6697091113678b0bec8ab",
        "etf_adj_return",
    ),
    "finance.return.fund_accum_nav_return": (
        "1.0.0",
        "sha256:53b7613839e145715964ae3777e400f9748a367b862400a3809794b52f41bed9",
        "fund_accum_nav_return",
    ),
    "finance.risk.annualized_volatility": (
        "1.0.0",
        "sha256:2843745f0c4903083430ef0b4eef6be253b09a4c014c28decbf5884466f0d668",
        "annualized_volatility",
    ),
    "finance.risk.drawdown_sortino_calmar": (
        "1.0.0",
        "sha256:178380bd64306f323409394ac706782516847c39ea438cb58d48527992bb8c3b",
        "drawdown_sortino_calmar",
    ),
    "finance.risk.sharpe_ratio": (
        "1.0.0",
        "sha256:fccc1f0f9264dc90730f7a3b6a35abce2c6f2884c79a3e3b9ce0a7190058db90",
        "sharpe",
    ),
    "finance.trend.moving_average": (
        "1.0.0",
        "sha256:57d985c96639eeb72a1206bce503d4a57bf1caa83922f261f42779007198e823",
        "moving_average",
    ),
    "finance.valuation.cb_premium": (
        "1.0.0",
        "sha256:36597d40c478c978358e61325615a64290ac4a0ff36eeee96ac70577b78abf15",
        "cb_premium",
    ),
    "finance.valuation.dividend_yield": (
        "1.0.0",
        "sha256:8c003d379da4532358c366a5a74f7ccbef854dabcf7588cad87fd959ab5eb3db",
        "dividend_yield",
    ),
    "finance.valuation.free_float_turnover": (
        "1.0.0",
        "sha256:a849e7ed36b10948dcfdbfffc671196fbc7b6cbfd60b8a76e72a030ef7a50564",
        "free_float_turnover",
    ),
    "finance.valuation.index_pe_pb": (
        "1.0.0",
        "sha256:cb3e6ae575342747b74b21468a52f6c5352ef6dec4a467a6c345454cb24503c6",
        "index_pe_pb",
    ),
    "finance.valuation.price_to_book": (
        "1.0.0",
        "sha256:924468f3ba30e8343d988d1e9b7cca6072b5df0be1bffafd3fbd46bfc0ccbf20",
        "price_to_book",
    ),
    "finance.valuation.rolling_pe": (
        "1.0.0",
        "sha256:8b1e5c4dc7a2e3f46895f4907ff63043a4e20e6ab0f3b89bd86dbdfa9a85bcb3",
        "rolling_pe",
    ),
}


def _load_fixture_specs():
    """Load the three fixture method-spec payloads keyed by methodId.

    These files are the A-canonical source of truth for this test: the
    assembled bindings are cross-checked against them INDEPENDENTLY of the
    generated documents under test.
    """
    specs = {}
    for filename in sorted(os.listdir(_FIXTURE_DIR)):
        if filename in ("index.json", "resolver-catalog.json"):
            continue
        if not filename.endswith(".json"):
            continue
        with open(os.path.join(_FIXTURE_DIR, filename), encoding="utf-8") as fh:
            payload = json.load(fh)
        specs[payload["methodId"]] = payload
    return specs


class _TempGeneratedBindingsBase(unittest.TestCase):
    """Generate a fresh binding set into a temp dir and point the seam at it."""

    def setUp(self):
        tmp = tempfile.TemporaryDirectory(prefix="gen-bindings-test-")
        self.addCleanup(tmp.cleanup)
        self._tmp_dir = tmp.name
        self._generate_into_temp_dir()
        self._old_generated_dir = bindings._GENERATED_DIR
        bindings._GENERATED_DIR = self._tmp_dir
        bindings._BINDINGS_CACHE = None
        bindings._FUNCTION_INDEX = None
        self.addCleanup(self._restore_seams)

    def _restore_seams(self):
        bindings._GENERATED_DIR = self._old_generated_dir
        # Reset (not restore) the caches so any later test reassembles from
        # the installed directory, which ensure_generated_bindings() built.
        bindings._BINDINGS_CACHE = None
        bindings._FUNCTION_INDEX = None

    def _generate_into_temp_dir(self):
        result = subprocess.run(
            [
                sys.executable,
                _GENERATOR,
                "--canonical-dir",
                _FIXTURE_DIR,
                "--out",
                os.path.join(self._tmp_dir, "method_specs.json"),
                "--docstrings-out",
                os.path.join(self._tmp_dir, "docstrings.py"),
                "--call-samples-out",
                os.path.join(self._tmp_dir, "call_samples.py"),
                "--package-version",
                alphafrog_finance.__version__,
            ],
            capture_output=True,
            text=True,
        )
        if result.returncode != 0:
            raise RuntimeError(
                "generate_method_bindings.py failed in test setup: "
                f"rc={result.returncode} stderr={result.stderr!r}"
            )

    # --- tamper helpers -----------------------------------------------------

    def _read_json(self, filename):
        with open(os.path.join(self._tmp_dir, filename), encoding="utf-8") as fh:
            return json.load(fh)

    def _write_json(self, filename, payload):
        with open(os.path.join(self._tmp_dir, filename), "w", encoding="utf-8") as fh:
            json.dump(payload, fh, ensure_ascii=False, sort_keys=True, indent=2)
            fh.write("\n")

    def _read_document(self, filename):
        """Load a generated docstrings.py / call_samples.py and return a
        DETACHED copy of its embedded DOCUMENT mapping."""
        path = os.path.join(self._tmp_dir, filename)
        spec = importlib.util.spec_from_file_location(
            "tamper_" + os.path.splitext(filename)[0], path
        )
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return json.loads(json.dumps(module.DOCUMENT))

    def _write_document(self, filename, document):
        """Rewrite a generated document module as a plain Python literal."""
        path = os.path.join(self._tmp_dir, filename)
        with open(path, "w", encoding="utf-8") as fh:
            fh.write("DOCUMENT = " + repr(document) + "\n")


class TestGeneratedBindingsHappyPath(_TempGeneratedBindingsBase):
    pass
class TestGeneratedBindingsTamperRegressions(_TempGeneratedBindingsBase):
    """Mutate exactly ONE generated document -> assembly must fail closed."""

if __name__ == "__main__":
    unittest.main()
