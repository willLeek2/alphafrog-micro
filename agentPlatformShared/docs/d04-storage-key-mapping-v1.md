# D04 统一存储路径门面 — 存储键映射与迁移说明 v1

- 归属：26Q3 stage1 W5（task #105），D04「统一存储路径门面」交付件。
- 基线 SHA：`73256d5344a116ba24b031f94d992473917e838b`；branch `ccqwen/260809-26q3-stage1-w5-facade`。
- 代码入口：`agentPlatformShared/src/main/java/world/willfrog/agent/platform/storage/AgentStoragePaths.java`（facade）+ `StorageRootUnavailableException.java`（§4.3 失败信号）。
- 现状审计依据：`notes-w5/D04-path-key-inventory.md`（W5 worktree 内，未跟踪工作底稿；其 K/H/N/R 编号在本文被引用）。
- 现状同步：本次修订对齐 D22-5.1.3 四提交（`ac6d4c16` → `77b95fe5` → `3edca994` → `904a42bc`）后的代码现状，涉及 §3、§5-K3、§7、§9；§10 registry 权威语义进一步同步至第二轮复审修复后的代码（认领原子提交、严格归属校验、有界读取大小上限、幽灵成员自愈）。

---

## 1. 一期范围：四根清单

D04 一期只收敛四个存储根/文件，全部经 `AgentStoragePaths` 单一门面解析：

| 根 | 门面 getter | 语义 |
|---|---|---|
| workspace 根 | `workspaceRoot()` | agent run workspace dump 树（`_by_run_id_index/{runId}` 等） |
| artifact 根 | `artifactRoot()` | persistent artifact registry A 树（`{type}/{type}_{uuid}.txt`） |
| dataset 根 | `datasetRoot()` | market-data dataset 根（`{datasetId}/…`） |
| observability debug 文件 | `observabilityDebugFile()` | JSONL debug sink 文件路径（非目录根） |

一期明确**不做**：对象存储 / 跨机共享根（设计文档 §4.4，留后续 wave）；`agent.llm.prompt-base-dir`（D01 域，不触碰）。

## 2. 解析链

```
新键 agent.storage.*  →  旧键别名（一期兼容）  →  代码默认值 DEFAULT_*
```

- 新键或旧键取值为 `null`/空白 → 视为未设置，继续回退（yml `${ENV:}` 占位在 env 未设时解析为空串，同样按未设置处理）。
- artifactRoot 独用双旧键解析链（D22-5.1.3 K3 收敛，见 §5-K3）：新键 > legacy；新键设置时短路，不读取也不比较任何旧键；新键未设而两 legacy 键同时设置且取值不同 → 启动 fail-closed（抛 `StorageRootUnavailableException`，消息只列键名不列值）。其余三根保持单旧键行为不变。
- 解析发生在 bean 构造期（create-time freeze）：**路径键无 Nacos 热更通道**（审计 R8：Python 动态白名单 `nacos_config.py` `KNOWN_DYNAMIC_KEYS` 与 Java Nacos dataId 均不含路径键），运行期改 Nacos 不会影响已解析根；根迁移只能走重启级发布。
- 所有 getter 返回规范化绝对路径（相对路径按进程 CWD 解析后 `toAbsolutePath().normalize()`）。

## 3. 键映射总表（旧键 / 新键 / 默认值 / env / 兼容期 / 迁移注意）

