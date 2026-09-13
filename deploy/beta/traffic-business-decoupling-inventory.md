# 流量分发与业务逻辑解耦：现状清单（三列盘点）

> 2026-09-13，dpsk-alen-mbp 整理。范围＝主 Beta 会拉起的业务服务，逐处核对到文件和类。
> 行号参照 `refactor/agent-codebase-refine` 分支 commit `a23a2a5e`（与本文件同批合入）。
> 目的：为「业务逻辑不感知自己在哪个环境启动」这一改进提供完整底账（对应需求 02-beta-代码改动需求-0913-02 的改动三第 1 步）。
> 口径：**列 A＝流量分发**（选实例、贴 dubbo 标签、读部署身份/泳道环境变量）；**列 B＝数据所有权**（哪一代进程允许动哪一行，即部署身份进入 SQL 条件的栅栏）；**列 C＝纯业务**（恢复、检查点、工具、账户等只管处理给定任务的代码）。C 本身不是问题，问题是 C 里出现在 A 或 B 的位置。

## 0. 结论摘要

- 真正把环境感知写进业务代码的只有两处，并且都在 codebase 的两个"重"服务里：
  - **agentLangchainService**：列 B 的栅栏调用点 42 处（39 处 `ForDeployment` 变体＋3 处 `ForDeploymentGeneration`）、分布在 15 个类；注入部署身份 Provider 的类 16 个（构造器 9＋字段 7）。列 A 的泳道上下文重建（`RunLaneContextScope`、DAG 工作线程）也在这里。
  - **frontend**：列 A 的入口泳道过滤器（这是流量分发**应该**待的位置，属于要保留的既有先例，不是要拆的对象）。
- 其他业务服务（admin、portfolio、fetch、sandbox-gw、externalInfo、domestic 各服务）**已隔离**：不读泳道/部署身份、不写部署身份 SQL 条件；Dubbo 标签路由完全靠 `common` 里全局注册的两个 SPI 过滤器，业务代码零参与。唯一例外是 logback 配置里把 `AF_DEPLOYMENT_ID` 用作日志标识（观测用途，见 §8）。
- `common` 是分发机制自身的基础设施（泳道上下文＋两个 SPI 过滤器＋部署身份类型），**保留**；`betaDeploymentController` 是身份/标签的**注入源**（写容器环境变量与 provider 参数），需求明确不在本次改动范围。

## 1. agentLangchainService（列 B 主战场，列 A 少量）

### 列 A：流量分发（泳道/身份的读取与上下文搬运）
| 文件:行 | 类/方法 | 说明 |
|---|---|---|
| `facade/AgentLangchainRunService.java:48,65-82` | `createRun()` | 受理入口：同时取 `DeploymentIdentity` 与 `LaneContext.trafficScopeId()` 写入 Run 行——A+B+C 三列交叉点（对应合同"Run 由受理实例写入可信身份"） |
| `control/RunLaneContextScope.java:20-35` | `wrap()` | 从 Run 的 `lane_tag` 重建线程上下文 |
| `control/LangchainRunConcurrencyScheduler.java:236` | `submit()` 前 wrap | 把泳道上下文带进线程池工作线程 |
| `execution/dag/LangchainDagWorkflowExecutor.java:315,450-462,609,1401-1420` | DAG 工作线程 | 泳道上下文快照/恢复 |
| `deployment/EnvironmentDeploymentIdentityProvider.java:10-33` | `current()` | 从环境变量 `AF_DEPLOYMENT_ID` / `AF_DEPLOYMENT_GENERATION_ID` 读身份（Provider 实现本身；分发机制的取数口，保留） |
| `deployment/NacosDeploymentGenerationLivenessProbe.java:24-25,62-88` | 代际存活探针 | 从 Nacos 实例 metadata 读代际 id（与路由同源的元数据读取） |
| `facade/AgentLangchainRunMessageMapper.java:26-28` | proto 回填 | 把 deploymentId/generationId/laneTag 回传响应 |

