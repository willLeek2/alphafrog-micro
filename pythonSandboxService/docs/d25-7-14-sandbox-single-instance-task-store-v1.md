# D25 7.1.4 — 沙箱任务状态单实例假设与 task_store 说明文档

## 0. 归属与元信息

| 项 | 值 |
|---|---|
| 归属条目 | 26Q3 stage1 · D25 · 7.1.4（沙箱单实例 / task-store 说明文档） |
| Wave / 任务 | W5 / task #105（wave owner：ccqwen-Max-mbp） |
| writer | ccqwen（起草初稿；终稿审核与提交由 wave owner 负责） |
| 状态 | draft v1 |
| 性质 | 纯文档切片：不含任何代码改动；全文锚点均为逐文件阅读后记录 |
| Worktree | `/Users/frog_wch/.slock/agents/17d40883-0181-40cd-acf6-70f675e13cdf/worktrees/260809-26q3-stage1-w5-facade`；文中 `文件:行` 均相对该 worktree 根。base SHA 援引 `notes-w5/D04-path-key-inventory.md` 文件头（`73256d5…`）；本切片不运行 git 命令，未自行复核 SHA |
| 边界 | 只覆盖沙箱任务状态（task_store）与单实例假设本身。7.1.3 / 7.1.9 / 8.1.1 仅交叉引用、不重写其正文；D03 / D04 / D21 的实施口径仅交叉引用、不重写 |
| 源文档可得性 | 26Q3 stage1 需求源文档（含 D25 7.1.4 正文、§4.5、§3 红线、Q-03/Q-06/Q-13 问题清单原文）**未在本 worktree 内找到**（检索记录见 §7）。因此本文只引用源文档的章节号（按验收任务给定的编号），不复述、不猜测其正文；凡依赖源文档原文的措辞均显式标注「待核」 |
| 工作笔记引用 | 文中对 `notes-w5/*.md` 的引用为 wave owner 工作底稿交叉引用（未入库，不包含在交付 SHA 内）；相关结论已在本文正文独立表述（§2.2、§2.6、§3），本文不依赖这些笔记即可独立核验 |

---

## 1. 单实例假设声明（当前阶段）

**声明：当前阶段，pythonSandboxService 的任务状态子系统（task_store）只在「单机 / 单实例（per-instance）」前提下提供正确性保证。多实例、跨主机、跨进程共享状态均不在当前支持范围内；超出该前提的行为是未定义的，本文 §4 只文档化扩展接缝，不承诺任何多实例语义。**

该声明由三层事实支撑（锚点见 §6 对照表）：

1. **部署形态是单实例**：`docker-compose.yml` 中 `python-sandbox-service` 为单一服务定义，固定 `container_name`（`docker-compose.yml:614,617`），服务块内无 `deploy.replicas`（`:680-683` 仅有内存限制）；上游网关通过单一固定 URL 访问（`docker-compose.yml:701` `AF_SANDBOX_SERVICE_URL: http://python-sandbox-service:8095`；`pythonSandboxGatewayService/src/main/resources/application.yml:25`）。
2. **任务状态是进程私有的**：task_store 的状态载体 = 进程内内存 dict + 单一本地文件 `state.json`，并发保护是进程内锁（`threading.RLock`），没有任何跨进程/跨主机协调机制（`pythonSandboxService/app/task_store.py:56-61`，详见 §2）。
3. **执行面是 node-local 的**：沙箱容器经本机 docker daemon 创建（compose 挂载 `/var/run/docker.sock`，`docker-compose.yml:677`）；任务队列是进程内 `asyncio.Queue`（`pythonSandboxService/app/main.py:70`）。

单实例内部的多 worker 并发（`AF_SANDBOX_MAX_CONCURRENCY`，compose 默认 20，`docker-compose.yml:623`）不构成多实例：所有 worker 是同一进程内的 asyncio 任务，共享同一个 task_store 内存 dict 与同一把锁（`main.py:68-69,388`）。

---

## 2. task_store 实现描述（与当前代码一致）

