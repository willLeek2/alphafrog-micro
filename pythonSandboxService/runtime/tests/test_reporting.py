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
    def test_emits_single_marker_line_to_stdout_and_returns_it(self):
        line, stdout = self.capture_report(
            self.cagr_result(),
            input_refs=["dataset:1"],
            source_resolver_tool_call_id="tool-call-resolver-1",
        )
        self.assertEqual(stdout, line + "\n")
        self.assertEqual(stdout.count("\n"), 1)
        self.assertTrue(line.startswith(MARKER))

    def test_field_order_matches_canonical_library_record(self):
        with open(_CANONICAL_FIXTURE, encoding="utf-8") as fh:
            fixture = json.load(fh)
        case1 = fixture["cases"][0]
        fixture_payload = next(
            l for l in case1["stdoutLines"] if l.startswith(MARKER)
        )[len(MARKER):]
        fixture_keys = list(json.loads(fixture_payload).keys())
        line, _ = self.capture_report(
            self.cagr_result(),
            input_refs=["dataset:1"],
            source_resolver_tool_call_id="tool-call-resolver-1",
        )
        self.assertEqual(list(json.loads(_payload_of(line)).keys()), fixture_keys)

    def test_triple_auto_filled_and_not_overridable(self):
        # report() exposes no parameter for the triple; it comes from specs.
        line, _ = self.capture_report(self.cagr_result())
        record = json.loads(_payload_of(line))
        self.assertEqual(record["methodId"], "finance.growth.cagr")
        self.assertEqual(record["methodVersion"], "1.0.0")
        self.assertEqual(record["specDigest"], "sha256:spec-example")
        self.assertEqual(record["evidence"], "LIBRARY_CALL_DECLARED")

    def test_unknown_method_id_raises(self):
        result = self.cagr_result()
        unknown = type(result)(
            method_id="finance.growth.unknown",
            value=result.value,
            unit=result.unit,
            parameters=result.parameters,
        )
        with self.assertRaisesRegex(RuntimeError, "canonical"):
            report(unknown)

    def test_source_resolver_tool_call_id_omitted_when_none(self):
        line, _ = self.capture_report(self.cagr_result())
        self.assertNotIn("sourceResolverToolCallId", json.loads(_payload_of(line)))

    def test_parameters_and_checks_echoed(self):
        result = self.cagr_result()
        line, _ = self.capture_report(result)
        record = json.loads(_payload_of(line))
        self.assertEqual(
            record["parameters"],
            {"beginningValue": 100.0, "endingValue": 160.0, "periods": 4},
        )
        self.assertEqual(record["checks"], dict(result.checks))
        self.assertEqual(record["environmentId"], _ENV_ID)
        self.assertEqual(record["schemaVersion"], "1")

    def test_validation_errors(self):
        with self.assertRaises(TypeError):
            report({"method_id": "x"})
        result = self.cagr_result()
        bad_value = type(result)(
            method_id=result.method_id,
            value=float("nan"),
            unit=result.unit,
            parameters=result.parameters,
        )
        with self.assertRaisesRegex(ValueError, "value"):
            report(bad_value)
        bad_unit = type(result)(
            method_id=result.method_id,
            value=result.value,
            unit="",
            parameters=result.parameters,
        )
        with self.assertRaisesRegex(ValueError, "unit"):
            report(bad_unit)
        with self.assertRaisesRegex(ValueError, "input_refs"):
            report(result, input_refs=["ok", 7])

    def test_no_legacy_field_names(self):
        line, _ = self.capture_report(self.cagr_result())
        record = json.loads(_payload_of(line))
        for legacy in ("adviceId", "adviceDurable", "persisted"):
            self.assertNotIn(legacy, record)


