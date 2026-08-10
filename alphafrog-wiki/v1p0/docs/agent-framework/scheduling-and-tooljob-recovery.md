# Agent 调度与长任务恢复

本文面向需要接入、排障或维护 Agent Run 的开发者。它解释当前系统为什么分成六层、一次运行怎样经过这些层，以及 `executePython` 这类长任务如何在进程重启后继续收口。

本文只描述已经进入代码的能力，不把计划中的功能写成现状。内容最后按本地集成提交 `b77e1126ba514f4dcfac928ae206e0e17f599aa2` 核对。修改本文列出的核心类或状态字段时，应在同一个变更中更新本文和相关 API 页面。

如果只想调用接口，先看[认证与运行生命周期](../agent-run/auth-and-lifecycle.md)。如果正在处理断线重连或排障，再配合阅读 [SSE 事件流](../agent-run/sse-stream.md)和[查询与观测接口](../agent-run/reads-and-observability.md)。

## 一、先理解六层分别解决什么问题

调度不是一个线程池，也不是一张状态表。六层分别拥有不同的资源和失败边界，通过 PostgreSQL 中的 Run、计划、事件和长任务锚点衔接。

| 层 | 解决的问题 | 主要实现 |
|---|---|---|
| 1. Run 准入与执行槽位 | 当前允许多少个 Run 真正执行，哪些 Run 在业务队列等待 | `AgentLangchainRunService`、`LangchainRunConcurrencyScheduler`、`LangchainRunExecutorLimitsResolver` |
| 2. 规划与工作流路由 | 把用户目标变成一份计划，并冻结为 LINEAR 或 DAG | `LangchainLinearRunPipelineImpl`、`LangchainAiPlanner`、`LangchainWorkflowRouting` |
| 3. Todo 与工具循环 | 执行一个 Todo，调用模型和工具，累计预算并产生节点事件 | `LangchainTodoNodeExecutor`、`LangchainLinearWorkflowExecutor`、`LangchainDagWorkflowExecutor`、`ToolRouter` |
| 4. ToolJob 挂起、收尾与恢复 | 长工具未结束时保存恢复上下文；工具结束后可靠释放资源并让原 Run 继续 | `ToolJobAnchor`、`ToolJobReconciler`、`ToolJobFinalizer`、`ToolJobResumeService` |
| 5. 数据分析容量账本 | 在创建 Sandbox 任务前按资源类别做准入，并保证容量最终只释放一次 | `DataAnalysisCapacityServiceImpl`、`DataAnalysisReservationState` |
| 6. Python Sandbox | 排队、创建隔离执行环境、运行代码、保存结果并响应查询与取消 | Sandbox Gateway、`pythonSandboxApi` 和 Python Sandbox 服务 |

这六层不能合成一个“大调度器”。Run 槽位限制的是 Agent 工作流并发，数据分析容量限制的是 Sandbox 资源，Sandbox 自己还有执行队列。三者统计的是不同对象，排障时不能拿一个数字代替另一个数字。

## 二、一次普通 Run 怎样流转

普通 Run 的主线如下：

```text
创建 Run
  → 申请 Run reservation（为一个 Run 预留的并发执行名额）
  → 取得执行槽位，或进入业务 FIFO 队列
  → PLANNING
  → 生成计划
  → 把 requested mode 与 planner 输出冻结为 effective plan
  → 先持久化计划，再发送 PLAN_READY
  → EXECUTING
  → LINEAR 或 DAG 执行器运行 Todo
  → COMPLETED / PARTIAL / FAILED / CANCELED
  → 释放 Run 槽位
  → 提升业务队列中的下一项
```

业务排队只由 `LangchainRunConcurrencyScheduler` 管理。物理线程池不应再藏一份独立 backlog，否则系统会同时出现“业务队列显示未排队、线程池实际还没执行”的两套真相。

计划也只有一份真相：

1. 用户请求中的 `executionMode` 先解析成 `LINEAR`、`DAG` 或 `AUTO`；非法值直接拒绝，缺省为 `AUTO`。
2. Planner 生成 Todo 计划。
3. `LangchainWorkflowRouting.effectivePlan(...)` 一次性得到生效计划。
4. 同一份生效计划写入 PostgreSQL、放入 `PLAN_READY` 事件，并交给执行器。

