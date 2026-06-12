# 快速开始

本文档说明 AlphaFrog V1.0 的认证方式、响应结构和推荐的客户端调用顺序。

## 接口路径族

| 类型 | 路径前缀 | 说明 |
| --- | --- | --- |
| v1.0 Agent 接口 | `/api/agent/**` | 当前主线接口 |
| 认证接口 | `/api/auth/**` | 登录、登出、注册、密码相关 |

SSE 流路径：`GET /api/agent/runs/{runId}/stream`。

## 通用响应结构

大多数 JSON 接口返回项目内的 `ResponseWrapper`：

```json
{
  "code": 0,
  "data": {},
  "message": null
}
```

- `code = 0` 表示成功，非零表示业务错误。
- `data` 承载实际返回数据，类型因接口而异。
- `message` 在成功时通常为 `null`，错误时携带中文错误描述。

两种**不套** `ResponseWrapper` 的情况：

1. 登录和登出接口：直接返回纯文本（JWT 字符串或提示字符串）。
2. 文件下载接口（snapshot 分片、artifact 下载）：直接返回字节内容和响应头，不套 JSON 包装。

## 认证方式

后端接受两种身份来源，可任选其一：

| 方式 | 适用场景 | 用法 |
| --- | --- | --- |
| Bearer token | Python / Go / 脚本客户端 | `Authorization: Bearer <token>` 请求头 |
| 认证 cookie | 浏览器 SSE（`EventSource`） | 登录接口自动 `Set-Cookie`，后续请求自动携带 |

注意：

- **不要**把 token 写入公开日志、stdout 输出或公共频道消息。
- 浏览器 `EventSource` 不支持自定义请求头，只能依赖 cookie。如果使用 Python `requests` 或 `httpx` 自行构造 SSE 客户端，可以同时携带 bearer token。
- token 有有效期。如果请求返回 `401`，需要重新登录获取新 token。

## 推荐客户端调用顺序

以下流程来自 `agent_run_sse_load_test.py` 和 `froglib/flow_client.py` 的实际调用逻辑，覆盖一次完整的 Agent Run 交互。

### 第一步：登录获取 token

```
POST /api/auth/login
```

发送用户名和密码，成功时响应体是原始 JWT 字符串（不是 JSON），同时后端会 `Set-Cookie`。

请求：

```json
{"username": "your-username", "password": "your-password"}
```

成功响应（HTTP 200）：

```text
eyJhbGciOiJIUzI1NiJ9...
```

客户端应保存这个 token，后续所有请求都带 `Authorization: Bearer <token>`。

常见失败：

- `400` — 用户名或密码为空、密码错误，或**该用户已经登录**。如果收到 `User already logged in`，应先调 logout 再重试 login。
- `403` — 账号已被禁用。
- `429` — 登录频率过高或账号因多次失败被临时锁定（10 分钟）。

### 第二步：创建运行

```
POST /api/agent/runs
```

创建 Agent Run。必填字段只有 `message`（用户问题或指令）。

最小请求：

```json
{"message": "帮我分析最近一个季度的营收趋势"}
```

完整请求（含可选配置）：

```json
{
  "message": "用户问题或指令",
  "context": {"debugMode": false},
  "config": {
    "model": "endpointName/modelName",
    "webSearch": {"enabled": false, "sources": []},
    "codeInterpreter": {"enabled": false, "maxCredits": 0},
    "smartRetrieval": {"enabled": false, "sources": []}
  },
  "idempotencyKey": "客户端生成的请求标识（可选）",
  "captureLlmRequests": false,
  "provider": "可选 provider",
  "plannerCandidateCount": 0,
  "debugMode": false,
  "stage_config_json": "{}"
}
```

成功响应（HTTP 200）的 `data` 字段：

```json
{
  "id": "run-id",
  "status": "RECEIVED",
  "currentStep": 0,
  "maxSteps": 0,
  "plan": null,
  "snapshot": null,
  "lastError": null,
  "ttlExpiresAt": null,
  "startedAt": null,
  "updatedAt": null,
  "completedAt": null,
  "ext": null,
  "streamUrl": "/api/agent/runs/{runId}/stream"
}
```

客户端应保存 `id` 和 `streamUrl`。创建后 run 的状态为 `RECEIVED`。

重要边界：

- 必填字段是 `message`，**不是** `question`。
- `config.model` 会被后端解析为 `endpointName` 和 `modelName`。
- `plannerCandidateCount` 仅对 admin 用户生效，非 admin 传了会被静默忽略。
- `debugMode` 可从顶层字段或 `context.debugMode` / `context.debug_mode` 传入。
- `idempotencyKey` 用于客户端请求标识，透传至后端 RPC 并记录在 run 的 ext 元数据中（`idempotency_key` 字段）。

### 第三步：连接 SSE 流

