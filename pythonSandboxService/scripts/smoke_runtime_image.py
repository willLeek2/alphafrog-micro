#!/usr/bin/env python3
# === work-package-H (ccqwen) ===
"""Runtime image smoke gate (MethodSpec V5 Spec §12, round-2 R2-1).

Asserts, INSIDE the target interpreter:

* ``import alphafrog_finance`` succeeds;
* ``alphafrog_finance.__version__ == "1.0.0"``;
* ``alphafrog_finance.__api_version__ == "1.0"``;
* the three frozen method bindings exist VERBATIM (methodId ->
  methodVersion + specDigest), loaded through the installed package's own
  generated-bindings loader (never re-derived here).

docker_build.sh runs this script post-build via ``docker run`` against the
built image, once under the image's SYSTEM python and once under the compat
venv (``/sandbox/.sandbox-venv/bin/python``); any non-zero exit fails the
build CLOSED. The same assertions therefore guard both interpreters.

The pure-logic core ``verify_runtime_metadata`` operates on fabricated
metadata (no import of the real distribution) and is unit-tested in
tests/test_build_runtime_manifest.py. The expected triples are FROZEN
contract values (Spec §6 / work package A canonical JSON) -- they are public
method identities, not secrets; diagnostics stay within §18 discipline
(method ids, versions and digests only, never content).
"""

from __future__ import annotations

import argparse
import sys
from typing import Any, Dict, List, Mapping, Optional, Tuple

# Frozen distribution metadata (Spec §6 / Spec §12 R2-1).
EXPECTED_VERSION = "1.0.0"
EXPECTED_API_VERSION = "1.0"

# Frozen method identities: methodId -> {"methodVersion", "specDigest"}.
# The specDigests are the work-package-A canonical digests of the three
# method specs (cagr / annualized_volatility / sharpe_ratio).
EXPECTED_BINDINGS: Dict[str, Dict[str, str]] = {
    "finance.growth.cagr": {
        "methodVersion": "1.0.0",
        "specDigest": (
            "sha256:cff05d88e83b787478edfd0252c414ded02b8236b9b1032126f5cd51c4d7b25e"
        ),
    },
    "finance.risk.annualized_volatility": {
        "methodVersion": "1.0.0",
        "specDigest": (
            "sha256:2843745f0c4903083430ef0b4eef6be253b09a4c014c28decbf5884466f0d668"
        ),
    },
    "finance.risk.sharpe_ratio": {
        "methodVersion": "1.0.0",
        "specDigest": (
            "sha256:fccc1f0f9264dc90730f7a3b6a35abce2c6f2884c79a3e3b9ce0a7190058db90"
        ),
    },
}


def verify_runtime_metadata(
    version: Any,
    api_version: Any,
    bindings: Optional[Mapping[str, Any]],
) -> List[str]:
    """Verify fabricated/loaded runtime metadata against the frozen contract.

    Returns the list of problem strings; an EMPTY list means the smoke gate
    passes. ``bindings`` is a mapping methodId -> {"methodVersion",
    "specDigest"} (or ``None`` when the generated bindings could not be
    loaded at all, which is itself a problem). Every frozen binding must
    exist VERBATIM: a wrong methodVersion or specDigest is a problem, a
    missing methodId is a problem. Extra bindings beyond the frozen three are
    not a smoke concern.
    """
    problems: List[str] = []
    if version != EXPECTED_VERSION:
        problems.append(
            "alphafrog_finance.__version__ is %r, expected %r"
            % (version, EXPECTED_VERSION)
        )
    if api_version != EXPECTED_API_VERSION:
        problems.append(
            "alphafrog_finance.__api_version__ is %r, expected %r"
            % (api_version, EXPECTED_API_VERSION)
        )
    if bindings is None:
        problems.append(
            "generated method bindings are unavailable (the installed "
            "distribution must carry _generated/method_specs.json)"
        )
    else:
        for method_id in sorted(EXPECTED_BINDINGS):
            expected = EXPECTED_BINDINGS[method_id]
            actual = bindings.get(method_id)
            if not isinstance(actual, Mapping):
                problems.append("missing method binding %r" % method_id)
                continue
            for field in ("methodVersion", "specDigest"):
                if actual.get(field) != expected[field]:
                    problems.append(
                        "method binding %r field %r is %r, expected %r (verbatim)"
                        % (method_id, field, actual.get(field), expected[field])
                    )
    return problems


def load_installed_metadata() -> Tuple[Any, Any, Optional[Dict[str, Any]]]:
    """Import the installed distribution and collect the smoke inputs.

    Returns ``(version, api_version, bindings)``. ``bindings`` is ``None``
    when the generated bindings cannot be loaded (missing build product);
    the import itself raising is handled by the caller.
    """
    import alphafrog_finance  # noqa: PLC0415 -- intentional in-image import

    version = getattr(alphafrog_finance, "__version__", None)
    api_version = getattr(alphafrog_finance, "__api_version__", None)
    bindings: Optional[Dict[str, Any]]
    try:
        from alphafrog_finance.reporting import (  # noqa: PLC0415
            _method_specs,
        )

        bindings = {
            method_id: dict(spec) for method_id, spec in _method_specs().items()
        }
    except Exception:
        bindings = None
    return version, api_version, bindings


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(
        prog="smoke_runtime_image.py",
        description=(
            "Smoke gate for the AlphaFrog runtime image (Spec §12 R2-1): "
            "verifies alphafrog_finance metadata and the three frozen method "
            "bindings VERBATIM inside the target interpreter. Exits non-zero "
            "on any problem (fail-closed)."
        ),
    )
    parser.parse_args(argv)

    interpreter = "%s (%s)" % (sys.executable, sys.version.split()[0])
    try:
        version, api_version, bindings = load_installed_metadata()
    except Exception as exc:
        print(
            "smoke FAILED [%s]: cannot import alphafrog_finance: %s"
            % (interpreter, exc),
            file=sys.stderr,
        )
        return 1

    problems = verify_runtime_metadata(version, api_version, bindings)
    if problems:
        print(
            "smoke FAILED [%s]: %d problem(s):" % (interpreter, len(problems)),
            file=sys.stderr,
        )
        for problem in problems:
            print("smoke FAILED:   - %s" % problem, file=sys.stderr)
        return 1

    print(
        "smoke OK [%s]: alphafrog_finance %s (apiVersion %s), %d/%d frozen "
        "method bindings verbatim"
        % (
            interpreter,
            version,
            api_version,
            len(EXPECTED_BINDINGS),
            len(EXPECTED_BINDINGS),
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