生产执行器不再提供“收到请求后自己重新 planning”的旁路。`LangchainLinearWorkflowExecutor` 只接受已经冻结的计划并调用 `executePlanned(...)`，因此数据库、SSE 和真正执行的拓扑不会各说各话。

## 三、LINEAR、DAG 与长工具的边界

### LINEAR

LINEAR 按稳定顺序执行 Todo。显式请求 LINEAR 时，即使 Planner 返回依赖信息，系统也会先做稳定拓扑排序、移除 DAG 专属字段，再把结果持久化为 LINEAR。

LINEAR 支持 `executePython` 在快路径到期后转为持久挂起：当前 worker 保存检查点、把 Run 改为 `WAITING_TOOL_JOB`，随后释放 Run 槽位。Sandbox 完成后，新的 worker 从原计划、已完成 Todo 前缀和原 Todo 继续执行，不会重新规划。

### DAG

DAG 可以并行执行依赖已经满足的节点，并记录 DAG 节点状态。显式请求 DAG 时保留依赖图；AUTO 则根据 Planner 模式和依赖关系选择 LINEAR 或 DAG。

当前 DAG 仍有一个必须诚实说明的限制：DAG 节点调用长 `executePython` 时，会在持有 durable ownership 的当前 worker 内阻塞轮询到终态。它不会把 Run 转成 `WAITING_TOOL_JOB`，因为系统尚不能持久化一个包含多个并行节点和多个外部任务的完整 frontier（待恢复的并行执行边界），并在重启后安全恢复它。

因此当前不是“DAG 不支持 Python”，而是：

- LINEAR：允许快路径后持久挂起并自动恢复；
- DAG：允许执行 Python，但长任务占用当前 DAG worker 阻塞等待；
- DAG worker 丢失：后台只做终态收尾、容量释放和 Run 失败，不猜测恢复并行拓扑。

如果将来要让 DAG 长任务也释放 worker，必须先实现一对多 ToolJob、每个节点独立的 fencing token，以及完整 frontier checkpoint（并行执行边界检查点）；不能把 LINEAR 恢复逻辑直接套到 DAG 上。

## 四、`executePython` 从创建到恢复

下面是 LINEAR 模式的持久挂起主路径：

```text
Todo 调用 executePython
  → 校验当前 workflow 与发布配置
  → 冻结 operationId、requestFingerprint、数据集快照和超时
  → 容量账本 PREPARING
  → PostgreSQL 保存 PREPARING ToolJob anchor
  → Gateway canonical createTask
  → 写回 taskId，容量账本 TASK_ATTACHED
  → 快路径轮询
       ├─ 已终态：同步收尾并返回工具结果
       └─ 仍运行：容量账本 PENDING_TRANSFERRED
                 单条数据库 CAS（比较旧值后才更新）写 anchor 并把 Run 改为 WAITING_TOOL_JOB
                 持久化 LINEAR checkpoint
                 当前 worker 退出并释放 Run 槽位
  → ToolJobReconciler（长工具对账器）轮询 Sandbox
  → ToolJobFinalizer（长工具终态收尾器）完成可重入收尾
  → Run 从 WAITING_TOOL_JOB 原子推进到 RECEIVED
  → anchor 进入 READY
  → ToolJobResumeService claim 为 LAUNCHING
  → 重新进入有界 Run scheduler
  → 工作流接受结果后进入 ACCEPTED
  → 原 Todo 继续执行，最终进入 CONSUMED 并清理 anchor
```

这里有两套相关但不同的状态：

| 状态组 | 状态 | 含义 |
|---|---|---|
| Run 状态 | `WAITING_TOOL_JOB` | 外部工具未完成，旧 Agent worker 必须退出 |
| 容量 reservation | `PREPARING → TASK_ATTACHED → PENDING_TRANSFERRED → TERMINAL_CONFIRMED → RELEASED` | Sandbox 容量从创建前占用到终态释放的生命周期 |
| 恢复交接 | `READY → LAUNCHING → ACCEPTED → CONSUMED` | 谁可以启动恢复 worker，以及结果是否已被工作流接受和最终消费 |