| 根 | 新键 | 旧键（一期别名） | 代码默认值 | yml env 占位 | 兼容期 | 迁移注意 |
|---|---|---|---|---|---|---|
| workspace | `agent.storage.workspace-root` | `agent.workspace.root` | `/data/agent_workspaces` | `AF_AGENT_STORAGE_WORKSPACE_ROOT` | 旧键长期保留为别名，一期无删除计划 | K1 原无任何 override，唯一消费链为 workspace dump 包；无读回 API，改根不影响既有查询面 |
| artifact | `agent.storage.artifact-root` | `agent.persistent-artifact.root`；`agent.artifact.storage.path`（第二 legacy alias，即 K3，D22-5.1.3 收敛） | `/data/agent_artifacts` | `AF_AGENT_STORAGE_ARTIFACT_ROOT` | 同上；兼容窗口 = 只要 legacy 键仍被任一组件设置即持续生效；退休条件 = 当所有部署环境确认停用 `agent.artifact.storage.path` 与 `agent.persistent-artifact.root`、统一使用新键后，legacy 解析可在后续版本移除 | 优先级新键 > legacy；两 legacy 冲突且新键未设 → 启动 fail closed（抛 `StorageRootUnavailableException`，只列键名不列值）；历史制品不搬迁不删除（已按旧键解析落盘的 artifact 文件保持原位，只读兼容）；Redis meta 存绝对路径（§6-R2） |
| dataset | `agent.storage.dataset-root` | `agent.tools.market-data.dataset.path` | `/data/agent_datasets` | `AF_AGENT_STORAGE_DATASET_ROOT` | 同上；旧键仍是 market-data 工具链的现役键 | H1 硬编码前缀的原属根（§4）；K4 现有 6 处独立 @Value 声明（原 `AgentArtifactService` 一处于 D22-5.1.3 摘除，见 §5-K3/§7），一期只收敛门面触达的 consumer（§7） |
| debug 文件 | `agent.storage.observability-debug-file` | `agent.observability.debug-file.path` | `/data/logs/agent-observability-debug.log` | `AF_AGENT_STORAGE_OBSERVABILITY_DEBUG_FILE` | 同上；伴生开关 `agent.observability.debug-file.enabled` 不变 | 默认路径无 volume 挂载（审计 R9），容器本地写 |

yml 声明位置：`agentLangchainService/src/main/resources/application-agent-platform-shared.yml` `agent.storage.*` 段（四个键默认留空 → 回退旧键 → 回退代码默认值；生产 env 未设时行为与 D04 前完全一致）。

### 3.1 H1：manifest.json refPath 硬编码行（无旧键）

| 位置 | 原实现 | 现实现 |
|---|---|---|
| `WorkspaceManifestWriter.writeManifest`（原 `:147` 行） | `"/data/agent_datasets/" + datasetId` 直接拼接写入 `manifest.json` `assets[].refPath`（**落盘持久化**） | `storagePaths.datasetRoot().resolve(datasetId)`；该前缀此前**无任何配置键（纯硬编码）**，现统一走 dataset 根 |

注：此改动只影响**新写出**的 manifest；已落盘的旧 `refPath` 仍含 `/data/agent_datasets/` 字面量（见 §6-R3）。

## 4. 可达性失败信号（设计文档 §4.3：不静默写错位置）

| 门面方法 | 语义 | 失败信号 |
|---|---|---|
| `requireWritableRoot(Path root, String keyLabel)` | 根存在 → 必须是可写目录；不存在 → `createDirectories` 建立 | 根被普通文件占位 / 建目录失败 / 不可写 → `StorageRootUnavailableException`（消息含键名+路径+原因），ERROR 日志 |
| `verifyDumpTarget(Path target)` | workspace dump 入口校验：先 `requireWritableRoot(workspaceRoot)`，再校验目标在根内（normalized `startsWith`） | 目标越出 workspace 根 → `SecurityException("dump target escapes configured workspace root…")`；根不可达 → `StorageRootUnavailableException` |

接线现状：
- `WorkspaceManifestWriter.writeAll` 入口调用 `verifyDumpTarget(runDir)` —— 越界或根不可达时**先失败、零落盘**。
- `PersistentArtifactRegistry.register` 写前调用 `requireWritableRoot(artifactRoot, KEY_ARTIFACT_ROOT)`。
- `AgentObservabilityDebugFileWriter` 例外：debug sink 是 best-effort 遥测，路径不可达沿用既有 warn-once 降级（不向上传播打断 run），与 D04 前行为一致；该类只换路径解析来源，不改失败语义。

## 5. 一期未纳入的键（及原因）

