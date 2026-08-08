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
    def test_fixture_shape(self):
        self.assertEqual(_FIXTURE["meta"]["generatedBy"], "gen_metrics_golden_v1.py")
        self.assertEqual([case["name"] for case in _CASES], _EXPECTED_CASE_NAMES)

    def test_all_golden_cases(self):
        for case in _CASES:
            with self.subTest(name=case["name"]):
                expected = case["expected"]
                result = _run_case(case)
                self.assertIsInstance(result, FinanceMetricResult)
                self.assertTrue(
                    math.isclose(
                        result.value,
                        expected["value"],
                        rel_tol=_REL_TOL,
                        abs_tol=_ABS_TOL,
                    ),
                    f"{case['name']}: value {result.value!r} != expected "
                    f"{expected['value']!r}",
                )
                self.assertEqual(result.method_id, expected["methodId"])
                self.assertEqual(result.unit, expected["unit"])
                self.assertEqual(dict(result.parameters), expected["parameters"])
                self.assertEqual(result.warnings, ())

    def test_period_rf_golden_equals_annual_rf_golden(self):
        # 0.06 / 12 rounds to the same double as 0.005, so the two
        # parametrizations must produce equal golden values.
        annual = _BY_NAME["sharpe-arithmetic-annual-rf"]["expected"]["value"]
        period = _BY_NAME["sharpe-arithmetic-period-rf"]["expected"]["value"]
        self.assertTrue(math.isclose(annual, period, rel_tol=_REL_TOL))


class TestSharpeGeometricFlexibility(unittest.TestCase):
    """return_convention='geometric' is library flexibility, NOT the v1.0.0
    canonical path. Kimi ruling (msg 65af9acf, frozen at CONTRACT_BASE_SHA
    7c695371): the golden fixture pins the arithmetic path only, so this case
    is a plain unit test with an inline statistics-module oracle instead of a
    fixture golden entry.
    """

    def test_geometric_value_matches_independent_oracle(self):
        returns = _BY_NAME["sharpe-arithmetic-annual-rf"]["args"]["returns"]
        rf_period = 0.06 / 12
        excess = [(1.0 + r) / (1.0 + rf_period) - 1.0 for r in returns]
        expected = statistics.fmean(excess) / statistics.stdev(excess) * math.sqrt(12)
        result = sharpe(
            returns,
            risk_free_rate=0.06,
            risk_free_rate_convention="annual",
            ddof=1,
            periods_per_year=12,
            return_convention="geometric",
        )
        self.assertTrue(
            math.isclose(result.value, expected, rel_tol=_REL_TOL, abs_tol=_ABS_TOL),
            f"geometric flexibility: {result.value!r} != oracle {expected!r}",
        )
        self.assertEqual(result.method_id, "finance.risk.sharpe_ratio")
        self.assertEqual(result.unit, "ratio_per_annum")
        self.assertEqual(result.parameters["returnConvention"], "geometric")


class TestVolatilityWindow(unittest.TestCase):
    def test_window_equal_to_sample_length_matches_full_sample(self):
        returns = _BY_NAME["vol-full-sample"]["args"]["returns"]
        expected = _BY_NAME["vol-full-sample"]["expected"]["value"]
        result = annualized_volatility(returns, periods_per_year=12, window=len(returns))
        self.assertTrue(
            math.isclose(result.value, expected, rel_tol=_REL_TOL, abs_tol=_ABS_TOL),
            f"window=len(returns): {result.value!r} != full sample {expected!r}",
        )


class TestResultModel(unittest.TestCase):
    def test_result_is_immutable(self):
        result = cagr(beginning_value=100.0, ending_value=160.0, periods=4)
        with self.assertRaises(dataclasses.FrozenInstanceError):
            result.value = 0.0

    def test_warnings_default_to_empty_tuple(self):
        for case in _CASES:
            with self.subTest(name=case["name"]):
                self.assertEqual(_run_case(case).warnings, ())


