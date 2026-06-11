# af_light_client

轻量 Agent run TUI 客户端。目标是给单次问题调试使用：读一个 YAML，登录、创建 run、订阅 SSE，并用克制的终端界面展示 LLM call、tool call、warnings 和最终答案。

## 用法

需要 Python 3.9+，依赖 `requests` 和 `pyyaml`：

```bash
python3 -m pip install requests pyyaml
```

```bash
python3 -m af_light_client --config af_light_client/config.example.yml --dry-run
python3 -m af_light_client --config af_light_client/config.example.yml
```

`--dry-run` 只校验配置并打印 create run 请求体，不会发起登录或 run。

## 最小配置

```yaml
base_url: "http://localhost:8090"
username: "stress_test_1"
password: "replace-with-password"
question: "帮我分析沪深300最近走势。"
```

可选 endpoint、timeout、TUI 行数和 create body 见 `config.example.yml`。

## Debug Logs

默认不落调试日志。需要实跑排查 TUI 或后端事件时，在 YAML 里打开：

```yaml
debug:
  logs: true
  output_root: "af_light_client/output"
```

开启后会在 `debug.output_root/YYYYMMDD-HHMMSS/` 下写入脱敏配置、create run 响应、SSE 事件 jsonl、warnings、最终 status/result、observability full，以及 Ctrl+C 或异常时的错误记录。Ctrl+C 会 best-effort cancel 当前 run，然后仍然拉取能拿到的终态观测数据。

## 事件契约

客户端订阅 `/api/agent/runs/{run_id}/stream`，主要处理：

- `snapshot`
- `agent.event`
- `run.status`
- `run.done`
- `error`

`agent.event` 里优先读取 `payload` object，兼容 `payloadJson` / `payload_json` 字符串。当前重点展示：

- `PLAN_READY`
- `TODO_NODE_STARTED / COMPLETED / FAILED / SKIPPED`
- `LLM_CALL_STARTED / DELTA / FINISHED`
- `TOOL_CALL_STARTED / FINISHED`
- `RUN_STATUS_CHANGED / PHASE_CHANGED / RUN_STATUS`

SSE 断开或 idle 超时后，客户端只做一次 `/status` 与 `/result` 兜底，不做无限重连。
