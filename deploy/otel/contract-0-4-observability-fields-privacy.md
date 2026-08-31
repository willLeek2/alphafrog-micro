# 合同 0-4：观测字段与隐私规则

状态：组 0 行级交付（执行方 cursor-bob-mbp）。基线提交 `5dccbef7`（分支 `refactor/agent-codebase-refine`）。
消费方：1-1（compose 五字段与部署预检）、1-2（日志 JSON 化）、1-3（采集器透传）、1-5（五字段与分部署查询验收）、2-4（beta 侧生成与字符校验）、后续 4-1 / 5-1。
变更纪律：下面「五字段清单」是定义、生成、验收三处共用的同一份表。改字段名、改取值规则或改验收方法，必须同步改这三处，以及所有消费行。字段名是对外契约。

依据：开工计划一 plan-a 组 0 行 0-4；落地版上篇 §1.2 / §1.3 / §1.4 / §1.6 / §1.7；中篇 §3(b) 第 1 步与两机连通清单；开工计划二 §一 第 1、2、6 条。隐私口径由 frog 确认后生效（计划二 §一 第 6 条）。

本文件接手了 ccqwen 退出草稿的结构，按落地版与官方文档重写。VictoriaLogs 保留期初值按官方默认 7 天写，不用草稿里的 14 天。

---

## 1. 这件事解决什么问题

观测线上要同时看稳定实例和 beta 实例。Jaeger 里一条调用链要能回答：这是哪个部署、哪条泳道、哪次构建、哪份镜像。VictoriaLogs 里同一服务的两套日志要能按部署拆开查。做不到这两点，组 1 的采集配置和组 4 的验收都对不齐。

落地版已经定了五个 span 资源属性名，以及日志行里一个 `deployment` 字段。OpenTelemetry 的 `OTEL_RESOURCE_ATTRIBUTES` 默认为空，不写就没有 `deployment.id`。稳定实例也必须显式写入，不能指望缺省值。

---

## 2. span 资源属性五字段（定义 / 生成 / 验收 同一清单）

五个字段写进每个 JVM 服务进程的 `OTEL_RESOURCE_ATTRIBUTES`。javaagent 把它们变成这条进程发出的全部 span 的资源属性。服务名走单独的 `OTEL_SERVICE_NAME`，不进这五个字段。

`OTEL_RESOURCE_ATTRIBUTES` 的官方格式是 `key1=value1,key2=value2`。逗号和等号是分隔符。落地版要求：**值里出现逗号、等号或换行时拒绝并重生成，不做百分号编码**。本清单一并禁止回车。官方允许百分号编码（见 OpenTelemetry Resource SDK），AlphaFrog 首版不用这条路，避免生成端和验收端各解一套。

