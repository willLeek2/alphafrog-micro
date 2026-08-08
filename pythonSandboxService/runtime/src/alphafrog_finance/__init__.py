# === work-package-B (ccqwen) ===
"""alphafrog_finance — sandbox finance metric library (work package B, Spec §6).

Public surface (frozen for 1.x, apiCompatRange >=1.0.0,<2.0.0; see the package
API inventory): ``cagr``, ``annualized_volatility``, ``sharpe``,
``FinanceMetricResult``, plus check helpers. ``report``/``report_custom`` will
be re-exported here once ``reporting.py`` lands (post CONTRACT_BASE_SHA, since
the marker record schema and environmentId source are task-0 contract items).

Import forms that must keep working (Spec §2.3):
    from alphafrog_finance import cagr
    from alphafrog_finance.reporting import report   # once reporting.py lands
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

__version__ = "1.0.0"
__api_version__ = "1.0"

__all__ = [
    "cagr",
    "annualized_volatility",
    "sharpe",
    "FinanceMetricResult",
    "check_cagr",
    "check_finite",
    "check_sharpe",
    "check_shape_1d",
    "check_unit",
    "check_volatility",
    "__version__",
    "__api_version__",
]
