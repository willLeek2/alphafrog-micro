# Agent 长工具等待与可恢复调度：最短阅读路径与全量文件导读

日期：2026-07-17
适用代码：`alphafrog-micro` 的 `agentPlatformShared`、`agentToolsShared`、`agentLangchainService`
学习目标：解释一次 Python 沙箱长任务怎样让出 Agent 工作线程，怎样跨进程保存执行位置，怎样在终态到达后重新排队，以及怎样阻止旧执行者覆盖新状态。

## 1. 为什么需要上下文切换

假设一次 Agent 运行有三个待办节点：

1. 查询行情并生成数据集；
2. 调用 `executePython` 计算指标；
3. 根据计算结果生成解释。

第二个待办节点的沙箱任务可能持续一分钟。沙箱已经异步执行，Java 线程如果持续轮询一分钟，仍然占有以下资源：

- `LangchainRunConcurrencyScheduler` 的一个运行名额；
- 线程池中的一个工作线程；
- 当前待办节点的 LangChain4j 工具调用现场；
- 运行级 `AgentContext` 与数据集编号映射。

这时 CPU 使用率未必很高，系统吞吐量却可能被运行名额限制。Oracle 的 `ThreadPoolExecutor` 文档说明，有限线程数与有限工作队列共同决定饱和后的拒绝行为；有界队列能够限制资源消耗，但队列大小和线程数必须一起设计。Temporal 的持久执行资料也说明，等待下游服务期间可以释放计算资源，恢复所需状态应先持久化。AlphaFrog 没有引入 Temporal，而是在现有 PostgreSQL、Redis、Spring 调度器和沙箱协议上实现同类执行语义。

这一设计要同时回答四个问题：

- 当前工作线程在什么时刻可以安全返回？
- 新工作线程如何知道应当跳过哪些待办节点？
- 多个实例同时发现终态时，谁拥有恢复权？
- 进程在任意一步退出后，下一次扫描从哪里继续？

## 2. 先区分四类状态

阅读源码前，先把四类状态分开。它们服务于不同资源，不能合并成一个枚举。

| 状态类别 | 示例 | 回答的问题 |
|---|---|---|
| 运行状态 | `EXECUTING`、`WAITING_TOOL_JOB`、`RECEIVED` | 用户看到的运行阶段是什么 |
| 外部任务分发状态 | `PREPARING`、`ATTACHED`、`PENDING`；同步快速路径还会短暂写入 `TERMINAL` | 沙箱任务已经分发到哪一步 |
| 恢复交接状态 | `READY`、`LAUNCHING`、`CONSUMED` | 哪个执行者拥有恢复租约，结果是否已经消费 |
| 容量预留状态 | `PREPARING`、`TASK_ATTACHED`、`PENDING_TRANSFERRED`、`TERMINAL_CONFIRMED`、`RELEASED` | 沙箱容量当前由谁负责释放 |

运行状态面向产品流程，分发状态面向外部操作，恢复状态面向跨进程所有权，容量状态面向资源账本。异步终态由 `terminalStatus` 与 `finalizerStep` 表示，`ToolJobFinalizer` 不会把分发状态改为 `TERMINAL`。名称有少量相似，但身份字段和推进条件不同。

## 3. 一个贯穿全文的运行示例

设运行为 `run-42`，挂起待办节点为 `todo-2`，工具调用为 `call-7`，沙箱返回 `task-99`。

```text
旧工作线程
  1. 申请沙箱容量预留
  2. 写入 `PREPARING` 锚点
  3. 调用 `createTask(request)`，请求中携带 `operationId` 和 `requestFingerprint`
  4. 写入 `task-99` 和 `ATTACHED`
  5. 快速路径未取得终态
  6. 容量状态转为 `PENDING_TRANSFERRED`
  7. 原子写入 `PENDING` 锚点，并把运行改为 `WAITING_TOOL_JOB`
  8. 抛出 `ExternalToolJobPendingException`
  9. 执行管线保存 `completedTodos`、`datasetSnapshot`、`todo-2` 和 `checkpointVersion`
 10. `Runnable` 返回，调度器归还运行名额

后台协调
 11. Redis 到期索引提示 `run-42` 到期
 12. 后台协调器从 PostgreSQL 重新读取锚点
 13. 沙箱返回 `SUCCEEDED` 和 `task-99` 的结果
 14. 完成器依次完成 `ENVELOPE`、`RELEASE`、`USAGE`、`EVENT`、`CAS_STATUS`、`RESUME_READY`

新工作线程
 15. 恢复服务通过 `token + leaseVersion` 把 `READY` 改为 `LAUNCHING`
 16. 恢复数据集登记表，并进入同一个有界调度器
 17. 读取原计划，跳过已完成的待办节点，把 `task-99` 的终态结果注入 `todo-2`
 18. 先把“结果已消费”和新待办节点位置写回同一 `LAUNCHING` 锚点，再执行 `todo-3`
 19. 最终结果持久化成功后，按 `token + leaseVersion` 清理旧锚点
```

第 7 步和第 9 步共同构成安全释放条件：外部任务身份已经持久化，工作流继续位置也已经持久化。只完成其中一步时，当前工作线程不能把“等待中”当作普通成功返回。

## 4. 最短八文件阅读路径

如果时间有限，按照下面顺序阅读。前四个文件建立持久化事实，后四个文件解释工作线程怎样退出和重新进入。