实现文件：`pythonSandboxService/app/task_store.py`（179 行，`DurableTaskStore`）。类 docstring 自述：**“Single-file atomic store for tasks and the operationId index”**（`task_store.py:50-54`）——单文件原子存储，任务与 operation 索引一起替换。

### 2.1 内存 dict 是唯一运行时状态

- `self.tasks: Dict[str, Task] = {}`：task_id → Task 的内存 dict（`task_store.py:59`）。
- `self.operations: Dict[str, dict[str, str]] = {}`：operation_id → `{task_id, request_fingerprint, payload_digest}` 的内存 dict（`task_store.py:60`）。
- 并发保护：`self._lock = threading.RLock()`（`task_store.py:58`）——**进程级锁**。它只保护同一进程内的多线程访问（沙箱执行经 `asyncio.to_thread` 落到线程池），对其它进程/主机上的副本无任何互斥效力。
- 所有读写路径都在这把锁内走内存 dict：`get`（`task_store.py:101-103`）、`save`（`:96-99`）、`get_by_operation_id`（`:105-108`）、`create`（`:63-94`）。
- `main.py` 在模块加载时创建全局唯一实例并直接复用其内存 dict：`task_store = DurableTaskStore(config.task_store_path)`、`tasks = task_store.tasks`（`main.py:67-69`）。**除启动时 `_load()` 一次性装载外，运行期不会重新读文件**（`task_store.py:56-61,133-154`）——文件只是持久化镜像，不是共享总线。

### 2.2 本地索引：单文件 state.json（全文档原子重写）

- 路径由 `AF_SANDBOX_TASK_STORE_PATH` 决定，默认 `/data/sandbox_tasks/state.json`（dataclass 默认 `config.py:200`；env 解析 `config.py:263`；compose 同值 `docker-compose.yml:658`；卷挂载 `./data/sandbox_tasks:/data/sandbox_tasks` `docker-compose.yml:676`）。
- 每次落盘（`_persist_locked`，`task_store.py:156-179`）把 **全量** `tasks + operations` 序列化为一个 JSON 文档：`mkstemp` 临时文件写入 → `fsync` → `os.replace` 原子替换 → 目录 `fsync`（`task_store.py:163-174`）。
  - 原子性只保证「单个文件不撕裂」，**不保证多写者内容不丢失**：这是整文档重写，任何并发的第二个写者都会整体覆盖（见 §4.2）。
- schema 版本 `sandbox_task_store_v1/v2`，当前写 v2；未知版本 fail-closed 拒绝加载、绝不静默迁移（`task_store.py:20-28,138-142`）。
- 装载时做引用完整性校验：operation 必须指向存在的 task、必须携带不可变的请求绑定（`task_store.py:148-152`）。
- 重启恢复：`recover_after_restart()`（`task_store.py:110-125`）把 QUEUED 任务收集重排、把 RUNNING 任务终态化为 FAILED（“sandbox service restarted while task was running”）；由 lifespan 启动时调用并入队（`main.py:390-391`）。**该逻辑只读本实例的 state.json。**
- 宿主机侧还有一个 state.json 消费方：运行时镜像保留脚本按 `AF_STATE_FILE` 读取 QUEUED/RUNNING 任务的 `runtime_image_ref` 作为保护集（`pythonSandboxService/scripts/prune_runtime_images.sh:167-211`；`deploy_latest.sh:570` 注入默认 `$ROOT_DIR/data/sandbox_tasks/state.json`；样例 `docker.env.example:61`；compose 宿主机视角键 `docker-compose.yml:646`）。同一文件因此存在容器内（P4）与宿主机（P5）两个路径键（与 `notes-w5/D04-path-key-inventory.md` §1.2 P4/P5、R10 一致）。

### 2.3 operation 索引：node-local 幂等索引

