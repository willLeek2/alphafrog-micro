# af_light_client

轻量 Agent run TUI 客户端。目标是给单次问题调试使用：读一个 YAML，登录、创建 run、订阅 SSE，并用克制的终端界面展示 LLM call、tool call、warnings 和最终答案。

**注意：** 不要把真实账号密码写入 `config.example.yml`。建议复制一份到 `configs/prod/` 或本地 ignored 路径，再填入真实 `username` / `password`。

## 用法

需要 Python 3.9+，依赖 `requests` 和 `pyyaml`：

```bash
python3 -m pip install requests pyyaml
```

```bash
python3 -m af_light_client --config af_light_client/config.example.yml --dry-run
python3 -m af_light_client --config af_light_client/config.example.yml
```

`--dry-run` 只校验配置并打印 create run 请求体，不会发起登录或创建 run。先跑 `--dry-run` 可以确认 LLM 配置是否正确进入请求体，而不用暴露真实账号。

## 最小配置

```yaml
base_url: "http://localhost:8090"
username: "stress_test_1"
password: "replace-with-password"
question: "帮我分析沪深300最近走势。"
```

## 指定 LLM

需要固定本次 run 使用的模型时，在 YAML 里配置 `llm`。字段名和压测脚本保持一致；顶层模型字段会直接进入 create run 请求体，`stage_config` 会在请求体里序列化为 `stage_config_json` 字符串：

- `endpointName` / `modelName` / `provider`：顶层默认模型。
- `stage_config`：按阶段覆盖，目前支持 `planning` 和 `final_answer`；发送请求时会转换为 `stage_config_json`。
- `providerOrder`：阶段内优先尝试的 provider 列表。

```yaml
llm:
  endpointName: openrouter
  modelName: qwen/qwen3.7-max
  provider: alibaba
  stage_config:
    planning:
      endpointName: openrouter
      modelName: moonshotai/kimi-k2.6
      providerOrder: [fireworks, moonshotai/int4]
    final_answer:
      endpointName: openrouter
      modelName: moonshotai/kimi-k2.6
      providerOrder: [fireworks, moonshotai/int4]
```

`create_body` 仍然保留给高级用户传后端新增字段；如果它和 `llm` 出现同名字段，`create_body` 优先。`debugMode`、`plannerCandidateCount` 这类 admin-only 或临时字段也通过 `create_body` 传。

更多 endpoint、timeout、TUI 行数、`llm` 和 create body 选项见 `config.example.yml`。`config.example.yml` 里的账号密码是占位符，可直接复制后修改。

## Debug Logs

默认不落调试日志。需要实跑排查 TUI 或后端事件时，在 YAML 里打开：

```yaml
debug:
  logs: true
  output_root: "af_light_client/output"
```

开启后会在 `debug.output_root/YYYYMMDD-HHMMSS/` 下写入脱敏配置、create run 响应、SSE 事件 jsonl、warnings、最终 status/result、可观测性全量数据，以及 Ctrl+C 或异常时的错误记录。Ctrl+C 会尽力取消当前 run，然后仍然拉取能拿到的终态观测数据。

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