| 字段名 | 定义 | 稳定实例的值 | beta 实例的值 | 谁生成、怎样注入 | 验收 |
|--------|------|--------------|---------------|------------------|------|
| `deployment.id` | 这条 telemetry 属于哪一次部署。人可读的部署名，不承担构建身份（构建身份是下面三列）。 | 固定字符串 `stable` | 本次部署的 deployment-id，与 0-3 部署单里的 id、环境变量 `AF_DEPLOYMENT_ID` 同一字符串 | 稳定：基础 compose 的 `x-otel-env` 锚点显式写出。beta：控制器生成 override 时写入。生成时做第 3 节字符校验，非法拒绝。 | Jaeger 打开该 trace 的 Process / Resource，五个键都在，且 `deployment.id` 与部署单（稳定侧=常量 `stable`）字节相等。 |
| `lane.tag` | 流量泳道标签。与 Dubbo 的 `-Ddubbo.provider.tag` 同一字符串。 | 固定字符串 `stable` | 当前唯一活动部署的标签。首版固定为 `lane-test`（全局并发部署上限=1） | 同上。标签来自控制器状态，不来自客户端请求头。 | 同一验收面。稳定与 beta 两条 trace 的 `lane.tag` 能分开过滤。 |
| `service.version` | 构建该镜像时写入的服务版本字符串。 | 构建/部署流水线注入的 `AF_BUILD_VERSION` | 控制器按本次构建产物写入，与部署单记录一致 | 稳定：部署环境文件提供 `AF_BUILD_VERSION`。beta：控制器写 override。compose 里允许 `${AF_BUILD_VERSION:-local}` 只给本机起容器。 | 与部署单记录的版本字节相等。生产与 beta 预检拒绝值为 `local` 或空。 |
| `git.commit` | 构建该镜像所用的完整 Git 提交对象 ID。 | 流水线注入的 `AF_BUILD_COMMIT` | 同上，与部署单记录一致 | 同上，变量名 `AF_BUILD_COMMIT`。 | 与部署单记录的提交字节相等。当前仓库是 SHA-1，值为 40 位小写十六进制。禁止短 hash。生产与 beta 预检拒绝值为 `unknown` 或空。 |
| `image.digest` | **该服务容器实际使用的本地 Image ID**（见 §2.4 两列，不是仓库清单摘要）。 | 该服务自己的 `AF_BUILD_IMAGE_ID_*`（§2.1 表） | 控制器在镜像已经在本机、容器启动之前，读取该服务镜像的 `docker inspect .Id` 写入 override | 稳定：构建/部署脚本在镜像构建或拉取完成之后、允许启动之前，逐服务读取并写入对应变量。beta：2-4 对选定服务做同样的事。禁止 11 个服务共用一个进程级变量。 | 与**该容器** `docker inspect --format '{{.Image}}'` 的值精确相等。禁止只核对前缀，禁止拿部署单里的 `repository@sha256:...` 仓库摘要来充数。生产与 beta 预检拒绝值为 `unknown` 或空。 |

硬性规则：

1. 五个键缺任何一个，该实例的部署身份不成立。1-5 试点按此判失败。
2. 验收看构建身份（`service.version` / `git.commit` / **该服务的** `image.digest`），对端 IP 只能说明流量到了哪台机器。
3. `deployment_generation_id`（2-6 的不可变执行代际，环境变量 `AF_DEPLOYMENT_GENERATION_ID`）**不是**本清单的字段，不要写进 `OTEL_RESOURCE_ATTRIBUTES`。
4. 保留值 `stable` 只用于稳定实例的 `deployment.id` 与 `lane.tag`。2-6 的迁移代 `legacy-stable` 只出现在数据库列 `deployment_generation_id`，不出现在本清单。
5. `service.version` 与 `git.commit` 在一次部署里 11 个 JVM 服务可以相同（同一次 `deploy_latest.sh`、同一个提交）。`image.digest` 必须每服务不同，因为 11 个服务是 11 个镜像。

### 2.1 稳定实例：1-1 要写进 compose 的字符串

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

镜像 ID 环境变量命名规则：`AF_BUILD_IMAGE_ID_` + compose 服务名转大写、连字符改下划线。11 个 JVM 服务（落地版 §1.1，`python-sandbox-service` 不是 Java，本清单不覆盖）固定如下。新增 JVM 服务按同一规则加一行，禁止再引入共用的 `AF_BUILD_IMAGE_DIGEST`。

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

### 2.2 beta 实例：2-4 控制器写进 override 的字符串

控制器在**该服务镜像已经在 beta 机本地、容器启动之前**写入，值里不使用 `local` / `unknown` 兜底。每个被拉起的服务写自己的五字段字符串，`image.digest` 取该服务镜像的本地 Image ID，不复用其它服务的值：

```text
OTEL_RESOURCE_ATTRIBUTES: "deployment.id=<deployment-id>,lane.tag=<泳道值>,service.version=<版本>,git.commit=<提交>,image.digest=<该服务本地 Image ID>"
AF_DEPLOYMENT_ID: "<deployment-id>"
```

`<deployment-id>` 与 `AF_DEPLOYMENT_ID`、部署单 id 必须是同一字符串。部署单另存「不可变镜像引用」（见 §2.4 左列），不要把那一列抄进 `image.digest`。

### 2.3 部署预检（生产与 beta；1-1 实现，2-4 在 beta 创建路径复用）

