# D22 金融运行时边界 — agentPlatformShared 允许驻留清单 v1

- 归属：26Q3 stage1 W5（task #105），D22「平台边界清理」§4.1（金融运行时边界，源条款 5.1.1）交付件。
- 基线 SHA：`ab895e8c1f2cb938862447e06aca9d4d2765b8e9`；branch `kimi/260809-26q3-stage1-w5-d22`。
- 代码入口：`world.willfrog.agent.platform.finance.boundary.FinanceSharedResidenceAllowlist`（封闭清单）+ `FinanceSharedResidenceArchTest`（fail-closed 架构测试，同包 test 侧）。
- 关联：D22 计划文档 §4.1 / §6 红线 1；codex 裁定（48b1447e：清单=封闭集合非先例；a81f8a99：交付物=文档+静态精确 FQCN 清单+架构测试）；D04（存储门面，同属边界治理，无文件重叠）。

---

## 1. 决策：选「允许驻留清单」路径，且清单是封闭集合

D22 §4.1 给出两条可选路径：①外迁接缝（shared 只留 SPI/DTO，实现迁业务模块）；②明确允许驻留清单 + 禁止继续扩张规则。本交付选 **②**，理由：

1. **§4.1 顾虑的受害者当前不存在。** 全仓库扫描实证：import `world.willfrog.agent.platform.finance` 的模块只有 agentToolsShared 与 agentLangchainService 两个，二者本就重依赖 agentPlatformShared；没有任何轻量网关/前端模块被迫编译进金融持久化实现（§4.1 顾虑「轻量模块引入 shared 即拖入重依赖」的现实情形）。agentApi、adminApi、adminService、common 等模块均无该包 import。
2. **外迁成本与收益不匹配。** 外迁需新增 SPI 模块并搬迁 32 个生产类，在单机阶段（frog 已定今年不上集群）收益不抵成本与回归风险。
3. **先挡住腐烂。** 封闭清单 + fail-closed 架构测试把 D22 §6 红线 1（无审批新增金融 Mapper/运行时进入 shared）变成可机械执行的门禁；外迁选项不因本清单关闭，外迁触发条件见 §2 各簇。

**清单性质**：封闭集合、逐项精确 FQCN、**不构成先例**——某类在清单内，不意味着「同类」新类可参照进入；新金融类进入 shared 一律走 §3 审批。

## 2. 允许驻留清单（FQCN 逐项）

共 33 项（32 个既有金融类 + 1 个边界治理工件）。按职责分五簇；「外迁触发」列只写该簇特有的触发条件，通用触发条件（出现第三个消费模块且其不应重依赖 shared / 金融持久化拆独立模块立项）见簇头。

### 簇 A：金融方法解析（resolver）契约与持久化 — 归属：金融方法建议链路（resolveFinanceMethods）

