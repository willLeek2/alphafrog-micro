# 查询与观测接口

查询结果和观测字段对应的 Run、ToolJob、容量与恢复状态见 [Agent 调度与长任务恢复](../agent-framework/scheduling-and-tooljob-recovery.md)。

本页覆盖运行列表、当前状态、最终结果、成本、事件、timeline、可观测性和 trace 详情接口。

## 运行读取

### `GET /api/agent/runs`

列出当前用户的 runs。

查询参数：

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `limit` | 后端默认 | 推荐 page size |
| `offset` | 后端默认 | 推荐 offset |
| `page` | 可选 | 提供时会换算成 offset |
| `size` | 可选 | 另一种 page size |
| `max` | 可选 | 另一种 page size |
| `status` | 空 | 后端状态过滤 |
| `days` | `0` | 最近天数过滤 |

响应 `data` 示例：

```json
{
  "items": [
    {
      "id": "run-id",
      "message": "original message",
      "status": "COMPLETED",
      "createdAt": "2026-06-11T12:00:00Z",
      "completedAt": "2026-06-11T12:03:00Z",
      "hasArtifacts": false,
      "durationMs": 180000,
      "totalTokens": 12345,
      "toolCalls": 6
    }
  ],
  "total": 1,
  "hasMore": false
}
```

### `GET /api/agent/runs/{runId}`

读取当前 run view。返回 `data` 与创建、cancel、pause、resume、update 使用同一类 `AgentRunResponse` 结构。

### `GET /api/agent/runs/{runId}/status`

读取当前 status、progress、最近观测摘要和时间字段。

响应 `data` 示例：

```json
{
  "id": "run-id",
  "status": "EXECUTING",
  "phase": "execution",
  "currentTool": null,
  "lastEventType": "TOOL_CALL_FINISHED",
  "lastEventAt": "2026-06-11T12:00:30Z",
  "lastEventPayload": {},
  "plan": {},
  "progress": {},
  "observability": {},
  "observabilitySummary": {},
  "observabilityFullAvailable": true,
  "totalCreditsConsumed": 0,
  "eventCount": 42,
  "startedAtMs": 1781160000000,
  "completedAtMs": null,
  "elapsedMs": 30000
}
```

### `GET /api/agent/runs/{runId}/result`

读取最终答案。如果 run 还不是 `COMPLETED`，controller 返回 HTTP `202`，wrapper 仍是成功结构，message 为 `任务未完成`。`/result` 是这个接口族里少有的会显式返回 `202` 的接口，其他读取接口（`/status`、`/cost` 等）即便出错也通常在 `200` wrapper 里。

响应 `data` 示例（HTTP 200）：

```json
{
  "id": "run-id",
  "status": "COMPLETED",
  "answer": "plain answer",
  "answerMarkdown": "markdown answer",
  "structuredAnswer": {},
  "payload": {},
  "observability": null,
  "totalCreditsConsumed": 0
}
```

## 成本

### `GET /api/agent/runs/{runId}/cost`

读取持久化或计算得到的 cost 数据。

响应 `data` 示例：

```json
{
  "id": "run-id",
  "totalCost": 0.0123,
  "upstreamInferenceCost": 0.015,
  "cacheDiscount": 0.0027,
  "costedCallCount": 3,
  "totalCallCount": 3,
  "complete": true,
  "currency": "USD",
  "source": "openrouter",
  "updatedAt": "2026-06-11T12:03:00Z",
  "persisted": true,
  "calls": [
    {
      "traceId": "trace-id",
      "generationId": "generation-id",
      "phase": "planning",
      "todoId": null,
      "endpoint": "endpoint",
      "model": "model",
      "actualCost": 0.0123,
      "upstreamInferenceCost": 0.015,
      "cacheDiscount": 0.0027,
      "isByok": false,
      "startedAtMs": 1781160000000,
      "completedAtMs": 1781160001000,
      "source": "openrouter"
    }
  ]
}
```

字段说明：