class TestReportCustom(ReportingTestBase):
    def test_custom_with_checks_field_order_and_evidence(self):
        with open(_CANONICAL_FIXTURE, encoding="utf-8") as fh:
            fixture = json.load(fh)
        case2 = fixture["cases"][1]
        fixture_payload = next(
            l for l in case2["stdoutLines"] if l.startswith(MARKER)
        )[len(MARKER):]
        fixture_keys = list(json.loads(fixture_payload).keys())
        line, _ = self.capture_report_custom(
            2.75,
            formula_description="37 个交易日内满足条件的自定义评分",
            input_refs=["dataset:2"],
            output_unit="score",
            parameters={"lookbackTradingDays": 37, "threshold": 0.8},
            checks={"finite": True},
            source_resolver_tool_call_id="tool-call-resolver-2",
        )
        record = json.loads(_payload_of(line))
        self.assertEqual(list(record.keys()), fixture_keys)
        self.assertEqual(record["evidence"], "CUSTOM_WITH_CHECKS")
        self.assertEqual(record["unit"], "score")
        self.assertNotIn("outputUnit", record)
        for triple_key in ("methodId", "methodVersion", "specDigest"):
            self.assertNotIn(triple_key, record)

    def test_custom_record_is_byte_identical_to_canonical_fixture(self):
        """Strongest contract check: with the fixture's own inputs, the emitted
        line must be byte-identical to the frozen canonical payload (358 bytes,
        rawDigest e2808240…). The library record (case 1) cannot be reproduced
        byte-exactly — the fixture is a format oracle with a pre-rounded value
        and reduced checks — so byte identity is asserted for the custom case
        only."""
        with open(_CANONICAL_FIXTURE, encoding="utf-8") as fh:
            fixture = json.load(fh)
        case2 = fixture["cases"][1]
        fixture_line = next(
            l for l in case2["stdoutLines"] if l.startswith(MARKER)
        )
        line, _ = self.capture_report_custom(
            2.75,
            formula_description="37 个交易日内满足条件的自定义评分",
            input_refs=["dataset:2"],
            output_unit="score",
            parameters={"lookbackTradingDays": 37, "threshold": 0.8},
            checks={"finite": True},
            source_resolver_tool_call_id="tool-call-resolver-2",
        )
        self.assertEqual(line, fixture_line)
        payload = _payload_of(line)
        self.assertEqual(
            len(payload.encode("utf-8")), case2["expected"]["emittedRecordBytes"]
        )
        self.assertEqual(
            hashlib.sha256(payload.encode("utf-8")).hexdigest(),
            case2["expected"]["rawDigests"][0],
        )

    def test_custom_unverified_when_checks_empty_or_none(self):
        for checks in (None, {}):
            with self.subTest(checks=checks):
                line, _ = self.capture_report_custom(
                    0.5,
                    formula_description="f(x)",
                    input_refs=[],
                    output_unit="ratio",
                    checks=checks,
                )
                record = json.loads(_payload_of(line))
                self.assertEqual(record["evidence"], "CUSTOM_UNVERIFIED")
                self.assertEqual(record["inputRefs"], [])
                self.assertEqual(record["parameters"], {})

    def test_source_association_never_upgrades_evidence(self):
        line, _ = self.capture_report_custom(
            0.5,
            formula_description="f(x)",
            input_refs=[],
            output_unit="ratio",
            checks=None,
            source_resolver_tool_call_id="tool-call-resolver-9",
        )
        record = json.loads(_payload_of(line))
        self.assertEqual(record["sourceResolverToolCallId"], "tool-call-resolver-9")
        self.assertEqual(record["evidence"], "CUSTOM_UNVERIFIED")

    def test_non_ascii_payload_is_raw_utf8(self):
        line, _ = self.capture_report_custom(
            1.0,
            formula_description="交易日加权",
            input_refs=[],
            output_unit="ratio",
        )
        payload = _payload_of(line)
        self.assertIn("交易日加权", payload)
        self.assertNotIn("\\u", payload)
        # deterministic bytes: compact separators, no spaces
        self.assertNotIn(": ", payload)
        self.assertNotIn(", ", payload)

    def test_validation_errors(self):
        kwargs = dict(
            formula_description="f(x)", input_refs=[], output_unit="ratio"
        )
        for bad_value in (float("nan"), float("inf"), "1.5", True):
            with self.subTest(value=bad_value):
                with self.assertRaisesRegex(ValueError, "value"):
                    report_custom(bad_value, **kwargs)
        with self.assertRaisesRegex(ValueError, "formula_description"):
            report_custom(1.0, formula_description="   ", input_refs=[], output_unit="ratio")
        with self.assertRaisesRegex(ValueError, "output_unit"):
            report_custom(1.0, formula_description="f(x)", input_refs=[], output_unit="")


class TestEnvironmentIdSource(ReportingTestBase):
    def test_missing_env_file_raises_runtime_error(self):
        os.environ[reporting._RUNTIME_ENV_VAR] = os.path.join(self._tmp, "nope.json")
        with self.assertRaisesRegex(RuntimeError, "runtime environment file"):
            report(self.cagr_result())

    def test_malformed_env_file_raises_runtime_error(self):
        with open(self._env_file, "w", encoding="utf-8") as fh:
            fh.write("{not json")
        with self.assertRaisesRegex(RuntimeError, "runtime environment file"):
            report(self.cagr_result())

    def test_env_file_without_environment_id_raises_runtime_error(self):
        with open(self._env_file, "w", encoding="utf-8") as fh:
            json.dump({"other": 1}, fh)
        with self.assertRaisesRegex(RuntimeError, "environment_id"):
            report(self.cagr_result())


