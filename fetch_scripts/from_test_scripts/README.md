# from_test_scripts — 预留目录

本目录为预留位置，用于存放未来经讨论后决定从项目根目录 `test_scripts/` 迁移至此的脚本。

## 背景

`test_scripts/` 目前包含多种类型的脚本：
- `auth/` — 用户注册、登录、设置相关流程
- `admin/` — 管理员权限、额度治理相关流程
- `data/` — 市场数据完整性校验、数据抓取相关流程
- `agent/` — Agent 运行观测、并行编排、工具缓存等流程
- `tools/` — 通用工具（HTTP 客户端、日志格式化等）

其中与"数据抓取/ingestion"职能相关的脚本（如 `data/market_data_integrity_flow.py` 等），在经团队讨论确认后，可能迁移至此目录，与 `rag_ingestion/` 统一归入 `fetch_scripts/` 管理。

## 当前状态

暂未迁入任何脚本。具体迁入哪些脚本、何时迁入，需经团队讨论后决定。
