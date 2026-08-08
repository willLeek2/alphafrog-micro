#!/usr/bin/env python3
# === work-package-B (ccqwen) ===
"""Build-time generator: canonical method bindings -> _generated/ build products.

Spec §6 / codex f1ed6ea9 (+ index.json consumption, codex 97ea103a; registry
swap, codex 0c147646/97ea103a): the alphafrog_finance distribution must ship
its method identity triples (methodId -> {methodVersion, specDigest}) and its
docstring/call-sample documents as build products derived from work package
A's canonical generated JSON. Hand-copied method triples are forbidden; this
tool is the ONLY sanctioned path from A's generated resources into the runtime
package.

This is a build tool. It is stdlib-only, runs before pip-install of the
runtime distribution (the image build invokes it first), and is never
imported by the alphafrog_finance runtime package.

CLI:
    generate_method_bindings.py --canonical-dir <dir> --out <method_specs.json>
        [--docstrings-out <docstrings.py>]
        [--call-samples-out <call_samples.py>]
        [--package-version <version>]

Inputs (<dir> = A's method-specs directory, e.g.
agentToolsShared/target/generated-resources/finance/method-specs/v1):
  * index.json              -- JSON array and the authoritative manifest of
                               the directory (codex 97ea103a). Every entry is
                               a JSON object with EXACTLY the keys
                               {methodId, resourcePath, specDigest, version};
                               resourcePath is a plain relative path whose
                               basename names the method's spec file inside
                               <dir>. It is consumed and strictly validated,
                               never skipped.
  * resolver-catalog.json   -- JSON array of catalog entries; each entry must
                               carry non-empty methodId/version/specDigest.
  * every other *.json file -- one method spec per file, top-level keys
                               restricted to A's generated schema
                               (schemaVersion, methodId, version, specDigest,
                               displayName, definition, conventions,
                               parameters, outputs, libraryBinding,
                               resolverHints, sourceRefs).

Outputs:
  * --out method_specs.json -- {"methods": {<methodId>: {"methodVersion",
                               "specDigest"}}}; the shape loaded by
                               alphafrog_finance.reporting._method_specs().
  * --docstrings-out        -- importable module embedding ONE JSON document
                               {"methods": {<methodId>: {"displayName",
                               "definition", "calculationExpression",
                               "parameterTable", "binding"}}} via json.loads.
  * --call-samples-out      -- importable module embedding ONE JSON document
                               {"methods": {<methodId>: {"function",
                               "callSample", "narrativeTemplate"}}} via
                               json.loads.
  The two module outputs are OPTIONAL; when omitted the run is byte-identical
  to the historical method_specs.json-only behaviour. When given, all three
  outputs are written all-or-nothing after ALL validation passes.

Gates (any violation fails CLOSED: non-zero exit, diagnostic on stderr, and
no output file is ever written -- every document is built fully in memory and
written exactly once at the end):
  * index.json must exist and parse to a JSON array; every entry must be a
    JSON object with EXACTLY the four keys above (missing or extra keys
    fail); duplicate methodId across index entries fails;
  * every index entry's resourcePath must be a safe relative path -- no
    absolute path (POSIX or Windows drive), no '..'/'.' traversal, no empty
    segments -- whose basename names an existing file in <dir> that parses
    to a JSON object; the spec's methodId/version/specDigest must equal the
    entry's values VERBATIM;
  * EXACT bidirectional coverage between index entries and spec files: every
    *.json in <dir> except resolver-catalog.json and index.json must be
    referenced by exactly one index entry (an unreferenced extra file fails;
    an entry referencing a missing file fails);
  * EXACT bidirectional coverage between index entries and the resolver
    catalog on methodId/version/specDigest (the catalog's extra keys --
    aliases/clarificationDimensions/commonPhrases/displayName -- remain
    tolerated; missing, extra, or duplicate correspondence fails);
  * the three frozen method ids must all be present (>=3 identities);
  * each spec file carries non-empty methodId/version and a specDigest of
    shape sha256:<64 lowercase hex>;
  * every spec file and every catalog entry with the same methodId must
    agree VERBATIM on version and specDigest, both directions (spec missing
    from the catalog, catalog entry with no spec file, or any mismatch
    fails);
  * duplicate methodId across spec files or catalog entries fails;
  * unknown/extra top-level shape surprises fail;
  * ALWAYS: every spec file's libraryBinding must be a JSON object with
    EXACTLY the keys {apiCompatRange, function, package}; package must equal
    "alphafrog_finance" verbatim; function must match ^[a-z_][a-z0-9_]*$;
    apiCompatRange must parse as ">=<lower>,<<upper>" numeric-tuple version
    bounds (any other grammar fails); duplicate function across specs fails.
    When --package-version is supplied it must lie inside every binding's
    apiCompatRange (None => the range-vs-version check is skipped, shape
    checks still run);
  * when the docstring/call-sample outputs are requested, every spec must
    carry a displayName/definition string and a single-entry conventions
    object whose entry is a JSON object (the single convention entry supplies
    calculationExpression/narrativeTemplate, empty string when absent).

Byte contract for every output (build reproducibility): UTF-8,
ensure_ascii=False, sort_keys=True, compact separators (",", ":") for the
embedded JSON, plus a single trailing newline.
"""
import argparse
import json
import os
import re
import sys