class TestDigestRoundTrip(ReportingTestBase):
    def test_emitted_payload_satisfies_4_1_digest_rules(self):
        line, _ = self.capture_report(
            self.cagr_result(),
            input_refs=["dataset:1"],
            source_resolver_tool_call_id="tool-call-resolver-1",
        )
        payload = _payload_of(line)
        data = payload.encode("utf-8")
        raw_digest = hashlib.sha256(data).hexdigest()
        record_digest = hashlib.sha256(struct.pack(">I", len(data)) + data).hexdigest()
        # Same payload emitted twice keeps relative order and digests stable.
        line2, _ = self.capture_report(
            self.cagr_result(),
            input_refs=["dataset:1"],
            source_resolver_tool_call_id="tool-call-resolver-1",
        )
        self.assertEqual(line, line2)
        self.assertEqual(hashlib.sha256(_payload_of(line2).encode("utf-8")).hexdigest(), raw_digest)
        self.assertEqual(len(raw_digest), 64)
        self.assertEqual(len(record_digest), 64)


class TestReportParametersEchoIncludingReturns(ReportingTestBase):
    """ITEM 1 (codex must-fix 0c147646): the emitted record must echo
    ``parameters`` EXACTLY as computed, including the canonical required
    ``returns`` sequence (contract §3.5 parameter table)."""

    def test_volatility_record_echoes_returns_and_window_exactly(self):
        returns = [0.01, -0.02, 0.015, 0.005, -0.01, 0.02]
        result = annualized_volatility(returns, periods_per_year=12, window=4)
        line, _ = self.capture_report(result)
        record = json.loads(_payload_of(line))
        self.assertEqual(
            record["parameters"],
            {"returns": returns, "periodsPerYear": 12, "window": 4},
        )
        self.assertEqual(record["methodId"], "finance.risk.annualized_volatility")

    def test_sharpe_record_echoes_returns_exactly(self):
        returns = [0.012, -0.008, 0.021, 0.004, 0.011]
        result = sharpe(returns, risk_free_rate=0.02, periods_per_year=252)
        line, _ = self.capture_report(result)
        record = json.loads(_payload_of(line))
        self.assertEqual(
            record["parameters"],
            {
                "returns": returns,
                "riskFreeRate": 0.02,
                "riskFreeRateConvention": "annual",
                "ddof": 1,
                "periodsPerYear": 252,
                "returnConvention": "arithmetic",
            },
        )
        self.assertEqual(record["methodId"], "finance.risk.sharpe_ratio")


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

    def test_no_triple_emits_no_triple_fields(self):
        line, _ = self.capture_report_custom(1.0, **self._base_kwargs())
        record = json.loads(_payload_of(line))
        for key in ("methodId", "methodVersion", "specDigest"):
            self.assertNotIn(key, record)

    def test_all_three_emit_complete_triple_and_field_order(self):
        kwargs = {**self._base_kwargs(), **self._TRIPLE}
        line, _ = self.capture_report_custom(
            1.0, source_resolver_tool_call_id="tool-call-resolver-3", **kwargs
        )
        record = json.loads(_payload_of(line))
        # Field order mirrors the library record (§4.3 example): the triple
        # comes right after schemaVersion, before the source association.
        self.assertEqual(
            list(record.keys()),
            [
                "schemaVersion",
                "methodId",
                "methodVersion",
                "specDigest",
                "sourceResolverToolCallId",
                "environmentId",
                "value",
                "unit",
                "parameters",
                "inputRefs",
                "checks",
                "formulaDescription",
                "evidence",
            ],
        )
        self.assertEqual(record["methodId"], self._TRIPLE["method_id"])
        self.assertEqual(record["methodVersion"], self._TRIPLE["method_version"])
        self.assertEqual(record["specDigest"], self._TRIPLE["spec_digest"])

    def test_partial_triples_raise_and_emit_nothing(self):
        partials = [
            {"method_id": "m"},
            {"method_version": "1.0.0"},
            {"spec_digest": "sha256:x"},
            {"method_id": "m", "method_version": "1.0.0"},
            {"method_id": "m", "spec_digest": "sha256:x"},
            {"method_version": "1.0.0", "spec_digest": "sha256:x"},
        ]
        for partial in partials:
            with self.subTest(provided=sorted(partial)):
                kwargs = {**self._base_kwargs(), **partial}
                buf = io.StringIO()
                with contextlib.redirect_stdout(buf):
                    with self.assertRaisesRegex(ValueError, "together"):
                        report_custom(1.0, **kwargs)
                self.assertEqual(buf.getvalue(), "")

    def test_empty_triple_component_raises_and_emits_nothing(self):
        empties = [
            {"method_id": ""},
            {"method_version": "   "},
            {"spec_digest": ""},
        ]
        for empty in empties:
            with self.subTest(overridden=sorted(empty)):
                kwargs = {**self._base_kwargs(), **self._TRIPLE, **empty}
                buf = io.StringIO()
                with contextlib.redirect_stdout(buf):
                    with self.assertRaises(ValueError):
                        report_custom(1.0, **kwargs)
                self.assertEqual(buf.getvalue(), "")

    def test_triple_presence_does_not_change_custom_evidence(self):
        for checks, expected in (
            (None, "CUSTOM_UNVERIFIED"),
            ({"finite": True}, "CUSTOM_WITH_CHECKS"),
        ):
            with self.subTest(checks=checks):
                line_plain, _ = self.capture_report_custom(
                    1.0,
                    checks=checks,
                    source_resolver_tool_call_id="tool-call-resolver-3",
                    **self._base_kwargs(),
                )
                line_triple, _ = self.capture_report_custom(
                    1.0,
                    checks=checks,
                    source_resolver_tool_call_id="tool-call-resolver-3",
                    **{**self._base_kwargs(), **self._TRIPLE},
                )
                plain = json.loads(_payload_of(line_plain))
                with_triple = json.loads(_payload_of(line_triple))
                self.assertEqual(plain["evidence"], expected)
                self.assertEqual(with_triple["evidence"], expected)
                # The triple is the ONLY difference between the two records.
                stripped = {
                    k: v
                    for k, v in with_triple.items()
                    if k not in ("methodId", "methodVersion", "specDigest")
                }
                self.assertEqual(stripped, plain)


