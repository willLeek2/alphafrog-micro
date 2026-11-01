"""Failing TDD skeletons for the future module ``app.bounded_exec_wrapper``.

Authoritative spec: 金融MethodSpec-V5-源码实施与Agent分工计划
  - §7.1 新增执行包装器 — wrapper behavior plus the input/output JSON shapes
    (wrapper input keys ``scriptPath``/``timeoutSeconds``/``effectiveOutputLimits``/
    ``runtimeEnvironmentPath``; bounded files ``capture/stdout.bin``,
    ``capture/stderr.bin``, ``capture/finance-records.jsonl`` and the
    ``capture/capture-result.json`` summary).
  - §4.1 stdout 记录字节规则 — marker family ``__AF_FINANCE_RESULT_``, first
    version marker ``__AF_FINANCE_RESULT_v1__``, batch digest rule.
  - §18 停止条件 — the wrapper must never let full user stdout/stderr flow
    back into the ``session.run()`` return value.

All tests in ``BoundedExecWrapperTest`` import the module inside ``setUp`` so
they fail loudly with ``ModuleNotFoundError`` until
``pythonSandboxService/app/bounded_exec_wrapper.py`` lands.
``RecordDigestReferenceTest`` is pure §4.1 logic and must PASS even before the
wrapper exists (its reference digest helper is defined inline below).

Invocation pinned here (final binding awaits CONTRACT_BASE_SHA): the wrapper is
started as ``python3 -m app.bounded_exec_wrapper <wrapper-input.json>`` with
cwd at the pythonSandboxService root; the input JSON has exactly the §7.1
example shape and the four bounded outputs land under ``<task-dir>/capture/``.

Run: ``cd pythonSandboxService && python3 -m unittest tests.test_bounded_exec_wrapper -v``
"""

from __future__ import annotations

import hashlib
import importlib
import json
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path

SERVICE_ROOT = Path(__file__).resolve().parents[1]

MARKER_FAMILY_PREFIX = "__AF_FINANCE_RESULT_"  # §4.1 标记族前缀
MARKER_V1_PREFIX = "__AF_FINANCE_RESULT_v1__"  # §4.1 首版标记

EMPTY_BATCH_DIGEST = hashlib.sha256(b"").hexdigest()  # §4.1 空记录批次摘要

# Shape mirrors the §7.1 effectiveOutputLimits example (values are test-local).
DEFAULT_LIMITS = {
    "stdoutMaxBytes": 1024 * 1024,
    "stderrMaxBytes": 256 * 1024,
    "recordChannelMaxBytes": 256 * 1024,
    "recordChannelMaxRecords": 128,
}


