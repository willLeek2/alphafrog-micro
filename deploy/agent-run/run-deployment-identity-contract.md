# Agent Run 部署身份合同

## 1. 解决的问题

一次 Agent Run 从入口请求开始后，会进入异步调度器，也可能在服务重启后从数据库恢复。入口线程上的流量标签会在请求结束时清理，不能表示这个 Run 后续应该由哪一套服务实例继续执行。

本合同给 Run 保存两项不可变身份：`deployment_id` 表示稳定环境或某个测试部署，`deployment_generation_id` 表示该部署的一次不可变构建。创建、追问、手动恢复、启动恢复和长工具恢复都使用这两项判断归属。服务实例只能执行与自身身份完全相等的 Run。

部署控制器继续管理服务实例和默认路由。它不读取 Run、Todo、事务结果或业务恢复状态。Agent 服务从部署配置取得自身身份，并在自己的数据库操作中判断 Run 归属。

## 2. 两个持久化字段

`alphafrog_agent_run` 增加以下字段：

| 字段 | 格式 | 含义 |
| --- | --- | --- |
| `deployment_id` | `stable`，或 3 至 64 位小写字母、数字、连字符组成的测试部署标识；首尾必须为字母或数字 | Run 创建时所在的部署 |
| `deployment_generation_id` | `gen-` 加 64 位小写十六进制字符；历史数据可以是 `legacy-stable` | Run 创建时所在部署的不可变构建代际 |

数据库迁移把已有记录填成 `deployment_id=stable`、`deployment_generation_id=legacy-stable`，随后移除这两个字段的数据库默认值。`legacy-stable` 表示迁移前无法证明具体构建的历史记录。活动服务实例不能使用这个值启动，也不能领取或恢复这些历史 Run；新的插入若漏传任一身份字段，会直接违反非空约束，不能静默落成历史身份。

两个字段在插入 Run 时写入。数据库触发器拒绝后续修改其中任意一项，使一次归属检查之后的异步写入不会因为身份字段被并发改写而改变对象。

升级现有环境时，必须先停止旧 Agent 写入并执行 `python3 migrate/migrate.py migrate --auto --to current`，确认 `migrate/migrations/upgrades/v1.5/001_agent_run_deployment_identity.sql` 已成功应用，再启动包含本合同代码的 Agent 服务。迁移工具的 `current` 计划会扫描并执行尚未登记到版本清单的增量目录；如果自动检测不能唯一确定当前版本，必须先查明并用 `python3 migrate/migrate.py migrate --from <当前版本> --to current` 显式执行，不得猜测起点。部署流程仍必须核对迁移记录和两个新字段，否则新的插入和带身份查询会失败。该迁移把 `legacy-stable` 的 `CANCELING` 写成 `CANCELED`，其余未结束历史 Run 写成 `FAILED`，并清除数据库中的工具锚点。迁移前应先排空在手请求；已经启动的外部 Sandbox 任务仍需按 Sandbox 的保留期限和清理机制核对，数据库迁移不等于外部任务已经取消。

## 3. 部署代际的计算

部署代际由以下三类输入决定：

1. 从 1 开始递增的部署单版本 `manifestVersion`，最大值为 9007199254740991，保证常见 JSON 实现都能无损表示。
2. 40 位小写十六进制 Git 提交标识 `gitCommit`。
3. 本次部署全部服务的不可变镜像引用。每个引用必须包含仓库名称和 `sha256` 摘要，格式为 `<repository>@sha256:<64 位小写十六进制字符>`。

计算时先按服务名的 ASCII 字符升序排列，再按下面的字节格式拼接。部署单已把服务名限定为小写英文字母、数字和连字符，因此不涉及各语言对非 ASCII 字符的排序差异。所有文本都使用 UTF-8；`\n` 是一个换行字节，`\0` 是一个零字节。

```text
alphafrog-deployment-generation-v1\n
manifest-version:<manifestVersion>\n
git-commit:<gitCommit>\n
service:<serviceName>\0<immutableImageReference>\n
```

对完整字节串计算 SHA-256，并在 64 位小写十六进制结果前加 `gen-`。服务输入顺序不影响结果；部署单版本、Git 提交或任一镜像摘要变化都会改变结果。

