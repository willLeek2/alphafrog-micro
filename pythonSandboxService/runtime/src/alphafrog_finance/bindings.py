# === work-package-B (ccqwen) ===
"""Method bindings for alphafrog_finance — identity from A-canonical generated
bindings (Spec §6 registry swap, codex 0c147646/97ea103a).

This module is the SOLE runtime source of method identity. It replaces the
former interim hard-coded registry in ``metrics.py``: hand-maintained method
identity is forbidden (Spec §6). Identity is assembled lazily (first use) and
FAIL CLOSED (``RuntimeError``) from the three generated build products that
``runtime/scripts/generate_method_bindings.py`` installs into
``alphafrog_finance/_generated/``:

  * ``method_specs.json`` -- methodId -> {methodVersion, specDigest} (the same
    location ``reporting.py`` reads; this module uses its own loader);
  * ``docstrings.py``     -- embedded document with displayName, definition,
    calculationExpression, parameterTable, and the verbatim libraryBinding;
  * ``call_samples.py``   -- embedded document with function, callSample, and
    narrativeTemplate.

Assembly gates (any violation raises ``RuntimeError``; nothing is cached):
  * the methodId sets of all three documents must be EXACTLY equal, both
    directions (a missing or extra method in any document fails);
  * every specDigest must match the frozen canonical shape
    ``sha256:<64 lowercase hex>`` (a tampered/malformed digest fails closed);
  * every binding.package must equal ``alphafrog_finance``;
  * every binding.function must resolve to a real callable attribute of
    ``alphafrog_finance.metrics``;
  * SIGNATURE COMPATIBILITY: for EVERY canonical parameter name its
    snake_case form (camelCase -> snake_case by inserting ``_`` before each
    uppercase letter then lowercasing) must be a parameter of that function —
    a missing parameter or unknown function fails closed (this is the Spec §6
    libraryBinding function/signature gate that makes a bad binding fail the
    image build when the build smoke-imports it);
  * ``alphafrog_finance.__version__`` must satisfy every binding's
    apiCompatRange.

Import-cycle care: this module imports ``metrics`` lazily INSIDE assembly, and
``metrics`` imports this module lazily INSIDE its lookup function; neither
imports the other at module top level.

The public seam is deliberately narrow — ``get_binding``, ``list_bindings``,
``method_id_for_function`` — and there is NO public path to inject or override
a method identity. The ``_GENERATED_DIR`` / ``_BINDINGS_CACHE`` /
``_FUNCTION_INDEX`` module attributes are private test seams only.
"""
from __future__ import annotations

import importlib.util
import inspect
import json
import os
import re
from dataclasses import dataclass
from typing import Any, Callable, Dict, Mapping, Optional, Tuple

__all__ = ["MethodBinding", "get_binding", "list_bindings", "method_id_for_function"]

# The generated build products live next to this module, in the same
# ``_generated`` directory that ``reporting.py`` reads method_specs.json from.
_GENERATED_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_generated")

# Private lazy-assembly caches (test seams). ``None`` => not yet assembled.
_BINDINGS_CACHE: Optional[Dict[str, "MethodBinding"]] = None
_FUNCTION_INDEX: Optional[Dict[str, str]] = None

# Spec §6: the only package a generated binding may target.
_PACKAGE_NAME = "alphafrog_finance"

# apiCompatRange grammar: ">=<lower>,<<upper>" (simple numeric-tuple compare).
_API_RANGE_RE = re.compile(r"^>=([^,]+),<(.+)$")

# Build-artifact drift guard: a generated specDigest must stay in the frozen
# canonical shape (a tampered/malformed digest fails closed).
_SPEC_DIGEST_RE = re.compile(r"^sha256:[0-9a-f]{64}$")