### 列 B：数据所有权（部署身份进入 SQL 条件的调用点，共 42 处 / 15 个类；其中 `ForDeploymentGeneration` 变体 3 处属代际退役清扫，天然归 deployment 包）
| 文件 | 类 | 处数 | 业务内容（列 C） |
|---|---|---|---|
| `control/WorkflowStartupRecovery.java:42,57-58,76,106-112,133,142-150` | 启动恢复 | 7 | 重启恢复扫描/认领/失败收口 |
| `execution/LangchainLinearRunPipelineImpl.java:123,929-1017` | 线性执行管线 | 6 | `belongsToLocalDeployment` / `findLocalRun` / 各 `update*ForLocal`——恢复与执行的主写入通道 |
| `facade/LangchainRunControlService.java:76,108,182-195,263-332,414-416` | 取消/暂停/恢复 | 7 | `requireLocalRun`、取消/暂停/恢复快照写入（含 `updateSnapshotForDeploymentIfStatus`） |
| `facade/LangchainFollowUpService.java:34,58-118` | follow-up 受理 | 4 | 追问受理与状态推进 |
| `tooljob/ToolJobAnchorService.java:27,48-49,370-371,387-416` | 长工具锚点 | 4 | 锚点读取与 `casUpdateStatus(..., identity)` |
| `execution/WorkflowCheckpointService.java:47,259-278` | 检查点服务 | 2 | 检查点读写（本次第一部分刚改过的类） |
| `execution/LangchainToolJobCheckpointCoordinator.java:38,64-123` | 长工具检查点协同 | 2 | 挂起点持久化与失败回退 |
| `tooljob/ToolJobFinalizer.java:74,574-579,630-637` | 长工具收尾器 | 2 | `belongsToLocalDeployment` 与终态收口 |
| `facade/AgentLangchainRunService.java:120` | 入队失败标记 | 1 | 创建后的失败写入 |
| `tooljob/ToolJobContinuationTracker.java:50,262-263` | 续跑跟踪 | 1 | |
| `tooljob/ToolJobCheckpointService.java:37,62-63` | 工具检查点 | 1 | |
| `tooljob/ToolJobCheckpointFailureRecoveryService.java:37,166-167` | 检查点失败恢复 | 1 | |
| `tooljob/ToolJobResumeLauncherImpl.java:38,58-59` | 恢复启动器 | 1 | |
| `deployment/AgentServiceShutdownState.java:24-102` | 关闭排空 | +2（Generation 级） | `count/failNonTerminalRunsForDeploymentGeneration`（代际退役清扫，属"数据所有权"的合法归处：退役逻辑本身就在 deployment 包） |
| `deployment/DeploymentGenerationReaper.java:36-134` | 代际清扫 | +1（Generation 级） | 清扫无存活实例代际 |

注入 `DeploymentIdentityProvider` 的 16 个类（构造器 9：WorkflowStartupRecovery、DeploymentGenerationReaper、DeploymentIdentityStartupVerifier、AgentServiceShutdownState、LangchainToolJobCheckpointCoordinator、AgentLangchainRunService、LangchainFollowUpService、LangchainRunControlService、ToolJobAnchorService；字段 `@Autowired(required=false)` 7：WorkflowCheckpointService、LangchainLinearRunPipelineImpl、ToolJobFinalizer、ToolJobCheckpointFailureRecoveryService、ToolJobResumeLauncherImpl、ToolJobCheckpointService、ToolJobContinuationTracker）。

其中属于「agent 恢复样板路径」、需求点名要摘掉注入的：`WorkflowCheckpointService`、线性执行器（`LangchainLinearRunPipelineImpl`）、`ToolJobResumeLauncherImpl`（长工具恢复链路）、以及 DAG 执行器的泳道搬运。

### 列 C：纯业务（本身不动，只是当前落在 A/B 路径上）
恢复与检查点：`WorkflowStartupRecovery`、`WorkflowCheckpointService`、`LangchainLinearRunPipelineImpl`、`LangchainToolJobCheckpointCoordinator`、`ToolJobResumeLauncherImpl`、`ToolJobCheckpointService`、`ToolJobCheckpointFailureRecoveryService`、`ToolJobContinuationTracker`；长工具：`ToolJobFinalizer`、`ToolJobAnchorService`、`ToolJobResumeService`；控制：`LangchainRunControlService`；账户/额度不在本模块。

## 2. frontend（列 A：入口泳道，机制正确，保留）

