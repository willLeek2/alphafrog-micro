# Sub-agent POC 边界

本目录只包含子 Agent 实验代码，不属于生产主路径，也不会进入默认 Maven source roots。

- 仅在显式启用 `subagent-poc` Maven profile 时编译和运行。
- 生产工具声明与路由不从本目录派生；spawn/wait 的正式能力由后续 D06 契约交付。
- POC 不得被生产 Spring 扫描、Dubbo 暴露或作为发布验收证据。

本地验证：

```bash
mvn -pl agentLangchainService -Psubagent-poc test
```