插值完成之后检查，任一失败则拒绝启动 / 拒绝 create：

- 五个键都存在。
- `deployment.id`、`lane.tag` 非空，且通过第 3 节字符校验。
- `service.version` 不是 `local`、不是空。
- `git.commit` 不是 `unknown`、不是空，且匹配 `^[0-9a-f]{40}$`。
- `image.digest` 不是 `unknown`、不是空，且匹配 `^sha256:[0-9a-f]{64}$`。
- 该服务的 `image.digest` 等于该服务即将使用（或已经 `create` 出）的容器 `inspect .Image`，不等于部署单里的仓库摘要列。
- 五个值都不含 `,` `=` `\n` `\r`。
- 禁止 11 个服务的 `image.digest` 插值指向同一个环境变量。

本机开发起容器可以用 `local` / `unknown`。那条路径不走生产预检、不走 beta 控制器 create。

### 2.4 镜像身份必须分成两列（不要混用）

仓库清单摘要和容器本地 Image ID 都长得像 `sha256:` + 64 位十六进制，但它们是两个对象。一次 `docker pull repository@sha256:...` 之后，本地 Image ID 仍可能与清单摘要不同。

| 列 | 存在哪 | 值从哪来 | 谁用来做什么 |
|----|--------|----------|--------------|
| 不可变镜像引用（含仓库清单摘要） | 部署单 / 专用部署 Compose 的 `image:` | `repository@sha256:<64位小写十六进制>` | 2-4 按摘要拉取、config 精确比较、禁止按 tag 起容器 |
| 容器实际 Image ID | span 资源属性 `image.digest`；环境变量 `AF_BUILD_IMAGE_ID_*` | `docker inspect` 的 `.Id`（对镜像）或容器的 `.Image`。格式同样是 `sha256:` + 64 位小写十六进制，**不含**仓库名、**不含** `@` | 观测验收、启动预检。只和该容器实际 Image ID 比 |

把左列填进 `image.digest` 会让 1-5 / 4-4 的「与容器实际 Image ID 精确相等」整批失败，也会让 11 个服务看起来像用了同一份镜像。1-1 和 2-4 生成资源属性时只读右列。

---

## 3. 值的字符校验（生成端与预检共用）

对 `deployment.id`、`lane.tag`、`service.version`、`git.commit`、`image.digest` 五个值：

- 禁止：逗号 `,`、等号 `=`、换行 `\n`、回车 `\r`。
- 禁止空值。
- `deployment.id` 另外遵守 0-3 规定的 deployment-id 字符集与长度；本清单要求它必须同时满足上一条。0-3 字符集尚未合入时，生成端先按「小写字母、数字、连字符，长度 1–63，不能以连字符开头或结尾」执行，并与 0-3 对齐一次。保留字 `stable` 仅稳定实例使用。
- `lane.tag` 首版稳定侧为 `stable`，beta 侧为 `lane-test`。后续若改标签，仍须通过本校验。

控制器生成 override 时做这项校验：非法则拒绝并重生成 / 拒绝 create，不写进环境变量。

---

## 4. 日志行内的 `deployment` 字段

日志 JSON 里的键名是 `deployment`，不是 `deployment.id`。这是落地版 §1.3 的 logback `customFields` 写法，1-2 按它抄。

| 项 | 规定 |
|----|------|
| JSON 键 | `deployment` |
| 值来源 | 进程环境变量 `AF_DEPLOYMENT_ID` |
| 稳定实例 | `AF_DEPLOYMENT_ID=stable`；logback 可写 `${AF_DEPLOYMENT_ID:-stable}` 作为本机缺省 |
| beta 实例 | 控制器注入 `AF_DEPLOYMENT_ID=<deployment-id>`，与 `deployment.id` 同一字符串 |
| 谁写入 | 业务服务自己写进每一行 JSON。一个采集器会同时读 stable 与 beta 的文件，采集器进程级变量不能用来盖这个字段。 |
| 采集器（1-3） | **透传、不覆盖、不补写。** 禁止用采集器自身环境变量、禁止对所有文件 `add` 同一个 `deployment` / `deployment.id`。解析成功后，这个字段留在日志属性里供 VictoriaLogs 过滤。不要像处理 `service` 那样，把它提升成采集器侧统一的资源属性。 |