| 键 | 原因 |
|---|---|
| K3 `agent.artifact.storage.path`（原 `AgentArtifactService` @Value 声明，D22-5.1.3 已摘除） | **已于 D22-5.1.3 收敛**：作为 artifactRoot 的第二 legacy alias（`LEGACY_ARTIFACT_ROOT_SECONDARY`）并入门面解析链。优先级新键 > legacy（新键设置时短路，不读取也不比较旧键）；新键未设而两 legacy 键同时设置且取值不同 → 启动 fail closed（抛 `StorageRootUnavailableException`，消息只列键名不列值）。兼容政策：历史制品不搬迁不删除（已按旧键解析落盘的 artifact 文件保持原位，只读兼容）；兼容窗口 = 只要 legacy 键仍被任一组件设置即持续生效；退休条件 = 当所有部署环境确认停用 `agent.artifact.storage.path` 与 `agent.persistent-artifact.root`、统一使用新键后，legacy 解析可在后续版本移除。K3 与 K2 共树 `/data/agent_artifacts` 但相对路径规则不同（`{runId}/scripts\|datasets/…`，审计 R1）。消费方现状：`AgentArtifactService` 本体不再直读 K3（原 K3/K4 @Value 均已移除，代码中 K3 键仅剩门面一处消费），K3 键由 `AgentStoragePaths` 作为第二 legacy alias 消费；service 经门面注入拿到 artifact 根，list/load 经 `PersistentArtifactRegistry` 权威路径（见 §7、§10），旧快照树仅保留只读回退。 |
| K5 `agent.tools.market-data.dataset.database-fetched-path`（`/data/database_fetched`）、K6 `…manifests-path`（`/data/manifests`） | dataset 域的邻近键，但属四层布局树（写方 `DatasetWriter`/`ManifestWriter`，消费链含 01→02 事件与沙箱 cp），归 D21-B/D22 slice，不与四根门面混改。 |
| K8/K9/K10（llm config-file / prompt-base-dir 族） | D01 域（prompt/config 加载），非存储根。 |
| K11/K12/H3/H4（debug observability 两路 `/app/logs/…`） | 注入方式异构（@ConfigurationProperties / System.getenv），且根在容器内、与四根无共享消费方；留后续收敛。 |
| H2 `/data/agent_guides`（LoadToolGuideTool，ro 挂载） | 只读资源目录，非存储根。 |
| P1–P6（Python 沙箱侧 env 键） | Kimi per-node 工具 slice 范围（W5 消费方门 = 本门面 SHA），不在 Java 门面内。 |

## 6. 迁移风险高亮（根变更前必读；编号沿用审计）

- **R2 Redis meta 存绝对路径**：`agent:persistent-artifact:{id}`（N1）、`dataset:meta:{queryKey}`（N4）、`manifest:meta:{queryKey}`（N6）的 value 内含绝对路径；registry 读取缝隙（`readArtifactBytes`/`readWithinArtifactRoot`）的 root guard 与 cleanup 删文件直接消费这些值。**改根会使存量 meta 指向旧路径**，需先耗尽存量或提供迁移/兼容层（一期未提供重写工具）。
- **R3 磁盘 meta 存绝对路径**：`ManifestWriter` 的 `meta.json`（M1）与 workspace `manifest.json` `assets[].refPath`（M2）已落盘旧前缀；workspace dump 为长期留存产物，无重写机制。
- **R4 在途事件携带绝对路径**：`DatasetPersistedEvent.persistedPath` → `AgentRunDatasetCsvWriter` → `sandbox_runner.py` cp 物化；迁移窗口内的在途 run 携带旧根路径。
- **R5 跨服务共享挂载**：`agent_datasets`/`database_fetched`/`manifests`/`agent_artifacts` 四树同时挂入 langchain 与 python-sandbox 两个容器（compose `:522-525` ↔ `:672-675`）；Java 键与 Python env（P1）分属两套配置体系，改根必须双侧同步。
- **R8 重启级**：无 Nacos 热更通道（见 §2），根切换 = 改配置 + 重启。
- **R9 容器本地根**：debug 文件默认 `/data/logs/…` 与沙箱 `/sandbox/runs` 无 volume 挂载，宿主机无存量可迁移，但也无法从宿主审计。
- DB 侧核验（R13）：`alphafrog_schema_full.sql` 无存量绝对路径列，风险面集中在 Redis meta、磁盘 json 与在途事件。

## 7. Consumer 迁移状态

**已经由门面（D04 本次交付 + D22-5.1.3 增量）**：

