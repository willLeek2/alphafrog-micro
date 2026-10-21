# Agentic POC 边界

本目录只包含静态并行 Agent 的实验代码，不属于生产主路径，也不会进入默认 Maven source roots。

- 仅在显式启用 `agentic-poc` Maven profile 时编译和运行。
- 生产 Run 仍由双池管线（`AgentRunPipeline` 接口，`DualPoolRunPipeline` 实现）按节点工作项驱动执行。
- POC 不得被生产 Spring 扫描、Dubbo 暴露或作为发布验收证据。

本地验证：

```bash
mvn -pl agentLangchainService -Pagentic-poc test
```
