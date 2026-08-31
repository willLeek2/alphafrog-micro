# 0-3 Beta 部署单与流量状态合同

本文固定 Beta 部署控制器使用的两类运行时文件。`manifest.json` 记录一个隔离流量范围希望运行哪些服务，`controller-state.json` 记录每个服务当前由哪个实例接收新流量，以及是否有候选实例正在启动、旧实例正在排空。

配套的可执行结构是：

- `deploy/beta/manifest.schema.json`
- `deploy/beta/controller-state.schema.json`

这套合同适用于 AlphaFrog 的所有服务。部署控制器不会因为某个服务处理 Agent Run、普通 HTTP 请求或行情查询而采用不同流程。

## 1. 要解决的问题

更新服务时，新旧实例需要短暂共存：

```mermaid
flowchart LR
    A[旧实例 A 继续接收流量] --> B[在另一个固定端口启动候选 B]
    B --> C{B 的容器健康检查通过吗}
    C -->|否| D[删除 B，A 继续服务]
    C -->|是| E[原子切换默认路由：A → B]
    E --> F[向 A 发送 SIGTERM]
    F --> G[A 停止接收新连接并处理在手请求]
    G -->|正常退出| H[删除 A，B 成为稳定活动实例]
    G -->|超过排空期限| I[强制停止并删除 A]
    I --> H
```

候选实例健康以前，旧实例继续承接流量，所以更新不会主动制造维护窗口。流量切换以后，部署控制器不重试旧实例里的请求，也不判断这些请求最终成功还是失败。

部署控制器只管理五类事实：

1. 当前默认流量指向哪个实例。
2. 是否有候选实例正在启动或等待健康检查。
3. 是否有旧实例正在排空。
4. 容器、端口和 Nacos 注册分别对应哪个实例。
5. 最近一次部署操作失败在哪里。

Agent Run、数据库事务、业务重试和业务恢复不属于本合同。部署控制器不查询 Run 表，不统计活动 Run，也不根据业务结果回滚流量。

首版最多管理 8 台机器，由一个控制器进程串行执行变更。同一时刻全局最多有一个服务的 `operation` 不为空。本合同不设计控制器集群选主、资源额度、跨机器租约或并行发布调度。

## 2. 流量范围和两个端口槽

`trafficScopeId` 表示一组彼此隔离的流量和实例。主 Beta 固定使用 `main-beta`，泳道使用 0-2 分配的其他标识。同一个服务可以同时出现在主 Beta 和多条泳道里，但 `trafficScopeId + serviceName` 不同，就必须使用彼此独立的实例集合和默认路由。

一个部署单描述一个完整流量范围。同一时刻只能有一个活动部署占用某个 `trafficScopeId`，不能用两个部署共同维护同一范围。

每个服务固定配置两个不同的宿主端口，称为端口槽 `A` 和 `B`。容器内端口保持不变：

- 稳定状态下，活动实例占用一个槽，另一个槽空闲。
- 更新时，候选实例占用另一个槽，所以新旧实例可以同时运行。
- 切换完成并删除旧实例后，两个槽的角色互换；下一次更新再使用空闲槽。

端口由部署单的 `runtime.hostPorts` 固定，控制器不能临时另选端口。`machineId` 指向 2-4 控制器配置中的静态机器表；机器地址和访问凭据不写进部署单。机器无法访问时，控制器不能把“没有查到容器”解释成“容器不存在”。

## 3. Nacos 注册和默认路由

