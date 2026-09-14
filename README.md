# AlphaFrog

> AlphaFrog 是 2026 年 1 月开始持续迭代的金融 Agent 个人项目。支持传统金融数据抓取查询，同时重点开发金融 Agent 功能。目前运行在阿里云北京的三台机器上。
> 项目相关支出预计占个人权益资产投资总额的0.3%-0.6%。

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

## Coding Agent友好的基础设施

- 搭建了独立于生产的 Beta 测试环境：由持有密钥的开发机连接宿主机，使用控制器来创建、健康检查、蓝绿更新和排空回收，不需要手工维护容器。
- 支持按「泳道」隔离的并行测试，流量分发按「泳道 → 主 Beta → 生产」逐级回落。

## 下一版本（v1.3）计划

- 功能方面
  - 固定一批常用金融指标的计算方法；
  - 实现基本的、负载可控的跨表SQL查询工具；
  - 业务服务可观测性增强 and Agent Run 可观测性可视化；
  - 扩展Python沙箱功能，支撑批量稳定的大计算量沙箱请求；
  - RAG功能迁移至storage-cn机器，拉取更大量研报并探索半自动化的数据清洗流程。
- 性能方面
  - 构建评测本Agent在具体市场环境下**回测正确率**的benchmark，为下一大版本铺垫；
  - 优化本Agent在给定硬件资源下每小时的Agent Run吞吐。
- 研效方面
  - 下一版本开发尽量使用【泳道-主beta-生产环境】的三段式发布流程。

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