| Consumer | 收敛点 |
|---|---|
| `WorkspacePathResolver` | 新增 `@Autowired AgentStoragePaths` 构造器（workspace 根 + dataset 根）；原 2-string @Value 构造器保留供测试 |
| `WorkspaceManifestWriter` | `datasetRoot()` 替换 H1 硬编码；`writeAll` 入口 `verifyDumpTarget` |
| `PersistentArtifactRegistry` | `artifactRoot()` 替换 @Value；`register` 前 `requireWritableRoot` |
| `AgentObservabilityDebugFileWriter` | `observabilityDebugFile()` 替换 @Value 路径（best-effort 语义不变） |
| `AgentArtifactService` | D22-5.1.3 切换：原 K3/K4 @Value 摘除，artifact 根 / dataset 根经 `AgentStoragePaths` 注入；list/load 经 `PersistentArtifactRegistry`（registry-first + 惰性幂等注册），legacy Base64 ID 仅只读回退（见 §5-K3、§10） |

**仍直读、由后续 slice 收敛**：`DatasetRegistry`/`DatasetWriter`/`ManifestWriter`（K4–K6，D21-B）、`WorkspaceHealthVerifier`/`WorkspaceAssetCollector`（K4，D21-A 协同；后者为 dead injection，审计 R11）、Python 侧 P1–P6（Kimi slice）、`DebugObservabilityService`/frontend debug 两路（K11/K12）。（`AgentArtifactService` 已于 D22-5.1.3 移出本清单：经 `AgentStoragePaths` + `PersistentArtifactRegistry` 切换完成，见上表。）

## 8. §4.1 单点切换运维口径

切换任一存储根的操作面 = **一个新键（或对应 `AF_AGENT_STORAGE_*` env）+ 一次重启**：

1. 设 `AF_AGENT_STORAGE_{WORKSPACE_ROOT|ARTIFACT_ROOT|DATASET_ROOT|OBSERVABILITY_DEBUG_FILE}`（compose env 注入）；未设时行为逐层回退，与 D04 前一致。
2. 重启相关服务（路径键 create-time freeze，重启前不生效）。
3. 已收敛 consumer（§7 表一）随门面整体切换，无需逐类改配置。
4. 跨容器共享树（R5）须同步挂载与 Python env；存量 meta 风险按 §6 评估。

## 9. 验证