class BoundedExecWrapperTest(unittest.TestCase):
    """Behavioral contract for ``app.bounded_exec_wrapper`` (spec §7.1)."""

    def setUp(self) -> None:
        # Import inside setUp so every test fails loudly with
        # ModuleNotFoundError until the implementation lands (no repo
        # convention exists yet for not-yet-written modules).
        self.wrapper = importlib.import_module("app.bounded_exec_wrapper")
        self._tmp = tempfile.TemporaryDirectory(prefix="af-wrapper-test-")
        self.task_dir = Path(self._tmp.name)

    def tearDown(self) -> None:
        if hasattr(self, "_tmp"):
            self._tmp.cleanup()

    # ------------------------------------------------------------------
    # helpers
    # ------------------------------------------------------------------

    def _write_script(self, source: str, name: str = "user_code.py") -> Path:
        script = self.task_dir / name
        script.write_text(source, encoding="utf-8")
        return script

    def _run_wrapper(
        self,
        script: Path,
        *,
        timeout_seconds: int = 30,
        limits: dict | None = None,
    ) -> subprocess.CompletedProcess:
        """Run the wrapper the way §7.1 shapes its input.

        The exact CLI binding (argv path vs stdin) awaits CONTRACT_BASE_SHA;
        the JSON payload shape below is already fixed by the §7.1 example.
        """
        merged = dict(DEFAULT_LIMITS)
        if limits:
            merged.update(limits)
        runtime_env = self.task_dir / "runtime-environment.json"
        if not runtime_env.exists():
            # The runtime-environment.json schema belongs to another work
            # package; these tests only need the path to exist.
            runtime_env.write_text("{}", encoding="utf-8")
        # D15 §4.2 (Scenario B): the wrapper now requires task-scoped
        # taskWorkspace + taskEnvironment (AF_TASK_* isolation moves out of
        # the global sitecustomize.py). Tests stage them under the task_dir.
        task_workspace = str(self.task_dir)
        task_env = {
            "AF_TASK_WORKSPACE": task_workspace,
            "AF_TASK_ARTIFACT_DIR": f"{task_workspace}/artifacts",
            "AF_TASK_TMP_DIR": f"{task_workspace}/tmp",
            "AF_TASK_METRICS_PATH": f"{task_workspace}/metrics/loader.jsonl",
        }
        payload = {
            "scriptPath": str(script),
            "timeoutSeconds": timeout_seconds,
            "effectiveOutputLimits": merged,
            "runtimeEnvironmentPath": str(runtime_env),
            "taskWorkspace": task_workspace,
            "taskEnvironment": task_env,
            "loaderPythonPath": task_workspace,
        }
        input_path = self.task_dir / "wrapper-input.json"
        input_path.write_text(json.dumps(payload), encoding="utf-8")
        return subprocess.run(
            [sys.executable, "-m", "app.bounded_exec_wrapper", str(input_path)],
            cwd=SERVICE_ROOT,
            capture_output=True,
            timeout=120,
        )

    def _capture_dir(self) -> Path:
        return self.task_dir / "capture"

    def _read_capture_result(self) -> dict:
        with open(self._capture_dir() / "capture-result.json", encoding="utf-8") as handle:
            return json.load(handle)

    def _read_capture_bytes(self, name: str) -> bytes:
        return (self._capture_dir() / name).read_bytes()

    # ------------------------------------------------------------------
    # tests
    # ------------------------------------------------------------------

    # ------------------------------------------------------------------
    # unknown-marker audit + the single joint recordChannelMaxBytes budget
    # (contract §4.1/§4.2)
    # ------------------------------------------------------------------

class RecordDigestReferenceTest(unittest.TestCase):
    """Pure-logic §4.1 batch-digest tests.

    These must PASS even before ``app.bounded_exec_wrapper`` exists; the
    reference helper below is the normative reading of the spec rule.
    """

    @staticmethod
    def _reference_batch_digest(payloads) -> str:
        # Reference implementation of spec §4.1 批次摘要规则:
        # batch digest = SHA-256 over the sequence of
        # [4-byte big-endian length || rawPayload UTF-8] for each record in
        # original order; empty batch = SHA-256 of empty bytes.
        hasher = hashlib.sha256()
        for payload in payloads:
            raw = payload.encode("utf-8")
            hasher.update(struct.pack(">I", len(raw)))
            hasher.update(raw)
        return hasher.hexdigest()

