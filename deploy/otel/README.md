# OpenTelemetry 观测部署说明

这一目录保存 AlphaFrog 第一版可观测部署所需的固定制品、日志采集器配置、部署前身份检查和真实环境试点清单。Java 服务通过 OpenTelemetry Java Agent 把调用轨迹直接发送到 Jaeger 的 4318 端口；OpenTelemetry Collector 只读取应用写入磁盘的 JSON 日志，并把日志发送到 VictoriaLogs。

## 部署前准备

先下载并校验固定版本的 Java Agent：

```bash
bash deploy/otel/fetch-javaagent.sh
bash deploy/otel/verify-javaagent.sh
```

构建服务镜像以后、启动容器以前，部署脚本会调用 `prepare-runtime-env.sh`。这个脚本逐个读取本地镜像的 Docker Image ID，并写入权限为 `0600` 的 `deploy/otel/runtime.env`。每个服务使用自己的 `AF_BUILD_IMAGE_ID_*` 变量，不能用仓库清单摘要代替容器实际镜像身份。

稳定环境使用 `AF_DEPLOYMENT_ID=stable` 和 `AF_LANE_TAG=stable`。Beta 试点使用部署单里的部署标识和 `AF_LANE_TAG=lane-test`。生产和 Beta 启动前都必须拒绝 `service.version=local`、未知提交标识、非法部署标识以及无效 Image ID。

## 两台机器共用采集器配置

`otel-collector-config.yaml` 不写死 VictoriaLogs 所在机器。101 主机可以使用默认值：

```text
AF_OTEL_VICTORIALOGS_ENDPOINT=http://victorialogs:9428/insert/opentelemetry
```

Beta 主机运行同一版本采集器时，把该变量改成 101 主机可访问的地址。Collector 会在这个基础地址后追加 `/v1/logs`。两台机器都需要把应用日志根目录只读挂载到 `/var/log/apps`，并给 `/var/lib/otelcol` 提供持久卷，用来保存读取位置和尚未发送的日志队列。

Java 服务的轨迹导出地址由 `AF_OTEL_TRACES_ENDPOINT` 提供。轨迹不经过 Collector；Beta 主机必须把它设为 101 主机 Jaeger 4318 端口的可达地址。

## 本机静态验证

以下命令只检查仓库配置，不启动 Docker、服务或后端：

```bash
python3 deploy/otel/verify-static-contract.py
bash deploy/otel/verify-collector-config.sh
```

静态检查可以证明 11 个 JVM 服务的 Compose 环境、日志配置、每服务镜像变量和制品摘要互相一致。它不能证明 Collector 能在真实镜像中启动，也不能证明 Jaeger、VictoriaLogs、跨机网络、文件权限、持久队列和 Java Agent 自动埋点已经正常工作。

## 真实环境试点

真实试点按 `pilot-checklist.md` 执行。至少需要验证一条同时经过 HTTP、Dubbo 和 JDBC 的请求、同一请求的轨迹与日志关联、Collector 重启后的读取位置、VictoriaLogs 暂时不可用时的磁盘队列、损坏 JSON 的保留，以及 24 小时容量数据。试点完成前，交付状态只能写成“配置和静态合同已验证”。