_CATALOG_NAME = "resolver-catalog.json"
_INDEX_NAME = "index.json"

# Spec §6 frozen identities: the B gate requires all three (>=3 method
# identities) before any bindings document may be emitted.
_REQUIRED_METHOD_IDS = (
    "finance.growth.cagr",
    "finance.risk.annualized_volatility",
    "finance.risk.sharpe_ratio",
)

# The only package the generated bindings may target (Spec §6). This is the
# libraryBinding.package value that must appear VERBATIM in every spec.
_PACKAGE_NAME = "alphafrog_finance"

_DIGEST_RE = re.compile(r"^sha256:[0-9a-f]{64}$")

# A's generated per-method spec schema (work package A build plugin). The
# generator is fail-closed: any other top-level key is a shape surprise.
_SPEC_TOP_LEVEL_KEYS = frozenset(
    {
        "schemaVersion",
        "methodId",
        "version",
        "specDigest",
        "displayName",
        "definition",
        "conventions",
        "parameters",
        "outputs",
        "libraryBinding",
        "resolverHints",
        "sourceRefs",
    }
)

# index.json entry shape (codex 97ea103a): EXACTLY these four keys. Any
# missing or extra key is a shape surprise and fails closed.
_INDEX_ENTRY_KEYS = frozenset({"methodId", "resourcePath", "specDigest", "version"})

# libraryBinding shape (Spec §6 registry swap): EXACTLY these three keys. Any
# missing or extra key is a shape surprise and fails closed.
_BINDING_KEYS = frozenset({"apiCompatRange", "function", "package"})

# libraryBinding.function must be a valid snake_case public attribute name.
_FUNCTION_RE = re.compile(r"^[a-z_][a-z0-9_]*$")

# apiCompatRange grammar: ">=<lower>,<<upper>" (e.g. ">=1.0.0,<2.0.0").
_API_RANGE_RE = re.compile(r"^>=([^,]+),<(.+)$")

# Parameter-table base keys are always present; these optional keys are copied
# VERBATIM only when present in the canonical spec.
_PARAMETER_OPTIONAL_KEYS = ("minimum", "default", "enum", "items")

# resourcePath must stay inside the canonical dir: split on both separator
# styles so backslash-based traversal cannot sneak past on POSIX hosts.
_RESOURCE_PATH_SEPARATOR_RE = re.compile(r"[\\/]")
_WINDOWS_DRIVE_RE = re.compile(r"^[A-Za-z]:")


class SpecError(Exception):
    """Fail-closed diagnostic; never raised after the output was written."""


def _fail(message):
    raise SpecError(message)


def _load_json(path):
    try:
        with open(path, encoding="utf-8") as fh:
            return json.load(fh)
    except OSError as exc:
        _fail(f"cannot read {path}: {exc}")
    except ValueError as exc:
        _fail(f"{path} is not valid JSON: {exc}")


def _require_identity_fields(where, entry):
    """methodId/version non-empty strings, specDigest sha256:<64 hex>."""
    if not isinstance(entry, dict):
        _fail(f"{where}: expected a JSON object")
    method_id = entry.get("methodId")
    if not isinstance(method_id, str) or not method_id:
        _fail(f"{where}: methodId must be a non-empty string")
    version = entry.get("version")
    if not isinstance(version, str) or not version:
        _fail(f"{where}: version must be a non-empty string")
    digest = entry.get("specDigest")
    if not isinstance(digest, str) or not _DIGEST_RE.match(digest):
        _fail(
            f"{where}: specDigest must match sha256:<64 hex>, got {digest!r}"
        )
    return method_id, version, digest