- operation_id 语法固定为 `runId:toolCallId:attempt`（正则 `task_store.py:18`；校验报错文案 `task_store.py:127-129`）；请求指纹必须为小写 `sha256:<64hex>`（`task_store.py:17,130-131`）。
- `create`（`task_store.py:63-94`）：
  - 无 operation_id → 直接写入（`:71-74`）。
  - 有 operation_id 且已存在绑定：指纹与首包 payload 摘要一致 → 幂等返回原任务（`existing=True`，`:76-85`）；不一致 → 抛 `OperationConflictError`（`:81-84`，HTTP 409，`main.py:482-483`）。
  - 首包 payload 摘要 `payload_digest` 由 `request_payload_digest` 计算（`task_store.py:42-46`），把幂等绑定钉死在首次 HTTP payload 上。
  - operation 记录与任务记录在同一次 `_persist_locked` 中一起落盘（`:87-93`）——operation 映射不可能脱离其绑定的 payload 摘要单独存活（类 docstring `task_store.py:50-54`）。
- **node-local 含义**：该索引只存在于本实例的内存 dict + 本实例的 state.json 中。网关侧把 operationId 明确定位为「createTask 不确定结果恢复的唯一幂等索引」并桥接 `GET /operations/{operation_id}`（`pythonSandboxGatewayService/src/main/java/world/willfrog/sandbox/service/PythonSandboxGatewayServiceImpl.java:148-154`）；这条恢复链隐含「查询命中的是创建发生的同一实例」——单实例部署下天然成立，多实例下不再成立（§4.2）。

### 2.4 HTTP 入口与 task_store 的使用方式

`main.py`（529 行）现有端点全部经由全局 `task_store`：

| 入口 | 位置 | 与 task_store 的关系 |
|---|---|---|
| `POST /tasks`（创建） | `main.py:436-493` | 校验 canonical fingerprint（`:462-466`）→ 冻结输出上限快照与镜像引用（`:472-474,479`）→ `task_store.create`（`:481`）→ 冲突 409 / 参数 400（`:482-485`）→ 仅当非幂等命中才入队（`:486-487`） |
| `GET /tasks/{task_id}`（查询状态） | `main.py:496-501` | `task_store.get`；未命中 404 |
| `GET /operations/{operation_id}`（幂等查询） | `main.py:504-514` | `task_store.get_by_operation_id`；未命中返回 `found=False` |
| `GET /tasks/{task_id}/result`（查询结果） | `main.py:517-529` | `task_store.get`；按终态/非终态分流 |
| 状态持久化点 | `main.py:202,206,345` | 队列超时、置 RUNNING、任务结束时 `task_store.save` |

**取消入口现状（如实记录）**：当前 `main.py` 中**没有**任务取消端点；`CANCELED` 只是 `TaskStatus` 枚举成员（`models.py:15`），被结果查询的状态分流（`main.py:523`）与重试分类（`retry_classification.py:16`）引用，但没有任何写路径会把任务置为 CANCELED。

### 2.5 container_max_concurrency==1（cmc==1）单并发前提

任务状态子系统的单实例前提与「每容器单并发」前提同层并存，后者由代码三层强制钉死：

1. **启动 fail-fast**：`DynamicSandboxConfig.__init__` 对基础配置 `container_max_concurrency > 1` 直接抛错（`nacos_config.py:121-126`）。
2. **单键热更拒绝**：`update_container_max_concurrency` 收到 >1 的值时记录 `DYNAMIC_CONFIG_REJECTED` 并保持 last-known-good（`nacos_config.py:197-221`）。
3. **整份 payload 拒绝**：Nacos 整份校验中 `containerMaxConcurrency>1` 使整个 payload 被拒（`nacos_config.py:273-287`）。

理由在代码注释中自述：runner 的任务引导仍会写/删一份 **全局** `/sandbox/sitecustomize.py`，尚未 task-local 化，因此所有配置（动态安装与预装）都必须 cmc==1（`nacos_config.py:90-97,102-120`）。部署面一致：compose `AF_SANDBOX_CONTAINER_MAX_CONCURRENCY: ${…:-1}` 且注释「保持一任务一容器」（`docker-compose.yml:649-650`）；env 默认亦为 `"1"`（`config.py:245`），仅校验 `>=1`（`config.py:246-249`）。恢复路径（cmc>1 何时可放开）也只在代码注释中给出：等 wrapper 引导变成子进程 task-local 之后、经并发隔离测试再放开（`nacos_config.py:117-120`；`config.py:277-284` 记录 cmc>1 与全局 `/sandbox/input` symlink 的互斥联动）。

