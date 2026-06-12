# 错误与脚本覆盖

本页汇总通用错误，以及 v1.0 压测脚本实际直接使用的接口。

## 通用 HTTP 状态和业务错误

| HTTP | `code` | 常见原因 |
| --- | --- | --- |
| `200` | `0` | 成功 |
| `202` | `0` | 请求被接受但结果未就绪；`/result` 在任务未完成时会这样返回，并带 message `任务未完成` |
| `400` | 非 0 | 请求体非法、缺少必填字段，或业务前置条件不满足 |
| `401` | 非 0 | JWT、cookie 或 admin token 缺失/无效 |
| `403` | 非 0 | 权限不足或账号禁用 |
| `404` | 非 0 | run、trace、artifact 或 snapshot 不存在 |
| `429` | 非 0 | 登录限流或临时锁定 |
| `502` | 非 0 | 上游 RPC 失败，例如 artifact storage 不可达 |

多数接口返回 `ResponseWrapper`。artifact 下载、snapshot part 下载等字节接口可能直接返回 HTTP 状态，失败时 body 为空。

## 压测脚本直接覆盖范围

`test_scripts/agent-v1p0/agent_run_sse_load_test.py` 直接使用的是较小子集：

| 接口 | 脚本用途 |
| --- | --- |
| `POST /api/auth/login` | `FlowClient.login()` 或配置里的 `auth.login_endpoint` |
| `POST /api/auth/logout` | 配置里的 cleanup endpoint |
| `POST /api/agent/runs` | 通过 orchestrator 创建 run |
| `GET /api/agent/runs/{runId}/stream` | 主要实时事件来源 |
| `POST /api/agent/runs/{runId}:cancel` | stop / cancel flow |
| `GET /api/agent/runs/{runId}/status` | 状态回查与校准 |
| `GET /api/agent/runs/{runId}/result` | 最终内容拉取 |
| `GET /api/agent/runs/{runId}/events` | SSE 落后时通过 `after_seq` gap-fill，见 `orchestrator._repair_events_from_rest` |
| `GET /api/agent/runs/{runId}/cost` | 相关 flow scripts 中的 cost fetch |
| `GET /api/agent/runs/{runId}/snapshot/parts` | reusable flow helpers 中的 snapshot part metadata |
| `GET /api/agent/runs/{runId}/snapshot/parts/{partIndex}` | reusable flow helpers 中的 snapshot part download |

压测脚本默认把 SSE 当作进度主来源，不会在每次 run 结束后主动拉完整可观测性数据。人工排障工具和轻量 TUI 可以按需要使用更完整的观测接口。
