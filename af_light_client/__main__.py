from __future__ import annotations

import argparse
import json
import sys
import time
from datetime import datetime
from typing import Any, Dict

from requests import exceptions as requests_exceptions

from .config import LightClientConfig
from .debug import DebugRunLogger
from .events import RunViewState, final_answer_from_result, is_terminal
from .http_client import AgentHttpClient, WarningStore
from .render import TerminalRenderer


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run one AlphaFrog agent request with a compact live TUI.")
    parser.add_argument("--config", required=True, help="YAML config path")
    parser.add_argument("--dry-run", action="store_true", help="validate config and print create body only")
    parser.add_argument("--no-tui", action="store_true", help="disable screen redraw; print event summaries")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    cfg = LightClientConfig.from_file(args.config)
    if args.dry_run:
        print(json.dumps(cfg.create_request_body(), ensure_ascii=False, indent=2))
        return 0

    warnings = WarningStore(cfg.max_warning_lines)
    state = RunViewState(max_trace_lines=cfg.max_trace_lines, max_warnings=cfg.max_warning_lines)
    renderer = TerminalRenderer(max_lines=cfg.max_trace_lines)
    debug = DebugRunLogger.from_config(cfg)
    debug.write_json("config.json", cfg.as_log_dict())
    debug.write_json("create_request.json", cfg.create_request_body())
    client = AgentHttpClient(
        cfg.base_url,
        request_timeout_seconds=cfg.request_timeout_seconds,
        stream_idle_timeout_seconds=cfg.stream_idle_timeout_seconds,
        warnings=warnings,
    )
    run_id = ""
    last_render = 0.0
    exit_code = 0
    interrupted = False
    try:
        token = client.login(cfg.login_endpoint, cfg.logout_endpoint, cfg.username, cfg.password)
        warnings.add(f"login ok as {cfg.username}; token len={len(token)}")
        run = client.create_run(cfg.create_endpoint, cfg.create_request_body())
        debug.write_json("create_response.json", run)
        run_id = str(run.get("runId") or run.get("run_id") or run.get("id"))
        state.set_run_id(run_id)
        warnings.add(f"run created: {run_id}")
        _render(args, renderer, state, warnings)

        for frame in client.stream_events(cfg.stream_endpoint_template, run_id):
            parsed = frame.parsed_data()
            debug.log_sse_frame(frame, parsed)
            state.ingest_sse(frame.event_type, parsed)
            _copy_warnings(state, warnings)
            if args.no_tui:
                _print_event_line(frame.event_type, state.snapshot().trace)
            now = time.monotonic()
            if now - last_render >= cfg.refresh_seconds:
                _render(args, renderer, state, warnings)
                last_render = now
            snap = state.snapshot()
            if is_terminal(snap.status):
                break

    except KeyboardInterrupt:
        interrupted = True
        exit_code = 130
        warnings.add("interrupted; sending cancel to in-flight run")
        debug.write_json("interrupt.json", {"reason": "KeyboardInterrupt", "runId": run_id})
        debug.write_text("interrupt.log", f"KeyboardInterrupt received; runId={run_id}\n")
        if run_id:
            client.cancel_run(cfg.cancel_endpoint_template, run_id)
    except (requests_exceptions.ReadTimeout, requests_exceptions.ConnectionError) as exc:
        exit_code = 2
        warnings.add(f"SSE disconnected or idle timeout: {exc}")
        debug.write_json("stream-error.json", {"error": str(exc), "type": type(exc).__name__})
    except Exception as exc:
        exit_code = 1
        warnings.add(str(exc))
        debug.write_json("error.json", {"error": str(exc), "type": type(exc).__name__})
        _copy_warnings(state, warnings)
        _render(args, renderer, state, warnings)
    finally:
        if run_id:
            _finish_with_rest(cfg, client, state, warnings, run_id, debug)
        _copy_warnings(state, warnings)
        debug.log_warning_snapshot(warnings.snapshot())
        debug.write_json(
            "summary.json",
            {
                "runId": run_id,
                "status": state.snapshot().status,
                "phase": state.snapshot().phase,
                "lastSeq": state.snapshot().last_seq,
                "startedAt": debug.started_at.isoformat(timespec="seconds"),
                "finishedAt": datetime.now().isoformat(timespec="seconds"),
                "interrupted": interrupted,
                "exitCode": exit_code,
                "answerPreview": state.snapshot().final_answer[:500],
                "files": debug.list_files(),
                "debugOutputDir": str(debug.output_dir) if debug.output_dir else "",
            },
        )
        if not args.no_tui:
            renderer.finish(state.snapshot())

    snap = state.snapshot()
    if exit_code:
        return exit_code
    return 0 if snap.status.upper() == "COMPLETED" else 2


def _finish_with_rest(
    cfg: LightClientConfig,
    client: AgentHttpClient,
    state: RunViewState,
    warnings: WarningStore,
    run_id: str,
    debug: DebugRunLogger,
) -> None:
    try:
        status = client.get_status(cfg.status_endpoint_template, run_id)
        debug.write_json("status_fallback.json", status)
        if status:
            state.ingest_sse("run.status", status)
    except Exception as exc:
        warnings.add(f"status fallback failed: {exc}")
        debug.write_json("status_fallback_error.json", {"error": str(exc), "type": type(exc).__name__})

    if not debug.enabled:
        try:
            result = client.get_result(cfg.result_endpoint_template, run_id)
            answer = final_answer_from_result(result)
            if answer:
                state.set_final_answer(answer)
        except Exception as exc:
            warnings.add(f"result fallback failed: {exc}")
        return

    try:
        events = client.get_events(cfg.events_endpoint_template, run_id, after_seq=0, limit=500)
        debug.write_json("events_fallback.json", events)
    except Exception as exc:
        warnings.add(f"events fallback failed: {exc}")
        debug.write_json("events_fallback_error.json", {"error": str(exc), "type": type(exc).__name__})
    try:
        result = client.get_result(cfg.result_endpoint_template, run_id)
        debug.write_json("result_fallback.json", result)
        answer = final_answer_from_result(result)
        if answer:
            state.set_final_answer(answer)
    except Exception as exc:
        warnings.add(f"result fallback failed: {exc}")
        debug.write_json("result_fallback_error.json", {"error": str(exc), "type": type(exc).__name__})
    try:
        observability = client.get_observability_full(cfg.observability_full_endpoint_template, run_id)
        debug.write_json("observability.json", observability)
    except Exception as exc:
        warnings.add(f"observability fallback failed: {exc}")
        debug.write_json("observability_error.json", {"error": str(exc), "type": type(exc).__name__})
        try:
            timeline = client.get_timeline(cfg.timeline_endpoint_template, run_id, after_seq=0, limit=500)
            debug.write_json("timeline_fallback.json", timeline)
        except Exception as timeline_exc:
            warnings.add(f"timeline fallback failed: {timeline_exc}")
            debug.write_json(
                "timeline_fallback_error.json",
                {"error": str(timeline_exc), "type": type(timeline_exc).__name__},
            )


def _copy_warnings(state: RunViewState, warnings: WarningStore) -> None:
    for item in warnings.snapshot():
        state.add_warning(item)


def _render(args: argparse.Namespace, renderer: TerminalRenderer, state: RunViewState, warnings: WarningStore) -> None:
    _copy_warnings(state, warnings)
    if not args.no_tui:
        renderer.render(state.snapshot())


def _print_event_line(event_type: str, trace: list[str]) -> None:
    summary = trace[-1] if trace else event_type
    print(f"{event_type}: {summary}")


if __name__ == "__main__":
    sys.exit(main())