### 2.6 动态配置边界：哪些可热更、哪些不可

- Nacos 动态配置白名单 **只有** `containerMaxConcurrency` 与四个输出上限键（`KNOWN_DYNAMIC_KEYS`，`nacos_config.py:18-19`）。
- 因此：`task_store_path`、`max_concurrency`、镜像引用、workspace 根等**一律不可热更**，只能重启级配置（与 `notes-w5/D04-path-key-inventory.md` §1.3「未发现任何路径键可经 Nacos 热更」一致）。
- 且 cmc==1 不变量使白名单中的 `containerMaxConcurrency` 实际只能取 1（§2.5）。Nacos 监听本身默认关闭（`AF_CONFIG_NACOS_ENABLED` 缺省不启用，`nacos_config.py:410-412`；`docker-compose.yml:664`）。

---

## 3. 相关单实例约束交叉引用（不重写）

本切片与同一验收框架下的其它单实例约束条目**协同一致**；本节只做交叉引用，不复述、不重写对方正文。

| 条目 | 约束主题（按验收任务给定的口径） | 与本条的关系 |
|---|---|---|
| **7.1.3** | 观测写锁单 JVM | 同属「当前阶段单实例/单 JVM」假设族。**「多实例扩展前必须 Q-06 决策（观测写锁外置）」是 7.1.3 的措辞与职责范围**——本条只交叉引用该要求，不代写 7.1.3 正文。沙箱任务状态自身的扩展前置见 §4.4（按 D25 §4.5 口径，见该节待核标注） |
| **7.1.9** | SSE 共享 Redis 单集群 | 本条不重复其内容；只记录依赖关系：沙箱任务状态目前**不**依赖 SSE/Redis 通道（task_store 无 Redis 依赖，索引与持久化全在本机内存+本地文件，§2），7.1.9 的单集群约束变化不改变本条 §1 声明 |
| **8.1.1** | DB/Redis 区域拓扑未裁定 | 拓扑未裁定意味着本条 §4.3 的外置存储选型不得预设区域形态；本条只引用该未裁定状态，不代写 8.1.1 |
| **D03 / D04 / D21** | 各自实施口径 | 本条不重写其实施口径。存储路径现状审计见 `notes-w5/D04-path-key-inventory.md`（其中 P4/P5/R10 与本条 §2.2 的 state.json 双视角键互证）；dump/manifest 与制品注册相关现状见 `notes-w5/D21-dump-manifest-anchor-verification.md`、`notes-w5/D22-artifact-registry-inventory.md` |

---

## 4. 多实例接缝清单（仅文档化，不实现）

**范围声明：本节只列出「若要扩展多实例，哪些接缝必须变更」，属于文档化；本切片与当前 W5 均不实现其中任何一项。**

### 4.1 接缝点（seam）

