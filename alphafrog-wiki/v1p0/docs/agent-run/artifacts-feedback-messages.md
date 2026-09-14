# 产物、反馈与追问

本页覆盖 run 结束后或排障时会用到的辅助接口：snapshot 分片、artifact 列表与下载、feedback、export、follow-up messages。

这些接口都需要登录态，路径都在 `/api/agent/runs/...` 下面。

## snapshot 分片

snapshot 体积可能较大，客户端先取分片元数据，再按 `partIndex` 下载字节。

### `GET /api/agent/runs/{runId}/snapshot/parts`

返回大 snapshot 的分片元数据。

查询参数：

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `maxPartSize` | `0` | `0` 表示使用后端默认分片大小；非 0 时传给后端作为最大 part size |

响应 `data`：

```json
{
  "runId": "run-id",
  "partSize": 262144,
  "totalParts": 3,
  "uncompressedSize": 700000,
  "compressedSize": 500000,
  "compression": "gzip",
  "checksum": "sha256:..."
}
```

字段说明：

| 字段 | 说明 |
| --- | --- |
| `runId` | run id |
| `partSize` | 后端实际使用的分片大小 |
| `totalParts` | 分片总数 |
| `uncompressedSize` | 原始 snapshot 字节数 |
| `compressedSize` | 压缩后字节数 |
| `compression` | 压缩算法；当前由后端 snapshot part service 决定 |
| `checksum` | 完整 snapshot 的校验值 |

### `GET /api/agent/runs/{runId}/snapshot/parts/{partIndex}`

下载单个 snapshot part，响应体是字节内容，不是 `ResponseWrapper`。

查询参数：

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `maxPartSize` | `0` | 必须和元数据请求使用同一语义；通常客户端保持默认即可 |

成功响应头：

| Header | 含义 |
| --- | --- |
| `Content-Type` | `application/octet-stream` |
| `Content-Length` | 当前 part 的字节数 |
| `Cache-Control` | `no-store` |
| `X-Snapshot-Compression` | 压缩算法 |
| `X-Snapshot-Part-Index` | 当前 part index |
| `X-Snapshot-Part-Size` | 当前 part size |
| `X-Snapshot-Total-Parts` | 总 part 数 |

常见失败直接用 HTTP 状态表达，不包一层 `ResponseWrapper`。

## artifact 列表与下载

artifact 是 run 产生或关联的可下载产物。目前主要来自 `executePython` 脚本快照和 market-data dataset 文件。

### `GET /api/agent/runs/{runId}/artifacts`

列出当前用户可读的 run 产物。

响应 `data`：

```json
[
  {
    "artifactId": "artifact-id",
    "type": "python_script",
    "name": "script-run-id-ref.py",
    "contentType": "text/x-python",
    "url": "/api/agent/runs/{runId}/artifacts/{artifactId}/download",
    "metaJson": "{\"kind\":\"python_script\",\"scope\":\"normal\"}",
    "createdAt": "2026-06-11T12:03:00Z",
    "expiresAtMillis": 1781163600000
  }
]
```

字段说明：

| 字段 | 说明 |
| --- | --- |
| `artifactId` | 下载时使用的 artifact id |
| `type` | 产物类型，例如 `python_script`、`dataset_csv`、`dataset_meta` |
| `name` | 下载文件名 |
| `contentType` | 下载响应的 MIME type |
| `url` | run-scoped 下载路径 |
| `metaJson` | 字符串形式 JSON；常见字段有 `kind`、`scope`、`source`、`seq`、`success`、`dataset_id` |
| `createdAt` | 产物创建时间；空字符串会在接口响应里转成 `null` |
| `expiresAtMillis` | 过期时间戳；`<= 0` 会在接口响应里转成 `null` |

边界：

- 普通用户只看普通范围；admin 会看到 admin 范围产物，保留时间也更长。
- 过期产物不会出现在列表里。
- 单个 artifact 下载大小受 `agent.artifact.download.max-bytes` 限制，默认 10 MiB。

### `GET /api/agent/runs/{runId}/artifacts/{artifactId}/download`

下载带 runId 的 artifact，响应体是字节内容，不是 `ResponseWrapper`。

另有全局下载路径：

- `GET /api/agent/artifacts/{artifactId}/download`

两类下载都会把 `artifactId` 传给 Dubbo `downloadArtifact`。带 runId 的下载路径仍要求 URL 中有 `runId`，但实际下载查找以 `artifactId` 和当前用户权限为准。

成功响应头：