| FQCN | 类型 | 职责 | 依赖方向 / 消费者 | 外迁触发 |
|---|---|---|---|---|
| `...platform.finance.FinanceMethodResolverClient` | 接口 | 轻量模型解析客户端窄接口，返回原始 JSON 或技术错误分类 | agentToolsShared:FinanceMethodTools 注入并调用；实现为本模块 FinanceMethodResolverModelService | 实现外迁时接口随迁 |
| `...platform.finance.FinanceMethodResolutionSink` | 接口 | 解析快照批量原子保存的 SPI | agentToolsShared:FinanceMethodTools 注入；实现为本模块 FinanceMethodResolutionPersistenceSink | 同上 |
| `...platform.finance.FinanceMethodResolutionSinkException` | 异常 | Sink 保存失败专用运行时异常 | agentToolsShared:FinanceMethodTools 捕获 | 随 Sink 接口 |
| `...platform.finance.FinanceMethodResolutionSnapshot` | DTO | 一次解析器调用的候选方法建议快照（含身份键/路由） | agentToolsShared:FinanceMethodTools 组装并交给 Sink | 随解析契约面 |
| `...platform.finance.FinanceMethodResolution` | DTO | 持久化后的单次解析建议记录 | agentLangchainService:FinanceResultComposer 按身份键查询消费 | 随查询面 |
| `...platform.finance.FinanceMethodResolutionQuery` | 实现类 | 按 runId/身份键查询已持久化建议 | agentLangchainService:FinanceResultComposer 消费 | 持久化拆模块时随迁 |
| `...platform.finance.FinanceMethodResolutionPersister` | 实现类 | 单事务原子写入一批建议 + 内容摘要幂等 | 仅包内（被 PersistenceSink 委托） | 随持久化拆模块 |
| `...platform.finance.FinanceMethodResolutionPersistenceSink` | 实现类 | 快照批量转 DB 行并委托事务化持久化 | 仅包内（Sink 接口的实现） | 同上 |
| `...platform.mapper.FinanceMethodResolutionMapper` | Mapper | 映射表 `alphafrog_agent_finance_method_resolution`（insertIgnore/findExact/listByRun） | 仅本模块：Persister/Query 注入；XML 在同模块 resources/mapper/ | 持久化拆独立库/模块时随迁 |
| `...platform.service.FinanceMethodResolverModelService` | 实现类 | ResolverClient 实现：构建专用 ChatModel、组 system prompt、fail-closed 技术预检 | 仅本模块注入；接口被 agentToolsShared 消费 | 解析实现外迁时随迁 |
| `...platform.service.FinanceMethodResolverModelResolver` | 实现类 | 解析 resolver 应使用的 LLM 配置（stage 配置→默认路由） | 仅本模块：被 ModelService 注入 | 同上 |

簇 A 驻留理由：Sink/ResolverClient 两个接口是「工具模块调用、平台模块实现」的契约面，DTO 被 langchain 结果编排查询消费，放任一业务模块都会造成另一模块反向依赖；MyBatis Mapper 扫描与事务装配集中在 platform 持久层，单独拆出需独立持久化配置。

### 簇 B：金融记录审计通道（executePython stdout 标记 → 校验 → DB）— 归属：金融记录通道

| FQCN | 类型 | 职责 | 依赖方向 / 消费者 | 外迁触发 |
|---|---|---|---|---|
| `...platform.finance.FinanceRecordChannelProcessor` | 实现类 | 同步/异步解析金融标记、校验、关联解析建议并持久化 | agentToolsShared:PythonSandboxTools（同步链）与 agentLangchainService:ToolJobFinalizer（异步链）双链注入 | 通道改独立服务时随迁 |
| `...platform.finance.FinanceRecordDecoder` | 实现类 | 从 stdout 提取 `__AF_FINANCE_RESULT_v1__` 标记行并算原始摘要 | agentLangchainService:ToolJobFinalizer 引用标记常量 | 随通道 |
| `...platform.finance.FinanceRecordSchemaValidator` | 实现类 | 金融记录 JSON Schema 校验 + 有限数值检查 | 仅包内（被 Processor 使用） | 随通道 |
| `...platform.finance.FinanceRecordPersister` | 实现类 | 事务 + 幂等写入一个批次及其记录 | 仅包内（被 Processor 委托） | 随持久化拆模块 |
| `...platform.finance.FinanceRecordQuery` | 实现类 | 按 runId/批次查询 renderable 记录 | agentLangchainService:FinanceResultComposer 消费 | 随查询面 |
| `...platform.finance.FinanceRecordProcessingException` | 异常 | 通道处理失败的 fail-closed 错误（含业务错误码） | agentToolsShared:PythonSandboxTools 与 agentLangchainService:ToolJobFinalizer 捕获 | 随通道 |
| `...platform.finance.FinanceRecordExtractionRequest` | DTO | 提取所需完整输入（身份/输出/环境/上限） | 双链（PythonSandboxTools、ToolJobFinalizer）组装 | 随通道 |
| `...platform.finance.FinanceRecordExtractionResult` | DTO | 处理器输出（批次/记录/去标记 stdout/模型提示） | 双链 + agentToolsShared:FinanceResultModelAdapter 消费 | 随通道 |
| `...platform.finance.FinanceToolResultFormatter` | 实现类 | 金融结果投影为有界、模型可见的 executePython JSON（成功/失败格式） | 双链 + FinanceResultModelAdapter 消费（双模块实证） | 随通道 |
| `...platform.finance.FinanceMetricRecord` | DTO | 一条持久化金融指标审计记录 | FinanceResultModelAdapter 与 FinanceResultComposer 双模块消费 | 随记录契约 |
| `...platform.finance.FinanceRecordBatch` | DTO | 一次 executePython 调用的金融记录批次审计行 | 仅包内（ExtractionResult/Persister/Mapper） | 随记录契约 |
| `...platform.mapper.FinanceMetricRecordMapper` | Mapper | 映射表 `alphafrog_agent_finance_record` | 仅本模块：Persister/Query 注入 | 随持久化拆模块 |
| `...platform.mapper.FinanceRecordBatchMapper` | Mapper | 映射表 `alphafrog_agent_finance_record_batch` | 仅本模块：Persister 注入 | 同上 |

