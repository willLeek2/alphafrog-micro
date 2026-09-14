#!/usr/bin/env python3
# === work-package-H (ccqwen) ===
"""Runtime image inventory: REAL in-image library set (MethodSpec V5 Spec §12,
round-2 R2-2).

The round-1 design -- host-side ``packages_json_from_lock()`` inferring the
library-set.json content from the lockfile -- is REMOVED. library-set.json,
the OCI ``librarySetDigest`` label and the external image-digest-mapping.json
must all carry the SAME verified ACTUAL inventory of the built image:

* ``--print-json`` runs INSIDE the image (docker_build.sh invokes it via
  ``docker run`` against the image's actual executing interpreter) and prints
  the PEP 503 normalized names + REAL versions of every installed
  distribution (including transitive deps), plus
  ``alphafrog_finance.__api_version__``;
* ``--verify`` runs on the host and fails CLOSED on any drift between the
  expected set (requirements-image.lock pins + the alphafrog_finance
  version/apiVersion read from the runtime source) and the actual inventory;
  on success it emits the verified packages array (``--packages-out``) that
  docker_build.sh feeds to build_runtime_manifest.py.

Comparison semantics: the comparison is bidirectional EXACT within the
managed namespace (the expected names): a missing managed package, a version
mismatch, an apiVersion mismatch, or an extra managed package (two distinct
installed distributions claiming the same normalized managed name) fails
closed. Installed packages OUTSIDE the managed namespace are transitive
dependencies / base tooling: they are recorded in the baked inventory but are
not expected-set members. The pure ``compare_library_inventories`` primitive
is the exact bidirectional comparator (unit-tested with fabricated
inventories in tests/test_build_runtime_manifest.py).

Pure Python 3 standard library only -- no third-party imports.
"""

from __future__ import annotations

import argparse
import ast
import json
import re
import sys
from typing import Any, Dict, List, Optional, Tuple

# PEP 503 name normalization: runs of '-', '_' and '.' collapse to a single
# '-', then lowercase. This is the canonical identity of a distribution.
_PEP503_RUN_RE = re.compile(r"[-_.]+")

# Normalized (PEP 503) name of the alphafrog_finance distribution.
FINANCE_PACKAGE_NAME = "alphafrog-finance"

# Fields the baked library-set package entries may carry (exact allowlist of
# scripts/build_runtime_manifest.py -- keep in sync).
_FINANCE_INIT_CONSTANTS = ("__version__", "__api_version__")


def normalize_package_name(name: Any) -> str:
    """PEP 503 normalization; fail-closed on non-string/empty input."""
    if not isinstance(name, str) or not name.strip():
        raise ValueError(
            "package name must be a non-empty string (got %r)" % (name,)
        )
    normalized = _PEP503_RUN_RE.sub("-", name).lower()
    if not normalized or normalized == "-":
        raise ValueError("package name %r normalizes to nothing" % (name,))
    return normalized


def query_installed_packages() -> Tuple[Dict[str, str], List[str]]:
    """Inventory the CURRENT interpreter via importlib.metadata.

    Returns ``(packages, duplicate_names)``: ``packages`` maps PEP 503
    normalized name -> real version; ``duplicate_names`` lists normalized
    names claimed by more than one installed distribution (an extra-managed-
    package ambiguity that verification fails closed on). Conflicting
    versions for one normalized name raise ValueError (fail-closed).
    """
    import importlib.metadata  # noqa: PLC0415

    packages: Dict[str, str] = {}
    counts: Dict[str, int] = {}
    for distribution in importlib.metadata.distributions():
        metadata = distribution.metadata
        raw_name = metadata.get("Name") if metadata is not None else None
        if not raw_name:
            # Unattributable metadata cannot enter a verified inventory.
            continue
        normalized = normalize_package_name(raw_name)
        version = distribution.version
        if not isinstance(version, str) or not version:
            raise ValueError(
                "distribution %r has no usable version" % (normalized,)
            )
        counts[normalized] = counts.get(normalized, 0) + 1
        if normalized in packages:
            if packages[normalized] != version:
                raise ValueError(
                    "conflicting versions for normalized package %r: %r vs %r"
                    % (normalized, packages[normalized], version)
                )
            continue
        packages[normalized] = version
    duplicates = sorted(name for name, count in counts.items() if count > 1)
    return packages, duplicates


def query_inventory() -> Dict[str, Any]:
    """Full inventory document of the current interpreter (for --print-json)."""
    packages, duplicates = query_installed_packages()
    api_version: Optional[str] = None
    try:
        import alphafrog_finance  # noqa: PLC0415

        candidate = getattr(alphafrog_finance, "__api_version__", None)
        if isinstance(candidate, str) and candidate:
            api_version = candidate
    except ImportError:
        api_version = None
    return {
        "packages": [
            {"name": name, "version": packages[name]} for name in sorted(packages)
        ],
        "apiVersion": api_version,
        "duplicateNames": duplicates,
    }


