"""把业务结果和证据缺口分开写成报告。"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any


def write_report(output_dir: Path, *, run_facts: dict[str, Any], gaps: list[dict[str, Any]]) -> Path:
    business = run_facts.get("businessResult")
    lines = [
        "# 诊断报告",
        "",
        "## 业务结果",
        "",
        json.dumps(business, ensure_ascii=False, indent=2),
        "",
        "## 证据缺口",
        "",
    ]
    if not gaps:
        lines.append("本次采集没有登记证据缺口。")
    else:
        for gap in gaps:
            lines.append(f"- {gap.get('item')}: {gap.get('reason')}")
    lines.append("")
    target = output_dir / "report.md"
    target.write_text("\n".join(lines), encoding="utf-8")
    return target