def _resource_path_basename(where, resource_path):
    """Validate an index entry's resourcePath; return its basename.

    resourcePath must be a plain relative descent whose basename addresses a
    file directly inside the canonical directory: absolute paths (POSIX-style
    or Windows drive), '..'/'.' traversal, and empty segments all fail
    closed. Anything "beyond" the canonical directory is unreachable by
    construction once the basename is resolved against it.
    """
    if not isinstance(resource_path, str) or not resource_path:
        _fail(f"{where}: resourcePath must be a non-empty string")
    if resource_path.startswith(("/", "\\")) or _WINDOWS_DRIVE_RE.match(resource_path):
        _fail(
            f"{where}: resourcePath must be a relative path, got {resource_path!r}"
        )
    segments = _RESOURCE_PATH_SEPARATOR_RE.split(resource_path)
    for segment in segments:
        if segment == "":
            _fail(
                f"{where}: resourcePath {resource_path!r} contains an empty "
                "path segment"
            )
        if segment in (".", ".."):
            _fail(
                f"{where}: resourcePath {resource_path!r} must not traverse "
                f"via {segment!r}"
            )
    return segments[-1]


def _load_catalog(canonical_dir):
    """methodId -> {"version", "specDigest"} from resolver-catalog.json."""
    catalog_path = os.path.join(canonical_dir, _CATALOG_NAME)
    if not os.path.isfile(catalog_path):
        _fail(f"missing catalog {catalog_path}")
    entries = _load_json(catalog_path)
    if not isinstance(entries, list):
        _fail(f"{catalog_path}: resolver catalog must be a JSON array")
    catalog = {}
    for index, entry in enumerate(entries):
        method_id, version, digest = _require_identity_fields(
            f"{catalog_path}[{index}]", entry
        )
        if method_id in catalog:
            _fail(f"{catalog_path}: duplicate methodId {method_id!r}")
        catalog[method_id] = {"version": version, "specDigest": digest}
    return catalog


def _load_index(canonical_dir):
    """methodId -> {"version", "specDigest", "resourcePath", "file"}.

    index.json (codex 97ea103a) is consumed, never skipped: it must exist,
    be a JSON array of objects with EXACTLY the keys
    {methodId, resourcePath, specDigest, version}, and carry no duplicate
    methodId across entries.
    """
    index_path = os.path.join(canonical_dir, _INDEX_NAME)
    if not os.path.isfile(index_path):
        _fail(f"missing index {index_path}")
    entries = _load_json(index_path)
    if not isinstance(entries, list):
        _fail(f"{index_path}: resource index must be a JSON array")
    index = {}
    for position, entry in enumerate(entries):
        where = f"{index_path}[{position}]"
        if not isinstance(entry, dict):
            _fail(f"{where}: index entry must be a JSON object")
        missing = sorted(_INDEX_ENTRY_KEYS.difference(entry))
        if missing:
            _fail(f"{where}: index entry is missing key(s) {missing!r}")
        unknown = sorted(set(entry).difference(_INDEX_ENTRY_KEYS))
        if unknown:
            _fail(f"{where}: index entry has unknown key(s) {unknown!r}")
        method_id, version, digest = _require_identity_fields(where, entry)
        if method_id in index:
            _fail(
                f"{index_path}: duplicate methodId {method_id!r} across "
                "index entries"
            )
        basename = _resource_path_basename(where, entry["resourcePath"])
        if basename in (_CATALOG_NAME, _INDEX_NAME):
            _fail(
                f"{where}: resourcePath must reference a method spec file, "
                f"not {basename!r}"
            )
        index[method_id] = {
            "version": version,
            "specDigest": digest,
            "resourcePath": entry["resourcePath"],
            "file": basename,
        }
    return index


