from __future__ import annotations

import argparse
import json
import sys
import time
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List

from requests import exceptions as requests_exceptions

from .config import LightClientConfig
from .debug import DebugRunLogger
from .events import RunViewState, final_answer_from_result, is_terminal
from .http_client import AgentHttpClient, WarningStore
from .render import TerminalRenderer


_PROJECT_ROOT = Path(__file__).resolve().parents[1]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run one AlphaFrog agent request with a compact live TUI.")
    parser.add_argument("--config", required=True, help="YAML config path")
    parser.add_argument("--dry-run", action="store_true", help="validate config and print create body only")
    parser.add_argument("--no-tui", action="store_true", help="disable screen redraw; print event summaries")
    parser.add_argument("--query-credits", action="store_true", help="query/refresh credits for an existing run output folder")
    parser.add_argument("--output-dir", default="", help="existing run output folder (relative to project root) for --query-credits")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    cfg = LightClientConfig.from_file(args.config)
    if args.dry_run:
        print(json.dumps(cfg.create_request_body(), ensure_ascii=False, indent=2))
        return 0
    if args.query_credits:
        return _query_credits_main(cfg, args)

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
        answer = state.snapshot().final_answer
        if answer:
            debug.write_text("answer.md", answer)
        if not args.no_tui:
            renderer.finish(state.snapshot())
        if run_id:
            _print_credits_summary(cfg, client, run_id, interrupted, debug)

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


def _print_credits_summary(
    cfg: LightClientConfig,
    client: AgentHttpClient,
    run_id: str,
    interrupted: bool,
    debug: DebugRunLogger,
) -> None:
    if interrupted:
        return
    try:
        credits = client.get_run_credits(
            cfg.credits_endpoint_template,
            run_id,
            timeout=cfg.credits_fetch_timeout_seconds,
        )
    except Exception as exc:
        debug.write_json("credits_error.json", {"error": str(exc), "type": type(exc).__name__})
        print(f"[credits] 查询失败: {exc}")
        return
    debug.write_json("credits.json", credits)
    summary = credits.get("summary") or {}
    total = credits.get("totalCredits") or summary.get("totalCredits") or "0"
    currency = credits.get("currency") or summary.get("currency") or "USD"
    immediate = summary.get("immediateCount") or 0
    delayed = summary.get("delayedCount") or 0
    pending = summary.get("pendingCount") or 0
    missing = summary.get("missingCount") or 0
    total_calls = summary.get("totalCallCount") or 0
    settled_calls = int(immediate) + int(delayed)
    print(
        f"[credits] 本次 run 消耗 {total} {currency} "
        f"(settlement: {settled_calls} settled = {immediate} immediate + {delayed} delayed; "
        f"{pending} pending; {missing} missing; total calls {total_calls})"
    )


def _query_credits_main(cfg: LightClientConfig, args: argparse.Namespace) -> int:
    output_dir = _resolve_output_dir(args.output_dir)
    if output_dir is None:
        print("[query-credits] 请指定 --output-dir 为本次 run 的输出文件夹路径", file=sys.stderr)
        return 2
    run_id = _load_run_id_from_output_dir(output_dir)
    if not run_id:
        print(f"[query-credits] 无法从 {output_dir / 'summary.json'} 读取 runId", file=sys.stderr)
        return 2

    warnings = WarningStore(cfg.max_warning_lines)
    client = AgentHttpClient(
        cfg.base_url,
        request_timeout_seconds=cfg.request_timeout_seconds,
        stream_idle_timeout_seconds=cfg.stream_idle_timeout_seconds,
        warnings=warnings,
    )
    try:
        client.login(cfg.login_endpoint, cfg.logout_endpoint, cfg.username, cfg.password)
        result = _query_and_refresh_credits(cfg, client, run_id)
        _print_query_credits_result(result)
        _write_json(output_dir / "credits_query.json", result)
        return 0
    except Exception as exc:
        print(f"[query-credits] 失败: {exc}", file=sys.stderr)
        return 1


def _resolve_output_dir(raw: str) -> Path | None:
    text = (raw or "").strip()
    if not text:
        return None
    path = Path(text)
    if not path.is_absolute():
        path = _PROJECT_ROOT / path
    return path.resolve()