| 顺序 | 文件 | 类或映射 | 阅读任务 |
|---|---|---|---|
| 1 | `agentPlatformShared/src/main/java/world/willfrog/agent/platform/dataanalysis/ToolJobAnchor.java` | `ToolJobAnchor` | 认识外部任务身份、检查点、终态和恢复租约 |
| 2 | `agentPlatformShared/src/main/resources/mapper/AgentRunMapper.xml` | `AgentRunMapper` SQL 映射 | 理解所有权条件怎样进入 `WHERE` 子句 |
| 3 | `agentLangchainService/src/main/java/world/willfrog/agentlangchain/orchestration/LangchainRunConcurrencyScheduler.java` | `LangchainRunConcurrencyScheduler` | 理解运行名额、有限队列和工作线程归还 |
| 4 | `agentToolsShared/src/main/java/world/willfrog/agent/tools/python/PythonSandboxTools.java` | `PythonSandboxTools` | 理解 `PREPARING → ATTACHED → PENDING` |
| 5 | `agentLangchainService/src/main/java/world/willfrog/agentlangchain/orchestration/LangchainLinearRunPipelineImpl.java` | `LangchainLinearRunPipelineImpl` | 理解工作流检查点为何必须早于工作线程返回 |
| 6 | `agentLangchainService/src/main/java/world/willfrog/agentlangchain/tooljob/ToolJobFinalizer.java` | `ToolJobFinalizer` | 理解终态处理为何拆成可重入步骤 |
| 7 | `agentLangchainService/src/main/java/world/willfrog/agentlangchain/tooljob/ToolJobResumeService.java` | `ToolJobResumeService` | 理解 `READY → LAUNCHING` 租约竞争和失败重试 |
| 8 | `agentLangchainService/src/main/java/world/willfrog/agentlangchain/orchestration/LangchainLinearWorkflowExecutor.java` | `LangchainLinearWorkflowExecutor` | 理解终态结果注入、待办节点跳过和消费确认 |

### 4.1 `ToolJobAnchor`：跨线程与跨进程的事实记录

`ToolJobAnchor` 不是普通缓存 DTO。它与 `AgentRun.toolJobAnchorJson` 对应，保存恢复所需的稳定事实。

建议按字段组阅读：

1. 外部操作身份：`operationId`、`requestFingerprint`、`taskId`、`toolCallId`、`attempt`；
2. 工作流位置：`todoId`、`sequence`、`completedTodosJson`、`toolCallsUsed`；
3. 数据恢复：`datasetSnapshotJson`、`datasetSnapshotDigest`、`datasetRefsJson`；
4. 容量恢复：`reservationJson`、`estimateJson`；
5. 终态事实：`terminalStatus`、`terminalResultPreview`、`terminalRawRef`、`terminalRetryable`；
6. 恢复所有权：`resumeState`、`resumeToken`、`resumeLeaseVersion`、`resumeClaimedAt`；
7. 可重入进度：`checkpointVersion`、`finalizerStep`、`usagePersisted`、`terminalEventEmitted`、`resultConsumed`。

`operationId` 绑定一次可幂等创建的沙箱操作；`toolCallId + attempt` 绑定 Agent 侧的一次逻辑调用；`taskId` 绑定沙箱已创建的后台任务。三个身份同时存在，才能阻止旧回调误改另一轮任务。

`checkpointVersion` 与 `resumeLeaseVersion` 也承担不同职责：前者保护工作流快照，后者保护恢复执行权。

### 4.2 `AgentRunMapper.xml`：把所有权验证放进原子更新

重点阅读以下 SQL：

- `claimPreparingToolJobAnchor`：只有运行状态符合且锚点为空时，首个分发者可以写入 `PREPARING`；
- `updateActiveToolJobAnchor`：通过 `operationId` 推进同一外部操作；
- `updateToolJobAnchorAndStatusByOperation`：在同一语句中推进锚点与运行状态；
- `updateToolJobCheckpoint`：通过任务身份和 `checkpointVersion` 合并恢复字段；
- `casUpdateAnchorResumeState`：通过运行状态、`resumeState`、`token`、`leaseVersion` 竞争恢复租约；
- `clearToolJobAnchorWithToken`：只允许当前租约清理当前锚点；
- `listActiveToolJobAnchors`、`listResumeReadyAnchors`：为启动恢复和定时补扫提供 PostgreSQL 查询入口。

应用层先读取再判断不能替代 SQL 条件。两个实例可能同时读取同一版本，只有 `UPDATE ... WHERE expected_state ...` 的受影响行数能够说明本次是否取得所有权。PostgreSQL 在 `Read Committed` 下会等待并发更新者，并对更新后的行重新计算 `WHERE` 条件；因此旧版本条件失效时，第二个更新返回零行，调用方必须重新读取。

### 4.3 `LangchainRunConcurrencyScheduler`：工作线程名额的创建与归还

建议顺序：`reserve()` → `submit()` → `submitRunning()` → `onRunFinished()` → `drainLocked()`。

- `reserve()` 在同步锁内决定直接运行、有限排队、弹性扩容或拒绝；
- `submit()` 让普通运行与恢复运行共用同一调度规则；
- `submitRunning()` 使用 `finally` 调用 `onRunFinished()`；
- `onRunFinished()` 递减运行计数并提升队首任务；
- `Reservation.activate()` 把名额归还责任从入口调用者交给 `Runnable` 包装层。

外部任务进入持久等待后，执行管线正常返回。这个返回会经过 `submitRunning()` 的 `finally`，所以工作线程名额可以立即交给另一次运行。这里没有终止沙箱；释放的是 Agent 侧执行资源。

### 4.4 `PythonSandboxTools`：短任务同步，长任务转为持久等待

建议顺序：`executePython()` → `executePythonInternal()` → `executeDataIntense()` → `completeSynchronously()` / `suspend()`。

`executeDataIntense()` 完成以下协议：

