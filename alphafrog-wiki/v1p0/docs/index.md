# AlphaFrog v1.0 Agent 文档

这组 MkDocs 页面用于说明 v1.0 Agent Run 客户端、轻量 TUI、压测脚本和人工排障会用到的 HTTP / SSE 接口。

## 阅读入口

- [快速开始](agent-run/quick-start.md)：接口族、认证方式、通用响应结构、客户端推荐调用顺序。
- [认证与运行生命周期](agent-run/auth-and-lifecycle.md)：登录、创建运行、取消、暂停、恢复、更新标题、删除运行。
- [SSE 事件流](agent-run/sse-stream.md)：实时事件、断线续传、事件 envelope、客户端处理边界。
- [查询与观测接口](agent-run/reads-and-observability.md)：运行列表、状态、结果、成本、事件、timeline、observability、trace 详情。
- [产物、反馈与追问](agent-run/artifacts-feedback-messages.md)：snapshot 分片、artifact 下载、export、feedback、follow-up messages。
- [错误与脚本覆盖](agent-run/errors-and-script-coverage.md)：通用错误、压测脚本实际覆盖范围。

## 写作约定

- 主体语言使用简体中文。英文只保留接口路径、字段名、事件名、状态名、类名和必要技术名词。
- 每个页面只承担一个读者任务，避免把接口清单、排障规则和脚本说明堆在同一页。
- 接口事实来自后端 controller、service、model 和 v1.0 flow client，不从临时运行日志反推文档。

## 主要代码来源

- `frontend/src/main/java/world/willfrog/alphafrogmicro/frontend/controller/AuthController.java`
- `frontend/src/main/java/world/willfrog/alphafrogmicro/frontend/controller/agent/AgentController.java`
- `frontend/src/main/java/world/willfrog/alphafrogmicro/frontend/controller/agent/AgentSseController.java`
- `frontend/src/main/java/world/willfrog/alphafrogmicro/frontend/service/AgentSseService.java`
- `frontend/src/main/java/world/willfrog/alphafrogmicro/frontend/model/agent/*.java`
- `test_scripts/agent-v1p0/agent_run_sse_load_test.py`
- `test_scripts/agent-v1p0/froglib/flow_client.py`
