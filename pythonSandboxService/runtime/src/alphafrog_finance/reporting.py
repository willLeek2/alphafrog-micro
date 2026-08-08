# === work-package-B (ccqwen) ===
"""Marker-line reporting for alphafrog_finance — the SOLE generator of
``__AF_FINANCE_RESULT_v1__`` lines in the library (Spec §6, contract §4).

Both ``report()`` and ``report_custom()`` share one encoder and one marker
prefix (contract §4.1): each call writes a single line
``__AF_FINANCE_RESULT_v1__`` + single-line JSON to stdout and returns it.

Field rules (contract §4.3, frozen at CONTRACT_BASE_SHA 7c695371):
  * ``schemaVersion="1"`` lives in each record JSON, never in channel metadata;
  * ``report()`` auto-fills the full method triple (methodId/methodVersion/
    specDigest) from the canonical JSON installed with the package — callers
    can never override it; evidence is always ``LIBRARY_CALL_DECLARED``;
  * ``report_custom()`` requires formula_description/input_refs/output_unit
    (plus value); non-empty ``checks`` -> ``CUSTOM_WITH_CHECKS``, otherwise
    ``CUSTOM_UNVERIFIED``; a source association can never upgrade evidence;
  * ``sourceResolverToolCallId`` is emitted only when provided (camelCase);
  * ``environmentId`` comes from the read-only task environment file written
    by the single source of truth ``runtime_environment.py`` (work package D);
  * no legacy field names (adviceId/adviceDurable etc., contract §4.1);
  * serialization is UTF-8 with compact separators (ensure_ascii=False), which
    keeps payload bytes — and therefore rawDigest/recordDigest — deterministic.
"""
from __future__ import annotations

import json
import math
import os
from typing import Any, List, Mapping, Optional, Sequence, Tuple

from .checks import check_finite, check_unit
from .models import FinanceMetricResult

MARKER = "__AF_FINANCE_RESULT_v1__"
SCHEMA_VERSION = "1"

EVIDENCE_LIBRARY = "LIBRARY_CALL_DECLARED"
EVIDENCE_CUSTOM_WITH_CHECKS = "CUSTOM_WITH_CHECKS"
EVIDENCE_CUSTOM_UNVERIFIED = "CUSTOM_UNVERIFIED"

# The wrapper (work package C) sets AF_RUNTIME_ENVIRONMENT_FILE to the task's
# read-only environment file before executing user code. The fallback path is
# a placeholder for direct/local use; in the sandbox the env var always wins.
_RUNTIME_ENV_VAR = "AF_RUNTIME_ENVIRONMENT_FILE"
_DEFAULT_RUNTIME_ENVIRONMENT_PATH = "/sandbox/runtime-environment.json"

# Installed with the package by the build step (scripts/generate_method_bindings.py
# reads A's canonical JSON; hand-copied triples are forbidden, Spec §6).
_METHOD_SPECS_PATH = os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "_generated", "method_specs.json"
)

_METHOD_SPECS_CACHE: Optional[Mapping[str, Mapping[str, Any]]] = None


def _runtime_environment_path() -> str:
    return os.environ.get(_RUNTIME_ENV_VAR, _DEFAULT_RUNTIME_ENVIRONMENT_PATH)


def _environment_id() -> str:
    """Read ``environmentId`` from the read-only task environment file.

    Raises RuntimeError when the file is missing/unreadable or lacks the
    field: the sandbox must always provide it (single source of truth,
    work package D), and emitting a record without environmentId would only
    produce a schema-invalid record downstream.
    """
    path = _runtime_environment_path()
    try:
        with open(path, encoding="utf-8") as fh:
            data = json.load(fh)
    except (OSError, ValueError) as exc:
        raise RuntimeError(
            f"cannot read runtime environment file {path!r}: {exc}"
        ) from None
    environment_id = data.get("environmentId") if isinstance(data, dict) else None
    if not isinstance(environment_id, str) or not environment_id:
        raise RuntimeError(
            f"runtime environment file {path!r} lacks a usable environmentId"
        )
    return environment_id


def _method_specs() -> Mapping[str, Mapping[str, Any]]:
    """methodId -> {"methodVersion": str, "specDigest": str}, loaded once."""
    global _METHOD_SPECS_CACHE
    if _METHOD_SPECS_CACHE is None:
        try:
            with open(_METHOD_SPECS_PATH, encoding="utf-8") as fh:
                payload = json.load(fh)
        except (OSError, ValueError) as exc:
            raise RuntimeError(
                "canonical method specs are not installed "
                f"({_METHOD_SPECS_PATH}): {exc}; they are generated at build "
                "time by generate_method_bindings.py and must not be "
                "hand-copied"
            ) from None
        methods = payload.get("methods") if isinstance(payload, dict) else None
        if not isinstance(methods, dict):
            raise RuntimeError(
                f"malformed canonical method specs at {_METHOD_SPECS_PATH}"
            )
        _METHOD_SPECS_CACHE = methods
    return _METHOD_SPECS_CACHE


def _encode_record(fields: Sequence[Tuple[str, Any]]) -> str:
    """MARKER + compact single-line JSON preserving the given field order."""
    payload = json.dumps(dict(fields), ensure_ascii=False, separators=(",", ":"))
    return MARKER + payload


