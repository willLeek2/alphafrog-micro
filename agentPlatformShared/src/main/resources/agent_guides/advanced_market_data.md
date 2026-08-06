# Advanced 市场数据查询指南

## 适用工具与触发方式

advanced 模式由以下工具承担，统一通过 **顶层 `mode=advanced` + `advancedQuery`** 触发：

- `searchAssetInfo(query=..., assetTypes=..., marketScope=..., mode=advanced, advancedQuery=...)` — `advancedQuery` 是 JSON 对象/字符串，内部解析为 `AdvancedSearchRequest`。
- `searchIndex(keyword=..., mode=advanced, advancedQuery=...)` — `advancedQuery` 仅支持 `has_stock` 条件。
- `getExchangeAssetDaily(tsCode=..., assetType=..., startDate=..., endDate=..., priceMode=..., mode=advanced, advancedQuery=...)` — `advancedQuery` 仅支持 `asset_type=stock`，通过 `conditions` 批量拉取成分股日线。

工具签名（agent 看到的 @Tool 描述）：

| 工具 | simple 必填 | advanced 必填 | 备注 |
|---|---|---|---|
| `searchAssetInfo` | `query` | `mode=advanced` + `advancedQuery` | `query` 用 `\|` 分隔或 JSON 数组表示多个；advanced 时 `query`/`assetTypes` 忽略 |
| `searchIndex` | `keyword` | `mode=advanced` + `advancedQuery` | `keyword` 同 `\|` 批量；advanced 时 `keyword` 忽略 |
| `getExchangeAssetDaily` | `tsCode` / `assetType` / `startDate` / `endDate` | `mode=advanced` + `advancedQuery` + `startDate` + `endDate` | advanced 时 `tsCode`/`assetType` 忽略；`priceMode` 目前仅 `raw_ohlc` |

`advancedQuery` 可以传 JSON 对象，也可以传 JSON 字符串。

## advancedQuery 结构（AdvancedSearchRequest）

`advancedQuery` 顶层字段：

| 字段 | 必填 | 用途 |
|---|---|---|
| `asset_type` | 视工具而定 | `stock` / `etf` / `index` / `off_exchange_fund`；别名 `assetType` / `assetTypes` / `asset_types` 都接受 |
| `name` | 否 | 资产名称搜索词，与 `has_stock` / `index_component` 等一起用 |
| `conditions` | 视工具而定 | `searchAssetInfo` / `searchIndex`：与 `name` 至少其一；`getExchangeAssetDaily` advanced：必填，至少 1 条；多条时 AND 关系 |

`asset_type` 与 `condition.type` 必须匹配，否则抛 `INVALID_ARGUMENT`：

| 工具 | asset_type | 支持的 condition.type |
|---|---|---|
| `searchAssetInfo` | `stock` | `index_component` / `sw_industry_l2_component` / `sw_industry_l3_component` |
| `searchAssetInfo` | `etf` | `has_stock` |
| `searchIndex` | — | `has_stock` |
| `getExchangeAssetDaily` | `stock`（advanced 唯一支持） | `index_component` / `sw_industry_l2_component` / `sw_industry_l3_component` |

## Condition 字段（AdvancedSearchCondition）

| 字段 | 必填 | 适用 type | 用途 |
|---|---|---|---|
| `type` | 是 | — | `index_component` / `has_stock` / `sw_industry_l2_component` / `sw_industry_l3_component` |
| `index_code` | `index_component` 必填 | `index_component` | 指数代码（TuShare 格式 `000300.SH`）；支持 `\|` 分隔多值 |
| `stock_code` | `has_stock` 必填 | `has_stock` | 股票代码（`000001.SZ`）；支持 `\|` 分隔多值 |
| `industry_code` | `sw_industry_l2_component` / `sw_industry_l3_component` 必填 | `sw_industry_l2_component` / `sw_industry_l3_component` | 申万行业代码；支持 `\|` 分隔多值 |
| `start_date` | 否 | `index_component` / `has_stock` | `YYYYMMDD` 或字面量 `"NONE"` |
| `end_date` | 否 | `index_component` / `has_stock` | `YYYYMMDD` 或字面量 `"NONE"` |
| `min_weight` | 否 | `index_component` | 浮点下限；不传表示不设下限；当前实现不限制负值 |
| `max_weight` | 否 | `index_component` | 浮点上限；不传表示不设上限；当前实现不限制负值 |

