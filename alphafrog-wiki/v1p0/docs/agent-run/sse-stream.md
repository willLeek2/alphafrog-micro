# SSE 事件流

SSE 是 v1.0 Agent 运行进度的实时主通道。轻客户端（TUI、压测脚本、浏览器小工具）应把 SSE 当作首选来源，只在断线修复或运行结束后用 REST 查询做补充读取。

## 打开事件流

### `GET /api/agent/runs/{runId}/stream`

SSE 流是 Agent 运行进度的实时读取接口。

断点续传控制：

| 名称 | 位置 | 说明 |
| --- | --- | --- |
| `after_seq` | query | 推荐游标，返回 `seq > after_seq` 的事件 |
| `Last-Event-ID` | header | 没有 `after_seq` 时的备用游标 |

优先级以后端 `AgentSseController.resolveResumeAfterSeq()` 为准：`after_seq` 优先；两者都缺失时从 0 开始。

认证方式：

- 浏览器 `EventSource` 推荐依赖登录 cookie，创建 run 的请求和 SSE 连接需要在同一 origin 下。
- Python / agent 客户端可以使用 `requests` / `httpx` 类 SSE client，携带 `Authorization: Bearer <token>`。

## SSE 事件名

| 事件名 | 载荷说明 |
| --- | --- |
| `snapshot` | 新连接的首包，包含 `status`、`phase`、`plan`、最近 N 条事件和 `lastSeq` |
| `agent.event` | 持久化 Agent 事件，业务类型在嵌套字段 `eventType` 里，载荷在 `payload` 里 |
| `run.status` | 状态和 phase 观察事件，连接初始和状态变化时发送 |
| `heartbeat` | 30 秒一次的 keepalive |
| `run.done` | 终态通知 |
| `error` | 初始化、认证、replay 错误或 live buffer overflow，发送后关闭流 |

`agent.event` 里的 `eventType`（如 `PLAN_READY`、`TOOL_CALL_FINISHED`）是业务事件类型，不要把它们和 SSE wire event name 混为一谈。

wire envelope 示例：

```text
id: 42
event: agent.event
data: {"type":"agent.event","runId":"run-id","seq":42,"eventType":"TOOL_CALL_FINISHED","payload":{},"ts":1781160030000}
```

字段规则：

- `id` 是 SSE 协议帧的 `id:` 行。服务端只有 `agent.event` 会显式设置它，且等于该 durable event 的 `seq`；`snapshot`、`run.status`、`heartbeat`、`run.done`、`error` 不设置 SSE `id`。
- `event` 是 SSE wire event name。
- `data` 是 JSON。多行 payload 会拆成多条 `data:`，客户端必须合并后再解析。
- 空行表示上一个事件结束。

## `lastSeq`、`seq` 与 SSE `id` 的关系

这三者容易混淆，但对断线续传和缺口修复至关重要：

| 字段 | 含义 | 出现位置 |
| --- | --- | --- |
| `seq` | 单条持久化事件的序号 | `agent.event` 的 `data.seq`；`snapshot.events[]` 里每条事件的 `seq`；`agent.event` 的 SSE `id` 也等于该事件的 `seq` |
| `lastSeq` | 当前 run 已产生事件的最大序号 / 事件计数 | `snapshot.lastSeq`、`run.status.lastSeq`、`run.done.lastSeq` |
| SSE `id` | SSE 协议帧的 `id:` 行，仅 `agent.event` 设置，重连时浏览器会自动作为 `Last-Event-ID` 带上 | `id: <seq>`，与对应 `agent.event` 的 `seq` 相同 |

注意：

- `lastSeq` 是 run 级别的“已见到最大序号”，不是单条事件的序号。
- `agent.event` 的 `data` 里放的是 `seq`，不是 `lastSeq`。
- `run.status` / `run.done` 的 `data` 里放的是 `lastSeq`，因为它本身不是持久化事件，而是状态观察帧。
- 只有 `agent.event` 带 SSE `id`；重连时若用 `Last-Event-ID` 续传，服务端会把它当作 `after_seq` 的备用来源。

## snapshot 载荷

```json
{
  "type": "snapshot",
  "runId": "run-id",
  "ts": 1781160000000,
  "status": "EXECUTING",
  "phase": "execution",
  "startedAtMs": 1781160000000,
  "completedAtMs": null,
  "plan": {},
  "events": [],
  "eventCount": 0,
  "lastSeq": 0
}
```

