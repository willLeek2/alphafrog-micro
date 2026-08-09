"""Real-Docker integration skeletons for the §7.1 bounded output capture.

Authoritative spec: 金融MethodSpec-V5-源码实施与Agent分工计划
  - §7.1 新增执行包装器 — bounded capture before bytes reach Python service
    memory / ``state.json``; timeout kills the whole process group; no
    leftover processes in reused containers.
  - §16.2 — these tests run via
    ``AF_RUN_DOCKER_TESTS=1 python3 -m unittest tests.test_sandbox_output_limits_integration -v``.
  - §18 stop conditions — unbounded user stdout/stderr must never reach the
    ``session.run()`` return value, and model-facing/preview output must never
    contain the finance marker prefix.

All tests are skipped unless ``AF_RUN_DOCKER_TESTS=1`` (this repo has no
pre-existing Docker-test convention, so the env-var guard requested by the
work plan is used). Heavy imports (``app.config`` / ``app.sandbox_runner``)
happen inside ``setUp`` so the module still loads — and the tests skip
cleanly — in environments without the Docker stack.

Exact bounded result field names await CONTRACT_BASE_SHA; helpers below mark
each pending binding.
"""

from __future__ import annotations

import os
import tempfile
import time
import unittest
from pathlib import Path

MARKER_FAMILY_PREFIX = "__AF_FINANCE_RESULT_"  # §4.1 标记族前缀

DOCKER_TESTS_ENABLED = os.environ.get("AF_RUN_DOCKER_TESTS") == "1"

LEAK_TOKEN = "AF_TIMEOUT_LEAK_TOKEN"