不能用其中一组状态替代另一组。例如 Run 已经是 `WAITING_TOOL_JOB`，并不代表容量已经释放；anchor 已经 `READY`，也不代表新的 worker 已经成功进入执行器。

## 五、ToolJob anchor 为什么是恢复真相源

`ToolJobAnchor` 保存在 `alphafrog_agent_run.tool_job_anchor_json`。它不只是一个 `taskId`，还冻结了：

- `operationId`、`requestFingerprint` 和创建请求，用来处理 create RPC 响应不确定；
- `toolCallId`、attempt、Todo、sequence 和 checkpointVersion，用来隔离旧尝试；
- 已完成 Todo、数据集注册表快照和工具预算，用来恢复原工作流上下文；
- 容量 reservation 与估算，用来精确释放资源；
- Sandbox 终态、结果摘要、完整结果引用和实际用量；
- finalizer 已完成到哪一步；
- 恢复 token、lease version、launcher owner 和数据库租约。

所有关键推进都使用数据库 compare-and-set（比较旧值后才更新，简称 CAS）。旧 worker、重复回调或另一台实例如果持有过期 token/version，只能得到更新行数为 0，不能覆盖新状态。

Redis 只保存 pending/due 加速索引和热副本。大多数仍处于等待、收尾或恢复交接的 anchor，
可以在 Redis 丢失后由启动恢复和周期补扫从 PostgreSQL 重建索引；因此“Redis 里没有 due 项”
不能单独证明任务已经丢失或完成。当前仍有一个下面会单列的例外：Run 已写成 `RECEIVED`、
`finalizerStep=CAS_STATUS`，但 `resumeState` 还为空时，现有 PostgreSQL 查询不会重新选中它。

## 六、终态为什么要分步收口

Sandbox 返回终态后，`ToolJobFinalizer` 不会一次性做完所有副作用，而是按固定顺序执行，每一步成功后先写 anchor：

1. `ENVELOPE`：冻结规范化终态、结果摘要、错误分类和资源用量；
2. `RELEASE`：凭 durable reservation 和终态证明释放容量；
3. `USAGE`：幂等保存实际资源用量；
4. `EVENT`：幂等发布唯一的工具终态事件；
5. `CAS_STATUS`：把 Run 从 `WAITING_TOOL_JOB` 原子推进到 `RECEIVED`；
6. `RESUME_READY`：生成新恢复 token 和 lease version，把 anchor 置为 `READY`。

大多数步骤之间发生进程退出时，下一次补扫会从第一个未完成步骤继续。当前有一个已知空窗：
`CAS_STATUS` 先把 Run 写成 `RECEIVED`，下一条独立数据库更新才写 `RESUME_READY`。如果进程
恰好在两次更新之间退出，而且 Redis due 索引也不可用，数据库会留下
`RECEIVED + finalizerStep=CAS_STATUS + resumeState 为空`；现有 PostgreSQL 补扫不会选中这条
记录，不能承诺它会自动恢复。容量释放出现“此前已经释放”会被当作幂等成功，但身份不一致、
用量钩子未装配或终态分类缺失会阻止后续步骤，不能为了让 Run 看起来结束而跳过账本与事件。

上面六步是“工具正常收尾后继续原 Run”的主线。实现中的步骤顺序还定义了第七个
`CANCELED` 标记，但它不是每次收尾都执行：当 Run 已经进入取消流程时，前四步仍先保存
终态、释放容量、保存用量并发布终态事件，随后由 `CANCELED` 步骤把 Run 最终确定为
`CANCELED`，清理 Redis pending/due，并明确禁止生成正常恢复机会。这个条件分支排在步骤
顺序的第七位，是为了让重入时能区分“取消已经完成”与“还需要继续收口”，不能把它误写成
正常恢复主线的第七个通用步骤。

取消和人工暂停不会照搬正常恢复：

- 取消先持久化取消意图，并把它传播到 Sandbox；普通取消完成终态收尾后把 Run 置为 `CANCELED`，不生成新的正常执行机会。
- 人工暂停可继续完成 envelope、容量和用量收尾，但保留等待状态；只有用户明确恢复后才继续。
- DAG blocking worker 丢失只允许 cleanup，不生成 LINEAR `READY`，也不重新执行 DAG。

### 周期补扫怎样兜住进程重启

