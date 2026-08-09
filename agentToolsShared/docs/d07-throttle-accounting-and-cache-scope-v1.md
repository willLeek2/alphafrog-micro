# D07 — 工具限流拦截的预算/计费口径与缓存作用域说明

版本：v1（26Q3 Stage1 W1，task #101）  
范围：`agentToolsShared`（ToolRouter / ToolResultCacheService）、`agentLangchainService`（ToolRouterToolExecutor）、`agentPlatformShared`（AgentCreditService）  
关联：D05（注册表单一来源）、D25（集群扩展文档）、Risks 1.3.5 / 3.3.2、Overview 方面二-8

---

## 1. 限流拒绝 ≠ 成功工具调用（唯一口径）

凡因容量或并发限流未能取得执行权的调用——权重限流 `TOOL_WEIGHT_LIMIT_EXCEEDED`、LC4j 前台 Semaphore 超时/中断——**未真正执行工具体**，全平台按同一规则记账：

| 维度 | 规则 |
|------|------|
| Run 工具预算（maxToolCalls 判定） | **不消耗**。权重拒绝路径不再写入 observability 成功 toolCalls 累加器；LC4j 拒绝本就不进 Router。预算检查读取的计数源唯一（observability summary.toolCalls）。 |
| 工具 credit（账单） | **不计**。`TOOL_CALL_FINISHED` 事件若因 UI 收尾需要保留，payload 必带 `creditsConsumed: 0`、`rejected_by_throttle: true`、`throttle_layer: weight_limit|lc4j_semaphore`；`AgentCreditService` 汇总侧对 `rejected_by_throttle=true` 显式跳过（优先于任何显式 credits 字段）。 |
| 拒绝观测 | 独立低基数 Micrometer 计数器 `tool.call.throttle.rejected{toolName, layer}`；不写入业务 tool trace，不计入执行耗时 Timer。 |
| 模型可见 | 拒绝返回可解析错误（权重层为标准错误 JSON，LC4j 层为原因文本），模型可稍后重试或缩小批量。 |

两层限流（`ToolWeightedLimitService` 权重层、`LangchainToolConcurrencyThrottle` LC4j 层）对「是否计预算 / 是否扣 credit」同口径，禁止分叉。

## 2. 元工具豁免

`checkParallelLimits` 保持既有豁免且与计费口径一致：

- 不消耗 maxToolCalls 成功预算（预算检查跳过）；
- 不写入业务 tool trace（观测记录跳过）；
- 不产生按次工具执行 credit（汇总侧按工具名跳过）；
- 权重限流对其发 noop 租约（豁免的是容量占用，与计费语义正交）。

## 3. 匿名 / 缺身份的缓存作用域（Risks 3.3.2）

工具结果缓存（Redis 族）scope 解析规则：

1. 有 userId → `user:<userId>`；
2. 无 userId 有 runId → `run:<runId>`（匿名 run 之间互不命中；AgentContext 当前无 session 身份概念，run 即匿名隔离单元）；
3. 二者皆无 → 空 scope，缓存层 **fail-closed 跳过共享缓存读写**（该次强制回源，且不写入任何跨请求共享键）。

任何路径不得再把缺身份默认写成可跨租户串线的 `global`；键构建层的 blank-scope 兜底已移除（仅 DATASET_REGISTRY 模式的观测性键使用 `no-shared-scope` 明示标签，该模式为内容寻址的系统级市场数据复用，不经 Redis scope 键）。

## 4. per-node 语义（集群归 D25）

权重限流 Semaphore 与 LC4j 前台 Semaphore 均为 **JVM 进程内**实现：多实例部署时各实例各自计数、各自封顶，不是分布式容量保证。运维估算：`全局许可 ≈ 实例数 × 每实例上限`。集群级限流/配额为扩展期事项，见 D25 交付与 `LangchainToolConcurrencyThrottle` / `ToolWeightedLimitService` 的类级说明（W5 G7 已冻结）。

## 5. 边界

本交付不重做工具注册单一来源（D05）、不实现子 Agent 控制工具（D06）、不改 LLM token/settlement 计费路径（S5A）、不实现分布式限流（D25）。