class TestPreEmitSchemaValidation(ReportingTestBase):
    """ITEM 3 (codex must-fix 0c147646): report functions enforce the v1
    schema bounds BEFORE emit (contract §4.3 field rules; §7 step 6 rejects
    non-conforming records; §9 failure matrix: one schema-invalid record
    makes the whole batch unpresentable). Same-table boundary checks: exactly
    at the limit emits fine, over the limit raises ValueError and emits
    nothing. Limits: inputRefs <= 128 entries / 512 UTF-8 bytes per entry,
    formulaDescription <= 4096 bytes, parameters <= 128 entries, checks <=
    128 entries, unit <= 128 bytes, environmentId <= 512 bytes, source
    association non-empty (see reporting.py constants for citations)."""

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

    def test_input_refs_count_boundary(self):
        self.assert_emits(self._custom(input_refs=["r"] * 128))
        self.assert_rejects(self._custom(input_refs=["r"] * 129))

    def test_input_ref_entry_bytes_boundary(self):
        self.assert_emits(self._custom(input_refs=["x" * 512]))
        self.assert_rejects(self._custom(input_refs=["x" * 513]))

    def test_formula_description_bytes_boundary(self):
        self.assert_emits(self._custom(formula_description="f" * 4096))
        self.assert_rejects(self._custom(formula_description="f" * 4097))
        # Limits are UTF-8 BYTES (contract §4.2 byte accounting): 1366 CJK
        # characters are only 1366 code points but 4098 bytes.
        self.assert_rejects(self._custom(formula_description="中" * 1366))

    def test_parameters_count_boundary(self):
        at_limit = {f"p{i:03d}": i for i in range(128)}
        over_limit = {f"p{i:03d}": i for i in range(129)}
        self.assert_emits(self._custom(parameters=at_limit))
        self.assert_rejects(self._custom(parameters=over_limit))

    def test_checks_count_boundary(self):
        at_limit = {f"c{i:03d}": True for i in range(128)}
        over_limit = {f"c{i:03d}": True for i in range(129)}
        self.assert_emits(self._custom(checks=at_limit))
        self.assert_rejects(self._custom(checks=over_limit))

    def test_unit_bytes_boundary(self):
        self.assert_emits(self._custom(output_unit="u" * 128))
        self.assert_rejects(self._custom(output_unit="u" * 129))

    def test_environment_id_bytes_boundary(self):
        for size, expect_emit in ((512, True), (513, False)):
            with self.subTest(size=size):
                with open(self._env_file, "w", encoding="utf-8") as fh:
                    json.dump({"environment_id": "e" * size}, fh)
                if expect_emit:
                    self.assert_emits(self._custom())
                else:
                    self.assert_rejects(self._custom())

    def test_source_association_must_be_non_empty(self):
        for bad_source in ("", "   "):
            with self.subTest(source=repr(bad_source)):
                self.assert_rejects(
                    self._custom(source_resolver_tool_call_id=bad_source)
                )
        self.assert_emits(
            self._custom(source_resolver_tool_call_id="tool-call-resolver-1")
        )

    def test_report_path_enforces_the_same_bounds(self):
        result = self.cagr_result()
        self.assert_emits(lambda: report(result, input_refs=["r"] * 128))
        self.assert_rejects(lambda: report(result, input_refs=["r"] * 129))
        self.assert_rejects(lambda: report(result, input_refs=["x" * 513]))
        self.assert_rejects(
            lambda: report(result, source_resolver_tool_call_id="")
        )
        cls = type(result)
        long_unit = cls(
            method_id=result.method_id,
            value=result.value,
            unit="u" * 129,
            parameters=result.parameters,
        )
        self.assert_rejects(lambda: report(long_unit))
        many_parameters = cls(
            method_id=result.method_id,
            value=result.value,
            unit=result.unit,
            parameters={f"p{i:03d}": i for i in range(129)},
        )
        self.assert_rejects(lambda: report(many_parameters))
        many_checks = cls(
            method_id=result.method_id,
            value=result.value,
            unit=result.unit,
            parameters={},
            checks={f"c{i:03d}": True for i in range(129)},
        )
        self.assert_rejects(lambda: report(many_checks))