1. 从 `runId + toolCallId + attempt` 派生稳定 `operationId`；
2. 申请容量预留；
3. 生成规范化创建参数与 `requestFingerprint`；
4. 在 `createTask()` 前写入 `PREPARING` 锚点；
5. RPC 结果不确定时先按 `operationId` 查询，避免创建第二个沙箱任务；
6. 取得 `taskId` 后写入 `ATTACHED`；
7. 在短暂快速路径内查询终态；
8. 快速路径到期时调用 `suspend()`。

`suspend()` 先把容量预留转为 `PENDING_TRANSFERRED`，再通过 `transferToPending()` 原子写入 `PENDING` 和 `WAITING_TOOL_JOB`，最后抛出 `ExternalToolJobPendingException`。异常在这里是内部控制信号，不代表业务失败。

### 4.5 `LangchainLinearRunPipelineImpl`：旧工作线程退出前保存工作流位置

首次执行从 `launchAsync()` 进入调度器。`executeRun()` 取得 `LangchainLinearWorkflowResult` 后检查 `isSuspended()`：

1. `persistToolJobCheckpoint()` 从数据库重新读取最新锚点；
2. 捕获已完成待办节点、数据集快照、挂起待办节点和工具调用计数；
3. `ToolJobCheckpointWriter.captureAndSave()` 使用版本条件写入；
4. 写入成功后发布 `TOOL_CALL_SUSPENDED`；
5. 当前 `Runnable` 返回，调度器归还名额。

如果检查点写入失败，`recordCheckpointFailure()` 会请求 `ToolJobCheckpointFailureRecoveryService` 建立明确的失败处理者，或确认另一个并发写入者已经保存更高版本。这是预期处置路径；若补偿服务抛出异常并返回 `UNOWNED`，当前执行管线仍会返回，不能把此类结果表述为已经取得持久化失败处理权。

恢复入口是 `launchResumedAsync()` 与 `executeResumedRun()`。恢复任务仍进入普通有界调度器，重新读取运行，恢复 `AgentContext`、原计划、模型配置与工具目录。当前实现只支持线性流程恢复；发现 DAG 计划时返回 `resume_dag_not_supported`，不会猜测执行顺序。

### 4.6 `ToolJobFinalizer`：终态处理拆成六个可重入步骤

`handleTerminal()` 使用 `finalizerStep` 保存每一步已经完成的位置：

```text
ENVELOPE → RELEASE → USAGE → EVENT → CAS_STATUS → RESUME_READY
```

- `ENVELOPE`：保存规范化终态、结果摘要、`rawRef`、实际用量与 `retryable` 分类；
- `RELEASE`：使用终态证明释放沙箱容量，接受 `ALREADY_RELEASED`；
- `USAGE`：按稳定操作身份写入资源用量；
- `EVENT`：按稳定去重键发布一次终态事件；
- `CAS_STATUS`：把运行从 `WAITING_TOOL_JOB` 改为 `RECEIVED`；
- `RESUME_READY`：生成新令牌，递增 `leaseVersion`，写入 `READY`。

任一步执行失败，方法立即返回。只要该记录仍在定时或启动扫描的选取范围内，下一轮就会重新读取 `finalizerStep`，从未完成步骤继续。第 10 节会说明两个状态组合尚未被 PostgreSQL 启动扫描选中。`terminalRetryable` 缺失时，流程停在容量释放之前，避免使用不完整终态证明释放资源。

### 4.7 `ToolJobResumeService`：恢复租约、回滚与过期重取

建议顺序：`tryResume()` → `launchFromReady()` → `doLaunch()` → `markHandoffAccepted()` → `completeHandoff()`。

`launchFromReady()` 把 `READY` 改为 `LAUNCHING`，并递增 `resumeLeaseVersion`。SQL 同时检查旧状态、旧令牌、旧版本，所以多个实例中只有一个更新成功。

如果数据集映射恢复失败或启动器未接受任务，`rollbackToReady()` 再次递增版本后回到 `READY`。版本不恢复旧值，可以避免 ABA：旧执行者不能因为状态再次出现 `READY` 而重新获得合法身份。

`reenterLaunching()` 处理进程退出后的遗留租约。只有认领时间超过配置期限，并且本地启动器不再报告活跃，才会生成新令牌和新版本，供下一轮扫描重新竞争。

### 4.8 `LangchainLinearWorkflowExecutor`：跳过已完成待办节点，注入外部结果

`resumePlanned()` 复用原计划，进入共享的 `executePlanned()`。首次消费外部结果前，恢复分支执行以下检查：

- `completedTodos` 只能位于挂起待办节点之前；
- 挂起 `todoId` 必须存在于原计划；
- 已完成的待办节点不再调用 LLM 或工具；
- 到达原挂起待办节点时，把终态摘要与 `rawRef` 组装成工具输出；
- 成功输出重新注册数据集引用；
- 当前待办节点加入已完成列表后，先调用消费确认回调；
- 消费确认成功后，才继续后续待办节点。

消费确认分为两段：`prepareAcceptedHandoff()` 先把待办节点位置推进到下一节点或 `FINAL_TODO_ID`，`markHandoffAccepted()` 再把新完成前缀写入同一 `LAUNCHING` 锚点。如果在消费确认后重入，`resultConsumed=true`，`completedTodos` 已经包含原挂起节点，`todoId` 也已指向下一节点或 `FINAL_TODO_ID`，此时不再应用“完成前缀位于原挂起节点之前”的首次消费条件。当前只有恢复执行管线持久化最终结果后，`completeHandoff()` 才清理旧租约；顺序二次长等待尚未完成，见第 10 节。

## 5. 三条状态序列怎样配合

### 5.1 运行状态

