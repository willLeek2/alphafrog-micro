# === work-package-B (ccqwen) ===
"""Check helpers for alphafrog_finance.

Spec basis: §6 (checks.py 提供三个库方法专用 check_* 和与方法无关的 finite、shape、单位辅助检查，
不扩成指标白名单). These only validate shapes/finiteness/units; they never judge financial
correctness and never grow into a metric whitelist.
"""
from __future__ import annotations

import math
from typing import Iterable


def check_finite(values: Iterable[float]) -> bool:
    """True when every value is a finite float (no NaN/inf)."""
    for v in values:
        if not isinstance(v, (int, float)) or isinstance(v, bool):
            return False
        if not math.isfinite(float(v)):
            return False
    return True


def check_shape_1d(values, min_length: int) -> bool:
    """True when values is a non-string sequence with at least min_length items."""
    if isinstance(values, (str, bytes)):
        return False
    try:
        return len(values) >= min_length
    except TypeError:
        return False


def check_unit(unit) -> bool:
    """True when unit is a non-empty plain string."""
    return isinstance(unit, str) and len(unit.strip()) > 0


def check_cagr(beginning_value: float, ending_value: float, periods: int) -> dict:
    """CAGR-specific checks: positive values, periods >= 1, all finite."""
    return {
        "finite": check_finite([beginning_value, ending_value]),
        "positiveValues": bool(beginning_value > 0 and ending_value > 0),
        "periodsValid": isinstance(periods, int) and not isinstance(periods, bool) and periods >= 1,
    }


def check_volatility(returns, window) -> dict:
    """Volatility-specific checks: length >= 2, finite series, optional window >= 2."""
    return {
        "shape": check_shape_1d(returns, 2),
        "finite": check_finite(returns) if check_shape_1d(returns, 1) else False,
        "windowValid": window is None or (isinstance(window, int) and not isinstance(window, bool) and window >= 2),
    }


def check_sharpe(returns, ddof: int, periods_per_year: int) -> dict:
    """Sharpe-specific checks: enough observations for ddof, finite series, valid ddof/ppy."""
    n = len(returns) if check_shape_1d(returns, 1) else 0
    return {
        "shape": check_shape_1d(returns, 2),
        "finite": check_finite(returns) if n else False,
        "ddofValid": isinstance(ddof, int) and not isinstance(ddof, bool) and ddof >= 0,
        "sampleExceedsDdof": n > ddof,
        "periodsPerYearValid": (
            isinstance(periods_per_year, int) and not isinstance(periods_per_year, bool) and periods_per_year >= 1
        ),
    }