`ToolJobReconciler` 有两个不同频率的循环，默认值都可以通过配置调整：

- 每 **5 秒**执行一次 due 扫描：从 Redis 取最多 20 个到期 Run，逐个从 PostgreSQL 重新读取
  最新 anchor，再查询 Sandbox 或继续 finalizer。Redis 在这里提供低延迟，不决定真实状态。
- 每 **60 秒**执行一次 PostgreSQL 补扫：重建丢失的 Redis pending/due 索引，并单独扫描
  `RECEIVED + READY`、租约过期的 `RECEIVED + LAUNCHING`，以及结果已被工作流接受、结果消费
  标记已经持久化、但恢复 worker 的租约已过期的 `EXECUTING + ACCEPTED`。最后一种情况用于
  接管“工作流已接收长工具结果，原恢复 worker 随后退出”的交接。

这两个周期不能合成一句“每 5 秒恢复”。5 秒循环只处理已经进入 due 索引的任务；60 秒循环
负责从数据库找回符合其查询条件、但因为 Redis 丢失、过期或进程重启而不再出现在热索引中的
任务。两条路径最终都必须重新通过数据库 CAS 和恢复租约，扫描频率本身不会赋予旧 worker
所有权。上面列出的 `RECEIVED + CAS_STATUS + resumeState 为空` 当前不符合任一补扫条件，
值班人员不能只等待下一个 60 秒周期。

## 七、幂等、取消和重启的关键规则

### 创建任务

- `operationId` 是稳定的外部操作身份，`requestFingerprint` 把它绑定到这一次规范化请求。
- create 响应超时或连接中断时，先按 `operationId` 查询；不能直接再创建一次。
- lookup 的 `found=false` 只有在没有 transport、HTTP 或解析错误时才表示权威不存在。
- create/lookup 返回的任务身份或 fingerprint 缺失、漂移时，保留 PREPARING 隔离并停止推进，不能把无法证明身份的任务当作正常 PENDING。

### 恢复任务

- `READY` 只代表可竞争，不代表 worker 已启动。
- `LAUNCHING` 使用 token、单调递增的 leaseVersion、launcher owner 和数据库时间租约防止双启动。
- launcher 在租约到期前不能被另一个实例接管；本地内存中的 `isActive` 只是同进程优化，不是跨实例真相源。
- 数据集注册表恢复失败时回滚到可重试状态，不能带空映射继续。
- 工作流接收结果后进入 `ACCEPTED`；只有结果最终消费后才进入 `CONSUMED` 并清理数据库 anchor。

### 控制信号

执行循环同时读取 Redis 快速信号和 PostgreSQL 持久状态。`WAITING`、`WAITING_TOOL_JOB`、`CANCELING`、`CANCELED` 都会阻止旧 worker 继续推进；其中 `WAITING` 和 `WAITING_TOOL_JOB` 的恢复入口不同，不能互相替代。

## 八、客户端与排障入口

客户端不需要理解所有内部步骤，但要遵守以下边界：

- 创建、暂停、恢复和取消的 HTTP 合同见[认证与运行生命周期](../agent-run/auth-and-lifecycle.md)。
- `PLAN_READY`、Todo、工具挂起/完成、DAG 和终态事件见 [SSE 事件流](../agent-run/sse-stream.md)。
- 状态、结果、事件、timeline、trace 与管理端观测见[查询与观测接口](../agent-run/reads-and-observability.md)。
- 断线后先取 snapshot，再按 cursor 续接 SSE；不要只依赖内存中的最后一个事件。

排查长工具卡住时，建议按下面顺序看：

1. Run 当前状态，以及 PostgreSQL 的 `tool_job_anchor_json` 是否存在；
2. `operationId`、`taskId`、`toolCallId`、attempt 和 checkpointVersion 是否属于同一轮；
3. reservation 是 `PREPARING`、`TASK_ATTACHED`、`PENDING_TRANSFERRED`、`TERMINAL_CONFIRMED` 还是 `RELEASED`；
4. Sandbox 是否已经终态，但 `finalizerStep` 仍停在 `ENVELOPE`、`RELEASE`、`USAGE`、`EVENT`
   或 `CAS_STATUS`；若 Run 已是 `RECEIVED`、步骤是 `CAS_STATUS` 且 `resumeState` 为空，当前不能
   靠普通 PostgreSQL 补扫自动继续，需要按已知恢复空窗处理；
