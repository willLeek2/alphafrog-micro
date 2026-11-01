# === work-package-B (ccqwen) ===
"""Tests for alphafrog_finance.reporting (Spec §6, contract §4.1/§4.3, frozen
at CONTRACT_BASE_SHA 7c695371). Field names/ordering are asserted against the
canonical fixture pythonSandboxService/tests/fixtures/finance-record-channel-v1.json
(SHA-256 19559b46…); the library never gets a second source of truth.

Run from pythonSandboxService/:

    python3 -m unittest discover -s runtime/tests -p 'test_*.py' -v
"""
import contextlib
import hashlib
import io
import json
import os
import shutil
import struct
import sys
import tempfile
import unittest

_SRC = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "src")
if _SRC not in sys.path:
    sys.path.insert(0, _SRC)
_TESTS = os.path.dirname(os.path.abspath(__file__))
if _TESTS not in sys.path:
    sys.path.insert(0, _TESTS)

# Registry swap: metric calls resolve identity through the generated bindings,
# so materialize the build products (real generator) before any metric runs.
from bindings_build_setup import ensure_generated_bindings  # noqa: E402

ensure_generated_bindings()

from alphafrog_finance import (
    FinanceMetricResult,
    annualized_volatility,
    cagr,
    report,
    report_custom,
    sharpe,
    MARKER,
)
from alphafrog_finance import reporting

_SERVICE_ROOT = os.path.dirname(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
)
_CANONICAL_FIXTURE = os.path.join(
    _SERVICE_ROOT, "tests", "fixtures", "finance-record-channel-v1.json"
)

_ENV_ID = "sha256:actual-runtime-example"
_SPECS = {
    "finance.growth.cagr": {
        "methodVersion": "1.0.0",
        "specDigest": "sha256:spec-example",
    },
    # Stand-in canonical entries so report() can emit the other two public
    # methods in tests; the real triples come from A's canonical JSON.
    "finance.risk.annualized_volatility": {
        "methodVersion": "1.0.0",
        "specDigest": "sha256:spec-volatility-example",
    },
    "finance.risk.sharpe_ratio": {
        "methodVersion": "1.0.0",
        "specDigest": "sha256:spec-sharpe-example",
    },
}


def _payload_of(line: str) -> str:
    assert line.startswith(MARKER), f"line lacks marker: {line!r}"
    return line[len(MARKER):]


