from __future__ import annotations

import json
import tempfile
import unittest
from datetime import datetime
from pathlib import Path
from typing import Optional

import argparse
import threading
import time

from af_light_client.__main__ import PeriodicTuiSnapshotLoop, _finish_with_rest, _render
from af_light_client.config import LightClientConfig, TuiSnapshotDebugConfig
from af_light_client.debug import DebugRunLogger, TuiBatchSnapshotWriter, redact
from af_light_client.events import RunViewState, ViewSnapshot
from af_light_client.http_client import WarningStore
from af_light_client.render import TerminalRenderer


class RedactTest(unittest.TestCase):
    def test_redacts_sensitive_keys_and_auth_strings(self) -> None:
        payload = {
            "password": "p",
            "Authorization": "Bearer abc.def",
            "url": "http://x/stream?token=abc&other=1",
            "headers": {"Cookie": "access_token=secret; Path=/"},
            "nested": [{"api_key": "k"}],
        }
        redacted = redact(payload)
        self.assertEqual(redacted["password"], "<redacted>")
        self.assertEqual(redacted["Authorization"], "<redacted>")
        self.assertEqual(redacted["headers"]["Cookie"], "<redacted>")
        self.assertEqual(redacted["nested"][0]["api_key"], "<redacted>")
        self.assertIn("token=<redacted>", redacted["url"])


