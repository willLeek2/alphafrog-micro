from __future__ import annotations

import json
import hashlib
import os
import time
from pathlib import Path
from typing import Any, Dict, Iterator, List, Mapping

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


def _read_atomic_csv(
    input_root: Path,
    dataset_id: str,
    ts_code: str | None = None,
    *,
    usecols=None,
    dtype=None,
) -> pd.DataFrame:
    dataset_mount = input_root / dataset_id
    csv_candidates = [
        dataset_mount / f"{dataset_id}.csv",
        dataset_mount / "data.csv",
        dataset_mount / f"{dataset_id}.json",
        dataset_mount / "data.json",
        dataset_mount / f"{dataset_id}.parquet",
        dataset_mount / "data.parquet",
    ]
    csv_path = next((path for path in csv_candidates if path.is_file()), None)
    if csv_path is None:
        raise FileNotFoundError(f"atomic dataset file not found under {dataset_mount}")
    frame = _read_csv(csv_path, dataset_id, "load_datasets", usecols=usecols, dtype=dtype)
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


def _split_run_level_ids(value: str) -> List[str]:
    """Accept comma-separated run-level numbers produced by executePython args."""
    parts = [part.strip() for part in str(value or "").split(",")]
    return [part for part in parts if part]


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
    file_path: str,
    from_ts_code: str | None,
    dataset_number: str,
    loader_method: str,
    *,
    usecols=None,
    dtype=None,
    metadata=None,
) -> pd.DataFrame:
    frame = _read_csv(
        Path(file_path), dataset_number, loader_method, usecols=usecols, dtype=dtype, metadata=metadata
    )
    if from_ts_code and "ts_code" not in frame.columns:
        frame = frame.copy()
        frame.insert(0, "ts_code", from_ts_code)
    return frame