```text
EXECUTING
  └─ 外部任务转为持久等待 → WAITING_TOOL_JOB
       ├─ 终态完成且允许自动恢复 → RECEIVED
       │    └─ 恢复工作线程获得调度名额，当前持久状态仍为 RECEIVED
       ├─ 用户暂停 → 当前代码改为 WAITING，但未同步改写锚点，见第 10 节
       ├─ 用户取消 → CANCELED
       └─ 检查点失败 → FAILURE_OWNED 后可进入 FAILED；UNOWNED 未形成持久处理者
```

`RECEIVED` 在这里表示可重新进入调度器。它不表示重新规划；`executeResumedRun()` 明确读取原计划并跳过规划器。

### 5.2 外部任务与完成器

```text
PREPARING → ATTACHED → PENDING
                            │
                            └─ 异步终态写入 terminalStatus
                                 └─ ENVELOPE → RELEASE → USAGE → EVENT
```

`PREPARING` 覆盖 `createTask()` 前后结果不确定窗口。服务重启后，`ToolJobStartupRecovery` 可以通过 `operationId` 查询已创建任务，或者重放相同规范化请求。没有这一步时，RPC 超时后无法判断应当释放容量还是继续等待。同步快速路径会短暂写入 `TERMINAL` 后清理活跃锚点；上图表示异步等待路径。

### 5.3 恢复交接

```text
READY --CAS(令牌, 版本)--> LAUNCHING
  ^                               │
  │                               ├─ 启动失败：新版本后回到 READY
  │                               ├─ 结果已接受：保存新待办节点位置
  │                               └─ 最终结果持久化：清理旧租约
  └──────── 租约过期且启动器不活跃 ┘
```

`token` 识别一次恢复轮次，`leaseVersion` 提供单调栅栏。两者同时进入 SQL 条件，能够阻止延迟工作线程清理后来生成的恢复上下文。

## 6. 全量 39 文件导读

下面的 39 个文件是本次长工具等待与恢复实现中直接增加详细注释的生产文件。阅读时可以分成六组。

### 6.1 持久化模型与数据库操作

| 序号 | 文件 | 类或接口 | 建议关注 |
|---:|---|---|---|
| 1 | `agentPlatformShared/src/main/java/world/willfrog/agent/platform/dataanalysis/ToolJobAnchor.java` | `ToolJobAnchor` | `fromJson()`、`toJson()` 与全部状态字段 |
| 2 | `agentPlatformShared/src/main/java/world/willfrog/agent/platform/dataanalysis/CompletedTodoRecord.java` | `CompletedTodoRecord` | 已完成待办节点的稳定序列化字段 |
| 3 | `agentPlatformShared/src/main/java/world/willfrog/agent/platform/dataanalysis/ExternalToolJobPendingException.java` | `ExternalToolJobPendingException` | `runId`、`toolCallId`、`attempt` 控制信号 |
| 4 | `agentPlatformShared/src/main/java/world/willfrog/agent/platform/dataanalysis/PythonSandboxDispatchStore.java` | `PythonSandboxDispatchStore` | `persistPreparing()`、`persistAttached()`、`transferToPending()`、`clearActive()` |
| 5 | `agentPlatformShared/src/main/java/world/willfrog/agent/platform/entity/AgentRun.java` | `AgentRun` | `toolJobAnchorJson` 与运行持久化字段 |
| 6 | `agentPlatformShared/src/main/java/world/willfrog/agent/platform/model/AgentRunStatus.java` | `AgentRunStatus` | `WAITING_TOOL_JOB` 的公开语义 |
| 7 | `agentPlatformShared/src/main/java/world/willfrog/agent/platform/mapper/AgentRunMapper.java` | `AgentRunMapper` | 锚点、状态、检查点与恢复租约方法签名 |
| 8 | `agentPlatformShared/src/main/resources/mapper/AgentRunMapper.xml` | MyBatis SQL | 所有带预期状态、身份、令牌和版本的条件更新 |

这一组先定义“可以跨进程相信什么”。如果只阅读 service，不读取 SQL 条件，很容易把普通更新误解成互斥所有权。

### 6.2 调度、工作流与控制信号

| 序号 | 文件 | 类 | 建议关注 |
|---:|---|---|---|
| 9 | `agentLangchainService/src/main/java/world/willfrog/agentlangchain/orchestration/LangchainRunConcurrencyScheduler.java` | `LangchainRunConcurrencyScheduler` | `reserve()`、`submit()`、`submitRunning()`、`onRunFinished()` |
| 10 | `agentLangchainService/src/main/java/world/willfrog/agentlangchain/orchestration/LangchainLinearRunPipelineImpl.java` | `LangchainLinearRunPipelineImpl` | `launchAsync()`、`launchResumedAsync()`、`persistToolJobCheckpoint()`、`persistResumedResult()` |
| 11 | `agentLangchainService/src/main/java/world/willfrog/agentlangchain/orchestration/LangchainLinearWorkflowExecutor.java` | `LangchainLinearWorkflowExecutor` | `resumePlanned()`、`prepareAcceptedHandoff()`、`resumeTerminalOutput()` |
| 12 | `agentLangchainService/src/main/java/world/willfrog/agentlangchain/orchestration/LangchainTodoNodeExecutor.java` | `LangchainTodoNodeExecutor` | `execute()`、`findPending()`，把等待信号转换成节点挂起结果 |
| 13 | `agentLangchainService/src/main/java/world/willfrog/agentlangchain/orchestration/LangchainTodoNodeResult.java` | `LangchainTodoNodeResult` | `suspended()` 与等待身份字段 |
| 14 | `agentLangchainService/src/main/java/world/willfrog/agentlangchain/orchestration/LangchainLinearWorkflowResult.java` | `LangchainLinearWorkflowResult` | `suspended`、挂起待办节点、已完成待办节点与工具计数 |
| 15 | `agentLangchainService/src/main/java/world/willfrog/agentlangchain/orchestration/LangchainTerminalToolErrorHandler.java` | `LangchainTerminalToolErrorHandler` | `handle()`、`isTerminalSignal()`，保留挂起控制信号 |
| 16 | `agentLangchainService/src/main/java/world/willfrog/agentlangchain/orchestration/LangchainRunExecutionGuard.java` | `LangchainRunExecutionGuard` | `shouldStop()`、`stopReason()`，恢复前后检查取消与暂停 |

