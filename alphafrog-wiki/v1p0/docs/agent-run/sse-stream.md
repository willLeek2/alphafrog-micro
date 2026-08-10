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
| `agent.event` | Agent 事件；通常为持久化事件，少数流式 DELTA 为 `durable=false` 的 live-only 事件 |
| `run.status` | 状态和 phase 观察事件，连接初始和状态变化时发送 |
| `heartbeat` | 30 秒一次的 keepalive |
| `run.done` | 终态通知 |
| `error` | 初始化、认证、replay 错误或 live buffer overflow，发送后关闭流 |

`agent.event` 里的 `eventType`（如 `PLAN_READY`、`TOOL_CALL_FINISHED`）是业务事件类型，不要把它们和 SSE wire event name 混为一谈。

wire envelope 示例：

```text
id: 42
event: agent.event
data: {"schemaVersion":1,"type":"agent.event","id":1001,"runId":"run-id","seq":42,"eventType":"TOOL_CALL_FINISHED","payload":{},"createdAt":"2026-06-11T12:00:30Z","ts":1781160030000,"durable":true}
```

字段规则：

- `id` 是 SSE 协议帧的 `id:` 行。服务端仅对 `durable=true && seq>=1` 的 `agent.event` 设置它，值等于 `seq`。live-only 事件和其他帧均不设置 SSE `id`。
- `event` 是 SSE wire event name。
- `data` 是 JSON。多行 payload 会拆成多条 `data:`，客户端必须合并后再解析。
- 空行表示上一个事件结束。

## `lastSeq`、`seq` 与 SSE `id` 的关系

这三者容易混淆，但对断线续传和缺口修复至关重要：

| 字段 | 含义 | 出现位置 |
| --- | --- | --- |
| `seq` | durable 事件的持久化序号；live-only 固定为 0 | `agent.event.data.seq`、`snapshot.events[].seq` |
| `lastSeq` | 该连接已确认发送的最大 durable `seq` | `snapshot.lastSeq`、`run.status.lastSeq`、`run.done.lastSeq` |
| SSE `id` | SSE 协议帧的 `id:` 行，仅 `agent.event` 设置，重连时浏览器会自动作为 `Last-Event-ID` 带上 | `id: <seq>`，与对应 `agent.event` 的 `seq` 相同 |

注意：

- `lastSeq` 不是事件数量，也不要求从 1 连续增长。Redis `INCR` 后 PostgreSQL `appendOnce` 冲突会留下合法 seq 缺口。
- `agent.event` 的 `data` 里放的是 `seq`，不是 `lastSeq`。
- `run.status` / `run.done` 的 `data` 里放的是 `lastSeq`，因为它本身不是持久化事件，而是状态观察帧。
- 只有 durable `agent.event` 带 SSE `id`；live-only `durable=false,seq=0,id=null` 不参与 `after_seq`、`Last-Event-ID` 去重或 cursor。

## snapshot 载荷

