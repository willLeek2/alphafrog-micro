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


# ---------------------------------------------------------------------------
# Run-level CSV helpers (260623-harness-optimization-03)
# ---------------------------------------------------------------------------

_RUN_LEVEL_DATASET_CSV = "paths_dataset.csv"
_RUN_LEVEL_MANIFEST_CSV = "path_manifest.csv"


def _run_level_csv_paths(input_root: Path) -> tuple[Path, Path]:
    """Run-level CSVs live next to the input_root (e.g. /sandbox/paths_dataset.csv)."""
    sandbox_root = input_root.parent
    return sandbox_root / _RUN_LEVEL_DATASET_CSV, sandbox_root / _RUN_LEVEL_MANIFEST_CSV


def _is_run_level_mode(input_root: Path) -> bool:
    paths_csv, manifests_csv = _run_level_csv_paths(input_root)
    return paths_csv.is_file() or manifests_csv.is_file()


def _load_run_level_dataset_index(paths_csv: Path) -> pd.DataFrame:
    return pd.read_csv(paths_csv)


def _load_run_level_manifest_index(manifests_csv: Path) -> pd.DataFrame:
    return pd.read_csv(manifests_csv)


def _looks_like_run_level_id(value: str) -> bool:
    """Run-level IDs are positive integers; legacy dataset ids contain dashes/dots."""
    return value.isdigit() and int(value) > 0


def _resolve_run_dataset_file_path(
    dataset_number: str, paths_df: pd.DataFrame
) -> str | None:
    mask = paths_df["agent_run_dataset_id"].astype(str) == dataset_number
    rows = paths_df[mask]
    if rows.empty:
        return None
    return str(rows.iloc[0]["dataset_file_path"])


def _resolve_run_manifest_file_path(
    manifest_number: str, manifests_df: pd.DataFrame
) -> str | None:
    mask = manifests_df["agent_run_manifest_id"].astype(str) == manifest_number
    rows = manifests_df[mask]
    if rows.empty:
        return None
    return str(rows.iloc[0]["manifest_file_path"])


def _read_run_dataset_csv(
    file_path: str, from_ts_code: str | None
) -> pd.DataFrame:
    frame = pd.read_csv(file_path)
    if from_ts_code and "ts_code" not in frame.columns:
        frame = frame.copy()
        frame.insert(0, "ts_code", from_ts_code)
    return frame


def _load_run_datasets_by_number(
    dataset_number: str, paths_csv: Path
) -> Dict[str, pd.DataFrame]:
    paths_df = _load_run_level_dataset_index(paths_csv)
    file_path = _resolve_run_dataset_file_path(dataset_number, paths_df)
    if file_path is None:
        raise FileNotFoundError(
            f"run-level dataset #{dataset_number} not found in {paths_csv}"
        )
    row = paths_df[paths_df["agent_run_dataset_id"].astype(str) == dataset_number].iloc[0]
    from_ts_code = str(row.get("from_ts_code") or "")
    if not from_ts_code or from_ts_code.upper() == "UNCERTAIN":
        from_ts_code = dataset_number
    frame = _read_run_dataset_csv(file_path, from_ts_code)
    return {from_ts_code: frame}


def _load_run_manifest_by_number(
    manifest_number: str,
    paths_csv: Path,
    manifests_csv: Path,
) -> DatasetLoadResult:
    manifests_df = _load_run_level_manifest_index(manifests_csv)
    manifest_path = _resolve_run_manifest_file_path(manifest_number, manifests_df)
    if manifest_path is None:
        raise FileNotFoundError(
            f"run-level manifest #{manifest_number} not found in {manifests_csv}"
        )

    with Path(manifest_path).open("r", encoding="utf-8") as handle:
        document = json.load(handle)

    members = document.get("members") or []
    if not isinstance(members, list):
        members = []

    frames: List[pd.DataFrame] = []
    failed_members: List[Dict[str, Any]] = []
    skipped_members: List[Dict[str, Any]] = []

    for member in members:
        if not isinstance(member, dict):
            continue
        status = str(member.get("status") or "").lower()
        ts_code = str(member.get("tsCode") or "")
        member_dataset_id = str(member.get("datasetId") or "")
        payload = {
            "tsCode": ts_code,
            "datasetId": member_dataset_id,
            "status": status,
            "errorCode": member.get("errorCode"),
            "errorMessage": member.get("errorMessage"),
        }
        if status == "ready":
            if not member_dataset_id:
                failed_members.append({**payload, "status": "broken", "errorMessage": "missing datasetId"})
                continue
            try:
                by_ts = _load_run_datasets_by_number(member_dataset_id, paths_csv)
                frame = next(iter(by_ts.values()))
                part = frame.copy()
                if "ts_code" not in part.columns:
                    part.insert(0, "ts_code", ts_code or member_dataset_id)
                frames.append(part)
            except Exception as e:
                failed_members.append({**payload, "errorMessage": str(e)})
        elif status == "failed":
            failed_members.append(payload)
        else:
            skipped_members.append(payload)

    if frames:
        frame = pd.concat(frames, ignore_index=True)
    else:
        frame = pd.DataFrame()

    return DatasetLoadResult(frame, failed_members=failed_members, skipped_members=skipped_members)


# ---------------------------------------------------------------------------
# Public API
# ---------------------------------------------------------------------------

def load_datasets(
    dataset_id: str,
    input_root: str = "/sandbox/input",
    data_dir: str | None = None,
) -> Dict[str, pd.DataFrame]:
    """Load one manifest or atomic dataset as dict[ts_code, DataFrame].

    Run-level mode: if /sandbox/paths_dataset.csv exists and dataset_id is a
    positive integer, resolve the real file path from the run-level index.
    Legacy mode: fall back to /sandbox/input/<dataset_id>/ structure.
    """
    root = Path(input_root)

    # Run-level fast path
    if _looks_like_run_level_id(dataset_id) and _is_run_level_mode(root):
        paths_csv, _ = _run_level_csv_paths(root)
        return _load_run_datasets_by_number(dataset_id, paths_csv)

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
    """Concat ready manifest members into one DataFrame with ts_code column.

    Run-level mode: if /sandbox/path_manifest.csv exists and manifest_id is a
    positive integer, resolve the manifest JSON from the run-level index and
    load member datasets via /sandbox/paths_dataset.csv.
    Legacy mode: fall back to /sandbox/input/<manifest_id>/ structure.
    """
    root = Path(input_root)

    # Run-level fast path
    if _looks_like_run_level_id(manifest_id) and _is_run_level_mode(root):
        paths_csv, manifests_csv = _run_level_csv_paths(root)
        return _load_run_manifest_by_number(manifest_id, paths_csv, manifests_csv)

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
