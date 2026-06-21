# Advanced 市场数据查询指南

## 适用工具

- `searchAssetInfo(mode=advanced, ...)`
- `searchIndex(mode=advanced, ...)`（仅 `has_stock` 条件）

## 条件类型

| type | 用途 |
|------|------|
| `index_component` | 查指数成分股及权重（需 `index_code`） |
| `has_stock` | 查包含某成分股的指数列表（需 `stock_code`） |

同一请求内多个 condition 为 **AND**；condition 内 `|` 分隔的代码为 **OR**。

## 日期与快照语义

- 显式日期使用 `YYYYMMDD`。
- **用户未明确时效性时**：默认以数据截止日为基准取**近一年**区间。
- **指数成分股权重等快照型数据**：若用户未指定日期，优先取**最新公告期**完整快照，而非全历史扫描。
- `start_date=NONE` / `end_date=NONE` 表示“未指定”，工具层会解析为 latest snapshot 或合理默认区间，不要自行填 `19000101`–`20991231`。

## 返回结构

Advanced 结果落盘为 JSON dataset，并返回：

- `dataset_id`：完整结果文件
- `preview_rows`：前 N 条预览（默认 10，Nacos 可配）

响应可能包含 `empty_reason` / `upstream_error`；**0 条结果时先读这些字段**，区分真无数据与查询失败。

## ETF 两跳链

ETF 不属于指数成分股表。查“跟踪某指数的 ETF”时：

1. 先用 `searchAssetInfo` / `searchIndex` 找到目标指数代码
2. 再用 `has_stock` 或资产搜索查跟踪关系

## 并行限制

调用 `checkParallelLimits` 获取 `advanced.maxItems`（默认 3）：单个 condition 内 `|` 拼接的代码数不得超过该值。
