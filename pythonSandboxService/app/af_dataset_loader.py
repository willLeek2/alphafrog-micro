from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Dict, List

import pandas as pd

try:
    from .dataset_manifest import (
        ExpandedDatasets,
        expand_dataset_ids,
        is_manifest_dataset,
        load_manifest_document,
        manifest_file_path,
    )
except ImportError:
    from dataset_manifest import (
        ExpandedDatasets,
        expand_dataset_ids,
        is_manifest_dataset,
        load_manifest_document,
        manifest_file_path,
    )


class DatasetLoadResult:
    """Wrapper exposing failed/skipped members alongside tabular data."""

    def __init__(
        self,
        frame: pd.DataFrame,
        *,
        failed_members: List[Dict[str, Any]] | None = None,
        skipped_members: List[Dict[str, Any]] | None = None,
    ) -> None:
        self.frame = frame
        self.failed_members = failed_members or []
        self.skipped_members = skipped_members or []

    @property
    def attrs(self) -> Dict[str, Any]:
        return {
            "failed_members": self.failed_members,
            "skipped_members": self.skipped_members,
        }


def _member_dicts(members) -> List[Dict[str, Any]]:
    rows: List[Dict[str, Any]] = []
    for member in members:
        rows.append(
            {
                "tsCode": member.ts_code,
                "datasetId": member.dataset_id,
                "status": member.status,
                "errorCode": member.error_code,
                "errorMessage": member.error_message,
            }
        )
    return rows


def _read_atomic_csv(input_root: Path, dataset_id: str, ts_code: str | None = None) -> pd.DataFrame:
    dataset_mount = input_root / dataset_id
    csv_candidates = [
        dataset_mount / f"{dataset_id}.csv",
        dataset_mount / "data.csv",
    ]
    csv_path = next((path for path in csv_candidates if path.is_file()), None)
    if csv_path is None:
        raise FileNotFoundError(f"atomic dataset csv not found under {dataset_mount}")
    frame = pd.read_csv(csv_path)
    if ts_code and "ts_code" not in frame.columns:
        frame = frame.copy()
        frame.insert(0, "ts_code", ts_code)
    return frame


def load_datasets(
    dataset_id: str,
    input_root: str = "/sandbox/input",
    data_dir: str | None = None,
) -> Dict[str, pd.DataFrame]:
    """Load one manifest or atomic dataset as dict[ts_code, DataFrame]."""
    root = Path(input_root)
    if data_dir is not None and is_manifest_dataset(Path(data_dir), dataset_id):
        expanded = expand_dataset_ids(Path(data_dir), [dataset_id])
        manifest_doc = load_manifest_document(Path(data_dir), dataset_id)
        result: Dict[str, pd.DataFrame] = {}
        for member in manifest_doc.get("members", []):
            if not isinstance(member, dict):
                continue
            if member.get("status") != "ready":
                continue
            member_id = str(member.get("datasetId") or "")
            ts_code = str(member.get("tsCode") or member_id)
            if not member_id:
                continue
            result[ts_code] = _read_atomic_csv(root, member_id, ts_code)
        for info in expanded.failed_members + expanded.skipped_members:
            key = info.ts_code or info.dataset_id
            if key and key not in result:
                result[key] = pd.DataFrame()
        return result

    if is_manifest_dataset(root, dataset_id):
        manifest_doc = load_manifest_document(root, dataset_id)
        result = {}
        for member in manifest_doc.get("members", []):
            if not isinstance(member, dict):
                continue
            if member.get("status") != "ready":
                continue
            member_id = str(member.get("datasetId") or "")
            ts_code = str(member.get("tsCode") or member_id)
            if not member_id:
                continue
            result[ts_code] = _read_atomic_csv(root, member_id, ts_code)
        return result

    frame = _read_atomic_csv(root, dataset_id)
    ts_code = str(frame["ts_code"].iloc[0]) if "ts_code" in frame.columns and not frame.empty else dataset_id
    return {ts_code: frame}


def load_manifest(
    manifest_id: str,
    input_root: str = "/sandbox/input",
    data_dir: str | None = None,
) -> DatasetLoadResult:
    """Concat ready manifest members into one DataFrame with ts_code column."""
    by_ts = load_datasets(manifest_id, input_root=input_root, data_dir=data_dir)
    if not by_ts:
        frame = pd.DataFrame()
    else:
        frames = []
        for ts_code, member_frame in by_ts.items():
            part = member_frame.copy()
            if "ts_code" not in part.columns:
                part.insert(0, "ts_code", ts_code)
            frames.append(part)
        frame = pd.concat(frames, ignore_index=True)

    failed_members: List[Dict[str, Any]] = []
    skipped_members: List[Dict[str, Any]] = []
    if data_dir is not None and is_manifest_dataset(Path(data_dir), manifest_id):
        expanded = expand_dataset_ids(Path(data_dir), [manifest_id], fail_fast_on_missing_ready=False)
        failed_members = _member_dicts(expanded.failed_members)
        skipped_members = _member_dicts(expanded.skipped_members)
    elif is_manifest_dataset(Path(input_root), manifest_id):
        manifest_path = manifest_file_path(Path(input_root), manifest_id)
        with manifest_path.open("r", encoding="utf-8") as handle:
            document = json.load(handle)
        for member in document.get("members", []):
            if not isinstance(member, dict):
                continue
            status = str(member.get("status") or "")
            payload = {
                "tsCode": member.get("tsCode"),
                "datasetId": member.get("datasetId"),
                "status": status,
                "errorCode": member.get("errorCode"),
                "errorMessage": member.get("errorMessage"),
            }
            if status == "failed":
                failed_members.append(payload)
            elif status in {"broken", "skipped"}:
                skipped_members.append(payload)

    return DatasetLoadResult(
        frame,
        failed_members=failed_members,
        skipped_members=skipped_members,
    )
