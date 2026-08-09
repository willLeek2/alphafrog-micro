# D04 统一存储路径门面 — 存储键映射与迁移说明 v1

- 归属：26Q3 stage1 W5（task #105），D04「统一存储路径门面」交付件。
- 基线 SHA：`73256d5344a116ba24b031f94d992473917e838b`；branch `ccqwen/260809-26q3-stage1-w5-facade`。
- 代码入口：`agentPlatformShared/src/main/java/world/willfrog/agent/platform/storage/AgentStoragePaths.java`（facade）+ `StorageRootUnavailableException.java`（§4.3 失败信号）。
- 现状审计依据：`notes-w5/D04-path-key-inventory.md`（W5 worktree 内，未跟踪工作底稿；其 K/H/N/R 编号在本文被引用）。

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
| dataset | `agent.storage.dataset-root` | `agent.tools.market-data.dataset.path` | `/data/agent_datasets` | `AF_AGENT_STORAGE_DATASET_ROOT` | 同上；旧键仍是 market-data 工具链的现役键 | H1 硬编码前缀的原属根（§4）；K4 有 7 处独立 @Value 声明，一期只收敛门面触达的 consumer（§7） |
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
| K3 `agent.artifact.storage.path`（`AgentArtifactService.java:51`） | **已于 D22-5.1.3 收敛**：作为 artifactRoot 的第二 legacy alias（`LEGACY_ARTIFACT_ROOT_SECONDARY`）并入门面解析链。优先级新键 > legacy（新键设置时短路，不读取也不比较旧键）；新键未设而两 legacy 键同时设置且取值不同 → 启动 fail closed（抛 `StorageRootUnavailableException`，消息只列键名不列值）。兼容政策：历史制品不搬迁不删除（已按旧键解析落盘的 artifact 文件保持原位，只读兼容）；兼容窗口 = 只要 legacy 键仍被任一组件设置即持续生效；退休条件 = 当所有部署环境确认停用 `agent.artifact.storage.path` 与 `agent.persistent-artifact.root`、统一使用新键后，legacy 解析可在后续版本移除。K3 与 K2 共树 `/data/agent_artifacts` 但相对路径规则不同（`{runId}/scripts\|datasets/…`，审计 R1），`AgentArtifactService` 本体仍直读 K3，消费方切换归后续 slice。 |
| K5 `agent.tools.market-data.dataset.database-fetched-path`（`/data/database_fetched`）、K6 `…manifests-path`（`/data/manifests`） | dataset 域的邻近键，但属四层布局树（写方 `DatasetWriter`/`ManifestWriter`，消费链含 01→02 事件与沙箱 cp），归 D21-B/D22 slice，不与四根门面混改。 |
| K8/K9/K10（llm config-file / prompt-base-dir 族） | D01 域（prompt/config 加载），非存储根。 |
| K11/K12/H3/H4（debug observability 两路 `/app/logs/…`） | 注入方式异构（@ConfigurationProperties / System.getenv），且根在容器内、与四根无共享消费方；留后续收敛。 |
| H2 `/data/agent_guides`（LoadToolGuideTool，ro 挂载） | 只读资源目录，非存储根。 |
| P1–P6（Python 沙箱侧 env 键） | Kimi per-node 工具 slice 范围（W5 消费方门 = 本门面 SHA），不在 Java 门面内。 |

## 6. 迁移风险高亮（根变更前必读；编号沿用审计）