5. `resumeState`、token、leaseVersion、launcher owner 和 leaseUntil 是否停在 `READY`、
   `LAUNCHING` 或 `ACCEPTED`；对于 `ACCEPTED`，还要检查结果消费标记、Run 是否为
   `EXECUTING` 以及数据库租约是否已经过期，判断是否满足接管条件；
6. 是否已经收到 `TOOL_CALL_SUSPENDED`、工具终态事件和 `WORKFLOW_RESUMED`；
7. Redis due 索引是否丢失，以及 PostgreSQL 补扫是否正在重建；
8. Run 槽位、Java 数据分析容量和 Sandbox worker/queue 指标分别是否耗尽。

出现状态不一致时，以 PostgreSQL 持久记录和 CAS 结果为准；日志、Redis 热副本和单个进程的内存状态只用于解释“为什么还没推进”，不能反过来覆盖数据库事实。

## 九、Sandbox 执行边界与结果预算

Python Sandbox 在真正执行用户代码前还要通过一套只在 Linux 容器中成立的安全门。当前 wrapper
按固定顺序清空补充组、设置 `NO_NEW_PRIVS`、清空 ambient capability、切换到无特权 gid/uid，
再显式把 inheritable、permitted、effective capability set 写为空。完成后还会从
`/proc/self/status` 读回 `CapInh`、`CapPrm`、`CapEff`、`CapAmb` 和 `NoNewPrivs` 核对；任何
硬步骤失败都必须在启动用户脚本前关闭执行，不能继续用原权限运行。

输出也不是无限内存或无限文件。任务创建时会冻结四个相互独立的限制：

- `stdoutMaxBytes`：普通标准输出最大字节数；
- `stderrMaxBytes`：标准错误最大字节数；
- `recordChannelMaxBytes`：金融结构化记录文件与未知格式标记审计文件共同使用的字节预算；
- `recordChannelMaxRecords`：结构化记录条数上限。

结构化记录超过预算时，wrapper 会删除不完整的记录批，把 `recordSetComplete` 设为 `false`，
并用非空 `dropReason` 说明为什么整批放弃；它不会把被截断的部分冒充完整结果。Python 服务读取
捕获文件时会再次校验文件存在性、记录条数和联合字节数，Java finalizer 还会在发布用户结果前
验证该通道的完整性和冻结快照。普通 stdout/stderr 与结构化记录分别计费，不能用把记录塞回
stdout 的方式绕过记录预算。

这里也必须说明验证边界：macOS 宿主机没有 Linux capability 和 `/proc/self/status`，本地 fake
测试只能证明调用顺序和失败关闭逻辑，不能证明真实容器已经成功降权。发布前仍需在获准的
Linux 容器环境检查实际 uid/gid、四个 capability 寄存器和 `NoNewPrivs`。

## 十、Run 终态后的 workspace 归档

Run 进入 `COMPLETED`、`PARTIAL`、`FAILED`、`CANCELED` 或 `EXPIRED` 后，调度主线已经结束，
但用户下载制品和排障所需的 workspace 还要异步落盘：

```text
AgentRunFinalizationService 发布 AgentRunFinalizedEvent
  → WorkspaceFinalizedEventListener
  → WorkspaceDumpScheduler 异步提交
  → WorkspaceDumpService 读取 Run、收集资产并校验健康度
  → WorkspaceManifestWriter 写 conversation、scripts、manifest、meta 和 workspace_state
```

`EXPIRED` 使用保守模式，只写最小状态与有限元数据；其他终态走完整归档。写入前用 Run 与消息
生成 fingerprint，只有指纹一致且上次归档足够完整时才跳过，避免事件与补扫重复触发造成重复
写入。单次失败会在调度器中重试，仍失败的条目进入 workspace 根目录下的磁盘 DLQ（死信队列）；启动时
调度器会重放未完成条目，过期的 `.processing` claim 也会恢复为可重试条目。DLQ 有容量、隔离
和淘汰审计，活跃 `.processing` 不会被容量淘汰。