```json
{
  "type": "snapshot",
  "schemaVersion": 1,
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

- `events` 默认是最近 10 条持久化事件（按 `seq` 升序），每条都是下述 schemaVersion=1 envelope。
- `eventCount` 是 run 的持久化事件数量；`lastSeq` 是已纳入 snapshot/恢复 cursor 的最大 durable seq，二者不可互换。
- 无论是否带恢复游标，首帧都必须是 `snapshot`。服务端初始化期间缓冲所有 live，再原子执行 replay → buffer flush → live。
- 普通用户的 `plan` 经过 `View.PLAN` 白名单，管理员经过 `View.ADMIN`；畸形 plan JSON 固定为 `null`，不会回显存储原文。

## agent.event 载荷

```json
{
  "schemaVersion": 1,
  "type": "agent.event",
  "id": 1001,
  "runId": "run-id",
  "seq": 42,
  "eventType": "TOOL_CALL_FINISHED",
  "payload": {
    "toolName": "ragSearch",
    "success": true
  },
  "createdAt": "2026-06-11T12:00:30Z",
  "ts": 1781160030000,
  "durable": true
}
```

- `event` 层的 SSE 名称固定是 `agent.event`。
- 真正的业务事件类型在嵌套字段 `eventType` 里。
- `payload` 在离开 SSE service 前会被共享 parser 规范化为 JSON object，REST `/events` 使用同一 parser。object 保持 object；array/string/number/boolean 包为 `{ "value": ... }`；空值为 `{}`；畸形 JSON 为 `{ "value": "INVALID_JSON" }`，绝不回显原始存储字符串。
- `createdAt` 是事件发生时间，`ts` 永远从它推导并在 snapshot/replay/live 间保持稳定；发送时间不能覆盖 `ts`。
- `id` 是可选 PostgreSQL 行 ID。事件身份与去重键是 `(runId, seq)`，不是 `id` 或 `(seq,eventType)`。

## 续传与 live 缓冲溢出

带游标重连时，服务端会：

1. 先发送 snapshot，再按 200 条一页从持久化存储读取 `seq > resumeAfterSeq` 的事件并推送。
2. replay 期间同时监听 Redis live 频道，把新到达的事件暂存到内存缓冲区（上限 500 条）。
3. replay 结束后，在一个原子切换过程里按 `seq` flush durable 缓冲事件并按到达顺序发送 live-only 事件；已推过的 `(runId,seq)` 会跳过。

如果 replay 期间 live 事件来得太快，缓冲区超过 500 条，服务端会：

- 设置 overflow 标志。
- 首次丢弃发生后尽快（目标 1 秒内）发送 `error` 帧，code 为 `LIVE_REPLAY_BUFFER_OVERFLOW`。
- 关闭 SSE 连接。

此时客户端**不要**继续等 SSE 自动补齐，而应改用 REST 拉取：

```
GET /api/agent/runs/{runId}/events?after_seq=<lastConfirmedSeq>&limit=200
```

## REST `/events` 补洞

`/events` 返回持久化事件列表，默认 `limit=200`，最大 `500`。SSE 断线或缺口修复时，可以用它按 `after_seq` 补拉事件。

典型补洞流程（TUI / 脚本视角）：

1. 维护 `lastConfirmedSeq`：已从 snapshot/SSE/REST 服务端帧实际应用的最大 durable seq。它是“确认过的最大值”，不是通过 `+1` 推算的连续游标。
2. snapshot 先应用 `events` 再记录其中最大 durable seq；`eventCount` 不得当作 cursor。
3. live-only 事件直接展示，但不改变 `lastConfirmedSeq`。
4. durable live 事件若明显跳过当前 cursor，先暂停渲染该事件，以 `after_seq=lastConfirmedSeq` 调 `/events`。REST 页内 seq 缺口合法；按返回项目实际 seq 与 `nextAfterSeq` 推进，不要求补出每个整数。
5. 如果 REST 为空/失败且 status 表明还有新 durable event，最多自动重试三次，退避 250/500/1000ms；仍失败则报告可恢复错误并停止自动重试。人工恢复仍从最后确认 cursor 开始。
6. 健康依赖且 durable event 不超过 5000 时，恢复目标 p95≤10s、p99≤30s；超过 30s 必须显式报告 `RECOVERY_SLA_EXCEEDED`。
7. durable event 超过 5000 时进入 `degraded_large_run`，恢复期间走 REST-only，不伪造 `run.done`，也不套用 10/30s SLA；成功追平后可从确认 cursor 返回 SSE。

## 轻客户端实现建议

- 用 `lastConfirmedSeq` 作为续传和补洞游标。不要因为看见整数缺口而自行插值，也不要用 eventCount 替代它。
- 对 `snapshot` 要先消费 `events` 数组再进入 live，避免重复渲染。
- durable `agent.event` 按 `(runId,seq)` 去重；live-only 事件不进入 durable 去重集合；`run.status` 按 `(status,phase)` 去重。
- 收到 `error` 帧后应主动关闭本地 SSE parser，不要再依赖该连接。
- 收到终态 `run.done` 后，可再调一次 `/status` 和 `/events` 做最终确认。

## 终态拼写

服务端对外只发送 `COMPLETED/PARTIAL/FAILED/CANCELED/EXPIRED`。读取旧数据时统一 trim/case，并把 `CANCELLED→CANCELED`、`TIMEOUT/TIMED_OUT→EXPIRED`；`CANCELING` 是过渡态，绝不能提前关闭连接。
