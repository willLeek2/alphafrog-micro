# === work-package-B (ccqwen) ===
"""Golden-value tests for alphafrog_finance (Spec §16.2: unittest only, no pytest).

Run from pythonSandboxService/:

    python3 -m unittest discover -s runtime/tests -p 'test_*.py' -v

Expected values live in fixtures/metrics-golden-v1.json and were produced by
gen_metrics_golden_v1.py using an implementation path independent of the
library (stdlib ``statistics`` module), so these tests compare the library
against an external oracle rather than against itself.
"""
import dataclasses
import json
import math
import os
import statistics
import sys
import unittest

_SRC = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "src")
if _SRC not in sys.path:
    sys.path.insert(0, _SRC)
_TESTS = os.path.dirname(os.path.abspath(__file__))
if _TESTS not in sys.path:
    sys.path.insert(0, _TESTS)

# Registry swap: metric calls resolve identity through the generated bindings,
# so materialize the build products (real generator) before any metric runs.
from bindings_build_setup import ensure_generated_bindings  # noqa: E402

ensure_generated_bindings()

from alphafrog_finance import cagr, annualized_volatility, sharpe, FinanceMetricResult

_FIXTURE_PATH = os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "fixtures", "metrics-golden-v1.json"
)
with open(_FIXTURE_PATH, encoding="utf-8") as _fh:
    _FIXTURE = json.load(_fh)

_CASES = _FIXTURE["cases"]
_BY_NAME = {case["name"]: case for case in _CASES}

_FUNCTIONS = {
    "cagr": cagr,
    "annualized_volatility": annualized_volatility,
    "sharpe": sharpe,
}

_REL_TOL = 1e-12
_ABS_TOL = 1e-15

_EXPECTED_CASE_NAMES = [
    "cagr-spec-example",
    "cagr-single-period",
    "cagr-int-inputs",
    "vol-full-sample",
    "vol-trailing-window",
    "sharpe-arithmetic-rf0",
    "sharpe-arithmetic-annual-rf",
    "sharpe-arithmetic-period-rf",
    "sharpe-ddof0",
    "sharpe-daily-ppy252",
]


def _run_case(case):
    return _FUNCTIONS[case["function"]](**case["args"])


class TestGoldenValues(unittest.TestCase):
    pass
class TestSharpeGeometricFlexibility(unittest.TestCase):
    """return_convention='geometric' is library flexibility, NOT the v1.0.0
    canonical path. Kimi ruling (msg 65af9acf, frozen at CONTRACT_BASE_SHA
    7c695371): the golden fixture pins the arithmetic path only, so this case
    is a plain unit test with an inline statistics-module oracle instead of a
    fixture golden entry.
    """

class TestVolatilityWindow(unittest.TestCase):
    pass
class TestResultModel(unittest.TestCase):
    pass
class TestChecks(unittest.TestCase):
    pass
class TestCanonicalParametersEcho(unittest.TestCase):
    """ITEM 1 (codex must-fix 0c147646): the canonical required parameter
    ``returns`` (contract §3.5 parameter table; the A-side narrative template
    references {returns}) must be part of ``parameters`` so the projector and
    any consumer can reproduce the exact computed value from parameters alone.
    """

    _RETURNS6 = [0.01, -0.02, 0.015, 0.005, -0.01, 0.02]

class TestMethodIdentityFactory(unittest.TestCase):
    """ITEM 4 (codex must-fix 0c147646; registry swap, codex 0c147646/97ea103a):
    method identity flows from the A-canonical GENERATED bindings
    (``alphafrog_finance.bindings``); no public kwarg can override the identity
    a public metric function produces, and the interim hard-coded registry is
    gone (hand-maintained identity is forbidden, Spec §6).
    """

class TestValueErrors(unittest.TestCase):
    pass
if __name__ == "__main__":
    unittest.main()