仓库中的 `deployment-generation-test-vector.json` 固定了一组跨实现测试向量。公共 Java 实现是 `DeploymentGenerationId.compute`。部署控制器和其他语言实现必须用该向量核对结果，不能自行选择 JSON 序列化或平台换行作为哈希输入。

## 4. 服务实例的可信身份

Agent 服务实例从以下环境变量读取自身身份：

| 环境变量 | 值 |
| --- | --- |
| `AF_DEPLOYMENT_ID` | 当前实例的部署标识 |
| `AF_DEPLOYMENT_GENERATION_ID` | 当前实例的活动代际 |
| `AF_DEPLOYMENT_RETIREMENT_TOKEN` | 部署控制器与 Agent 实例共享的专用随机凭证，至少 32 个字符 |
| `AF_DEPLOYMENT_RETIREMENT_ONLY` | 仅限异常退出后的人工退役修复；普通实例必须为 `false` |

启用 Agent RPC 提供者时，Spring 组件初始化阶段的启动检查会解析两个身份变量和退役凭证，检查通过后才允许应用继续完成启动。身份缺失、空白、格式错误、使用 `legacy-stable`，或退役凭证缺失、少于 32 个字符、带首尾空白或控制字符，都会使服务启动失败。身份和凭证首次读取后保存在进程内，运行期间不接受配置热替换。提供者关闭时，启动恢复不读取这些变量。

稳定环境也使用同一规则。稳定环境的 `deployment_id` 固定为 `stable`，`deployment_generation_id` 仍由部署单版本、完整提交和不可变镜像集合计算。发布新版本时必须产生新的代际。

若旧实例在收到退役请求前异常退出，人工修复使用 `AF_DEPLOYMENT_RETIREMENT_ONLY=true` 重建同一镜像和身份，并同时设置 `AF_DUBBO_REGISTRY_REGISTER=false`。退役专用进程从初始化开始就是不可准入状态：普通 Agent RPC 实现不装配，对这些方法的直接调用返回“未实现”；进程不执行工作流启动恢复、长工具启动或周期恢复、进程内长工具续接、取消协调和工作区重放，只通过直接地址提供可用的退役 RPC。缺少“不注册到服务发现”的设置时启动检查失败。这个模式只用于把同一代际未结束的 Run 写成终态，成功后立即停止并删除实例；不能把它当成普通服务实例。

## 5. RPC 字段

下列接口消息携带部署身份：

| 消息 | 字段 |
| --- | --- |
| `CreateAgentRunRequest` | `deployment_id`、`deployment_generation_id` |
| `SendAgentMessageRequest` | `deployment_id`、`deployment_generation_id` |
| `ResumeAgentRunRequest` | `deployment_id`、`deployment_generation_id` |
| `RetireAgentDeploymentGenerationRequest` | 旧实例自身的 `deployment_id`、`deployment_generation_id`，以及部署控制器专用的 `retirement_token` |
| `AgentRunMessage` | `deployment_id`、`deployment_generation_id` |

入口服务必须覆盖外部客户端提供的同名值，只能从服务端路由事实和受信流量上下文填写请求字段。Agent 服务校验 RPC 字段是否等于本机环境身份。退役接口还使用部署控制器与 Agent 实例之间独立注入的随机凭证，并以常量时间比较；凭证不写日志、不进入响应，也不能复用面向测试用户的入口口令。请求身份与接收实例不一致时，创建请求在扣减额度和写数据库前失败；追问与手动恢复在写消息、事件、缓存或 Run 状态前失败。

带测试部署身份的请求如果被错误回退到稳定实例，稳定实例会因为身份不相等而拒绝创建。这个检查防止调用方把测试请求在稳定环境执行误认为测试部署成功。

## 6. 创建、追问和手动恢复

### 6.1 创建

创建顺序如下：

1. 校验请求中的用户、消息和部署身份。
2. 校验请求身份与当前 Agent 实例身份完全相等。
3. 完成额度检查和调度容量预留。
4. 把两项身份与 Run 其余字段一起插入数据库。
5. 把包含同一身份的 `AgentRunMessage` 返回给调用方，并把 Run 提交给调度器。