簇 B 驻留理由：处理器与格式化器被同步（agentToolsShared）与异步（agentLangchainService）两条执行链共同注入，这是它们留在共享层的直接原因；DTO 为双链交换格式；Mapper 理由同簇 A。

### 簇 C：记录通道配置与环境事实 — 归属：金融记录通道配置面

| FQCN | 类型 | 职责 | 依赖方向 / 消费者 | 外迁触发 |
|---|---|---|---|---|
| `...platform.finance.FinanceRecordChannelProperties` | 配置属性类 | 通道 Spring 配置属性与代码硬上限 | 本模块 AiConfig 启用；agentToolsShared 测试引用 | 随通道配置面 |
| `...platform.finance.FinanceRecordChannelConfigLoader` | 实现类 | Nacos JSON/默认值加载通道配置并按硬上限钳制 | agentToolsShared:PythonSandboxTools、FinanceRecordChannelTargetEnvironmentProvider 与 agentLangchainService:ToolJobFinalizer 消费 | 同上 |
| `...platform.finance.FinanceRecordChannelLimits` | DTO | 冻结到 run/工具任务锚点的通道上限 | 主代码经 ConfigLoader.Snapshot 间接持有 | 同上 |
| `...platform.finance.FinanceRecordChannelMetadata` | DTO | Proto 通道元数据（记录数/字节/摘要/截断标志） | agentToolsShared:FinanceRecordProtoAdapter 消费 | 同上 |
| `...platform.finance.FinanceRecordChannelObservability` | 实现类 | 通道低基数指标（持久化结果/跨环境/处理失败） | 仅包内（被 Processor 使用） | 随通道 |
| `...platform.finance.FinanceEnvironmentFact` | DTO | 沙箱执行环境与目标环境的镜像/库/包 API 清单 | FinanceRecordProtoAdapter、FinanceRecordChannelTargetEnvironmentProvider、ToolJobFinalizer 消费 | 随环境核验面 |

簇 C 驻留理由：配置加载与环境事实 DTO 同样被双链共用；与簇 B 同生命周期。

### 簇 D：环境证据核验 — 归属：金融记录证据分级

| FQCN | 类型 | 职责 | 依赖方向 / 消费者 | 外迁触发 |
|---|---|---|---|---|
| `...platform.finance.FinanceEnvironmentVerifier` | 实现类 | 对比声明证据等级、实际执行环境与目标环境，判定兼容性并给出有效证据等级 | 仅包内（被 Processor 使用） | 随通道 |
| `...platform.finance.FinanceEvidenceLevel` | 枚举 | 证据等级：库调用声明/带校验自定义/未校验自定义 | 仅包内（Verifier/Processor） | 随通道 |

簇 D 驻留理由：核验逻辑仅包内消费，但与簇 B 处理管线强内聚，单独拆出只会制造跨模块包循环风险。

### 簇 E：边界治理工件 — 归属：D22 §4.1 自身