Spring 终态事件不是持久消息，所以系统还用 `WorkspacePollingObserver` 默认每 30 秒按
`(updated_at, run_id)` 复合游标扫描 PostgreSQL 终态 Run。这个补扫可以找回重启或事件窗口中
漏掉的归档，但文件系统仍是单机/单 writer 设计；多进程共享同一目录的竞争当前没有生产证据。

## 十一、与金融模块边界的关系

本文只说明调度如何消费金融记录结果，不重写 D22 的模块边界。当前边界是：金融工具实现、
方法目录和结果投影留在 `agentToolsShared`；`agentPlatformShared` 只保留确实需要跨模块共享的
金融配置、记录通道处理、持久化模型和小型扩展接口。平台共享模块不能反向依赖
`world.willfrog.agent.tools.finance`，也不能直接依赖 Sandbox 协议生成类，否则轻量平台消费者
会被迫带入整套金融工具和远程执行依赖。

`FinanceSharedResidenceAllowlist` 是平台共享模块中获准驻留金融类的封闭清单，
`FinanceSharedResidenceArchTest` 会扫描编译后的主类并要求扫描集合与清单完全相等：新金融类
没有先更新并评审清单会失败，清单保留已经删除的类也会失败。完整的包职责、允许驻留原因和
命名约定仍以 `agentToolsShared/PACKAGE_BOUNDARY.md` 与 D22 边界文档为准；D26 Wiki 只建立
调度侧入口，不复制第二份边界真相。

## 十二、当前没有验证或没有实现的能力

下面这些不能从宿主机单元测试推导为“生产可用”：

- 真实 PostgreSQL 下的跨实例并发 CAS 和故障恢复；
- 真实 Redis 丢失、过期和恢复扫描；
- `CAS_STATUS` 已写入、`RESUME_READY` 尚未写入且 Redis due 同时不可用时，PostgreSQL 补扫
  当前不会自动找回这条恢复交接；
- 真实容器中的 marker、进程组取消和资源用量回传；
- Java → Gateway → Python 的完整跨服务取消与恢复；
- 多进程共同使用文件型 artifact/workspace 存储时的竞争行为；
- macOS fake 无法替代 Linux 容器内真实 uid/gid、capability 与 `NoNewPrivs` 读回；
- DAG 多个外部长任务同时释放 worker 后的 frontier 恢复；该能力目前没有实现。

本地测试通过只能证明被覆盖的 Java/Python 合同。要宣称上述能力可用于生产，仍需在获准的非本机环境运行真实 PostgreSQL、Redis、Gateway 和 Sandbox 验证。

## 十三、代码事实来源

维护本文时至少同时核对下列代码：

- `agentLangchainService/.../LangchainRunConcurrencyScheduler.java`
- `agentLangchainService/.../LangchainLinearRunPipelineImpl.java`
- `agentLangchainService/.../LangchainWorkflowRouting.java`
- `agentLangchainService/.../LangchainWorkflowStepCoordinator.java`
- `agentLangchainService/.../LangchainRunExecutionGuard.java`
- `agentLangchainService/.../ToolJobReconciler.java`
- `agentLangchainService/.../ToolJobFinalizer.java`
- `agentLangchainService/.../ToolJobResumeService.java`
- `agentLangchainService/.../ToolJobStartupRecovery.java`
- `agentLangchainService/.../WorkspaceFinalizedEventListener.java`
- `agentLangchainService/.../WorkspaceDumpScheduler.java`
- `agentLangchainService/.../WorkspaceDumpService.java`
- `agentLangchainService/.../WorkspacePollingObserver.java`
- `agentPlatformShared/.../ToolJobAnchor.java`
- `agentPlatformShared/.../AgentRunDagNodeMapper.java`
- `agentPlatformShared/.../FinanceSharedResidenceAllowlist.java`
- `agentToolsShared/.../PythonSandboxTools.java`
- `agentToolsShared/.../PythonWaitPolicy.java`
- `agentToolsShared/.../DataAnalysisCapacityServiceImpl.java`
- `pythonSandboxService/app/bounded_exec_wrapper.py`
- `pythonSandboxService/app/capture_reader.py`
- `pythonSandboxService/app/finance_record_channel.py`

路径中的 `...` 表示 Java package 目录；类名是稳定检索入口。若这些类发生职责迁移，应同时更新本文中的层级、状态图和排障顺序。