调度器在接收 Run 时检查一次身份，执行线程从数据库重读 Run 后再检查一次。第二次检查覆盖排队期间对象被替换或错误传递的情况。恢复执行同样在提交恢复队列和从数据库重读后各检查一次。

执行线程的重读语句直接带当前实例的部署标识和代际。若一个 Run 被错误投递给其他代际，接收方只能确认“这个 Run 不属于本实例”，不能据此判断原代际已经退役，因此只拒绝执行，不改写原 Run。真正的退役终态由原代际实例收到第 8 节的可信退役请求后写入。

### 6.2 追问

追问仅允许当前实例处理同一部署和同一代际的已完成 Run。数据库领取语句同时比较 Run 标识、用户标识、部署标识、部署代际和当前状态。领取成功后才创建用户消息；领取失败返回“原测试部署已停用”，不会把 Run 静默迁移到新代际。

### 6.3 手动恢复

手动恢复先比较请求身份、当前实例身份和数据库中的 Run 身份。数据库重置语句再次比较两项身份和允许恢复的状态。重置成功后，服务按同一身份重读 Run，再提交普通调度器。

`legacy-stable`、旧代际和其他部署的 Run 都不能通过恢复领取。失败响应保留原 Run，不把它改写为当前实例的代际。

### 6.4 取消和暂停

取消与暂停请求沿用既有 RPC，没有让外部客户端填写部署身份。Agent 服务在读取会触发生命周期副作用的 Run 以前，先用本机部署标识、代际、Run 标识和用户标识做数据库查询；查不到时同步返回“原测试部署已停用”。后续取消终态、暂停状态和等待长工具期间的快照写入继续把同一身份与旧状态放进 SQL 条件，条件未命中时不发送取消或暂停事件，也不写对应的 Redis 终态。

取消、暂停、创建、追问和手动恢复与退役操作使用同一个进程内串行区间。控制请求已经进入区间时，它的数据库条件写入先完成，退役随后关闭仍未结束的 Run；退役先取得区间时，后续控制请求直接拒绝。这样退役完成后，迟到的暂停或取消快照不能把 `FAILED` 或 `CANCELED` 改回等待状态。

## 7. 启动恢复和长工具恢复

启动恢复扫描在 SQL 中限定当前实例的 `deployment_id` 和 `deployment_generation_id`。领取、取消遗留记录和写入恢复失败时继续比较这两项。活动实例不会发现 `legacy-stable` 或其他代际的 Run。

长工具对账器的三类扫描也在 SQL 中限定当前实例身份：活跃工具锚点、可恢复锚点和停在状态切换中间的锚点。工具终态处理器在执行第一个工具结果处理或外部清理前，从数据库读取 Run 并比较当前实例身份。身份不相等时，它不消费工具结果、不执行外部清理，也不改写原 Run，只返回 `deployment_generation_inactive`。原代际可能仍在正常服务，错误接收方不能把一次误投当成代际已经失效的证据。

Run 的部署身份由数据库触发器保持不可变。正常执行的状态、计划、终态快照和检查点写入，以及长工具恢复的终态写入，都在 SQL 更新条件中继续比较当前实例的部署标识和代际。普通执行线程只接受数据库重读状态为 `RECEIVED` 的 Run；额度不足终态和开始执行的 SQL 也固定以 `RECEIVED` 为原状态。计划、工作流检查点和普通终态使用 `EXECUTING`；暂停、取消、终态或取消中的状态已经先落库后，迟到的执行写返回 0，不能重新开始模型或工具执行，也不能覆盖控制结果。操作标识、令牌和版本继续负责长工具与恢复执行的所有权。身份条件与并发条件必须同时满足，不能互相替代。

## 8. 代际退出

候选实例启动和后台扫描不会主动发现或改写旧代际 Run。此时默认路由尚未切换，旧实例仍然是合法执行者。若旧 Run 或工具终态被错误投递给候选实例，候选实例只拒绝处理，旧代际继续拥有该 Run。