| 文件:行 | 类 | 说明 |
|---|---|---|
| `filter/LaneWebFilter.java:31-34,62-89,99-138` | 入口过滤器 | 解析并剥离 `X-AlphaFrog-Traffic-Scope-Id/-Deployment-Id/-Deployment-Generation-Id/-Lane-Tag` 四个头；部署注入值优先、请求头兜底；`main-beta`/非法值不打包 |
| `lane/LaneEntryProperties.java:10-30` | 配置 | `alphafrog.lane.entry.{enabled,traffic-scope-id}` |
| `config/LaneEntryConfiguration.java:17-30` | 装配 | 注册过滤器、禁用容器重复注册 |
| `config/SecurityConfig.java:30,66-67,111-112` | 安全链 | LaneWebFilter 挂在 JwtAuthFilter 之后 |
| `controller/agent/AgentController.java:243-257` | 创建 Run | HTTP→Dubbo 调用不显式传泳道，泳道隐式依赖 Dubbo 附件（这是设计内行为：标签由出站过滤器贴） |

备注：frontend 是"办入口的事"的既有先例（需求点名参照），列 A 内容集中且正交，**不是要拆的对象**；它没有列 B 内容。

## 3. agentPlatformShared（列 B 的 SQL 声明端 + 一处 A/B/C 交叉点）

| 文件:行 | 内容 | 列 |
|---|---|---|
| `mapper/AgentRunMapper.java` | 19 个 `ForDeployment` 方法声明＋6 个 `@Deprecated` 兼容默认方法（写死 `stable/legacy-stable`）＋3 个 `ForDeploymentGeneration` ＋ `casUpdateStatus(..., DeploymentIdentity)`。第 20/27/54/67/79/91/108/123/133/174/194/203/226/247/262/269/788/797/812/827/1007 行附近 | B |
| `resources/mapper/AgentRunMapper.xml:8-9,46-62,104-105,158-159,332-333,353-354,372-373,391,476-500,1332+` | `deployment_id / deployment_generation_id / lane_tag` 列与 WHERE 栅栏、可选栅栏片段 `optionalDeploymentIdentityFence` | B |
| `service/AgentRunEventService.java:114-131,145-147,217-220,246,250-262` | 创建 Run 写部署身份＋`normalizeLaneTag`（main-beta→null）；`:246` 是本模块唯一真实 ForDeployment 调用 | A+B+C |
| `entity/AgentRun.java:19-23`、`entity/DeploymentGenerationRecord.java` | 实体字段（保留；退役清扫还要用） | B |
| `migrate/migrations/upgrades/v1.5/001_agent_run_deployment_identity.sql`、`002_agent_run_lane_tag.sql`、`003_agent_run_lane_tag_constraint_cleanup.sql`、`migrate/migrations/init/004_agent.sql` | 表结构 | B |

## 4. common（分发机制基础设施，保留）

| 文件:行 | 内容 | 列 |
|---|---|---|
| `lane/LaneContext.java:15-21,33-45,47-61` | `alphafrog.traffic-scope-id` / `dubbo.tag` / MDC 常量、`trafficScopeId → 官方 dubbo.tag` 映射、TransmittableThreadLocal 载体 | A（机制） |
| `lane/LaneConsumerHopFilter.java:20-53` | 消费方出站过滤器：写/清 `dubbo.tag` 附件＋MDC | A（机制） |
| `lane/LaneProviderEntryFilter.java:17-44` | 提供方入站过滤器：从官方 `dubbo.tag` 恢复 scope、无标清残留 | A（机制） |
| `resources/META-INF/dubbo/...ClusterFilter`、`...Filter` | 两个 SPI 的全局注册（所有服务自动生效） | A（机制） |
| `deployment/DeploymentIdentity.java`、`DeploymentIdentityProvider.java`、`DeploymentIdentityMismatchException.java`、`DeploymentGenerationId.java` | 部署身份类型与 `current()` 接口 | B（类型面；解耦后仍由 gateway 子包/部署侧使用） |
| `config/nacos/NacosConfigBridge.java:42-45,73,180-220` | 泳道配置链 `"{scopeId}.{dataId}" → "{dataId}"`（`AF_LANE_TRAFFIC_SCOPE_ID`） | A 旁支（配置隔离；分类待确认，见 §7） |

## 5. 已隔离的服务（只有 Dubbo 自注册，业务代码不读环境，不必造 gateway 空包）

