# D04 统一存储路径门面 — 存储键映射与迁移说明 v1

- 归属：26Q3 stage1 W5（task #105），D04「统一存储路径门面」交付件。
- 基线 SHA：`73256d5344a116ba24b031f94d992473917e838b`；branch `ccqwen/260809-26q3-stage1-w5-facade`。
- 代码入口：`agentPlatformShared/src/main/java/world/willfrog/agent/platform/storage/AgentStoragePaths.java`（facade）+ `StorageRootUnavailableException.java`（§4.3 失败信号）。
- 现状审计依据：`notes-w5/D04-path-key-inventory.md`（W5 worktree 内，未跟踪工作底稿；其 K/H/N/R 编号在本文被引用）。
- 现状同步：本次修订对齐 D22-5.1.3 四提交（`ac6d4c16` → `77b95fe5` → `3edca994` → `904a42bc`）后的代码现状，涉及 §3、§5-K3、§7、§9；§10 registry 权威语义进一步同步至第二轮复审修复后的代码（认领原子提交、严格归属校验、有界读取大小上限、幽灵成员自愈）。
- v5 同步：`PersistentArtifactRegistry` 完成 v5 重写（第五轮 MUST-FIX），本次修订同步更新 §9 验证（`PersistentArtifactRegistryTest` 全量重写、38 例）与 §10 registry 权威语义。v5 变化总纲：run 制品索引由「集合 SET（SADD/SCARD）+ SSCAN 提示式游标幽灵清理 + 独立游标键 `run-purge-cursor`」改为「有序集合 ZSET（成员 = artifactId，score = 每 run 一把单调序号，由 run-seq 计数器键脚本内 INCRBY 发号）+ 窗口轮转幽灵清理（ZRANGE 带 LIMIT 是构造性硬预算，轮转状态编码在 score 排序本身，游标键整体废除）」；读取 touch 改为单条原子 Lua（同一次脚本执行内同时更新 meta 的 lastAccessAtMillis/expiresAtMillis 与四类键 TTL，返回状态码 0/1/2，Java 侧绝不吞异常报成功）；过期 cleanup 改为每条 meta 走一条 Lua 原子判定（在 Redis 单线程内读回当前 JSON 的 expiresAtMillis 才删；JSON 损坏/无日期一律不删）；幂等认领脚本的 EXISTS 分支（输家采纳赢家）做索引 TTL 刷新时只取赢家 meta 键自身剩余 TTL，绝不取输家传入的 TTL；TTL 归一化收口到唯一权威点 `effectiveTtlHours`。键清单新增 `agent:persistent-artifact:run-seq:{runId}` 一类（详见 §10）。

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
- `PersistentArtifactRegistryTest`（agentPlatformShared，38 例，D22-5.1.3 新增，随 v5 全量重写对齐 ZSET 窗口轮转实现；Redis 用线程安全内存 fake——values 字符串 / hashes 哈希 / zsets 有序集合三张表，五种 Lua 脚本（过期清理判定 / 值条件 HDEL / 读取 touch / 列表加入 / 幂等认领）按 ARGV 个数分发、共用一把锁模拟 Redis 单线程原子执行，另带可控时钟与每键 deadline，文件落 @TempDir，不碰生产 DB/Redis/Nacos）：registry 契约钉住——显式注册可 list/download / legacy AgentContext 入口兼容（含无上下文历史语义） / 幂等重复注册复用同一 artifactId 且零重写（含 external 幂等，清理不动底层文件） / 幂等入口强制 runId / 跨 run/user 拒绝与 meta 缺上下文一律严格拒绝（fail-closed） / external 越根/traversal/symlink 逃逸拒绝且零落盘（批准根内路径正常接受） / cleanup 同删 meta+文件+run 索引+身份字段 / cleanup SCAN 显式跳过与 meta 共享前缀的 run 索引/身份/序号键 / listByRunId 滤掉 meta 缺失的幽灵成员并顺手 ZREM（读取侧自愈，v5 由 SREM 改 ZREM）。MUST-FIX 反测逐轮扩充并随 v5 重写：第一轮覆盖 ① 并发幂等注册单一赢家 + 陈旧身份字段值条件清除并重试注册；② 有界索引超限原子拒绝并回滚 + cap<=0 fail-closed + 并发注册永不溢出 cap；③ 跨 run/user strict 矩阵；④ 注册后 symlink swap / 内容篡改读取侧 fail-closed；⑤ 身份字段编码无碰撞。第二轮覆盖 ⑥ FULL（容量满）拒绝后不留幽灵身份/索引痕迹且重试仍稳定拒绝（FULL 路径从不写身份字段）；⑦ 输家认领时赢家身份+run 列表必然已原子提交（输家 adoption 即读到赢家在列表内）；⑧ 有界流式读取超上限即拒（权威执行点 readBounded，即使绕过 Files.size 预检也拦得住）；⑨ 父目录被换成符号链接指向根外（TOCTOU）读取侧拒绝；⑩ 幽灵成员填满 cap 时有界清理后新注册恢复成功。第三轮（第五轮起按 v5 重写）覆盖 ⑪ 幽灵清理进展保证——cap=200>预算 128、第一个排序窗口（score 最低的 128 个成员）全是活成员、幽灵排在最后：第一次注册 FULL 且轮转状态可验证（被检查的活成员重打分到未检查成员之后、重打分个数恰 = 硬预算 128、轮转发号持久化在 run-seq 键），第二次注册 ADDED、幽灵被清（钉住 ceil(200/128)=2 的确定性上界；旧实现「每次重复检查同一批活成员、幽灵永远清不到」的缺陷被消除）；⑫ 并发变更档——两次注册之间外部注入 30 个新幽灵，只保证每次执行硬预算 + 持续进展、不承诺圈数上界；⑬ 脚本形态静态证明——认领/加入两段 Lua 脚本文本含 `zrange`/`zrem`/`zcard`/`incrby`、不含 `smembers`/`sscan`/`scard`/`cursor`（全量取集合命令、提示式游标、独立游标键全部消失），且 run-seq 键在 KEYS 里；⑭ 读侧 touch 滑动——注册时 meta/身份/列表/序号四键 TTL 截止点对齐，时钟推进后 readContent 使四键截止点一起向后滑动回满额；⑮ TTL 漂移防护——滑动后同身份幂等复用返回同一 artifactId（不产生第二个 ID、不重写文件），meta 真过期后重新认领才换新 ID 且列表仍只 1 项、序号键随索引一起过期后发号从 1 重来（降级不丢数据）；⑯ EXISTS 分支修复——手工把赢家从 run 列表 ZSET 删掉后幂等重注册，返回同一 artifactId 且成员以新发序号 ZADD 补回（钉住「身份记着赢家、列表却没有赢家」的异常不再被采纳）。第五轮覆盖 ⑰ TTL 归一化唯一权威点 effectiveTtlHours——ttlHours=0/负一律回退默认 12h，meta.expiresAtMillis/meta.ttlHours/meta 键 TTL/全部脚本 ARGV 同源零漂移；⑱ EXISTS 分支 TTL 刷新只取赢家 meta 键自身剩余 TTL（短 TTL 输家改不短、长 TTL 输家拉不长，两个方向的漂移都钉死）；⑲ touch 单条原子脚本同时更新 lastAccessAtMillis/expiresAtMillis 且返回状态码 0/1/2、Java 侧绝不吞异常（0→IllegalArgumentException、2→IllegalStateException；非幂等制品绝不创建身份项、丢失身份项 HSETNX 补建、入侵者占槽返回 2 拒绝覆盖且不得进入 run 列表）；⑳ cleanup Lua 原子判定——损坏 JSON / 无 expiresAtMillis / 非数字一律绝不盲删，真到龄者原子删除，刚被 touch 续期的制品绝不被误删；边界约束：score 是每 run 单调序号而非毫秒时间戳（同毫秒注册也严格互异、按注册顺序递增）。
- `AgentArtifactServiceTest`（agentPlatformShared，8 例，D22-5.1.3 新增）：facade 契约钉住——registry 注册项 DTO 映射 / 事件派生项惰性幂等注册 / registry-first 读取 / legacy Base64 只读回退 / 跨 run 访问拒绝 / retention 两档与 success-only 过滤保持 / download 大小上限仅下载面生效。
- 红线自检：diff 无新增 `/data/agent_*` 硬编码（`DEFAULT_*` 常量为四根既有默认值的集中化，属现状继承）；四根清单齐全（§1）；可达性失败信号齐全（§4）；未触碰 `agent.llm.prompt-base-dir`（D01）；未引入对象存储/跨机共享（§4.4）。