默认路由切换后，部署控制器停止把新请求交给旧实例，并先禁用旧实例的 Nacos 注册。在向旧进程发送 SIGTERM 以前，控制器必须按旧实例的直接地址调用 `RetireDeploymentGeneration`，请求携带该实例的部署标识、代际和专用退役凭证，不携带 Run 标识。这个地址必须只在部署控制网络内开放；网络隔离缩小访问面，凭证负责验证调用权，二者不能互相替代。Agent 先验证凭证，再要求请求身份与本机环境身份完全一致；通过后把本进程标记为已退役，使新的创建和排队任务停止准入，再按本机身份批量更新仍未结束的 Run：`CANCELING` 进入 `CANCELED`，其余 `RECEIVED`、`PLANNING`、`EXECUTING`、`SUMMARIZING`、`WAITING_TOOL_JOB` 和 `WAITING` 进入 `FAILED`，错误码为 `deployment_generation_inactive`。RPC 只返回成功或失败，不向部署控制器暴露 Run 数量或内容。

创建、追问和手动恢复的数据库准入与退役操作使用同一个进程内串行区间。已经进入该区间的准入会先完成，随后退役操作把它产生的非终态 Run 一并关闭；退役操作先取得区间时，后续准入直接失败。追问会在区间内开启一个独立事务，Run 重置和用户消息插入提交后才释放该区间；它不加入调用方可能存在的外层事务。这个顺序避免批量关闭结束后又插入或重新激活一个旧代际 Run。

控制器收到退役 RPC 成功响应后才对旧容器执行带超时的 SIGTERM 停止。HTTP 和 Dubbo 的在手网络请求从收到 SIGTERM 开始，在部署合同的 `runtime.applicationDrainSeconds` 内排空；退役 RPC 不在外部先等完这段时间，因此不会重复计时。Spring 普通关闭事件会先把本进程标记为正在关闭，再由 Run 线程池请求中断；执行管线在这个关闭窗口捕获到异常时不写业务失败。未收到退役信号的普通关闭仍保持非终态 Run，由同一代际重启后按恢复规则继续；收到退役信号的旧代际则已在 SIGTERM 前把未结束 Run 写成明确终态。只有显式退役 RPC 能触发批量终态更新，候选启动失败和普通关闭都不能冒充代际退役。若进程在退役 RPC 前被强制终止，同一代际重新启动时仍可按原恢复规则继续；若旧代际不再启动，也没有收到退役请求，则数据库不能凭空得知它已经退出，这种异常窗口需要值班人员重新启动旧代际并重放退役请求。

若退役时 Run 正在等待外部长工具，批量更新只负责把 Run 写成明确失败，不清除 `tool_job_anchor_json`，也不承诺已经完成 Sandbox 任务取消、用量结算、终态事件或工具锚点清理。新代际扫描不会处理旧代际锚点；外部 Sandbox 资源仍依赖现有保留期限和清理机制。部署控制器不能为补足这项限制而读取 Run；端到端部署验收必须单独观察旧代际长工具是否在预期时间内释放。

旧实例在 `preStopCompletedAt` 写入前异常退出时，不能用普通模式重建后再发送退役请求，因为应用完成启动与外部请求到达之间存在窗口，启动恢复可能已经重新调用模型或工具。此时只能使用第 4 节的退役专用模式。该进程先以不可准入状态启动，随后验证直接调用的退役请求并执行同一批量终态语句；这条路径不恢复业务执行。

## 9. 失败语义

| 场景 | 结果 |
| --- | --- |
| 服务身份缺失、非法、使用 `legacy-stable`，或退役凭证未安全配置 | Agent RPC 提供者启动失败 |
| 退役请求凭证不匹配 | 同步拒绝，不修改进程退役标志和任何 Run |
| 创建请求身份与接收实例不一致 | 请求在额度和持久化之前失败 |
| 追问指向旧代际、历史代际或其他部署 | 同步返回拒绝，提示“原测试部署已停用” |
| 手动恢复指向旧代际、历史代际或其他部署 | 同步失败，原 Run 保持不变 |
| 启动扫描或长工具扫描遇到其他代际 | SQL 查询不返回该 Run |
| 已准入 Run 或工具终态被错误投递到其他代际 | 错误接收方停止处理并返回 `deployment_generation_inactive`，原 Run 保持不变 |
| 原代际实例收到身份完全匹配的退役 RPC | 取消中的 Run 进入 `CANCELED`，其余未结束 Run 进入 `FAILED` |
| 同一代际普通重启或运维停机 | 不执行批量终态更新；同代际重启后按恢复规则继续 |
| 退役前异常退出后以退役专用模式重建 | 从启动起拒绝普通业务和恢复，只允许匹配身份与凭证的退役 RPC |
| 尝试修改已创建 Run 的部署身份 | 数据库触发器拒绝更新 |

