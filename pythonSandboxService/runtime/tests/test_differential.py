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
    def test_cagr_matches_cross_owner_recomputation(self):
        result = cagr(beginning_value=100.0, ending_value=160.0, periods=4)
        self.assertTrue(
            math.isclose(result.value, KIMI_CAGR, rel_tol=_REL_TOL, abs_tol=_ABS_TOL),
            f"cagr {result.value!r} != cross-owner draft {KIMI_CAGR!r}",
        )
        # third path: direct exponentiation
        independent = (160.0 / 100.0) ** (1.0 / 4) - 1.0
        self.assertTrue(math.isclose(result.value, independent, rel_tol=_REL_TOL))
        # The report-level typo in msg 9e67f8e6 ("0.12468261…") was prose only;
        # the library value must be exactly the cross-owner recomputation.
        self.assertEqual(result.value, KIMI_CAGR)


class TestVolatilityDifferential(unittest.TestCase):
    def test_full_sample_matches_cross_owner_recomputation(self):
        result = annualized_volatility(KIMI_VOL_RETURNS, periods_per_year=252)
        self.assertTrue(
            math.isclose(result.value, KIMI_VOL_FULL, rel_tol=_REL_TOL, abs_tol=_ABS_TOL),
            f"vol full {result.value!r} != draft {KIMI_VOL_FULL!r}",
        )

    def test_window3_matches_cross_owner_recomputation(self):
        result = annualized_volatility(
            KIMI_VOL_RETURNS, periods_per_year=252, window=3
        )
        self.assertTrue(
            math.isclose(result.value, KIMI_VOL_WINDOW3, rel_tol=_REL_TOL, abs_tol=_ABS_TOL),
            f"vol window=3 {result.value!r} != draft {KIMI_VOL_WINDOW3!r}",
        )

    def test_stdev_path_matches_statistics_module(self):
        stdev = statistics.stdev(KIMI_VOL_RETURNS)
        self.assertEqual(stdev, KIMI_VOL_STDEV)
        result = annualized_volatility(KIMI_VOL_RETURNS, periods_per_year=252)
        self.assertTrue(
            math.isclose(result.value, stdev * math.sqrt(252), rel_tol=_REL_TOL)
        )


class TestSharpeDifferential(unittest.TestCase):
    def test_rf0_matches_cross_owner_recomputation(self):
        result = sharpe(KIMI_SHARPE_RETURNS, periods_per_year=252)
        self.assertTrue(
            math.isclose(result.value, KIMI_SHARPE_RF0, rel_tol=_REL_TOL, abs_tol=_ABS_TOL),
            f"sharpe rf=0 {result.value!r} != draft {KIMI_SHARPE_RF0!r}",
        )

    def test_rf002_matches_cross_owner_recomputation_within_1ulp(self):
        result = sharpe(
            KIMI_SHARPE_RETURNS,
            risk_free_rate=0.02,
            risk_free_rate_convention="annual",
            periods_per_year=252,
        )
        # Cross-owner draft equals the statistics-module oracle bit-for-bit;
        # the library's sum-of-squares stdev path lands 1 ULP away (~8.5e-17
        # relative). isclose(rel_tol=1e-12) absorbs this expected difference.
        excess = [r - 0.02 / 252 for r in KIMI_SHARPE_RETURNS]
        oracle = statistics.fmean(excess) / statistics.stdev(excess) * math.sqrt(252)
        self.assertEqual(oracle, KIMI_SHARPE_RF002)
        self.assertTrue(
            math.isclose(result.value, KIMI_SHARPE_RF002, rel_tol=_REL_TOL, abs_tol=_ABS_TOL),
            f"sharpe rf=0.02 {result.value!r} != draft {KIMI_SHARPE_RF002!r}",
        )
        self.assertLess(
            abs(result.value - KIMI_SHARPE_RF002) / KIMI_SHARPE_RF002, 1e-15
        )


if __name__ == "__main__":
    unittest.main()