1-2 的 `customFields` 固定为：

```xml
<customFields>{"service":"${appName}","deployment":"${AF_DEPLOYMENT_ID:-stable}"}</customFields>
```

`service` 仍由 logback 从 `spring.application.name` 来；采集器成功分支把 `service` 提升为 `service.name`，与 Jaeger 服务名对齐。`deployment` 不走这条提升。

查询口径（1-5 验收）：VictoriaLogs 里同一服务的 stable 日志与 beta 日志，`deployment` 值不同，且能分别查出。

同一进程内不变量：`AF_DEPLOYMENT_ID` == `OTEL_RESOURCE_ATTRIBUTES` 里的 `deployment.id`。1-5 抽一条请求，日志行的 `deployment` 与 Jaeger 资源属性 `deployment.id` 必须相等。

---

## 5. 两机连通与写入路径（中篇 §3(b)）

beta 机器把 trace 发到 101 的 `4318`，把日志发到 101 的 `9428`。两套后端只有一份，靠本清单的字段区分稳定与 beta。连通清单之外的端口（含 Jaeger UI `16686`）不对 beta 机开放。

---

## 6. 脱敏字段清单

落地版 §1.7：日志与 trace 会含用户问题、SQL、数据标识与错误详情。这些内容允许存在。必须从日志、trace 的属性 / 事件 / 日志正文、采集器自身日志、MCP 工具返回里拿掉的是令牌、密码、完整请求正文，以及下面扩写的同类秘密。

### 6.1 禁止出现的值

| 类别 | 具体项 |
|------|--------|
| 令牌与会话 | `Authorization` / `Bearer` / JWT 全文、`Cookie` / `Set-Cookie` 全文、刷新令牌 |
| 泳道入口口令 | `AF_LANE_TAG_PASSPHRASE`（请求头口令：不写日志、不回传响应、不进 span 属性或事件） |
| 数据库口令 | 生产库写口令、beta 库口令、`market_reader` 只读口令；含连接串里的密码段；错误消息不得回显完整连接串 |
| Nacos 凭据 | 管理员口令、发布口令、各服务读账号口令；Nacos 2.5 认证三变量必须一并禁止：`NACOS_AUTH_TOKEN`、`NACOS_AUTH_IDENTITY_KEY`、`NACOS_AUTH_IDENTITY_VALUE`（计划二 §二 第 1 条同一组；身份键没有默认值，漏写会让验收把脱敏当成已完整） |
| 机器与仓库 | SSH 私钥、镜像仓库管理凭据 |
| API 密钥 | `AF_AGENT_API_KEY` 及各模型 / 搜索 provider 的 key。若日志框架必须打一个引用，只保留「已配置 / 未配置」，不打前 4 后 4 这种仍可拼回的片段 |
| 完整请求正文 | HTTP 请求体、HTTP 响应体、客户端完整提问原文若与令牌或密码同段出现时整段打码。普通用户问题按落地版允许进日志；不要为了「方便排查」打开请求体采集 |

### 6.2 写入侧怎么落实（采集器不做二次脱敏）

写进去的内容按「已经过本清单」对待。新的日志点上线前对照本表。

javaagent（1-1 挂上时保持这些默认，不要为排查打开）：

- `OTEL_INSTRUMENTATION_COMMON_DB_STATEMENT_SANITIZER_ENABLED` 保持默认开启。官方行为：`db.statement` 里的字面量换成 `?`，JDBC 绑定参数不会进 span。
- 不配置 HTTP 请求头 / 响应头采集。官方默认不采集；打开后才会把 `Authorization`、`Cookie` 一类头写进 span。
- 不配置 HTTP 请求体 / 响应体采集。
- `OTEL_LOGS_EXPORTER=none`，避免 javaagent 再送一遍日志。

应用日志：

- 口令类环境变量不进 logback pattern，不进 MDC。
- 异常信息若可能带连接串，捕获处去掉 user/password 再打。

