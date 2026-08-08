# === work-package-B (ccqwen) ===
"""Metric functions for alphafrog_finance.

Spec basis: §6 (metrics.py 三个指标函数：纯函数、显式参数、不取数、不做金融正确性判断)
and §5.2 YAML/canonical shapes. YAML camelCase parameter names map 1:1 to
snake_case kwargs (word-wise lowercase); the ``parameters`` echo in each result
uses the camelCase YAML names as open, method-specific keys.

Method IDs are proposed strings; the final values are owned by work package A's
YAML/canonical JSON (see models.py note). Constraint violations raise
``ValueError`` naming the offending parameter; nothing is printed and no marker
line is produced on failure.
"""
from __future__ import annotations

import math
from typing import Any, Dict, List, Sequence

from .checks import check_cagr, check_sharpe, check_volatility
from .models import FinanceMetricResult

# Proposed method identities (final strings owned by package A YAML).
_METHOD_ID_CAGR = "finance.growth.cagr"
_METHOD_ID_VOLATILITY = "finance.risk.annualized_volatility"
_METHOD_ID_SHARPE = "finance.risk.sharpe_ratio"

_UNIT_RATIO = "ratio"
_UNIT_RATIO_PER_ANNUM = "ratio_per_annum"

_RF_CONVENTIONS = ("annual", "period")
_RETURN_CONVENTIONS = ("arithmetic", "geometric")


def _validate_number(name: str, value: Any) -> float:
    """Return value as a finite float, else raise ValueError naming `name`."""
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValueError(f"{name} must be a number, got {type(value).__name__}")
    v = float(value)
    if not math.isfinite(v):
        raise ValueError(f"{name} must be finite, got {value!r}")
    return v


def _validate_int(name: str, value: Any, minimum: int) -> int:
    """Return value as an int >= minimum (bool rejected), else ValueError."""
    if isinstance(value, bool) or not isinstance(value, int):
        raise ValueError(f"{name} must be an integer, got {type(value).__name__}")
    if value < minimum:
        raise ValueError(f"{name} must be >= {minimum}, got {value}")
    return value


def _validate_choice(name: str, value: Any, allowed: Sequence[str]) -> str:
    if not isinstance(value, str) or value not in allowed:
        raise ValueError(f"{name} must be one of {'|'.join(allowed)}, got {value!r}")
    return value


def _validate_returns(returns: Any) -> List[float]:
    """Return returns as a list of >= 2 finite floats, else ValueError."""
    if isinstance(returns, (str, bytes)):
        raise ValueError("returns must be a sequence of numbers, not a string")
    try:
        items = list(returns)
    except TypeError:
        raise ValueError("returns must be a sequence of numbers") from None
    if len(items) < 2:
        raise ValueError(f"returns must contain at least 2 observations, got {len(items)}")
    values: List[float] = []
    for i, item in enumerate(items):
        if isinstance(item, bool) or not isinstance(item, (int, float)):
            raise ValueError(f"returns[{i}] must be a number, got {type(item).__name__}")
        v = float(item)
        if not math.isfinite(v):
            raise ValueError(f"returns[{i}] must be finite, got {item!r}")
        values.append(v)
    return values


def _sample_std(values: Sequence[float], ddof: int) -> float:
    """Sample standard deviation with the given ddof; caller ensures n > ddof."""
    n = len(values)
    mean = sum(values) / n
    ss = sum((v - mean) ** 2 for v in values)
    return math.sqrt(ss / (n - ddof))


def cagr(*, beginning_value: float, ending_value: float, periods: int) -> FinanceMetricResult:
    """Compound annual growth rate.

    Formula: ``(ending_value / beginning_value) ** (1 / periods) - 1``.

    Args:
        beginning_value: positive starting value (> 0).
        ending_value: positive ending value (> 0).
        periods: integer number of periods, >= 1.

    Returns:
        FinanceMetricResult with unit ``ratio``.

    Raises:
        ValueError: on any constraint violation (message names the parameter).
    """
    beginning_value = _validate_number("beginning_value", beginning_value)
    ending_value = _validate_number("ending_value", ending_value)
    periods = _validate_int("periods", periods, 1)
    if beginning_value <= 0:
        raise ValueError(f"beginning_value must be > 0, got {beginning_value}")
    if ending_value <= 0:
        raise ValueError(f"ending_value must be > 0, got {ending_value}")
    value = (ending_value / beginning_value) ** (1.0 / periods) - 1.0
    return FinanceMetricResult(
        method_id=_METHOD_ID_CAGR,
        value=value,
        unit=_UNIT_RATIO,
        parameters={
            "beginningValue": beginning_value,
            "endingValue": ending_value,
            "periods": periods,
        },
        checks=check_cagr(beginning_value, ending_value, periods),
    )


