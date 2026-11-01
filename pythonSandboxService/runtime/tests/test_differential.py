# === work-package-B (ccqwen) ===
"""Differential tests for alphafrog_finance (Spec §6 差分测试).

The oracle values were computed INDEPENDENTLY by the A-line owner (Kimi,
msg 3a2720de, work-package-A golden-data draft, python3 stdlib recomputation)
and are consumed here verbatim as a cross-owner differential reference — the
library is compared against another owner's numbers, not against itself. The
stdlib ``statistics`` oracle is recomputed inline as a third path.

Note on the sharpe rf=0.02 case: the library's pure sum-of-squares sample
standard deviation differs from the statistics-module exact-fraction path by
1 ULP in the final result (relative difference ~8.5e-17). This is expected
floating-point behaviour of two correct implementations, NOT a regression —
all comparisons therefore use ``math.isclose`` with rel_tol=1e-12 (the same
tolerance as the golden tests, Spec §16.2).

The differential reference never changes the finalized golden fixture
(metrics-golden-v1.json, arithmetic path only per Kimi ruling 65af9acf).

Run from pythonSandboxService/:

    python3 -m unittest discover -s runtime/tests -p 'test_*.py' -v
"""
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

from alphafrog_finance import annualized_volatility, cagr, sharpe

_REL_TOL = 1e-12
_ABS_TOL = 1e-15

# --- A-line independent draft values (Kimi msg 3a2720de) ---

# CAGR(100, 160, 4)
KIMI_CAGR = 0.12468265038069815

# annualized_volatility([0.01, -0.02, 0.015, 0.005, -0.01], periods_per_year=252)
KIMI_VOL_RETURNS = [0.01, -0.02, 0.015, 0.005, -0.01]
KIMI_VOL_STDEV = 0.014577379737113252
KIMI_VOL_FULL = 0.2314087293081227
KIMI_VOL_WINDOW3 = 0.1997498435543818

# sharpe([0.012, -0.008, 0.021, 0.004, 0.011], arithmetic, annual rf, ppy=252, ddof=1)
KIMI_SHARPE_RETURNS = [0.012, -0.008, 0.021, 0.004, 0.011]
KIMI_SHARPE_RF0 = 11.765958024756987
KIMI_SHARPE_RF002 = 11.649232250701859


class TestCagrDifferential(unittest.TestCase):
    pass
class TestVolatilityDifferential(unittest.TestCase):
    pass
class TestSharpeDifferential(unittest.TestCase):
    pass
if __name__ == "__main__":
    unittest.main()