**日期语义与校验说明**：

- `index_component` / `has_stock`：`start_date` / `end_date` 控制指数成分权重快照的日期区间。
  - 两者都为 `NONE` 时，工具取该指数最新公告期单日快照（`getMaxTradeDateByTsCode` + `getIndexWeightsByTsCodeAndTradeDate`），不是全历史扫描。
  - 单边 `NONE` 表示不限制该侧边界。
  - `start_date > end_date` 抛 `INVALID_ARGUMENT`。
- `sw_industry_l2_component` / `sw_industry_l3_component`：代码当前仅取 `is_new='Y'` 的最新行业成员；`start_date` / `end_date` 会被解析验证但不参与过滤（保留字段，后续可能扩展）。
- `min_weight > max_weight` 抛 `INVALID_ARGUMENT`（两者都传时）。

## 组合语义

- 同一 condition 内多值用 `|` 分隔代码，工具按 OR 拆开逐个查询再合并（去重保序）。
- 多条 conditions 之间是 AND：每条求结果后做集合交，只保留在每条结果中同时出现的 `ts_code`。
- `|` 数量限制：单 condition 内 `|` 拆分出的代码数不得超过 `checkParallelLimits().advanced.maxItems`（默认 3，clamp [1,20]）。超出抛 `BATCH_LIMIT_EXCEEDED`。

## 返回结构

### `searchAssetInfo` / `searchIndex` advanced

工具返回 JSON，`data` 字段：

| 字段 | 含义 |
|---|---|
| `mode` | 固定 `"advanced"` |
| `asset_type` | 与请求一致 |
| `row_count` | 结果行数 |
| `dataset_id` | 搜索类：`adv-{12位 SHA-256}`；`getExchangeAssetDaily` advanced：由 `DatasetWriter` 生成的 ID（格式 `<runId>-advanced-<conditionSummary>-<group-digest>-<start>-<end>-<8位uuid>`），其中 `<group-digest>` 是内部稳定的 storage/registry key；writer 关闭时为 `""` |
| `dataset_status` | `created` / `reused` / `inline` |
| `reused` | 是否命中既有 dataset（bool） |
| `preview_rows` | 前 N 条预览（默认 10，可在 Nacos 改） |
| `preview_limit` | 预览条数上限，与 `preview_rows.length` 取小 |
| `conditions_meta` | 每条 condition 的执行统计 |
| `empty_reason` | 仅 0 行时存在；例如 `no_matching_index_weights` |
| `upstream_error` | 上游 IDL 失败时存在 |

`dataset_id` 为空时，`data.dataset` 内联完整 dataset map（含 `schema_version=1`、`tool`、`asset_type`、`query`（canonicalQuery，只保留 `name` 与 `conditions` 两层键，condition 行内字段固定为 `type/index_code/stock_code/industry_code/start_date/end_date/min_weight/max_weight` 七项，snake_case）、`conditions_meta`、`row_count`、`results[]`）。dataset 落盘路径由 `DatabaseFetchedPathStrategy` 决定，搜索类走 `market_data_advanced_search` topic，`getExchangeAssetDaily` advanced 走 `stock_daily_advanced` topic。

`conditions_meta[]` 每条 condition 对应一项，字段：`condition_index`、`type`、`slot_type`（含 `date_match_reason: "long"` 与 `weight_match_reason: "float|null"`）、`start_date`、`end_date`、`min_weight`、`max_weight`。

行结构：