### 6.3 明确允许出现的内容

用户问题、SQL 结构（已脱敏的 `db.statement`）、内部 `userId`、Run / trace id、错误类型与堆栈（`stack_trace` 由 LogstashEncoder 折成单字段）。手机号、邮箱不是落地版强制脱敏项；若某条日志主动打印了全文，后续审查可以收紧，本版不把它写成组 1 的阻塞项。

---

## 7. 访问权限

| 数据 | 存在哪 | 谁可以读 | 谁可以写 |
|------|--------|----------|----------|
| trace（含五字段） | 101 机器上的 Jaeger（UI 16686，OTLP 4318） | 项目成员，用途=排障与验收 | 仅各服务 javaagent → 4318 |
| 业务 JSON 日志 | 101 机器上的 VictoriaLogs（9428） | 同上 | 仅 otel-collector → 9428 |
| 应用本地滚动文件 | 各服务容器 `/app/logs/app.log` | 该机器上的部署 / 登录账号 | 仅该服务进程 |
| 采集器偏移与发送队列 | 采集器持久卷 `/var/lib/otelcol` | 部署账号 | 仅采集器进程 |
| 部署单 / 全局状态 | 0-3 规定的目录 | 部署账号 | 仅控制器 |

首版 Jaeger 与 VictoriaLogs 不另建账号体系，按现状放在部署内网。后续若把 UI 暴露到更大网段，先补认证再扩大访问。agent 侧只读查询走以后的 5-1 工具（带审计与最小返回字段），不给通用查询入口。

VictoriaLogs 的删除 HTTP 接口（`POST /delete/run_task` 等）默认应保持对非本机关闭。开启时只绑回环。MCP 工具不得调用删除接口。

---

## 8. 保留期限

计划一要求：容量与「是否 100% 保留」等 1-5 试点拿出 span 数、字节数、每日写入量再下结论。下面是**工作初值**，不是拍板数字。frog 按计划二 §一 第 6 条确认后生效；1-5 若实测撑不住，只改本节数字，不改字段名。

| 数据 | 工作初值 | 依据与调整入口 |
|------|----------|----------------|
| 应用本地滚动日志 | 3 天；单文件 50MB；单服务总量 500MB | 落地版 §1.3 的 logback 滚动策略。11 个服务最坏约 5.5GB。这是后端宕机时的原始副本。 |
| 本地压缩文件 `.log.gz` | 随上面 3 天滚动删掉 | 只供人工取证。采集器 `include` 只匹配 `*.log`，不自动补读压缩文件（落地版 §1.4）。 |
| VictoriaLogs | 7 天 | VictoriaLogs 官方默认 `-retentionPeriod` 为 7 天（文档：docs.victoriametrics.com/victorialogs/#retention）。磁盘上限另见计划二 §一 第 5 条，等试点补数值。 |
| Jaeger trace | 7 天（工作目标） | 计划二要求 frog 核对现网 Jaeger 的保留策略与 Badger 数据卷。Badger 嵌入存储没有 Cassandra 那种表级 TTL；实现手段以 frog 现网容器参数与磁盘清理为准，本清单不编造标志名。1-5 记录每日写入量后由 frog 确认能否维持 7 天。 |
| 采集器持久队列 | 缓冲，不是保留策略 | 短故障用。应用本地 3 天文件才是原始副本。自动重放步骤属后置项。 |
| 部署单与删除墓碑 | 随 0-3 | 观测后端里该 deployment 的日志 / trace **不随 beta 部署删除**（事后排障还要查），到期由本表自动清除。 |

1-5 第 7 步若结论是「100% 保留不可持续」，保留策略改为「错误 100% + 成功按比例」。改动写回本节，并通知 4-1。

---

## 9. 删除方法