def _load_specs(canonical_dir, index):
    """methodId -> {"version", "specDigest", "file", "payload"}.

    Every index entry must resolve to an existing spec file inside the
    canonical directory whose identity triple equals the entry's values
    VERBATIM. The FULL spec payload is retained so the libraryBinding and
    docstring/call-sample documents can be derived from it. Returns the spec
    map plus the set of referenced file names.
    """
    specs = {}
    referenced = set()
    for method_id, entry in index.items():
        name = entry["file"]
        path = os.path.join(canonical_dir, name)
        if not os.path.isfile(path):
            _fail(
                f"index entry {method_id!r}: resourcePath "
                f"{entry['resourcePath']!r} names {name!r}, which does not "
                "exist in the canonical directory"
            )
        payload = _load_json(path)
        if not isinstance(payload, dict):
            _fail(f"{path}: method spec must be a JSON object")
        unknown = set(payload) - _SPEC_TOP_LEVEL_KEYS
        if unknown:
            _fail(f"{path}: unknown top-level key(s) {sorted(unknown)!r}")
        spec_method_id, spec_version, spec_digest = _require_identity_fields(
            path, payload
        )
        if spec_method_id != method_id:
            _fail(
                f"methodId mismatch: index entry {method_id!r} references "
                f"spec file {name!r}, which declares methodId "
                f"{spec_method_id!r}"
            )
        if spec_version != entry["version"]:
            _fail(
                f"methodId {method_id!r}: version mismatch between "
                f"index.json ({entry['version']!r}) and spec file {name!r} "
                f"({spec_version!r})"
            )
        if spec_digest != entry["specDigest"]:
            _fail(
                f"methodId {method_id!r}: specDigest mismatch between "
                f"index.json ({entry['specDigest']!r}) and spec file "
                f"{name!r} ({spec_digest!r})"
            )
        if name in referenced:
            _fail(
                f"spec file {name!r} is referenced by more than one index "
                "entry"
            )
        referenced.add(name)
        specs[method_id] = {
            "version": spec_version,
            "specDigest": spec_digest,
            "file": name,
            "payload": payload,
        }
    return specs, referenced


def _coverage_check(canonical_dir, referenced):
    """Every spec JSON on disk must be referenced by exactly one index entry.

    resolver-catalog.json and index.json are infrastructure, not specs; any
    other *.json in the canonical directory that index.json does not
    reference is an unreferenced extra and fails closed.
    """
    unreferenced = sorted(
        name
        for name in os.listdir(canonical_dir)
        if name.endswith(".json")
        and name not in (_CATALOG_NAME, _INDEX_NAME)
        and name not in referenced
    )
    if unreferenced:
        _fail(
            f"spec file(s) {unreferenced!r} exist in the canonical "
            f"directory but are not referenced by {_INDEX_NAME}"
        )


def _cross_check(specs, catalog):
    """Verbatim equality both ways between spec files and the catalog."""
    for method_id, spec in specs.items():
        entry = catalog.get(method_id)
        if entry is None:
            _fail(
                f"spec file {spec['file']!r}: methodId {method_id!r} is "
                "missing from resolver-catalog.json"
            )
        for field in ("version", "specDigest"):
            if spec[field] != entry[field]:
                _fail(
                    f"methodId {method_id!r}: {field} mismatch between spec "
                    f"file {spec['file']!r} ({spec[field]!r}) and catalog "
                    f"({entry[field]!r})"
                )
    for method_id in catalog:
        if method_id not in specs:
            _fail(
                f"catalog entry {method_id!r} has no spec file in the "
                "canonical directory"
            )


def _cross_check_index(index, catalog):
    """Bidirectional exact coverage between index.json and the catalog.

    The catalog must contain exactly one item per index entry matching on
    methodId/version/specDigest, and every catalog item must correspond to
    exactly one index entry (missing/extra/duplicate correspondence fails).
    Catalog items' extra keys (aliases/clarificationDimensions/
    commonPhrases/displayName) remain tolerated.
    """
    for method_id, entry in index.items():
        catalog_entry = catalog.get(method_id)
        if catalog_entry is None:
            _fail(
                f"index entry {method_id!r} is missing from "
                "resolver-catalog.json"
            )
        for field in ("version", "specDigest"):
            if entry[field] != catalog_entry[field]:
                _fail(
                    f"methodId {method_id!r}: {field} mismatch between "
                    f"index.json ({entry[field]!r}) and catalog "
                    f"({catalog_entry[field]!r})"
                )
    for method_id in catalog:
        if method_id not in index:
            _fail(
                f"catalog entry {method_id!r} has no corresponding "
                "index.json entry"
            )


# --- libraryBinding validation (Spec §6 registry swap) ---------------------


