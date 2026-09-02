# 单服务真实环境试点清单

本清单用于 `agent-langchain-service` 的获准环境试点。本机静态检查、Maven 构建和配置摘要校验不能代替这些步骤。

1. 构建或拉取服务镜像后，运行部署预检；完整部署一次选择全部 11 个 JVM 服务，确认任意两个服务的本地 Image ID 都不同。单独部署一个服务时只检查被选择的服务，不能证明它与未选择服务的镜像互不重复。启动容器后，确认 `image.digest` 等于该容器 `docker inspect` 返回的 `.Image`，并且五个资源属性齐全。
2. 部署前确认宿主机 `data/logs` 和试点服务子目录都由部署账号持有且权限为 `0700`；脚本遇到旧的 `0755` 或其他属主必须拒绝，不得自动放宽或改权。启动后确认服务容器可以写入 `app.log`，部署账号可以读取该文件，Collector 的实际用户是 Compose 指定的 `0:0` 且可以通过只读挂载读取，另一个普通宿主机账号不能进入日志目录。
3. 启动服务后检查 Java Agent 加载日志。发起一条同时经过 HTTP、Dubbo、JDBC 的测试请求，在 Jaeger 中确认各段调用属于同一条 trace。
4. 在 Jaeger 中检查 `deployment.id`、`lane.tag`、`service.version`、`git.commit`、`image.digest` 五个资源属性；生产或 Beta 环境不得出现 `local`、`unknown`。
5. 在 VictoriaLogs 中按同一个 `trace_id` 找到 JSON 日志；日志的 `deployment` 必须等于 trace 的 `deployment.id`。基线实例与 Beta 实例必须能按该字段分别查询。
6. 重启采集器，确认它从持久化读取位置继续，不重复灌入已经确认发送的日志。临时阻断 VictoriaLogs，再恢复；确认磁盘发送队列恢复发送。
7. 向试点日志目录追加一行故意损坏的 JSON，确认 VictoriaLogs 收到原文并带 `parse.error=json_parse_failed`。
8. 抽查日志与 trace，确认没有 Authorization、Cookie、数据库密码、Nacos 三个认证变量、模型或搜索服务密钥；`db.statement` 的字面量应替换为 `?`。
9. 记录 24 小时内的 span 数、日志条数、应用日志字节数、采集器持久队列峰值、Jaeger 与 VictoriaLogs 磁盘增量。用这些数字决定 3 天本地日志、7 天后端保留的工作初值是否可持续。

试点尚未执行时，交付结论必须写“配置和静态合同已验证，真实 trace、日志、重启与后端故障恢复仍待获准环境执行”。