- **到期自动删**：VictoriaLogs 按 `-retentionPeriod` 丢掉过期分区。Jaeger 按第 8 节工作目标，由 frog 现网参数或磁盘清理执行。应用本地文件由 logback 滚动删除。
- **按 deployment 删容器**：2-4 / 0-3 的安全删除只清理容器、卷、部署目录。观测后端数据保留到到期。
- **按查询条件删日志（人工）**：在 101 本机、删除接口仅回环可访问的前提下，使用 VictoriaLogs `POST /delete/run_task?filter=<LogsQL>`。操作留谁、何时、哪条 filter。不提供远程删除 API。
- **按时间窗口删 trace（人工）**：Jaeger Badger 没有公开的「按 trace id 删除一条」稳定接口。需要按用户或按事故清除时，工作方式是时间窗口 + 数据卷清理，接受短暂停窗口。具体命令等 1-5 看清现网存储参数后补进排障手册，本版不编造。
- **紧急全量**：停写入，清空 Jaeger Badger 数据卷与 VictoriaLogs `-storageDataPath`。只允许 101 宿主机、部署账号执行。

按用户要求删除时：用内部 `userId` 定位（写入时不依赖手机号 / 邮箱全文）。trace 侧受 Badger 接口限制，能承诺的是时间窗口级，不是单条 GDPR 级删除。这一条写进 frog 确认清单。

---

## 10. 给组 1 的验收对照（1-5 / §1.6 会用到的字段项）

下面只列本清单负责的检查。javaagent 能否加载、跨服务 span 是否连上，仍按落地版 §1.6 第 1–4、6 步。

1. 稳定实例任取一条带请求上下文的 trace：五个资源属性都在；`deployment.id=stable`；`lane.tag=stable`；`service.version` / `git.commit` 不是 `local` / `unknown`（生产与 beta 环境）；`image.digest` 等于**该容器**的 `inspect .Image`，且来自该服务自己的 `AF_BUILD_IMAGE_ID_*`，不是共用变量、不是部署单仓库摘要。
2. beta 实例一条 trace：五个资源属性都在；`deployment.id` / `lane.tag` / `service.version` / `git.commit` 与本次部署单一致；`image.digest` 与**该容器**实际 Image ID 精确相等，不等于部署单里的 `repository@sha256:...` 仓库摘要。
3. 同一条请求：VictoriaLogs 按 `trace_id` 查到的日志行，`deployment` 字段等于该 trace 的 `deployment.id`。
4. 同一服务的 stable 与 beta 日志能按 `deployment` 分别查出，值不同。
5. 采集器配置里找不到用进程级变量覆盖 `deployment` 的 `add` / `replace`。
6. 坏 JSON 行带着 `parse.error` 到达 VictoriaLogs（落地版 §1.4；本清单不改这条，1-3 / 1-5 执行）。
7. 抽查 span 属性与日志正文：不含第 6 节禁止项。Nacos 认证三变量 `NACOS_AUTH_TOKEN`、`NACOS_AUTH_IDENTITY_KEY`、`NACOS_AUTH_IDENTITY_VALUE` 必须都抽到（键名和值都不出现）；`db.statement` 中的字面量已被替换成 `?`。
8. 第 7 步容量数字记下来之后，回写第 8 节保留策略。

---

## 11. 本清单不负责的事

- `docker-compose.yml` 的具体补丁（1-1 单写者）。
- 11 份 `logback-spring.xml` 除 `customFields` 以外的滚动参数（1-2 按落地版 §1.3 抄；滚动数字已在第 8 节引用）。
- 采集器 YAML 语法与 `file_storage`（1-3）。
- javaagent 版本钉死（1-4）。
- 部署单字段与字符集的完整定义（0-3）。本清单只要求观测值与之兼容。
- Run 表的 `deployment_id` / `deployment_generation_id`（2-6）。

---

## 12. 待 frog 确认（计划二 §一 第 6 条）

1. 第 6 节脱敏清单。
2. 第 7 节访问范围（内网成员可读、删除接口仅本机）。
3. 第 8 节工作初值：本地 3 天、VictoriaLogs 7 天、Jaeger 目标 7 天。
4. 第 9 节：trace 侧只能做到时间窗口 / 数据卷级删除，做不到稳定的按单条 trace 删除。

未确认前，组 1 可以按本清单实现字段与写入侧脱敏；后端保留天数与删除开关以 101 现网为准，1-5 记录现状。