@dataclass(frozen=True)
class MethodBinding:
    """Immutable binding between a canonical method and its library function.

    Attributes:
        method_id: canonical methodId (e.g. ``finance.growth.cagr``).
        method_version: canonical methodVersion (e.g. ``1.0.0``).
        spec_digest: canonical specDigest (``sha256:<64 hex>``).
        function_name: the ``libraryBinding.function`` name.
        function: the resolved callable attribute of ``alphafrog_finance.metrics``.
        parameter_names: canonical parameter names in declaration order.
        api_compat_range: the ``libraryBinding.apiCompatRange`` string.
        docstring_text: the canonical ``definition`` text for the method.
        parameter_table: the canonical parameterTable entries (tuple of dicts).
        call_sample: the canonical callSample string.
    """

    method_id: str
    method_version: str
    spec_digest: str
    function_name: str
    function: Callable[..., Any]
    parameter_names: Tuple[str, ...]
    api_compat_range: str
    docstring_text: str
    parameter_table: Tuple[Mapping[str, Any], ...]
    call_sample: str


def _snake_case(name: str) -> str:
    """camelCase -> snake_case by inserting ``_`` before each uppercase letter
    then lowercasing (Spec §6 signature-compatibility rule)."""
    out = []
    for ch in name:
        if ch.isupper():
            out.append("_")
        out.append(ch.lower())
    return "".join(out)


def _parse_version_tuple(where: str, text: Any) -> Tuple[int, ...]:
    if not isinstance(text, str) or not text:
        raise RuntimeError(f"{where}: version must be a non-empty string, got {text!r}")
    numbers = []
    for part in text.split("."):
        if not part.isdigit():
            raise RuntimeError(
                f"{where}: version {text!r} is not a dot-separated numeric version"
            )
        numbers.append(int(part))
    return tuple(numbers)


def _require_version_satisfies(method_id: str, api_compat_range: Any, package_version: str) -> None:
    if not isinstance(api_compat_range, str) or not api_compat_range:
        raise RuntimeError(
            f"method {method_id!r}: apiCompatRange must be a non-empty string, "
            f"got {api_compat_range!r}"
        )
    match = _API_RANGE_RE.match(api_compat_range)
    if match is None:
        raise RuntimeError(
            f"method {method_id!r}: apiCompatRange {api_compat_range!r} must "
            "match the grammar '>=<lower>,<<upper>'"
        )
    lower = _parse_version_tuple(f"method {method_id!r} apiCompatRange lower", match.group(1))
    upper = _parse_version_tuple(f"method {method_id!r} apiCompatRange upper", match.group(2))
    version = _parse_version_tuple(f"method {method_id!r} package version", package_version)
    if not (lower <= version < upper):
        raise RuntimeError(
            f"method {method_id!r}: package version {package_version!r} is "
            f"outside apiCompatRange {api_compat_range!r}"
        )


def _load_method_specs_document() -> Mapping[str, Any]:
    path = os.path.join(_GENERATED_DIR, "method_specs.json")
    if not os.path.isfile(path):
        raise RuntimeError(
            f"canonical method specs are not installed ({path}); they are "
            "generated at build time by generate_method_bindings.py and must "
            "not be hand-copied"
        )
    try:
        with open(path, encoding="utf-8") as fh:
            payload = json.load(fh)
    except (OSError, ValueError) as exc:
        raise RuntimeError(f"cannot read {path}: {exc}") from None
    return payload