def _emit(fields: Sequence[Tuple[str, Any]]) -> str:
    line = _encode_record(fields)
    print(line, flush=True)
    return line


def _validated_input_refs(input_refs: Sequence[str]) -> List[str]:
    refs = list(input_refs)
    for i, ref in enumerate(refs):
        if not isinstance(ref, str):
            raise ValueError(f"input_refs[{i}] must be a string, got {type(ref).__name__}")
    return refs


def _validated_checks(checks: Mapping[str, Any]) -> dict:
    return {str(k): bool(v) for k, v in dict(checks).items()}


def report(
    result: FinanceMetricResult,
    *,
    input_refs: Sequence[str] = (),
    source_resolver_tool_call_id: Optional[str] = None,
) -> str:
    """Emit a library-method record line for ``result`` and return it.

    The method triple is auto-filled from the installed canonical JSON keyed
    by ``result.method_id``; callers cannot override it. Evidence is always
    ``LIBRARY_CALL_DECLARED``.

    Raises:
        TypeError: if result is not a FinanceMetricResult.
        ValueError: on non-finite value or empty unit.
        RuntimeError: if the method triple is not in the canonical JSON, or
            the runtime environment file is unavailable.
    """
    if not isinstance(result, FinanceMetricResult):
        raise TypeError(f"result must be a FinanceMetricResult, got {type(result).__name__}")
    if not isinstance(result.value, (int, float)) or isinstance(result.value, bool):
        raise ValueError(f"value must be a number, got {type(result.value).__name__}")
    if not check_finite([float(result.value)]):
        raise ValueError(f"value must be finite, got {result.value!r}")
    if not check_unit(result.unit):
        raise ValueError(f"unit must be a non-empty string, got {result.unit!r}")
    if source_resolver_tool_call_id is not None and not isinstance(
        source_resolver_tool_call_id, str
    ):
        raise ValueError("source_resolver_tool_call_id must be a string or None")

    spec = _method_specs().get(result.method_id)
    if not isinstance(spec, Mapping) or "methodVersion" not in spec or "specDigest" not in spec:
        raise RuntimeError(
            f"method {result.method_id!r} is not present in the canonical "
            "method specs; report() never hand-fills the triple"
        )

    fields: List[Tuple[str, Any]] = [
        ("schemaVersion", SCHEMA_VERSION),
        ("methodId", result.method_id),
        ("methodVersion", spec["methodVersion"]),
        ("specDigest", spec["specDigest"]),
    ]
    if source_resolver_tool_call_id is not None:
        fields.append(("sourceResolverToolCallId", source_resolver_tool_call_id))
    fields.extend(
        [
            ("environmentId", _environment_id()),
            ("value", float(result.value)),
            ("unit", result.unit),
            ("parameters", dict(result.parameters)),
            ("inputRefs", _validated_input_refs(input_refs)),
            ("checks", _validated_checks(result.checks)),
            ("evidence", EVIDENCE_LIBRARY),
        ]
    )
    return _emit(fields)


def report_custom(
    value: float,
    *,
    formula_description: str,
    input_refs: Sequence[str],
    output_unit: str,
    parameters: Optional[Mapping[str, Any]] = None,
    checks: Optional[Mapping[str, Any]] = None,
    source_resolver_tool_call_id: Optional[str] = None,
) -> str:
    """Emit a custom-formula record line and return it.

    The Python parameter ``output_unit`` serializes to the record field
    ``unit`` (contract §4.3). Evidence is ``CUSTOM_WITH_CHECKS`` when
    ``checks`` is non-empty, otherwise ``CUSTOM_UNVERIFIED``; providing a
    ``source_resolver_tool_call_id`` never upgrades evidence.

    Raises:
        ValueError: on non-finite value, empty formula_description, or empty
            output_unit.
        RuntimeError: if the runtime environment file is unavailable.
    """
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValueError(f"value must be a number, got {type(value).__name__}")
    if not check_finite([float(value)]):
        raise ValueError(f"value must be finite, got {value!r}")
    if not isinstance(formula_description, str) or not formula_description.strip():
        raise ValueError("formula_description must be a non-empty string")
    if not check_unit(output_unit):
        raise ValueError(f"output_unit must be a non-empty string, got {output_unit!r}")
    if source_resolver_tool_call_id is not None and not isinstance(
        source_resolver_tool_call_id, str
    ):
        raise ValueError("source_resolver_tool_call_id must be a string or None")

    checks_dict = _validated_checks(checks or {})
    evidence = EVIDENCE_CUSTOM_WITH_CHECKS if checks_dict else EVIDENCE_CUSTOM_UNVERIFIED

    fields: List[Tuple[str, Any]] = [("schemaVersion", SCHEMA_VERSION)]
    if source_resolver_tool_call_id is not None:
        fields.append(("sourceResolverToolCallId", source_resolver_tool_call_id))
    fields.extend(
        [
            ("environmentId", _environment_id()),
            ("value", float(value)),
            ("unit", output_unit),
            ("parameters", dict(parameters or {})),
            ("inputRefs", _validated_input_refs(input_refs)),
            ("checks", checks_dict),
            ("formulaDescription", formula_description),
            ("evidence", evidence),
        ]
    )
    return _emit(fields)
