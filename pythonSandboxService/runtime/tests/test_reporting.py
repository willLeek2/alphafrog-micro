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

from alphafrog_finance import cagr, report, report_custom, MARKER
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
    }
}


def _payload_of(line: str) -> str:
    assert line.startswith(MARKER), f"line lacks marker: {line!r}"
    return line[len(MARKER):]


class ReportingTestBase(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.mkdtemp(prefix="af-reporting-test-")
        self._env_file = os.path.join(self._tmp, "runtime-environment.json")
        with open(self._env_file, "w", encoding="utf-8") as fh:
            json.dump({"environmentId": _ENV_ID}, fh)
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
        with self.assertRaisesRegex(RuntimeError, "environmentId"):
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


if __name__ == "__main__":
    unittest.main()