| 字段 | 含义 |
| --- | --- |
| `totalCost` | 按 `currency` 计的估算总成本，`complete=false` 时可能是 `0` 或 `null` |
| `upstreamInferenceCost` | provider 报告的折扣前推理成本 |
| `cacheDiscount` | provider 报告的 cached token 折扣 |
| `costedCallCount` | 计入总成本的调用数 |
| `totalCallCount` | 观测到的 LLM 调用总数，可能大于 `costedCallCount` |
| `complete` | 是否已经从可观测性数据完成成本归集 |
| `persisted` | run 级别总成本是否已经持久化 |
| `calls` | 单次调用成本明细；无成本明细时可能为空或省略 |

成本可能晚于 run 完成，因为 run 级别总成本需要在可观测性数据可用后写入。

## 事件与 timeline

### `GET /api/agent/runs/{runId}/events`

按序列号读取持久化 Agent events。

查询参数：

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `after_seq` | `0` | 返回 `seq > after_seq` 的事件 |
| `limit` | `200` | 限制在 `1..500` |

响应 `data`：

```json
{
  "items": [
    {
      "id": "event-id",
      "runId": "run-id",
      "seq": 42,
      "eventType": "TOOL_CALL_FINISHED",
      "payload": {},
      "createdAt": "2026-06-11T12:00:30Z"
    }
  ],
  "nextAfterSeq": 42,
  "hasMore": false
}
```

### `GET /api/agent/runs/{runId}/timeline`

把最近 events 和可观测性 spans 合成 timeline。

查询参数：

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `after_seq` | `0` | 事件游标，返回 `seq > after_seq` 的事件 |
| `limit` | `100` | 限制在 `1..500` |

响应 `data`：

```json
{
  "items": [
    {
      "seq": 42,
      "source": "event",
      "traceId": null,
      "type": "TOOL_CALL_FINISHED",
      "time": "2026-06-11T12:00:30Z",
      "title": "tool call finished",
      "durationMs": null,
      "detail": {}
    }
  ],
  "nextAfterSeq": 42,
  "hasMore": false
}
```

分页规则：

- items 服务端按 `time` 升序、其次 `source`、最后 `seq` 排序，跨事件和 trace spans。
- 下一页使用响应里的 `nextAfterSeq` 作为 `after_seq`。
- `hasMore=false` 时停止；不要用 `len(items) < limit` 当作结束条件。
- 服务端会把 `after_seq` 夹在 `[0, +∞)`，`limit` 夹在 `[1, 500]`，越界值会被静默修正。

## 可观测性和 trace

### `GET /api/agent/runs/{runId}/observability/full`

在可用时返回完整可观测性 JSON。

边界：

- 响应字节超过 5 MiB（`5 * 1024 * 1024`）会返回业务错误，提示客户端改用 `/traces` 或 `/timeline`。
- 可观测性字符串本身为 `null` 或空白时返回 `DATA_NOT_FOUND`，message 为 `observability 不存在`。
- 可观测性字符串非空但无法解析为 JSON 时，controller 不会报 `DATA_NOT_FOUND`，而是把原始字符串作为 `data` 返回（HTTP 200、wrapper 成功）。这是当前代码行为；不要把这种场景当成"正常可观测性 JSON"使用。

### `GET /api/agent/runs/{runId}/traces`

从可观测性数据中列出规范化 LLM / tool spans。

查询参数：

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `type` | 空 | 可选 `llm` 或 `tool`，大小写不敏感 |
| `phase` | 空 | 可选 phase filter，大小写不敏感 |
| `after` | `0` | span 合成序号游标，返回 `seq > after` 的 spans |
| `limit` | `100` | 限制在 `1..500` |

响应 `data` 示例：