def _parse_version_tuple(where, text, label):
    """Parse a dot-separated numeric version into an int tuple (fail closed)."""
    if not isinstance(text, str) or not text:
        _fail(f"{where}: {label} must be a non-empty string, got {text!r}")
    parts = text.split(".")
    numbers = []
    for part in parts:
        if not part.isdigit():
            _fail(
                f"{where}: {label} {text!r} is not a dot-separated numeric "
                "version"
            )
        numbers.append(int(part))
    return tuple(numbers)


def _parse_api_compat_range(where, api_compat_range):
    """Parse ">=<lower>,<<upper>" into (lower_tuple, upper_tuple)."""
    if not isinstance(api_compat_range, str) or not api_compat_range:
        _fail(
            f"{where}: apiCompatRange must be a non-empty string, got "
            f"{api_compat_range!r}"
        )
    match = _API_RANGE_RE.match(api_compat_range)
    if match is None:
        _fail(
            f"{where}: apiCompatRange {api_compat_range!r} must match the "
            "grammar '>=<lower>,<<upper>' (e.g. '>=1.0.0,<2.0.0')"
        )
    lower = _parse_version_tuple(
        where, match.group(1), "apiCompatRange lower bound"
    )
    upper = _parse_version_tuple(
        where, match.group(2), "apiCompatRange upper bound"
    )
    return lower, upper


def _validate_library_binding(where, payload):
    """Validate one spec's libraryBinding; return the binding object."""
    binding = payload.get("libraryBinding")
    if not isinstance(binding, dict):
        _fail(f"{where}: libraryBinding must be a JSON object")
    missing = sorted(_BINDING_KEYS.difference(binding))
    if missing:
        _fail(f"{where}: libraryBinding is missing key(s) {missing!r}")
    unknown = sorted(set(binding).difference(_BINDING_KEYS))
    if unknown:
        _fail(f"{where}: libraryBinding has unknown key(s) {unknown!r}")
    package = binding.get("package")
    if package != _PACKAGE_NAME:
        _fail(
            f"{where}: libraryBinding.package must be {_PACKAGE_NAME!r}, got "
            f"{package!r}"
        )
    function = binding.get("function")
    if not isinstance(function, str) or _FUNCTION_RE.match(function) is None:
        _fail(
            f"{where}: libraryBinding.function must match "
            f"^[a-z_][a-z0-9_]*$, got {function!r}"
        )
    _parse_api_compat_range(where, binding.get("apiCompatRange"))
    return binding


def _validate_library_bindings(specs):
    """Validate every spec's libraryBinding and reject duplicate functions."""
    functions = {}
    for method_id in sorted(specs):
        where = specs[method_id]["file"]
        binding = _validate_library_binding(where, specs[method_id]["payload"])
        function = binding["function"]
        if function in functions:
            _fail(
                f"duplicate libraryBinding.function {function!r} across "
                f"{functions[function]!r} and {method_id!r}"
            )
        functions[function] = method_id


def _check_package_version(specs, package_version):
    """Every binding's apiCompatRange must include the package version."""
    for method_id in sorted(specs):
        where = specs[method_id]["file"]
        binding = specs[method_id]["payload"]["libraryBinding"]
        api_compat_range = binding["apiCompatRange"]
        lower, upper = _parse_api_compat_range(where, api_compat_range)
        version = _parse_version_tuple(where, package_version, "package version")
        if not (lower <= version < upper):
            _fail(
                f"{where}: package version {package_version!r} is outside "
                f"libraryBinding.apiCompatRange {api_compat_range!r}"
            )


# --- docstring / call-sample documents (optional outputs) ------------------


def _require_string(where, payload, key):
    value = payload.get(key)
    if not isinstance(value, str):
        _fail(f"{where}: {key} must be a string, got {value!r}")
    return value


def _single_convention_entry(where, payload):
    """Return the single conventions entry ({} when conventions is absent).

    When present, conventions must be a JSON object with EXACTLY one entry
    whose value is a JSON object: the docstring/call-sample documents are
    defined against that single convention entry, so any other shape is a
    fail-closed inconsistency.
    """
    conventions = payload.get("conventions")
    if conventions is None:
        return {}
    if not isinstance(conventions, dict):
        _fail(f"{where}: conventions must be a JSON object")
    if len(conventions) != 1:
        _fail(
            f"{where}: conventions must contain exactly one entry, got "
            f"{len(conventions)}"
        )
    (entry,) = conventions.values()
    if not isinstance(entry, dict):
        _fail(f"{where}: the single conventions entry must be a JSON object")
    return entry