| # | 接缝 | 现状（锚点） | 多实例化所需变更方向（仅描述，不设计、不选型） |
|---|---|---|---|
| S1 | 持久化介质 | 单文件 state.json 全文档原子重写（`task_store.py:156-179`；路径 `config.py:200,263`） | 外置到跨实例可见的共享存储；选型未定（§4.3） |
| S2 | 并发控制 | 进程内 `threading.RLock`（`task_store.py:58`） | operation 绑定（create 的读-判-写，`task_store.py:70-94`）需要跨实例原子化（条件写/CAS/外部锁），否则冲突检测失效 |
| S3 | 索引一致性 | tasks 与 operations 同文档替换（`task_store.py:50-54,87-93`） | 外置后两者仍需同事务可见，避免「索引指向别的实例的任务/孤儿索引」 |
| S4 | 任务队列 | 进程内 `asyncio.Queue`（`main.py:70,487`） | 排队语义需跨实例（外部队列或共享调度），否则任务只在创建实例上执行 |
| S5 | 重启恢复 | `recover_after_restart` 只读本实例状态、无所有权/去重（`task_store.py:110-125`；`main.py:390-391`） | 需要任务所有权归属与跨实例去重，否则共享状态下会重复入队/执行 |
| S6 | 网关路由 | 单一固定 URL（`docker-compose.yml:701`；gateway `application.yml:25`；operation 恢复链 `PythonSandboxGatewayServiceImpl.java:148-154`） | 多后端时需「同 operation 路由回原实例」的粘性路由，或状态共享后解除该依赖 |
| S7 | 宿主机侧消费 | `prune_runtime_images.sh` 按单个 `AF_STATE_FILE` 读保护集（`prune_runtime_images.sh:167-211`；`deploy_latest.sh:570`） | 多主机时需聚合所有实例的状态来源，否则镜像保护集不全 |
| S8 | 部署形态 | 固定 `container_name`、无 replicas、docker.sock node-local（`docker-compose.yml:617,677,680-683`） | 部署层水平扩展前置 |
| S9 | 动态配置 | cmc==1 三层强制（§2.5）与单实例无直接耦合，但恢复条件（sitecustomize task-local 化，`nacos_config.py:117-120`）是多实例之外另一条独立前置 | 维持现状直至恢复条件满足；不得借多实例名义放开 |

### 4.2 task_store 跨实例行为差异（现状推演，均基于 §2 锚点）

- **情形 A：两个实例各自独立 state.json（当前默认形态复制两份）**
  - 任务集合、operation 索引完全互不可见：在实例 1 创建的任务，`GET /tasks/{id}` 在实例 2 返回 404（`main.py:498-500`）。
  - 幂等失效：同一 operation_id 在两个实例各创建一次都会成功（`create` 只查本实例 `operations` dict，`task_store.py:76`），网关的 operation 恢复查询命中非创建实例时得到 `found=False`（`main.py:506-508`）→ 「不确定结果恢复的唯一幂等索引」（gateway `:148`）失效，可能产生重复任务。
  - 重启恢复互不相干：各实例只重排自己的 QUEUED（`task_store.py:110-125`）。
- **情形 B：两个实例被人为指向同一共享文件**
  - 无跨进程锁：`threading.RLock` 不跨进程（`task_store.py:58`）；两边各自 `read-modify-write` 全量重写（`:156-179`），后写者整体覆盖先写者的全部任务与索引——**数据静默丢失**。
  - 内存与文件脱钩：实例只在启动时装载一次（`task_store.py:56-61`），运行期互不感知对方写入；`os.replace` 的原子性只防撕裂，不防覆盖。
  - 重启恢复冲突：两实例同时把文件中 QUEUED 任务入队 → 同一任务被两个实例各执行一次（无所有权/去重，`main.py:390-391`）。
  - 结论：情形 B **不是**共享存储方案，只是把情形 A 的「互不可见」换成「互相覆盖」。

### 4.3 task_store 外置依赖存储选型：**未裁定，不选**

- 本条**不选定**任何外置存储（不预设 Redis/DB/其它 KV）。理由：Q-03 / Q-13 未裁定（其原文未在本 worktree 找到，见 §7），且 8.1.1 DB/Redis 区域拓扑未裁定（§3）——在两者裁定前选型无依据。
- 本节只给出选型必须回答的问题清单（供后续决策，非方案）：
  1. operation 绑定（create 读-判-写）的跨实例原子性由谁提供（S2）？
  2. tasks 与 operations 的同事务一致性如何保证（S3）？
  3. 排队/恢复的所有权与去重语义（S4/S5）落在存储层还是调度层？
  4. 宿主机侧镜像保护集的消费方式如何迁移（S7）？
  5. 与 7.1.9（SSE 共享 Redis 单集群）及 8.1.1 拓扑裁定的依赖顺序？

### 4.4 沙箱任务状态的扩展前置（按任务要求以 D25 §4.5 口径整理；§4.5 原文不在本 worktree，措辞待 wave owner 对照源文档核校）

多实例扩展沙箱任务状态之前，至少满足以下前置（每一条都对应 §4.1 接缝，全部可由代码现状核验）：