```json
{
  "spans": [
    {
      "seq": 1,
      "type": "llm",
      "traceId": "trace-id",
      "time": "2026-06-11T12:00:00Z",
      "phase": "planning",
      "todoId": null,
      "durationMs": 15234,
      "model": "model",
      "inputTokens": 1000,
      "outputTokens": 500,
      "hasError": false,
      "hasInputMessages": true,
      "hasReasoning": false,
      "outputSummary": "..."
    }
  ],
  "summary": {
    "totalLlmCalls": 1,
    "totalToolCalls": 0,
    "totalDurationMs": 15234,
    "totalTokens": 1500
  }
}
```

过滤和分页：

- `type` 和 `phase` 用 AND 组合；空字符串视为不过滤。
- `after` 是 span 合成序号游标。接口会给每个 span 分配单调 `seq`，并返回 `seq > after` 的 spans。
- 响应没有 `nextAfter`。客户端需要记录本页最大 `seq`，下一页作为 `after`；也可以改用带 `nextAfterSeq` 的 `/timeline`。
- `summary` 来自可观测性 JSON 顶层 `summary`，是 run 级别的统计，**不**受 `type` / `phase` / `after` / `limit` 影响；缺字段时为 `0`。映射关系是 `summary.llmCalls` → `totalLlmCalls`、`summary.toolCalls` → `totalToolCalls`、`summary.totalDurationMs` → `totalDurationMs`、`summary.totalTokens` → `totalTokens`。
- 如果该 run 的可观测性数据缺失或为空 JSON，接口返回 `spans: []` + 全零 `summary`，不会报 `DATA_NOT_FOUND`。这是 `/traces` 与 `/observability/full` 在"没有可观测性数据"语义上的差别。

### `GET /api/agent/runs/{runId}/traces/{traceId}`

返回单条 LLM 或 tool trace。

LLM detail 可能包含 model、endpoint、token counts、cost、input messages、output text、reasoning text、errors、attempts、HTTP request / response 和 curl 命令。Tool detail 可能包含 tool name、params、output、success、cache state、decision trace 和 decision excerpt。

### `GET /api/agent/runs/{runId}/llm-calls/{llmCallId}/detail`

返回安全的按需加载 LLM call detail。

查询参数：

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `includeThinking` | `false` | 只对 admin 用户生效；非 admin 调用会被静默当作 `false` |

响应字段：

- 顶层：`type`、`detailKind`、`source`、`id`、`runId`、`todoId`、`todoSequence`、`phase`、`stage`、`time`、`durationMs`、`status`、`summary`。
- `metrics`：`inputTokens` / `outputTokens` / `totalTokens` / `actualCost`，对应一次 LLM 调用的 token 与 cost 汇总。
- `llm.model` 始终回传；`llm.reasoningContent` 仅在 `includeThinking=true` 且调用者是 admin 时才可能存在（来自 Redis 6h TTL 的 detail 数据块）。
- `limits`：`previewMaxChars` / `truncated`，用于客户端知道输出是否被截断。
- `reasoningUnavailable`：当 `includeThinking=true` 且 admin 调用，但 Redis detail 数据块缺失或不含 `reasoningText` 时为 `true`（例如超过 6h TTL 过期），`detailKind` 仍可能是 `available`，仅 thinking 不可用。

`detailKind` 的取值有 `available` / `truncated` / `unavailable` / `expired`；`source` 的取值有 `observability_snapshot` / `call_detail_redis`。

非 admin 用户即使传 `includeThinking=true` 也不会通过该接口拿到 thinking 内容；接口不会因此报错。

### `GET /api/agent/runs/{runId}/tool-calls/{toolCallId}/detail`

返回安全的按需加载 tool call detail。

响应字段：

- 顶层：`type`、`detailKind`、`source`、`id`、`runId`、`todoId`、`todoSequence`、`phase`、`stage`、`time`、`durationMs`、`status`、`summary`。
- `metrics`：tool 调用本身的耗时与计数（具体字段以 `DetailMetrics` 为准）。
- `tool`：`name` / `paramsSummary` / `outputPreview`。
- `limits`：`previewMaxChars` / `truncated`。

`includeThinking` 在 tool detail 路径上不生效；tool detail 也不包含 reasoning。