| 字段 | 含义 |
|---|---|
| `ts_code` | 命中的资产代码 |
| `name` | 资产名称 |
| `asset_type` | 资产类型 |
| `index_code` / `index_name` | ETF 两跳场景下回填（`searchAssetInfo(etf, has_stock)` 用）；对 `sw_industry_*_component` 回填为行业 code/name |
| `match_conditions` | per-condition 命中数组；第 i 个元素 = 第 i 条 condition 的命中记录 |

`match_conditions` 中每条记录的结构因 `type` 而异：

- `index_component` / `has_stock`：`[trade_date, weight]`
- `sw_industry_l2_component` / `sw_industry_l3_component`：`[industry_code, industry_name, "l1_code|l1_name"]`

### `getExchangeAssetDaily` advanced

返回的是**成分股日线数据集**，结构与搜索类不同：

| 字段 | 含义 |
|---|---|
| `mode` | 固定 `"advanced"` |
| `asset_type` | 固定 `"stock"` |
| `row_count` | 实际拉到的日线行数（不是股票只数） |
| `dataset_id` | 日线 dataset 的 ID（格式见上）；同时可通过 `dataset_ids` 数组获取 |
| `dataset_ids` | 含 `dataset_id` 的单元素数组，便于 executePython 等下游统一消费 |
| `matched_stocks` | 命中条件的成分股代码列表 |
| `matched_stock_count` | 命中条件的成分股只数 |
| `start_date` / `end_date` | 日线日期范围 |
| `conditions_meta` | 请求的 conditions 原样回显 |

无 `preview_rows`；结果通过 `dataset_id` 落盘供后续 `executePython` 等 todo 消费。

## ETF 两跳链

ETF 不属于指数成分股表。查“跟踪某指数的 ETF”时：

1. 先用 `searchAssetInfo` / `searchIndex` 找到目标指数代码。
2. 再用 `has_stock` 或资产搜索查跟踪关系。

`searchAssetInfo(asset_type=etf, has_stock)` 的具体实现：

1. 第一跳：`has_stock` 反向查包含某 stock 的指数（`indexWeightDao.getLatestIndexWeightsByConCodeAndDateRange`）。
2. 第二跳：把查到的指数代码丢给 `domesticListedAssetService.searchListedAssets(query=indexCode, assetTypes=[etf])`，命中 `ListedAssetInfoItem.indexCode` / `indexName` 字段的 ETF。

结果行的 `index_code` / `index_name` 来自第二跳。

## 相关工具：getStockSwIndustryInfo

`getStockSwIndustryInfo(tsCode)` 用于查询单只股票的申万一/二/三级行业信息，支持 `\|` 或 JSON 数组批量。返回：

| 字段 | 含义 |
|---|---|
| `ts_code` | 股票代码 |
| `count` | 该股票的申万行业记录数 |
| `items[]` | 每条含 `l1_code/l1_name`、`l2_code/l2_name`、`l3_code/l3_name`、`in_date`、`out_date`、`is_new` |

该工具与 advanced 的 `sw_industry_l2_component` / `sw_industry_l3_component` 配合使用：先用 `getStockSwIndustryInfo` 确认某股票的二级/三级行业代码，再用 advanced 条件批量拉取同行业成分股日线。

## 并行与配置限制

调 `checkParallelLimits` 取 `advanced` 组：

| 子字段 | 含义 | 默认 / clamp |
|---|---|---|
| `maxItems` | 单 condition `\|` 拆分上限 | 默认 3，clamp [1,20] |
| `previewRows` | 预览条数 | 默认 10，clamp [0,100] |
| `tools` | 适用工具列表 | `["searchIndex(mode=advanced)", "searchAssetInfo(mode=advanced)", "getExchangeAssetDaily(mode=advanced)"]` |
| `argumentFormat` | 提示 | `advancedQuery is a JSON object with asset_type, name?, conditions. conditions use | separated index_code/stock_code/industry_code values. Dates must be YYYYMMDD or NONE. getExchangeAssetDaily advanced only supports stock asset_type.` |

Nacos 热加载配置（`agent-llm.local.json`，由 `agent.llm.config-file` 指定文件，10s 轮询）：

