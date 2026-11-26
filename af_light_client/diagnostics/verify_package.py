"""按清单重算 SHA-256。不改写已经形成的清单和报告。"""

from __future__ import annotations

import json
from pathlib import Path

from .manifest import sha256_file


def verify_package(package_dir: Path) -> dict:
    manifest_path = package_dir / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    mismatches: list[dict[str, str]] = []
    for entry in manifest.get("files") or []:
        rel = entry.get("path")
        expected = entry.get("sha256")
        if not rel or not expected:
            continue
        target = package_dir / rel
        if not target.is_file():
            mismatches.append({"path": rel, "result": "文件损坏", "detail": "清单中的文件不存在"})
            continue
        actual = sha256_file(target)
        if actual != expected:
            mismatches.append({"path": rel, "result": "内容被修改", "detail": actual})
    return {"checked": True, "mismatchCount": len(mismatches), "mismatches": mismatches}