def _build_parameter_table(where, payload):
    """parameterTable array preserving the spec's declaration order."""
    parameters = payload.get("parameters")
    if parameters is None:
        return []
    if not isinstance(parameters, dict):
        _fail(f"{where}: parameters must be a JSON object")
    table = []
    for name, spec in parameters.items():
        if not isinstance(spec, dict):
            _fail(f"{where}: parameter {name!r} must be a JSON object")
        required = spec.get("required")
        if not isinstance(required, bool):
            _fail(
                f"{where}: parameter {name!r} 'required' must be a boolean, "
                f"got {required!r}"
            )
        param_type = spec.get("type")
        if not isinstance(param_type, str) or not param_type:
            _fail(
                f"{where}: parameter {name!r} 'type' must be a non-empty "
                f"string, got {param_type!r}"
            )
        meaning = spec.get("meaning")
        if not isinstance(meaning, str) or not meaning:
            _fail(
                f"{where}: parameter {name!r} 'meaning' must be a non-empty "
                f"string, got {meaning!r}"
            )
        entry = {
            "name": name,
            "required": required,
            "type": param_type,
            "meaning": meaning,
        }
        for optional_key in _PARAMETER_OPTIONAL_KEYS:
            if optional_key in spec:
                entry[optional_key] = spec[optional_key]
        table.append(entry)
    return table


def _build_docstrings_payload(specs):
    """{"methods": {methodId: {displayName, definition, calculationExpression,
    parameterTable, binding}}}."""
    methods = {}
    for method_id in sorted(specs):
        where = specs[method_id]["file"]
        payload = specs[method_id]["payload"]
        convention = _single_convention_entry(where, payload)
        calculation_expression = convention.get("calculationExpression", "")
        if not isinstance(calculation_expression, str):
            _fail(
                f"{where}: conventions calculationExpression must be a "
                f"string, got {calculation_expression!r}"
            )
        methods[method_id] = {
            "displayName": _require_string(where, payload, "displayName"),
            "definition": _require_string(where, payload, "definition"),
            "calculationExpression": calculation_expression,
            "parameterTable": _build_parameter_table(where, payload),
            "binding": payload["libraryBinding"],
        }
    return {"methods": methods}


def _build_call_samples_payload(specs):
    """{"methods": {methodId: {function, callSample, narrativeTemplate}}}."""
    methods = {}
    for method_id in sorted(specs):
        where = specs[method_id]["file"]
        payload = specs[method_id]["payload"]
        convention = _single_convention_entry(where, payload)
        narrative_template = convention.get("narrativeTemplate", "")
        if not isinstance(narrative_template, str):
            _fail(
                f"{where}: conventions narrativeTemplate must be a string, "
                f"got {narrative_template!r}"
            )
        function = payload["libraryBinding"]["function"]
        parameters = payload.get("parameters") or {}
        # Canonical JSON carries NO example values: every placeholder is the
        # literal ``...`` and no value is invented.
        arguments = ", ".join(f"{name}=..." for name in parameters)
        methods[method_id] = {
            "function": function,
            "callSample": f"{function}({arguments})",
            "narrativeTemplate": narrative_template,
        }
    return {"methods": methods}


def _canonical_json(payload):
    # Byte contract (build reproducibility): UTF-8, ensure_ascii=False,
    # sort_keys=True, compact separators.
    return json.dumps(
        payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    )


def _render_generated_module(purpose, payload):
    """Render an importable module embedding ONE JSON document via json.loads.

    The JSON text is embedded as a Python string literal built with repr(),
    which is a total, deterministic escaping for any embedded Chinese text or
    quoting, so the module is always valid Python.
    """
    json_text = _canonical_json(payload)
    literal = repr(json_text)
    lines = [
        "# === work-package-B (ccqwen) ===",
        '"""GENERATED by runtime/scripts/generate_method_bindings.py — DO NOT EDIT.',
        "",
        f"Build product: alphafrog_finance/_generated {purpose}. The single",
        "DOCUMENT below is the compact canonical JSON (ensure_ascii=False,",
        'sort_keys=True, separators=(",", ":")) embedded via json.loads. The',
        "module is byte-deterministic for a given canonical input set; any",
        "hand edit is forbidden (Spec §6).",
        '"""',
        "import json",
        "",
        f"DOCUMENT = json.loads({literal})",
        "",
    ]
    return "\n".join(lines)