class TestChecks(unittest.TestCase):
    def test_cagr_checks_all_true(self):
        result = cagr(beginning_value=100.0, ending_value=160.0, periods=4)
        for key in ("finite", "positiveValues", "periodsValid"):
            self.assertIs(result.checks[key], True)

    def test_volatility_checks_all_true(self):
        for name in ("vol-full-sample", "vol-trailing-window"):
            with self.subTest(name=name):
                result = _run_case(_BY_NAME[name])
                for key in ("shape", "finite", "windowValid"):
                    self.assertIs(result.checks[key], True)

    def test_sharpe_checks_all_true(self):
        keys = ("shape", "finite", "ddofValid", "sampleExceedsDdof", "periodsPerYearValid")
        for case in _CASES:
            if case["function"] != "sharpe":
                continue
            with self.subTest(name=case["name"]):
                result = _run_case(case)
                for key in keys:
                    self.assertIs(result.checks[key], True)


class TestValueErrors(unittest.TestCase):
    def test_cagr_value_errors(self):
        base = {"beginning_value": 100.0, "ending_value": 160.0, "periods": 4}
        cases = [
            ({"beginning_value": 0}, "beginning_value"),
            ({"beginning_value": -5}, "beginning_value"),
            ({"ending_value": 0}, "ending_value"),
            ({"periods": 0}, "periods"),
            ({"periods": 1.5}, "periods"),
            ({"periods": True}, "periods"),
            ({"beginning_value": "100"}, "beginning_value"),
            ({"beginning_value": float("nan")}, "beginning_value"),
        ]
        for overrides, param in cases:
            with self.subTest(overrides=overrides, param=param):
                kwargs = dict(base)
                kwargs.update(overrides)
                with self.assertRaisesRegex(ValueError, param):
                    cagr(**kwargs)

    def test_annualized_volatility_value_errors(self):
        returns6 = _BY_NAME["vol-full-sample"]["args"]["returns"]
        calls = [
            (lambda: annualized_volatility([0.01], periods_per_year=12), "returns"),
            (
                lambda: annualized_volatility([0.01, float("inf")], periods_per_year=12),
                "returns",
            ),
            (
                lambda: annualized_volatility([0.01, True], periods_per_year=12),
                "returns",
            ),
            (lambda: annualized_volatility("ab", periods_per_year=12), "returns"),
            (
                lambda: annualized_volatility(returns6, periods_per_year=12, window=1),
                "window",
            ),
            (
                lambda: annualized_volatility(returns6, periods_per_year=12, window=7),
                "window",
            ),
            (
                lambda: annualized_volatility(returns6, periods_per_year=12, window=2.5),
                "window",
            ),
            (lambda: annualized_volatility(returns6, periods_per_year=0), "periods_per_year"),
        ]
        for i, (call, param) in enumerate(calls):
            with self.subTest(case=i, param=param):
                with self.assertRaisesRegex(ValueError, param):
                    call()

    def test_sharpe_value_errors(self):
        returns6 = _BY_NAME["sharpe-arithmetic-rf0"]["args"]["returns"]
        calls = [
            (lambda: sharpe([0.01, 0.02], ddof=2), "ddof"),
            (lambda: sharpe(returns6, ddof=-1), "ddof"),
            (
                lambda: sharpe(returns6, risk_free_rate_convention="monthly"),
                "risk_free_rate_convention",
            ),
            (lambda: sharpe(returns6, return_convention="log"), "return_convention"),
            (
                lambda: sharpe([0.01, -1.5], return_convention="geometric"),
                "returns",
            ),
            (
                lambda: sharpe(
                    [0.01, 0.02],
                    risk_free_rate=-12.0,
                    periods_per_year=12,
                    return_convention="geometric",
                ),
                "risk_free_rate",
            ),
            (
                lambda: sharpe([0.01, 0.01, 0.01], risk_free_rate=0.0),
                "returns",
            ),
        ]
        for i, (call, param) in enumerate(calls):
            with self.subTest(case=i, param=param):
                with self.assertRaisesRegex(ValueError, param):
                    call()


if __name__ == "__main__":
    unittest.main()
