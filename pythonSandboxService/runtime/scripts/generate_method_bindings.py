#!/usr/bin/env python3
# === work-package-B (ccqwen) ===
"""Build-time generator: canonical method bindings -> _generated/method_specs.json.

Spec §6 / codex f1ed6ea9: the alphafrog_finance distribution must ship its
method identity triples (methodId -> {methodVersion, specDigest}) as a build
product derived from work package A's canonical generated JSON. Hand-copied
method triples are forbidden; this tool is the ONLY sanctioned path from A's
generated resources into the runtime package.

This is a build tool. It is stdlib-only, runs before pip-install of the
runtime distribution (the image build invokes it first), and is never imported
by the alphafrog_finance runtime package.

CLI:
    generate_method_bindings.py --canonical-dir <dir> --out <method_specs.json>

Inputs (<dir> = A's method-specs directory, e.g.
agentToolsShared/target/generated-resources/finance/method-specs/v1):
  * resolver-catalog.json   -- JSON array of catalog entries; each entry must
                               carry non-empty methodId/version/specDigest.
  * every other *.json file -- one method spec per file, top-level keys
                               restricted to A's generated schema
                               (schemaVersion, methodId, version, specDigest,
                               displayName, definition, conventions,
                               parameters, outputs, libraryBinding,
                               resolverHints, sourceRefs).

Gates (any violation fails CLOSED: non-zero exit, diagnostic on stderr, and
no output file is ever written -- the document is built fully in memory and
written exactly once at the end):
  * the three frozen method ids must all be present (>=3 identities);
  * each spec file carries non-empty methodId/version and a specDigest of
    shape sha256:<64 lowercase hex>;
  * every spec file and every catalog entry with the same methodId must agree
    VERBATIM on version and specDigest, both directions (spec missing from
    the catalog, catalog entry with no spec file, or any mismatch fails);
  * duplicate methodId across spec files or catalog entries fails;
  * unknown/extra top-level shape surprises fail.

Output (--out): {"methods": {<methodId>: {"methodVersion": <version>,
"specDigest": <specDigest>}}} -- exactly the shape loaded by
alphafrog_finance.reporting._method_specs() -- serialized deterministically
as json.dumps(payload, ensure_ascii=False, sort_keys=True,
separators=(",", ":")) plus a single trailing newline.
"""
import argparse
import json
import os
import re
import sys

_CATALOG_NAME = "resolver-catalog.json"

# Spec §6 frozen identities: the B gate requires all three (>=3 method
# identities) before any bindings document may be emitted.
_REQUIRED_METHOD_IDS = (
    "finance.growth.cagr",
    "finance.risk.annualized_volatility",
    "finance.risk.sharpe_ratio",
)

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


def _load_specs(canonical_dir):
    """methodId -> {"version", "specDigest", "file"} from the spec files."""
    spec_files = sorted(
        name
        for name in os.listdir(canonical_dir)
        if name.endswith(".json") and name != _CATALOG_NAME
    )
    if not spec_files:
        _fail(f"{canonical_dir}: no method spec files (*.json) found")
    specs = {}
    for name in spec_files:
        path = os.path.join(canonical_dir, name)
        payload = _load_json(path)
        if not isinstance(payload, dict):
            _fail(f"{path}: method spec must be a JSON object")
        unknown = set(payload) - _SPEC_TOP_LEVEL_KEYS
        if unknown:
            _fail(f"{path}: unknown top-level key(s) {sorted(unknown)!r}")
        method_id, version, digest = _require_identity_fields(path, payload)
        if method_id in specs:
            _fail(
                f"duplicate methodId {method_id!r} across spec files "
                f"{specs[method_id]['file']!r} and {name!r}"
            )
        specs[method_id] = {"version": version, "specDigest": digest, "file": name}
    return specs


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


def generate(canonical_dir, out_path):
    if not os.path.isdir(canonical_dir):
        _fail(f"canonical dir does not exist or is not a directory: {canonical_dir}")
    catalog = _load_catalog(canonical_dir)
    specs = _load_specs(canonical_dir)
    _cross_check(specs, catalog)
    missing = [mid for mid in _REQUIRED_METHOD_IDS if mid not in specs]
    if missing:
        _fail(
            "frozen method ids missing from canonical dir (Spec §6 requires "
            f">=3 identities): {missing!r}"
        )
    payload = {
        "methods": {
            method_id: {
                "methodVersion": specs[method_id]["version"],
                "specDigest": specs[method_id]["specDigest"],
            }
            for method_id in sorted(specs)
        }
    }
    # Byte contract (build reproducibility): UTF-8, ensure_ascii=False,
    # sort_keys=True, compact separators, single trailing newline.
    document = (
        json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        + "\n"
    )
    out_parent = os.path.dirname(os.path.abspath(out_path))
    if out_parent:
        os.makedirs(out_parent, exist_ok=True)
    with open(out_path, "w", encoding="utf-8") as fh:
        fh.write(document)
    return payload


def main(argv=None):
    parser = argparse.ArgumentParser(
        prog="generate_method_bindings.py",
        description=(
            "Generate alphafrog_finance/_generated/method_specs.json from work "
            "package A's canonical method-spec JSON (Spec §6; hand-copied "
            "method triples are forbidden). Fails closed on any missing, "
            "mismatched, duplicated, or malformed input, writing nothing."
        ),
    )
    parser.add_argument(
        "--canonical-dir",
        required=True,
        help=(
            "Directory holding A's resolver-catalog.json plus one *.json spec "
            "file per method (e.g. .../generated-resources/finance/"
            "method-specs/v1)."
        ),
    )
    parser.add_argument(
        "--out",
        required=True,
        help="Path of the method_specs.json to write (built in memory first).",
    )
    args = parser.parse_args(argv)
    try:
        payload = generate(args.canonical_dir, args.out)
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