class ProcessTreeSweepTest(unittest.TestCase):
    """P0-2 (codex b39f5e6b / 1d81ca85): bounded post-exit process-tree
    cleanup.  A child that exits promptly may leave grandchildren that
    inherited the capture pipes; the drain threads only reach EOF when the
    LAST pipe holder dies, so the wrapper must actively sweep the tree —
    promptly, and with no survivor — instead of blocking to its own timeout
    and still reporting success (the live red baseline f319ad54: main
    exit(0) + sleep-120 grandchild produced wrapperExitCode 0 after ~30s
    with the grandchild still alive).

    Non-docker: these run on the macOS host (no /proc, no prctl) and pin
    the ppid-chain + lsof pipe-holder fallback; the Linux subreaper + /proc
    path is exercised by the docker-gated codex repro in
    tests/test_wrapper_uid_isolation.py.
    """

    def setUp(self) -> None:
        self.wrapper = importlib.import_module("app.bounded_exec_wrapper")
        self._tmp = tempfile.TemporaryDirectory(prefix="af-sweep-test-")
        self.task_dir = Path(self._tmp.name).resolve()

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def _write_script(self, source: str) -> Path:
        script = self.task_dir / "user_code.py"
        script.write_text(source, encoding="utf-8")
        return script

    def _run_wrapper(self, script: Path, timeout_seconds: int = 30):
        # D15 §4.2 (Scenario B): wrapper-input now carries AF_TASK_* in
        # taskEnvironment + a task-local taskWorkspace, replacing the legacy
        # global /sandbox/sitecustomize.py bootstrap.
        task_workspace = str(self.task_dir)
        task_env = {
            "AF_TASK_WORKSPACE": task_workspace,
            "AF_TASK_ARTIFACT_DIR": f"{task_workspace}/artifacts",
            "AF_TASK_TMP_DIR": f"{task_workspace}/tmp",
            "AF_TASK_METRICS_PATH": f"{task_workspace}/metrics/loader.jsonl",
        }
        payload = {
            "scriptPath": str(script),
            "timeoutSeconds": timeout_seconds,
            "effectiveOutputLimits": dict(DEFAULT_LIMITS),
            "runtimeEnvironmentPath": str(self.task_dir / "runtime-environment.json"),
            "taskWorkspace": task_workspace,
            "taskEnvironment": task_env,
            "loaderPythonPath": task_workspace,
        }
        (self.task_dir / "runtime-environment.json").write_text("{}", encoding="utf-8")
        input_path = self.task_dir / "wrapper-input.json"
        input_path.write_text(json.dumps(payload), encoding="utf-8")
        started = time.monotonic()
        completed = subprocess.run(
            [sys.executable, "-m", "app.bounded_exec_wrapper", str(input_path)],
            cwd=SERVICE_ROOT,
            capture_output=True,
            timeout=90,
        )
        return completed, time.monotonic() - started

    def _read_capture_result(self) -> dict:
        return json.loads(
            (self.task_dir / "capture" / "capture-result.json").read_text(
                encoding="utf-8"
            )
        )

    def _assert_grandchild_dead(self, wall_seconds: float) -> None:
        pid_path = self.task_dir / "grandchild.pid"
        self.assertTrue(pid_path.exists(), "user script did not record the grandchild pid")
        grandchild_pid = int(pid_path.read_text(encoding="utf-8").strip())
        deadline = time.monotonic() + 10.0
        while True:
            try:
                os.kill(grandchild_pid, 0)
            except ProcessLookupError:
                return  # gone: the sweep killed it
            self.assertLess(
                time.monotonic(),
                deadline,
                f"grandchild pid {grandchild_pid} survived the wrapper "
                f"(wall {wall_seconds:.1f}s) — the process-tree sweep missed it",
            )
            time.sleep(0.2)

class StreamingClassifierTest(unittest.TestCase):
    """P0-3 (codex 21aaf3b8): the bounded streaming line state machine.

    Unit tier over ``_StreamingStdoutClassifier``: the old
    ``pending + chunk`` accumulation is gone, so a no-newline stdout can
    never grow pending unbounded (quadratic concat -> OOM), while marker
    classification semantics stay EXACTLY the frozen classify_line rules.
    """

    def setUp(self) -> None:
        self.wrapper = importlib.import_module("app.bounded_exec_wrapper")
        self._tmp = tempfile.TemporaryDirectory(prefix="af-classifier-test-")
        self.dir = Path(self._tmp.name)
        self._closeables = []

    def tearDown(self) -> None:
        for closeable in self._closeables:
            try:
                closeable()
            except Exception:
                pass
        self._tmp.cleanup()

    def _make(
        self,
        *,
        stdout_cap: int = 1024 * 1024,
        record_budget: int = 256 * 1024,
        max_records: int = 128,
    ):
        import io as _io

        sink = self.wrapper._BoundedByteSink(_io.BytesIO(), stdout_cap)
        budget = self.wrapper._JointByteBudget(record_budget)
        records = self.wrapper._RecordChannel(
            self.dir / "finance-records.jsonl", max_records, budget
        )
        audit = self.wrapper._UnknownMarkerAudit(
            self.dir / "finance-records-unknown-marker.jsonl", budget
        )
        classifier = self.wrapper._StreamingStdoutClassifier(
            sink, records, audit, budget
        )
        self._closeables.append(records.finalize)
        self._closeables.append(audit.finalize)
        return classifier, sink, records, audit, budget

class StreamingStdoutEndToEndTest(unittest.TestCase):
    """P0-3 (codex 21aaf3b8) end-to-end tier: the REAL wrapper subprocess
    bounds a multi-MB no-newline stdout at the frozen cap and the child
    still exits normally."""

    def setUp(self) -> None:
        importlib.import_module("app.bounded_exec_wrapper")
        self._tmp = tempfile.TemporaryDirectory(prefix="af-stdout-e2e-")
        self.task_dir = Path(self._tmp.name).resolve()

    def tearDown(self) -> None:
        self._tmp.cleanup()

if __name__ == "__main__":
    unittest.main()