@unittest.skipUnless(
    DOCKER_TESTS_ENABLED,
    "real Docker integration tests; enable with AF_RUN_DOCKER_TESTS=1 (spec §16.2)",
)
class SandboxOutputLimitsIntegrationTest(unittest.TestCase):
    """End-to-end output-limit behavior of the sandbox runner (§7.1)."""

    def setUp(self) -> None:
        # Imported here (not at module top) so the file still loads — and the
        # tests skip cleanly — when Docker/llm_sandbox is unavailable.
        from app.config import SandboxConfig
        from app.sandbox_runner import (
            create_sandbox_session,
            get_session_container_id,
            run_in_open_session,
            run_in_sandbox,
        )

        self._tmp = tempfile.TemporaryDirectory(prefix="af-output-limits-it-")
        root = Path(self._tmp.name)
        data_dir = root / "data"
        (data_dir / "dataset-it").mkdir(parents=True)
        workspace_root = root / "runs"
        workspace_root.mkdir()

        self.config = SandboxConfig(
            data_dir=data_dir,
            max_concurrency=1,
            execution_timeout_seconds=90.0,
            memory_limit="512m",
            memswap_limit="512m",
            docker_backend="docker",
            workdir="/sandbox",
            log_level="INFO",
            sandbox_image=os.environ.get(
                "AF_SANDBOX_IMAGE", "alphafrog-sandbox-runtime:latest"
            ),
            skip_environment_setup=True,
            preinstalled_libraries=frozenset(),
            container_max_concurrency=1,
            pool_enabled=False,
            pool_min_size=0,
            pool_max_size=1,
            pool_acquire_timeout_seconds=30.0,
            pool_idle_timeout_seconds=None,
            pool_max_container_uses=None,
            workspace_root=str(workspace_root),
            compat_input_path_enabled=True,
        )
        self._create_sandbox_session = create_sandbox_session
        self._get_session_container_id = get_session_container_id
        self._run_in_open_session = run_in_open_session
        self._run_in_sandbox = run_in_sandbox

    def tearDown(self) -> None:
        if hasattr(self, "_tmp"):
            self._tmp.cleanup()

    # ------------------------------------------------------------------
    # helpers
    # ------------------------------------------------------------------

    def _task_bound_stdout(self, result: dict) -> str:
        """The stdout field that main.py writes into ``Task``/``state.json``.

        Field binding source: today's runner result dict (see
        tests.test_main_idempotency runner_result shape); final field binding
        awaits CONTRACT_BASE_SHA.
        """
        return result["stdout"]

    def _stdout_truncated_flag(self, result: dict) -> bool:
        """Locate the stdoutTruncated flag on the runner result.

        Exact key awaits CONTRACT_BASE_SHA (§7.1 capture-result field names).
        """
        for key in ("stdout_truncated", "stdoutTruncated"):
            if key in result:
                return bool(result[key])
        capture = result.get("capture") or result.get("capture_result") or {}
        if "stdoutTruncated" in capture:
            return bool(capture["stdoutTruncated"])
        self.fail(
            "runner result exposes no stdoutTruncated flag; field binding "
            "awaits CONTRACT_BASE_SHA (spec §7.1)"
        )

    def _model_facing_output(self, result: dict) -> str:
        """The bounded model-facing/preview output exposed by the runner.

        §7.1 (finance-records.jsonl 永不直接进入模型预览) and §18 (任一异步
        preview 含金融 marker 必须停止上线). Exact field name awaits
        CONTRACT_BASE_SHA.
        """
        for key in ("model_preview", "modelPreview", "preview"):
            value = result.get(key)
            if isinstance(value, str):
                return value
        self.fail(
            "runner result exposes no model-facing/preview field; field "
            "binding awaits CONTRACT_BASE_SHA (spec §7.1/§18)"
        )

    # ------------------------------------------------------------------
    # tests
    # ------------------------------------------------------------------

    def test_stdout_flood_stays_bounded_in_service_memory_path(self) -> None:
        """§7.1 + §18: user code prints tens of MB of stdout; the
        service-side memory path (the Task/state.json-bound result stdout)
        only ever holds bounded bytes, ``stdoutTruncated=true``, and the task
        still completes. Until the wrapper lands this fails because
        llm-sandbox 0.3.33 accumulates the full output into the returned
        string (§7.1 lines 861-862)."""
        code = (
            "import sys\n"
            "chunk = 'A' * 1023 + '\\n'\n"
            "for _ in range(30 * 1024):\n"  # ~30 MiB of stdout
            "    sys.stdout.write(chunk)\n"
            "sys.stdout.flush()\n"
        )
        started = time.monotonic()
        result = self._run_in_sandbox(
            self.config,
            "task-flood",
            "dataset-it",
            None,
            code,
            None,
            None,
            None,
        )
        elapsed = time.monotonic() - started

        self.assertLess(elapsed, 300.0, "flood run must complete in bounded time")
        self.assertEqual(result["exit_code"], 0, "the flooded task still completes")
        bounded_stdout = self._task_bound_stdout(result)
        # Exact effective cap awaits Task.effective_output_limits /
        # CONTRACT_BASE_SHA; assert the service never holds the full ~30 MiB.
        self.assertLessEqual(
            len(bounded_stdout.encode("utf-8")),
            2 * 1024 * 1024,
            "Task/state.json-bound stdout must stay bounded (§7.1, §18)",
        )
        self.assertTrue(
            self._stdout_truncated_flag(result),
            "stdoutTruncated must be true after a stdout flood (§7.1)",
        )

    def test_timeout_completes_bounded_and_leaves_no_process_in_reused_container(
        self,
    ) -> None:
        """§7.1 实施方式 6 + line 864: user code loops forever; the run
        completes in bounded time with a non-zero exit translation, and no
        leftover process from the timed-out run survives in the (reused)
        container. One shared session = one reused container across both
        runs."""
        infinite_code = (
            "import subprocess, sys, time\n"
            "subprocess.Popen([sys.executable, '-c',\n"
            "    'import time\\nwhile True:\\n"
            f"    time.sleep(1)  # {LEAK_TOKEN}'])\n"
            "while True:\n"
            "    time.sleep(0.2)\n"
        )
        probe_code = (
            "import os\n"
            f"token = b'{LEAK_TOKEN}'\n"
            "found = []\n"
            "for pid in os.listdir('/proc'):\n"
            "    if not pid.isdigit():\n"
            "        continue\n"
            "    try:\n"
            "        with open('/proc/' + pid + '/cmdline', 'rb') as handle:\n"
            "            data = handle.read()\n"
            "    except OSError:\n"
            "        continue\n"
            "    if token in data:\n"
            "        found.append(pid)\n"
            "print('LEFTOVER:' + ','.join(found))\n"
        )

        session = self._create_sandbox_session(self.config, execution_timeout=30.0)
        try:
            container_id = self._get_session_container_id(session)
            started = time.monotonic()
            result_timeout = self._run_in_open_session(
                self.config,
                session,
                "task-timeout",
                "dataset-it",
                None,
                infinite_code,
                None,
                None,
                3.0,
                container_id=container_id,
                pool_enabled=False,
            )
            elapsed = time.monotonic() - started
            self.assertLess(
                elapsed, 60.0, "timed-out run must complete in bounded time (§7.1)"
            )
            # Exact timeout exit/signal translation awaits CONTRACT_BASE_SHA.
            self.assertNotEqual(
                result_timeout["exit_code"],
                0,
                "infinite-loop run must not report success",
            )

            result_probe = self._run_in_open_session(
                self.config,
                session,
                "task-leftover-probe",
                "dataset-it",
                None,
                probe_code,
                None,
                None,
                30.0,
                container_id=container_id,
                pool_enabled=False,
            )
            self.assertEqual(
                result_probe["exit_code"],
                0,
                "the reused container must stay usable after a timeout",
            )
            self.assertIn("LEFTOVER:", result_probe["stdout"])
            leftover = result_probe["stdout"].split("LEFTOVER:", 1)[1].strip()
            self.assertEqual(
                leftover,
                "",
                "processes from the timed-out run survived inside the reused "
                f"container (spec §7.1 实施方式 6): pids={leftover!r}",
            )
        finally:
            session.close()

    def test_marker_prefix_never_leaks_into_model_facing_output(self) -> None:
        """§18 (任一异步 preview 含金融 marker 必须停止上线) + §7.1
        (finance-records.jsonl 永不直接进入模型预览): user code prints marker
        lines; the model-facing/preview output exposed by the runner must
        never contain the marker family prefix. Exact bounded result field
        awaits CONTRACT_BASE_SHA (see ``_model_facing_output``)."""
        code = (
            "import sys\n"
            "sys.stdout.write('ordinary line\\n')\n"
            "sys.stdout.write('__AF_FINANCE_RESULT_v1__'\n"
            "    + '{\"sourceResolverToolCallId\":\"call-1\",\"kind\":\"CAGR\",\"value\":0.05}\\n')\n"
            "sys.stdout.write('__AF_FINANCE_RESULT_v1__'\n"
            "    + '{\"sourceResolverToolCallId\":\"call-2\",\"customKey\":\"自定义\"}\\n')\n"
            "sys.stdout.flush()\n"
        )
        result = self._run_in_sandbox(
            self.config,
            "task-marker-leak",
            "dataset-it",
            None,
            code,
            None,
            None,
            None,
        )

        preview = self._model_facing_output(result)
        self.assertNotIn(
            MARKER_FAMILY_PREFIX,
            preview,
            "finance marker prefix leaked into model-facing/preview output "
            "(spec §18 stop condition)",
        )


if __name__ == "__main__":
    unittest.main()
