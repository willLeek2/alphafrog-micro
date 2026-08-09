# D25 沙箱与调度可扩展性接缝

| Wave | 260809-26q3-stage1 W5 (task #105) |
|------|-----------------------------------|
| Slice | ccmax D25 容量接缝 (7.1.1 容量账本 + 7.1.2 在途监控) |
| Base | `13539f49` (S_w5_d04) |
| 关联审计 | `notes-w5/D25-capacity-seam-audit.md` (worktree-local) |

## 1. 范围

文档化"当前实现是单机"的事实，并列出多实例扩展前必须替换的接缝点。本文只文档化接缝，不实现多实例本体；不重写 D04 storage / D21 dump / D25 sandbox 单实例语义。

## 2. 容量账本接缝 (7.1.1)

### 2.1 接缝点

- `DataAnalysisCapacityService` (interface, `agentPlatformShared/.../dataanalysis/`) — 当前实现 `DataAnalysisCapacityServiceImpl` 是单 Java 进程内 in-memory (`ConcurrentHashMap` ledger + `AtomicInteger` counters)，类注释 L27/L33-34 已自我声明。
- `DataAnalysisCapacityProperties` (`@ConfigurationProperties(prefix = "alphafrog.data-analysis.capacity")`, `agentToolsShared/.../python/`) — 配置载体也是接缝一部分。替换 impl 时 properties 必须保留现有 getter (`getMaxUnits` / `getMaxHeavyActive` 等)，因为 `ToolJobStartupRecovery.recover(...)` 入参需要传值 (`ToolJobStartupRecovery.java:33,222-224`)。
- `CapacityAdmissionException` (top-level class, `agentPlatformShared/.../dataanalysis/`) — 异常已迁出 impl 类作为接口包内共享类型 (commit `9b131aef` G1)，避免 consumer 依赖 impl-specific 类型。`Reason` 枚举与 §6.8 错误码词汇表镜像。

### 2.2 容量上限公式

```
全局 capacity units 上限 ≈ 实例数 × alphafrog.data-analysis.capacity.max-units
全局 HEAVY 并发上限 ≈ 实例数 × alphafrog.data-analysis.capacity.max-heavy-active
```

当前实现不维护跨实例聚合；多实例时每个 JVM 独立计容量，跨实例超卖不受保护。Q-03 (外置存储选型) 裁定前不接入。

### 2.3 接口前置条件

`DataAnalysisCapacityService.java:5-10` 接口注释已追加多实例替换前置条件 (commit `3df165a7` G3)：实现当前为单 Java 进程内 in-memory；多实例横向扩展前必须替换为外置持久化实现。

## 3. 在途 Run 调度接缝 (7.1.2)

### 3.1 单实例假设

`LangchainRunConcurrencyScheduler` (`agentLangchainService/.../orchestration/LangchainRunConcurrencyScheduler.java`) 是单 JVM 内的有界执行槽位调度器。所有计数和队列在同一把 lock 内修改 (类注释 L19-29)。槽位 hard limits 来自 `LangchainRunExecutorLimitsResolver.hardLimits()`，启动冻结自 `agent.langchain.run.executor.{core,max,queue}-pool-size`。

### 3.2 全局并发公式

```
全局 Run 并发上限 ≈ 实例数 × agent.langchain.run.executor.max-pool-size
全局排队上限 ≈ 实例数 × agent.langchain.run.executor.queue-capacity
```

### 3.3 Snapshot 字段 (per-node)

`buildSnapshot()` 暴露 (commit `b32d6166` G4 加入 `instanceId`)：

| 字段 | 语义 |
|------|------|
| `instanceId` | `hostname@pid`，JVM 启动时一次性解析，标识 snapshot 来源 |
| `running` | 已交线程池、未从 Runnable finally 退出的 Run 数 |
| `queued` | 已 reserve 未 submit + 已入 queue 的名额 |
| `rejectedTotal` | 累计拒绝数 (仅观测) |
| `corePoolSize` / `maxPoolSize` / `queueCapacity` | 当前动态限制 (`currentLimits()`) |
| `hardCorePoolSize` / `hardMaxPoolSize` / `hardQueueCapacity` | 启动冻结硬上限 (`hardLimits()`) |
| `oldestQueuedAgeMs` | 队首入队时间 |

所有字段都是 per-node。跨实例聚合必须由外部观测系统 (Prometheus / Grafana) 按 `instanceId` label 求和。

### 3.4 HTTP 暴露与 per-node 语义

`GET /agent-langchain/scheduler` (`AgentLangchainHealthController.java:44-47`) 调用 `schedulerSnapshot()`。响应体未在 JSON 顶层显式标注 per-node scope；调用方需通过 `instanceId` 字段识别 snapshot 来源。多实例部署中，需要轮询每个实例的 endpoint 或接入 Metrics 导出 (见 §4)。

## 4. 观测导出缺口

| 维度 | 当前 | 缺口 |
|------|------|------|
| Micrometer | 工具调用 latency 维度已注册 (`ToolRouter` / `ToolResultCacheService` 等)，但 `running` / `queued` 未注册为 gauge | in-flight Run 计数无 metric |
| Prometheus | 0 文件 | Prometheus 抓不到 scheduler snapshot |
| 自定义 endpoint | `GET /agent-langchain/scheduler` + `GET /agent-langchain/tool-throttle` (`@RestController`，非 Actuator `@Endpoint`) | 多实例时需轮询每节点 |
| JMX | 0 文件 | 无 JMX MBean 暴露 |

本 wave 不实现 Micrometer 接入；列入未来扩展项。多实例观测当前依赖 §3.4 HTTP endpoint 轮询。

## 5. 工具限流 (3.1.4，交叉引用)

> 工具限流 per-node 注释由 cckimi slice 落地；本节仅交叉引用，不抢写。

- `LangchainToolConcurrencyThrottle` (`agentLangchainService/.../config/`) — 进程内公平 Semaphore，allowlist 固定 `executePython`。
- `ToolWeightedLimitService` (`agentToolsShared/.../router/`) — 进程内 lazy-init Semaphore，配置 `maxWeight` 默认 12。

两者都是 per-node；多实例时每节点独立计 permit。无 Redis / DB / ZK 协调。

## 6. 验收对照 (D25 plan §8.3 ccmax slice)

| 验收项 | 状态 | 落地 |
|--------|------|------|
| 容量账本调用方仅依赖可替换接口；内存为实现细节 | PASS | G1 闭合 `PythonSandboxTools:28` 接缝违规 (commit `9b131aef`)；5 个 consumer 全部仅依赖 interface；cast 检查 0 命中 |
| 并发公式与 per-node 限流已文档化；在途指标可观察或已列缺口 | PASS | 公式 §2.2 + §3.2；在途指标 §3.3 可观察 (`instanceId` + 10 字段)；Micrometer 缺口 §4 已列 |

## 7. 不在本 slice (其他 owner scope)

| Gap | Owner |
|-----|-------|
| 7.1.5 batch size 硬编码 | dpsk |
| 7.1.8 Dubbo version/tag | codex |
| 7.1.4 sandbox single-instance task_store | ccqwen (commit `cab8813a`) |
| 7.1.3 / 7.1.9 / 8.1.1 docs | 其他 owners |
| 4.1.6 调试落盘单实例 | 其他 owners |
| 3.1.4 per-node 工具限流注释 | cckimi |
