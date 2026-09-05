# Beta 部署与流量治理合同

## 1. 目标与边界

Beta 控制器负责把部署单变成服务实例，并通过 Nacos 注册完成新旧实例切换。控制器只处理容器、端口、健康检查、注册和统一停止期限，不读取 Agent Run、Todo、事务或业务恢复状态。

流量选择不再由控制器状态文件或自研精确实例路由器承担。请求实际落到哪里，以 Dubbo 标签路由、同区优先和 Nacos 当前注册为准。`controller-state.json` 只保存部署编排检查点，帮助控制器重启后继续创建、切换、排空或清理。

## 2. 运行时文件

每个部署目录保存一份通过 `manifest.schema.json` 校验的部署单。控制器状态根目录保存一份通过 `controller-state.schema.json` 校验的 `controller-state.json`。

部署单由调用方提交并在接受后保持不可变。全局状态只有控制器可以写，读取者不得把它当作路由表。写入使用同目录临时文件、文件落盘、原子替换和目录落盘；读取到损坏或无法解释的状态时停止自动操作并报告错误。

两份 Schema 只检查单份 JSON 的结构。跨文件相等关系、部署代际摘要、服务摘要、公共处理期限、实例角色与注册事实由控制器的合同校验器检查。

## 3. 部署单

部署单至少固定：

- `deploymentId`：测试部署标识，不能使用稳定环境保留值 `stable`。
- `trafficScopeId`：`main-beta` 或一个泳道名称；不能使用稳定环境保留值 `stable`。
- `manifestVersion`、完整 Git 提交和部署所有者。
- 每个服务的不可变镜像摘要、本机 Image ID、机器、双宿主端口、健康检查、统一处理期限、Dubbo 应用名和 Nacos 注册键。

控制器配置 `applicationDrainSeconds` 是这一台控制器管理的所有主 Beta 与泳道服务共同使用的处理期限，默认 60 秒且不得小于 6 秒。每份部署单中全部服务的 `applicationDrainSeconds` 与 `drainGraceSeconds` 都必须等于这个配置值。Agent 在总期限中固定保留最后 5 秒用于失败记录持久化和进程退出，其余时间是自然处理窗口；控制器把相同总期限交给 Docker 作为强制停止边界。这样多个部署单不能各自延长或缩短停止窗口，也不会出现自然处理窗口为零的配置。

Agent 的应用名固定为 `agent-langchain-service`。控制器为这个应用生成一条可推导的关闭时间线：公共期限 `T` 同时写入 Agent 关闭配置和 Docker `stop_grace_period`；自然处理窗口为 `T-5`；Dubbo 静态等待上限为 5 秒；Spring 后续生命周期等待为 0。Agent 在关闭事件开始时再把 Dubbo 的统一截止时间登记为“当前时间 + T”，自然窗口结束后停止 Run 执行器并执行失败写入。因此，数据库写入、Dubbo 和进程退出共享最后 5 秒，Run 执行器、Dubbo 或 Spring 都不能在自然窗口结束后重新取得完整的 `T`。其它服务继续按自己的 `shutdownProfile` 使用 Spring 或 Dubbo 有序关闭，Docker 的 `T` 是所有服务共同的最终强制停止边界。

服务使用两个固定宿主端口槽。活动实例占一个槽，更新候选占另一个槽；切换完成并清理旧实例后，两者角色互换。容器内端口保持服务自己的固定值，部署单显式保存两组宿主端口。

## 4. 注册拓扑与官方路由

