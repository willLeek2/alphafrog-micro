"""诊断包文件清单。校验值按文件字节计算 SHA-256。"""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
from typing import Any


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def build_manifest(
    output_dir: Path,
    *,
    collected_at: str,
    extra_entries: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    files: list[dict[str, Any]] = []
    for path in sorted(output_dir.rglob("*")):
        if not path.is_file():
            continue
        if path.name == "manifest.json":
            continue
        files.append(
            {
                "path": path.relative_to(output_dir).as_posix(),
                "source": "admin-diagnostic-read",
                "query": {},
                "eventTime": None,
                "eventTimeMissingReason": "该文件没有单独的事件时间",
                "readTime": collected_at,
                "sha256": sha256_file(path),
                "completeness": "collected",
                "missingReason": None,
            }
        )
    files.extend(extra_entries or [])
    return {"schemaVersion": 1, "files": files}


def write_manifest(output_dir: Path, manifest: dict[str, Any]) -> Path:
    target = output_dir / "manifest.json"
    target.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return target