def _write_text(path, text):
    parent = os.path.dirname(os.path.abspath(path))
    if parent:
        os.makedirs(parent, exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(text)


def generate(
    canonical_dir,
    out_path,
    docstrings_out=None,
    call_samples_out=None,
    package_version=None,
):
    if not os.path.isdir(canonical_dir):
        _fail(f"canonical dir does not exist or is not a directory: {canonical_dir}")
    catalog = _load_catalog(canonical_dir)
    index = _load_index(canonical_dir)
    specs, referenced = _load_specs(canonical_dir, index)
    _coverage_check(canonical_dir, referenced)
    _cross_check_index(index, catalog)
    _cross_check(specs, catalog)
    missing = [mid for mid in _REQUIRED_METHOD_IDS if mid not in specs]
    if missing:
        _fail(
            "frozen method ids missing from canonical dir (Spec §6 requires "
            f">=3 identities): {missing!r}"
        )
    # Spec §6 registry swap: ALWAYS validate every libraryBinding, even when
    # only method_specs.json is requested.
    _validate_library_bindings(specs)
    if package_version is not None:
        _check_package_version(specs, package_version)
    payload = {
        "methods": {
            method_id: {
                "methodVersion": specs[method_id]["version"],
                "specDigest": specs[method_id]["specDigest"],
            }
            for method_id in sorted(specs)
        }
    }
    # Build EVERY requested document fully in memory before writing anything
    # (all-or-nothing; no partial artifact on any failure).
    documents = [(out_path, _canonical_json(payload) + "\n")]
    if docstrings_out is not None:
        documents.append(
            (docstrings_out, _render_generated_module("docstrings", _build_docstrings_payload(specs)))
        )
    if call_samples_out is not None:
        documents.append(
            (
                call_samples_out,
                _render_generated_module("call samples", _build_call_samples_payload(specs)),
            )
        )
    for path, text in documents:
        _write_text(path, text)
    return payload


def main(argv=None):
    parser = argparse.ArgumentParser(
        prog="generate_method_bindings.py",
        description=(
            "Generate alphafrog_finance/_generated build products from work "
            "package A's canonical method-spec JSON (Spec §6; hand-copied "
            "method triples are forbidden). Consumes index.json, "
            "resolver-catalog.json, and the per-method spec files with exact "
            "bidirectional coverage, and validates every libraryBinding. "
            "Fails closed on any missing, mismatched, duplicated, or "
            "malformed input, writing nothing."
        ),
    )
    parser.add_argument(
        "--canonical-dir",
        required=True,
        help=(
            "Directory holding A's index.json, resolver-catalog.json, and "
            "one *.json spec file per method (e.g. .../generated-resources/"
            "finance/method-specs/v1)."
        ),
    )
    parser.add_argument(
        "--out",
        required=True,
        help="Path of the method_specs.json to write (built in memory first).",
    )
    parser.add_argument(
        "--docstrings-out",
        default=None,
        help=(
            "Optional path of the docstrings.py module to write (embedded "
            "canonical JSON). Omitted => not produced."
        ),
    )
    parser.add_argument(
        "--call-samples-out",
        default=None,
        help=(
            "Optional path of the call_samples.py module to write (embedded "
            "canonical JSON). Omitted => not produced."
        ),
    )
    parser.add_argument(
        "--package-version",
        default=None,
        help=(
            "Optional package version to check against every binding's "
            "apiCompatRange (e.g. 1.0.0). Omitted => the range-vs-version "
            "check is skipped; libraryBinding shape checks still run."
        ),
    )
    args = parser.parse_args(argv)
    try:
        payload = generate(
            args.canonical_dir,
            args.out,
            docstrings_out=args.docstrings_out,
            call_samples_out=args.call_samples_out,
            package_version=args.package_version,
        )
    except SpecError as exc:
        print(f"generate_method_bindings: error: {exc}", file=sys.stderr)
        return 2
    ids = sorted(payload["methods"])
    print(
        "generate_method_bindings: wrote {} method spec(s) [{}] to {}".format(
            len(ids), ", ".join(ids), args.out
        )
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