def _load_run_datasets_by_number(
    dataset_number: str,
    paths_csv: Path,
    *,
    usecols=None,
    dtype=None,
    loader_method: str = "load_datasets",
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
    frame = _read_run_dataset_csv(
        file_path,
        from_ts_code,
        dataset_number,
        loader_method,
        usecols=usecols,
        dtype=dtype,
        metadata=_run_dataset_metadata(paths_csv, dataset_number),
    )
    return {from_ts_code: frame}


def _merge_dataset_maps(
    target: Dict[str, pd.DataFrame],
    addition: Dict[str, pd.DataFrame],
    *,
    suffix: str,
) -> None:
    for key, frame in addition.items():
        out_key = key
        if out_key in target:
            out_key = f"{key}#{suffix}"
        target[out_key] = frame


def _load_run_manifest_by_number(
    manifest_number: str,
    paths_csv: Path,
    manifests_csv: Path,
    *,
    usecols=None,
    dtype=None,
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
                by_ts = _load_run_datasets_by_number(
                    member_dataset_id,
                    paths_csv,
                    usecols=usecols,
                    dtype=dtype,
                    loader_method="load_manifest",
                )
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

def _run_dataset_metadata(paths_csv: Path, number: str) -> Dict[str, Any]:
    path = paths_csv.with_name("paths_dataset_meta.json")
    if not path.is_file():
        return {}
    with path.open("r", encoding="utf-8") as handle:
        document = json.load(handle)
    return (document.get("datasets") or {}).get(str(number)) or {}


def _file_metadata(path: Path) -> Dict[str, Any]:
    for candidate in (path.with_suffix(".meta.json"), path.parent / "meta.json"):
        if candidate.is_file():
            with candidate.open("r", encoding="utf-8") as handle:
                return json.load(handle)
    return {}


def _project_frame(frame: pd.DataFrame, usecols=None, dtype=None) -> pd.DataFrame:
    source_columns = list(frame.columns)
    if usecols is not None:
        selected = [column for column in frame.columns if usecols(column)] if callable(usecols) else list(usecols)
        frame = frame.loc[:, selected]
    if dtype is not None:
        effective = {key: value for key, value in dtype.items() if key in frame.columns} if isinstance(dtype, dict) else dtype
        frame = frame.astype(effective)
    frame.attrs["_af_source_columns"] = source_columns
    return frame


def _parquet_reader(con, path: Path, usecols):
    relation = con.read_parquet(str(path))
    columns = relation.columns
    if usecols is None:
        return relation, columns
    selected = [column for column in columns if usecols(column)] if callable(usecols) else list(usecols)
    missing = [column for column in selected if column not in columns]
    if missing:
        raise KeyError(f"columns not found: {missing}")
    if selected:
        projection = ",".join('"' + column.replace('"', '""') + '"' for column in selected)
        relation = relation.project(projection)
    return relation, columns


def read_dataset_file(path: Path, metadata=None, *, usecols=None, dtype=None) -> pd.DataFrame:
    """按登记格式读取表格；JSON记录中的字典和列表保持嵌套，不自动展开。"""
    path = Path(path)
    metadata = _file_metadata(path) if metadata is None else metadata
    fmt = str(metadata.get("format") or path.suffix.lstrip(".")).lower()
    if fmt == "csv":
        return pd.read_csv(path, usecols=usecols, dtype=dtype)
    if fmt == "parquet":
        import duckdb
        with duckdb.connect() as con:
            relation, source_columns = _parquet_reader(con, path, usecols)
            frame = relation.fetchdf()
        frame = _project_frame(frame, usecols, dtype)
        frame.attrs["_af_source_columns"] = source_columns
        return frame
    if fmt != "json":
        raise ValueError(f"unsupported dataset format: {fmt!r}")
    with path.open("r", encoding="utf-8") as handle:
        records = json.load(handle)
    records_path = metadata.get("recordsPath")
    if records_path is not None:
        if not isinstance(records_path, str) or not records_path or not isinstance(records, dict) or records_path not in records:
            raise ValueError("JSON recordsPath must name an existing top-level record array")
        records = records[records_path]
        if not isinstance(records, list):
            raise ValueError("JSON recordsPath must contain an array of objects")
    elif isinstance(records, dict):
        records = [records]
    if not isinstance(records, list) or any(not isinstance(record, dict) for record in records):
        raise ValueError("tabular JSON must be an object or an array of objects; use json.load for other JSON values")
    frame = pd.DataFrame.from_records(records) if records else pd.DataFrame(columns=metadata.get("columns") or [])
    return _project_frame(frame, usecols, dtype)


def _read_csv(path: Path, dataset_number: str, loader_method: str, *, usecols=None, dtype=None, metadata=None) -> pd.DataFrame:
    frame = read_dataset_file(path, metadata, usecols=usecols, dtype=dtype)
    total_columns = frame.attrs.get("_af_source_columns")
    if total_columns is None:
        total_columns = (metadata or _file_metadata(path)).get("columns")
    # JSON/Parquet不重新按CSV读取表头；无元数据时从实际表格取得列数。
    if total_columns is None and path.suffix.lower() != ".csv":
        total_columns = frame.attrs.get("_af_source_columns", list(frame.columns))
    _append_loader_metric(path, dataset_number, loader_method, frame.columns, usecols, total_columns=total_columns)
    return frame


def _iter_csv(path: Path, dataset_number: str, loader_method: str, chunksize: int, *, usecols=None, dtype=None, metadata=None) -> Iterator[pd.DataFrame]:
    if chunksize <= 0:
        raise ValueError("chunksize must be a positive number of complete rows")
    metadata = _file_metadata(path) if metadata is None else metadata
    fmt = str(metadata.get("format") or path.suffix.lstrip(".")).lower()
    if fmt == "json":
        # 普通JSON需解析完整文档；分块只限制每次返回的行数，不宣称流式解析。
        frame = _read_csv(path, dataset_number, loader_method, usecols=usecols, dtype=dtype, metadata=metadata)
        for start in range(0, len(frame), chunksize):
            yield frame.iloc[start:start + chunksize].copy()
        return
    if fmt == "parquet":
        import duckdb
        with duckdb.connect() as con:
            cursor, source_columns = _parquet_reader(con, path, usecols)
            columns = cursor.columns
            opened = False
            while rows := cursor.fetchmany(chunksize):
                chunk = _project_frame(pd.DataFrame.from_records(rows, columns=columns), usecols, dtype)
                if not opened:
                    _append_loader_metric(path, dataset_number, loader_method, chunk.columns, usecols, total_columns=source_columns)
                    opened = True
                yield chunk
            if not opened:
                frame = _project_frame(pd.DataFrame(columns=columns), usecols, dtype)
                _append_loader_metric(path, dataset_number, loader_method, frame.columns, usecols, total_columns=source_columns)
        return
    if fmt != "csv":
        raise ValueError(f"unsupported dataset format: {fmt!r}")
    opened = False
    for chunk in pd.read_csv(path, usecols=usecols, dtype=dtype, chunksize=chunksize):
        if not opened:
            _append_loader_metric(path, dataset_number, loader_method, chunk.columns, usecols)
            opened = True
        yield chunk
    if not opened:
        columns = pd.read_csv(path, nrows=0, usecols=usecols, dtype=dtype).columns
        _append_loader_metric(path, dataset_number, loader_method, columns, usecols)


def _append_loader_metric(
    path: Path,
    dataset_number: str,
    loader_method: str,
    selected_columns,
    requested_usecols,
    *,
    total_columns=None,
) -> None:
    metrics_path = os.getenv("AF_TASK_METRICS_PATH", "").strip()
    if not metrics_path:
        return
    try:
        if total_columns is None:
            total_columns = list(pd.read_csv(path, nrows=0).columns)
        selected = list(selected_columns)
        payload = {
            "schema_version": "loader_metric_v1",
            "datasetNumber": str(dataset_number),
            "pathHash": "sha256:" + hashlib.sha256(str(path.resolve()).encode("utf-8")).hexdigest(),
            "logicalBytes": path.stat().st_size,
            "openCount": 1,
            "loaderMethod": loader_method,
            "selectedColumnCount": len(selected),
            "totalColumnCount": len(total_columns),
            "timeMillis": int(time.time() * 1000),
        }
        target = Path(metrics_path)
        target.parent.mkdir(parents=True, exist_ok=True)
        with target.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(payload, separators=(",", ":")) + "\n")
    except Exception:
        # Usage attribution must never replace the user's script result.
        return

def load_datasets(
    dataset_id: str,
    input_root: str = "/sandbox/input",
    data_dir: str | None = None,
    *,
    usecols=None,
    dtype=None,
) -> Dict[str, pd.DataFrame]:
    """Load one manifest or atomic dataset as dict[ts_code, DataFrame].

    Run-level mode: if /sandbox/paths_dataset.csv or /sandbox/path_manifest.csv
    exists and dataset_id is a positive integer, resolve the real file path from
    the run-level index. Legacy mode: fall back to /sandbox/input/<dataset_id>/
    structure.
    """
    root = Path(input_root)

    # Run-level fast path
    run_level_ids = _split_run_level_ids(dataset_id)
    if run_level_ids and all(_looks_like_run_level_id(item) for item in run_level_ids) and _is_run_level_mode(root):
        paths_csv, _ = _run_level_csv_paths(root)
        result: Dict[str, pd.DataFrame] = {}
        for number in run_level_ids:
            _merge_dataset_maps(
                result,
                _load_run_datasets_by_number(
                    number, paths_csv, usecols=usecols, dtype=dtype
                ),
                suffix=number,
            )
        return result

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
            result[ts_code] = _read_atomic_csv(
                root, member_id, ts_code, usecols=usecols, dtype=dtype
            )
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
            result[ts_code] = _read_atomic_csv(
                root, member_id, ts_code, usecols=usecols, dtype=dtype
            )
        return result

    frame = _read_atomic_csv(root, dataset_id, usecols=usecols, dtype=dtype)
    ts_code = str(frame["ts_code"].iloc[0]) if "ts_code" in frame.columns and not frame.empty else dataset_id
    return {ts_code: frame}


def load_manifest(
    manifest_id: str,
    input_root: str = "/sandbox/input",
    data_dir: str | None = None,
    *,
    usecols=None,
    dtype=None,
) -> DatasetLoadResult:
    """Concat ready manifest members into one DataFrame with ts_code column.

    Run-level mode: if /sandbox/path_manifest.csv or /sandbox/paths_dataset.csv
    exists and manifest_id is a positive integer, resolve the manifest JSON from
    the run-level index and load member datasets via /sandbox/paths_dataset.csv.
    Legacy mode: fall back to /sandbox/input/<manifest_id>/ structure.
    """
    root = Path(input_root)

    # Run-level fast path
    run_level_ids = _split_run_level_ids(manifest_id)
    if run_level_ids and all(_looks_like_run_level_id(item) for item in run_level_ids) and _is_run_level_mode(root):
        paths_csv, manifests_csv = _run_level_csv_paths(root)
        results = [
            _load_run_manifest_by_number(
                number, paths_csv, manifests_csv, usecols=usecols, dtype=dtype
            )
            for number in run_level_ids
        ]
        frames = [result.frame for result in results if not result.frame.empty]
        frame = pd.concat(frames, ignore_index=True) if frames else pd.DataFrame()
        failed_members: List[Dict[str, Any]] = []
        skipped_members: List[Dict[str, Any]] = []
        for result in results:
            failed_members.extend(result.failed_members)
            skipped_members.extend(result.skipped_members)
        return DatasetLoadResult(
            frame,
            failed_members=failed_members,
            skipped_members=skipped_members,
        )

    by_ts = load_datasets(
        manifest_id,
        input_root=input_root,
        data_dir=data_dir,
        usecols=usecols,
        dtype=dtype,
    )
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


def iter_datasets(
    dataset_id: str,
    chunksize: int,
    input_root: str = "/sandbox/input",
    *,
    usecols=None,
    dtype=None,
) -> Iterator[pd.DataFrame]:
    """返回完整行分块；CSV/Parquet流式读取，普通JSON先解析完整文档。"""
    if chunksize <= 0:
        raise ValueError("chunksize must be a positive number of complete rows")
    root = Path(input_root)
    run_level_ids = _split_run_level_ids(dataset_id)
    if run_level_ids and all(_looks_like_run_level_id(item) for item in run_level_ids) and _is_run_level_mode(root):
        paths_csv, _ = _run_level_csv_paths(root)
        paths_df = _load_run_level_dataset_index(paths_csv)
        for number in run_level_ids:
            file_path = _resolve_run_dataset_file_path(number, paths_df)
            if file_path is None:
                raise FileNotFoundError(f"run-level dataset #{number} not found in {paths_csv}")
            row = paths_df[paths_df["agent_run_dataset_id"].astype(str) == number].iloc[0]
            from_ts_code = str(row.get("from_ts_code") or "")
            if not from_ts_code or from_ts_code.upper() == "UNCERTAIN":
                from_ts_code = number
            for chunk in _iter_csv(
                Path(file_path), number, "iter_datasets", chunksize, usecols=usecols, dtype=dtype,
                metadata=_run_dataset_metadata(paths_csv, number)
            ):
                if "ts_code" not in chunk.columns:
                    chunk = chunk.copy()
                    chunk.insert(0, "ts_code", from_ts_code)
                yield chunk
        return

    for frame in load_datasets(
        dataset_id, input_root=input_root, usecols=usecols, dtype=dtype
    ).values():
        for start in range(0, len(frame), chunksize):
            yield frame.iloc[start:start + chunksize].copy()


def iter_manifest_chunks(
    manifest_id: str,
    chunksize: int,
    input_root: str = "/sandbox/input",
    *,
    usecols=None,
    dtype=None,
) -> Iterator[pd.DataFrame]:
    """Yield complete-row chunks for each ready member of a run-level manifest."""
    if chunksize <= 0:
        raise ValueError("chunksize must be a positive number of complete rows")
    root = Path(input_root)
    run_level_ids = _split_run_level_ids(manifest_id)
    if run_level_ids and all(_looks_like_run_level_id(item) for item in run_level_ids) and _is_run_level_mode(root):
        _, manifests_csv = _run_level_csv_paths(root)
        manifests_df = _load_run_level_manifest_index(manifests_csv)
        for number in run_level_ids:
            manifest_path = _resolve_run_manifest_file_path(number, manifests_df)
            if manifest_path is None:
                raise FileNotFoundError(f"run-level manifest #{number} not found in {manifests_csv}")
            with Path(manifest_path).open("r", encoding="utf-8") as handle:
                document = json.load(handle)
            for member in document.get("members") or []:
                if isinstance(member, dict) and str(member.get("status") or "").lower() == "ready":
                    member_number = str(member.get("datasetId") or "")
                    if member_number:
                        yield from iter_datasets(
                            member_number,
                            chunksize,
                            input_root=input_root,
                            usecols=usecols,
                            dtype=dtype,
                        )
        return

    result = load_manifest(
        manifest_id, input_root=input_root, usecols=usecols, dtype=dtype
    ).frame
    for start in range(0, len(result), chunksize):
        yield result.iloc[start:start + chunksize].copy()


def load_read_profile(
    dataset_id: str,
    profile: str,
    input_root: str = "/sandbox/input",
    *,
    manifest: bool = False,
):
    """Load a run-level dataset/manifest using a named profile from its public metadata file."""
    root = Path(input_root)
    sandbox_root = root.parent
    metadata_path = sandbox_root / ("path_manifest_meta.json" if manifest else "paths_dataset_meta.json")
    with metadata_path.open("r", encoding="utf-8") as handle:
        document = json.load(handle)
    collection = document.get("manifests" if manifest else "datasets") or {}
    metadata = collection.get(str(dataset_id)) or {}
    profiles: Mapping[str, List[str]] = metadata.get("readProfiles") or {}
    usecols = profiles.get(profile)
    if not usecols:
        raise KeyError(f"read profile {profile!r} not found for run-level id {dataset_id}")
    recommended_dtype: Mapping[str, str] = metadata.get("recommendedDtype") or {}
    dtype = {column: value for column, value in recommended_dtype.items() if column in usecols}
    if manifest:
        return load_manifest(dataset_id, input_root=input_root, usecols=usecols, dtype=dtype)
    return load_datasets(dataset_id, input_root=input_root, usecols=usecols, dtype=dtype)
