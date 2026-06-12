# 认证与运行生命周期

本页说明登录、创建运行和运行生命周期控制（取消、暂停、恢复、更新标题、删除）。每个端点列出请求格式、响应格式、边界条件和常见错误。

## 登录与登出

### `POST /api/auth/login`

登录并返回原始 JWT 字符串（**不是** `ResponseWrapper`），同时设置认证 cookie。后续请求可任选 Bearer token 或 cookie 鉴权。

**请求体：**

```json
{
  "username": "string（必填）",
  "password": "string（必填）"
}
```

**成功响应（HTTP 200）：**

响应体是纯文本 JWT 字符串，客户端应保存用于后续请求的 `Authorization: Bearer <token>` 头。

```text
eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ...
```

**错误响应表：**

| HTTP 状态 | 响应体示例 | 触发条件 |
| --- | --- | --- |
| `400` | `Invalid request body format` | 请求体不是合法 JSON 或字段类型错误 |
| `400` | `Username and password are required` | 用户名或密码为空 |
| `400` | `Invalid credentials` | 密码错误；剩余尝试次数不足 5 时还会附带 `X attempts remaining` |
| `400` | `User already logged in` | 该用户已有有效登录会话，需先 logout |
| `403` | `Account is disabled` | 账号被禁用 |
| `429` | `Too many login attempts, please try again later` | 登录频率触发全局限流 |
| `429` | `Account temporarily locked due to too many failed login attempts` | 该账号因多次失败被锁定 10 分钟 |

**`User already logged in` 的重试模式：**

v1.0 flow client 将此错误视为可恢复：收到后先调 `POST /api/auth/logout`（带同一 username），logout 成功后再重试 login。这个流程在压测脚本的 token pool 刷新逻辑中使用。

### `POST /api/auth/logout`

退出指定用户并清理认证 cookie。

**请求体：**

```json
{
  "username": "string（必填）"
}
```

**成功响应（HTTP 200）：**

纯文本字符串，不是 `ResponseWrapper`。

```text
User logged out successfully
```

**错误响应：**

| HTTP 状态 | 响应体示例 | 触发条件 |
| --- | --- | --- |
| `400` | `User not logged in` | 该用户当前没有登录会话 |

### 其他认证端点（不参与 Agent Run 流程）

| 端点 | 方法 | 说明 |
| --- | --- | --- |
| `/api/auth/register` | POST | 注册新用户，需要 username、password、email、inviteCode |
| `/api/auth/me` | GET | 返回当前登录用户的信息（需要已认证） |
| `/api/auth/forgot-password` | POST | 发送密码重置邮件 |
| `/api/auth/reset-password` | POST | 用 token 重置密码 |
| `/api/auth/change-password` | POST | 已登录用户修改密码 |

这些端点不参与 Agent Run 的客户端交互流程，此处不展开。

## 创建运行

### `POST /api/agent/runs`

创建 Agent Run。必填字段只有 `message`（用户问题或指令）。

**请求体（最小）：**

```json
{
  "message": "用户问题或指令"
}
```

**请求体（完整字段）：**

```json
{
  "message": "用户问题或指令（必填，不能为空或纯空白）",
  "context": {
    "debugMode": false
  },
  "config": {
    "model": "endpointName/modelName",
    "webSearch": {
      "enabled": false,
      "sources": []
    },
    "codeInterpreter": {
      "enabled": false,
      "maxCredits": 0
    },
    "smartRetrieval": {
      "enabled": false,
      "sources": []
    }
  },
  "idempotencyKey": "客户端生成的请求标识（可选）",
  "modelName": "直接指定 modelName（可选，会被 config.model 解析结果覆盖）",
  "endpointName": "直接指定 endpointName（可选，会被 config.model 解析结果覆盖）",
  "captureLlmRequests": false,
  "provider": "可选 provider",
  "plannerCandidateCount": 0,
  "debugMode": false,
  "stage_config_json": "{}"
}
```

**字段边界：**

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `message` | **是** | 用户问题或指令。注意字段名是 `message`，不是 `question` |
| `config.model` | 否 | 格式为 `endpointName/modelName`，后端解析后**覆盖**顶层 `modelName` / `endpointName` 字段 |
| `modelName` / `endpointName` | 否 | 顶层直接指定，但会被 `config.model` 解析结果覆盖（如果 `config.model` 非空） |
| `plannerCandidateCount` | 否 | 仅 admin 用户生效；非 admin 传了会被静默忽略并记录日志 |
| `debugMode` | 否 | 可从顶层字段、`context.debugMode` 或 `context.debug_mode` 三个位置传入 |
| `idempotencyKey` | 否 | 客户端请求标识，透传至 RPC 并记录在 run ext 的 `idempotency_key` 字段中 |
| `stage_config_json` | 否 | JSON 字符串，传递给后端 stage 配置 |

