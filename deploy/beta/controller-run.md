# Beta 部署控制器运行说明

`betaDeploymentController` 是 Beta 机器上的宿主机常驻进程。它读取部署单、启动候选容器、检查健康状态，并在候选可用后停止旧容器。生产机器不运行这个控制器。

## 构建与安装文件

在仓库根目录使用 Java 17 构建：

```bash
mvn -pl betaDeploymentController -am install
```

可执行文件生成在 `betaDeploymentController/target/betaDeploymentController-0.0.1-SNAPSHOT.jar`。把它安装为 `/opt/alphafrog-beta/controller/betaDeploymentController.jar`。同一次安装还需要准备以下文件：

- 把 `betaDeploymentController/bin/tcp-healthcheck` 安装为 `/opt/alphafrog-beta/bin/tcp-healthcheck`，属主为运行账号，权限为 `0755`。
- 运行 `deploy/otel/fetch-javaagent.sh` 和 `deploy/otel/verify-javaagent.sh`，再把校验通过的 `deploy/otel/opentelemetry-javaagent.jar` 安装为 `/opt/alphafrog-beta/otel/opentelemetry-javaagent.jar`，权限为 `0444`。
- 把 `deploy/beta/controller.service` 安装为 `/etc/systemd/system/alphafrog-beta-controller.service`。
- 把 `deploy/beta/controller.env.example` 和 `deploy/beta/controller.yml.example` 分别复制为 `/etc/alphafrog-beta/controller.env` 和 `/etc/alphafrog-beta/controller.yml`，替换其中的地址占位值。

安装前创建不允许登录的 `alphafrog-beta` 系统账号，以及控制器需要的目录。`/opt/alphafrog-beta/controller`、`/opt/alphafrog-beta/bin` 和 `/opt/alphafrog-beta/otel` 由该账号读取；`/etc/alphafrog-beta` 及其 `services`、`secrets` 子目录只允许 `root` 和该账号读取。systemd 会以 `0700` 创建 `/var/lib/alphafrog-beta`，控制器再在其中保存状态、Compose 文件和日志目录。目标机器使用的 Docker socket 如果不属于 `docker` 组，还要把单元文件中的 `SupplementaryGroups` 改为实际组名。

API 凭证、`controller.env` 和每个服务的环境文件都可能包含密钥，不能提交到仓库。凭证文件只能包含至少 32 个非控制字符，不能带首尾空白或换行。下面的命令生成 64 个十六进制字符，并在写入时去掉换行：

```bash
sudo sh -c 'umask 077; openssl rand -hex 32 | tr -d "\\n" > /etc/alphafrog-beta/secrets/controller-api-token'
sudo chown alphafrog-beta:alphafrog-beta /etc/alphafrog-beta/secrets/controller-api-token
```

凭证文件的权限必须是 `0600`。`controller.env` 和每个服务的独立环境文件同样不得允许其他用户读取；服务环境文件名不能是 `.env`。安装完成后先用 `namei -l` 或等价工具逐层核对这些目录和文件没有符号链接、属主正确、权限没有放宽，再启动控制器。

## 控制器配置

systemd 单元通过 `/etc/alphafrog-beta/controller.env` 注入开关、监听地址、状态目录、Nacos 和观测配置。`SPRING_CONFIG_ADDITIONAL_LOCATION` 再加载 `/etc/alphafrog-beta/controller.yml` 中的机器和服务映射；这种写法可以原样保留 `agent-service` 等带连字符的服务名。

必须核对的环境变量如下：