def parse_lock_packages(lock_text: str) -> Dict[str, str]:
    """Parse requirements-image.lock into {normalized name: version}.

    One ``name==version`` pin per line (comments/blanks allowed); any other
    shape, an empty name/version, or a duplicate pin fails closed.
    """
    packages: Dict[str, str] = {}
    for lineno, raw_line in enumerate(lock_text.splitlines(), 1):
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if "==" not in line:
            raise ValueError(
                "lock line %d is not a 'name==version' pin: %r" % (lineno, line)
            )
        name, version = line.split("==", 1)
        name = name.strip()
        version = version.strip()
        if not name or not version:
            raise ValueError(
                "lock line %d has an empty name or version: %r" % (lineno, line)
            )
        normalized = normalize_package_name(name)
        if normalized in packages:
            raise ValueError(
                "duplicate lock pin for %r at line %d" % (normalized, lineno)
            )
        packages[normalized] = version
    return packages


def read_finance_constants(init_py_path: str) -> Tuple[str, str]:
    """Read ``__version__`` / ``__api_version__`` from the runtime source.

    Static AST extraction (never imports the package): the expected
    alphafrog_finance version/apiVersion come from the SAME source the
    pyproject dynamic version attr reads. Missing constants fail closed.
    """
    with open(init_py_path, "r", encoding="utf-8") as handle:
        tree = ast.parse(handle.read(), filename=init_py_path)
    found: Dict[str, str] = {}
    for node in ast.walk(tree):
        if not isinstance(node, ast.Assign) or not isinstance(node.value, ast.Constant):
            continue
        value = node.value.value
        if not isinstance(value, str):
            continue
        for target in node.targets:
            if isinstance(target, ast.Name) and target.id in _FINANCE_INIT_CONSTANTS:
                found[target.id] = value
    missing = [name for name in _FINANCE_INIT_CONSTANTS if name not in found]
    if missing:
        raise ValueError(
            "%s does not define string constant(s): %s"
            % (init_py_path, ", ".join(missing))
        )
    return found["__version__"], found["__api_version__"]


def compare_library_inventories(
    expected_packages: Dict[str, str],
    actual_packages: Dict[str, str],
    expected_api_version: Optional[str] = None,
    actual_api_version: Optional[str] = None,
) -> Dict[str, Any]:
    """Bidirectional EXACT comparison of two ``{name: version}`` inventories.

    Returns a problem report::

        {"missing": [...], "extra": [...],
         "versionMismatch": [[name, expected, actual], ...],
         "apiVersionMismatch": bool, "ok": bool}

    ``missing``  -- expected names absent from actual;
    ``extra``    -- actual names absent from expected;
    ``versionMismatch`` -- names present in both with different versions;
    ``apiVersionMismatch`` -- compared only when ``expected_api_version`` is
    not None (then actual must equal it EXACTLY).
    """
    missing = sorted(set(expected_packages) - set(actual_packages))
    extra = sorted(set(actual_packages) - set(expected_packages))
    version_mismatch = sorted(
        [name, expected_packages[name], actual_packages[name]]
        for name in set(expected_packages) & set(actual_packages)
        if expected_packages[name] != actual_packages[name]
    )
    api_version_mismatch = (
        expected_api_version is not None
        and expected_api_version != actual_api_version
    )
    ok = not (missing or extra or version_mismatch or api_version_mismatch)
    return {
        "missing": missing,
        "extra": extra,
        "versionMismatch": version_mismatch,
        "apiVersionMismatch": api_version_mismatch,
        "ok": ok,
    }


def verify_runtime_inventory(
    expected_packages: Dict[str, str],
    actual_inventory: Any,
    expected_api_version: Optional[str],
) -> List[str]:
    """Fail-closed verification of the ACTUAL inventory against the expected
    set (production flow; the unit-tested core is compare_library_inventories).

    ``actual_inventory`` is the ``--print-json`` document. Returns the list of
    problem strings (empty == verified): duplicate normalized names (extra
    managed packages), missing managed packages, version mismatches, extra
    managed packages within the namespace, and any apiVersion mismatch.
    """
    problems: List[str] = []
    if not isinstance(actual_inventory, dict):
        return ["actual inventory document is not a JSON object"]
    for name in actual_inventory.get("duplicateNames") or []:
        problems.append(
            "extra managed package: duplicate normalized name %r in the "
            "image inventory" % (name,)
        )
    actual: Dict[str, str] = {}
    for entry in actual_inventory.get("packages") or []:
        if not isinstance(entry, dict):
            problems.append("malformed inventory entry: %r" % (entry,))
            continue
        name = entry.get("name")
        version = entry.get("version")
        if not isinstance(name, str) or not name:
            problems.append("inventory entry without a name: %r" % (entry,))
            continue
        if not isinstance(version, str) or not version:
            problems.append("inventory entry %r without a version" % (name,))
            continue
        actual[name] = version
    # Bidirectional EXACT within the managed namespace (the expected names);
    # packages outside it are transitive deps / base tooling and are recorded
    # in the baked inventory, not compared against the expected set.
    managed_actual = {
        name: actual[name] for name in expected_packages if name in actual
    }
    report = compare_library_inventories(
        expected_packages,
        managed_actual,
        expected_api_version=expected_api_version,
        actual_api_version=actual_inventory.get("apiVersion"),
    )
    for name in report["missing"]:
        problems.append(
            "missing package: expected managed package %r is not installed "
            "in the image" % (name,)
        )
    for name, expected, actual_version in report["versionMismatch"]:
        problems.append(
            "version mismatch: package %r expected %r, image has %r"
            % (name, expected, actual_version)
        )
    for name in report["extra"]:
        problems.append("extra managed package: %r" % (name,))
    if report["apiVersionMismatch"]:
        problems.append(
            "apiVersion mismatch: alphafrog_finance expected %r, image has %r"
            % (expected_api_version, actual_inventory.get("apiVersion"))
        )
    return problems