1. **先完成外置存储裁定**：Q-03 / Q-13 裁定前不动 S1（本条 §4.3）。
2. **operation 幂等索引先跨实例原子化**（S2/S3）：否则网关恢复链（gateway `:148-154`）在多实例下破坏「唯一幂等索引」语义。
3. **重启恢复先有所有权/去重**（S5）：否则 QUEUED 任务会被重复执行。
4. **镜像保留保护集的多主机聚合同步纳入**（S7）：防止保护集不全导致在途任务镜像被清理。
5. **与 7.1.3 的关系**：多实例扩展前必须 Q-06 决策（观测写锁外置）——该要求**归属 7.1.3 措辞**，本条仅交叉引用（§3），不代写其正文、不替代其判定。
6. cmc==1 不变量与多实例扩展相互独立，不得互相搭车放开（S9；恢复路径见 `nacos_config.py:117-120`）。

---

## 5. 诚实度声明

1. 全文**不宣称**task_store 或沙箱服务「已集群安全」「分布式安全」或具备任何多实例正确性。当前实现的保证边界 = **单机、单实例（per-instance）、单进程状态 + 单文件持久化**（§1、§2）。
2. 文中每一条事实声明都给出代码锚点或源文档章节指引，可逐条对照核验（§6）。未核验的内容（源文档原文、base SHA）均已显式标注，未混入事实声明。
3. 核验过程中发现的**已知文档/代码出入**（如实记录，不在本切片内修改）：
   - `config.py:182-186` 字段注释称 cmc「Default 5」，而 `load_config` 的 env 默认值实为 `"1"`（`config.py:245`）——注释与实际默认不符。
   - `pythonSandboxService/README.md:14,31-52` 仍描述旧接口 `POST /execute` 与 `AF_SANDBOX_MAX_CONCURRENCY` 默认 2；`main.py` 当前接口为 `/tasks` 系列（`main.py:436-529`），compose 该键默认 20（`docker-compose.yml:623`）。
   - `CANCELED` 状态存在（`models.py:15`）但无任何置为 CANCELED 的写路径/取消端点（§2.4）。
4. 本文档不引入任何未经验证的行为描述；对源文档章节号（7.1.3/7.1.9/8.1.1/D25 §4.5/Q-03/Q-06/Q-13）的引用仅表示交叉引用关系，不构成对其原文的复述。

---

## 6. 可核验性对照表