| 变量 | 用途 |
| --- | --- |
| `AF_BETA_CONTROLLER_ENABLED` | 必须为 `true`，否则控制器运行组件不会装配。 |
| `AF_BETA_CONTROLLER_BIND_ADDRESS` / `AF_BETA_CONTROLLER_PORT` | 默认监听 `127.0.0.1:19090`。不要把内部接口直接暴露到公网。 |
| `AF_BETA_CONTROLLER_STATE_ROOT` | 保存状态、部署单、生成的 Compose 和服务日志。随附 systemd 单元只会准备默认目录 `/var/lib/alphafrog-beta`；改用其他路径时，必须预先创建精确目录并授予 `alphafrog-beta` 写权限。若仍要让 systemd 托管该目录，再按目标系统的 systemd 规则调整单元。 |
| `AF_BETA_CONTROLLER_API_TOKEN_FILE` | Bearer 凭证文件路径。凭证内容不写进 systemd 单元。 |
| `AF_BETA_HEALTHCHECK_SCRIPT` | 挂载进候选容器的固定 TCP 探针。 |
| `AF_BETA_NACOS_SERVER_ADDRESS` | 生产 Nacos 从 Beta 机器可访问的地址，不包含 `nacos://` 前缀。 |
| `AF_CONFIG_NACOS_NAMESPACE` / `AF_CONFIG_NACOS_USERNAME` / `AF_CONFIG_NACOS_PASSWORD` | Nacos 命名空间和可选鉴权。 |
| `AF_OTEL_TRACES_ENDPOINT` | Java 服务通过 Java Agent 发送轨迹时使用的生产 Jaeger OTLP HTTP 地址。控制器也会为非 Java 服务生成对应的 `OTEL_*` 环境变量；是否真正导出轨迹取决于服务自身实现。 |
| `AF_BETA_OTEL_JAVAAGENT_JAR` | 宿主机上已经校验的 OpenTelemetry Java Agent 文件。 |

`controller.yml` 为八个主 Beta 服务分别指定环境文件，并按需指定业务数据卷。控制器统一生成以下观测设置，因此服务环境文件里的同名值会被 Compose 的 `environment` 段覆盖：

- `OTEL_EXPORTER_OTLP_ENDPOINT`、`OTEL_EXPORTER_OTLP_PROTOCOL` 和三类导出开关；
- `OTEL_SERVICE_NAME` 与五项部署资源属性；
- Java 服务的 `JAVA_TOOL_OPTIONS` 和只读 Java Agent 挂载；
- `<AF_BETA_CONTROLLER_STATE_ROOT>/data/logs/<serviceName>:/app/logs` 日志挂载；状态根目录的默认值是 `/var/lib/alphafrog-beta`。

服务需要额外的 JVM 参数时，在 `controller.yml` 的 `java-tool-options` 中配置；控制器会在其末尾添加 Java Agent 参数。Python Sandbox 把 `java-agent-enabled` 设为 `false`，仍保留轨迹导出变量和日志目录，但不注入 Java 参数或挂载 Java Agent。`volumes` 只放业务卷，不能重复挂载 `/app/logs` 或 `/otel/javaagent.jar`。

八个业务服务使用到 PostgreSQL、Redis、RabbitMQ 或 Meilisearch 时，只连接 Beta 本机的对应依赖。Qdrant 同样只为以后进入 Beta 的服务预留本地实例，当前八个服务不使用它。Nacos/Dubbo 路由，以及 Jaeger 和 VictoriaLogs 观测后端由生产环境共用。每个服务环境文件都要按这个边界填写，不能把 Beta 业务数据连接到生产的同名依赖。

## 启动与检查

确认 `alphafrog-beta` 账号通过 `docker` 补充组访问 `/var/run/docker.sock`。如果目标系统的 Docker socket 使用其他组，需要在安装时同步修改单元的 `SupplementaryGroups`。

替换配置占位值、安装凭证和服务环境文件后执行：

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now alphafrog-beta-controller.service
sudo systemctl status alphafrog-beta-controller.service
```

带正确凭证的状态查询应返回控制器响应；错误凭证应被拒绝：

```bash
TOKEN="$(sudo cat /etc/alphafrog-beta/secrets/controller-api-token)"
curl -H "Authorization: Bearer ${TOKEN}" \
  http://127.0.0.1:19090/internal/beta/status/main-beta/agent-service
curl -i -H "Authorization: Bearer wrong-token" \
  http://127.0.0.1:19090/internal/beta/status/main-beta/agent-service
```

机器重启后确认服务自动启动。测试进程恢复时可以向 Java 主进程发送 `SIGKILL`，随后检查 systemd 是否按 `Restart=always` 重新拉起，并确认状态文件仍能读取。真实机器上的 Docker、Nacos、Jaeger、VictoriaLogs、开机自启和进程恢复必须另行完成并记录验证结果；仓库单元测试不会代替这些验证。