- `events` 默认是最近 10 条持久化事件（按 `seq` 升序），每条都有独立的 `seq`。
- `lastSeq` 取 `status.eventCount` 和 `events` 中最大 `seq` 的较大者。
- 无游标连接时，客户端先收到 `snapshot`，再进入 live 推送。

## agent.event 载荷

```json
{
  "type": "agent.event",
  "runId": "run-id",
  "seq": 42,
  "eventType": "TOOL_CALL_FINISHED",
  "payload": {
    "toolName": "ragSearch",
    "success": true
  },
  "createdAt": "2026-06-11T12:00:30Z",
  "ts": 1781160030000
}
```

- `event` 层的 SSE 名称固定是 `agent.event`。
- 真正的业务事件类型在嵌套字段 `eventType` 里。
- `payload` 在离开 SSE service 前会被规范化为 JSON object；内部存储的 `payloadJson` 对客户端不可见。

## 续传与 live 缓冲溢出

带游标重连时，服务端会：

1. 按 200 条一页从持久化存储读取 `seq > resumeAfterSeq` 的事件并推送。
2. replay 期间同时监听 Redis live 频道，把新到达的事件暂存到内存缓冲区（上限 500 条）。
3. replay 结束后，按 `seq` 排序并 flush 缓冲区；已推过的 `seq` 会跳过，避免重复。

如果 replay 期间 live 事件来得太快，缓冲区超过 500 条，服务端会：

- 设置 overflow 标志。
- replay 结束后发送一条 `error` 帧，code 为 `LIVE_REPLAY_BUFFER_OVERFLOW`。
- 关闭 SSE 连接。

此时客户端**不要**继续等 SSE 自动补齐，而应改用 REST 拉取：

```
GET /api/agent/runs/{runId}/events?after_seq=<lastContiguousSeq>&limit=200
```

## REST `/events` 补洞

`/events` 返回持久化事件列表，默认 `limit=200`，最大 `500`。SSE 断线或缺口修复时，可以用它按 `after_seq` 补拉事件。

典型补洞流程（TUI / 脚本视角）：

1. 维护两个游标：
   - `lastAppliedSeq`：已应用到本地视图的最大 `seq`（可能不连续）。
   - `lastContiguousSeq`：已确认连续的最后一个 `seq`，续传和补洞都用它。
2. 初始从 `snapshot.lastSeq` 拿到 run 当前事件计数；若 `snapshot.events` 为空，则 `lastContiguousSeq = 0`。
3. 每收到一条 `agent.event`，更新 `lastAppliedSeq = max(lastAppliedSeq, event.seq)`。
4. 如果 `event.seq == lastContiguousSeq + 1`，说明连续，推进 `lastContiguousSeq = event.seq`。
5. 如果 `event.seq > lastContiguousSeq + 1`，说明出现缺口：暂停按 `seq` 渲染这条事件，用 `after_seq = lastContiguousSeq` 调用 `/events` 拉取 `[lastContiguousSeq + 1, event.seq - 1]` 的缺失事件，补齐后再推进 `lastContiguousSeq`。
6. 收到 `run.status` / `run.done` 时，它携带的 `lastSeq` 可以帮助判断服务端已持久化到第几条，但补洞游标仍应使用 `lastContiguousSeq`。
7. 断线重连时带上 `?after_seq=<lastContiguousSeq>`。
8. 如果重连后收到 `LIVE_REPLAY_BUFFER_OVERFLOW`，直接以 `after_seq = lastContiguousSeq` 调用 `/events` 批量补齐，然后回到步骤 3。

## 轻客户端实现建议

- 用 `lastContiguousSeq` 作为续传和补洞游标，而不是 `lastAppliedSeq` 或 SSE `id`。浏览器自动重连时带上的 `Last-Event-ID` 只反映最后一条 `agent.event`，可能落后或跳跃，脚本客户端应显式维护 `lastContiguousSeq`。
- 对 `snapshot` 要先消费 `events` 数组再进入 live，避免重复渲染。
- `agent.event` 按 `(seq, eventType)` 去重；`run.status` 按 `(status, phase)` 去重。
- 收到 `error` 帧后应主动关闭本地 SSE parser，不要再依赖该连接。
- 收到终态 `run.done` 后，可再调一次 `/status` 和 `/events` 做最终确认。

## 历史状态拼写

SSE 中可能出现的历史状态拼写：`CANCELLED`、`TIMEOUT`、`TIMED_OUT`。轻客户端判断终态时应识别这些拼写，统一映射到 `CANCELED` / `EXPIRED` 语义。
