# Advanced 市场数据查询指南

## 适用工具与触发方式

advanced 模式由两个工具承担，按工具入口 + `mode=advanced` 触发：

- `searchAssetInfo(query=..., assetTypes=..., marketScope=...)` — `query` 是 JSON 字符串，且顶层含 `mode: "advanced"`；工具内部解析为 `AdvancedSearchRequest`。
- `searchIndex(keyword=...)` — `keyword` 是 JSON 字符串，且顶层含 `mode: "advanced"`；走 advanced 时工具只支持 `has_stock` 这一种 condition。

工具签名（agent 看到的 @Tool 描述）：

| 工具 | 必填 | 可选 | 备注 |
|---|---|---|---|
| `searchAssetInfo` | `query` (JSON) | `assetTypes`（默认 `stock,etf,index,off_exchange_fund` 全部）/ `marketScope`（仅 `domestic`） | `query` 用 `\|` 分隔或 JSON 数组表示多个 |
| `searchIndex` | `keyword` (JSON) | — | `keyword` 同 `\|` 批量 |

`query` 字符串以 `{` 开头时按 JSON 解析；解析失败或缺 `mode: "advanced"` 时按普通 keyword 走。

## 入口 JSON 结构（AdvancedSearchRequest）

顶层字段：

| 字段 | 必填 | 用途 |
|---|---|---|
| `mode` | 是 | 必须 `"advanced"`；顶层或嵌套在 `query` 内都可 |
| `asset_type` | 是 | `stock` / `etf` / `index` / `off_exchange_fund`；别名 `assetType` / `assetTypes` / `asset_types` 都接受 |
| `name` | 否 | 资产名称搜索词，与 `has_stock` / `index_component` 一起用 |
| `conditions` | 是 | 至少 1 条；多条时 AND 关系 |

`asset_type` 与 `condition.type` 必须匹配，否则抛 `INVALID_ARGUMENT`：

- `searchAssetInfo(asset_type=stock)` 配 `index_component`
- `searchAssetInfo(asset_type=etf)` 配 `has_stock`

## Condition 字段（AdvancedSearchCondition）

| 字段 | 必填 | 适用 type | 用途 |
|---|---|---|---|
| `type` | 是 | — | `index_component` / `has_stock` |
| `index_code` | `index_component` 必填 | `index_component` | 指数代码（TuShare 格式 `000300.SH`）；支持 `\|` 分隔多值 |
| `stock_code` | `has_stock` 必填 | `has_stock` | 股票代码（`000001.SZ`）；支持 `\|` 分隔多值 |
| `start_date` | 否 | 全部 | `YYYYMMDD` 或字面量 `"NONE"` |
| `end_date` | 否 | 全部 | `YYYYMMDD` 或字面量 `"NONE"` |
| `min_weight` | 否 | `index_component` | 0.00–1.00；不传表示不设下限 |
| `max_weight` | 否 | `index_component` | 0.00–1.00；不传表示不设上限 |

`start_date` / `end_date` 都为 `NONE` 时，工具取该指数最新公告期单日快照（`getMaxTradeDateByTsCode` + `getIndexWeightsByTsCodeAndTradeDate`），不是全历史扫描。

## 组合语义

- 同一 condition 内多值用 `|` 分隔代码，工具按 OR 拆开逐个查询再合并（去重保序）。
- 多条 conditions 之间是 AND：每条求结果后做集合交，只保留在每条结果中同时出现的 `ts_code`。
- `|` 数量限制：单 condition 内 `|` 拆分出的代码数不得超过 `checkParallelLimits().advanced.maxItems`（默认 3，clamp [1,20]）。超出抛 `BATCH_LIMIT_EXCEEDED`。

## 返回结构

工具返回 JSON，`data` 字段：

| 字段 | 含义 |
|---|---|
| `mode` | 固定 `"advanced"` |
| `asset_type` | 与请求一致 |
| `row_count` | 结果行数 |
| `dataset_id` | `{runId}-advanced-{toolName}-{assetType}-{8位 uuid}`；writer 关闭时为 `""` |
| `dataset_status` | `created` / `reused` / `inline` |
| `reused` | 是否命中既有 dataset（bool） |
| `preview_rows` | 前 N 条预览（默认 10，可在 Nacos 改） |
| `preview_limit` | 预览条数上限，与 `preview_rows.length` 取小 |
| `conditions_meta` | 每条 condition 的执行统计 |
| `empty_reason` | 仅 0 行时存在；例如 `no_matching_index_weights` |
| `upstream_error` | 上游 IDL 失败时存在 |

`dataset_id` 为空时，`data.dataset` 内联完整 dataset map（含 `schema_version=1`、`tool`、`asset_type`、`query`（canonicalQuery）、`conditions_meta`、`row_count`、`results[]`）。

行结构：

| 字段 | 含义 |
|---|---|
| `ts_code` | 命中的资产代码 |
| `name` | 资产名称 |
| `asset_type` | 资产类型 |
| `index_code` / `index_name` | ETF 两跳场景下回填（`searchAssetInfo(etf, has_stock)` 用） |
| `match_conditions` | per-condition 命中数组；第 i 个元素 = 第 i 条 condition 的命中记录（每条 = `[trade_date, weight]`） |

