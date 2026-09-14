# === work-package-B (ccqwen) ===
"""Immutable result objects for alphafrog_finance.

Spec basis: 金融MethodSpec-V5-源码实施与Agent分工计划.md §6 (models.py 定义不可变结果对象，
包含值、单位、参数回显、警告和检查结果). `parameters` stays an open object: method-specific
execution parameters never become global resolver/user fields.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Mapping


@dataclass(frozen=True)
class FinanceMetricResult:
    """Immutable result of one metric computation.

    Attributes:
        method_id: method identity echoed from the computation
            (e.g. ``finance.growth.cagr``; final strings owned by work package A YAML).
            The public metric functions obtain it exclusively through the
            module-private identity factory in ``metrics.py`` (Spec §6);
            ``report()`` only emits a method triple looked up from the
            canonical specs installed with the package, so a hand-crafted
            instance can never supply or override the emitted triple
            (codex must-fix 0c147646 ITEM 4).
        value: the computed metric value.
        unit: result unit string (e.g. ``ratio``, ``ratio_per_annum``).
        parameters: echo of the execution parameters actually used (open keys).
        warnings: non-fatal observations; empty by default. The library makes no
            financial-correctness judgments, so nothing is added automatically.
        checks: named boolean check outcomes (e.g. ``{"finite": True}``).
    """

    method_id: str
    value: float
    unit: str
    parameters: Mapping[str, Any]
    warnings: tuple = ()
    checks: Mapping[str, bool] = field(default_factory=dict)