def packages_for_library_set(actual_inventory: Dict[str, Any]) -> List[Dict[str, str]]:
    """Verified ACTUAL inventory -> library-set packages array.

    Every installed package enters the baked library set (the SAME inventory
    the OCI label and the external mapping carry); the alphafrog_finance
    entry additionally carries the verified apiVersion. Sorted by name.
    """
    api_version = actual_inventory.get("apiVersion")
    entries: List[Dict[str, str]] = []
    for package in sorted(
        actual_inventory.get("packages") or [], key=lambda item: item.get("name") or ""
    ):
        entry: Dict[str, str] = {
            "name": package["name"],
            "version": package["version"],
        }
        if package["name"] == FINANCE_PACKAGE_NAME and api_version:
            entry["apiVersion"] = api_version
        entries.append(entry)
    return entries


def _build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="runtime_image_inventory.py",
        description=(
            "Query the runtime image's ACTUAL executing interpreter for the "
            "installed library set (PEP 503 names + real versions + "
            "alphafrog_finance apiVersion) and fail-closed compare it "
            "against the expected lock-based set (Spec §12 R2-2)."
        ),
    )
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument(
        "--print-json",
        action="store_true",
        help="print the CURRENT interpreter's inventory JSON (runs inside the image)",
    )
    mode.add_argument(
        "--verify",
        action="store_true",
        help="verify an inventory document against the expected set (host side)",
    )
    parser.add_argument("--lock", help="requirements-image.lock path (--verify)")
    parser.add_argument(
        "--actual-json", help="inventory JSON document from --print-json (--verify)"
    )
    parser.add_argument(
        "--finance-init",
        help="runtime/src/alphafrog_finance/__init__.py path (--verify)",
    )
    parser.add_argument(
        "--packages-out",
        help="write the verified packages array (library-set input) here (--verify)",
    )
    return parser


def main(argv: Optional[List[str]] = None) -> int:
    args = _build_arg_parser().parse_args(argv)

    if args.print_json:
        try:
            inventory = query_inventory()
        except Exception as exc:
            print("runtime_image_inventory: error: %s" % exc, file=sys.stderr)
            return 1
        print(json.dumps(inventory, ensure_ascii=False, sort_keys=True))
        return 0

    # --verify
    if not args.lock or not args.actual_json or not args.finance_init:
        print(
            "runtime_image_inventory: error: --verify requires --lock, "
            "--actual-json and --finance-init",
            file=sys.stderr,
        )
        return 2
    try:
        with open(args.lock, "r", encoding="utf-8") as handle:
            expected = parse_lock_packages(handle.read())
        finance_version, finance_api_version = read_finance_constants(args.finance_init)
        with open(args.actual_json, "r", encoding="utf-8") as handle:
            actual_inventory = json.load(handle)
    except (OSError, ValueError) as exc:
        print("runtime_image_inventory: error: %s" % exc, file=sys.stderr)
        return 1
    expected[FINANCE_PACKAGE_NAME] = finance_version

    problems = verify_runtime_inventory(expected, actual_inventory, finance_api_version)
    if problems:
        print(
            "runtime_image_inventory: inventory verification FAILED (%d problem(s)):"
            % len(problems),
            file=sys.stderr,
        )
        for problem in problems:
            print("runtime_image_inventory:   - %s" % problem, file=sys.stderr)
        return 1

    if args.packages_out:
        entries = packages_for_library_set(actual_inventory)
        with open(args.packages_out, "w", encoding="utf-8") as handle:
            json.dump(entries, handle, ensure_ascii=False, indent=2)
            handle.write("\n")
    actual_names = {
        package.get("name") for package in actual_inventory.get("packages") or []
    }
    print(
        "runtime_image_inventory: verified %d installed package(s) against %d "
        "expected managed package(s); alphafrog_finance %s (apiVersion %s)"
        % (
            len(actual_names),
            len(expected),
            finance_version,
            finance_api_version,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