| FQCN | 类型 | 职责 | 依赖方向 / 消费者 | 外迁触发 |
|---|---|---|---|---|
| `...platform.finance.boundary.FinanceSharedResidenceAllowlist` | 常量类 | 本封闭清单的代码载体，供架构测试与运行时查阅 | 本模块架构测试 | 清单机制废弃时 |

## 3. 禁止继续扩张规则（D22 §6 红线 1 的执行口径）

1. **新金融类默认禁止进入 agentPlatformShared。** 金融类识别口径（与架构测试一致）：简单类名以 `Finance` 开头，或位于含 `.finance.` 包段的包下。
2. **命名约定强制。** 凡新增金融类，无论目标模块，必须遵守上述命名口径——否则架构测试在原理上无法识别，此时拦截义务完全落在评审人身上（架构测试覆盖边界见 §4 诚实声明）。
3. **例外审批形式。** 确需进入 shared 的例外，必须先产出本文档的下一版本（v2+，逐项说明理由）并同步更新 `FinanceSharedResidenceAllowlist` 与架构测试预期——此即「D22 parent 审批」的落地形式；先斩后奏会被架构测试直接挡下。
4. **不得以「只是个小 DTO/工具类」规避。** 凡口径命中，一律先审批后进入；DTO 与 Mapper 同权。

## 4. 架构测试机制与覆盖边界（诚实声明）

`FinanceSharedResidenceArchTest`（agentPlatformShared test 侧，与本清单同交付）：

- **扫描面**：本模块编译产物 `target/classes` 下 `world/willfrog/agent/platform/**` 的全部主类（排除 `$` 内部类，内部类随外层条目覆盖）。
- **判定**：识别口径命中的类集合必须与封闭清单**精确相等**——多出（未审批扩张）或少于（清单陈旧）都失败；扫描目录缺失或识别结果为空同样失败（防止扫描失效导致测试空转假绿）。
- **反测实证**：交付验证时临时加入假想的 `mapper/FinanceEvilUnapprovedMapper.java`，测试按预期失败并在报错中点出该类名；文件与编译产物已删除，复跑恢复全绿。
- **不覆盖什么**：①故意绕过命名约定的金融类（如把 `CagrCalculator` 放进 `platform.util`）测试不可见，依赖评审；②agentToolsShared / agentLangchainService 各自模块内的 finance 包不在本测试面内（属各自模块边界事项，见 §6）；③编译产物之外的源码形态变化（注释/文档）不在测试面内。

## 5. 与 D22 计划文档的对应

- **§4.1 二选一**：选清单路径；本文档 §2 即「边界文档列出允许驻留类型」，§3 即「禁止继续扩张规则」，§4 即「阻断新的金融 Mapper 无审批进入 shared」的机制。
- **§6 红线 1 审查判定**「变更 diff 无未文档化的 finance 扩张」：由架构测试在本地/CI 门禁直接机械判定，不再依赖评审肉眼。
- **§4.1 轻量模块顾虑**：现状扫描证明无轻量模块消费该包；未来一旦出现，按 §2 通用外迁触发条款启动外迁评估。

## 6. 边界（本交付不做什么）

不重写/修改 D04（存储门面）、D21（终态发布）、D25（集群扩展）；不改任何生产逻辑；不处理 agentToolsShared `tools/finance` 包与 agentLangchainService `finance` 包的模块内边界（归各自模块的边界事项，不在 D22 §4.1 的 shared 驻留口径内）；不做 §4.4 PACKAGE_BOUNDARY 重写（D22 另一子项）；不新建 SPI 模块（外迁路径未选）。

## 7. 验证

- `FinanceSharedResidenceArchTest` 4 例全绿（精确相等 / 清单 FQCN 形态 / 假想扩张识别反测 / 治理工件自指可加载）。
- 假想未审批 Mapper 反测：临时文件触发的预期失败已实证（报错含类名），清理后复跑全绿。
- 三模块全量门禁与行尾空格检查：见本切片交接消息（数字以 surefire XML 为准）。
