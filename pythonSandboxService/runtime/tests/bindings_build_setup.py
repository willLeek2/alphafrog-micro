# === work-package-B (ccqwen) ===
"""Shared test setup (NOT a test module — no ``test_`` prefix, never collected).

The registry swap makes every public metric call resolve its method identity
through ``alphafrog_finance.bindings``, which reads the three generated build
products out of the INSTALLED ``alphafrog_finance/_generated/`` directory.
Those products are gitignored build artifacts, so the runtime test suite must
materialize them first, exactly as the image build does by running
``runtime/scripts/generate_method_bindings.py`` before pip-install.

``ensure_generated_bindings()`` runs the REAL generator against the committed
verbatim fixture ``tests/fixtures/a-generated-resources-v1/`` and writes
``method_specs.json``, ``docstrings.py``, and ``call_samples.py`` into the
installed ``_generated/`` directory. It is fail-closed: any generator failure
raises ``RuntimeError``. It is idempotent within a process (runs once).
"""
import os
import re
import subprocess
import sys

_TESTS_DIR = os.path.dirname(os.path.abspath(__file__))
_RUNTIME_DIR = os.path.dirname(_TESTS_DIR)
_GENERATOR = os.path.join(_RUNTIME_DIR, "scripts", "generate_method_bindings.py")
_FIXTURE_DIR = os.path.join(_TESTS_DIR, "fixtures", "a-generated-resources-v1")
_GENERATED_DIR = os.path.join(
    _RUNTIME_DIR, "src", "alphafrog_finance", "_generated"
)
_PACKAGE_INIT = os.path.join(
    _RUNTIME_DIR, "src", "alphafrog_finance", "__init__.py"
)

_DONE = False


def _package_version():
    """Extract ``__version__`` from alphafrog_finance/__init__.py, fail-closed."""
    with open(_PACKAGE_INIT, encoding="utf-8") as fh:
        source = fh.read()
    match = re.search(r"^__version__\s*=\s*\"([^\"]+)\"", source, re.MULTILINE)
    if match is None:
        raise RuntimeError(
            f"cannot extract __version__ from {_PACKAGE_INIT}"
        )
    return match.group(1)


def ensure_generated_bindings():
    """Generate the installed build products once per process (fail closed)."""
    global _DONE
    if _DONE:
        return
    os.makedirs(_GENERATED_DIR, exist_ok=True)
    result = subprocess.run(
        [
            sys.executable,
            _GENERATOR,
            "--canonical-dir",
            _FIXTURE_DIR,
            "--out",
            os.path.join(_GENERATED_DIR, "method_specs.json"),
            "--docstrings-out",
            os.path.join(_GENERATED_DIR, "docstrings.py"),
            "--call-samples-out",
            os.path.join(_GENERATED_DIR, "call_samples.py"),
            "--package-version",
            _package_version(),
        ],
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        raise RuntimeError(
            "generate_method_bindings.py failed during test setup: "
            f"rc={result.returncode} stderr={result.stderr!r}"
        )
    _DONE = True