新旧实例在切换窗口中都按实例注册到 Nacos。[Nacos 管理接口文档](https://nacos.io/en/docs/latest/manual/admin/admin-api/)提供了实例查询与实例标识的实际格式依据。每条实际注册记录保存完整服务名、分组、命名空间、集群、IP、端口和 Nacos 实例标识，并带上：

- `registeredTrafficScopeId`：实例属于哪个主 Beta 或泳道范围。
- `registeredReleaseId`：实例属于哪个服务版本。

因此正常更新时查询到两个实例不是错误。控制器必须能区分活动实例、候选实例和排空实例，不能再以“查询结果恰好一条”作为成功条件。

默认路由由 `trafficScopeId + serviceName` 唯一定位。本合同不允许调用方再提交一份独立 `routeKey`，避免两份身份不一致。0-3 选择的首版执行点是 2-4 控制器维护的路由事实：

- `route.defaultInstanceId`：默认实例。
- `route.defaultReleaseId`：新请求和后续服务调用使用的默认版本标签。
- `route.routeVersion`：每次创建、切换或移除默认路由时递增。
- `route.updatedAt`：该路由事实的写入时间。

控制器通过一次 `controller-state.json` 原子替换同时更新默认实例、默认版本和实例角色。2-4 的只读状态接口返回这份完整快照；2-5 入口染色和 2-3 服务调用路由只读取完整版本，不分别拼接字段。因此“切换”在合同里只有旧快照和新快照，不存在一半字段属于旧实例、另一半属于新实例的中间状态。

Nacos 负责保存并发现按版本区分的实例，路由事实负责决定无显式版本的新流量使用哪个版本。Nacos 中同时存在新旧实例不等于新旧实例同时接收默认流量。

## 4. 运行时文件和写入规则

运行时路径固定为：

```text
/var/lib/alphafrog-beta/
├── controller-state.json
└── deployments/
    └── <deployment-id>/
        └── manifest.json
```

全局状态文件是 `/var/lib/alphafrog-beta/controller-state.json`。单个部署的部署单是 `/var/lib/alphafrog-beta/deployments/<deployment-id>/manifest.json`。2-4 控制器和测试必须直接引用这两个文件名。

两个文件都使用 UTF-8 JSON，禁止重复键。每次更新先在目标目录写临时文件，对文件执行 `fsync`，再使用原子重命名替换正式文件，最后对父目录执行 `fsync`。`controller-state.json` 每次成功替换时把 `stateVersion` 加 1。

部署控制器是 `manifest.json` 的唯一写入者。它先校验请求、生成完整部署单、重新计算摘要，再原子写入文件。调用方不能直接改运行时文件。同一个部署存在任何未完成 `operation`，或者整个部署处于 `DELETING` 时，控制器拒绝接受新部署单，防止新版本覆盖正在执行的目标。

两个文件不能一起原子替换，所以只允许一个短暂领先窗口：新 `manifest.json` 已经落盘，而 `controller-state.json` 仍保存旧 `acceptedManifestVersion`，并且没有未完成操作。重启后控制器重新校验新部署单，再把新版本接受进全局状态。全局状态不能领先部署单，也不能在操作执行中被另一版部署单覆盖。

## 5. 部署单 `manifest.json`

部署单保存期望配置，不保存容器标识、健康结果、当前路由或排空进度。

### 5.1 顶层字段

| 字段 | 含义 |
| --- | --- |
| `schemaVersion` | 首版固定为 `1` |
| `deploymentId` | Beta 部署标识；保留值 `stable` 禁止使用 |
| `trafficScopeId` | 主 Beta 或某条泳道的隔离流量范围 |
| `manifestVersion` | 同一部署从 1 开始严格递增的部署单版本 |
| `gitCommit` | 这次部署对应的 40 位 Git 提交标识 |
| `owner` | 至少包含 `ownerId`，用于找到部署负责人 |
| `createdAt`、`expiresAt` | UTC 时间；到期触发删除操作，不触发业务恢复 |
| `services` | 该流量范围的完整服务集合，`serviceName` 必须唯一；首版普通更新只能保持或增加服务，不能移除已有服务 |

### 5.2 服务字段

每个服务包含：

- `serviceName`：服务名。
- `releaseId`：该服务这次发布的可追踪版本标识。不同服务可以使用不同版本，因此它属于服务而不是部署单顶层。
- `serviceSpecSha256`：本服务完整配置的规范 JSON 摘要。
- `machineId`：服务运行在哪台静态 Beta 机器。
- `image.repositoryDigest`：不可变仓库镜像引用。
- `image.localImageId`：目标机器已经安装的本地镜像标识。
- `runtime.containerPort`：容器内端口。
- `runtime.hostPorts`：两个不同的固定宿主端口，数组第一个对应槽 A，第二个对应槽 B。
- `runtime.readinessTimeoutSeconds`：候选容器从启动到必须就绪的最长时间。
- `runtime.drainGraceSeconds`：发送 SIGTERM 后最多等待多少秒，超时后允许强制停止。
- `registration`：Nacos 服务名、分组、命名空间和集群模板。
- `runtimeConfigSha256`：可选运行配置摘要；秘密值本身不得写入部署单。

候选是否就绪使用镜像或 Compose 已定义的容器 `HEALTHCHECK`。候选容器必须实际带有健康检查；缺少健康检查直接判定候选失败。本合同不要求每个服务另实现 `healthPath` 或 `drainPath`。

排空统一执行 `docker stop --signal SIGTERM --timeout <drainGraceSeconds>`，明确覆盖镜像可能配置的其他 `STOPSIGNAL`。服务应在 SIGTERM 处理器中停止接受新连接并等待在手请求；到期仍未退出时 Docker 强制停止。[Docker stop 命令文档](https://docs.docker.com/reference/cli/docker/container/stop/)说明了显式信号、超时和强制停止行为。

`serviceSpecSha256` 使用 RFC 8785 JSON Canonicalization Scheme（JCS，规范 JSON 序列化）计算。输入是当前服务对象删除 `serviceSpecSha256` 后的结果。2-4 每次读取部署单时重新计算，摘要不一致就拒绝应用。

## 6. 全局状态 `controller-state.json`

全局状态保存控制器已经接受的部署单身份，以及 Docker、Nacos 和路由层观察到的事实。它不保存业务请求、Run 标识、事务结果、资源预算或历史幂等回执。

### 6.1 部署状态

部署记录包含 `deploymentId`、`trafficScopeId`、`acceptedManifestVersion`、`manifestSha256`、`gitCommit`、`owner`、`expiresAt` 和服务列表。`phase` 只有：

- `ACTIVE`：部署单存在，至少还有一个服务状态。首次创建多个服务时，未轮到的服务也保存在列表中。
- `DELETING`：整个部署正在删除；未轮到的服务保持 `STABLE`，当前最多一个服务执行 `DELETE`，清理完成的服务逐个从列表移除。任一服务进入 `FAILED` 后停止选择下一个服务。

这些重复字段必须与当前部署单逐项相等。只有删除部署的最后一个固定窗口允许 `phase=DELETING`、`services=[]` 且 `manifest.json` 已不存在；控制器随后只需移除部署记录。

### 6.2 服务状态

一个服务槽位最多保存三个实例位置：

| 字段 | 含义 |
| --- | --- |
| `activeInstance` | 当前默认流量应进入的活动实例 |
| `candidateInstance` | 正在启动或等待就绪的候选实例 |
| `drainingInstance` | 已经退出默认路由、正在排空的旧实例 |

首版全局串行操作，所以同一个服务最多有一个旧实例排空，不使用数组累积多代实例。如果旧实例到排空期限仍未退出，控制器强制停止并删除它，然后释放端口槽。

`targetManifestVersion` 和 `targetServiceSpecSha256` 必须等于当前已接受部署单中该服务的 `manifestVersion` 和 `serviceSpecSha256`。它们表示本次部署目标，不等于活动实例当前运行的版本。更新在切流前失败时，这两个目标字段不回退；旧活动实例仍保留自身的 `manifestVersion` 和 `serviceSpecSha256`，`failedManifestVersion` 记录失败目标。这样状态接口可以同时说明“当前仍运行旧版本”和“哪一版更新失败”。

一个多服务部署单被接受后，全局串行规则只允许其中一个服务开始操作。尚无活动实例的新服务保存为 `CREATING + operation=null + candidateInstance=null`；已有活动实例的服务保持 `STABLE + operation=null`。轮到新服务时，控制器才原子写入 `CREATE` 操作；轮到已有服务时才进入 `UPDATING`。排队的 `STABLE` 服务活动实例可以仍是上一代，目标字段已经指向新部署单。因此 `STABLE` 有三种正常判断：活动版本等于目标表示已经收敛；活动版本落后且 `failedManifestVersion != targetManifestVersion` 表示等待普通调度；活动版本落后且两者相等表示失败暂停，普通扫描不得重试。

服务 `phase` 有五种：

- `CREATING`：没有活动实例，正在排队或创建第一个候选。排队时 `operation` 和 `candidateInstance` 都是 `null`。
- `STABLE`：一个活动实例接收默认流量，没有候选、排空实例或当前操作。
- `UPDATING`：旧活动实例继续服务，或者新活动实例已经切流而旧实例正在排空。
- `DELETING`：默认路由正在移除，或者原活动实例正在排空。
- `FAILED`：控制器无法唯一确认安全下一步，当前操作已经停止，保留实例和错误事实供人处理。

`failedManifestVersion` 和 `lastError` 记录最近一次失败目标。候选在切流前明确失败且可以安全删除时，旧活动实例和旧路由不变，服务回到 `STABLE`，同时保留本次失败版本和错误。`FAILED` 用于创建失败、活动实例意外消失、机器查询不确定、对象身份冲突或路由事实与实例角色不一致等无法安全自动收尾的情况。

### 6.3 实例状态

三类实例都保存实例标识、机器、服务版本、部署单版本、服务摘要、容器名称与标识、端口槽、宿主端口、可路由地址和完整 Nacos 注册事实。

候选实例另外保存 `readiness`、`readinessObservedAt` 和 `readinessDeadline`。控制器用候选启动时间加 `readinessTimeoutSeconds` 得到截止时间，并在等待健康以前落盘。Docker 状态映射固定为：`starting` 写 `STARTING`，`healthy` 写 `READY`，`unhealthy` 或缺少 HEALTHCHECK 写 `FAILED`；目标机器无法访问写 `UNKNOWN`，不能把候选当作已经不存在。到达截止时间仍未 `READY` 也按候选失败处理。

排空实例另外保存 `drainStartedAt` 和 `drainDeadline`。`drainStartedAt` 表示排空检查点生效的时间，不表示停止命令已经发送；控制器在发送 SIGTERM 前先持久化这两个时间。实例正常退出或到期强制停止后，先注销该实例的 Nacos 注册并删除容器，再清空 `drainingInstance`。

### 6.4 当前操作

当前操作类型只有 `CREATE`、`UPDATE` 和 `DELETE`，阶段只有六个：

| 阶段 | 含义 |
| --- | --- |
| `STARTING_CANDIDATE` | 已分配候选实例标识，正在创建容器 |
| `WAITING_CANDIDATE_READINESS` | 候选容器存在，等待 Docker 健康检查 |
| `SWITCHING_TRAFFIC` | 候选就绪，准备原子替换默认路由和实例角色 |
| `DRAINING_PREVIOUS` | 新实例已接收默认流量，旧实例正在排空 |
| `REMOVING_TRAFFIC` | 删除服务时正在移除默认路由 |
| `DRAINING_ACTIVE` | 删除服务时原活动实例正在排空 |

`CREATE` 只使用前三个阶段；`UPDATE` 使用前四个阶段；`DELETE` 只使用后两个阶段。创建容器以前，控制器先生成 `candidateInstanceId` 并写入 `STARTING_CANDIDATE`，此时完整 `candidateInstance` 可以仍为 `null`。确定容器事实以后才填写候选记录。

## 7. 创建、更新和删除

### 7.1 创建

部署第一次包含多个服务，或者后续部署单一次增加多个服务时，控制器先把所有新增服务写成 `CREATING + operation=null + candidateInstance=null`。如果全局没有当前操作，也没有任何服务停在 `FAILED`，控制器按 `trafficScopeId`、`serviceName` 的字典序选择一个排队服务，并在一次全局状态替换中为它建立 `CREATE` 操作。其余新增服务继续排队，不会同时创建第二个候选。当前服务成为 `STABLE` 后才选择下一个；当前服务进入 `FAILED` 时停止这个部署的自动创建，等待人显式重试或提交可接受的新部署单。

```text
CREATING / STARTING_CANDIDATE
  → 在槽 A 或 B 创建候选并按实例注册 Nacos
  → WAITING_CANDIDATE_READINESS
  → Docker 健康检查通过
  → SWITCHING_TRAFFIC
  → 默认路由实际指向候选
  → 候选成为 activeInstance
  → STABLE，清空 operation
```

候选缺少 HEALTHCHECK、明确 `unhealthy` 或到截止时间仍未就绪时，控制器删除候选容器和注册，写入 `FAILED` 与错误，不生成或重试业务请求。

### 7.2 更新

更新开始时，旧 `activeInstance` 和旧路由保持不动。候选必须使用另一个端口槽，并在注册元数据中使用新 `releaseId`。服务存在期间，`machineId` 和两个 `hostPorts` 不可变；需要移动机器或更换端口时，调用方必须先完成删除，再按新部署创建，不能把这类迁移伪装成普通更新。

候选缺少 HEALTHCHECK、明确 `unhealthy` 或到截止时间仍未就绪时，只删除候选的容器和 Nacos 注册，旧实例继续服务并回到 `STABLE`。如果机器无法访问，控制器不能确认候选是否已删除，服务进入 `FAILED` 并保留候选事实。控制器不自动重试同一个 `manifestVersion`；调用方需要显式提交更高版本，或者使用后续 2-4 定义的显式重试操作。

候选就绪后，控制器先写入 `SWITCHING_TRAFFIC` 检查点，再计算 `drainStartedAt` 和 `drainDeadline`。下一次原子替换同时把 `route.defaultInstanceId/defaultReleaseId` 改成候选、把候选变成 `activeInstance`、把旧活动实例连同完整排空时间变成 `drainingInstance`，并进入 `DRAINING_PREVIOUS`。成功落盘后才向旧实例发送停止命令。读取者只会看到切换前或切换后的完整快照。

流量切到新实例并且排空期限已经随角色一起持久化后，控制器对旧容器执行带超时的停止。旧请求的成功、失败或业务重试不改变部署状态。旧实例退出或被强制停止、注册和容器都清理后，服务进入 `STABLE`。

### 7.3 删除整个部署

首版普通新部署单禁止从 `services` 中移除已有服务。需要减少服务集合时，调用方先删除整个部署，等实例和端口全部清理，再用新的完整服务集合创建部署。这样删除期间原部署单一直保留每个服务的目标、机器和两个端口，不需要增加单服务删除墓碑。

删除整个部署时，每个服务先进入 `REMOVING_TRAFFIC`，再计算排空开始时间和期限。下一次原子替换同时把默认实例和默认版本改为 `null`、把活动实例连同完整排空时间移入 `drainingInstance`，并进入 `DRAINING_ACTIVE`。成功落盘后控制器才发送 SIGTERM。实例退出或到期强制停止后，控制器注销注册、删除容器并移除服务状态。

删除整个部署时，控制器先确认没有其他当前操作，再把部署 `phase` 写成 `DELETING`。尚未轮到的服务保持 `STABLE + operation=null`；控制器按 `serviceName` 字典序选择一个服务，原子写入它的 `DELETING + DELETE operation`，完成后移除该服务，再选择下一个。查询机器、容器或 Nacos 时若无法唯一确认结果，当前服务进入 `FAILED + operation=null`，剩余服务继续保持 `STABLE`，整个部署停止自动删除。人处理并显式重试以前，控制器不能跳过失败服务去启动下一项删除。

服务列表为空后，控制器删除固定路径下的 `manifest.json`，同步父目录，再移除全局部署记录。控制器只删除这一个已知文件并尝试删除空目录，不递归删除部署目录。

## 8. 控制器重启后的最小核对

首版不保证在每个外部调用中断点自动恢复。控制器重启时只做一次有限核对：

1. 读取并校验两类文件；失败就停止写操作并报告错误。
2. 按 `machineId` 查询记录中的容器，按完整注册键查询 Nacos，并把 `controller-state.json` 中的路由事实作为默认路由真相。
3. 容器名称和标签必须同时匹配 `deploymentId`、`trafficScopeId`、`serviceName`、`instanceId` 和 `releaseId`。仅找到一个完全匹配对象时可以补齐状态；找到多个对象或身份冲突时写 `FAILED`。
4. 路由事实仍指向旧实例时，旧实例继续服务；路由事实已经指向候选时，实例角色也必须是“候选已成为活动实例、旧实例正在排空”。角色与路由不一致时写 `FAILED`，不猜测哪一方正确。
5. 候选仍在等待时，控制器按当前时间和 `readinessDeadline` 继续检查；截止前保持等待，截止后执行第 7 节的候选失败规则。缺少 HEALTHCHECK 立即失败。
6. 存在 `drainingInstance` 且容器仍运行时，控制器按剩余 `drainDeadline` 幂等重发 `docker stop --signal SIGTERM --timeout <剩余秒数>`；期限已过则直接强制停止。容器已退出时继续注销 Nacos、删除容器并清空排空记录。查询或停止命令无法确认结果时写 `FAILED`。
7. 机器或 Nacos 查询失败时保留最后事实并写错误，不把“无法观察”当作“对象不存在”。

如果这些事实不能唯一决定下一步，控制器停在 `FAILED`，由人重新操作。它不建设多阶段自动回滚、通用人工修复工作流或业务请求恢复。

## 9. Schema 之外的一致性检查

JSON Schema 负责字段、类型、枚举和基本组合。2-4 还必须执行以下跨记录检查：

1. `deploymentId` 唯一；一个 `trafficScopeId` 最多对应一个活动部署；部署内 `serviceName` 唯一。
2. 所有实例标识、容器标识和完整 Nacos 注册身份全局唯一。同一服务的活动、候选和排空实例不能使用相同 `instanceId` 或端口槽。所有活动部署单中的两个预留槽都参与检查，`machineId + hostPort` 全局唯一；主 Beta、不同泳道和不同服务也不能重叠。服务仍存在时，新部署单的 `machineId + hostPorts` 必须与旧状态一致，因此未退出的上一代实例不会因为部署单改址而提前释放端口。
3. 候选实例和切流后的新活动实例必须匹配当前目标的 `manifestVersion`、`serviceSpecSha256` 和 `releaseId`。切流前的旧活动实例、切流后的排空实例保留自身上一代事实。`STABLE` 的活动实例可以等于目标，也可以在正常排队或失败暂停时仍是上一代；是否可调度由目标版本和 `failedManifestVersion` 的三种组合决定。所有实例的注册端口等于宿主端口，注册范围和版本等于实例自身的范围和版本。
4. `STABLE` 必须只有一个活动实例，且默认实例和默认版本等于活动实例；不能有候选、排空实例或当前操作。`CREATING` 的默认实例和默认版本必须为空。
5. `UPDATING` 切流前必须保存“旧活动实例 + 新候选实例”，路由仍指向旧活动实例；切流后必须保存“新活动实例 + 旧排空实例”，路由指向新活动实例，两者占用不同端口槽。`DELETING/REMOVING_TRAFFIC` 的默认路由必须仍等于活动实例；进入 `DRAINING_ACTIVE` 时默认实例和默认版本必须同时为空。
6. 全部部署合计最多一个非空 `operation`。没有当前操作时，只要某个部署内存在 `FAILED` 服务，控制器就停止该部署的自动调度。其余部署先排除 `failedManifestVersion == targetManifestVersion` 的服务，再按 `trafficScopeId`、`serviceName` 的字典序选择下一项工作；排队的 `CREATING` 可以建立 `CREATE`，排队的上一代 `STABLE` 可以建立 `UPDATE`，`DELETING` 部署只能逐个建立 `DELETE`。失败目标只有在部署单版本提高，或 2-4 收到显式重试操作后才重新进入调度；普通扫描不能自动重试同一版。
7. `CREATE/UPDATE` 操作先保存非空 `candidateInstanceId`。`STARTING_CANDIDATE` 允许完整候选记录暂时为 `null`；候选记录出现后，它的 `instanceId` 必须逐字等于预分配的 `operation.candidateInstanceId`。`DELETE` 和 `DRAINING_PREVIOUS` 的 `candidateInstanceId` 必须为 `null`。排空期限必须等于或晚于排空开始时间，部署到期时间必须晚于创建时间。
8. 部署记录的 `acceptedManifestVersion`、摘要、Git 提交、负责人、到期时间和服务目标必须与部署单一致；只允许第 4 节定义的新部署单领先窗口和删除窗口。

任一检查失败时，控制器停止新的外部写操作并记录部署错误。它不会根据业务 Run 或请求结果修复状态。

## 10. 状态接口和验收

状态接口按流量范围和服务至少返回：

- `trafficScopeId`、`serviceName` 和服务 `phase`。
- 活动、候选和排空实例标识。
- 默认实例、默认版本、路由版本和切换时间。
- 当前操作类型和阶段。
- 最近一次部署错误和失败的部署单版本。

0-3 的静态验收至少覆盖：

- 两份 Schema 通过 Draft 2020-12 元 Schema校验。
- 合法部署单通过；重复服务、相同的两个宿主端口、可变镜像标签、错误摘要和保留标识 `stable` 被拒绝。
- 普通部署单移除已有服务被拒绝；删除整个部署时，原部署单保留到全部服务完成排空和清理。
- 自定义检查拒绝两个部署在同一机器预留重叠宿主端口，并同时覆盖跨服务和跨流量范围冲突。
- 自定义检查拒绝普通更新改变 `machineId` 或两个固定宿主端口，并覆盖上一代容器尚未退出时不能释放旧端口。
- 创建起点、候选就绪、切流后排空、稳定状态和删除排空样例通过。
- `CREATE/UPDATE/DELETE` 与阶段的非法组合被拒绝。
- 自定义检查拒绝同一范围的两个部署、全局两个并行操作、活动与候选共用端口槽、Nacos 身份重复、`STABLE` 路由不指向活动实例。
- 候选验收覆盖缺少 HEALTHCHECK、`unhealthy`、持续 `starting` 到超时和机器不可访问；重启核对覆盖“候选已就绪但路由仍是旧实例”“路由已切到候选但停止命令尚未发送”“排空期限已经超过”“机器不可访问不能当作实例已退出”和“路由与实例角色不一致必须失败”。
- 更新失败后连续执行多轮普通调度，均不得自动重试 `failedManifestVersion == targetManifestVersion` 的目标；提高部署单版本或显式重试后才允许重新创建候选。
- 一个部署单同时更新多个服务时，只允许一个服务拥有 `operation`，其余服务以 `STABLE` 上一代实例合法排队；轮到以后再进入更新。
- 首次部署两个服务或一次增加两个服务时，只允许一个服务拥有 `CREATE operation`，其余新增服务以 `CREATING + operation=null + candidateInstance=null` 合法排队。
- 删除至少两个服务时，只有当前服务拥有 `DELETE operation`，未轮到的服务保持 `STABLE`；当前删除失败后该服务进入 `FAILED`，后续服务不能启动删除。
- 文档和两个 Schema 使用同一字段名和运行时文件名。

这份合同和 Schema 只定义静态结构与流程。当前没有运行 Beta 控制器、Docker、Nacos、数据库或跨机器网络，也没有验证真实服务能否在 SIGTERM 后正确停止接收新连接并在期限内退出。