这里要沿着异常传播方向阅读：`PythonSandboxTools.suspend()` 抛出等待信号，工具错误处理器保留该信号，待办节点执行器识别信号，工作流结果标记挂起，执行管线才能保存检查点并返回。

### 6.3 工具路由与沙箱分发

| 序号 | 文件 | 类 | 建议关注 |
|---:|---|---|---|
| 17 | `agentLangchainService/src/main/java/world/willfrog/agentlangchain/tools/ToolRouterToolExecutor.java` | `ToolRouterToolExecutor` | `execute()`、`executeWithContext()`、同步 Python 完成确认 |
| 18 | `agentToolsShared/src/main/java/world/willfrog/agent/tools/router/ToolRouter.java` | `ToolRouter` | `invokeWithMeta()`、`invokeExecutePython()`、`executeDirect()` |
| 19 | `agentToolsShared/src/main/java/world/willfrog/agent/tools/python/PythonSandboxTools.java` | `PythonSandboxTools` | `executeDataIntense()`、`completeSynchronously()`、`suspend()` |
| 20 | `agentLangchainService/src/main/java/world/willfrog/agentlangchain/tooljob/PythonSandboxDispatchStoreImpl.java` | `PythonSandboxDispatchStoreImpl` | `persistPreparing()`、`persistAttached()`、`transferToPending()`、`clearActive()` |

`ToolRouterToolExecutor` 负责 LangChain4j 调用上下文，`ToolRouter` 负责工具选择与实际调用，`PythonSandboxTools` 负责长任务协议，`PythonSandboxDispatchStoreImpl` 负责把工具模块请求转换为数据库条件更新。

### 6.4 检查点写入与失败处理

| 序号 | 文件 | 类或接口 | 建议关注 |
|---:|---|---|---|
| 21 | `agentLangchainService/src/main/java/world/willfrog/agentlangchain/tooljob/ToolJobCheckpointRequest.java` | `ToolJobCheckpointRequest` | 外部任务身份、预期版本、待办节点前缀、数据集快照 |
| 22 | `agentLangchainService/src/main/java/world/willfrog/agentlangchain/tooljob/ToolJobCheckpointWriter.java` | `ToolJobCheckpointWriter` | `captureAndSave()` 检查点写入接口 |
| 23 | `agentLangchainService/src/main/java/world/willfrog/agentlangchain/tooljob/ToolJobCheckpointService.java` | `ToolJobCheckpointService` | `captureAndSave()` 与身份、JSON 类型、摘要校验 |
| 24 | `agentLangchainService/src/main/java/world/willfrog/agentlangchain/tooljob/ToolJobCheckpointFailureRecoveryService.java` | `ToolJobCheckpointFailureRecoveryService` | `handleFailure()`、`retryPending()`、失败标记所有权 |
| 25 | `agentLangchainService/src/main/java/world/willfrog/agentlangchain/tooljob/ToolJobAnchorService.java` | `ToolJobAnchorService` | `claimPreparing()`、`checkpointUpdate()`、`casResumeState()`、`clearAnchorWithToken()` |

检查点失败应当拥有持久化状态。如果写入失败后只记录日志，旧工作线程返回时会丢失待办节点前缀。`ToolJobCheckpointFailureRecoveryService` 会尝试判断是否已有更高版本，或者冻结本次失败处理者，再由后续扫描推进。当该服务因数据库异常返回 `UNOWNED` 时，当前代码仍会让执行管线返回；第 10 节会把这个未完成的故障窗口单独列出。

### 6.5 查询终态、可重入完成与恢复启动

| 序号 | 文件 | 类或接口 | 建议关注 |
|---:|---|---|---|
| 26 | `agentLangchainService/src/main/java/world/willfrog/agentlangchain/tooljob/ToolJobRedisCache.java` | `ToolJobRedisCache` | `atomicWritePendingAndDue()`、`fetchDue()`；Redis 只提供热副本与到期索引 |
| 27 | `agentLangchainService/src/main/java/world/willfrog/agentlangchain/tooljob/ToolJobReconciler.java` | `ToolJobReconciler` | `reconcileFromDue()`、`rebuildFromAnchors()`、`processItem()` |
| 28 | `agentLangchainService/src/main/java/world/willfrog/agentlangchain/tooljob/ToolJobResultValidator.java` | `ToolJobResultValidator` | `validate()`，核对 `taskId`、预期状态与终态正文完整性 |
| 29 | `agentLangchainService/src/main/java/world/willfrog/agentlangchain/tooljob/ToolJobFinalizer.java` | `ToolJobFinalizer` | `handleTerminal()`、`handleNotFound()`、`releaseCapacity()` |
| 30 | `agentLangchainService/src/main/java/world/willfrog/agentlangchain/tooljob/ToolJobEventHookImpl.java` | `ToolJobEventHookImpl` | `emitTerminalEvent()` 的稳定去重键 |
| 31 | `agentLangchainService/src/main/java/world/willfrog/agentlangchain/tooljob/ToolJobUsageHookImpl.java` | `ToolJobUsageHookImpl` | `upsertUsage()` 的幂等用量写入 |
| 32 | `agentLangchainService/src/main/java/world/willfrog/agentlangchain/tooljob/ToolJobResumeContext.java` | `ToolJobResumeContext` | 恢复工作线程的 DTO，含令牌、版本、待办节点和终态结果 |
| 33 | `agentLangchainService/src/main/java/world/willfrog/agentlangchain/tooljob/ToolJobResumeLauncher.java` | `ToolJobResumeLauncher` | `launch()` 与 `isActive()` 协议 |
| 34 | `agentLangchainService/src/main/java/world/willfrog/agentlangchain/tooljob/ToolJobResumeLauncherImpl.java` | `ToolJobResumeLauncherImpl` | `launch()`、`isActive()` 与 `activeClaims` 去重 |
| 35 | `agentLangchainService/src/main/java/world/willfrog/agentlangchain/tooljob/ToolJobResumeService.java` | `ToolJobResumeService` | `tryResume()`、租约竞争、半交接与租约清理 |
| 36 | `agentLangchainService/src/main/java/world/willfrog/agentlangchain/tooljob/ToolJobStartupRecovery.java` | `ToolJobStartupRecovery` | `onReady()`、容量账本恢复、`PREPARING` 结果不确定处理 |