- **R2 Redis meta 存绝对路径**：`agent:persistent-artifact:{id}`（N1）、`dataset:meta:{queryKey}`（N4）、`manifest:meta:{queryKey}`（N6）的 value 内含绝对路径；`readPath` root guard 与 cleanup 删文件直接消费这些值。**改根会使存量 meta 指向旧路径**，需先耗尽存量或提供迁移/兼容层（一期未提供重写工具）。
- **R3 磁盘 meta 存绝对路径**：`ManifestWriter` 的 `meta.json`（M1）与 workspace `manifest.json` `assets[].refPath`（M2）已落盘旧前缀；workspace dump 为长期留存产物，无重写机制。
- **R4 在途事件携带绝对路径**：`DatasetPersistedEvent.persistedPath` → `AgentRunDatasetCsvWriter` → `sandbox_runner.py` cp 物化；迁移窗口内的在途 run 携带旧根路径。
- **R5 跨服务共享挂载**：`agent_datasets`/`database_fetched`/`manifests`/`agent_artifacts` 四树同时挂入 langchain 与 python-sandbox 两个容器（compose `:522-525` ↔ `:672-675`）；Java 键与 Python env（P1）分属两套配置体系，改根必须双侧同步。
- **R8 重启级**：无 Nacos 热更通道（见 §2），根切换 = 改配置 + 重启。
- **R9 容器本地根**：debug 文件默认 `/data/logs/…` 与沙箱 `/sandbox/runs` 无 volume 挂载，宿主机无存量可迁移，但也无法从宿主审计。
- DB 侧核验（R13）：`alphafrog_schema_full.sql` 无存量绝对路径列，风险面集中在 Redis meta、磁盘 json 与在途事件。

## 7. Consumer 迁移状态

**已经由门面（本次交付）**：

| Consumer | 收敛点 |
|---|---|
| `WorkspacePathResolver` | 新增 `@Autowired AgentStoragePaths` 构造器（workspace 根 + dataset 根）；原 2-string @Value 构造器保留供测试 |
| `WorkspaceManifestWriter` | `datasetRoot()` 替换 H1 硬编码；`writeAll` 入口 `verifyDumpTarget` |
| `PersistentArtifactRegistry` | `artifactRoot()` 替换 @Value；`register` 前 `requireWritableRoot` |
| `AgentObservabilityDebugFileWriter` | `observabilityDebugFile()` 替换 @Value 路径（best-effort 语义不变） |

**仍直读、由后续 slice 收敛**：`AgentArtifactService`（K3 键已于 D22-5.1.3 收敛为门面第二 legacy alias，见 §5-K3；服务本体仍直读待切换；K4，D22）、`DatasetRegistry`/`DatasetWriter`/`ManifestWriter`（K4–K6，D21-B）、`WorkspaceHealthVerifier`/`WorkspaceAssetCollector`（K4，D21-A 协同；后者为 dead injection，审计 R11）、Python 侧 P1–P6（Kimi slice）、`DebugObservabilityService`/frontend debug 两路（K11/K12）。

## 8. §4.1 单点切换运维口径

切换任一存储根的操作面 = **一个新键（或对应 `AF_AGENT_STORAGE_*` env）+ 一次重启**：

1. 设 `AF_AGENT_STORAGE_{WORKSPACE_ROOT|ARTIFACT_ROOT|DATASET_ROOT|OBSERVABILITY_DEBUG_FILE}`（compose env 注入）；未设时行为逐层回退，与 D04 前一致。
2. 重启相关服务（路径键 create-time freeze，重启前不生效）。
3. 已收敛 consumer（§7 表一）随门面整体切换，无需逐类改配置。
4. 跨容器共享树（R5）须同步挂载与 Python env；存量 meta 风险按 §6 评估。

## 9. 验证

- `AgentStoragePathsTest`（agentPlatformShared，18 用例）：默认值 / 旧键别名 / 新键优先 / 空白回退 / dataset 旧键=market-data 键 / artifactRoot 第二 legacy alias（K3 单设、双 legacy 同值、新键短路于双 legacy 冲突、双 legacy 冲突 fail-closed 且消息只列键名不列值）/ `requireWritableRoot` 建目录与两类失败 / `verifyDumpTarget` 越界与 null / 显式构造器归一化与空白拒绝。
- `WorkspaceManifestWriterTest`（agentLangchainService，4 用例）：refPath 走配置根（断言不再出现 `/data/agent_datasets` 前缀）/ 越界目标 SecurityException 且零落盘 / 根不可达 StorageRootUnavailableException / 既有五文件契约不回归。
- `ToolOutputRefServiceImplTest`（agentPlatformShared，6 用例）：改为经显式构造器注入门面根（替代原 @Value 反射注入），行为断言不变。
- 红线自检：diff 无新增 `/data/agent_*` 硬编码（`DEFAULT_*` 常量为四根既有默认值的集中化，属现状继承）；四根清单齐全（§1）；可达性失败信号齐全（§4）；未触碰 `agent.llm.prompt-base-dir`（D01）；未引入对象存储/跨机共享（§4.4）。