| # | 声明 | 核验依据（文件:行 / 章节） |
|---|---|---|
| C1 | 部署为单实例：单一服务定义、固定容器名、无 replicas | `docker-compose.yml:614,617,680-683` |
| C2 | 网关单一固定 URL 访问沙箱 | `docker-compose.yml:701`；`pythonSandboxGatewayService/src/main/resources/application.yml:25`；`pythonSandboxGatewayService/src/main/java/world/willfrog/sandbox/service/PythonSandboxGatewayServiceImpl.java:30,100` |
| C3 | task_store 运行时状态 = 进程内内存 dict（tasks） | `pythonSandboxService/app/task_store.py:59` |
| C4 | operation 索引 = 进程内内存 dict，node-local | `task_store.py:60,105-108,133-154` |
| C5 | 并发保护为进程内 `threading.RLock`，无跨进程效力 | `task_store.py:58` |
| C6 | 本地索引为单文件 state.json，每次全量文档原子重写（mkstemp+fsync+os.replace+目录 fsync） | `task_store.py:156-179`（类 docstring 自述 single-file atomic store `:50-54`） |
| C7 | state.json 路径键与默认值；容器内/宿主机双视角 | `config.py:200,263`；`docker-compose.yml:646,658,676`；`deploy_latest.sh:570`；`docker.env.example:61`；`notes-w5/D04-path-key-inventory.md` §1.2 P4/P5、R10 |
| C8 | schema 版本 v1/v2，未知版本 fail-closed | `task_store.py:26-28,138-142` |
| C9 | operation_id 语法 `runId:toolCallId:attempt`、指纹为小写 sha256 | `task_store.py:17-18,127-131` |
| C10 | 幂等创建：绑定一致返回原任务；不一致抛冲突（409） | `task_store.py:42-46,63-94`；`main.py:481-485` |
| C11 | operation 恢复链定位：「createTask 不确定结果恢复的唯一幂等索引」 | `PythonSandboxGatewayServiceImpl.java:148-154`；`main.py:504-514` |
| C12 | 重启恢复只读本实例状态：QUEUED 重排、RUNNING 终态化 | `task_store.py:110-125`；`main.py:390-391` |
| C13 | 运行期不重读文件（启动时装载一次） | `task_store.py:56-61`（`_load` 仅由 `__init__` 调用）；`main.py:68-69` |
| C14 | 创建/查询/结果端点全走全局 task_store | `main.py:436-493,496-501,504-514,517-529`；状态持久化点 `main.py:202,206,345` |
| C15 | 无取消端点；CANCELED 仅为枚举成员 | `main.py` 全文无 cancel 路由；`models.py:15`；`main.py:523`；`retry_classification.py:16` |
| C16 | cmc==1 三层强制：启动 fail-fast / 单键热更拒绝 / 整份 payload 拒绝 | `nacos_config.py:121-126,197-221,273-287` |
| C17 | cmc env 默认 "1"、校验 >=1；compose 设 1 且注释一任务一容器 | `config.py:245-249`；`docker-compose.yml:649-650` |
| C18 | cmc==1 理由：全局 sitecustomize.py 写/删未 task-local；恢复路径有注释记载 | `nacos_config.py:90-97,102-120`；`config.py:277-284` |
| C19 | Nacos 动态白名单仅 containerMaxConcurrency + 四个输出上限；task_store 路径/并发 worker 数不可热更 | `nacos_config.py:18-19`；`notes-w5/D04-path-key-inventory.md` §1.3 |
| C20 | 任务队列为进程内 asyncio.Queue；worker 数 = max(1, max_concurrency) | `main.py:70,388`；compose 默认 20 `docker-compose.yml:623` |
| C21 | 执行面 node-local：挂载 docker.sock | `docker-compose.yml:677` |
| C22 | 宿主机侧 state.json 消费：镜像保留保护集 | `prune_runtime_images.sh:167-211`；`deploy_latest.sh:570`；`docker.env.example:61` |
| C23 | 7.1.3 / 7.1.9 / 8.1.1 / Q-06 归属与交叉引用（正文不重写） | 验收任务给定口径；源文档原文不在本 worktree（§7），章节号按任务指定引用 |
| C24 | Q-03 / Q-13 未裁定前不选外置存储 | 验收任务给定口径；源文档原文未找到（§7） |

---

## 7. 源文档检索记录（如实记录）

在 worktree 内按以下线索检索 26Q3 stage1 需求源文档（含 D25 7.1.4、§4.5、§3 红线、Q-03/Q-06/Q-13 原文）：

- 内容检索（全 worktree，ripgrep）：`7.1.4`、`D25`、`7.1.3|7.1.9|8.1.1`、`Q-03|Q-06|Q-13`、`26Q3|stage1|需求|验收`、`单实例|多实例|集群安全|写锁`、`D03|D04|D21|D22`——除 `notes-w5/` 三篇 W5 审计笔记（D04/D21/D22 切片产出）外无命中；`D25` 仅命中 `alphafrogDebugMCP/package-lock.json`（无关）。
- 目录检索：无 `requirements/` 目录；候选文档目录仅 `notes-w5/`、`pythonSandboxService/docs/`、`agentLangchainService/docs/`、`alphafrog-wiki/`，逐一排查无 26Q3 stage1 需求文档。
- 结论：**源文档不在本 worktree 内**。`notes-w5/D22-artifact-registry-inventory.md:4` 引用的规划文档 `D22-platform-boundary-cleanup-v1.md` 亦不在 worktree 内，佐证规划/需求文档存放在 worktree 之外。
- 影响：本文 §3、§4.3、§4.4 对源文档章节号的引用均为交叉引用占位，原文措辞待 wave owner 在 worktree 外核校；本文未据此编造任何源文档内容。
