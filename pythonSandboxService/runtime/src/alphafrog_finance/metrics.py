# === work-package-B (ccqwen) ===
"""Metric functions for alphafrog_finance.

Spec basis: §6 (metrics.py 三个指标函数：纯函数、显式参数、不取数、不做金融正确性判断)
and §5.2 YAML/canonical shapes. YAML camelCase parameter names map 1:1 to
snake_case kwargs (word-wise lowercase); the ``parameters`` echo in each result
uses the camelCase YAML names as open, method-specific keys, including each
method's canonical required inputs (contract §3.5 parameter table: volatility
and sharpe both require ``returns``), so a consumer can reproduce the exact
computed value from ``parameters`` alone.

Method identity flows from the A-canonical GENERATED bindings
(``alphafrog_finance.bindings``); no public kwarg can override it (Spec §6,
codex must-fix 0c147646 ITEM 4; registry swap, codex 0c147646/97ea103a). The
former interim hard-coded registry was REMOVED — hand-maintained method
identity is forbidden. Constraint violations raise ``ValueError`` naming the
offending parameter; nothing is printed and no marker line is produced on
failure.
"""
from __future__ import annotations

import math
from typing import Any, Dict, List, Mapping, Sequence

from .checks import check_cagr, check_sharpe, check_volatility
from .models import FinanceMetricResult


def _method_id_for(metric_key: str) -> str:
    """Module-private identity lookup — the only path from a public metric
    function to a method id. Identity comes from the A-canonical generated
    bindings (``alphafrog_finance.bindings``); unknown keys are internal
    programming errors and fail closed. The ``bindings`` import is lazy
    (inside the function) to avoid any import cycle: ``bindings`` imports
    ``metrics`` lazily inside its own assembly."""
    from . import bindings

    return bindings.method_id_for_function(metric_key)