- **pythonSandboxGatewayService**：纯协议网关（Dubbo→沙箱 HTTP）。除 RpcContext 调试附件（`PythonSandboxGatewayServiceImpl.java:1298-1306`，与泳道无关）外零 A/B 内容。保持现状。
- **adminService、portfolioService、domesticFetchService、externalInfoService、domesticStock/Fund/Index/ListedAssetService**：无 Pool lane/deployment/dubbo.tag 代码；Dubbo 配置未设 tag，路由完全依赖 `common` 两个 SPI 过滤器。
- **Python 沙箱进程**（`pythonSandboxService/app/nacos_config.py:410-425,541-543,572-573`）：有与 Java 侧镜像的泳道配置链。需求明示沙箱不再加环境感知，仅在清单登记，不动。
- **agentToolsShared**：`tools/python/PythonSandboxTools.java:1359-1379` 写 RpcContext（SESSION_ID/RUN_ID/SESSION_DIR）是调试观测，与泳道无关；不列为耦合。

## 6. betaDeploymentController（注入源，本次不改）

- `infra/DockerComposeContainerRuntime.java:307-343,448-507`：向容器写 `AF_DEPLOYMENT_ID / AF_DEPLOYMENT_GENERATION_ID / AF_LANE_TAG / AF_LANE_TRAFFIC_SCOPE_ID / AF_LANE_ENTRY_ENABLED`；provider parameters `dubbo.tag=trafficScopeId`（非 main-beta）、`zone=beta`。
- `core/BetaDeploymentService.java` 等：部署单与代际调度（trafficScopeId 占位、状态推进）。需求明确不改控制器滚动协议。

## 7. 待确认清单

1. Provider 为 null 时静默回落到"无栅栏重载"的 9 处（`WorkflowCheckpointService:259`、`LangchainLinearRunPipelineImpl:936`、`ToolJobAnchorService:48`、`ToolJobFinalizer:577`、`ToolJobContinuationTracker:262`、`ToolJobCheckpointService:62`、`ToolJobResumeLauncherImpl:58`、`ToolJobCheckpointFailureRecoveryService:166`、`LangchainToolJobCheckpointCoordinator:64`）：生产环境若注入失败会静默丢掉 B 保护——解耦方案要决定这类回落是删除还是显式失败。
2. 未加身份栅栏的只读路径是否算所有权缺口（`tooljob/ToolJobEventHookImpl.java:30`、`workspace/WorkspaceDumpService.java:62`、`facade/LangchainRunReadService.java:519,528,556`、`control/LangchainRunExecutionGuard.java:53`）——倾向不算（只读/无写入），但要对齐口径。
3. `NacosConfigBridge` 泳道配置链的归类：算流量分发（A）还是独立"配置隔离"能力。
4. frontend 是否有绕过 Dubbo 附件的直连入口（未穷尽）。
5. `deploy/` 下契约与 compose 文件中的环境变量清单（观测/配置面）未逐行穷举，只登记为配置面。

## 8. 观测标识（不算分发，也不算所有权，登记在案）

各服务 `logback-spring.xml:21` 用 `${AF_DEPLOYMENT_ID:-stable}` 作日志标识；`deploy/otel`、`docker-compose.yml` 有对应注入。这是观测用途，与业务行为无关，保留（解耦检查时以"业务包源码不引用"为准，不波及日志配置）。

## 9. 下一步（对应需求改动三第 2、3 步，待 cckimi 对齐后执行）

- **agent**（样板）：把上表列 A 的入站"恢复泳道/部署范围"与列 B 的"当前进程能处理哪些行"两类判定收进新增 `gateway` 子包；恢复/检查点/控制等业务包不再注入 `DeploymentIdentityProvider`、不再按"有没有部署身份"分叉 SQL，条件只用业务字段（runId、userId、status、租约 token、operationId）。
- **frontend**：现状已符合"分发在入口"，只需按结论补 `gateway` 子包的目录归置（或标注"已符合，不改造"）。
- 其余服务：清单记为"已隔离"，不硬造空包。

## 10. 改动后状态（2026-09-13 第二部分落地对照）

本节记录上表所列 A/B 内容在第二部分代码落地后的归处，供 review 对照；行号不再维护，以类与方法名为准。

### 10.1 列 A（流量分发）归处：`gateway` 子包