- `tools.market_data.advanced.previewRows` → 预览条数
- `runtime.parallel.maxParallelQueriesInAdvancedMode` → 单 condition `\|` 上限
- `runtime.parallel.maxAdvancedDailyConstituentStocks` → `getExchangeAssetDaily` advanced 单次允许匹配的最大成分股只数（默认 500）

**热加载失败行为**：若某次轮询 Nacos 返回异常或 JSON 解析失败，保留上一轮成功加载的配置，不抛错中断运行。

## 错误码

| 码 | 触发 |
|---|---|
| `INVALID_ARGUMENT` | 日期格式错 / asset_type 与 condition.type 不匹配 / 缺 `mode` / `advancedQuery` 不是合法 JSON / `getExchangeAssetDaily` 传了非 `stock` 的 asset_type |
| `BATCH_LIMIT_EXCEEDED` | 单 condition `\|` 拆分 > `maxItems`；或 `getExchangeAssetDaily` advanced 匹配到的成分股只数超过 `maxAdvancedDailyConstituentStocks`（默认 500，可配）。**建议**：调用方应先 `checkParallelLimits` 查询当前限制，再按需拆批；当前 `details` 为空，错误消息含超限数量和限制值。 |
| `UPSTREAM_ERROR` | 上游 IDL 调用失败（`data` 上同时含 `upstream_error` / `matched_stocks` 等上下文） |
| `NO_DATA` | 没找到任何结果 |
| `TOOL_ERROR` | writer / dao 内部异常 |

## 示例

### 例 1：searchAssetInfo(stock) + index_component，按成分股权重筛 000300.SH

```json
{
  "mode": "advanced",
  "advancedQuery": {
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
  "mode": "advanced",
  "advancedQuery": {
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
  "mode": "advanced",
  "advancedQuery": {
    "conditions": [
      { "type": "has_stock", "stock_code": "000001.SZ" },
      { "type": "has_stock", "stock_code": "600519.SH" }
    ]
  }
}
```

期望：同时包含 000001.SZ 和 600519.SH 的指数；`match_conditions` 是 2 元素数组，第 0 项是 000001.SZ 的命中、第 1 项是 600519.SH 的命中。

### 例 4：searchAssetInfo(stock) + sw_industry_l3_component

```json
{
  "mode": "advanced",
  "advancedQuery": {
    "asset_type": "stock",
    "conditions": [
      { "type": "sw_industry_l3_component", "industry_code": "430101" }
    ]
  }
}
```

期望：返回申万三级行业代码 `430101`（国有大型银行）下的最新成分股；`match_conditions[0]` = `["430101", "国有大型银行", "430000|金融"]`。

### 例 5：getExchangeAssetDaily advanced，拉取沪深300成分股 2024年1月日线

```json
{
  "mode": "advanced",
  "advancedQuery": {
    "asset_type": "stock",
    "conditions": [
      {
        "type": "index_component",
        "index_code": "000300.SH",
        "start_date": "20240101",
        "end_date": "20241231"
      }
    ]
  },
  "startDate": "20240101",
  "endDate": "20240131",
  "priceMode": "raw_ohlc"
}
```

期望：先解析 advanced 条件得到 000300.SH 在 2024 年的成分股，再批量拉取这些股票 2024-01-01 至 2024-01-31 的 raw_ohlc 日线；返回 `mode=advanced`、`matched_stocks=[...]`、`dataset_id=...`。

### 例 6：单 condition `|` 拆分超限（默认 3）

```json
{
  "mode": "advanced",
  "advancedQuery": {
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

### 例 7：index_component 的 start_date / end_date 都为 NONE，取最新快照

```json
{
  "mode": "advanced",
  "advancedQuery": {
    "asset_type": "stock",
    "conditions": [
      { "type": "index_component", "index_code": "000300.SH" }
    ]
  }
}
```

期望：工具内部取 `getMaxTradeDateByTsCode("000300.SH")` 拿到最新公告日，单日查询；返回的 `match_conditions[0]` 通常只有 1 个 `[trade_date, weight]` 元素。
