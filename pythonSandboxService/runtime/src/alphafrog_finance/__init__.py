# === work-package-B (ccqwen) ===
"""alphafrog_finance — sandbox finance metric library (work package B, Spec §6).

Public surface (frozen for 1.x, apiCompatRange >=1.0.0,<2.0.0; see the package
API inventory): ``cagr``, ``annualized_volatility``, ``sharpe``,
``FinanceMetricResult``, ``report``, ``report_custom``, plus check helpers.

Import forms that must keep working (Spec §2.3):
    from alphafrog_finance import cagr
    from alphafrog_finance.reporting import report
"""
from .checks import (
    check_cagr,
    check_finite,
    check_sharpe,
    check_shape_1d,
    check_unit,
    check_volatility,
)
from .metrics import annualized_volatility, cagr, sharpe
from .models import FinanceMetricResult
from .reporting import MARKER, report, report_custom

__version__ = "1.0.0"
__api_version__ = "1.0"

__all__ = [
    "cagr",
    "annualized_volatility",
    "sharpe",
    "FinanceMetricResult",
    "report",
    "report_custom",
    "MARKER",
    "check_cagr",
    "check_finite",
    "check_sharpe",
    "check_shape_1d",
    "check_unit",
    "check_volatility",
    "__version__",
    "__api_version__",
]
