# Agentic POC 边界

本目录只包含静态并行 Agent 的实验代码，不属于生产主路径，也不会进入默认 Maven source roots。

- 仅在显式启用 `agentic-poc` Maven profile 时编译和运行。
- 生产 Run 仍由 `LangchainLinearRunPipeline` 规划并路由到 LINEAR/DAG 执行器。
- POC 不得被生产 Spring 扫描、Dubbo 暴露或作为发布验收证据。

本地验证：

```bash
mvn -pl agentLangchainService -Pagentic-poc test
```