def _metric_result(
    metric_key: str,
    *,
    value: float,
    unit: str,
    parameters: Dict[str, Any],
    warnings: tuple = (),
    checks: Dict[str, bool],
) -> FinanceMetricResult:
    """Module-private result factory: resolves the method id via the
    A-canonical generated bindings so the public metric functions never expose
    an identity kwarg."""
    return FinanceMetricResult(
        method_id=_method_id_for(metric_key),
        value=value,
        unit=unit,
        parameters=parameters,
        warnings=warnings,
        checks=checks,
    )

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
    return _metric_result(
        "cagr",
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
    by ``sqrt(periods_per_year)``.

    The ``parameters`` echo follows the canonical ``returns + window`` shape
    (contract §3.5 parameter table: ``returns/periodsPerYear`` required,
    ``window`` optional): ``returns`` echoes the ORIGINAL full series as
    passed, and when ``window`` is used it is echoed alongside, so a consumer
    can reproduce the exact value via ``returns[-window:]``.

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
    parameters: Dict[str, Any] = {
        "returns": list(values),
        "periodsPerYear": periods_per_year,
    }
    if window is not None:
        parameters["window"] = window
    return _metric_result(
        "annualized_volatility",
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
    # Canonical parameter order (contract §3.5 table: Sharpe requires
    # ``returns``; the rest are optional execution parameters). The returns
    # sequence actually used is echoed so a consumer can reproduce the exact
    # computed value from parameters alone (codex must-fix 0c147646 ITEM 1).
    return _metric_result(
        "sharpe",
        value=value,
        unit=_UNIT_RATIO_PER_ANNUM,
        parameters={
            "returns": list(values),
            "riskFreeRate": risk_free_rate,
            "riskFreeRateConvention": risk_free_rate_convention,
            "ddof": ddof,
            "periodsPerYear": periods_per_year,
            "returnConvention": return_convention,
        },
        checks=check_sharpe(values, ddof, periods_per_year),
    )


# ---------------------------------------------------------------------------
# === work-package-01304：预设金融指标库 15 项方法 ===
#
# 输入约定（P3-2 拍板）：主模型经取数工具查库、数据以数组入参进方法；本包不取数。
# 序列口径：序列里的 null/None 项视为该日无值，压缩跳过并计入 warnings（不是当 0、
# 不是当 1.0）；调用方需自行保证序列按日期升序（取数工具描述已注明「行序不保证」，
# 主模型负责排序后再传入；数据集复用命中时先按请求窗口过滤再传入）。
# 复权口径（review 拍板）：closes 与 adjFactors 可选配对，传入时方法内乘
# adj_close = close * adj_factor；缺因子日（close 有值、因子 null）与无行情日
# （close null）分开计数——前者是灌数缺口、后者才对应停牌口径；缺因子不当 1.0。
# 多输出方法（①③⑥⑦⑪⑫⑬⑭）返回 Dict[str, FinanceMetricResult]（键=规格 outputs
# 名），每条结果的 parameters 附带 "output": <同名键>，缺值的输出不出现在字典里、
# 不为该键 report()、禁止 NaN；单窗口方法（⑧⑨⑩）一次一个窗口，N 是参数。
# ---------------------------------------------------------------------------

_UNIT_PERCENT = "percent"
_UNIT_PRICE = "price"


def _metric_result_multi(
    metric_key: str,
    outputs: Dict[str, Dict[str, Any]],
    parameters: Dict[str, Any],
    warnings: tuple = (),
    checks: Dict[str, bool] | None = None,
) -> Dict[str, FinanceMetricResult]:
    """Multi-output result factory: one FinanceMetricResult per computable
    output, all sharing the method identity resolved from the generated
    bindings. Each result's parameters carry the shared echo plus
    ``"output": <name>``（投影器按该键取 yaml 对应输出的 unit/displayName）。
    Outputs whose value is missing this call are simply absent（禁止 NaN）。"""
    results: Dict[str, FinanceMetricResult] = {}
    for name, spec in outputs.items():
        per_output_parameters = dict(parameters)
        per_output_parameters["output"] = name
        value = spec["value"]
        if not math.isfinite(value):
            raise ValueError(
                f"output {name!r} of {metric_key!r} is not finite ({value!r}); "
                "missing outputs must be omitted, never NaN/inf"
            )
        results[name] = FinanceMetricResult(
            method_id=_method_id_for(metric_key),
            value=value,
            unit=spec["unit"],
            parameters=per_output_parameters,
            warnings=warnings,
            checks=spec.get("checks") or checks or {"finite": True},
        )
    return results


def _compact_series(name: str, values: Any) -> tuple[List[float], int]:
    """Return (compact finite-float list, missing-count). None items are
    treated as 「该日无值」 and skipped; non-None non-numbers are errors."""
    if isinstance(values, (str, bytes)):
        raise ValueError(f"{name} must be a sequence of numbers/null, not a string")
    try:
        items = list(values)
    except TypeError:
        raise ValueError(f"{name} must be a sequence of numbers/null") from None
    compact: List[float] = []
    missing = 0
    for i, item in enumerate(items):
        if item is None:
            missing += 1
            continue
        if isinstance(item, bool) or not isinstance(item, (int, float)):
            raise ValueError(f"{name}[{i}] must be a number or null, got {type(item).__name__}")
        v = float(item)
        if not math.isfinite(v):
            raise ValueError(f"{name}[{i}] must be finite, got {item!r}")
        compact.append(v)
    return compact, missing


def _warnings_for(missing: int, label: str = "序列内无值日") -> tuple:
    return (f"窗口内{label} {missing} 个已跳过",) if missing > 0 else ()


def _validate_window(name: str, value: Any, minimum: int, allowed: Sequence[int] | None = None) -> int:
    """Validate one window size N（一次一个窗口）；bool 被拒绝。"""
    if isinstance(value, bool) or not isinstance(value, int):
        raise ValueError(f"{name} must be an integer, got {type(value).__name__}")
    if value < minimum:
        raise ValueError(f"{name} must be >= {minimum}, got {value}")
    if allowed is not None and value not in allowed:
        raise ValueError(f"{name} must be one of {'|'.join(str(a) for a in allowed)}, got {value}")
    return value


def _adj_close_series(
    closes: Any, adj_factors: Any
) -> tuple[List[float], int, int]:
    """Combine closes with optional adj_factors: adj_close = close * adj_factor.

    Returns (series, null_close_days, missing_factor_days)。无因子序列时 closes
    原样压缩（指数收盘口径）。逐位配对：close null 计「无行情日」（停牌口径），
    close 有值而因子 null 计「缺因子日」（灌数缺口），两类分开计数；两类当天都跳过、
    缺因子不当 1.0。"""
    if adj_factors is None:
        series, null_close = _compact_series("closes", closes)
        return series, null_close, 0
    if isinstance(closes, (str, bytes)):
        raise ValueError("closes must be a sequence of numbers/null, not a string")
    if isinstance(adj_factors, (str, bytes)):
        raise ValueError("adjFactors must be a sequence of numbers/null, not a string")
    raw = list(closes)
    raw_factors = list(adj_factors)
    if len(raw) != len(raw_factors):
        raise ValueError(
            f"adjFactors length {len(raw_factors)} does not match closes length {len(raw)}"
        )
    series: List[float] = []
    null_close_days = 0
    missing_factor_days = 0
    for i, (c, f) in enumerate(zip(raw, raw_factors)):
        if c is None:
            null_close_days += 1
            continue
        if f is None:
            missing_factor_days += 1
            continue
        if isinstance(c, bool) or not isinstance(c, (int, float)) or not math.isfinite(float(c)):
            raise ValueError(f"closes[{i}] must be a finite number or null")
        if isinstance(f, bool) or not isinstance(f, (int, float)) or not math.isfinite(float(f)):
            raise ValueError(f"adjFactors[{i}] must be a finite number or null")
        series.append(float(c) * float(f))
    return series, null_close_days, missing_factor_days


def _adj_warnings(null_close_days: int, missing_factor_days: int) -> tuple:
    """缺因子日=灌数缺口（知识正文处理），null close=停牌口径——两类分开报。"""
    parts = []
    if missing_factor_days > 0:
        parts.append(f"窗口内缺复权因子 {missing_factor_days} 日（灌数缺口，已跳过、未按 1.0 计）")
    if null_close_days > 0:
        parts.append(f"窗口内无行情 {null_close_days} 日（停牌口径，已跳过）")
    return tuple(parts)


def _optional_positive_number(name: str, value: Any) -> float | None:
    """None -> None（无值）；否则必须是有限正数。"""
    if value is None:
        return None
    v = _validate_number(name, value)
    if v <= 0:
        raise ValueError(f"{name} must be > 0, got {v}")
    return v


def _optional_number(name: str, value: Any) -> float | None:
    """None -> None（无值）；否则必须是有限数。"""
    if value is None:
        return None
    return _validate_number(name, value)


# --- 估值 ---

def rolling_pe(pe_ttm_series: Sequence[float | None], *, window: int | None = None) -> Dict[str, FinanceMetricResult]:
    """滚动市盈率 / E/P / 历史分位（阶段二·估值）。

    PE_TTM = 序列最后一个有值日的 pe_ttm；E/P = 1 / pe_ttm；
    pePercentile = 窗口内有值日中 pe_s <= 当前 pe 的占比（0~1，含当日）。
    当前无值（亏损留空）时不输出任何键；pe <= 0 时只输出 peTtm 并带警告。
    """
    compact, missing = _compact_series("peTtmSeries", pe_ttm_series)
    if window is not None:
        window = _validate_window("window", window, 1)
    series = compact if window is None else compact[-window:]
    warnings = _warnings_for(missing)
    parameters: Dict[str, Any] = {"peTtmSeries": list(series)}
    if window is not None:
        parameters["window"] = window
    if not series:
        return {}
    current = series[-1]
    outputs: Dict[str, Dict[str, Any]] = {"peTtm": {"value": current, "unit": _UNIT_RATIO}}
    if current > 0:
        outputs["ep"] = {"value": 1.0 / current, "unit": _UNIT_RATIO}
        outputs["pePercentile"] = {
            "value": sum(1 for s in series if s <= current) / len(series),
            "unit": _UNIT_RATIO,
        }
    else:
        warnings = warnings + (f"当前 pe_ttm={current} <= 0，E/P 与历史分位未输出",)
    return _metric_result_multi("rolling_pe", outputs, parameters, warnings)


def price_to_book(pb: float | None) -> FinanceMetricResult | None:
    """市净率（阶段二·估值）：直接采用接口 pb；无值为 None（不输出记录）。"""
    value = _optional_positive_number("pb", pb)
    if value is None:
        return None
    return _metric_result(
        "price_to_book",
        value=value,
        unit=_UNIT_RATIO,
        parameters={"pb": value},
        checks={"finite": math.isfinite(value)},
    )


def index_pe_pb(pe_ttm: float | None, pb: float | None) -> Dict[str, FinanceMetricResult]:
    """指数滚动市盈率 / 市净率（阶段二·估值）：读指数每日指标，无值的键不输出。"""
    outputs: Dict[str, Dict[str, Any]] = {}
    if pe_ttm is not None:
        v = _validate_number("peTtm", pe_ttm)
        outputs["peTtm"] = {"value": v, "unit": _UNIT_RATIO}
    if pb is not None:
        v = _optional_positive_number("pb", pb)
        if v is not None:
            outputs["pb"] = {"value": v, "unit": _UNIT_RATIO}
    if not outputs:
        return {}
    return _metric_result_multi("index_pe_pb", outputs, {"peTtm": pe_ttm, "pb": pb})


def dividend_yield(dv_ttm: float | None) -> FinanceMetricResult | None:
    """股息率（阶段二·估值）：dv_ttm 接口已是百分比数值，不再乘 100。"""
    value = _optional_number("dvTtm", dv_ttm)
    if value is None:
        return None
    return _metric_result(
        "dividend_yield",
        value=value,
        unit=_UNIT_PERCENT,
        parameters={"dvTtm": value},
        checks={"finite": math.isfinite(value)},
    )


def free_float_turnover(turnover_rate_f: float | None) -> FinanceMetricResult | None:
    """自由流通换手率（阶段二·估值）：turnover_rate_f 接口已是百分比数值。"""
    value = _optional_number("turnoverRateF", turnover_rate_f)
    if value is None:
        return None
    return _metric_result(
        "free_float_turnover",
        value=value,
        unit=_UNIT_PERCENT,
        parameters={"turnoverRateF": value},
        checks={"finite": math.isfinite(value)},
    )


# --- 质量 ---

def dupont_roe(
    roe_dt: float | None,
    netprofit_margin: float | None,
    assets_turn: float | None,
    assets_to_eqt: float | None,
) -> Dict[str, FinanceMetricResult]:
    """扣非 ROE 与杜邦三组件（阶段二·质量）。

    主输出 ROE_dt；三组件一并给出，三者乘积作为核对值（ROE_approx = NPM*AT*EM）。
    当四个值都存在时附带 dupontConsistent 检查（乘积与 ROE_dt 相对偏差 <= 5%
    或绝对偏差 <= 0.5 个百分点视为一致——机械恒等式核对，不做业务判断）。
    """
    values = {
        "roeDt": _optional_number("roeDt", roe_dt),
        "netprofitMargin": _optional_number("netprofitMargin", netprofit_margin),
        "assetsTurn": _optional_number("assetsTurn", assets_turn),
        "assetsToEqt": _optional_number("assetsToEqt", assets_to_eqt),
    }
    outputs: Dict[str, Dict[str, Any]] = {}
    unit_map = {
        "roeDt": _UNIT_PERCENT,
        "netprofitMargin": _UNIT_PERCENT,
        "assetsTurn": _UNIT_RATIO,
        "assetsToEqt": _UNIT_RATIO,
    }
    for key, v in values.items():
        if v is not None:
            outputs[key] = {"value": v, "unit": unit_map[key]}
    checks: Dict[str, bool] | None = None
    if all(v is not None for v in values.values()):
        approx = values["netprofitMargin"] * values["assetsTurn"] * values["assetsToEqt"]
        outputs["dupontApprox"] = {"value": approx, "unit": _UNIT_PERCENT}
        roe = values["roeDt"]
        checks = {
            "dupontConsistent": abs(approx - roe) <= max(0.05 * abs(roe), 0.5)
        }
    if not outputs:
        return {}
    return _metric_result_multi("dupont_roe", outputs, dict(values), (), checks)


# --- 预测 ---

def _median(values: Sequence[float]) -> float:
    ordered = sorted(values)
    n = len(ordered)
    mid = n // 2
    if n % 2 == 1:
        return ordered[mid]
    return (ordered[mid - 1] + ordered[mid]) / 2.0


def forward_pe_peg(rows: Sequence[Mapping[str, Any]]) -> Dict[str, FinanceMetricResult]:
    """预测市盈率与 PEG（阶段二·预测，report_rc 行列表）。

    入参是 report_rc 行列表（每行 org_name / quarter / pe / eps，null 原样传、
    不当 0）。方法内配对：按 org_name 分组、组内按 quarter 排序（DAO 无 ORDER BY，
    方法必须自己排；同一机构同一 quarter 多行时取列表中最后一行，调用前应按
    report_date 留最新）；「同一机构、相邻预测期、当期 pe 与 eps 同行」在代码里
    执行——当期行提供 pe 与 eps_this，紧邻的前一个预测期行提供 eps_prev。
    跨券商的有限 PE_fwd / PEG 分别取中位数；禁止取返回列表第一行。
    只有单期预测、或算不出有限正增速 g 时，只输出 forwardPe。
    """
    if isinstance(rows, (str, bytes)):
        raise ValueError("rows must be a sequence of report_rc row objects, not a string")
    try:
        items = list(rows)
    except TypeError:
        raise ValueError("rows must be a sequence of report_rc row objects") from None

    groups: Dict[str, Dict[str, tuple]] = {}
    for i, row in enumerate(items):
        if not isinstance(row, Mapping):
            raise ValueError(f"rows[{i}] must be an object, got {type(row).__name__}")
        org = row.get("org_name")
        quarter = row.get("quarter")
        if not isinstance(org, str) or not org:
            raise ValueError(f"rows[{i}].org_name must be a non-empty string")
        if not isinstance(quarter, str) or not quarter:
            raise ValueError(f"rows[{i}].quarter must be a non-empty string")
        pe = row.get("pe")
        eps = row.get("eps")
        if pe is not None:
            pe = _validate_number(f"rows[{i}].pe", pe)
        if eps is not None:
            eps = _validate_number(f"rows[{i}].eps", eps)
        # 同 org 同 quarter 重复行：保留最后一行（调用侧应先按 report_date 去重留最新）
        groups.setdefault(org, {})[quarter] = (pe, eps)

    pe_values: List[float] = []
    peg_values: List[float] = []
    for org in sorted(groups):
        quarters = sorted(groups[org])
        if not quarters:
            continue
        current_pe, eps_this = groups[org][quarters[-1]]
        if current_pe is not None and current_pe > 0:
            pe_values.append(current_pe)
        if len(quarters) < 2:
            continue  # 只有一期预测：算不出 g，不参与 PEG
        _, eps_prev = groups[org][quarters[-2]]
        if eps_this is None or eps_prev is None or eps_prev <= 0:
            continue
        g = eps_this / eps_prev - 1.0
        if math.isfinite(g) and g > 0 and current_pe is not None and current_pe > 0:
            peg_values.append(current_pe / (g * 100.0))

    parameters: Dict[str, Any] = {"rowCount": len(items), "orgCount": len(groups)}
    outputs: Dict[str, Dict[str, Any]] = {}
    warnings: tuple = ()
    if not pe_values:
        return {}
    outputs["forwardPe"] = {"value": _median(pe_values), "unit": _UNIT_RATIO}
    if peg_values:
        outputs["peg"] = {"value": _median(peg_values), "unit": _UNIT_RATIO}
    else:
        warnings = ("无「同一机构相邻预测期」的有限正增速配对，PEG 未输出（只输出 forwardPe）",)
    return _metric_result_multi("forward_pe_peg", outputs, parameters, warnings)


# --- 动量与均线 ---

def price_momentum(
    closes: Sequence[float | None],
    *,
    window: int,
    adj_factors: Sequence[float | None] | None = None,
) -> FinanceMetricResult:
    """价格动量（阶段二·动量）：mom_N = adj_close[t]/adj_close[t-N] - 1。

    N 取 {63,126,252}（3/6/12 个月按交易日历）；adjFactors 可选——传入时
    adj_close = close * adj_factor（缺因子日与无行情日分开计数并跳过），不传时
    closes 须已是复权口径（指数收盘即用）。有效日不足 N+1 时报错。
    """
    n = _validate_window("window", window, 1, allowed=(63, 126, 252))
    series, null_close_days, missing_factor_days = _adj_close_series(closes, adj_factors)
    if len(series) < n + 1:
        raise ValueError(
            f"有效交易日 {len(series)} 不足窗口 {n}+1，动量无定义"
        )
    value = series[-1] / series[-1 - n] - 1.0
    parameters: Dict[str, Any] = {"closes": list(series), "window": n}
    if adj_factors is not None:
        parameters["adjFactorsApplied"] = True
    return _metric_result(
        "price_momentum",
        value=value,
        unit=_UNIT_RATIO,
        parameters=parameters,
        warnings=_adj_warnings(null_close_days, missing_factor_days),
        checks={"finite": math.isfinite(value)},
    )


def moving_average(
    closes: Sequence[float | None],
    *,
    window: int,
    adj_factors: Sequence[float | None] | None = None,
) -> Dict[str, FinanceMetricResult]:
    """MA / EMA（阶段二·均线）：SMA_N = 最近 N 个有效日均值；
    EMA_N 以首个 N 日 SMA 为起点迭代 α = 2/(N+1)（输出最新值）。一次一个窗口。

    指数/ETF 传入对应收盘口径（ETF 需乘复权因子——传 adjFactors 或预先乘好）。
    有效日不足 N 时报错。
    """
    n = _validate_window("window", window, 2)
    series, null_close_days, missing_factor_days = _adj_close_series(closes, adj_factors)
    if len(series) < n:
        raise ValueError(f"有效交易日 {len(series)} 不足窗口 {n}，均线无定义")
    sma = sum(series[:n]) / n
    ema = sma
    alpha = 2.0 / (n + 1)
    for price in series[n:]:
        ema = alpha * price + (1.0 - alpha) * ema
    parameters: Dict[str, Any] = {"closes": list(series), "window": n}
    if adj_factors is not None:
        parameters["adjFactorsApplied"] = True
    warnings = _adj_warnings(null_close_days, missing_factor_days)
    outputs = {
        "sma": {"value": sma, "unit": _UNIT_PRICE},
        "ema": {"value": ema, "unit": _UNIT_PRICE},
    }
    return _metric_result_multi("moving_average", outputs, parameters, warnings)


# --- 区间收益 ---

def etf_adj_return(
    closes: Sequence[float | None],
    *,
    window: int | None = None,
    adj_factors: Sequence[float | None] | None = None,
) -> FinanceMetricResult:
    """ETF 复权区间收益（阶段二·区间收益）：ret = 复权收盘[t]/复权收盘[t-N] - 1。

    ETF 应传 adjFactors（etf_close * etf_adj_factor）。window 为 N 个交易日：
    不传时按传入序列首尾计算（调用侧先按请求窗口过滤再传入）；传入时取最后
    N+1 个有效日。有效日不足时报错。
    """
    n = None if window is None else _validate_window("window", window, 1)
    series, null_close_days, missing_factor_days = _adj_close_series(closes, adj_factors)
    if n is None:
        if len(series) < 2:
            raise ValueError(f"有效交易日 {len(series)} 不足 2，区间收益无定义")
        value = series[-1] / series[0] - 1.0
    else:
        if len(series) < n + 1:
            raise ValueError(f"有效交易日 {len(series)} 不足窗口 {n}+1，区间收益无定义")
        value = series[-1] / series[-1 - n] - 1.0
    parameters: Dict[str, Any] = {"closes": list(series)}
    if n is not None:
        parameters["window"] = n
    if adj_factors is not None:
        parameters["adjFactorsApplied"] = True
    return _metric_result(
        "etf_adj_return",
        value=value,
        unit=_UNIT_RATIO,
        parameters=parameters,
        warnings=_adj_warnings(null_close_days, missing_factor_days),
        checks={"finite": math.isfinite(value)},
    )


def fund_accum_nav_return(accum_navs: Sequence[float | None], *, window: int | None = None) -> FinanceMetricResult:
    """公募累计净值区间收益（阶段二·区间收益）：ret = accum_nav[t]/accum_nav[t-N] - 1。

    用累计净值（不是单位净值）；未披露日（null）按无值跳过并计警告。window 为 N
    个净值日：不传时按传入序列首尾计算（数据集复用命中时先按请求窗口过滤
    nav_date、按日期排序，再传入）；传入时取最后 N+1 个有效日。
    """
    n = None if window is None else _validate_window("window", window, 1)
    series, missing = _compact_series("accumNavs", accum_navs)
    if n is None:
        if len(series) < 2:
            raise ValueError(f"有效净值日 {len(series)} 不足 2，区间收益无定义")
        value = series[-1] / series[0] - 1.0
    else:
        if len(series) < n + 1:
            raise ValueError(f"有效净值日 {len(series)} 不足窗口 {n}+1，区间收益无定义")
        value = series[-1] / series[-1 - n] - 1.0
    parameters: Dict[str, Any] = {"accumNavs": list(series)}
    if n is not None:
        parameters["window"] = n
    return _metric_result(
        "fund_accum_nav_return",
        value=value,
        unit=_UNIT_RATIO,
        parameters=parameters,
        warnings=_warnings_for(missing, "未披露净值日"),
        checks={"finite": math.isfinite(value)},
    )


def cb_daily_return(pct_chgs: Sequence[float | None], *, window: int | None = None) -> Dict[str, FinanceMetricResult]:
    """可转债日收益与区间连乘（阶段二·区间收益）：ret_d = pct_chg / 100；
    区间收益 intervalReturn = prod(1 + ret_d) - 1。

    双输出：dailyReturn = 最后一个有值日的日收益；intervalReturn 在 window
    不传时对整个传入序列连乘、传入时对最后 window 个有值日连乘。
    pct_chg 缺值日按无行情跳过并计警告。转债价格序列不加复权因子。
    """
    n = None if window is None else _validate_window("window", window, 1)
    series, missing = _compact_series("pctChgs", pct_chgs)
    if not series:
        raise ValueError("pctChgs 内没有任何有值日，日收益无定义")
    span = series if n is None else series[-n:]
    if not span:
        raise ValueError(f"有效交易日 {len(series)} 不足窗口 {n}，区间收益无定义")
    prod = 1.0
    for v in span:
        prod *= 1.0 + v / 100.0
    parameters: Dict[str, Any] = {"pctChgs": list(series)}
    if n is not None:
        parameters["window"] = n
    outputs = {
        "dailyReturn": {"value": series[-1] / 100.0, "unit": _UNIT_RATIO},
        "intervalReturn": {"value": prod - 1.0, "unit": _UNIT_RATIO},
    }
    return _metric_result_multi(
        "cb_daily_return", outputs, parameters, _warnings_for(missing)
    )


# --- 风险 ---

def drawdown_sortino_calmar(
    prices: Sequence[float | None],
    *,
    risk_free_rate: float = 0.0,
    risk_free_rate_convention: str = "annual",
    periods_per_year: int = 252,
    adj_factors: Sequence[float | None] | None = None,
) -> Dict[str, FinanceMetricResult]:
    """最大回撤 / Sortino / Calmar（阶段二·风险，一方法三输出）。

    价格序列 P：股票/ETF 用复权收盘（传 adjFactors 或预先乘好）、指数用收盘、
    公募用累计净值（均不传 adjFactors）。adjFactors 传入时缺因子日与无行情日
    分开计数并跳过；不传时序列内 null 按无值日计。
    maxDD = min(P[t]/max(P[s], s<=t) - 1)（<= 0）；
    Sortino 与现有夏普同一套超额收益定义，下行偏差只统计负超额（ddof=1），
    无负超额或负超额不足 2 期时不输出该键；
    Calmar = CAGR / |maxDD|，maxDD = 0（区间单调不回撤）时不输出该键。
    """
    if adj_factors is not None:
        series, null_close_days, missing_factor_days = _adj_close_series(prices, adj_factors)
        warnings = list(_adj_warnings(null_close_days, missing_factor_days))
    else:
        series, missing = _compact_series("prices", prices)
        warnings = list(_warnings_for(missing, "无值日"))
    if len(series) < 2:
        raise ValueError(f"prices must contain at least 2 valid observations, got {len(series)}")
    risk_free_rate = _validate_number("riskFreeRate", risk_free_rate)
    risk_free_rate_convention = _validate_choice(
        "riskFreeRateConvention", risk_free_rate_convention, _RF_CONVENTIONS
    )
    periods_per_year = _validate_int("periodsPerYear", periods_per_year, 1)
    if risk_free_rate_convention == "annual":
        rf_period = risk_free_rate / periods_per_year
    else:
        rf_period = risk_free_rate
    parameters: Dict[str, Any] = {
        "prices": list(series),
        "riskFreeRate": risk_free_rate,
        "riskFreeRateConvention": risk_free_rate_convention,
        "periodsPerYear": periods_per_year,
    }
    if adj_factors is not None:
        parameters["adjFactorsApplied"] = True
    outputs: Dict[str, Dict[str, Any]] = {}

    peak = series[0]
    max_dd = 0.0
    for p in series:
        if p > peak:
            peak = p
        dd = p / peak - 1.0
        if dd < max_dd:
            max_dd = dd
    outputs["maxDrawdown"] = {"value": max_dd, "unit": _UNIT_RATIO}

    returns = [series[i] / series[i - 1] - 1.0 for i in range(1, len(series))]
    excess = [r - rf_period for r in returns]
    negatives = [e for e in excess if e < 0]
    if len(negatives) >= 2:
        mean_excess = sum(excess) / len(excess)
        down = _sample_std(negatives, 1)
        outputs["sortino"] = {
            "value": mean_excess / down * math.sqrt(periods_per_year),
            "unit": _UNIT_RATIO_PER_ANNUM,
        }
    else:
        warnings.append(f"负超额收益期数 {len(negatives)} 不足 2，Sortino 未输出")

    if max_dd < 0:
        n_periods = len(series) - 1
        cagr = (series[-1] / series[0]) ** (periods_per_year / n_periods) - 1.0
        outputs["calmar"] = {"value": cagr / abs(max_dd), "unit": _UNIT_RATIO_PER_ANNUM}
    else:
        warnings.append("区间无回撤（maxDD = 0），Calmar 未输出")
    return _metric_result_multi("drawdown_sortino_calmar", outputs, parameters, tuple(warnings))


# --- 转债溢价 ---

def cb_premium(cb_over_rate: float | None, bond_over_rate: float | None) -> Dict[str, FinanceMetricResult]:
    """可转债转股溢价率与纯债溢价率（阶段二·转债溢价）：接口已是百分比，直接使用。"""
    outputs: Dict[str, Dict[str, Any]] = {}
    cb = _optional_number("cbOverRate", cb_over_rate)
    bond = _optional_number("bondOverRate", bond_over_rate)
    if cb is not None:
        outputs["cbOverRate"] = {"value": cb, "unit": _UNIT_PERCENT}
    if bond is not None:
        outputs["bondOverRate"] = {"value": bond, "unit": _UNIT_PERCENT}
    if not outputs:
        return {}
    return _metric_result_multi("cb_premium", outputs, {"cbOverRate": cb_over_rate, "bondOverRate": bond_over_rate})


# --- 跨市场 ---

def ah_premium(ah_premium: float | None) -> FinanceMetricResult | None:
    """AH 溢价（阶段二·跨市场）：接口单位是（A/H）%，直接使用。"""
    value = _optional_number("ahPremium", ah_premium)
    if value is None:
        return None
    return _metric_result(
        "ah_premium",
        value=value,
        unit=_UNIT_PERCENT,
        parameters={"ahPremium": value},
        checks={"finite": math.isfinite(value)},
    )