def _load_run_id_from_output_dir(output_dir: Path) -> str:
    summary_path = output_dir / "summary.json"
    if not summary_path.exists():
        return ""
    try:
        data = json.loads(summary_path.read_text(encoding="utf-8"))
        return str(data.get("runId") or data.get("run_id") or "")
    except Exception:
        return ""


def _write_json(path: Path, data: Dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def _query_and_refresh_credits(
    cfg: LightClientConfig,
    client: AgentHttpClient,
    run_id: str,
) -> Dict[str, Any]:
    credits = client.get_run_credits(
        cfg.credits_endpoint_template,
        run_id,
        timeout=cfg.credits_fetch_timeout_seconds,
    )
    refreshed = False
    if _needs_cost_refresh(credits):
        credits = client.refresh_run_credits(
            cfg.credits_refresh_endpoint_template,
            run_id,
            timeout=cfg.credits_fetch_timeout_seconds,
        )
        refreshed = True

    user_credits: Dict[str, Any] = {}
    try:
        user_credits = client.get_user_credits(
            cfg.user_credits_endpoint,
            timeout=cfg.credits_fetch_timeout_seconds,
        )
    except Exception as exc:
        user_credits = {"error": str(exc)}

    summary = credits.get("summary") or {}
    return {
        "runId": run_id,
        "refreshed": refreshed,
        "totalCredits": credits.get("totalCredits") or summary.get("totalCredits") or "0",
        "currency": credits.get("currency") or summary.get("currency") or "USD",
        "summary": summary,
        "records": credits.get("records") or [],
        "userCredits": user_credits,
        "queriedAt": datetime.now().isoformat(),
    }


def _needs_cost_refresh(credits: Dict[str, Any]) -> bool:
    summary = credits.get("summary") or {}
    total_call_count = int(summary.get("totalCallCount") or 0)
    if total_call_count == 0:
        return False
    records = credits.get("records") or []
    # 按 callId 取 attempt 最大的 effective 记录，避免 attempt=1 的 PENDING_RETRY 占位误导判断。
    effective_by_call: Dict[str, Dict[str, Any]] = {}
    for rec in records:
        call_id = rec.get("callId") or ""
        if not call_id:
            continue
        attempt = int(rec.get("settlementAttempt") or 0)
        existing = effective_by_call.get(call_id)
        if existing is None or attempt > int(existing.get("settlementAttempt") or 0):
            effective_by_call[call_id] = rec
    openrouter_effective = [
        r for r in effective_by_call.values() if str(r.get("endpoint") or "").lower() == "openrouter"
    ]
    if not openrouter_effective:
        return False
    for rec in openrouter_effective:
        if rec.get("costSource") == "OPENROUTER_ACTUAL" and rec.get("settlementStatus") == "SETTLED":
            continue
        return True
    return False


def _print_query_credits_result(result: Dict[str, Any]) -> None:
    summary = result.get("summary") or {}
    refreshed = result.get("refreshed")
    print(
        f"[query-credits] runId={result.get('runId')} "
        f"总消耗 {result.get('totalCredits')} {result.get('currency')}"
    )
    print(
        f"[query-credits] settlement: "
        f"{summary.get('immediateCount', 0)} immediate + "
        f"{summary.get('delayedCount', 0)} delayed, "
        f"{summary.get('pendingCount', 0)} pending, "
        f"{summary.get('missingCount', 0)} missing, "
        f"共 {summary.get('totalCallCount', 0)} calls"
    )
    if refreshed:
        print("[query-credits] 已触发一次 cost 刷新")
    else:
        print("[query-credits] 无需刷新，所有可追踪端点 cost 已到位")
    user = result.get("userCredits") or {}
    if "remainingCredits" in user:
        print(f"[query-credits] 用户当前剩余 credit: {user.get('remainingCredits')} {user.get('currency', 'USD')}")
    elif "error" in user:
        print(f"[query-credits] 查询用户剩余 credit 失败: {user.get('error')}")


if __name__ == "__main__":
    sys.exit(main())