历史 Run 保留查询和审计价值。它们不会自动归入当前稳定代际，也不会因为新版本发布而获得执行资格。

## 10. 与路由和部署控制器的边界

部署控制器根据部署单计算代际，给服务实例注入身份和专用退役凭证，并提供当前流量范围默认指向哪个代际的只读事实。入口流量染色和服务间调用路由使用这份事实选择实例。控制器完成默认路由切换、回读并禁用旧实例的 Nacos 注册后，在发送 SIGTERM 以前通过部署控制网络和旧实例直接地址发送带凭证的退役 RPC；它不提交 Run 标识、不接收 Run 数量，也不查询 Run、Todo、事务或业务恢复状态。

Agent 服务把收到的身份保存到 Run，并在异步执行和恢复时使用数据库身份。部署控制器不查询活动 Run，也不等待 Run 清零。默认路由切换后，新请求由新代际创建新的 Run；旧实例先按可信退役请求把仍未完成的旧 Run 写成明确终态，再由控制器发送 SIGTERM。已经进入旧实例的 HTTP 或 Dubbo 请求从 SIGTERM 开始按部署合同的应用停机期限排空。旧部署删除后，针对旧 Run 的新追问和手动恢复会被拒绝。

## 11. 当前验证范围

仓库单元测试覆盖身份格式、历史代际拒绝、固定哈希向量、服务环境变量、创建前身份校验、追问在写消息前拒绝、手动恢复领取、启动恢复扫描、长工具扫描、调度提交前拒绝、准入与退役串行和显式退役逻辑。MyBatis 是把 Java 方法参数绑定到 SQL 的持久化组件；它的绑定测试检查部署身份条件、状态条件和终态集合。

本次提交只建立 Run 身份、RPC 字段、数据库栅栏和 Agent 端校验。现有稳定环境 Compose 尚未注入 `AF_DEPLOYMENT_GENERATION_ID` 和 `AF_DEPLOYMENT_RETIREMENT_TOKEN`，入口服务也尚未填写创建、追问和手动恢复请求的新字段；在部署控制器、稳定环境部署配置和入口调用方完成对接前，启用 Agent RPC 提供者会因配置缺失而拒绝启动，旧入口即使手工补环境变量也会因请求缺字段而被拒绝。稳定环境还需要由部署流程提供与测试部署相同的部署单版本、完整提交和不可变镜像集合，不能用代码中的临时默认值代替。

实现位置按职责列在下面，路径均从仓库根目录开始：