## ETF 两跳链

ETF 不在指数成分股表。`searchAssetInfo(asset_type=etf, has_stock)` 走两跳：

1. 第一跳：`has_stock` 反向查包含某 stock 的指数（`indexWeightDao.getLatestIndexWeightsByConCodeAndDateRange`）。
2. 第二跳：把查到的指数代码丢给 `domesticListedAssetService.searchListedAssets(query=indexCode, assetTypes=[etf])`，命中 `ListedAssetInfoItem.indexCode` / `indexName` 字段的 ETF。

结果行的 `index_code` / `index_name` 来自第二跳。

## 并行与配置限制

调 `checkParallelLimits` 取 `advanced` 组：

| 子字段 | 含义 | 默认 / clamp |
|---|---|---|
| `maxItems` | 单 condition `\|` 拆分上限 | 默认 3，clamp [1,20] |
| `previewRows` | 预览条数 | 默认 10，clamp [0,100] |
| `tools` | 适用工具列表 | `["searchIndex(mode=advanced)", "searchAssetInfo(mode=advanced)"]` |
| `argumentFormat` | 提示 | `conditions use \| separated index_code/stock_code values. Dates must be YYYYMMDD or NONE.` |

Nacos 热加载配置（`agent-llm.local.json`，由 `agent.llm.config-file` 指定文件，10s 轮询）：

- `tools.market_data.advanced.previewRows` → 预览条数
- `runtime.parallel.maxParallelQueriesInAdvancedMode` → 单 condition `\|` 上限

## 错误码

| 码 | 触发 |
|---|---|
| `INVALID_ARGUMENT` | 日期格式错 / asset_type 与 condition.type 不匹配 / 缺 `mode` |
| `BATCH_LIMIT_EXCEEDED` | 单 condition `\|` 拆分 > `maxItems` |
| `UPSTREAM_ERROR` | 上游 IDL 调用失败（`data` 上同时含 `upstream_error` 字段） |
| `NO_DATA` | 没找到任何结果 |
| `TOOL_ERROR` | writer / dao 内部异常 |

## 示例

### 例 1：searchAssetInfo(stock) + index_component，按成分股权重筛 000300.SH

```json
{
  "query": {
    "mode": "advanced",
    "asset_type": "stock",
    "name": "银行",
    "conditions": [
      {
        "type": "index_component",
        "index_code": "000300.SH",
        "start_date": "20240101",
        "end_date": "20241231",
        "min_weight": 0.01
      }
    ]
  }
}
```

期望：返回 000300.SH 在 2024 年期间、银行行业、权重 ≥ 1% 的成分股；`match_conditions[0]` = 每只股票在区间内的 `[trade_date, weight]` 列表。

### 例 2：searchAssetInfo(etf) + has_stock，反查 000001.SZ 所在指数的 ETF

```json
{
  "query": {
    "mode": "advanced",
    "asset_type": "etf",
    "name": "沪深300",
    "conditions": [
      {
        "type": "has_stock",
        "stock_code": "000001.SZ",
        "start_date": "20240101",
        "end_date": "20241231"
      }
    ]
  }
}
```

期望：先反查 000001.SZ 属于哪些指数（取 000300.SH），再列 `assetType=etf` 且 `indexCode=000300.SH` 的 ETF（如 `510300.SH` 沪深300ETF）；`match_conditions[0]` = 该 ETF 跟踪指数的命中记录。

### 例 3：searchIndex + has_stock，列出同时包含两只股票的指数（AND）

```json
{
  "keyword": {
    "mode": "advanced",
    "conditions": [
      { "type": "has_stock", "stock_code": "000001.SZ" },
      { "type": "has_stock", "stock_code": "600519.SH" }
    ]
  }
}
```

期望：同时包含 000001.SZ 和 600519.SH 的指数；`match_conditions` 是 2 元素数组，第 0 项是 000001.SZ 的命中、第 1 项是 600519.SH 的命中。

### 例 4：单 condition `|` 拆分超限（默认 3）

```json
{
  "query": {
    "mode": "advanced",
    "asset_type": "stock",
    "conditions": [
      {
        "type": "index_component",
        "index_code": "000001.SZ|000002.SZ|000003.SZ|000004.SZ"
      }
    ]
  }
}
```

期望：抛 `BATCH_LIMIT_EXCEEDED`（4 > 3）。要么减少 `|` 数量，要么先调 `checkParallelLimits` 看 `advanced.maxItems` 是不是被运维调到 ≥ 4。

### 例 5：start_date / end_date 都为 NONE，取最新快照

```json
{
  "query": {
    "mode": "advanced",
    "asset_type": "stock",
    "conditions": [
      { "type": "index_component", "index_code": "000300.SH" }
    ]
  }
}
```

期望：工具内部取 `getMaxTradeDateByTsCode("000300.SH")` 拿到最新公告日，单日查询；返回的 `match_conditions[0]` 通常只有 1 个 `[trade_date, weight]` 元素。
