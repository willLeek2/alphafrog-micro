# Beta 部署控制器

这个服务实现 `deploy/beta/` 中固定的部署与流量治理合同。它接收已经校验的部署单，逐个启动候选实例，并检查 Docker 健康状态。导出 Dubbo 提供者的服务由自身注册到 Nacos，控制器只读确认候选端点已经可用；frontend 这类非提供者服务只检查容器健康。候选就绪后，控制器更新本地实例角色并正常停止旧容器；旧服务自行注销。旧实例在公共处理期限扣除收尾余量后的窗口内自然完成已经受理的工作，剩余时间用于把本代未结束的 Run 写成明确失败并退出，整个公共期限到达后才由容器运行时强制停止。

控制器只管理服务实例和流量。它不会查询 Agent Run、Todo、数据库事务、业务重试或业务恢复状态。候选实例在切流前失败时，旧实例与旧路由保持不变；主 Beta 和各个泳道通过不同的 `trafficScopeId` 分开管理。

## 启用前配置

控制器默认关闭，只有 `AF_BETA_CONTROLLER_ENABLED=true` 时才装配运行组件。HTTP 默认只监听 `127.0.0.1:19090`。所有 `/internal/beta/` 接口都要求 Bearer 凭证；凭证从 `AF_BETA_CONTROLLER_API_TOKEN_FILE` 指向的普通文件读取，文件不能是符号链接，内容至少 32 个字符且权限不得宽于 `0600`。

控制器本身不作为 Dubbo 服务注册到 Nacos，也不开启 Dubbo 服务监听。它不会调用 Agent 退役接口，也不持有 Agent 退役凭证。Agent 在自然处理窗口内完成已经受理的工作，并在窗口结束后为本代剩余 Run 写入明确失败。若这次收尾没有成功，其他 Agent 只有在 Nacos 连续一个确认期限都找不到目标代际，并在写数据库前再次确认仍无注册后，才按每轮固定数量上限分批补写失败；这项补漏不以是否发生过容器强停为前提，也不承诺在固定时间内清完。

每台 Beta 机器需要配置以下属性：

- `docker-host`：Docker 守护进程地址，只接受 `unix`、`tcp` 或 `ssh` 协议。
- `bind-ip`：候选容器发布宿主端口时绑定的确定 IP 地址。
- `routable-address`：其他服务实际能够访问的地址，也是 Nacos 注册地址。

每个服务还需要配置一个已经存在的环境文件，以及部署时必须挂载的业务卷。环境文件由运维按服务摘取需要的变量后放在控制器主机上，部署单不能提交任意环境变量或挂载路径。文件必须是普通文件、不是符号链接，权限不得宽于 `0600`，每个服务使用不同路径，文件名不能是整份生产 `.env`。控制器生成专用 Compose 之后，先用 `compose config --quiet` 确认环境文件可读；再用 `compose config --no-env-resolution --format json` 核对保留下来的 `env_file` 恰好指向该服务文件，并把 `.env` 当作数据卷挂进去的配置一并拒绝。默认的 `config --format json` 会把环境文件展开后丢掉 `env_file`，不能拿来做路径核验。

固定 TCP 探针应从 `bin/tcp-healthcheck` 安装到控制器主机的 `/opt/alphafrog-beta/bin/tcp-healthcheck`，保持可执行，并以同一路径只读挂载到候选容器。当前探针需要候选镜像提供 Bash。仓库里的生产 `.env` 由 `.gitignore` 排除，不得提交。

示例配置：

```yaml
alphafrog:
  beta-controller:
    enabled: true
    application-drain-seconds: 60
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
- `DELETE /internal/beta/deployments/{deploymentId}`：按服务名顺序停止、排空并删除整个部署；提供者实例由服务自行注销。
- `POST /internal/beta/deployments/{deploymentId}/services/{serviceName}/retry`：在外部事实确定时重试已记录的失败。
- `POST /internal/beta/reconcile`：立即执行一个持久化步骤；后台也会按固定间隔执行。
- `GET /internal/beta/status/{trafficScopeId}/{serviceName}`：读取服务、候选、排空进度和最近错误。

状态文件固定保存在 `/var/lib/alphafrog-beta/controller-state.json`，部署单固定保存在 `/var/lib/alphafrog-beta/deployments/<deployment-id>/manifest.json`。写入使用同目录临时文件、文件同步、原子重命名和父目录同步。状态文件只保存部署编排检查点，不是业务路由真相；调用方根据 Dubbo 原生标签路由、同区优先和 Nacos 当前注册选路。控制器启动时会校验两份 Schema、时间和 IP 格式断言及跨文件关系；无法证明事实一致时拒绝继续写外部资源。`application-drain-seconds` 是所有部署单共用的停止期限，部署单中的每个服务都必须与它相等；停止开始后保存的截止时间不会因控制器重启或重试而延长。

## 当前验证边界

本模块的普通单元测试使用内存中的 Docker 与 Nacos 替身，不会连接真实基础设施。真实 Beta 机器仍需验证 Docker 远程连接、镜像本地标识、提供者自注册到 Nacos 2.5.0、非提供者健康判断、Dubbo 标签与同区回落、SIGTERM 自然排空、期限强停和控制器进程重启。