```
GET /api/agent/runs/{runId}/stream
```

创建 run 后应尽快连接 SSE 流以获取实时进度。连接方式取决于客户端类型：

- **浏览器**：使用 `EventSource`，依赖 cookie 鉴权。创建 run 的请求和 SSE 连接必须在同一 origin 下。
- **Python / 脚本**：使用支持自定义请求头的 SSE 客户端，携带 `Authorization: Bearer <token>`。

SSE 流的 wire event name（`event:` 行）有以下几种（详见 [SSE 事件流](sse-stream.md)）：

| event name | 含义 |
| --- | --- |
| `snapshot` | 首包，包含 run 状态、最近 N 条事件、`lastSeq`、plan 等 |
| `agent.event` | 业务事件，具体类型在 `data.eventType` 字段中（如 `PLAN_READY`、`TODO_NODE_STARTED`、`TOOL_CALL_FINISHED`、`LLM_CALL_STARTED` 等，详见 SSE 事件流页） |
| `run.status` | 状态或 phase 变更 |
| `heartbeat` | 保活信号，每 30 秒 |
| `run.done` | 运行终止 |
| `error` | 鉴权失败或流异常，发送后连接关闭 |

注意：业务事件的具体枚举以 [SSE 事件流](sse-stream.md) 页为准，不要将 `eventType` 的值与 SSE wire event name 混淆。

### 第四步：处理断线重连

SSE 连接可能因网络波动中断。重连时通过以下方式续传：

**续传参数优先级（以后端 `AgentSseController.resolveResumeAfterSeq()` 为准）：**

1. `after_seq` 查询参数优先。推荐脚本客户端重连时带上 `?after_seq=<最后收到的 seq>`。
2. 没有 `after_seq` 时，后端读取 `Last-Event-ID` 请求头。浏览器 `EventSource` 重连时浏览器会自动带此头。
3. 两者同时存在时，以后端 `after_seq` 为准。

**补缺：** 如果重连后仍发现有事件缺失，调用 `GET /api/agent/runs/{runId}/events?after_seq=<lastContiguousSeq>` 拉取缺口事件。

**缓冲区溢出：** `AgentSseService` 在 replay 期间会将新到达的 live 事件暂存在内存缓冲区（`LIVE_REPLAY_BUFFER_LIMIT = 500`）。如果 replay 尚未完成而 live 缓冲区已满，服务端会发送 `LIVE_REPLAY_BUFFER_OVERFLOW` 错误并关闭连接。此时客户端应改用 REST `GET /events?after_seq=<lastContiguousSeq>` 补拉全部缺失事件。

### 第五步：运行结束后拉取结果

运行完全结束后（SSE 收到终态事件或 `/status` 返回终态状态），按需拉取：

| 目的 | 接口 | 说明 |
| --- | --- | --- |
| 确认状态 | `GET /status` | 返回当前 status、phase、currentTool、进度等 |
| 获取结果 | `GET /result` | 运行未完成时返回 HTTP 202；完成时返回 200 + answer |
| 查看成本 | `GET /cost` | 返回费用明细，注意 `complete` 和 `persisted` 的语义区别 |
| 排障观测 | `GET /timeline` | 按 `seq` 排序的时序事件，支持 `after_seq` 分页 |
| 深度排障 | `GET /traces` | 按 `type` + `phase` 过滤的 trace 列表 |
| 全量观测 | `GET /observability/full` | 全量观测数据聚合 |

### 第六步：用户中断

用户主动取消时调用 `POST /api/agent/runs/{runId}:cancel`。取消是**异步**过程：服务端会写入 cancel 状态、尽量 flush 观测数据、追加终态事件。取消后应再拉一次 status、result、events 以获取最终态。

## 运行状态一览

常见状态及含义：

| 状态 | 含义 |
| --- | --- |
| `RECEIVED` | 已接收，等待开始 |
| `PLANNING` | 正在生成执行计划 |
| `EXECUTING` | 正在执行工具调用 |
| `WAITING` | 已暂停，等待 resume 继续或 cancel 终止 |
| `SUMMARIZING` | 正在生成最终回答 |
| `COMPLETED` | 正常完成 |
| `PARTIAL` | 部分完成（部分步骤失败） |
| `FAILED` | 执行失败 |
| `CANCELING` | 正在取消中 |
| `CANCELED` | 已取消 |
| `EXPIRED` | 运行超时过期 |

SSE 流中可能出现的历史状态拼写：`CANCELLED`、`TIMEOUT`、`TIMED_OUT`，客户端在判断终态时应一并识别。

客户端判断终态的推荐逻辑：status 属于 `COMPLETED` / `PARTIAL` / `FAILED` / `CANCELED` / `EXPIRED`（以及历史拼写 `CANCELLED` / `TIMEOUT` / `TIMED_OUT`）时视为已结束，停止轮询。