def annualized_volatility(
    returns: Sequence[float],
    *,
    periods_per_year: int,
    window: int | None = None,
) -> FinanceMetricResult:
    """Annualized volatility of a periodic return series.

    Semantics: sample standard deviation (ddof=1, fixed) of the series — or of
    the trailing ``window`` observations when ``window`` is given — multiplied
    by ``sqrt(periods_per_year)``. The returns series itself is an input, not a
    ``parameters`` key; only ``periodsPerYear`` (and ``window`` when used) are
    echoed.

    Args:
        returns: at least 2 finite periodic returns.
        periods_per_year: integer >= 1.
        window: optional integer >= 2, <= len(returns); None uses the whole sample.

    Returns:
        FinanceMetricResult with unit ``ratio_per_annum``.

    Raises:
        ValueError: on any constraint violation (message names the parameter).
    """
    values = _validate_returns(returns)
    periods_per_year = _validate_int("periods_per_year", periods_per_year, 1)
    if window is not None:
        window = _validate_int("window", window, 2)
        if window > len(values):
            raise ValueError(
                f"window must not exceed len(returns)={len(values)}, got {window}"
            )
        series = values[-window:]
    else:
        series = values
    value = _sample_std(series, 1) * math.sqrt(periods_per_year)
    parameters: Dict[str, Any] = {"periodsPerYear": periods_per_year}
    if window is not None:
        parameters["window"] = window
    return FinanceMetricResult(
        method_id=_METHOD_ID_VOLATILITY,
        value=value,
        unit=_UNIT_RATIO_PER_ANNUM,
        parameters=parameters,
        checks=check_volatility(values, window),
    )


def sharpe(
    returns: Sequence[float],
    *,
    risk_free_rate: float = 0.0,
    risk_free_rate_convention: str = "annual",
    ddof: int = 1,
    periods_per_year: int = 252,
    return_convention: str = "arithmetic",
) -> FinanceMetricResult:
    """Annualized Sharpe ratio of a periodic return series.

    Semantics:
        - ``risk_free_rate_convention="annual"``: the rate is converted per
          period as ``risk_free_rate / periods_per_year``; ``"period"`` uses it
          directly.
        - ``return_convention="arithmetic"``: excess_i = r_i - rf_period.
        - ``return_convention="geometric"``: excess_i =
          (1 + r_i) / (1 + rf_period) - 1 (requires r_i > -1 and
          rf_period > -1; this interpretation may be adjusted before contract
          freeze per open question Q2).
        - ratio = mean(excess) / std(excess, ddof) * sqrt(periods_per_year).

    Args:
        returns: at least 2 finite periodic returns; len(returns) > ddof.
        risk_free_rate: finite float, default 0.0.
        risk_free_rate_convention: ``annual`` (default) or ``period``.
        ddof: degrees of freedom for the excess-return std dev, >= 0, default 1.
        periods_per_year: integer >= 1, default 252.
        return_convention: ``arithmetic`` (default) or ``geometric``.

    Returns:
        FinanceMetricResult with unit ``ratio_per_annum``.

    Raises:
        ValueError: on any constraint violation (message names the parameter).
    """
    values = _validate_returns(returns)
    risk_free_rate = _validate_number("risk_free_rate", risk_free_rate)
    risk_free_rate_convention = _validate_choice(
        "risk_free_rate_convention", risk_free_rate_convention, _RF_CONVENTIONS
    )
    ddof = _validate_int("ddof", ddof, 0)
    periods_per_year = _validate_int("periods_per_year", periods_per_year, 1)
    return_convention = _validate_choice(
        "return_convention", return_convention, _RETURN_CONVENTIONS
    )
    if len(values) <= ddof:
        raise ValueError(
            f"ddof must be smaller than len(returns)={len(values)}, got {ddof}"
        )
    if risk_free_rate_convention == "annual":
        rf_period = risk_free_rate / periods_per_year
    else:
        rf_period = risk_free_rate
    if return_convention == "arithmetic":
        excess = [v - rf_period for v in values]
    else:  # geometric
        if rf_period <= -1.0:
            raise ValueError(
                "risk_free_rate implies 1 + rf_period <= 0, undefined under "
                "return_convention='geometric'"
            )
        for i, v in enumerate(values):
            if v <= -1.0:
                raise ValueError(
                    f"returns[{i}] <= -1 is undefined under return_convention='geometric'"
                )
        excess = [(1.0 + v) / (1.0 + rf_period) - 1.0 for v in values]
    std = _sample_std(excess, ddof)
    if std == 0.0:
        raise ValueError(
            "returns imply zero standard deviation of excess returns; sharpe is undefined"
        )
    value = (sum(excess) / len(excess)) / std * math.sqrt(periods_per_year)
    return FinanceMetricResult(
        method_id=_METHOD_ID_SHARPE,
        value=value,
        unit=_UNIT_RATIO_PER_ANNUM,
        parameters={
            "riskFreeRate": risk_free_rate,
            "riskFreeRateConvention": risk_free_rate_convention,
            "ddof": ddof,
            "periodsPerYear": periods_per_year,
            "returnConvention": return_convention,
        },
        checks=check_sharpe(values, ddof, periods_per_year),
    )