class DebugRunLoggerTest(unittest.TestCase):
    def test_disabled_logger_creates_no_directory(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            logger = DebugRunLogger(enabled=False, output_root=td)
            logger.write_json("x.json", {"a": 1})
            self.assertEqual(list(Path(td).iterdir()), [])

    def test_enabled_logger_writes_timestamped_redacted_files(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            logger = DebugRunLogger(
                enabled=True,
                output_root=td,
                now=datetime(2026, 6, 11, 21, 40, 1),
            )
            logger.write_json("config.json", {"password": "secret", "ok": 1})
            logger.append_jsonl("events.jsonl", {"url": "http://x?a=1&token=secret"})
            run_dir = Path(td) / "20260611-214001"
            self.assertTrue(run_dir.is_dir())
            config = json.loads((run_dir / "config.json").read_text())
            self.assertEqual(config["password"], "<redacted>")
            self.assertEqual(config["ok"], 1)
            line = (run_dir / "events.jsonl").read_text().strip()
            self.assertIn("token=<redacted>", line)


class TuiBatchSnapshotWriterTest(unittest.TestCase):
    def test_writes_due_snapshots_and_rolls_batches(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            writer = TuiBatchSnapshotWriter(
                enabled=True,
                output_root=td,
                started_at=datetime(2026, 6, 13, 21, 40, 1),
                config=TuiSnapshotDebugConfig(
                    enabled=True,
                    interval_ms=250,
                    batch_interval_ms=1000,
                ),
            )

            writer.write_if_due(
                "first screen\n",
                monotonic_now=10.0,
                wall_now=datetime(2026, 6, 13, 21, 40, 2),
            )
            writer.write_if_due(
                "too soon\n",
                monotonic_now=10.1,
                wall_now=datetime(2026, 6, 13, 21, 40, 3),
            )
            writer.write_if_due(
                "second screen\n",
                monotonic_now=10.3,
                wall_now=datetime(2026, 6, 13, 21, 40, 4),
            )
            writer.write_if_due(
                "next batch\n",
                monotonic_now=11.1,
                wall_now=datetime(2026, 6, 13, 21, 40, 5),
            )

            debug_dir = Path(td) / "20260613-214001" / "debug"
            batch1 = (debug_dir / "tui_batch001.txt").read_text(encoding="utf-8")
            batch2 = (debug_dir / "tui_batch002.txt").read_text(encoding="utf-8")
            self.assertIn("time 20260613-214002\nsnapshot:\n\nfirst screen\n\n---\n", batch1)
            self.assertNotIn("too soon", batch1)
            self.assertIn("second screen", batch1)
            self.assertIn("next batch", batch2)

    def test_force_writes_even_before_interval(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            writer = TuiBatchSnapshotWriter(
                enabled=True,
                output_root=td,
                started_at=datetime(2026, 6, 13, 21, 40, 1),
                config=TuiSnapshotDebugConfig(
                    enabled=True,
                    interval_ms=1000,
                    batch_interval_ms=5000,
                ),
            )

            writer.write_if_due("first\n", monotonic_now=1.0)
            writer.write_if_due("forced\n", monotonic_now=1.1, force=True)

            text = (
                Path(td)
                / "20260613-214001"
                / "debug"
                / "tui_batch001.txt"
            ).read_text(encoding="utf-8")
            self.assertIn("first", text)
            self.assertIn("forced", text)

    def test_tui_snapshots_work_without_debug_logs(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            writer = TuiBatchSnapshotWriter(
                enabled=True,
                output_root=td,
                started_at=datetime(2026, 6, 13, 21, 40, 1),
                config=TuiSnapshotDebugConfig(
                    enabled=True,
                    interval_ms=250,
                    batch_interval_ms=5000,
                ),
            )
            writer.write_if_due("screen\n", monotonic_now=1.0)
            debug_dir = Path(td) / "20260613-214001" / "debug"
            self.assertTrue((debug_dir / "tui_batch001.txt").is_file())


class SnapshotTextTest(unittest.TestCase):
    def _snapshot(self) -> ViewSnapshot:
        return ViewSnapshot(
            run_id="run-abcdef123456",
            status="RUNNING",
            phase="plan",
            workflow="linear",
            last_seq=3,
            trace=["step one failed", "step two completed"],
            dag_nodes={},
            warnings=["login ok"],
            final_answer="done",
        )

    def test_snapshot_text_matches_build_lines_without_ansi(self) -> None:
        snapshot = self._snapshot()
        renderer = TerminalRenderer(max_lines=14, color=False)
        text = renderer.snapshot_text(snapshot)
        self.assertEqual(text, "\n".join(renderer._build_lines(snapshot)) + "\n")
        self.assertNotIn("\x1b", text)

    def test_snapshot_text_with_color_uses_ansi(self) -> None:
        snapshot = self._snapshot()
        renderer = TerminalRenderer(max_lines=14, color=True)
        text = renderer.snapshot_text(snapshot)
        self.assertIn("\x1b", text)


class RenderNoTuiTest(unittest.TestCase):
    def test_render_no_tui_skips_snapshot_writer(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            writer = TuiBatchSnapshotWriter(
                enabled=True,
                output_root=td,
                started_at=datetime(2026, 6, 13, 21, 40, 1),
                config=TuiSnapshotDebugConfig(
                    enabled=True,
                    interval_ms=250,
                    batch_interval_ms=5000,
                ),
            )
            state = RunViewState()
            state.set_run_id("run-1")
            state.ingest_sse("run.status", {"status": "RUNNING", "phase": "plan"})
            args = argparse.Namespace(no_tui=True)
            display = TerminalRenderer(max_lines=14, color=False)
            snapshot_renderer = TerminalRenderer(max_lines=14, color=False)
            warnings = WarningStore()

            _render(args, display, snapshot_renderer, writer, state, warnings, monotonic_now=1.0)

            debug_dir = Path(td) / "20260613-214001" / "debug"
            self.assertEqual(list(debug_dir.glob("tui_batch*.txt")), [])


class PeriodicTuiSnapshotLoopTest(unittest.TestCase):
    def test_background_loop_writes_without_render(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            writer = TuiBatchSnapshotWriter(
                enabled=True,
                output_root=td,
                started_at=datetime(2026, 6, 13, 21, 40, 1),
                config=TuiSnapshotDebugConfig(
                    enabled=True,
                    interval_ms=50,
                    batch_interval_ms=5000,
                ),
            )
            loop = PeriodicTuiSnapshotLoop(
                enabled=True,
                interval_ms=50,
                snapshot_text=lambda: "idle screen\n",
                writer=writer,
            )
            loop.start()
            time.sleep(0.18)
            loop.stop()

            batch_path = Path(td) / "20260613-214001" / "debug" / "tui_batch001.txt"
            self.assertTrue(batch_path.is_file())
            text = batch_path.read_text(encoding="utf-8")
            self.assertGreaterEqual(text.count("idle screen"), 2)

    def test_disabled_loop_writes_nothing(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            writer = TuiBatchSnapshotWriter(
                enabled=True,
                output_root=td,
                started_at=datetime(2026, 6, 13, 21, 40, 1),
                config=TuiSnapshotDebugConfig(
                    enabled=True,
                    interval_ms=50,
                    batch_interval_ms=5000,
                ),
            )
            loop = PeriodicTuiSnapshotLoop(
                enabled=False,
                interval_ms=50,
                snapshot_text=lambda: "should not write\n",
                writer=writer,
            )
            loop.start()
            time.sleep(0.12)
            loop.stop()
            self.assertEqual(list(Path(td).rglob("tui_batch*.txt")), [])

    def test_concurrent_write_if_due_does_not_corrupt_batches(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            writer = TuiBatchSnapshotWriter(
                enabled=True,
                output_root=td,
                started_at=datetime(2026, 6, 13, 21, 40, 1),
                config=TuiSnapshotDebugConfig(
                    enabled=True,
                    interval_ms=250,
                    batch_interval_ms=5000,
                ),
            )

            def worker(offset: int) -> None:
                for idx in range(10):
                    writer.write_if_due(
                        f"screen-{offset}-{idx}\n",
                        monotonic_now=float(offset * 10 + idx),
                        force=True,
                    )

            threads = [threading.Thread(target=worker, args=(offset,)) for offset in range(4)]
            for thread in threads:
                thread.start()
            for thread in threads:
                thread.join()

            batch_paths = sorted((Path(td) / "20260613-214001" / "debug").glob("tui_batch*.txt"))
            text = "".join(path.read_text(encoding="utf-8") for path in batch_paths)
            self.assertEqual(text.count("---"), 40)


class FinishWithRestDebugTest(unittest.TestCase):
    def test_debug_disabled_skips_debug_only_rest_fetches(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            cfg = _config(debug_logs=False, debug_output_root=td)
            client = _RestClient()
            state = RunViewState()
            warnings = WarningStore()
            debug = DebugRunLogger(enabled=False, output_root=td)

            _finish_with_rest(cfg, client, state, warnings, "run-1", debug)

            self.assertEqual(client.calls, ["status", "result"])
            self.assertEqual(state.snapshot().status, "COMPLETED")
            self.assertEqual(state.snapshot().final_answer, "final text")
            self.assertEqual(list(Path(td).iterdir()), [])

    def test_debug_enabled_writes_rest_payloads_and_observability_fallback(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            cfg = _config(debug_logs=True, debug_output_root=td)
            client = _RestClient(observability_error=RuntimeError("too large"))
            state = RunViewState()
            warnings = WarningStore()
            debug = DebugRunLogger(
                enabled=True,
                output_root=td,
                now=datetime(2026, 6, 11, 21, 40, 1),
            )

            _finish_with_rest(cfg, client, state, warnings, "run-1", debug)

            self.assertEqual(client.calls, ["status", "events", "result", "observability", "timeline"])
            run_dir = Path(td) / "20260611-214001"
            self.assertTrue((run_dir / "status_fallback.json").is_file())
            self.assertTrue((run_dir / "events_fallback.json").is_file())
            self.assertTrue((run_dir / "result_fallback.json").is_file())
            self.assertTrue((run_dir / "observability_error.json").is_file())
            self.assertTrue((run_dir / "timeline_fallback.json").is_file())
            self.assertTrue(any("observability fallback failed" in item for item in warnings.snapshot()))


def _config(*, debug_logs: bool, debug_output_root: str) -> LightClientConfig:
    return LightClientConfig(
        base_url="http://example.com",
        username="u",
        password="p",
        question="q",
        debug_logs=debug_logs,
        debug_output_root=debug_output_root,
    )


class _RestClient:
    def __init__(self, *, observability_error: Optional[Exception] = None) -> None:
        self.calls: list[str] = []
        self.observability_error = observability_error

    def get_status(self, template: str, run_id: str) -> dict:
        self.calls.append("status")
        return {"status": "COMPLETED", "phase": "done", "lastSeq": 9}

    def get_events(self, template: str, run_id: str, *, after_seq: int = 0, limit: int = 500) -> dict:
        self.calls.append("events")
        return {"items": [{"seq": 9}]}

    def get_result(self, template: str, run_id: str) -> dict:
        self.calls.append("result")
        return {"answer": "final text"}

    def get_observability_full(self, template: str, run_id: str) -> dict:
        self.calls.append("observability")
        if self.observability_error is not None:
            raise self.observability_error
        return {"trace": []}

    def get_timeline(self, template: str, run_id: str, *, after_seq: int = 0, limit: int = 500) -> dict:
        self.calls.append("timeline")
        return {"items": [{"seq": 9}]}


if __name__ == "__main__":
    unittest.main()