Redis 有序集合通过分值维护成员顺序，适合作为 `nextPollAt` 到期索引。这里仍以 PostgreSQL 锚点为事实来源：普通 `PENDING` 记录可以通过 `rebuildFromAnchors()` 重建，Redis 命中后也要重新读取数据库。第 10 节会说明两个尚未覆盖的启动扫描窗口。

### 6.6 用户控制、读取与事件

| 序号 | 文件 | 类 | 建议关注 |
|---:|---|---|---|
| 37 | `agentLangchainService/src/main/java/world/willfrog/agentlangchain/facade/LangchainRunControlService.java` | `LangchainRunControlService` | `cancelRun()`、`pauseRun()`、`resumeRun()` 对等待任务的处置 |
| 38 | `agentLangchainService/src/main/java/world/willfrog/agentlangchain/facade/LangchainRunReadService.java` | `LangchainRunReadService` | `getRun()`、`getStatus()`、结果与事件读取 |
| 39 | `agentPlatformShared/src/main/java/world/willfrog/agent/platform/service/AgentEventService.java` | `AgentEventService` | `isRunnable()`、`append()`、`appendOnce()` |

取消、暂停与自动恢复会并发发生，所以执行管线、后台协调器和恢复工作线程都要重新查询运行状态。事件只描述已经持久化的事实；例如 `TOOL_CALL_SUSPENDED` 在检查点写入成功后发布，`WORKFLOW_RESUMED` 使用令牌与版本构造去重身份。

## 7. PostgreSQL、Redis 与进程内状态的职责

| 存储位置 | 保存内容 | 丢失后的影响 |
|---|---|---|
| PostgreSQL | 运行状态、计划、完整锚点、检查点、恢复租约 | 无法自动恢复，应当停止推进 |
| Redis | 等待状态热副本、到期有序集合、事件实时分发辅助 | 大部分等待状态可以从 PostgreSQL 补建；当前仍有两个启动扫描窗口，见第 10 节 |
| JVM 内存 | 工作线程任务、`AgentContext`、数据集登记表、本地活跃认领 | 进程退出后重新构造 |
| 沙箱 | 实际 Python 任务与结果产物 | 通过 `operationId`、`taskId` 与请求指纹核对 |

“数据库先写入，Redis 后写入”在多处重复出现。原因是 Redis 删除或更新成功不能证明工作流已经持久化；数据库更新成功后，普通 `PENDING` 记录即使 Redis 操作失败，启动恢复仍能扫描 PostgreSQL。这项结论不覆盖第 10 节列出的两个扫描遗漏组合。

## 8. 常见故障窗口

| 故障发生点 | 持久化事实 | 恢复动作 |
|---|---|---|
| `createTask()` 前退出 | `PREPARING`、规范化请求、容量预留 | 启动恢复按 `operationId` 查询；明确未创建时再决定重放或释放 |
| `createTask()` 返回前网络中断 | `PREPARING`，服务端可能已有任务 | 先查询相同 `operationId` 与请求指纹，禁止立即创建第二个任务 |
| 已取得 `taskId`，写入 `ATTACHED` 前退出 | `PREPARING`，沙箱可按 `operationId` 查询 | 启动恢复找到任务后补写附着状态 |
| 运行已改为 `WAITING_TOOL_JOB`，完整检查点写入失败 | 外部任务锚点已存在，待办节点前缀未确认 | 尝试建立失败标记处理者，或确认更高 `checkpointVersion`；`UNOWNED` 仍是当前限制 |
| Redis 到期索引写入失败 | PostgreSQL 中有 `PENDING` 锚点 | `rebuildFromAnchors()` 补建到期索引 |
| 终态写入后、容量释放前退出 | `finalizerStep=ENVELOPE` | 下一次从 `RELEASE` 继续 |
| 容量已释放、步骤写回前退出 | 容量账本可能返回 `ALREADY_RELEASED` | 按相同终态证明再次释放，再写入 `RELEASE` |
| `READY` 被认领后、提交工作线程前退出 | `LAUNCHING`、令牌、版本、`claimedAt` | 超过期限且启动器不活跃后生成新租约 |
| 外部结果注入后、最终结果保存前退出 | `resultConsumed=true` 与已推进待办节点前缀 | 重入时从下一待办节点或最终回答继续 |
| 恢复结果已写入终态运行，清理租约前退出 | 运行已为 `COMPLETED/PARTIAL/FAILED`，锚点仍为 `LAUNCHING` | Redis 同时丢失时，当前 PostgreSQL 启动扫描不会选中，见第 10 节 |

## 9. 为什么不能只保存 `taskId`