| Header | 含义 |
| --- | --- |
| `Content-Type` | artifact 的 `contentType`；无法解析时回退 `application/octet-stream` |
| `Content-Length` | 文件字节数 |
| `Content-Disposition` | `attachment; filename="..."` |

常见失败：

| HTTP | 含义 |
| --- | --- |
| `401` | 未认证 |
| `400` | run-scoped 路径缺少 `runId` |
| `404` | run 或 artifact 不存在，或当前用户不可读 |
| `422` | artifact 超过下载大小限制 |
| `502` | 上游 RPC 失败 |
| `500` | 其他服务端错误 |

## feedback 反馈

### `POST /api/agent/runs/{runId}/feedback`

提交 run feedback。后端会追加 `FEEDBACK_RECEIVED` 事件。

请求：

```json
{
  "rating": 1,
  "comment": "optional comment",
  "tags": ["useful"],
  "payload": {
    "source": "light-client"
  }
}
```

字段说明：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `rating` | integer | 当前 controller 只接收整数；不要传 `"positive"` / `"negative"` 字符串 |
| `comment` | string | 可选备注 |
| `tags` | array | 可选标签，会序列化成 `tagsJson` 传给后端 |
| `payload` | object | 可选扩展数据，会序列化成 `payloadJson` 传给后端 |

响应 `data` 是字符串：

```json
"ok"
```

## export 导出

### `POST /api/agent/runs/{runId}:export`

提交 run export 请求。

请求：

```json
{
  "format": "json"
}
```

响应 `data`：

```json
{
  "exportId": "export-id",
  "status": "not_implemented",
  "url": null,
  "message": "export not implemented in langchain service yet"
}
```

实现现状：

- 两个 controller 都会记录 `EXPORT_REQUESTED` 事件。
- export 当前是未完整实现的占位接口：会返回 `exportId`，但 `status` 是 `not_implemented`，通常没有可下载 `url`。
- 不要把这个接口描述成已经生成文件或已经能稳定下载导出包。

## follow-up messages

follow-up messages 用于在一个已完成 run 上继续追问。服务端会复用同一个 run，消息历史也是后续上下文来源之一。

### `POST /api/agent/runs/{runId}/messages`

向 run 发送追问消息。

请求：

```json
{
  "content": "follow-up question",
  "contextOverride": "{}",
  "debugMode": false,
  "stream": false
}
```

字段说明：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `content` | string | 必填，不能为空 |
| `contextOverride` | string | 仅 admin 且 `debugMode=true` 时传给后端；其他情况会被忽略并写 warn log |
| `debugMode` | boolean | 是否允许使用 `contextOverride` |
| `stream` | boolean | 传给后端 `SendAgentMessageRequest.stream`；客户端仍应通过 run SSE stream 观察事件变化 |

响应 `data`：

```json
{
  "messageId": 123,
  "seq": 2,
  "status": "accepted",
  "runStatus": "EXECUTING",
  "rejectReason": null
}
```

边界：

- `content` 为空会返回参数错误。
- 目前仅 `COMPLETED` 状态的 run 支持追问；其他状态会被后端拒绝，并在 `rejectReason` 中说明原因。
- follow-up 发送成功后，客户端应继续用 `/stream` 或 `/events` 观察新事件，不应假设 `POST /messages` 本身会返回最终答案。

### `GET /api/agent/runs/{runId}/messages`

列出 run 消息历史。

查询参数：

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `limit` | `50` | 后端限制到 `1..200` |
| `offset` | `0` | 负数会按 `0` 处理 |
| `include_initial` | `true` | 是否包含初始用户消息 |

响应 `data`：

```json
{
  "items": [
    {
      "id": 123,
      "seq": 1,
      "role": "user",
      "content": "question",
      "msgType": "initial",
      "metaJson": "{}",
      "createdAt": "2026-06-11T12:00:00Z"
    }
  ],
  "total": 1,
  "hasMore": false
}
```

字段说明：

| 字段 | 说明 |
| --- | --- |
| `items[].id` | 消息表主键 |
| `items[].seq` | run 内消息序号 |
| `items[].role` | 消息角色，常见值为 `user`、`assistant`、`system` |
| `items[].content` | 消息内容 |
| `items[].msgType` | 消息类型，常见值为 `initial`、`follow_up`、`summary` |
| `items[].metaJson` | 字符串形式 JSON 元数据；空字符串会在接口响应里转成 `null` |
| `items[].createdAt` | 消息创建时间；空字符串会在接口响应里转成 `null` |
| `total` | 符合筛选条件的总消息数 |
| `hasMore` | `offset + items.length < total` 时为 `true` |