def _load_embedded_document(filename: str, module_name: str) -> Mapping[str, Any]:
    """Load a generated ``docstrings.py`` / ``call_samples.py`` module by path
    and return its embedded ``DOCUMENT`` mapping."""
    path = os.path.join(_GENERATED_DIR, filename)
    if not os.path.isfile(path):
        raise RuntimeError(
            f"generated binding document is not installed ({path}); it is "
            "produced at build time by generate_method_bindings.py"
        )
    spec = importlib.util.spec_from_file_location(module_name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot create an import spec for {path}")
    module = importlib.util.module_from_spec(spec)
    try:
        spec.loader.exec_module(module)
    except Exception as exc:  # noqa: BLE001 - fail closed on any load error
        raise RuntimeError(f"cannot load generated module {path}: {exc}") from None
    document = getattr(module, "DOCUMENT", None)
    if not isinstance(document, dict):
        raise RuntimeError(f"{path}: module exposes no DOCUMENT mapping")
    return document


def _methods_of(document: Any, label: str) -> Mapping[str, Any]:
    methods = document.get("methods") if isinstance(document, dict) else None
    if not isinstance(methods, dict):
        raise RuntimeError(
            f"{label}: malformed document; expected a top-level 'methods' object"
        )
    return methods


def _assemble() -> None:
    """Lazily assemble the bindings, fail-closed with RuntimeError."""
    global _BINDINGS_CACHE, _FUNCTION_INDEX
    if _BINDINGS_CACHE is not None:
        return

    specs_methods = _methods_of(_load_method_specs_document(), "method_specs.json")
    docstrings_methods = _methods_of(
        _load_embedded_document("docstrings.py", "alphafrog_finance._generated.docstrings"),
        "docstrings.py",
    )
    call_samples_methods = _methods_of(
        _load_embedded_document(
            "call_samples.py", "alphafrog_finance._generated.call_samples"
        ),
        "call_samples.py",
    )

    # EXACT bidirectional equality of the methodId sets across all three docs.
    spec_ids = set(specs_methods)
    docstring_ids = set(docstrings_methods)
    call_sample_ids = set(call_samples_methods)
    if not (spec_ids == docstring_ids == call_sample_ids):
        raise RuntimeError(
            "generated binding documents disagree on methodId sets: "
            f"method_specs.json={sorted(spec_ids)!r}, "
            f"docstrings.py={sorted(docstring_ids)!r}, "
            f"call_samples.py={sorted(call_sample_ids)!r}"
        )
    if not spec_ids:
        raise RuntimeError("generated binding documents contain no methods")

    # Lazy imports avoid any module-load-time import cycle (Spec §6).
    from . import metrics as _metrics
    import alphafrog_finance as _pkg

    package_version = getattr(_pkg, "__version__", None)
    if not isinstance(package_version, str) or not package_version:
        raise RuntimeError("alphafrog_finance.__version__ is unavailable")

    bindings: Dict[str, MethodBinding] = {}
    function_index: Dict[str, str] = {}
    for method_id in sorted(spec_ids):
        spec_entry = specs_methods[method_id]
        docstring_entry = docstrings_methods[method_id]
        call_sample_entry = call_samples_methods[method_id]
        if not isinstance(spec_entry, dict):
            raise RuntimeError(f"method {method_id!r}: malformed method_specs.json entry")
        if not isinstance(docstring_entry, dict):
            raise RuntimeError(f"method {method_id!r}: malformed docstrings.py entry")
        if not isinstance(call_sample_entry, dict):
            raise RuntimeError(f"method {method_id!r}: malformed call_samples.py entry")

        method_version = spec_entry.get("methodVersion")
        spec_digest = spec_entry.get("specDigest")
        if not isinstance(method_version, str) or not method_version:
            raise RuntimeError(f"method {method_id!r}: methodVersion must be a non-empty string")
        if not isinstance(spec_digest, str) or not spec_digest:
            raise RuntimeError(f"method {method_id!r}: specDigest must be a non-empty string")
        if _SPEC_DIGEST_RE.match(spec_digest) is None:
            raise RuntimeError(
                f"method {method_id!r}: specDigest {spec_digest!r} is not in the "
                "frozen canonical shape 'sha256:<64 lowercase hex>'"
            )

        binding = docstring_entry.get("binding")
        if not isinstance(binding, dict):
            raise RuntimeError(f"method {method_id!r}: docstrings.py entry lacks a binding object")
        package = binding.get("package")
        if package != _PACKAGE_NAME:
            raise RuntimeError(
                f"method {method_id!r}: binding.package must be {_PACKAGE_NAME!r}, "
                f"got {package!r}"
            )
        function_name = binding.get("function")
        if not isinstance(function_name, str) or not function_name:
            raise RuntimeError(f"method {method_id!r}: binding.function must be a non-empty string")
        if call_sample_entry.get("function") != function_name:
            raise RuntimeError(
                f"method {method_id!r}: call_samples.py function "
                f"{call_sample_entry.get('function')!r} != binding.function "
                f"{function_name!r}"
            )
        api_compat_range = binding.get("apiCompatRange")
        _require_version_satisfies(method_id, api_compat_range, package_version)

        # libraryBinding function gate: must resolve to a real callable on metrics.
        function = getattr(_metrics, function_name, None)
        if function is None or not callable(function):
            raise RuntimeError(
                f"method {method_id!r}: binding.function {function_name!r} does "
                "not resolve to a callable attribute of alphafrog_finance.metrics"
            )

        parameter_table_raw = docstring_entry.get("parameterTable")
        if not isinstance(parameter_table_raw, list):
            raise RuntimeError(
                f"method {method_id!r}: docstrings.py entry lacks a parameterTable array"
            )
        parameter_names = []
        for entry in parameter_table_raw:
            if not isinstance(entry, dict):
                raise RuntimeError(
                    f"method {method_id!r}: parameterTable entry must be an object"
                )
            name = entry.get("name")
            if not isinstance(name, str) or not name:
                raise RuntimeError(
                    f"method {method_id!r}: parameterTable entry lacks a name"
                )
            parameter_names.append(name)

        # Spec §6 signature gate: every canonical parameter's snake_case form
        # must be a real parameter of the bound function.
        try:
            signature = inspect.signature(function)
        except (TypeError, ValueError) as exc:
            raise RuntimeError(
                f"method {method_id!r}: cannot introspect {function_name!r}: {exc}"
            ) from None
        signature_parameters = set(signature.parameters)
        for name in parameter_names:
            snake = _snake_case(name)
            if snake not in signature_parameters:
                raise RuntimeError(
                    f"method {method_id!r}: canonical parameter {name!r} "
                    f"(snake_case {snake!r}) is not a parameter of "
                    f"{function_name!r}; libraryBinding signature incompatibility"
                )

        docstring_text = docstring_entry.get("definition")
        if not isinstance(docstring_text, str):
            raise RuntimeError(
                f"method {method_id!r}: docstrings.py entry lacks a string definition"
            )
        call_sample = call_sample_entry.get("callSample")
        if not isinstance(call_sample, str) or not call_sample:
            raise RuntimeError(
                f"method {method_id!r}: call_samples.py entry lacks a callSample"
            )

        if function_name in function_index:
            raise RuntimeError(
                f"duplicate binding.function {function_name!r} across "
                f"{function_index[function_name]!r} and {method_id!r}"
            )

        bindings[method_id] = MethodBinding(
            method_id=method_id,
            method_version=method_version,
            spec_digest=spec_digest,
            function_name=function_name,
            function=function,
            parameter_names=tuple(parameter_names),
            api_compat_range=api_compat_range,
            docstring_text=docstring_text,
            parameter_table=tuple(parameter_table_raw),
            call_sample=call_sample,
        )
        function_index[function_name] = method_id

    _BINDINGS_CACHE = bindings
    _FUNCTION_INDEX = function_index


def get_binding(method_id: str) -> MethodBinding:
    """Return the assembled ``MethodBinding`` for ``method_id``.

    Raises RuntimeError (fail closed) when the generated bindings are absent,
    inconsistent, or do not include ``method_id``.
    """
    _assemble()
    assert _BINDINGS_CACHE is not None
    binding = _BINDINGS_CACHE.get(method_id)
    if binding is None:
        raise RuntimeError(f"no generated binding for method {method_id!r}")
    return binding


def list_bindings() -> Tuple[MethodBinding, ...]:
    """Return all assembled bindings as a tuple sorted by method_id."""
    _assemble()
    assert _BINDINGS_CACHE is not None
    return tuple(_BINDINGS_CACHE[mid] for mid in sorted(_BINDINGS_CACHE))


def method_id_for_function(function_name: str) -> str:
    """Return the canonical methodId bound to ``function_name``.

    This is the identity seam used by ``metrics._method_id_for``; unknown
    function names are internal programming errors and fail closed.
    """
    _assemble()
    assert _FUNCTION_INDEX is not None
    method_id = _FUNCTION_INDEX.get(function_name)
    if method_id is None:
        raise RuntimeError(
            f"no method identity registered for {function_name!r}; method "
            "identity must come from the A-canonical generated bindings "
            "(Spec §6), never from caller-supplied values"
        )
    return method_id