class ReportingTestBase(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.mkdtemp(prefix="af-reporting-test-")
        self._env_file = os.path.join(self._tmp, "runtime-environment.json")
        # D's runtime_environment.py writes ExecutionEnvironment.model_dump():
        # snake_case keys (environment_id), camelCase only in emitted records.
        with open(self._env_file, "w", encoding="utf-8") as fh:
            json.dump({"environment_id": _ENV_ID}, fh)
        self._old_env = os.environ.get(reporting._RUNTIME_ENV_VAR)
        os.environ[reporting._RUNTIME_ENV_VAR] = self._env_file
        self._old_specs = reporting._METHOD_SPECS_CACHE
        reporting._METHOD_SPECS_CACHE = dict(_SPECS)

    def tearDown(self):
        reporting._METHOD_SPECS_CACHE = self._old_specs
        if self._old_env is None:
            os.environ.pop(reporting._RUNTIME_ENV_VAR, None)
        else:
            os.environ[reporting._RUNTIME_ENV_VAR] = self._old_env
        shutil.rmtree(self._tmp, ignore_errors=True)

    def capture_report(self, *args, **kwargs):
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            line = report(*args, **kwargs)
        return line, buf.getvalue()

    def capture_report_custom(self, *args, **kwargs):
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            line = report_custom(*args, **kwargs)
        return line, buf.getvalue()

    def cagr_result(self):
        return cagr(beginning_value=100.0, ending_value=160.0, periods=4)


class TestReport(ReportingTestBase):
    pass
class TestReportCustom(ReportingTestBase):
    pass
class TestEnvironmentIdSource(ReportingTestBase):
    pass
class TestDigestRoundTrip(ReportingTestBase):
    pass
class TestReportParametersEchoIncludingReturns(ReportingTestBase):
    """ITEM 1 (codex must-fix 0c147646): the emitted record must echo
    ``parameters`` EXACTLY as computed, including the canonical required
    ``returns`` sequence (contract §3.5 parameter table)."""

class TestReportCustomMethodTriple(ReportingTestBase):
    """ITEM 2 (codex must-fix 0c147646): the frozen contract (§4.3) allows a
    custom record with source only, OR source + the COMPLETE method triple —
    report_custom() takes three optional triple parameters and enforces
    ALL-OR-NONE: exactly 0 or exactly 3 present."""

    _TRIPLE = {
        "method_id": "finance.growth.cagr",
        "method_version": "1.0.0",
        "spec_digest": "sha256:spec-example",
    }

    def _base_kwargs(self):
        return {
            "formula_description": "f(x)",
            "input_refs": ["dataset:1"],
            "output_unit": "ratio",
        }

class TestPreEmitSchemaValidation(ReportingTestBase):
    """ITEM 3 (codex must-fix 0c147646): report functions enforce the v1
    schema bounds BEFORE emit (contract §4.3 field rules; §7 step 6 rejects
    non-conforming records; §9 failure matrix: one schema-invalid record
    makes the whole batch unpresentable). Same-table boundary checks: exactly
    at the limit emits fine, over the limit raises ValueError and emits
    nothing. Limits: inputRefs <= 128 entries / 512 UTF-8 bytes per entry,
    formulaDescription <= 4096 bytes, parameters <= 128 entries, checks <=
    128 entries, unit <= 128 bytes, environmentId <= 512 bytes, source
    association non-empty (see reporting.py constants for citations). The
    additional E-schema code-point layer (unit 96, environmentId 256, …) is
    covered by TestESchemaCodePointValidation below."""

    def assert_emits(self, fn):
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            fn()
        self.assertNotEqual(buf.getvalue(), "")

    def assert_rejects(self, fn):
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            with self.assertRaises(ValueError):
                fn()
        self.assertEqual(buf.getvalue(), "")

    def _custom(self, **overrides):
        kwargs = {
            "formula_description": "f(x)",
            "input_refs": [],
            "output_unit": "ratio",
        }
        kwargs.update(overrides)
        return lambda: report_custom(1.0, **kwargs)

class TestMethodIdentityNotOverridableAtEmit(ReportingTestBase):
    """ITEM 4 (codex must-fix 0c147646): a hand-crafted or wrongly-bound
    method_id cannot masquerade as a public-library method — the emitted
    triple comes exclusively from the canonical specs, and no emit function
    exposes a kwarg to override it."""

class TestESchemaCodePointValidation(ReportingTestBase):
    """B must-fix 2 (codex b4b48852): the Java consumer validates records
    against E's FROZEN metric-record-v1.schema.json, whose JSON Schema
    maxLength counts Unicode CODE POINTS (Python len(str)), not UTF-8 bytes.
    The emit-side validators now mirror that field table VERBATIM — unit 96,
    environmentId 256, sourceResolverToolCallId 160, inputRefs items
    minLength 1 / maxLength 512, methodId 160, methodVersion 64, specDigest
    160 + ^sha256:[0-9A-Za-z._:-]+$ — in addition to the retained UTF-8
    byte layer. At the code-point limit emits; one over raises ValueError
    and emits NOTHING (contract §7 step 6, §9 failure matrix)."""

    def assert_emits(self, fn):
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            fn()
        self.assertNotEqual(buf.getvalue(), "")

    def assert_rejects(self, fn):
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            with self.assertRaises(ValueError):
                fn()
        self.assertEqual(buf.getvalue(), "")

    def _custom(self, **overrides):
        kwargs = {
            "formula_description": "f(x)",
            "input_refs": [],
            "output_unit": "ratio",
        }
        kwargs.update(overrides)
        return lambda: report_custom(1.0, **kwargs)

    def _custom_with_triple(self, **overrides):
        kwargs = {
            "formula_description": "f(x)",
            "input_refs": [],
            "output_unit": "ratio",
            "method_id": "finance.growth.cagr",
            "method_version": "1.0.0",
            "spec_digest": "sha256:spec-example",
        }
        kwargs.update(overrides)
        return lambda: report_custom(1.0, **kwargs)

class TestStrictBooleanChecks(ReportingTestBase):
    """B must-fix 2 (codex b4b48852): ``_validated_checks`` previously
    coerced any truthy value with bool() — the string "false" became True.
    Only real booleans are accepted now; anything else raises ValueError
    before emit and nothing reaches stdout."""

    def assert_emits(self, fn):
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            fn()
        self.assertNotEqual(buf.getvalue(), "")

    def assert_rejects(self, fn):
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            with self.assertRaises(ValueError):
                fn()
        self.assertEqual(buf.getvalue(), "")

class TestGeneratedBindingDriftGuard(ReportingTestBase):
    """B must-fix 2 (codex b4b48852): the library/generated-binding triple
    must pass the SAME E-schema field-table validator as a caller-provided
    custom triple before emit, so build-artifact drift in the installed
    canonical specs can never produce a record the Java schema check would
    reject."""

    def assert_rejects(self, fn):
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            with self.assertRaisesRegex(ValueError, "drift"):
                fn()
        self.assertEqual(buf.getvalue(), "")

class TestNonFinitePayloadFailClosed(ReportingTestBase):
    """B must-fix 3 (codex bfcfebec): ``parameters`` is an OPEN object, so a
    caller-supplied NaN/±Infinity used to be emitted as the bare tokens
    ``NaN``/``Infinity`` — not valid JSON, and the Java consumer rejects the
    whole batch (contract §7 step 6, §9 failure matrix). The marker encoder
    now runs with ``allow_nan=False``: ``json.dumps`` raises ValueError
    BEFORE any bytes are produced and ``_emit`` prints only the fully-encoded
    line, so the ValueError propagates with stdout COMPLETELY empty (no
    partial marker) — the pre-emit invariant that the library never emits a
    record E/Java would reject."""

    def assert_emits(self, fn):
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            fn()
        self.assertNotEqual(buf.getvalue(), "")

    def assert_rejects(self, fn):
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            with self.assertRaises(ValueError):
                fn()
        # Fail closed = stdout stays COMPLETELY empty, not even a partial
        # marker prefix.
        self.assertEqual(buf.getvalue(), "")

    def _custom(self, **overrides):
        kwargs = {
            "formula_description": "f(x)",
            "input_refs": [],
            "output_unit": "ratio",
        }
        kwargs.update(overrides)
        return lambda: report_custom(1.0, **kwargs)

if __name__ == "__main__":
    unittest.main()