class TestMethodIdentityNotOverridableAtEmit(ReportingTestBase):
    """ITEM 4 (codex must-fix 0c147646): a hand-crafted or wrongly-bound
    method_id cannot masquerade as a public-library method — the emitted
    triple comes exclusively from the canonical specs, and no emit function
    exposes a kwarg to override it."""

    def test_forged_unknown_method_id_emits_nothing(self):
        forged = FinanceMetricResult(
            method_id="finance.growth.cagr.forged",
            value=1.0,
            unit="ratio",
            parameters={},
        )
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            with self.assertRaisesRegex(RuntimeError, "canonical"):
                report(forged)
        self.assertEqual(buf.getvalue(), "")

    def test_report_exposes_no_triple_kwargs(self):
        import inspect

        params = set(inspect.signature(report).parameters)
        for triple_kwarg in ("method_id", "method_version", "spec_digest"):
            self.assertNotIn(triple_kwarg, params)
        result = self.cagr_result()
        with self.assertRaises(TypeError):
            report(result, method_version="9.9.9")
        with self.assertRaises(TypeError):
            report(result, spec_digest="sha256:forged")

    def test_result_model_has_no_caller_fillable_triple(self):
        import dataclasses as dc

        names = {f.name for f in dc.fields(FinanceMetricResult)}
        self.assertNotIn("method_version", names)
        self.assertNotIn("spec_digest", names)

    def test_custom_triple_never_grants_library_evidence(self):
        # Declaring a real library triple on a CUSTOM record is contract-
        # allowed linkage (§4.3), but it can never produce library evidence.
        line, _ = self.capture_report_custom(
            1.0,
            formula_description="f(x)",
            input_refs=[],
            output_unit="ratio",
            method_id="finance.growth.cagr",
            method_version="1.0.0",
            spec_digest="sha256:spec-example",
        )
        record = json.loads(_payload_of(line))
        self.assertEqual(record["methodId"], "finance.growth.cagr")
        self.assertEqual(record["evidence"], "CUSTOM_UNVERIFIED")
        self.assertNotEqual(record["evidence"], "LIBRARY_CALL_DECLARED")

    def test_emitted_triple_comes_from_canonical_specs_not_result(self):
        # Even a hand-crafted instance of a REAL method id gets its triple
        # exclusively from the installed canonical specs.
        hand = FinanceMetricResult(
            method_id="finance.growth.cagr",
            value=0.5,
            unit="ratio",
            parameters={"beginningValue": 100.0, "endingValue": 150.0, "periods": 1},
        )
        line, _ = self.capture_report(hand)
        record = json.loads(_payload_of(line))
        self.assertEqual(
            record["methodVersion"],
            _SPECS["finance.growth.cagr"]["methodVersion"],
        )
        self.assertEqual(
            record["specDigest"], _SPECS["finance.growth.cagr"]["specDigest"]
        )


if __name__ == "__main__":
    unittest.main()
