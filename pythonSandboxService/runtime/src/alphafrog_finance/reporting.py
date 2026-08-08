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
  * ``report_custom()`` optionally carries the COMPLETE method triple
    (method_id + method_version + spec_digest), all-or-none: any partial
    combination raises ValueError and emits nothing; the triple never changes
    the CUSTOM_* evidence levels (contract §4.3);
  * all v1 field bounds (non-empty source, entry counts, UTF-8 byte lengths)
    are enforced BEFORE emit so the library never emits a record that the
    Java v1 schema check would reject as a whole batch (contract §7, §9);
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

# Pre-emit v1 schema limits (codex must-fix 0c147646 ITEM 3). Contract §4.3
# (frozen at CONTRACT_BASE_SHA 7c695371) requires ``unit`` to be a non-empty
# string and declares ``inputRefs`` a bounded string array and ``checks`` a
# bounded object; contract §7 validation step 6 rejects every record whose
# JSON violates the v1 field rules, and per the §9 failure matrix a single
# schema-invalid record makes the WHOLE batch unpresentable. The report
# functions therefore reject these cases BEFORE emitting, instead of leaving
# obviously invalid records for the Java side to reject as a whole batch.
# The concrete numbers are the B-gate guard values mandated by the cross-
# module review: 128 mirrors the frozen batch record cap (recordChannelMaxRecords,
# contract §13 / python-sandbox config); string lengths are measured in UTF-8
# bytes because the channel accounts rawPayload bytes (contract §4.2). These
# guards must be reconciled with work package E's record schema validator
# (agentPlatformShared .../finance/records/metric-record-v1.schema.json, Spec
# §9.2) once it is delivered.
_MAX_INPUT_REFS = 128
_MAX_INPUT_REF_BYTES = 512
_MAX_FORMULA_DESCRIPTION_BYTES = 4096
_MAX_PARAMETER_ENTRIES = 128
_MAX_CHECK_ENTRIES = 128
_MAX_UNIT_BYTES = 128
_MAX_ENVIRONMENT_ID_BYTES = 512

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
    """Read the environment id from the read-only task environment file.

    The file key is snake_case ``environment_id`` (work package D's
    ExecutionEnvironment schema); it is emitted as the camelCase record
    field ``environmentId``. Raises RuntimeError when the file is
    missing/unreadable or lacks the field: the sandbox must always provide
    it (single source of truth, work package D), and emitting a record
    without environmentId would only produce a schema-invalid record
    downstream.
    """
    path = _runtime_environment_path()
    try:
        with open(path, encoding="utf-8") as fh:
            data = json.load(fh)
    except (OSError, ValueError) as exc:
        raise RuntimeError(
            f"cannot read runtime environment file {path!r}: {exc}"
        ) from None
    # The file is written by work package D's runtime_environment.py
    # (single-source ExecutionEnvironment.model_dump()), which uses
    # snake_case keys; the emitted record field is camelCase environmentId.
    environment_id = data.get("environment_id") if isinstance(data, dict) else None
    if not isinstance(environment_id, str) or not environment_id:
        raise RuntimeError(
            f"runtime environment file {path!r} lacks a usable environment_id"
        )
    _validate_byte_length(
        "environmentId", environment_id, _MAX_ENVIRONMENT_ID_BYTES
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


def _validate_byte_length(name: str, value: str, limit: int) -> None:
    """Raise ValueError when the UTF-8 byte length of value exceeds limit.

    Contract §4.2 accounts records in rawPayload UTF-8 bytes, so all string
    bounds here are byte limits, not code-point counts.
    """
    size = len(value.encode("utf-8"))
    if size > limit:
        raise ValueError(
            f"{name} must not exceed {limit} UTF-8 bytes, got {size}"
        )


def _validated_source(source_resolver_tool_call_id: Optional[str]) -> None:
    """A provided source association must be a non-empty string: an empty
    ``sourceResolverToolCallId`` would be schema-invalid (contract §4.3)."""
    if source_resolver_tool_call_id is None:
        return
    if not isinstance(source_resolver_tool_call_id, str) or not source_resolver_tool_call_id.strip():
        raise ValueError(
            "source_resolver_tool_call_id must be a non-empty string when "
            f"provided, got {source_resolver_tool_call_id!r}"
        )


def _validated_input_refs(input_refs: Sequence[str]) -> List[str]:
    refs = list(input_refs)
    if len(refs) > _MAX_INPUT_REFS:
        raise ValueError(
            f"input_refs must have at most {_MAX_INPUT_REFS} entries, got {len(refs)}"
        )
    for i, ref in enumerate(refs):
        if not isinstance(ref, str):
            raise ValueError(f"input_refs[{i}] must be a string, got {type(ref).__name__}")
        _validate_byte_length(f"input_refs[{i}]", ref, _MAX_INPUT_REF_BYTES)
    return refs


def _validated_parameters(parameters: Optional[Mapping[str, Any]]) -> dict:
    params = dict(parameters or {})
    if len(params) > _MAX_PARAMETER_ENTRIES:
        raise ValueError(
            f"parameters must have at most {_MAX_PARAMETER_ENTRIES} entries, "
            f"got {len(params)}"
        )
    return params


def _validated_checks(checks: Mapping[str, Any]) -> dict:
    result = {str(k): bool(v) for k, v in dict(checks).items()}
    if len(result) > _MAX_CHECK_ENTRIES:
        raise ValueError(
            f"checks must have at most {_MAX_CHECK_ENTRIES} entries, "
            f"got {len(result)}"
        )
    return result


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
        ValueError: on non-finite value, empty/over-long unit, empty source
            association, or any field exceeding the pre-emit v1 schema limits.
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
    # All v1 schema bounds are enforced BEFORE anything is emitted: a single
    # schema-invalid record would make the whole batch unpresentable on the
    # Java side (contract §7 step 6, §9 failure matrix).
    _validate_byte_length("unit", result.unit, _MAX_UNIT_BYTES)
    _validated_source(source_resolver_tool_call_id)
    parameters = _validated_parameters(result.parameters)
    refs = _validated_input_refs(input_refs)
    checks = _validated_checks(result.checks)
    environment_id = _environment_id()

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
            ("environmentId", environment_id),
            ("value", float(result.value)),
            ("unit", result.unit),
            ("parameters", parameters),
            ("inputRefs", refs),
            ("checks", checks),
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
    method_id: Optional[str] = None,
    method_version: Optional[str] = None,
    spec_digest: Optional[str] = None,
) -> str:
    """Emit a custom-formula record line and return it.

    The Python parameter ``output_unit`` serializes to the record field
    ``unit`` (contract §4.3). Evidence is ``CUSTOM_WITH_CHECKS`` when
    ``checks`` is non-empty, otherwise ``CUSTOM_UNVERIFIED``; providing a
    ``source_resolver_tool_call_id`` — or a method triple — never upgrades
    evidence.

    Method linkage (contract §4.3): a custom record may carry no resolver
    association at all, only ``sourceResolverToolCallId``, or the COMPLETE
    method triple (``method_id`` + ``method_version`` + ``spec_digest``) to
    reference one resolution snapshot exactly. The triple is therefore
    all-or-none: exactly 0 or exactly 3 of the three optional parameters may
    be present; any partial combination raises ValueError and no record is
    emitted. The triple never changes the CUSTOM_* evidence levels, and the
    Java side never guesses a missing partial tuple.

    Raises:
        ValueError: on non-finite value, empty formula_description, empty
            output_unit, a partial method triple, an empty source
            association, or any field exceeding the pre-emit v1 schema limits.
        RuntimeError: if the runtime environment file is unavailable.
    """
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValueError(f"value must be a number, got {type(value).__name__}")
    if not check_finite([float(value)]):
        raise ValueError(f"value must be finite, got {value!r}")
    if not isinstance(formula_description, str) or not formula_description.strip():
        raise ValueError("formula_description must be a non-empty string")
    _validate_byte_length(
        "formula_description", formula_description, _MAX_FORMULA_DESCRIPTION_BYTES
    )
    if not check_unit(output_unit):
        raise ValueError(f"output_unit must be a non-empty string, got {output_unit!r}")
    _validate_byte_length("output_unit", output_unit, _MAX_UNIT_BYTES)

    # ALL-OR-NONE method triple (contract §4.3): a partial tuple cannot be
    # associated, so it is rejected before anything is emitted.
    triple = (method_id, method_version, spec_digest)
    triple_present = [item for item in triple if item is not None]
    has_triple = bool(triple_present)
    if has_triple and len(triple_present) != 3:
        raise ValueError(
            "method_id, method_version and spec_digest must be provided "
            "together (all three) or not at all"
        )
    if has_triple:
        for name, item in (
            ("method_id", method_id),
            ("method_version", method_version),
            ("spec_digest", spec_digest),
        ):
            if not isinstance(item, str) or not item.strip():
                raise ValueError(
                    f"{name} must be a non-empty string when the method "
                    f"triple is provided, got {item!r}"
                )

    # Pre-emit v1 schema bounds (contract §7 step 6, §9 failure matrix).
    _validated_source(source_resolver_tool_call_id)
    parameters_dict = _validated_parameters(parameters)
    refs = _validated_input_refs(input_refs)
    checks_dict = _validated_checks(checks or {})
    environment_id = _environment_id()

    evidence = EVIDENCE_CUSTOM_WITH_CHECKS if checks_dict else EVIDENCE_CUSTOM_UNVERIFIED

    fields: List[Tuple[str, Any]] = [("schemaVersion", SCHEMA_VERSION)]
    if has_triple:
        # Field order mirrors the library record (contract §4.3 example):
        # schemaVersion, methodId, methodVersion, specDigest, then source.
        fields.extend(
            [
                ("methodId", method_id),
                ("methodVersion", method_version),
                ("specDigest", spec_digest),
            ]
        )
    if source_resolver_tool_call_id is not None:
        fields.append(("sourceResolverToolCallId", source_resolver_tool_call_id))
    fields.extend(
        [
            ("environmentId", environment_id),
            ("value", float(value)),
            ("unit", output_unit),
            ("parameters", parameters_dict),
            ("inputRefs", refs),
            ("checks", checks_dict),
            ("formulaDescription", formula_description),
            ("evidence", evidence),
        ]
    )
    return _emit(fields)