| 原位置 | 改动后 |
|---|---|
| `facade/AgentLangchainRunService.createRun()` 读身份＋泳道 | 受理入口仍在这里，但身份只通过 `RunOwnershipGateway.requireIdentity()` 取一次、泳道只通过 `LaneScopeGateway.currentLaneTag()` 取一次，随后写入 Run 行；此处是业务代码里唯一允许出现 `DeploymentIdentity` 值的位置（有源码合同测试守住） |
| `control/RunLaneContextScope`（已删除） | 并入 `gateway/LaneScopeGateway`：`currentLaneTag()` / `wrap(run, task)` / `capture()` + `Snapshot.restore()` |
| `control/LangchainRunConcurrencyScheduler` 提交前 wrap | 改调 `LaneScopeGateway.wrap(...)` |
| `execution/dag/LangchainDagWorkflowExecutor` 泳道快照/恢复 | 改用 `LaneScopeGateway.capture()` 与 `Snapshot.restore()`，类内不再出现 `LaneContext` |
| Dubbo 出站标签（`common` SPI 过滤器） | 不变（分发机制本身） |

### 10.2 列 B（数据所有权）归处：认领入口留在 gateway，认领之后的写只用业务字段

- **认领入口（仍然带部署代际栅栏，且必须由 gateway 传入本进程身份）**：`RunOwnershipGateway` 的
  `findByIdForDeployment` / `findByIdAndUserForDeployment` / `listStartupRecoveryCandidatesForDeployment` /
  `claimStartupRestartForDeployment` / `admitFollowUpForDeployment` / `listActiveToolJobAnchorsForDeployment` /
  `listResumeReadyAnchorsForDeployment` / `listStuckAtCasStatusAnchorsForDeployment` /
  `claimResumeLauncher` / `takeoverExpiredResumeLauncher`。
  其中两条恢复租约认领语句的栅栏改成**无条件**（原先挂在可选片段上，缺参数会静默丢掉栅栏）。
- **认领之后的业务写（不再带部署身份，条件只用 run id / user / 精确原状态 / operationId / 租约 token+version+owner）**：
  `updateStatus`、`updateStatusWithTtl`、`updatePlanJson`、`updateExecutionCheckpoint`、`updateTerminalSnapshot`、
  `updateSnapshotIfStatus`、`pauseSnapshotWithTtl`、`cancelTerminalSnapshotWithTtl`、`updateResumedTerminal`、
  `resetForResume`、`completeStartupCancellation`、`failStartupRecovery`，以及全部工具锚点 CAS 语句。
- **未分级来源的显式闸门**：Redis due 列表是跨代际共享、没有 SQL 栅栏的来源，
  `ToolJobReconciler.reconcileFromDue` 在进入业务前先 `ownershipGateway.owns(runId)` 判定归属；
  `ToolJobFinalizer` 入口保留同一道判定（双保险）。
- **代际退役 / 孤儿清扫**：留在 `deployment` 包（`AgentServiceShutdownState`、`DeploymentGenerationReaper`），
  继续使用 `ForDeploymentGeneration` 三类语句。
- **受理入口的身份写入**：`createRun` 仍然把身份写进行（一次性、不可变），之后执行链不再比对环境。

### 10.3 源码合同（测试守着，防止回流）

- `agentLangchainService` 新增 `TrafficDistributionIsolationContractTest`：`gateway/`、`deployment/` 之外的主源码
  不得出现 `DeploymentIdentityProvider`、`AF_DEPLOYMENT_ID`、`AF_DEPLOYMENT_GENERATION_ID`、`LaneContext`、`ForDeployment`；
  `DeploymentIdentity` 值只允许出现在受理入口 `facade/AgentLangchainRunService.java`。
- `agentPlatformShared` 的 `AgentRunMapperWorkflowRestartBindingTest` 改为：认领/发现类语句必须带部署代际条件，
  业务写语句必须带精确原状态且不得出现部署代际条件。
- 归属判定行为由 `RunOwnershipGatewayTest` 覆盖：本代际放行、他代际拒绝、身份缺失 fail-closed、扫描与认领携带本进程身份。

### 10.4 §7 待确认清单的处置结果

1. Provider 为 null 时静默回落的 9 处：**随之消失**——业务类不再注入 Provider；剩下唯一的身份读取口是 gateway，
   缺失即抛错（fail-closed），不再存在"无栅栏重载"。
2. 只读路径：按对齐结论**不算所有权缺口**，不改造。
3. `NacosConfigBridge` 泳道配置链：留在 `common`（cckimi 对齐结论）。
4. frontend：**已符合，不改造**（入口泳道是分发该在的位置）。
5. `deploy/` 契约与 compose 的环境变量清单：不在本次改动范围。