**成功响应（HTTP 200）：**

`ResponseWrapper.data` 为 `AgentRunResponse`：

```json
{
  "code": 0,
  "data": {
    "id": "run-id（UUID 格式）",
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
  },
  "message": null
}
```

创建后 status 为 `RECEIVED`，`streamUrl` 是后续 SSE 连接的路径。运行启动后 status 会经过 `PLANNING` → `EXECUTING` → `SUMMARIZING` → `COMPLETED` 等状态转换。

**常见错误：**

| 条件 | `code` | `message` |
| --- | --- | --- |
| `message` 为空 | 非零 | `message 不能为空` |
| 未登录 | 非零 | `未登录或用户不存在` |
| 账号被禁用 | 非零 | `账号已被禁用，无法创建新任务` |
| Dubbo RPC 异常 | 非零 | 具体错误信息 |

## 控制运行

### `POST /api/agent/runs/{runId}:cancel`

请求取消运行。

取消是**异步**过程：服务端会写入 cancel 状态、尽力 flush 观测数据、追加终态事件，然后返回当前 run view。响应结构与创建/查询 run 相同（`AgentRunResponse`）。

调用后运行状态会经过 `CANCELING` 再到达 `CANCELED`。客户端应继续监听 SSE 流直到收到终态事件，或轮询 `/status` 直到终态。

**无请求体。**

### `POST /api/agent/runs/{runId}:pause`

暂停运行，返回当前 run view。

暂停后运行进入 `WAITING` 状态，等待用户通过 resume 继续或通过 cancel 终止。

注意：`WAITING` 状态由 pause 操作进入。对已完成 run 发送追问消息不应混为一谈——追问消息走 `POST /messages` 接口，在已完成 run 上发起新对话并继续通过 SSE/events 观察。

**无请求体。**

### `POST /api/agent/runs/{runId}:resume`

恢复运行。可恢复的状态包括 `WAITING`、`FAILED`、`CANCELED`——这三种状态下 resume 会将运行重置为 `RECEIVED` 并异步重新执行。`EXPIRED` 状态调用 resume 会返回错误。其他非可恢复状态直接返回当前 run 不做变更。

**请求体（可选）：**

```json
{
  "planOverrideJson": "{}"
}
```

`planOverrideJson` 是 JSON 字符串格式的执行计划覆盖，**不是**自由文本。不传或传空对象表示不覆盖 plan。

**响应：** 返回当前 run view（`AgentRunResponse`）。

## 更新与删除

### `PUT /api/agent/runs/{runId}`

更新运行标题。

**请求体：**

```json
{
  "title": "新标题"
}
```

**字段约束：**

| 约束 | 说明 |
| --- | --- |
| 必填 | `title` 不能为空或纯空白 |
| 长度上限 | 不超过 120 个字符（trim 后） |
| 响应 | 返回更新后的完整 `AgentRunResponse` |

**错误：**

| 条件 | `message` |
| --- | --- |
| title 为空 | `title 不能为空` |
| title 超过 120 字符 | `title 长度不能超过 120` |

### `DELETE /api/agent/runs/{runId}`

删除当前用户拥有的运行。

**删除规则：**

- 只能删除属于自己的 run（通过当前认证用户鉴权）。
- **正在执行**的 run 禁止删除。运行中状态包括：`RECEIVED`、`PLANNING`、`EXECUTING`、`SUMMARIZING`。应先 cancel 或 pause 后再删除。
- 删除后会同步清理 Redis 中的状态缓存。

**成功响应：**

```json
{
  "code": 0,
  "data": "ok",
  "message": null
}
```

**错误响应：**

| HTTP 状态 | 条件 |
| --- | --- |
| `401` | 用户未登录 |
| `404` | run 不存在或不属于当前用户 |
| `409` | run 正在执行中，需先取消或暂停 |

## 生命周期状态转换图

```
RECEIVED → PLANNING → EXECUTING → SUMMARIZING → COMPLETED
                ↓           ↓            ↓
             FAILED      WAITING ←     PARTIAL
               ↑            ↓    ↑
               └── :resume ─┘    │
               ┌── :resume ──────┘
            CANCELED ← CANCELING
               ↑            ↑
               └── :resume ─┘ (:cancel 从 EXECUTING 进入)

任意非终态 → EXPIRED（超时，不可 resume）
```

- `WAITING`：通过 `:pause` 从 `EXECUTING` 进入；通过 `:resume` 回到 `RECEIVED` 重新执行。
- `FAILED` / `CANCELED`：均为终态，但可通过 `:resume` 重置为 `RECEIVED` 重新执行。
- `EXPIRED`：超时终态，**不可** resume。
- `CANCELING`：过渡态，由 `:cancel` 触发，最终到达 `CANCELED`。
- `PARTIAL`：部分步骤成功但整体未完全达成目标，也是终态。
