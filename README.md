# AlphaFrog

> AlphaFrog 是 2026 年 1 月开始持续迭代的金融 Agent 个人项目。支持传统金融数据抓取查询，同时重点开发金融 Agent 功能。

## 技术栈选择

- **传统后端**：主要使用 Spring Boot、Nacos、Dubbo、Redis、RabbitMQ、PostgreSQL 等常见后端技术，服务之间通过 Dubbo/gRPC 协作，数据抓取任务通过队列调度。
- **Agent 框架**：使用 LangChain4j 接入模型调用和工具调用；使用 MeiliSearch / Qdrant 支撑资产搜索与年报 RAG 查询；使用 llm-sandbox 支持轻量的、有基本伸缩能力的 Python 沙箱。
- **部署方式**：当前主要通过 Docker Compose 管理本地和测试环境部署。

## 已实现的 Agent 功能

- 抓取和查询较大量 A 股市场多类资产数据，包括行情、交易日历、财务指标等基础信息。
- 根据请求难度，可决策 linear 和 DAG 两种执行模式；复杂任务可以拆成多个可观测节点执行，降低单次上下文压力，也便于定位局部失败。
- 支持自然语言触发资产搜索、批量查询、网页搜索、Python sandbox 计算，以及对已抓取公司年报的基础 RAG 查询。

## Agent 可观测性与鲁棒性

- 初步构建了 Agent 执行的可观测链路，包括 SSE 事件流、LLM 调用 / 工具调用轨迹、LLM 成本分析、基本的合成数据流水线与压测脚本等；不同类型的运行数据按使用场景设置不同 TTL，兼顾调试追踪、成本分析和 Redis 存储压力。
- 做了一些基本的鲁棒性优化：批量查询、查询结果复用、LLM 与外部搜索调用的可控重试、DAG 节点级重试等。

## 下一步计划

- 收敛/删除部分已经不再使用的微服务，让项目结构更清楚。
- 继续优化 RAG，尤其是检索质量和评测闭环。

## 实际部署

- 基本测试文档 coming soon，可查看 <https://alpha.frogwch.com/v1p0> 获取最新使用指引。
- 可访问 <https://alpha.frogwch.com/invitations> 获取新的试用用户。

## 快速开始

```bash
# 1. 克隆并配置环境
cp .env.example .env
# 编辑 .env 填写数据库、Redis、API Keys

# 2. 初始化数据库
psql -h your_host -U your_user -d alphafrog -f alphafrog_schema_full.sql

# 3. 构建并启动
bash build_all_images.sh
docker-compose up -d
```

更详细的部署说明见 [deploy_guide.md](./deploy_guide.md)。

## 参与构建本项目的 Slock agents

slock-codex-coder-mbp、slock-cckimi-Zhiyuan-mbp、slock-ccmax-Jiancheng-mbp、slock-dpsk-alen-mcp、slock-cursor-bob-mbp、slock-Cindy、slock-cursor-tracy-mbp、slock-grace-teacher-mbp、slock-wang-teacher-mbp

---

一切从相信开始 2019/11/27
