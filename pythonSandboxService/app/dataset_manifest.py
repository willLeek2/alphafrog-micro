from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, List

DATASET_ID_PATTERN = re.compile(r"^[a-zA-Z0-9._-]+$")


@dataclass(frozen=True)
class ManifestMemberInfo:
    ts_code: str
    dataset_id: str
    status: str
    error_code: str | None = None
    error_message: str | None = None


@dataclass
class ExpandedDatasets:
    """Result of expanding incoming dataset ids (manifest + atomic)."""

    manifest_ids: List[str] = field(default_factory=list)
    atomic_ids: List[str] = field(default_factory=list)
    failed_members: List[ManifestMemberInfo] = field(default_factory=list)
    skipped_members: List[ManifestMemberInfo] = field(default_factory=list)


def manifest_file_path(data_dir: Path, dataset_id: str) -> Path:
    return data_dir / dataset_id / f"{dataset_id}.manifest.json"


def is_manifest_dataset(data_dir: Path, dataset_id: str) -> bool:
    return manifest_file_path(data_dir, dataset_id).is_file()


def _validate_dataset_id(dataset_id: str) -> None:
    if not dataset_id or not DATASET_ID_PATTERN.match(dataset_id):
        raise ValueError(f"dataset_id contains illegal characters: {dataset_id!r}")


def _resolve_dataset_dir(data_dir: Path, dataset_id: str) -> Path:
    _validate_dataset_id(dataset_id)
    dataset_dir = (data_dir / dataset_id).resolve()
    base_dir = data_dir.resolve()
    if not str(dataset_dir).startswith(str(base_dir)):
        raise ValueError(f"dataset_id resolves outside base directory: {dataset_id}")
    return dataset_dir


def load_manifest_document(data_dir: Path, manifest_id: str) -> Dict[str, Any]:
    _validate_dataset_id(manifest_id)
    manifest_path = manifest_file_path(data_dir, manifest_id)
    if not manifest_path.is_file():
        raise FileNotFoundError(f"manifest not found: {manifest_path}")
    try:
        with manifest_path.open("r", encoding="utf-8") as handle:
            payload = json.load(handle)
    except json.JSONDecodeError as exc:
        raise ValueError(f"manifest.json format error for {manifest_id}: {exc}") from exc
    if not isinstance(payload, dict):
        raise ValueError(f"manifest.json root must be object for {manifest_id}")
    return payload


def _parse_member(raw: Dict[str, Any]) -> ManifestMemberInfo:
    return ManifestMemberInfo(
        ts_code=str(raw.get("tsCode") or ""),
        dataset_id=str(raw.get("datasetId") or ""),
        status=str(raw.get("status") or ""),
        error_code=raw.get("errorCode"),
        error_message=raw.get("errorMessage"),
    )


def expand_dataset_ids(
    data_dir: Path,
    dataset_id_list: List[str],
    *,
    fail_fast_on_missing_ready: bool = True,
) -> ExpandedDatasets:
    """Expand manifest ids to ready atomic ids; preserve manifest ids for copy."""
    manifest_ids: List[str] = []
    atomic_ids: List[str] = []
    failed_members: List[ManifestMemberInfo] = []
    skipped_members: List[ManifestMemberInfo] = []
    seen_manifest: set[str] = set()
    seen_atomic: set[str] = set()

    for ds_id in dataset_id_list:
        if not ds_id:
            continue
        cleaned = ds_id.strip()
        if not cleaned:
            continue

        if is_manifest_dataset(data_dir, cleaned):
            if cleaned not in seen_manifest:
                manifest_ids.append(cleaned)
                seen_manifest.add(cleaned)
            document = load_manifest_document(data_dir, cleaned)
            members = document.get("members")
            if not isinstance(members, list):
                raise ValueError(f"manifest {cleaned} missing members array")
            for item in members:
                if not isinstance(item, dict):
                    raise ValueError(f"manifest {cleaned} has invalid member entry")
                member = _parse_member(item)
                if member.status == "ready":
                    if not member.dataset_id:
                        raise ValueError(
                            f"manifest {cleaned} ready member missing datasetId "
                            f"(tsCode={member.ts_code})"
                        )
                    dataset_dir = _resolve_dataset_dir(data_dir, member.dataset_id)
                    if not dataset_dir.is_dir():
                        message = (
                            f"manifest ready member directory missing: "
                            f"manifest={cleaned} member={member.dataset_id}"
                        )
                        if fail_fast_on_missing_ready:
                            raise FileNotFoundError(message)
                        skipped_members.append(
                            ManifestMemberInfo(
                                member.ts_code,
                                member.dataset_id,
                                "broken",
                                error_code="MISSING_DATASET",
                                error_message=message,
                            )
                        )
                        continue
                    csv_path = dataset_dir / f"{member.dataset_id}.csv"
                    if not csv_path.is_file():
                        message = (
                            f"manifest ready member csv missing: "
                            f"manifest={cleaned} member={member.dataset_id}"
                        )
                        if fail_fast_on_missing_ready:
                            raise FileNotFoundError(message)
                        skipped_members.append(
                            ManifestMemberInfo(
                                member.ts_code,
                                member.dataset_id,
                                "broken",
                                error_code="MISSING_CSV",
                                error_message=message,
                            )
                        )
                        continue
                    if member.dataset_id not in seen_atomic:
                        atomic_ids.append(member.dataset_id)
                        seen_atomic.add(member.dataset_id)
                elif member.status == "failed":
                    failed_members.append(member)
                elif member.status == "broken":
                    skipped_members.append(member)
            continue

        _validate_dataset_id(cleaned)
        dataset_dir = _resolve_dataset_dir(data_dir, cleaned)
        if not dataset_dir.is_dir():
            raise FileNotFoundError(f"dataset_id directory not found: {cleaned}")
        if cleaned not in seen_atomic:
            atomic_ids.append(cleaned)
            seen_atomic.add(cleaned)

    return ExpandedDatasets(
        manifest_ids=manifest_ids,
        atomic_ids=atomic_ids,
        failed_members=failed_members,
        skipped_members=skipped_members,
    )