只保存 `taskId` 无法回答以下问题：

- 这个任务属于哪次工具调用与哪次尝试；
- 创建 RPC 超时时，服务端是否已经创建任务；
- 应当把结果注入哪个待办节点；
- 哪些待办节点已经执行，哪些工具调用已经计数；
- 数据集短编号怎样恢复到真实数据；
- 容量预留是否已经释放；
- 哪个启动器可以清理恢复信息。

因此锚点同时保存操作身份、工作流检查点、容量凭证、终态事实和恢复租约。字段较多源于故障窗口较多；每一组字段都应当对应明确的不变量与条件更新。

## 10. 当前限制与继续改进方向

教程必须区分“代码已经保证的语义”和“仍需要修改的地方”。当前代码至少有以下限制：

1. 自动恢复只覆盖线性计划，而且顺序执行中的第二个长沙箱任务也无法再次挂起。`executeResumedRun()` 发现 DAG 计划时返回 `resume_dag_not_supported`。恢复执行时，持久状态仍为 `RECEIVED`，旧 `LAUNCHING` 锚点也仍存在；新任务的 `persistPreparing()` 却要求 `EXECUTING` 且锚点为空。因此多分支等待、分支汇合和顺序二次长等待都尚未完成。
2. `ToolJobFinalizer` 先通过一次原子更新写入 `finalizerStep=CAS_STATUS`，并把运行从 `WAITING_TOOL_JOB` 改为 `RECEIVED`，随后才写入 `resumeState=READY`。`listActiveToolJobAnchors()` 不查询 `RECEIVED`，`listResumeReadyAnchors()` 只查询 `READY/LAUNCHING`。如果进程恰好在两次写入之间退出，并且 Redis 到期记录同时丢失，当前 PostgreSQL 启动补扫不会选中这条记录。修复方向是扩大补扫条件，或者把运行状态与 `READY` 锚点合并到同一条条件更新。
3. 恢复执行的最终结果会先写入 `COMPLETED/PARTIAL/FAILED`，之后的完成回调才调用 `completeHandoff()` 清理 `LAUNCHING` 锚点。如果进程在两者之间退出，这条运行既不符合活跃锚点扫描状态，也不符合 `status=RECEIVED` 的恢复扫描条件。Redis 同时丢失时，该租约不会被 PostgreSQL 启动扫描选中。
4. 检查点写入失败后，`recordCheckpointFailure()` 会尝试取得失败处理权或确认更高版本；但补偿服务抛出异常时可以返回 `UNOWNED`，执行管线仍会返回并释放工作线程。这会留下“检查点未完整写入，失败处理权也未持久化”的窗口。
5. `PREPARING` 的解析只在应用启动恢复中完成。创建 RPC 结果不确定时，工具层保留 `PREPARING` 并向上返回普通失败；周期协调器遇到空 `taskId` 会直接返回。当进程长期不重启时，该记录没有在线解析器。
6. `LangchainRunControlService.pauseRun()` 会把 `WAITING_TOOL_JOB` 改为 `WAITING`，但不会同时修改锚点的 `autoResume` 或 `runDisposition`。后续完成器仍以 `WAITING_TOOL_JOB` 作为条件更新的预期状态，因此“工具等待期间暂停”尚未形成完整协议。修复时应让用户控制状态与锚点处置在同一个条件更新中保持一致。
7. `ToolJobReconciler` 的非终态分支调用 `updateAnchor()` 后，没有检查返回值便更新 Redis 到期时间。版本冲突时，下一轮 PostgreSQL 补扫通常可以纠正热副本，但这一轮 Redis 可能短暂保存旧版本。修复方向是只在数据库更新成功后写入 Redis，失败时删除本轮到期记录并重新读取。
8. `ToolJobRedisCache.atomicWritePendingAndDue()` 执行 Lua 后直接返回 `true`，没有检查脚本返回值。Redis 命令异常仍会抛出，但空返回或异常返回值没有独立分类。修复方向是验证返回值，并把失败交给 PostgreSQL 补扫指标。
9. `ToolJobResultValidator` 目前核对 `taskId`、预期状态与终态正文完整性，没有在这一层核对 `retryable` 和资源用量。后续 `ToolJobFinalizer` 会在 `retryable` 缺失时停止容量释放，并在构造用量时执行类型解析；更完整的协议可以把必填字段检查提前到结果验证器。

第 2、3 项都直接影响无 Redis 时的启动恢复，应该优先增加两类测试：模拟数据库已经进入 `CAS_STATUS`、Redis 无记录、服务重新启动，验证补扫能否继续生成 `READY`；模拟终态运行仍留有 `LAUNCHING` 锚点，验证启动恢复能否完成租约清理。第 4、5 项还需要数据库异常和长时间不重启的故障注入测试。

## 11. 面试表达

如果面试官询问长工具等待怎样释放工作线程，第一句先回答持久等待与重新调度，再展开幂等创建、检查点版本和恢复租约，最后说明项目限制。

### 11.1 30 秒开场

> 我们的 Python 沙箱已经异步执行，但 Agent 工作线程原先会持续轮询，长任务会长期占有线程池名额。我的处理方式是短任务保留同步快速路径，超时后把外部任务身份、待办节点位置、已完成前缀、数据集快照和容量凭证写入 PostgreSQL 锚点，再让当前执行管线返回。沙箱到达终态后，后台协调器完成可重入处理，通过令牌和单调租约版本竞争恢复权，新工作线程读取原计划、跳过已完成的待办节点，并把外部结果注入原挂起节点。

### 11.2 60 秒展开