## 10. D22-5.1.3 后的 registry 权威语义（合同层面）

D22-5.1.3 四提交与 codex MUST-FIX 修正（含第二轮、第三轮复审修复与第五轮 v5 重写）后，持久制品域形成「单一权威 registry + user API 门面」结构，代码入口为 `PersistentArtifactRegistry.java` 与降级后的 `AgentArtifactService.java`：

- **唯一权威**：`PersistentArtifactRegistry` 是唯一权威 registry——注册、元数据（Redis）、文件落盘、run 级有界索引、归属校验、TTL 清理与内容哈希校验均在此收口。`AgentArtifactService` 降级为 user API facade + legacy 适配器：不再自管存储（原 K3/K4 @Value 摘除，根一律经 `AgentStoragePaths`），list/load 走 registry-first，仅保留历史 Base64 ID 的只读回退。
- **显式上下文入口**：`registerExplicit` / `registerExternalExplicit`（非幂等，每次调用生成新 artifactId；rawRef 逐条注册经 `RunRawRefStoreImpl` 走这里，其 logicalId 固定为 runId，不能走幂等路径）与 `registerIdempotent` / `registerExternalIdempotent`（幂等），runId/userId 均显式传参，不依赖 `AgentContext` 线程态；幂等入口 runId 为空即抛 `IllegalArgumentException`。旧 `register`/`registerExternal` 保留为有界兼容 delegate：从 `AgentContext` 线程态补齐上下文后转调显式入口。
- **幂等身份去重**：Redis hash `agent:persistent-artifact:run-identity:{runId}`，field 为**无碰撞身份串**——各段按 `长度:值|` 编码，`identityField(artifactType, logicalId, externalPath)`（内容制品路径段为空；长度前缀保证不同 (type, logicalId, path) 组合不可能拼出同一 field）。registry 与 facade **共用这唯一 public static helper**：facade 的 `candidateIdentityOf`/`registryIdentityOf` 直接委托 `PersistentArtifactRegistry.identityField(...)`，不再存在第二份格式实现。认领协议：候选文件 + meta 先行写入，随后经**一条 Redis Lua 脚本原子提交**完成认领（Redis 单线程执行脚本，脚本内所有步骤要么全部生效、要么全部不生效）——脚本 KEYS 为三个键：身份哈希、run 列表 ZSET、run 序号计数器键 `agent:persistent-artifact:run-seq:{runId}`；ARGV 依次为身份 field、候选 artifactId、容量上限、幽灵清理预算、meta 键前缀、索引键 TTL 秒数（由 Java 侧唯一归一化点 `effectiveTtlHours` 派生，见下方 TTL 与清理条目）。脚本内依次做四步：①查身份哈希（按内容身份去重的 Redis 散列）上该身份字段是否已有赢家——有赢家且赢家 meta 仍活时，**校验并修复赢家的 run 列表成员资格**（ZSCORE 缺失即以 INCRBY 新发序号 ZADD 补回，杜绝「输家采纳一个用户列表里看不见的赢家」），并**按赢家 meta 键自身的剩余 TTL**（`TTL` 命令读回，绝不取输家传入的 ARGV——短 TTL 输家不可能把赢家索引 TTL 改短，长 TTL 输家也不可能把索引拉得比 meta 更久）对身份/列表/序号三类索引键做只延长不缩短的 TTL 刷新，随后返回 EXISTS:赢家ID（EXISTS 路径不写任何新索引项）；赢家 meta 已缺失（陈旧悬挂）时同样返回 EXISTS:赢家ID，由 Java 侧按既有协议清陈旧字段后重试；该修复消除「身份记着赢家、run 列表却没有赢家」（历史 TTL 漂移或外部干预造成）的异常被输家原样采纳的可能；②无赢家则做**窗口轮转有界幽灵清理**（见下方幽灵成员自愈条目）；③用 ZCARD 检查 run 列表 ZSET 是否已满（容量上限为 `agent.persistent-artifact.run-list-cap`），已满返回 FULL（不写任何东西）；④未满则 HSET 身份字段 + INCRBY 发新序号 + ZADD run 列表（score = 该序号）+ 对三类索引键做只延长不缩短的 TTL 刷新，全部在同一次脚本执行里完成，返回 CLAIMED。不存在「身份已写、列表未写」或「列表已写、容量未查」的中间窗口。赢家的索引键 TTL 延长不再是提交成功后的 Java 侧后补调用，而是在脚本内完成（只延长不缩短：脚本内的 extendOnly 助手读回当前剩余 TTL，剩余比目标短才 EXPIRE 到目标值，键已不存在（TTL 命令返回 -2）则不做任何事——避免短 TTL 覆盖长 TTL）。竞争输家只有在脚本返回 EXISTS（已有赢家）时才接管赢家的 artifactId——EXISTS 意味着赢家的身份项与列表项已在同一次脚本执行中原子落盘，且脚本刚在同一次执行里确认/修复了赢家的列表成员资格，所以输家不可能拿到「幽灵 ID」（身份存在但 run 列表缺失的 ID）；输家回滚自己的候选 meta 与文件（脚本对输家不写任何索引）后接管赢家 artifactId：赢家 meta 仍在则直接 adopt 其既有注册（零重写）；meta 恰被 TTL/并发 cleanup 清掉则经 **Lua 值条件 HDEL 脚本**原子清除陈旧字段（仅当 field 值仍等于旧 artifactId 才删，避免与并发新认领 ABA），然后重试注册；至多 3 轮仍不收敛抛 `IllegalStateException`。容量不足时脚本返回 FULL 且不写任何索引，Java 侧回滚候选文件与元数据（超限语义详见下方 run 有界清单条目）。不变量：identity=X ⇒ X 的 meta 必在认领前已写入，故 find(X) miss 即代表已被清理，清字段重试安全。
- **run 作用域有界清单 `listByRunId`**：run 索引是 ZSET `agent:persistent-artifact:run-list:{runId}`（成员 = artifactId，score = 每 run 一把单调递增序号，由 run-seq 计数器键脚本内 INCRBY 发号——绝不重复、绝不回退，只表达「被检查的先后顺序」，不依赖任何时间语义），cap = `agent.persistent-artifact.run-list-cap`（默认 1000）。**超限语义 = 注册原子拒绝并回滚（可见失败）**：容量检查与索引写入在同一条 Redis Lua 脚本内完成——幂等注册走上述认领脚本（窗口轮转幽灵清理、ZCARD 容量检查、HSET 身份字段 + INCRBY 发号 + ZADD run 列表 + 只延长不缩短 TTL 刷新同脚本完成）；非幂等注册走另一条更小的 Lua 脚本（KEYS=[列表 ZSET, run-seq 序号键]，窗口轮转幽灵清理 + ZCARD 容量检查 + INCRBY 发号 + ZADD + 同款 TTL 刷新），同样要么全部生效、要么全部不生效。已满时脚本返回 FULL 且不写任何索引，Java 侧抛 `IllegalStateException` 并回滚候选 meta 与文件；不存在「meta-only 成功、仅跳过索引」的静默路径。原先「先 SADD → SCARD 查容量 → 超限再 SREM」的多命令序列已彻底移除，不再存在「列表已写、容量未查」或「先写后撤」的中间状态。**cap<=0 一律 fail-closed**（直接抛异常，禁止退化为不限长）。并发保守语义：同 cap 下的竞争者可能同时被拒，但索引大小永不超过 cap。`listByRunId` 只读、不生成新 ID：按 score 升序读索引 → 逐条取 meta → 滤掉 meta 缺失（Redis TTL 到期或已清理）与 runId 不符的陈旧项，并对发现 meta 缺失的成员顺手 ZREM（幽灵成员有界自愈，见下条），按创建时间升序返回。
- **幽灵成员自愈（有界 + 进展保证，ZSET 窗口轮转）**：run 列表 ZSET 里可能出现「幽灵成员」——成员 ID 还留在 ZSET 里，但对应的 Redis 元数据键已经不存在（典型原因：元数据键自带 TTL 到期被 Redis 自动删除，而 ZSET 成员没有 TTL）。幽灵成员让 ZCARD 计数虚高，导致 run 列表明明没满却持续报容量超限。修复：上述两条 Lua 脚本在容量检查前都做**窗口轮转有界幽灵清理**——脚本不全量物化 ZSET（SET 时代的 SMEMBERS 全量取回与 SSCAN 提示式游标扫描均已废弃）：每次以 `ZRANGE list 0 budget-1` 取出**当前 score 最低的至多 GHOST_PURGE_BUDGET 个成员**（固定预算常量，当前值 128；ZRANGE 带 LIMIT 是**构造性硬上限**——单次脚本执行检查的成员数不可能超过预算，这是 Redis 命令语义本身决定的，而不是 SSCAN COUNT 那种可被服务端忽略的提示），逐个 EXISTS 其 meta 键，meta 已不存在的幽灵成员当场 ZREM 移除；窗口内的活成员随后用 INCRBY 新发的连续序号重新打分、整体移到所有未检查成员之后（score 严格大于任何未检查成员的得分）——**轮转状态就编码在 score 排序本身，不存在任何独立游标键**（旧游标键 `agent:persistent-artifact:run-purge-cursor:{runId}` 已整体废除，「游标键被短 TTL 候选覆盖/提前过期」这类漂移故障从构造上消失）。进展保证分两档如实表述：①成员集合固定（无并发注册/删除）时，窗口每次严格前进，至多 **ceil(成员总数 / 预算)** 次索引写入后所有幽灵必然被清完（确定性上界）；②有并发写入/删除时，只保证「每次执行至多检查预算数个成员（硬预算）+ 已检查的活成员严格后移（持续进展）」，不承诺圈数上界。轮转重打分与新成员入列共用一把 **run 序号计数器键 `agent:persistent-artifact:run-seq:{runId}`**（字符串，脚本内 INCRBY 发号）：序号严格单调递增，同毫秒内的多次注册也拿到严格互异的序号；键自身 TTL 随索引键同步滑动（脚本内同款只延长不缩短刷新），不会比索引键活得更久；键若因 Redis 重启/逐出丢失，发号从 1 重来，仅退化为「新成员可能排在旧成员之前」，不丢数据、不报错，硬预算与持续进展仍成立。cleanup 的 SCAN 按前缀显式跳过序号键。另外 `listByRunId` 遍历时发现缺元数据的成员也会顺手 ZREM。因此幽灵成员不会永久占用容量配额，新注册可以在幽灵被清理后恢复成功。
- **归属校验（一律严格，宽容 matcher 已删除）**：所有用户/工具可达的读取与定位入口现在一律走同一个严格归属校验——无论是从 `AgentContext` 线程态补上下文的旧入口（`ToolOutputRefServiceImpl` 依赖线程态的旧 read/locatorFor 入口），还是显式传入 runId/userId 的入口（`AgentArtifactService` list/load 与 `ToolOutputRefServiceImpl` 的显式上下文 overload read/locatorFor）。校验规则：制品元数据的 runId/userId 与调用方的 runId/userId，四个值都必须非空白且两两相等，否则抛 `IllegalArgumentException`（fail-closed）。宽容校验方法 `matchesOwnerLenient`（任一边为空则该侧不校验）已从生产代码中彻底删除；历史遗留的「无上下文制品」（meta 里没有 runId/userId）现在经任何入口都拒绝读取。跨 run/跨 user 访问一律拒绝。**短格式 raw_ref（`raw_ref_001` 形态）同此合同**：reread 工具读短格式时显式携带当前 runId 与 userId 两个上下文进入 `RunRawRefStore`；映射层（短 ID → artifactId 的 Redis hash）只能证明该短 ID 在此 run 下注册过，不足以放行内容——内容读取一律经 registry 的 `readContentStrict` 做上述四值严格归属校验，userId 空白或不匹配的调用方 fail-closed 被拒。短格式不存在只凭 runId 放行的读取路径。
- **external 路径门槛**：只接受 D04 批准根（artifactRoot / datasetRoot）内路径。注册时先做规范化 containment 校验，越根或 traversal 抛 `SecurityException`（零落盘）；已存在的路径额外 `toRealPath()` 跟随 symlink 复检真实位置（批准根同样解析 symlink 后比较，如 macOS `/var` → `/private/var`），拒绝 symlink 逃逸。**读取侧同样复检（TOCTOU 强化）**：registry 提供权威读取缝隙 `readArtifactBytes(artifactId, maxBytes)` 与 `readWithinArtifactRoot(path, maxBytes)`——先对路径与批准根做 `toRealPath()` 真实 containment 复检（fail-closed），再以 `NOFOLLOW_LINKS` 打开**原始路径**读取（注册后把路径换成 symlink 的攻击会在 open 时失败）；大小上限由两层构成——①快速失败预检查：`Files.size` 超过上限直接拒绝；②权威的有界流式读取：从输入流最多读 maxBytes+1 字节，一旦读到第 maxBytes+1 个字节就拒绝——这样即使文件在预检查与实际读取之间被增大（TOCTOU，检查时间与使用时间不一致的攻击），内存里也最多只分配 maxBytes+1 字节，不会出现「预检查通过后将任意大文件整个读入内存」；内容哈希校验在读完之后照常进行；facade 的 registry 制品下载与 legacy 文件回退一律委托这两条缝隙，不再直接按 meta.path 自行读取。注册时校验 + 读取时复检双重收窄，TOCTOU 窗口不再只依赖注册时点。
- **TTL 与清理（唯一归一化点 + 原子 touch + Lua 清理判定）**：facade 惰性注册 TTL = max(normalRetentionDays, adminRetentionDays)×24h，两档均 <=0（永不过期）时取 365d 上界（meta TTL 无法表达无限）；registry 侧生效时长的**唯一归一化点是 `effectiveTtlHours`**：ttlHours>0 取 ttlHours，否则取 `agent.persistent-artifact.ttl-hours`（默认 12h），再 clamp 到至少 1 小时——meta.expiresAtMillis、meta.ttlHours、meta 键 SET TTL、以及所有脚本的 TTL 秒数 ARGV **全部由这一个值派生**，不存在第二处各自归一化导致的漂移（修复前 meta 记 12h 而索引只设 1h，索引先过期后同一幂等身份可被第二次 CLAIMED）。索引键 TTL 维护只延长不缩短，且按**统一滑动过期协议**对齐：meta、身份哈希、run 列表 ZSET、run 序号四类键（旧「清理游标」键已随 SET 索引一起废除）以同一生效时长为寿命基准——写路径（认领 CLAIMED / 加入 ADDED）由 Lua 脚本在原子提交内做只延长不缩短的 TTL 刷新（脚本内比较剩余 TTL，仅在不足时延长，短 TTL 绝不覆盖长 TTL）；**EXISTS 修复路径的 TTL 刷新时长不取输家传入的 ARGV，而是取赢家 meta 键自身的剩余 TTL（`TTL` 命令读回）**，杜绝「短 TTL 输家把赢家索引 TTL 改短」（长 TTL 输家把索引拉得比 meta 更久同样被堵死）；读路径由 **touch 的单条原子 Lua 脚本**完成——在同一次脚本执行内「重写 meta（同时更新 lastAccessAtMillis 与 expiresAtMillis，expiresAtMillis 不再停在注册时的旧值，cleanup 的 Lua 判定读到的正是本次滑动后的新值）→ 幂等制品的身份步（槽位为空 HSETNX 以本 artifactId 补建、槽位值是本 artifactId 通过、槽位被其他 artifactId 占用返回 2，绝不覆盖他人槽位；非幂等制品传空身份 field、整步跳过，绝不顺手创建身份项）→ 成员 score 同步（已在 ZSET 的成员以新发序号重新打分移回队尾，缺失成员 ZADD NX 补回）→ ZSET/身份/序号三类键只延长不缩短 EXPIRE（meta 键自身 SET+EXPIRE 满额滑动）」，返回状态码 0/1/2（0 = meta 已在 find 与 touch 之间消失、1 = 成功、2 = 身份槽位被其他 artifactId 占用），**Java 侧对 0/2/null 一律外抛异常，绝不吞掉报成功**。因此任何读取都会让索引 TTL 不小于 meta 新 TTL，不存在「索引先于 meta 过期 → list 丢条目 → 同一幂等身份被第二次 CLAIMED」的漂移窗口。cleanup（@Scheduled，默认 5min 间隔）SCAN `agent:persistent-artifact:*` 并按前缀显式跳过 run 索引/身份/序号键（它们与 meta 共享前缀，即使误存可解析 JSON 也不得按 meta 处理）；**过期判定不再用 Java 预读的 expiresAtMillis 直接删**：每个候选 meta 键执行一条判定 Lua（`CLEANUP_META_SCRIPT`），脚本在 Redis 单线程内读回该键当前 JSON、解析 expiresAtMillis、仅当其为数字且 <= now 才 DEL——判定与删除原子；JSON 损坏/非对象返回 -1（Java 记日志跳过，绝不盲删），expiresAtMillis 缺失/null/非数字返回 0（保守保留，与历史判空逻辑的永不过期语义一致）。Java 预读 meta 只为拿到文件路径；若预读与脚本判定之间 touch 刚把 expiresAtMillis 改到未来，脚本读到的是新值 → 返回 0 → 不删。touch 与 cleanup 都是单条脚本，Redis 单线程串行化二者，不存在 touch-then-cleanup 的 TOCTOU 窗口。脚本返回 1 后由 Java 侧收尾文件与索引痕迹：同删 meta（脚本已删）+ 文件 + run 索引项 + 条件清身份字段（身份字段经值条件 HDEL 原子清除，仅在仍指向本 artifactId 时删，避免误删并发新注册抢占的字段）——索引残项属幽灵，写入侧窗口轮转与读取侧顺手清理亦会收掉（双保险）。文件侧：内容制品只删 artifact 根内文件；external 仅当 cleanupPath 标记且为 symlink 时删链接本体，幂等 external 固定 cleanupPath=false，清理不动底层文件。
- **legacy Base64 回退**：`type|runId|ref` 旧格式 ID 只在 registry miss 时触发、只读、零写入零注册——按旧快照位置（`{artifactRoot}/{runId}/scripts|datasets/…`）读取，且要求 runId 相等与事件侧重放命中（retention 两档与 success-only 过滤语义保持）；历史制品不搬迁不删除。
