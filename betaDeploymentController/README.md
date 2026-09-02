# Beta 部署控制器

这个服务实现 `deploy/beta/` 中固定的部署与流量治理合同。它接收已经校验的部署单，逐个启动候选实例，联合检查 Docker 健康状态和 Nacos 注册状态，然后通过一次 `controller-state.json` 原子替换切换默认路由。切流以后，控制器先让旧实例停止接收新流量，再执行服务声明的停止前动作，最后给予应用完整的退出期限并清理旧实例。

控制器只管理服务实例和流量。它不会查询 Agent Run、Todo、数据库事务、业务重试或业务恢复状态。候选实例在切流前失败时，旧实例与旧路由保持不变；主 Beta 和各个泳道通过不同的 `trafficScopeId` 分开管理。

## 启用前配置

控制器默认关闭，只有 `AF_BETA_CONTROLLER_ENABLED=true` 时才装配运行组件。HTTP 默认只监听 `127.0.0.1:19090`。所有 `/internal/beta/` 接口都要求 Bearer 凭证；凭证从 `AF_BETA_CONTROLLER_API_TOKEN_FILE` 指向的普通文件读取，文件不能是符号链接，内容至少 32 个字符且权限不得宽于 `0600`。

Agent 实例的停止前动作使用另一份独立凭证。`AF_BETA_RETIREMENT_TOKEN_FILE` 指向的文件遵守相同的内容和权限限制。两份凭证都不会进入运行时状态、部署单、生成的 Compose 文件、命令行或接口响应。

控制器本身不作为 Dubbo 服务注册到 Nacos，也不开启 Dubbo 服务监听。只有在排空 Agent 旧实例时，控制器才按状态中保存的直接地址建立一次短连接，调用该实例的退役接口。

每台 Beta 机器需要配置以下属性：

- `docker-host`：Docker 守护进程地址，只接受 `unix`、`tcp` 或 `ssh` 协议。
- `bind-ip`：候选容器发布宿主端口时绑定的确定 IP 地址。
- `routable-address`：其他服务实际能够访问的地址，也是 Nacos 注册地址。

每个服务还需要配置一个已有的环境文件，以及部署时必须挂载的业务卷。环境文件由运维准备，部署单不能提交任意环境变量或挂载路径。固定 TCP 探针应从 `bin/tcp-healthcheck` 安装到控制器主机的 `/opt/alphafrog-beta/bin/tcp-healthcheck`，保持可执行，并以同一路径只读挂载到候选容器。当前探针需要候选镜像提供 Bash。

示例配置：

```yaml
alphafrog:
  beta-controller:
    enabled: true
    machines:
      beta-machine-1:
        docker-host: unix:///var/run/docker.sock
        bind-ip: 10.0.0.8
        routable-address: 10.0.0.8
    services:
      agent-service:
        env-file: /etc/alphafrog-beta/services/agent-service.env
        volumes:
          - /srv/alphafrog/shared:/srv/alphafrog/shared:rw
```

## 接口

- `PUT /internal/beta/deployments/{deploymentId}/manifest`：提交新部署或更高版本部署单。
- `DELETE /internal/beta/deployments/{deploymentId}`：按服务名顺序移除默认路由、排空并删除整个部署。
- `POST /internal/beta/deployments/{deploymentId}/services/{serviceName}/retry`：在外部事实确定时重试已记录的失败。
- `POST /internal/beta/reconcile`：立即执行一个持久化步骤；后台也会按固定间隔执行。
- `GET /internal/beta/routes/{trafficScopeId}/{serviceName}`：从当前原子状态读取默认实例和精确端点。读取失败或默认实例为空时，调用方必须停止转发新请求。
- `GET /internal/beta/status/{trafficScopeId}/{serviceName}`：读取服务、候选、排空进度和最近错误。

状态文件固定保存在 `/var/lib/alphafrog-beta/controller-state.json`，部署单固定保存在 `/var/lib/alphafrog-beta/deployments/<deployment-id>/manifest.json`。写入使用同目录临时文件、文件同步、原子重命名和父目录同步。控制器启动时会校验两份 Schema、时间和 IP 格式断言、全部跨文件关系，以及部署单领先全局状态的唯一允许恢复窗口；无法证明事实一致时拒绝继续写外部资源。

## 当前验证边界

本模块的普通单元测试使用内存中的 Docker、Nacos 和退役接口替身，不会连接真实基础设施。真实 Beta 机器仍需验证 Docker 远程连接、镜像本地标识、Nacos 2.5.0 注册和禁流、服务停止前动作、SIGTERM 排空、控制器进程重启，以及入口和服务间调用组件对路由切换的端到端行为。