本合同按仓库固定的 Dubbo 3.3.2 行为编写。标签筛选以官方 [`TagStateRouter`](https://github.com/apache/dubbo/blob/dubbo-3.3.2/dubbo-cluster/src/main/java/org/apache/dubbo/rpc/cluster/router/tag/TagStateRouter.java) 为准；跨注册订阅的同区优先以官方 [`ZoneAwareClusterInvoker`](https://github.com/apache/dubbo/blob/dubbo-3.3.2/dubbo-cluster/src/main/java/org/apache/dubbo/rpc/cluster/support/registry/ZoneAwareClusterInvoker.java) 为准。升级 Dubbo 时必须先重跑真实路由顺序测试，再确认下面的回落关系仍成立。

Beta 和生产共享同一台 Nacos 服务，但使用两套逻辑注册配置：

| 逻辑注册 | Nacos 分组 | Dubbo zone | 订阅方 |
|----------|------------|------------|--------|
| Beta | `alphafrog-beta` | `beta` | Beta 消费方 |
| 生产 | `DEFAULT_GROUP` | `prod` | Beta 与生产消费方按各自配置 |

Beta 消费方同时订阅两路并使用 Dubbo 原生 `zone-aware` 集群。生产服务的现有 Dubbo 配置没有另设 Nacos 分组，因此 Beta 消费方的生产路订阅 Nacos 默认分组 `DEFAULT_GROUP`。Beta registry 标记为官方 `preferred=true`，因此当前 Beta 消费方先选 Beta 路；Beta 路对该服务没有可用提供者时才落到生产路。生产服务只订阅它现有的默认分组，不反向看到 Beta 实例。

Beta 逻辑注册内部使用 Dubbo 原生标签路由：

- 主 Beta 实例不带静态泳道标签。
- 泳道实例的 Nacos metadata 保存 Dubbo 官方提供者参数 `dubbo.tag=<lane>`；同时保存同值的 `tag=<lane>`，方便注册现场检查，但路由判断只以 `dubbo.tag` 为准。
- 请求通过官方附件 `dubbo.tag` 携带泳道标签。
- 有同标实例时只选同标实例；没有同标实例时按 Dubbo 默认的非强制标签语义回落到无标主 Beta。

Dubbo 先选择 registry，再在该 registry 内执行标签路由，不会因为所选 Beta registry 中缺少某个标签而返回外层重选生产 registry。因此，三级回落依赖一个运行约束：只要某服务还有任何泳道实例，Beta registry 中必须同时保留该服务的无标签主 Beta 实例。控制器在接受泳道部署前检查对应主 Beta 服务已经有活动实例；泳道实例仍存在时拒绝删除对应主 Beta 服务。整个 Beta registry 对该服务都没有可用提供者时，`zone-aware` 才能选择生产路。

每条实例注册还必须保存：

- `alphafrog.deployment-id`
- `alphafrog.deployment-generation-id`
- `alphafrog.traffic-scope-id`
- `alphafrog.release-id`
- `alphafrog.instance-id`
- `zone=beta`

控制器关闭应用自身的 Dubbo 注册后，必须自行写出消费者可还原为提供者 URL 的 metadata。除上面的部署字段外，至少包括 `protocol=tri`、接口 `path`、`interface`、Dubbo 服务 `group` 与 `version`、应用名 `application`、`category=providers`、`side=provider` 和 `dynamic=true`。这些值由部署单的 `dubboServiceKey` 与 `registration.applicationName` 生成；缺字段或与服务键不一致时，实例不能进入可选状态。

metadata 与部署单、实例记录逐项一致。候选创建时就带最终标签和区参数，但 `enabled=false, weight=0`，健康通过以前不能接流量。

## 5. 创建与更新

### 5.1 创建

创建部署时，控制器依次执行：

1. 校验部署单、端口、镜像、本机环境文件和全局状态版本。
2. 在全局状态中写入创建操作与候选计划。
3. 用确定的容器名、端口槽、部署身份和代际启动候选。
4. 以不可选状态注册候选，并核对唯一实例、完整 metadata 和健康结果。
5. 将候选改为 `enabled=true, weight=1`，再次读取 Nacos 确认。
6. 在一次全局状态替换中把候选提升为活动实例，服务进入 `STABLE`。

首次创建没有旧实例，不需要排空。

### 5.2 更新

更新保持旧实例继续服务，顺序固定为：

1. 旧活动实例保持 `enabled=true, weight=1`。
2. 在另一端口槽启动候选；候选注册为 `enabled=false, weight=0`。
3. 候选健康检查和注册事实全部通过后，进入 `SWITCHING_TRAFFIC` 检查点。
4. 将候选改为 `enabled=true, weight=1`并回读确认。
5. 从 Nacos 注销旧实例，并确认按完整注册键已经查不到它。
6. 在一次全局状态替换中把候选提升为活动实例，把旧实例移入排空记录，写入 `trafficRemovedAt` 与 `registrationRemovedAt`。
7. 向旧容器发送 `SIGTERM`。Agent 立即停止受理新 Run，并在公共处理期限扣除收尾余量后的自然处理窗口内，继续按正常逻辑完成已经受理的工作；默认 60 秒公共期限与 5 秒收尾余量对应 55 秒自然处理窗口。
8. 自然处理窗口结束时，Agent 先把本代仍未结束的 Run 写成明确失败，再用剩余时间完成持久化和进程退出。旧容器仍未在公共处理期限内退出时，容器运行时才强制停止；随后控制器删除旧容器和 Compose 临时文件，服务回到 `STABLE`。

第 5 步是新请求不再进入旧实例的确定边界。控制器不写“默认路由指针”，也不查询或通过 RPC 修改 Agent Run。旧 Agent 先自然处理已经受理的工作，并在自然处理窗口结束时为本代剩余 Run 写入明确失败。若本代收尾未能成功，其他 Agent 只有在 Nacos 连续一个确认期限都找不到目标代际，并在写数据库前再次确认仍无注册后，才分批补写失败；“分批”表示每轮数据库更新有数量上限，不承诺在固定时间内清完。

候选已经被健康检查明确判为失败、注册明确缺失或就绪超时时，控制器清理候选容器和候选注册；旧活动实例始终保持可选。Nacos 查询失败、对象身份冲突等无法确认外部事实的情况保留候选并进入 `FAILED`，等待人工核对后显式重试。候选已经启用但旧注册尚未摘除时发生崩溃，控制器重启后依据操作阶段和两条注册现场继续完成摘除。旧注册已经摘除、但候选尚未在状态文件中提升时发生崩溃，只要操作仍为 `SWITCHING_TRAFFIC`、候选注册唯一且可选健康，控制器就接受这一精确窗口并完成提升；其它缺失活动注册的组合仍进入失败。两种恢复都不得创建第二个候选。

## 6. 删除

删除一个部署时，每个服务执行。若目标是主 Beta，控制器先确认没有任何同服务的泳道实例、候选或排空实例；仍有泳道提供者时拒绝删除主 Beta，以免破坏官方回落链。

1. 持久化 `REMOVING_TRAFFIC` 检查点。
2. 注销活动实例并确认 Nacos 中已经不存在精确注册。
3. 把活动实例移入排空记录，写入 `trafficRemovedAt` 与 `registrationRemovedAt`。
4. 发送 `SIGTERM`，使用同一个公共处理期限等待。
5. 自然退出或到期强停后，删除容器、临时文件和服务状态。

删除不等待 Agent Run 计数，也不取消业务 Run。没有实例注册以后开始的新请求按官方路由落到允许的下一级；如果两级都没有服务提供者，则调用失败。

## 7. 全局状态

每个服务最多保存三个角色：

- `activeInstance`：当前可选实例；
- `candidateInstance`：正在启动或等待切换的不可选实例；
- `drainingInstance`：已经注销、只处理在手工作的旧实例。

排空实例保存 `trafficRemovedAt`、`registrationRemovedAt`、`stopSignalRequestedAt` 和 `stopDeadline`。前两项证明新流量已经切走；后两项记录本次停止请求和公共期限。停止信号发送前先持久化一次截止时间；控制器重启或显式重试时只使用原截止时间计算剩余秒数，截止时间已到就立即强停，不能重新获得完整窗口。状态文件不保存路由版本、默认实例指针或可供业务调用方查询的精确地址。

服务操作阶段限定为：

- 创建：`STARTING_CANDIDATE`、`WAITING_CANDIDATE_READINESS`、`SWITCHING_TRAFFIC`；
- 更新：上述三项加 `DRAINING_PREVIOUS`；
- 删除：`REMOVING_TRAFFIC`、`DRAINING_ACTIVE`。

一次部署只允许一个未完成操作。每次外部副作用前后都重新核对操作标识、状态版本、容器身份和 Nacos 注册事实；发现多对象、身份冲突或无法查询时停止，不猜测成功。

## 8. 容器配置

控制器生成的 Compose 配置必须：

- 使用不可变镜像引用并核对本机 Image ID；
- 注入部署标识、部署代际、泳道范围、发布标识和镜像摘要；
- 注入 `OTEL_SERVICE_NAME` 与包含部署、泳道、版本、提交和本地 Image ID 的五字段 `OTEL_RESOURCE_ATTRIBUTES`；
- frontend 泳道实例启用可信入口打标并注入本部署的泳道名；主 Beta frontend 保持入口打标关闭；
- 使用显式宿主地址、宿主端口、容器端口和 `SIGTERM`；
- 配置 Spring 与 Dubbo 的有序关闭期限；`agent-langchain-service` 使用 0 秒 Spring 后续等待和 5 秒 Dubbo 静态上限，Dubbo 还必须服从进程内的统一截止时间；
- 配置同一台 Nacos 的 Beta、生产两路消费注册，分别带 `zone=beta`、`zone=prod`，Beta 路为 `preferred=true`，消费集群为 `zone-aware`；
- 关闭应用自身的提供者注册，由控制器统一创建、启用和注销实例；

控制器在启动容器前用 `docker compose config` 读取有效配置。有效配置与计划不一致时拒绝启动；不能因为源文件看起来正确就跳过渲染后核对。

## 9. 重启恢复

控制器重启后先读取并校验部署单、全局状态、容器现场和 Nacos 现场：

- 候选计划存在但容器不存在：按确定名称继续创建。
- 候选容器存在但注册未保存：只有容器标签、镜像、端口和代际全部唯一匹配才认领。
- 候选注册已经启用：继续注销旧注册并提升候选；旧注册已经不存在时直接完成同一提升检查点。
- 服务处于排空阶段：旧注册必须已经不存在；旧容器存在则继续等待或停止，不存在则清理记录。
- 注册查询失败、同一身份出现多条实例或 metadata 不一致：进入 `FAILED` 并等待显式重试。

状态文件不得覆盖 Nacos 现场。它只说明控制器准备做什么和已经确认到哪一步。

## 10. 验收

至少覆盖以下行为：

- 主 Beta 注册没有 `tag`，泳道注册的 `tag` 与 `dubbo.tag` 都等于泳道名；全部实例带正确部署身份、完整 Dubbo 提供者 metadata 与 `zone=beta`。
- 候选健康以前不可选；候选失败时旧实例仍可选且没有维护窗口。
- 更新先启用并确认候选，再注销并确认旧实例；状态文件不含默认路由字段。
- 删除先注销注册，再发送停止信号；控制器全程不查询 Run。
- 所有服务共享同一处理期限，排空到期后可以强停；Agent 的 Run 自然处理、失败写入、执行器停止、Dubbo 关闭与 Spring 生命周期不能形成串行重复等待。
- 泳道服务在主 Beta 缺失时不能创建；存在泳道实例时不能删除同服务主 Beta。
- 同一台 Nacos 的两路消费配置、两个 zone、Beta `preferred=true` 和 `zone-aware` 集群进入有效 Compose 配置。
- 控制器重启时能继续候选启用、旧注册摘除和排空；停止截止时间不因重启或重试延长；“候选已启用且旧注册已摘除”的中断窗口能完成状态提升；不创建重复候选，不认领身份不一致对象。
- Nacos 查询失败、注册多条或 metadata 不完整时停止自动动作。

Schema、单元测试和静态 Compose 渲染不能证明真实 Nacos 的标签选择、Dubbo 同区回落、跨机网络和 Docker 信号时序。获准环境的组合验收必须实际观察“泳道→主 Beta→生产”回落、候选失败保持旧服务、自然排空和期限强停。
