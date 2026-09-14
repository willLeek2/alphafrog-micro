"""D13 (26Q3) Python-side error-surface contract tests.

Pins the frozen D13 Python mappings (ccqwen 1f4e16d4 + 5c543fea, accepted by
codex 3d78edba; Cindy 8e21955c / 6a6e6158 / 45dafcda):

* bounded acceptance queue: create rejects 503 BEFORE persisting when the
  queue is full (Gateway maps 503 → OVERLOADED_OR_UNAVAILABLE);
* effective task timeout ceiling: after seconds/millis consistency
  normalization the FINAL effective timeout is validated once with
  ``0 < effective <= max_task_timeout_seconds`` (reject threshold
  ``effective > max``; the Gateway long-read margin is NOT part of the
  business limit). NaN / Infinity / negative / zero fail closed;
* CanonicalFingerprintMismatch → 400 (INVALID_ARGUMENT), while a genuine
  store-level OperationConflictError stays 409 (CONFLICT);
* result-not-finished → 425 Too Early (unknown 4xx → Gateway UNSPECIFIED,
  fail-closed polling) instead of the pre-D13 409;
* terminal-without-result → JSON 500 (DOWNSTREAM_FAILURE) instead of 400;
* operation lookup authoritative absence = 200 + found=false + blank error,
  never 404;
* every unhandled exception surfaces as a JSON 500 via the global handler;
* config fail-fast: ``queue_max_size < 1`` and
  ``max_task_timeout_seconds <= 0`` raise at load_config.

Additive on f8a383e0 (codex 5457b713 cross-contract MUST-FIX 1/2):

* MUST-FIX 2: the FINAL effective timeout is resolved and frozen at create
  time. When both timeout fields are absent the frozen effective value is
  ``config.execution_timeout_seconds`` (never re-derived at run time); a
  configured default above the ceiling fails at load_config AND at the
  create gate; ``max_task_timeout_seconds`` and
  ``execution_timeout_seconds`` must be finite (inf/nan rejected).
* MUST-FIX 1 (Python side): the canonical companion
  ``AF_SANDBOX_MAX_TASK_TIMEOUT_MILLIS`` must equal
  ``AF_SANDBOX_MAX_TASK_TIMEOUT_SECONDS * 1000`` exactly (decimal-exact)
  when present -- default/equal-override PASS, mismatch/non-finite/
  non-positive/non-numeric FAIL at load_config; an absent companion stays
  permitted for dev/test (release presence is enforced by the compose
  contract test in ccmax's release-binding commit).
"""

from __future__ import annotations

import asyncio
import dataclasses
import hashlib
import json
import os
import sys
import tempfile
import types
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

# app.config.load_config() runs at import time and requires a valid
# AF_SANDBOX_IMAGE digest reference (Spec §12). Pin a dev digest reference
# for the test process unless the environment already provides one, and pin
# strict-release mode: the 260814 default (local-image-id) would reject a
# registry digest reference, and this module's tests exercise D13 timeout
# binding, not the image reference policy.
os.environ.setdefault(
    "AF_SANDBOX_IMAGE",
    "registry.local/alphafrog/runtime@sha256:" + "a" * 64,
)
os.environ.setdefault("AF_SANDBOX_IMAGE_VERIFY_MODE", "strict-release")

llm_sandbox = types.ModuleType("llm_sandbox")
llm_sandbox.SandboxSession = object
llm_sandbox_exceptions = types.ModuleType("llm_sandbox.exceptions")
llm_sandbox_exceptions.SandboxTimeoutError = TimeoutError
sys.modules.setdefault("llm_sandbox", llm_sandbox)
sys.modules.setdefault("llm_sandbox.exceptions", llm_sandbox_exceptions)

from fastapi import HTTPException  # noqa: E402

from app import main  # noqa: E402
from app.canonical_fingerprint import CanonicalSandboxCreateSpec  # noqa: E402
from app.config import (  # noqa: E402
    load_config,
    validate_max_task_timeout_binding,
)
from app.models import ExecuteRequest, Task, TaskStatus  # noqa: E402
from app.task_store import DurableTaskStore  # noqa: E402