- `AgentStoragePathsTest`（agentPlatformShared，18 例）：默认值 / 旧键别名 / 新键优先 / 空白回退 / dataset 旧键=market-data 键 / artifactRoot 第二 legacy alias（K3 单设、双 legacy 同值、新键短路于双 legacy 冲突、双 legacy 冲突 fail-closed 且消息只列键名不列值）/ `requireWritableRoot` 建目录与两类失败 / `verifyDumpTarget` 越界与 null / 显式构造器归一化与空白拒绝。
- `WorkspaceManifestWriterTest`（agentLangchainService，11 例；D04 原有行为用例如下，其余为 D21-A dump 减量写入/state 增量）：refPath 走配置根（断言不再出现 `/data/agent_datasets` 前缀）/ 越界目标 SecurityException 且零落盘 / 根不可达 StorageRootUnavailableException / 既有五文件契约不回归。
- `ToolOutputRefServiceImplTest`（agentPlatformShared，9 例）：改为经显式构造器注入门面根（替代原 @Value 反射注入），行为断言不变；D22-5.1.3 增显式上下文 overload 用例（不依赖 AgentContext 线程态）；MUST-FIX 两轮合计钉住 2 例——显式入口调用方上下文缺失即拒（fail-closed）/ 无上下文历史制品在依赖 AgentContext 线程态的旧入口与显式入口一律严格拒绝（宽容 matcher 已删除，后者为第二轮替换原「宽容放行」用例而来）。
- `PersistentArtifactRegistryTest`（agentPlatformShared，30 例，D22-5.1.3 新增、MUST-FIX 三轮扩充）：registry 契约钉住——显式注册可 list/download / legacy AgentContext 入口兼容 / 幂等重复注册复用同一 artifactId 且零重写 / 幂等入口强制 runId / 跨 run/user 拒绝与 meta 缺上下文一律严格拒绝（fail-closed） / external 越根/traversal/symlink 逃逸拒绝且零落盘 / cleanup 同删 meta+文件+run 索引+身份字段 / cleanup SCAN 跳过索引键 / listByRunId 幽灵成员读取侧自愈 SREM（第二轮强化）。MUST-FIX 新增反测共 18 例（第一轮 8 + 第二轮 5 + 第三轮 5），第一轮覆盖：① 并发幂等注册单一赢家 + 陈旧身份字段值条件清除并重试注册；② 有界索引超限原子拒绝并回滚 + cap<=0 fail-closed + 并发注册永不溢出 cap；③ 跨 run/user strict 矩阵（既有用例更新）；④ 注册后 symlink swap / 内容篡改读取侧 fail-closed；⑤ 身份字段编码无碰撞。第二轮新增 5 例覆盖：⑥ FULL（容量满）拒绝后不留幽灵身份/索引痕迹且重试仍稳定拒绝；⑦ 输家认领时赢家身份+run 列表必然已原子提交（输家 adoption 即读到赢家在列表内）；⑧ 有界流式读取超上限即拒（即使绕过 Files.size 预检也拦得住）；⑨ 父目录被换成符号链接指向根外（TOCTOU）读取侧拒绝；⑩ 幽灵成员填满 cap 时有界清理后新注册恢复成功。第三轮新增 5 例覆盖：⑪ 幽灵清理进展保证——cap=200>预算 128、第一个排序窗口（前 128 个成员）全是活成员、幽灵排在最后：第一次注册 FULL 且游标键记录轮转位置，第二次注册 ADDED、幽灵被清、游标键归零删除（钉住旧实现「每次重复检查同一批活成员、幽灵永远清不到」的缺陷）；⑫ 脚本字面静态证明——两段 Lua 脚本文本含 `sscan`、不含 `smembers`（全量取 SET 的命令已从脚本中消失），游标键在 KEYS 里；⑬ 读侧 touch 滑动——注册时 meta/身份/列表三键 TTL 截止点对齐，时钟推进后 readContent 使三键截止点一起向后滑动；⑭ TTL 漂移防护——滑动后同身份幂等复用返回同一 artifactId（不产生第二个 ID、不重写文件），meta 真过期后重新认领才换新 ID 且列表仍只 1 项；⑮ EXISTS 分支修复——手工把赢家从 run 列表 SET 删掉后幂等重注册，返回同一 artifactId 且列表成员被 SADD 补回（钉住「身份记着赢家、列表却没有赢家」的异常不再被采纳）。
- `AgentArtifactServiceTest`（agentPlatformShared，8 例，D22-5.1.3 新增）：facade 契约钉住——registry 注册项 DTO 映射 / 事件派生项惰性幂等注册 / registry-first 读取 / legacy Base64 只读回退 / 跨 run 访问拒绝 / retention 两档与 success-only 过滤保持 / download 大小上限仅下载面生效。
- 红线自检：diff 无新增 `/data/agent_*` 硬编码（`DEFAULT_*` 常量为四根既有默认值的集中化，属现状继承）；四根清单齐全（§1）；可达性失败信号齐全（§4）；未触碰 `agent.llm.prompt-base-dir`（D01）；未引入对象存储/跨机共享（§4.4）。

## 10. D22-5.1.3 后的 registry 权威语义（合同层面）

D22-5.1.3 四提交与 codex MUST-FIX 修正（含第二轮、第三轮复审修复）后，持久制品域形成「单一权威 registry + user API 门面」结构，代码入口为 `PersistentArtifactRegistry.java` 与降级后的 `AgentArtifactService.java`：

