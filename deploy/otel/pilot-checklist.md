# 单服务真实环境试点清单

本清单用于 `agent-langchain-service` 的获准环境试点。本机静态检查、Maven 构建和配置摘要校验不能代替这些步骤。

1. 构建或拉取服务镜像后，运行部署预检；确认 `image.digest` 等于该容器 `docker inspect` 返回的 `.Image`，并且五个资源属性齐全。
2. 启动服务后检查 Java Agent 加载日志。发起一条同时经过 HTTP、Dubbo、JDBC 的测试请求，在 Jaeger 中确认各段调用属于同一条 trace。
3. 在 Jaeger 中检查 `deployment.id`、`lane.tag`、`service.version`、`git.commit`、`image.digest` 五个资源属性；生产或 Beta 环境不得出现 `local`、`unknown`。
4. 在 VictoriaLogs 中按同一个 `trace_id` 找到 JSON 日志；日志的 `deployment` 必须等于 trace 的 `deployment.id`。基线实例与 Beta 实例必须能按该字段分别查询。
5. 重启采集器，确认它从持久化读取位置继续，不重复灌入已经确认发送的日志。临时阻断 VictoriaLogs，再恢复；确认磁盘发送队列恢复发送。
6. 向试点日志目录追加一行故意损坏的 JSON，确认 VictoriaLogs 收到原文并带 `parse.error=json_parse_failed`。
7. 抽查日志与 trace，确认没有 Authorization、Cookie、数据库密码、Nacos 三个认证变量、模型或搜索服务密钥；`db.statement` 的字面量应替换为 `?`。
8. 记录 24 小时内的 span 数、日志条数、应用日志字节数、采集器持久队列峰值、Jaeger 与 VictoriaLogs 磁盘增量。用这些数字决定 3 天本地日志、7 天后端保留的工作初值是否可持续。

试点尚未执行时，交付结论必须写“配置和静态合同已验证，真实 trace、日志、重启与后端故障恢复仍待获准环境执行”。