> 安全性依赖三组条件。第一组是创建幂等：`operationId + requestFingerprint` 覆盖 RPC 结果不确定窗口，避免重复创建沙箱任务。第二组是检查点版本：`checkpointVersion` 与任务身份进入 PostgreSQL `WHERE` 条件，旧工作线程无法覆盖新快照。第三组是恢复租约：`resumeToken + resumeLeaseVersion` 控制 `READY → LAUNCHING`、结果消费和最终清理。终态处理拆成 `ENVELOPE、RELEASE、USAGE、EVENT、CAS_STATUS、RESUME_READY`，每一步持久化后才进入下一步；在启动扫描能够重新发现该运行的状态下，进程退出后可以从最后完成步骤继续。Redis 只用于到期索引和热副本，PostgreSQL 仍是恢复依据。

### 11.3 30 秒补充

> 当前实现有明确限制：只支持线性计划的自动恢复，顺序执行中的第二个长沙箱任务也无法再次挂起，DAG 多分支汇合还没有实现。我的早期考虑对“结果已经注入，但最终运行结果尚未持久化”这个窗口不够充分；经过补充学习与代码修订，交接被拆成结果接受和旧租约清理两段，只有新进度持久化成功后才清理旧锚点。下一步需先完成顺序二次长等待，再为 DAG 的多个挂起节点建立独立身份、汇合条件和分支级消费记录。

### 11.4 压力追问

应对路径：承认 → 展开二级知识点 → 落地项目事实。

**面试官：为什么不采用 `CompletableFuture` 保存旧线程？**

解析：

- 承认：`CompletableFuture` 能够减少同步阻塞，但进程退出后，堆内的异步任务对象和调用现场都会消失。
- 二级知识点：需要把执行位置、外部操作身份和所有权版本写入可恢复存储，才能跨进程继续。
- 项目事实：AlphaFrog 把原计划、待办节点前缀、数据集快照和恢复租约写入 PostgreSQL，新工作线程不依赖旧线程对象。

**面试官：为什么 Redis 不作为唯一事实来源？**

解析：

- 承认：Redis 有序集合很适合按照 `nextPollAt` 查询到期任务。
- 二级知识点：到期索引与业务状态可以分离，索引允许补建，业务状态必须具备持久条件更新。
- 项目事实：`ToolJobRedisCache` 提供到期索引，`ToolJobReconciler.rebuildFromAnchors()` 可以从 PostgreSQL 有效锚点重建；恢复认领也会重新读取 PostgreSQL。

**面试官：CAS 返回零行时为什么不能继续？**

解析：

- 承认：零行可能表示另一个执行者已经推进，也可能表示用户取消、任务身份变化或版本变化。
- 二级知识点：乐观条件更新失败后必须重新读取当前事实，不能依据旧快照猜测状态。
- 项目事实：检查点失败处理会区分“已有更高版本”和“需要冻结失败处理者”；恢复租约失败则当前启动器退出。

**面试官：怎样证明没有重复消费外部结果？**

解析：

- 承认：只依赖事件去重不足以证明工作流进度没有重复。
- 二级知识点：结果消费位置需要和恢复租约绑定，并在后续执行前持久化。
- 项目事实：`markHandoffAccepted()` 把 `resultConsumed=true`、新待办节点位置和已完成待办节点前缀写入同一 `LAUNCHING` 锚点；`completeHandoff()` 仍要求相同令牌与版本。

## 12. 自查清单

完成源码阅读后，应当能够独立回答：

- 为什么 `PREPARING` 必须早于 `createTask()`；
- 为什么运行状态、锚点状态和恢复状态不能共用一个枚举；
- 为什么检查点成功后才能发布挂起事件并让旧工作线程返回；
- 为什么完成器的容量释放早于运行重新入队；
- 为什么 `READY → LAUNCHING` 同时需要令牌和单调版本；
- 为什么结果接受与旧锚点清理分成两段；
- 为什么普通 `PENDING` 记录可以从 PostgreSQL 补建，以及当前哪两个启动扫描窗口仍会遗漏；
- 为什么当前线性流程恢复不能直接推广到 DAG。

## 13. 参考资料

以下资料均在 2026-07-17 通过官方英文页面核验，并使用 `agent-working-docs/interview/fetch_jina.py` 保存了本地抓取材料。

1. [Temporal Documentation](https://docs.temporal.io/)：持久执行、故障后从已保存位置继续的概念说明。
2. [Temporal, Building Reliable Applications with Durable Execution](https://assets.temporal.io/durable-execution.pdf)：等待下游服务期间释放资源、持久化执行步骤的说明。
3. [Oracle Java 17, ThreadPoolExecutor](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/ThreadPoolExecutor.html)：线程数、工作队列、有界队列和拒绝策略。
4. [PostgreSQL, Transaction Isolation](https://www.postgresql.org/docs/current/transaction-iso.html)：`Read Committed` 下并发更新等待与 `WHERE` 条件重新计算。
5. [Redis, Sorted Sets](https://redis.io/docs/latest/develop/data-types/sorted-sets/)：按照 score 维护有序成员，用作到期索引的数据结构依据。

## 14. 和 AlphaFrog 项目的连接方式

- AlphaFrog 没有保存 Java 调用栈；它保存的是可重新构造的业务执行位置。
- `ToolJobAnchor` 放在 `AgentRun` 的 PostgreSQL JSONB 字段中，Redis 只保存热副本和到期索引。
- 普通运行与恢复运行共用 `LangchainRunConcurrencyScheduler`，恢复任务不会绕过容量限制和排队顺序。
- 沙箱任务继续占有沙箱容量，Agent 工作线程则在检查点持久化后归还；两类资源分别记账。
- 当前自动恢复仅覆盖线性工作流的单次长等待。顺序二次长等待、DAG、多等待任务和分支汇合需要新增状态表示与消费协议，不能直接复用单挂起点假设。