- **唯一权威**：`PersistentArtifactRegistry` 是唯一权威 registry——注册、元数据（Redis）、文件落盘、run 级有界索引、归属校验、TTL 清理与内容哈希校验均在此收口。`AgentArtifactService` 降级为 user API facade + legacy 适配器：不再自管存储（原 K3/K4 @Value 摘除，根一律经 `AgentStoragePaths`），list/load 走 registry-first，仅保留历史 Base64 ID 的只读回退。
- **显式上下文入口**：`registerExplicit` / `registerExternalExplicit`（非幂等，每次调用生成新 artifactId；rawRef 逐条注册经 `RunRawRefStoreImpl` 走这里，其 logicalId 固定为 runId，不能走幂等路径）与 `registerIdempotent` / `registerExternalIdempotent`（幂等），runId/userId 均显式传参，不依赖 `AgentContext` 线程态；幂等入口 runId 为空即抛 `IllegalArgumentException`。旧 `register`/`registerExternal` 保留为有界兼容 delegate：从 `AgentContext` 线程态补齐上下文后转调显式入口。
- **幂等身份去重**：Redis hash `agent:persistent-artifact:run-identity:{runId}`，field 为**无碰撞身份串**——各段按 `长度:值|` 编码，`identityField(artifactType, logicalId, externalPath)`（内容制品路径段为空；长度前缀保证不同 (type, logicalId, path) 组合不可能拼出同一 field）。registry 与 facade **共用这唯一 public static helper**：facade 的 `candidateIdentityOf`/`registryIdentityOf` 直接委托 `PersistentArtifactRegistry.identityField(...)`，不再存在第二份格式实现。认领协议：候选文件 + meta 先行写入，随后经**一条 Redis Lua 脚本原子提交**完成认领（Redis 单线程执行脚本，脚本内所有步骤要么全部生效、要么全部不生效）——脚本 KEYS 为三个键：身份哈希、run 列表 SET、幽灵清理游标键 `agent:persistent-artifact:run-purge-cursor:{runId}`。脚本内依次做五件事：①查身份哈希（按内容身份去重的 Redis 散列）上该身份字段是否已有赢家；②有赢家时，若赢家 meta 仍活则**修复赢家的 run 列表成员资格**（缺失即 SADD 补回）并对身份/列表两个索引键做只延长不缩短的 TTL 刷新，返回 EXISTS:赢家ID——该修复消除「身份记着赢家、run 列表却没有赢家」（历史 TTL 漂移或外部干预造成）的异常被输家原样采纳的可能；③无赢家则游标轮转有界清理 run 列表 SET 中的幽灵成员（见下方幽灵成员自愈条目）；④用 SCARD 检查 run 列表 SET 是否已满（容量上限为 `agent.persistent-artifact.run-list-cap`）；⑤未满则 HSET 身份字段 + SADD run 列表 + 对两个索引键做只延长不缩短的 TTL 刷新，全部在同一次脚本执行里完成。不存在「身份已写、列表未写」或「列表已写、容量未查」的中间窗口。赢家的索引键 TTL 延长不再是提交成功后的 Java 侧后补调用，而是在脚本内完成（只延长不缩短：脚本内比较当前剩余 TTL，仅在不足时才刷新，避免短 TTL 覆盖长 TTL）。竞争输家只有在脚本返回 EXISTS（已有赢家）时才接管赢家的 artifactId——EXISTS 意味着赢家的身份项在盘，且脚本刚在同一次执行里确认/修复了赢家的列表成员资格，所以输家不可能拿到「幽灵 ID」（身份存在但 run 列表缺失的 ID）；输家回滚自己的候选 meta 与文件（脚本对输家不写任何索引）后接管赢家 artifactId：赢家 meta 仍在则直接 adopt 其既有注册（零重写）；meta 恰被 TTL/并发 cleanup 清掉则经 **Lua 值条件 HDEL 脚本**原子清除陈旧字段（仅当 field 值仍等于旧 artifactId 才删，避免与并发新认领 ABA），然后重试注册；至多 3 轮仍不收敛抛 `IllegalStateException`。容量不足时脚本返回 FULL 且不写任何索引，Java 侧回滚候选文件与元数据（超限语义详见下方 run 有界清单条目）。不变量：identity=X ⇒ X 的 meta 必在认领前已写入，故 find(X) miss 即代表已被清理，清字段重试安全。
- **run 作用域有界清单 `listByRunId`**：run 索引 SET `agent:persistent-artifact:run-list:{runId}`，cap = `agent.persistent-artifact.run-list-cap`（默认 1000）。**超限语义 = 注册原子拒绝并回滚（可见失败）**：容量检查与索引写入在同一条 Redis Lua 脚本内完成——幂等注册走上述认领脚本（游标轮转幽灵清理、SCARD 容量检查、HSET 身份字段 + SADD run 列表 + 只延长不缩短 TTL 刷新同脚本完成）；非幂等注册走另一条更小的 Lua 脚本（KEYS=[列表 SET, 清理游标键]，游标轮转幽灵清理 + 容量检查 + SADD + 同款 TTL 刷新），同样要么全部生效、要么全部不生效。已满时脚本返回 FULL 且不写任何索引，Java 侧抛 `IllegalStateException` 并回滚候选 meta 与文件；不存在「meta-only 成功、仅跳过索引」的静默路径。原先「先 SADD → SCARD 查容量 → 超限再 SREM」的多命令序列已彻底移除，不再存在「列表已写、容量未查」或「先写后撤」的中间状态。**cap<=0 一律 fail-closed**（直接抛异常，禁止退化为不限长）。并发保守语义：同 cap 下的竞争者可能同时被拒，但索引大小永不超过 cap。`listByRunId` 只读、不生成新 ID：读索引 → 逐条取 meta → 滤掉 meta 缺失（Redis TTL 到期或已清理）与 runId 不符的陈旧项，并对发现 meta 缺失的成员顺手 SREM（幽灵成员有界自愈，见下条），按创建时间升序返回。
- **幽灵成员自愈（有界 + 进展保证）**：run 列表 SET 里可能出现「幽灵成员」——成员 ID 还留在 SET 里，但对应的 Redis 元数据键已经不存在（典型原因：元数据键自带 TTL 到期被 Redis 自动删除，而 SET 成员没有 TTL）。幽灵成员让 SCARD 计数虚高，导致 run 列表明明没满却持续报容量超限。修复：上述两条 Lua 脚本在容量检查前都做**有界游标轮转幽灵清理**——脚本不全量物化 SET（不使用 SMEMBERS 这类把整个集合一次取回的命令）；每个 run 有一个独立的清理游标键 `agent:persistent-artifact:run-purge-cursor:{runId}`，记录下一次轮转扫描的出发点（键不存在或归零即从头扫）。每次脚本执行只从游标位置起检查接下来的 GHOST_PURGE_BUDGET 个成员（固定预算常量，当前值 128；窗口取排序快照上的 [游标, 游标+预算)），元数据键不存在的成员当场 SREM 移除；扫完一整轮（窗口到达末尾）删除游标键，下次从头开始。两条保证：①单次执行有界——至多检查预算数个成员，脚本开销不随列表规模增长；②进展有保证——每次执行从上一次停下的位置接续扫描，一整轮必然覆盖每个成员，因此即使前若干个窗口全是活成员、幽灵排在后面，幽灵也会在至多 ceil(列表规模/预算) 次写操作内被清掉，不会出现「每次都重复检查同一批活成员、永远轮不到幽灵」的停滞。游标键自身 TTL 与索引键同步滑动（脚本内同款只延长不缩短刷新），不会比索引键活得更久；cleanup 的 SCAN 按前缀显式跳过游标键。另外 `listByRunId` 遍历时发现缺元数据的成员也会顺手 SREM。因此幽灵成员不会永久占用容量配额，新注册可以在幽灵被清理后恢复成功。
- **归属校验（一律严格，宽容 matcher 已删除）**：所有用户/工具可达的读取与定位入口现在一律走同一个严格归属校验——无论是从 `AgentContext` 线程态补上下文的旧入口（`ToolOutputRefServiceImpl` 依赖线程态的旧 read/locatorFor 入口），还是显式传入 runId/userId 的入口（`AgentArtifactService` list/load 与 `ToolOutputRefServiceImpl` 的显式上下文 overload read/locatorFor）。校验规则：制品元数据的 runId/userId 与调用方的 runId/userId，四个值都必须非空白且两两相等，否则抛 `IllegalArgumentException`（fail-closed）。宽容校验方法 `matchesOwnerLenient`（任一边为空则该侧不校验）已从生产代码中彻底删除；历史遗留的「无上下文制品」（meta 里没有 runId/userId）现在经任何入口都拒绝读取。跨 run/跨 user 访问一律拒绝。**短格式 raw_ref（`raw_ref_001` 形态）同此合同**：reread 工具读短格式时显式携带当前 runId 与 userId 两个上下文进入 `RunRawRefStore`；映射层（短 ID → artifactId 的 Redis hash）只能证明该短 ID 在此 run 下注册过，不足以放行内容——内容读取一律经 registry 的 `readContentStrict` 做上述四值严格归属校验，userId 空白或不匹配的调用方 fail-closed 被拒。短格式不存在只凭 runId 放行的读取路径。
- **external 路径门槛**：只接受 D04 批准根（artifactRoot / datasetRoot）内路径。注册时先做规范化 containment 校验，越根或 traversal 抛 `SecurityException`（零落盘）；已存在的路径额外 `toRealPath()` 跟随 symlink 复检真实位置（批准根同样解析 symlink 后比较，如 macOS `/var` → `/private/var`），拒绝 symlink 逃逸。**读取侧同样复检（TOCTOU 强化）**：registry 提供权威读取缝隙 `readArtifactBytes(artifactId, maxBytes)` 与 `readWithinArtifactRoot(path, maxBytes)`——先对路径与批准根做 `toRealPath()` 真实 containment 复检（fail-closed），再以 `NOFOLLOW_LINKS` 打开**原始路径**读取（注册后把路径换成 symlink 的攻击会在 open 时失败）；大小上限由两层构成——①快速失败预检查：`Files.size` 超过上限直接拒绝；②权威的有界流式读取：从输入流最多读 maxBytes+1 字节，一旦读到第 maxBytes+1 个字节就拒绝——这样即使文件在预检查与实际读取之间被增大（TOCTOU，检查时间与使用时间不一致的攻击），内存里也最多只分配 maxBytes+1 字节，不会出现「预检查通过后将任意大文件整个读入内存」；内容哈希校验在读完之后照常进行；facade 的 registry 制品下载与 legacy 文件回退一律委托这两条缝隙，不再直接按 meta.path 自行读取。注册时校验 + 读取时复检双重收窄，TOCTOU 窗口不再只依赖注册时点。
- **TTL 与清理**：facade 惰性注册 TTL = max(normalRetentionDays, adminRetentionDays)×24h，两档均 <=0（永不过期）时取 365d 上界（meta TTL 无法表达无限）；registry 侧 ttlHours<=0 回退 `agent.persistent-artifact.ttl-hours`（默认 12h）。索引键 TTL 维护只延长不缩短，且按**统一滑动过期协议**对齐：meta、身份哈希、run 列表、清理游标四类键以同一 ttlHours 为寿命基准——注册侧由 Lua 脚本在写入（CLAIMED/ADDED）与 EXISTS 路径内做只延长不缩短的 TTL 刷新（脚本内比较剩余 TTL，仅在不足时延长，短 TTL 不会覆盖长 TTL）；读侧 `touch()` 重写 meta 的同时经 `extendTtlIfNeeded` 延长身份/列表两个索引键；游标键在脚本写入时同款刷新。因此不存在「索引键先于 meta 过期」的窗口（索引先过期会让活成员从 run 列表丢失、listByRunId 看不见 meta 仍在的制品）。cleanup（@Scheduled，默认 5min 间隔）SCAN `agent:persistent-artifact:*` 并按前缀显式跳过 run 索引/身份/游标键；过期项同删 meta + 文件 + run 索引项 + 条件清身份字段（身份字段仅在仍指向本 artifactId 时删，避免误删并发新注册抢占的字段）。文件侧：内容制品只删 artifact 根内文件；external 仅当 cleanupPath 标记且为 symlink 时删链接本体，幂等 external 固定 cleanupPath=false，清理不动底层文件。
- **legacy Base64 回退**：`type|runId|ref` 旧格式 ID 只在 registry miss 时触发、只读、零写入零注册——按旧快照位置（`{artifactRoot}/{runId}/scripts|datasets/…`）读取，且要求 runId 相等与事件侧重放命中（retention 两档与 success-only 过滤语义保持）；历史制品不搬迁不删除。