- RPC 消息与退役接口：`agentApi/src/main/proto/agentService.proto`
- 身份值、格式校验和代际哈希：`common/src/main/java/world/willfrog/alphafrogmicro/common/deployment/DeploymentIdentity.java`、`common/src/main/java/world/willfrog/alphafrogmicro/common/deployment/DeploymentGenerationId.java`、`common/src/main/java/world/willfrog/alphafrogmicro/common/deployment/DeploymentIdentityProvider.java`、`common/src/main/java/world/willfrog/alphafrogmicro/common/deployment/DeploymentIdentityMismatchException.java`
- 跨语言代际向量：`deploy/agent-run/deployment-generation-test-vector.json`
- 环境身份、启动检查和退役鉴权：`agentLangchainService/src/main/java/world/willfrog/agentlangchain/deployment/EnvironmentDeploymentIdentityProvider.java`、`agentLangchainService/src/main/java/world/willfrog/agentlangchain/deployment/DeploymentIdentityStartupVerifier.java`、`agentLangchainService/src/main/java/world/willfrog/agentlangchain/deployment/RetirementOnlyStartupVerifier.java`、`agentLangchainService/src/main/java/world/willfrog/agentlangchain/deployment/DeploymentRetirementAuthorizer.java`
- 普通关闭状态和显式退役：`agentLangchainService/src/main/java/world/willfrog/agentlangchain/deployment/AgentServiceShutdownState.java`、`agentLangchainService/src/main/java/world/willfrog/agentlangchain/deployment/DeploymentGenerationRetirementService.java`
- 创建、追问、恢复和消息映射入口：`agentLangchainService/src/main/java/world/willfrog/agentlangchain/facade/AgentLangchainRunService.java`、`agentLangchainService/src/main/java/world/willfrog/agentlangchain/facade/LangchainFollowUpService.java`、`agentLangchainService/src/main/java/world/willfrog/agentlangchain/facade/LangchainRunControlService.java`、`agentLangchainService/src/main/java/world/willfrog/agentlangchain/facade/AgentLangchainRunMessageMapper.java`
- RPC 实现：`agentLangchainService/src/main/java/world/willfrog/agentlangchain/facade/AgentLangchainDubboServiceImpl.java`、`agentLangchainService/src/main/java/world/willfrog/agentlangchain/facade/RetirementOnlyAgentDubboService.java`
- 异步线程关闭、执行和启动恢复：`agentLangchainService/src/main/java/world/willfrog/agentlangchain/config/LangchainRunAsyncConfig.java`、`agentLangchainService/src/main/java/world/willfrog/agentlangchain/execution/LangchainLinearRunPipelineImpl.java`、`agentLangchainService/src/main/java/world/willfrog/agentlangchain/control/WorkflowStartupRecovery.java`
- 检查点身份条件：`agentLangchainService/src/main/java/world/willfrog/agentlangchain/execution/WorkflowCheckpointService.java`、`agentLangchainService/src/main/java/world/willfrog/agentlangchain/execution/LangchainToolJobCheckpointCoordinator.java`
- 长工具发现、检查点、续接和终态处理：`agentLangchainService/src/main/java/world/willfrog/agentlangchain/tooljob/ToolJobAnchorService.java`、`agentLangchainService/src/main/java/world/willfrog/agentlangchain/tooljob/ToolJobCheckpointService.java`、`agentLangchainService/src/main/java/world/willfrog/agentlangchain/tooljob/ToolJobCheckpointFailureRecoveryService.java`、`agentLangchainService/src/main/java/world/willfrog/agentlangchain/tooljob/ToolJobContinuationTracker.java`、`agentLangchainService/src/main/java/world/willfrog/agentlangchain/tooljob/ToolJobResumeLauncherImpl.java`、`agentLangchainService/src/main/java/world/willfrog/agentlangchain/tooljob/ToolJobFinalizer.java`
- Run 实体和数据库接口：`agentPlatformShared/src/main/java/world/willfrog/agent/platform/entity/AgentRun.java`、`agentPlatformShared/src/main/java/world/willfrog/agent/platform/mapper/AgentRunMapper.java`、`agentPlatformShared/src/main/resources/mapper/AgentRunMapper.xml`
- 事件侧身份读取：`agentPlatformShared/src/main/java/world/willfrog/agent/platform/service/AgentRunEventService.java`
- 新库建表和现有库升级：`migrate/migrations/init/004_agent.sql`、`migrate/migrations/upgrades/v1.5/001_agent_run_deployment_identity.sql`
- Beta 部署单和实例状态约束：`deploy/beta/manifest.schema.json`、`deploy/beta/controller-state.schema.json`、`deploy/beta/beta-deployment-traffic-governance-contract.md`、`deploy/beta/verify-contract.mjs`

这些测试没有启动 PostgreSQL、Docker、Nacos 或完整服务，也没有执行真实进程重启、默认路由切换、Spring 完整关闭顺序和跨机调用。数据库触发器、迁移执行、环境变量注入、退役 RPC 的直接寻址、正在运行线程的中断响应、外部长工具清理、非优雅终止和端到端拒绝结果仍需在获准的集成环境验证。