class MainD13ErrorSurfaceTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.store = DurableTaskStore(Path(self.temp_dir.name) / "state.json")
        self.queue: asyncio.Queue = asyncio.Queue()
        # Capture the module-level queue BEFORE patching so its bounded
        # wiring can be asserted (patch.object hides it while active).
        self.original_queue = main.task_queue
        self.store_patch = patch.object(main, "task_store", self.store)
        self.tasks_patch = patch.object(main, "tasks", self.store.tasks)
        self.queue_patch = patch.object(main, "task_queue", self.queue)
        self.store_patch.start()
        self.tasks_patch.start()
        self.queue_patch.start()
        # D14: this suite still exercises keyless create for timeout-surface
        # fixtures. Explicit non-production compat — production refuse coverage
        # lives in test_main_d14_operation_id_gate.py.
        self.config_patch = patch.object(
            main,
            "config",
            dataclasses.replace(main.config, allow_create_without_operation_id=True),
        )
        self.config_patch.start()

    async def asyncTearDown(self) -> None:
        self.config_patch.stop()
        self.queue_patch.stop()
        self.tasks_patch.stop()
        self.store_patch.stop()
        self.temp_dir.cleanup()

    def request(
        self,
        code: str = "print(1)",
        operation_id: str | None = "run-1:call-1:1",
        timeout_millis: int | None = 60_000,
        timeout_seconds: float | None = None,
    ) -> ExecuteRequest:
        code_hash = "sha256:" + hashlib.sha256(code.encode("utf-8")).hexdigest()
        if operation_id:
            spec = CanonicalSandboxCreateSpec(
                schema_version="sandbox_create_v1",
                operation_id=operation_id,
                code_hash=code_hash,
                immutable_dataset_snapshot_digest="sha256:" + "c" * 64,
                resource_class="STANDARD",
                memory_limit_bytes=512 * 1024 * 1024,
                timeout_millis=timeout_millis if timeout_millis is not None else 60_000,
                runtime_environment_version="python-runtime-v1",
                libraries_digest="sha256:" + "d" * 64,
                sandbox_options_digest="sha256:" + "e" * 64,
            )
            request_fingerprint = spec.request_fingerprint()
        else:
            # Keyless create: no operation_id/fingerprint pair (legal; the
            # idempotency validator requires both together or neither).
            request_fingerprint = None
        return ExecuteRequest(
            dataset_id="dataset-1",
            code=code,
            operation_id=operation_id,
            request_fingerprint=request_fingerprint,
            resource_class="STANDARD",
            capacity_units=1,
            memory_limit_bytes=512 * 1024 * 1024,
            timeout_millis=timeout_millis,
            timeout_seconds=timeout_seconds,
            runtime_environment_version="python-runtime-v1",
            canonical_spec_schema_version="sandbox_create_v1",
            code_hash=code_hash,
            immutable_dataset_snapshot_digest="sha256:" + "c" * 64,
            libraries_digest="sha256:" + "d" * 64,
            sandbox_options_digest="sha256:" + "e" * 64,
        )

    # --- bounded acceptance queue (503 → OVERLOADED_OR_UNAVAILABLE) --------

    async def test_queue_full_rejects_create_with_503_before_persist(self) -> None:
        bounded: asyncio.Queue = asyncio.Queue(maxsize=1)
        await bounded.put("placeholder-task")
        with patch.object(main, "task_queue", bounded):
            with self.assertRaises(HTTPException) as raised:
                await main.create_task(self.request())

        self.assertEqual(raised.exception.status_code, 503)
        # Nothing was persisted: the pre-create full() check fires first.
        self.assertEqual(len(self.store.tasks), 0)
        self.assertEqual(bounded.qsize(), 1)

    def test_module_level_queue_is_bounded_by_config(self) -> None:
        self.assertEqual(
            self.original_queue.maxsize, main.config.queue_max_size
        )

    # --- effective timeout ceiling (Cindy 8e21955c regression set) ---------

    def max_seconds(self) -> float:
        return main.config.max_task_timeout_seconds

    async def test_only_millis_over_max_is_invalid_argument(self) -> None:
        request = self.request(
            timeout_millis=int(self.max_seconds() * 1000) + 1
        )
        with self.assertRaises(HTTPException) as raised:
            await main.create_task(request)
        self.assertEqual(raised.exception.status_code, 400)
        self.assertIn("effective task timeout", raised.exception.detail)
        self.assertEqual(self.queue.qsize(), 0)

    async def test_only_seconds_over_max_is_invalid_argument(self) -> None:
        # Legacy seconds-only request: keyless (the canonical spec requires
        # timeout_millis, so a keyed seconds-only create cannot be valid).
        request = self.request(
            operation_id=None,
            timeout_millis=None,
            timeout_seconds=self.max_seconds() + 1,
        )
        with self.assertRaises(HTTPException) as raised:
            await main.create_task(request)
        self.assertEqual(raised.exception.status_code, 400)
        self.assertIn("effective task timeout", raised.exception.detail)

    async def test_both_fields_agree_over_max_is_invalid_argument(self) -> None:
        over_millis = int(self.max_seconds() * 1000) + 1
        request = self.request(
            timeout_millis=over_millis,
            timeout_seconds=over_millis / 1000.0,
        )
        with self.assertRaises(HTTPException) as raised:
            await main.create_task(request)
        self.assertEqual(raised.exception.status_code, 400)
        self.assertIn("effective task timeout", raised.exception.detail)

    async def test_both_fields_conflict_stays_invalid_argument(self) -> None:
        request = self.request(timeout_millis=60_000, timeout_seconds=70.0)
        with self.assertRaises(HTTPException) as raised:
            await main.create_task(request)
        self.assertEqual(raised.exception.status_code, 400)
        self.assertIn("conflicts", raised.exception.detail)

    async def test_exactly_max_is_accepted(self) -> None:
        request = self.request(timeout_millis=int(self.max_seconds() * 1000))
        response = await main.create_task(request)
        self.assertFalse(response.existing)
        self.assertEqual(self.queue.qsize(), 1)

    async def test_fractional_seconds_max_plus_epsilon_is_rejected(self) -> None:
        # Mirror of Cindy 1b29792d MF2: a fractional effective timeout just
        # above the ceiling must NOT slip through truncation. Seconds-domain
        # float comparison rejects 1800.0009 > 1800.0 directly.
        request = self.request(
            operation_id=None,
            timeout_millis=None,
            timeout_seconds=self.max_seconds() + 0.0009,
        )
        with self.assertRaises(HTTPException) as raised:
            await main.create_task(request)
        self.assertEqual(raised.exception.status_code, 400)
        self.assertIn("effective task timeout", raised.exception.detail)

    async def test_nan_seconds_fail_closed(self) -> None:
        request = self.request(
            operation_id=None, timeout_millis=None, timeout_seconds=float("nan")
        )
        with self.assertRaises(HTTPException) as raised:
            await main.create_task(request)
        self.assertEqual(raised.exception.status_code, 400)
        self.assertIn("effective task timeout", raised.exception.detail)

    async def test_infinity_seconds_fail_closed(self) -> None:
        request = self.request(
            operation_id=None, timeout_millis=None, timeout_seconds=float("inf")
        )
        with self.assertRaises(HTTPException) as raised:
            await main.create_task(request)
        self.assertEqual(raised.exception.status_code, 400)
        self.assertIn("effective task timeout", raised.exception.detail)

    async def test_negative_seconds_fail_closed(self) -> None:
        request = self.request(
            operation_id=None, timeout_millis=None, timeout_seconds=-1.0
        )
        with self.assertRaises(HTTPException) as raised:
            await main.create_task(request)
        self.assertEqual(raised.exception.status_code, 400)
        self.assertIn("effective task timeout", raised.exception.detail)

    async def test_zero_seconds_fail_closed(self) -> None:
        request = self.request(
            operation_id=None, timeout_millis=None, timeout_seconds=0.0
        )
        with self.assertRaises(HTTPException) as raised:
            await main.create_task(request)
        self.assertEqual(raised.exception.status_code, 400)
        self.assertIn("effective task timeout", raised.exception.detail)

    # --- fingerprint mismatch 400 vs store conflict 409 --------------------

    async def test_store_conflict_same_operation_id_stays_409(self) -> None:
        first = self.request(code="print(1)")
        await main.create_task(first)
        # Same operation_id, different payload, its OWN correct fingerprint:
        # a genuine state conflict → 409 → CONFLICT (unchanged by D13).
        second = self.request(code="print(2)")
        with self.assertRaises(HTTPException) as raised:
            await main.create_task(second)
        self.assertEqual(raised.exception.status_code, 409)
        self.assertEqual(self.queue.qsize(), 1)

    # --- result endpoint surface -------------------------------------------

    async def test_result_not_finished_is_425(self) -> None:
        for status in (TaskStatus.QUEUED, TaskStatus.RUNNING):
            task = Task(
                task_id=f"task-{status.value.lower()}",
                status=status,
                request=ExecuteRequest(dataset_id="dataset-1", code="print(1)"),
            )
            self.store.save(task)
            with self.assertRaises(HTTPException) as raised:
                await main.get_task_result(task.task_id)
            self.assertEqual(raised.exception.status_code, 425)

    async def test_failed_terminal_without_result_is_json_500(self) -> None:
        task = Task(
            task_id="task-failed-no-result",
            status=TaskStatus.FAILED,
            request=ExecuteRequest(dataset_id="dataset-1", code="print(1)"),
            error="restart-recovery gap",
        )
        self.store.save(task)
        with self.assertRaises(HTTPException) as raised:
            await main.get_task_result(task.task_id)
        self.assertEqual(raised.exception.status_code, 500)
        self.assertIn("restart-recovery gap", raised.exception.detail)

    # --- operation lookup authoritative absence (hard rule) ----------------

    async def test_operation_lookup_unknown_is_authoritative_absence(self) -> None:
        response = await main.get_task_by_operation_id("never-seen")
        self.assertFalse(response.found)
        self.assertIsNone(response.task_id)
        # error stays blank/None — absence is authoritative, never a 404.
        self.assertFalse(response.error)

    # --- global unhandled-exception handler (JSON 500) ----------------------

    async def test_unhandled_exception_handler_is_registered_and_json(self) -> None:
        self.assertIn(Exception, main.app.exception_handlers)
        fake_request = SimpleNamespace(
            method="GET", url=SimpleNamespace(path="/tasks/boom")
        )
        response = await main.unhandled_exception_handler(
            fake_request, RuntimeError("persist layer exploded")
        )
        self.assertEqual(response.status_code, 500)
        body = json.loads(response.body)
        self.assertIn("persist layer exploded", body["detail"])
        self.assertIn("RuntimeError", body["detail"])

    # --- config fail-fast ----------------------------------------------------

    def test_queue_max_size_below_one_fails_config_load(self) -> None:
        with patch.dict(os.environ, {"AF_SANDBOX_QUEUE_MAX_SIZE": "0"}):
            with self.assertRaises(ValueError):
                load_config()

    def test_max_task_timeout_seconds_non_positive_fails_config_load(self) -> None:
        with patch.dict(os.environ, {"AF_SANDBOX_MAX_TASK_TIMEOUT_SECONDS": "0"}):
            with self.assertRaises(ValueError):
                load_config()

    # --- MF2: final effective timeout freeze (codex 5457b713 MUST-FIX 2) ---

    async def test_absent_timeout_freezes_configured_default(self) -> None:
        # Keyless create with BOTH timeout fields absent: the final
        # effective timeout is config.execution_timeout_seconds and must be
        # FROZEN into the persisted request (run_in_sandbox / pool.run_task
        # must never re-derive an unvalidated value at execution time).
        request = self.request(
            operation_id=None, timeout_millis=None, timeout_seconds=None
        )
        response = await main.create_task(request)
        self.assertFalse(response.existing)
        self.assertEqual(self.queue.qsize(), 1)
        task = self.store.tasks[response.task_id]
        self.assertEqual(
            task.request.timeout_seconds, main.config.execution_timeout_seconds
        )

    async def test_absent_timeout_rejected_when_configured_default_over_max(self) -> None:
        # Create-gate half of codex 5457b713's exact counter-example:
        # configured default execution timeout 1801 > max 1800, both request
        # timeout fields absent → the frozen effective 1801 must be rejected
        # at acceptance (400), never persisted, never run.
        over_config = dataclasses.replace(
            main.config,
            execution_timeout_seconds=main.config.max_task_timeout_seconds + 1,
        )
        request = self.request(
            operation_id=None, timeout_millis=None, timeout_seconds=None
        )
        with patch.object(main, "config", over_config):
            with self.assertRaises(HTTPException) as raised:
                await main.create_task(request)
        self.assertEqual(raised.exception.status_code, 400)
        self.assertIn("effective task timeout", raised.exception.detail)
        self.assertEqual(self.queue.qsize(), 0)
        self.assertEqual(len(self.store.tasks), 0)

    def test_load_config_rejects_execution_timeout_over_max(self) -> None:
        # Startup half of codex 5457b713's exact counter-example: a
        # configured default execution timeout above the ceiling is a
        # startup-time contradiction (fail-fast), not a per-request
        # discovery.
        env = {
            "AF_SANDBOX_EXECUTION_TIMEOUT": "1801",
            "AF_SANDBOX_MAX_TASK_TIMEOUT_SECONDS": "1800",
        }
        with patch.dict(os.environ, env):
            with self.assertRaises(ValueError):
                load_config()

    def test_load_config_accepts_execution_timeout_at_exactly_max(self) -> None:
        env = {
            "AF_SANDBOX_EXECUTION_TIMEOUT": "1800",
            "AF_SANDBOX_MAX_TASK_TIMEOUT_SECONDS": "1800",
        }
        with patch.dict(os.environ, env):
            self.assertEqual(load_config().execution_timeout_seconds, 1800.0)

    def test_load_config_rejects_infinite_max_task_timeout(self) -> None:
        # The pre-fix `<= 0` check accepted inf, silently disabling the
        # ceiling (codex 5457b713 MUST-FIX 2).
        with patch.dict(os.environ, {"AF_SANDBOX_MAX_TASK_TIMEOUT_SECONDS": "inf"}):
            with self.assertRaises(ValueError):
                load_config()

    def test_load_config_rejects_nan_max_task_timeout(self) -> None:
        with patch.dict(os.environ, {"AF_SANDBOX_MAX_TASK_TIMEOUT_SECONDS": "nan"}):
            with self.assertRaises(ValueError):
                load_config()

    def test_load_config_rejects_non_finite_execution_timeout(self) -> None:
        for raw in ("inf", "nan"):
            with self.subTest(raw=raw):
                with patch.dict(os.environ, {"AF_SANDBOX_EXECUTION_TIMEOUT": raw}):
                    with self.assertRaises(ValueError):
                        load_config()

    # --- MF1: release-binding companion millis (codex 5457b713 MUST-FIX 1) --

    def test_companion_millis_equal_default_passes(self) -> None:
        env = {
            "AF_SANDBOX_MAX_TASK_TIMEOUT_SECONDS": "1800",
            "AF_SANDBOX_MAX_TASK_TIMEOUT_MILLIS": "1800000",
        }
        with patch.dict(os.environ, env):
            self.assertEqual(load_config().max_task_timeout_seconds, 1800.0)

    def test_companion_millis_equal_override_passes(self) -> None:
        env = {
            "AF_SANDBOX_MAX_TASK_TIMEOUT_SECONDS": "1200",
            "AF_SANDBOX_MAX_TASK_TIMEOUT_MILLIS": "1200000",
        }
        with patch.dict(os.environ, env):
            self.assertEqual(load_config().max_task_timeout_seconds, 1200.0)

    def test_companion_millis_fractional_seconds_exact_passes(self) -> None:
        # Decimal-exact equivalence: no float-epsilon hole for fractional
        # seconds (0.1-style binary artifacts must not cause false FAIL).
        # execution timeout lowered alongside so it stays within the ceiling.
        env = {
            "AF_SANDBOX_MAX_TASK_TIMEOUT_SECONDS": "1.5",
            "AF_SANDBOX_MAX_TASK_TIMEOUT_MILLIS": "1500",
            "AF_SANDBOX_EXECUTION_TIMEOUT": "1.5",
        }
        with patch.dict(os.environ, env):
            self.assertEqual(load_config().max_task_timeout_seconds, 1.5)

    def test_companion_millis_mismatch_fails_fast(self) -> None:
        for millis in ("1799999", "1800001", "1800000.5"):
            with self.subTest(millis=millis):
                env = {
                    "AF_SANDBOX_MAX_TASK_TIMEOUT_SECONDS": "1800",
                    "AF_SANDBOX_MAX_TASK_TIMEOUT_MILLIS": millis,
                }
                with patch.dict(os.environ, env):
                    with self.assertRaises(ValueError):
                        load_config()

    def test_companion_millis_non_positive_fails_fast(self) -> None:
        for millis in ("0", "-1000"):
            with self.subTest(millis=millis):
                env = {
                    "AF_SANDBOX_MAX_TASK_TIMEOUT_SECONDS": "1800",
                    "AF_SANDBOX_MAX_TASK_TIMEOUT_MILLIS": millis,
                }
                with patch.dict(os.environ, env):
                    with self.assertRaises(ValueError):
                        load_config()

    def test_companion_millis_non_finite_fails_fast(self) -> None:
        for millis in ("inf", "nan"):
            with self.subTest(millis=millis):
                env = {
                    "AF_SANDBOX_MAX_TASK_TIMEOUT_SECONDS": "1800",
                    "AF_SANDBOX_MAX_TASK_TIMEOUT_MILLIS": millis,
                }
                with patch.dict(os.environ, env):
                    with self.assertRaises(ValueError):
                        load_config()

    def test_companion_millis_non_numeric_fails_fast(self) -> None:
        for seconds, millis in (("1800", "abc"), ("xyz", "1800000")):
            with self.subTest(seconds=seconds, millis=millis):
                env = {
                    "AF_SANDBOX_MAX_TASK_TIMEOUT_SECONDS": seconds,
                    "AF_SANDBOX_MAX_TASK_TIMEOUT_MILLIS": millis,
                }
                with patch.dict(os.environ, env):
                    with self.assertRaises(ValueError):
                        load_config()

    def test_companion_millis_absent_still_permitted_for_dev(self) -> None:
        # Dev/test environments without the release binding keep working;
        # release PRESENCE is enforced by the compose contract test in the
        # release-binding commit (codex a1b749ad), not by mandatory
        # presence here.
        minimal_env = {
            "AF_SANDBOX_IMAGE": os.environ["AF_SANDBOX_IMAGE"],
            # 260814 scheduler-03: clear=True 会清掉模块级 strict-release 固定，
            # 而默认 local-image-id 模式拒绝 digest 引用；minimal env 必须显式
            # 带上校验模式。
            "AF_SANDBOX_IMAGE_VERIFY_MODE": "strict-release",
            "AF_SANDBOX_MAX_TASK_TIMEOUT_SECONDS": "1200",
        }
        with patch.dict(os.environ, minimal_env, clear=True):
            self.assertEqual(load_config().max_task_timeout_seconds, 1200.0)

    def test_binding_seam_mismatch_error_names_both_release_keys(self) -> None:
        # The compose contract test reuses validate_max_task_timeout_binding;
        # its failure must be diagnosable from the message alone.
        with self.assertRaises(ValueError) as raised:
            validate_max_task_timeout_binding("1800", "999")
        message = str(raised.exception)
        self.assertIn("AF_SANDBOX_MAX_TASK_TIMEOUT_MILLIS", message)
        self.assertIn("AF_SANDBOX_MAX_TASK_TIMEOUT_SECONDS", message)
        self.assertIn("999", message)


if __name__ == "__main__":
    unittest.main()
