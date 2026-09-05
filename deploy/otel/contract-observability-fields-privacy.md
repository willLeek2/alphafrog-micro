# 合同：观测字段与隐私规则

本合同供 Compose 生成、日志 JSON 化、采集器透传、部署控制器、单服务试点和只读观测工具共同使用。下面的五字段清单同时约束定义、生成和验收；修改字段名、取值规则或验收方法时，必须同步修改所有消费方。

保留期限中的 7 天来自 [VictoriaLogs 官方保留策略](https://docs.victoriametrics.com/victorialogs/#retention)。Jaeger 的期限仍需按实际存储配置验证，不能从本合同推断现网已经满足目标。仓库当前固定 OpenTelemetry Java Agent 2.31.1；自动观测与配置语义以 [OpenTelemetry Java Agent 官方文档](https://opentelemetry.io/docs/zero-code/java/agent/) 为准。

---

## 1. 这件事解决什么问题

观测线上要同时看稳定实例和 beta 实例。Jaeger 里一条调用链要能回答：这是哪个部署、哪条泳道、哪次构建、哪份镜像。VictoriaLogs 里同一服务的两套日志要能按部署拆开查。做不到这两点，观测线部署的采集配置和端到端验收都对不齐。

本合同定义五个 span 资源属性名，以及日志行里的 `deployment` 字段。OpenTelemetry 的 `OTEL_RESOURCE_ATTRIBUTES` 默认为空；稳定实例和 Beta 实例都必须显式写入。

---

## 2. span 资源属性五字段（定义 / 生成 / 验收 同一清单）

五个字段写进纳入观测的 JVM 服务进程的 `OTEL_RESOURCE_ATTRIBUTES`。OpenTelemetry Java Agent（随 JVM 启动的自动观测组件）把它们变成该进程发出的全部 span（一次调用中的链路片段）的资源属性。服务名走单独的 `OTEL_SERVICE_NAME`，不进这五个字段。新增 JVM 服务，包括部署控制器在启用 Java Agent 时，也按同一规则接入。

`OTEL_RESOURCE_ATTRIBUTES` 的官方格式是 `key1=value1,key2=value2`。逗号和等号是分隔符。本合同要求值里出现逗号、等号、换行或回车时拒绝生成，不做百分号编码，避免生成端和验收端采用不同的解码方式。

| 字段名 | 定义 | 稳定实例的值 | beta 实例的值 | 谁生成、怎样注入 | 验收 |
|--------|------|--------------|---------------|------------------|------|
| `deployment.id` | 这条 telemetry 属于哪一次部署。人可读的部署名，不承担构建身份（构建身份是下面三列）。 | 固定字符串 `stable` | 本次部署的 deployment-id，与 Beta 部署单里的 id、环境变量 `AF_DEPLOYMENT_ID` 同一字符串 | 稳定：基础 compose 的 `x-otel-env` 锚点显式写出。beta：控制器生成 override 时写入。生成时做第 3 节字符校验，非法拒绝。 | Jaeger 打开该 trace 的 Process / Resource，五个键都在，且 `deployment.id` 与部署单（稳定侧=常量 `stable`）字节相等。 |
| `lane.tag` | 观测中的流量范围。泳道实例取泳道名；主 Beta 取 `main-beta`，但该值只用于观测，不会成为 Dubbo 静态路由标签。 | 固定字符串 `stable` | Beta 部署单的 `trafficScopeId`，可以是 `main-beta` 或具体泳道名 | 同上。值来自可信部署配置，不来自客户端请求头。 | 同一验收面。稳定、主 Beta 与各泳道 trace 可以分开过滤。 |
| `service.version` | 构建该镜像时写入的服务版本字符串。 | 构建/部署流水线注入的 `AF_BUILD_VERSION` | 控制器按本次构建产物写入，与部署单记录一致 | 稳定：部署环境文件提供 `AF_BUILD_VERSION`。beta：控制器写 override。compose 里允许 `${AF_BUILD_VERSION:-local}` 只给本机起容器。 | 与部署单记录的版本字节相等。生产与 beta 预检拒绝值为 `local` 或空。 |
| `git.commit` | 构建该镜像所用的完整 Git 提交对象 ID。 | 流水线注入的 `AF_BUILD_COMMIT` | 同上，与部署单记录一致 | 同上，变量名 `AF_BUILD_COMMIT`。 | 与部署单记录的提交字节相等。当前仓库是 SHA-1，值为 40 位小写十六进制。禁止短 hash。生产与 beta 预检拒绝值为 `unknown` 或空。 |
| `image.digest` | **该服务容器实际使用的本地 Image ID**（见 §2.4 两列，不是仓库清单摘要）。 | 该服务自己的 `AF_BUILD_IMAGE_ID_*`（§2.1 表） | 控制器在镜像已经在本机、容器启动之前，读取该服务镜像的 `docker inspect .Id` 写入 override | 稳定：构建/部署脚本在镜像构建或拉取完成之后、允许启动之前，逐服务读取并写入对应变量。beta：部署控制器对选定服务做同样的事。禁止 11 个服务共用一个进程级变量。 | 与**该容器** `docker inspect --format '{{.Image}}'` 的值精确相等。禁止只核对前缀，禁止拿部署单里的 `repository@sha256:...` 仓库摘要来充数。生产与 beta 预检拒绝值为 `unknown` 或空。 |

硬性规则：

1. 五个键缺任何一个，该实例的部署身份不成立。单服务试点按此判失败。
2. 验收看构建身份（`service.version` / `git.commit` / **该服务的** `image.digest`），对端 IP 只能说明流量到了哪台机器。
3. `deployment_generation_id`（Run 部署身份合同里的不可变执行代际，环境变量 `AF_DEPLOYMENT_GENERATION_ID`）**不是**本清单的字段，不要写进 `OTEL_RESOURCE_ATTRIBUTES`。
4. 保留值 `stable` 只用于稳定实例的 `deployment.id` 与 `lane.tag`。Run 部署身份合同的迁移代 `legacy-stable` 只出现在数据库列 `deployment_generation_id`，不出现在本清单。
5. `service.version` 与 `git.commit` 在一次部署里的多个 JVM 服务可以相同。每个服务的 `image.digest` 必须从该服务实际容器读取；不同服务复用同一不可变镜像时，值允许相同。

### 2.1 稳定实例：基础 Compose 与部署预检写入的字符串

`x-otel-env` 锚点只放协议与导出开关，以及稳定侧**共用的四项**所依赖的插值（`deployment.id` / `lane.tag` / `service.version` / `git.commit`）。**不要**把 `image.digest` 放进锚点。各服务块自己写完整的 `OTEL_RESOURCE_ATTRIBUTES` 五字段字符串，前四项与锚点约定相同，第五项用本服务变量。

```text
# x-otel-env 锚点（共用，不含 image.digest）
OTEL_EXPORTER_OTLP_ENDPOINT: http://jaeger:4318
OTEL_EXPORTER_OTLP_PROTOCOL: http/protobuf
OTEL_TRACES_EXPORTER: otlp
OTEL_METRICS_EXPORTER: none
OTEL_LOGS_EXPORTER: none
AF_DEPLOYMENT_ID: stable

# 各服务块（以 agent-langchain-service 为例）
OTEL_RESOURCE_ATTRIBUTES: "deployment.id=stable,lane.tag=stable,service.version=${AF_BUILD_VERSION:-local},git.commit=${AF_BUILD_COMMIT:-unknown},image.digest=${AF_BUILD_IMAGE_ID_AGENT_LANGCHAIN_SERVICE:-unknown}"
```

`OTEL_LOGS_EXPORTER=none` 的原因：日志走「文件 → 采集器 → VictoriaLogs」。这里不关，同一行会被送两次。

镜像 ID 环境变量命名规则：`AF_BUILD_IMAGE_ID_` + Compose 服务名转大写、连字符改下划线。当前业务 JVM 服务如下；`python-sandbox-service` 不是 Java，不在本表内。新增 JVM 服务按同一规则加一行，不引入无法对应具体容器的共用 `AF_BUILD_IMAGE_DIGEST`。

| compose 服务名 | 环境变量 |
|----------------|----------|
| `domestic-stock-service` | `AF_BUILD_IMAGE_ID_DOMESTIC_STOCK_SERVICE` |
| `domestic-index-service` | `AF_BUILD_IMAGE_ID_DOMESTIC_INDEX_SERVICE` |
| `domestic-fund-service` | `AF_BUILD_IMAGE_ID_DOMESTIC_FUND_SERVICE` |
| `domestic-listed-asset-service` | `AF_BUILD_IMAGE_ID_DOMESTIC_LISTED_ASSET_SERVICE` |
| `domestic-fetch-service` | `AF_BUILD_IMAGE_ID_DOMESTIC_FETCH_SERVICE` |
| `admin-service` | `AF_BUILD_IMAGE_ID_ADMIN_SERVICE` |
| `portfolio-service` | `AF_BUILD_IMAGE_ID_PORTFOLIO_SERVICE` |
| `agent-langchain-service` | `AF_BUILD_IMAGE_ID_AGENT_LANGCHAIN_SERVICE` |
| `external-info-service` | `AF_BUILD_IMAGE_ID_EXTERNAL_INFO_SERVICE` |
| `python-sandbox-gateway-service` | `AF_BUILD_IMAGE_ID_PYTHON_SANDBOX_GATEWAY_SERVICE` |
| `frontend` | `AF_BUILD_IMAGE_ID_FRONTEND` |

生成时点：镜像已经构建或拉取到本机之后、容器允许启动之前。构建前只有提交和版本，不能填写 `image.digest`。部署脚本对每个将要启动的 JVM 服务执行 `docker inspect --format '{{.Id}}' <本机镜像>`（或等价地读将要使用的镜像 ID），写入上表对应变量，再和即将写入 `OTEL_RESOURCE_ATTRIBUTES` 的值比较，一致才允许启动。

### 2.2 beta 实例：部署控制器写进 override 的字符串

控制器在**该服务镜像已经在 beta 机本地、容器启动之前**写入，值里不使用 `local` / `unknown` 兜底。每个被拉起的服务写自己的五字段字符串，`image.digest` 取该服务镜像的本地 Image ID，不复用其它服务的值：

```text
OTEL_RESOURCE_ATTRIBUTES: "deployment.id=<deployment-id>,lane.tag=<泳道值>,service.version=<版本>,git.commit=<提交>,image.digest=<该服务本地 Image ID>"
AF_DEPLOYMENT_ID: "<deployment-id>"
```

`<deployment-id>` 与 `AF_DEPLOYMENT_ID`、部署单 id 必须是同一字符串。部署单另存「不可变镜像引用」（见 §2.4 左列），不要把那一列抄进 `image.digest`。

### 2.3 部署预检（稳定侧由脚本检查，Beta 侧由部署控制器检查）

插值完成之后检查，任一失败则拒绝启动 / 拒绝 create：

- 五个键都存在。
- `deployment.id`、`lane.tag` 非空，且通过第 3 节字符校验。
- `service.version` 不是 `local`、不是空。
- `git.commit` 不是 `unknown`、不是空，且匹配 `^[0-9a-f]{40}$`。
- `image.digest` 不是 `unknown`、不是空，且匹配 `^sha256:[0-9a-f]{64}$`。
- 该服务的 `image.digest` 等于该服务即将使用（或已经 `create` 出）的容器 `inspect .Image`，不等于部署单里的仓库摘要列。
- 五个值都不含 `,` `=` `\n` `\r`。
- 每个服务的 `image.digest` 插值必须指向该服务自己的环境变量；不同变量经实际镜像核对后可以得到相同值。

本机开发起容器可以用 `local` / `unknown`。那条路径不走生产预检、不走 beta 控制器 create。

### 2.4 镜像身份必须分成两列（不要混用）

仓库清单摘要和容器本地 Image ID 都长得像 `sha256:` + 64 位十六进制，但它们是两个对象。一次 `docker pull repository@sha256:...` 之后，本地 Image ID 仍可能与清单摘要不同。

| 列 | 存在哪 | 值从哪来 | 谁用来做什么 |
|----|--------|----------|--------------|
| 不可变镜像引用（含仓库清单摘要） | 部署单 / 专用部署 Compose 的 `image:` | `repository@sha256:<64位小写十六进制>` | 部署控制器按摘要拉取、config 精确比较、禁止按 tag 起容器 |
| 容器实际 Image ID | span 资源属性 `image.digest`；环境变量 `AF_BUILD_IMAGE_ID_*` | `docker inspect` 的 `.Id`（对镜像）或容器的 `.Image`。格式同样是 `sha256:` + 64 位小写十六进制，**不含**仓库名、**不含** `@` | 观测验收、启动预检。只和该容器实际 Image ID 比 |

把左列填进 `image.digest` 会让单服务试点和泳道端到端验收的「与容器实际 Image ID 精确相等」整批失败，也会让服务镜像身份失真。稳定侧由 `docker-compose.yml`、`deploy/otel/prepare-runtime-env.sh` 与 `deploy/otel/verify-static-contract.py` 共同生成和检查；Beta 侧由部署控制器生成和检查。两侧生成资源属性时都只读右列。

---

## 3. 值的字符校验（生成端与预检共用）

对 `deployment.id`、`lane.tag`、`service.version`、`git.commit`、`image.digest` 五个值：

- 禁止：逗号 `,`、等号 `=`、换行 `\n`、回车 `\r`。
- 禁止空值。
- `deployment.id` 另外遵守 Beta 部署与流量治理合同的字符集：`stable`，或 3 至 64 位小写字母、数字、连字符，首尾为字母或数字。保留字 `stable` 仅稳定实例使用。
- `lane.tag` 在稳定侧为 `stable`；Beta 侧取部署单的 `trafficScopeId`。主 Beta 使用 `main-beta`，各泳道使用自己的名称。

控制器生成 override 时做这项校验：非法则拒绝并重生成 / 拒绝 create，不写进环境变量。

---

## 4. 日志行内的 `deployment` 字段

日志 JSON 里的键名是 `deployment`，不是 `deployment.id`。日志 JSON 化使用下面的 logback `customFields` 配置。

| 项 | 规定 |
|----|------|
| JSON 键 | `deployment` |
| 值来源 | 进程环境变量 `AF_DEPLOYMENT_ID` |
| 稳定实例 | `AF_DEPLOYMENT_ID=stable`；logback 可写 `${AF_DEPLOYMENT_ID:-stable}` 作为本机缺省 |
| beta 实例 | 控制器注入 `AF_DEPLOYMENT_ID=<deployment-id>`，与 `deployment.id` 同一字符串 |
| 谁写入 | 业务服务自己写进每一行 JSON。一个采集器会同时读 stable 与 beta 的文件，采集器进程级变量不能用来盖这个字段。 |
| 采集器 | **透传、不覆盖、不补写。** 禁止用采集器自身环境变量、禁止对所有文件 `add` 同一个 `deployment` / `deployment.id`。解析成功后，这个字段留在日志属性里供 VictoriaLogs 过滤。不要像处理 `service` 那样，把它提升成采集器侧统一的资源属性。 |

日志 JSON 化的 `customFields` 固定为：

```xml
<customFields>{"service":"${appName}","deployment":"${AF_DEPLOYMENT_ID:-stable}"}</customFields>
```

`service` 仍由 logback 从 `spring.application.name` 来；采集器成功分支把 `service` 提升为 `service.name`，与 Jaeger 服务名对齐。`deployment` 不走这条提升。

查询口径（单服务试点验收）：VictoriaLogs 里同一服务的 stable 日志与 beta 日志，`deployment` 值不同，且能分别查出。

同一进程内不变量：`AF_DEPLOYMENT_ID` == `OTEL_RESOURCE_ATTRIBUTES` 里的 `deployment.id`。单服务试点抽一条请求，日志行的 `deployment` 与 Jaeger 资源属性 `deployment.id` 必须相等。

---

## 5. 两机连通与写入路径

beta 机器把 trace 发到 101 的 `4318`，把日志发到 101 的 `9428`。两套后端只有一份，靠本清单的字段区分稳定与 beta。连通清单之外的端口（含 Jaeger UI `16686`）不对 beta 机开放。

---

## 6. 脱敏字段清单

日志与 trace 会含用户问题、SQL、数据标识与错误详情。这些内容允许存在。令牌、密码、完整请求正文和下面列出的同类秘密不得进入日志、trace 属性或事件、采集器自身日志以及只读工具返回。

### 6.1 禁止出现的值

| 类别 | 具体项 |
|------|--------|
| 令牌与会话 | `Authorization` / `Bearer` / JWT 全文、`Cookie` / `Set-Cookie` 全文、刷新令牌 |
| 泳道入口口令 | `AF_LANE_TAG_PASSPHRASE`（请求头口令：不写日志、不回传响应、不进 span 属性或事件） |
| 数据库口令 | 生产库写口令、beta 库口令、`market_reader` 只读口令；含连接串里的密码段；错误消息不得回显完整连接串 |
| Nacos 凭据 | 管理员口令、发布口令、各服务读账号口令；Nacos 2.5 的 `NACOS_AUTH_TOKEN`、`NACOS_AUTH_IDENTITY_KEY`、`NACOS_AUTH_IDENTITY_VALUE` 也全部禁止记录 |
| 机器与仓库 | SSH 私钥、镜像仓库管理凭据 |
| API 密钥 | `AF_AGENT_API_KEY` 及各模型 / 搜索 provider 的 key。若日志框架必须打一个引用，只保留「已配置 / 未配置」，不打前 4 后 4 这种仍可拼回的片段 |
| 完整请求正文 | HTTP 请求体、HTTP 响应体；客户端提问若与令牌或密码处于同一字段，整个字段打码。普通用户问题可以进入经过审查的业务日志，但不得因此打开通用请求体采集 |

### 6.2 写入侧怎么落实（采集器不做二次脱敏）

写进去的内容按「已经过本清单」对待。新的日志点上线前对照本表。

Java Agent 2.31.1（Compose 挂上时保持这些默认，不要为排查打开；配置语义见 [OpenTelemetry 自动观测配置](https://opentelemetry.io/docs/zero-code/java/agent/instrumentation/)）：

- `OTEL_INSTRUMENTATION_COMMON_DB_STATEMENT_SANITIZER_ENABLED` 保持默认开启。官方行为：`db.statement` 里的字面量换成 `?`，JDBC 绑定参数不会进 span。
- 不配置 HTTP 请求头 / 响应头采集。官方默认不采集；打开后才会把 `Authorization`、`Cookie` 一类头写进 span。
- 不配置 HTTP 请求体 / 响应体采集。
- `OTEL_LOGS_EXPORTER=none`，避免 javaagent 再送一遍日志。

应用日志：

- 口令类环境变量不进 logback pattern，不进 MDC。
- 异常信息若可能带连接串，捕获处去掉 user/password 再打。

### 6.3 明确允许出现的内容

用户问题、SQL 结构（已脱敏的 `db.statement`）、内部 `userId`、Run / trace id、错误类型与堆栈（`stack_trace` 由 LogstashEncoder 折成单字段）。手机号、邮箱当前不在强制清单内；主动记录这些值的日志仍需单独评估用途和访问范围。

---

## 7. 访问权限

| 数据 | 存在哪 | 谁可以读 | 谁可以写 |
|------|--------|----------|----------|
| trace（含五字段） | 101 机器上的 Jaeger（UI 16686，OTLP 4318） | 项目成员，用途=排障与验收 | 仅各服务 javaagent → 4318 |
| 业务 JSON 日志 | 101 机器上的 VictoriaLogs（9428） | 同上 | 仅 otel-collector → 9428 |
| 应用本地滚动文件 | 各服务容器 `/app/logs/app.log` | 该机器上的部署 / 登录账号 | 仅该服务进程 |
| 采集器偏移与发送队列 | 采集器持久卷 `/var/lib/otelcol` | 部署账号 | 仅采集器进程 |
| 部署单 / 全局状态 | Beta 部署与流量治理合同规定的目录 | 部署账号 | 仅控制器 |

首版 Jaeger 与 VictoriaLogs 不另建账号体系，按现状放在部署内网。后续若把 UI 暴露到更大网段，先补认证再扩大访问。agent 侧只读查询走以后的只读观测工具（带审计与最小返回字段），不给通用查询入口。

VictoriaLogs 的删除 HTTP 接口（`POST /delete/run_task` 等）默认应保持对非本机关闭。开启时只绑回环。MCP 工具不得调用删除接口。

---

## 8. 保留期限

下面的期限是开始试点时使用的配置初值。单服务试点必须记录 span 数、字节数和每日写入量，再判断容量是否足以保留全部数据。实测容量不足时，只调整本节期限或采样比例，不改变字段合同。

| 数据 | 工作初值 | 依据与调整入口 |
|------|----------|----------------|
| 应用本地滚动日志 | 3 天；单文件 50MB；单服务总量 500MB | 当前 logback 滚动配置。11 个业务服务的理论上限约 5.5GB。这是后端暂时不可用时的原始副本。 |
| 本地压缩文件 `.log.gz` | 随上面 3 天滚动删掉 | 只供人工取证。采集器 `include` 只匹配 `*.log`，不自动补读压缩文件。 |
| VictoriaLogs | 7 天 | [VictoriaLogs 官方文档](https://docs.victoriametrics.com/victorialogs/#retention)规定未设置 `-retentionPeriod` 时默认保留 7 天。磁盘上限由试点实测补齐。 |
| Jaeger trace | 7 天（目标） | 需要核对实际 Jaeger 保留配置与 Badger 数据卷。Badger 嵌入存储没有表级生存期限；具体清理方式以实际容器参数和磁盘策略为准。 |
| 采集器持久队列 | 缓冲，不是保留策略 | 短故障用。应用本地 3 天文件才是原始副本。自动重放步骤属后置项。 |
| 部署单与控制器状态记录 | 随 Beta 部署与流量治理合同 | 观测后端里该 deployment 的日志 / trace **不随 beta 部署删除**（事后排障还要查），到期由本表自动清除。 |

单服务试点若结论是「100% 保留不可持续」，保留策略改为「错误 100% + 成功按比例」。改动写回本节，并通知观测线端到端验收。

---

## 9. 删除方法

- **到期自动删**：VictoriaLogs 按 `-retentionPeriod` 丢掉过期分区。Jaeger 按第 8 节目标，由实际存储参数或磁盘清理执行。应用本地文件由 logback 滚动删除。
- **按 deployment 删实例**：部署控制器与 Beta 部署合同的删除只注销服务实例、排空并清理容器和部署目录。观测后端数据保留到到期。
- **按查询条件删日志（人工）**：在 101 本机、删除接口仅回环可访问的前提下，使用 VictoriaLogs `POST /delete/run_task?filter=<LogsQL>`。操作留谁、何时、哪条 filter。不提供远程删除 API。
- **按时间窗口删 trace（人工）**：Jaeger Badger 没有公开的「按 trace id 删除一条」稳定接口。需要按用户或按事故清除时，工作方式是时间窗口 + 数据卷清理，接受短暂停窗口。具体命令等单服务试点看清现网存储参数后补进排障手册，本版不编造。
- **紧急全量**：停写入，清空 Jaeger Badger 数据卷与 VictoriaLogs `-storageDataPath`。只允许 101 宿主机、部署账号执行。

按用户要求删除时，使用内部 `userId` 定位，写入时不依赖手机号或邮箱全文。trace 侧受 Badger 接口限制，当前只能承诺按时间窗口处理，不能承诺稳定地删除单条 trace。

---

## 10. 观测线部署验收

下面列出本合同负责的检查。Java Agent 是否加载、跨服务 span 是否连接，还要在真实服务环境验证。

1. 稳定实例任取一条带请求上下文的 trace：五个资源属性都在；`deployment.id=stable`；`lane.tag=stable`；`service.version` / `git.commit` 不是 `local` / `unknown`（生产与 beta 环境）；`image.digest` 等于**该容器**的 `inspect .Image`，且来自该服务自己的 `AF_BUILD_IMAGE_ID_*`，不是共用变量、不是部署单仓库摘要。
2. beta 实例一条 trace：五个资源属性都在；`deployment.id` / `lane.tag` / `service.version` / `git.commit` 与本次部署单一致；`image.digest` 与**该容器**实际 Image ID 精确相等，不等于部署单里的 `repository@sha256:...` 仓库摘要。
3. 同一条请求：VictoriaLogs 按 `trace_id` 查到的日志行，`deployment` 字段等于该 trace 的 `deployment.id`。
4. 同一服务的 stable 与 beta 日志能按 `deployment` 分别查出，值不同。
5. 采集器配置里找不到用进程级变量覆盖 `deployment` 的 `add` / `replace`。
6. 坏 JSON 行带着 `parse.error` 到达 VictoriaLogs；这由采集器配置与单服务试点验证。
7. 抽查 span 属性与日志正文：不含第 6 节禁止项。Nacos 认证三变量 `NACOS_AUTH_TOKEN`、`NACOS_AUTH_IDENTITY_KEY`、`NACOS_AUTH_IDENTITY_VALUE` 必须都抽到（键名和值都不出现）；`db.statement` 中的字面量已被替换成 `?`。
8. 第 7 步容量数字记下来之后，回写第 8 节保留策略。

---

## 11. 本清单不负责的事

- `docker-compose.yml`、`prepare-runtime-env.sh` 和预检脚本的后续部署编排改动；本合同只固定它们必须共同满足的字段与核对规则。
- 各服务 `logback-spring.xml` 除 `customFields` 以外的滚动参数；滚动数字见第 8 节。
- 采集器 YAML 语法与 `file_storage`。
- OpenTelemetry Java Agent 制品升级；固定版本保存在 `deploy/otel/javaagent.version`。
- 部署单字段与字符集的完整定义（见 Beta 部署与流量治理合同）。本清单只要求观测值与之兼容。
- Run 表的 `deployment_id` / `deployment_generation_id`（见 Run 部署身份合同）。

---

## 12. 部署前需要确认的环境参数

1. 第 6 节脱敏清单。
2. 第 7 节访问范围（内网成员可读、删除接口仅本机）。
3. 第 8 节工作初值：本地 3 天、VictoriaLogs 7 天、Jaeger 目标 7 天。
4. 第 9 节：trace 侧只能做到时间窗口 / 数据卷级删除，做不到稳定的按单条 trace 删除。

这些环境参数确认以前，可以按本合同实现字段与写入侧脱敏；后端保留天数与删除开关以实际观测机配置为准，单服务试点记录现状。
