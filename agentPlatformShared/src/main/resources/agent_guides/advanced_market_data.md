# Advanced 市场数据查询指南

## 适用工具与触发方式

advanced 模式由两个工具承载，触发条件是入口 JSON 顶层含 `mode: "advanced"`（顶层或嵌套在 `query` 内都可）。

- `searchAssetInfo(query=..., assetTypes=..., marketScope=...)`：当 `query` 是以 `{` 开头的 JSON 字符串且含 `mode: "advanced"`，工具内部解析为 `AdvancedSearchRequest`，再走 `AdvancedSearchEngine`。
- `searchIndex(keyword=...)`：当 `keyword` 是以 `{` 开头的 JSON 字符串且含 `mode: "advanced"`，进入 advanced 分支；该分支下 `searchIndex` 仅支持 `has_stock` 这一种 condition（`index_component` 会抛 `INVALID_ARGUMENT`）。

JSON 解析失败时（如 `{not valid json`）按普通 keyword 走非 advanced 分支；只有 `parseAdvancedStringPayload` 解析成功且 `isAdvancedMap` 返回 true 才进入 advanced 分支。

| 工具 | 必填参数 | 可选参数 | 备注 |
|---|---|---|---|
| `searchAssetInfo` | `query` (JSON 字符串) | `assetTypes`（默认 `stock,etf,index,off_exchange_fund`）/ `marketScope`（仅 `domestic`） | 非 advanced 分支时 `query` 用 `\|` 分隔或 JSON 数组 |
| `searchIndex` | `keyword` (JSON 字符串) | — | 非 advanced 分支时 `keyword` 用 `\|` 分隔 |

## 入口 JSON 结构（AdvancedSearchRequest）

`AdvancedSearchRequest.from(toolName, params, objectMapper)` 工厂方法从 params 里读取以下字段：

| 字段 | 必填 | 用途 / 取值 |
|---|---|---|
| `mode` | 是 | 必须字面量 `"advanced"`；位于 params 顶层或 `query` 内部均可；缺省抛 `INVALID_ARGUMENT` |
| `asset_type` | 是（searchAssetInfo）/ 否（searchIndex） | 标准值 `stock` / `etf` / `index` / `off_exchange_fund`；接受别名 `assetType` / `assetTypes` / `asset_types`；逗号分隔时只取第一段；统一小写化 |
| `name` | 否 | 资产名称搜索词；`searchIndex` 走 `domesticIndexService.searchDomesticIndex`，`searchAssetInfo` 走 `domesticListedAssetService.searchListedAssets` |
| `conditions` | 是（与 name 至少有其一） | 数组；多条时 AND 关系；非数组抛 `INVALID_ARGUMENT` |

`asset_type` 与 `condition.type` 必须匹配，工厂层不强制，由 `AdvancedSearchEngine` 在执行时校验：

- `searchAssetInfo(asset_type=stock)` 配 `index_component`（合法）
- `searchAssetInfo(asset_type=etf)` 配 `has_stock`（合法）
- `searchAssetInfo(asset_type=stock)` 配 `has_stock` 抛 `INVALID_ARGUMENT`
- `searchAssetInfo(asset_type=fund)` 抛 `INVALID_ARGUMENT`
- `searchIndex` 配 `index_component` 抛 `INVALID_ARGUMENT`

`canonicalQuery`（dataset 内嵌的规范化结构）只保留 `name` 与 `conditions` 两层键，condition 行内字段固定为 `type / index_code / stock_code / start_date / end_date / min_weight / max_weight` 七项，字段名一律 snake_case。

## Condition 字段（AdvancedSearchCondition）

| 字段 | 必填 | 适用 type | 用途 |
|---|---|---|---|
| `type` | 是 | — | 字面量 `index_component` 或 `has_stock`，工厂层统一小写 |
| `index_code` | `index_component` 必填 | `index_component` | 指数代码（TuShare 格式如 `000300.SH`）；支持 `\|` 分隔多值（OR 合并） |
| `stock_code` | `has_stock` 必填 | `has_stock` | 股票代码（如 `000001.SZ`）；支持 `\|` 分隔多值（OR 合并） |
| `start_date` | 否 | 全部 | `YYYYMMDD` 字面量或字符串 `"NONE"`；非 YYYYMMDD 抛 `INVALID_ARGUMENT` |
| `end_date` | 否 | 全部 | `YYYYMMDD` 字面量或字符串 `"NONE"`；非 YYYYMMDD 抛 `INVALID_ARGUMENT` |
| `min_weight` | 否 | `index_component` | 浮点下限；weight < min_weight 被过滤掉 |
| `max_weight` | 否 | `index_component` | 浮点上限；weight > max_weight 被过滤掉 |

字段别名：每个 snake_case 字段都同时接受 camelCase（`indexCode` / `stockCode` / `startDate` / `endDate` / `minWeight` / `maxWeight`）。

日期校验：

- `start_date > end_date` 抛 `INVALID_ARGUMENT`。
- `min_weight > max_weight` 抛 `INVALID_ARGUMENT`。
- `start_date="NONE"` 表示未指定，工具层解析为 `MIN_DATE = 19000101`；`end_date="NONE"` 解析为 `MAX_DATE = 20991231`。
- 两个日期都为 `NONE` 时，工具调用 `indexWeightDao.getMaxTradeDateByTsCode` 取出最新公告期 trade_date，再调 `getIndexWeightsByTsCodeAndTradeDate` 取该日单日完整快照（不是全历史扫描）。

## 组合语义

- **同一 condition 内**：`index_code` 或 `stock_code` 用 `\|` 分隔的多代码按 OR 拆开逐个查询，结果用 LinkedHashSet 去重保序合并。
- **多条 conditions 之间**：每条 condition 单独求结果，工具对所有结果做集合交（`intersect`），只在每个结果集合中同时出现的 `ts_code` 才会保留；交集为空时返回 0 行并附带 `empty_reason: "no_matching_index_weights"`。
- **`\|` 数量限制**：单 condition 内 `\|` 拆分出的代码数不得超过 `checkParallelLimits().advanced.maxItems`（默认 3，clamp [1,20]）。超出抛 `BATCH_LIMIT_EXCEEDED`，错误消息形如 `stock_code count exceeds maxParallelQueriesInAdvancedMode`。
- **同 tsCode 多 tradeDate**：同一指数下同一股票出现多个 trade_date 时，只保留 `trade_date` 最大的那条；其 `[trade_date, weight]` 写入 `match_conditions` 对应下标。

## 返回结构

工具返回的 JSON 整体结构 `{ok: true, tool, data, error: null}`；`data` 字段如下：

| 字段 | 含义 |
|---|---|
| `mode` | 固定 `"advanced"` |
| `asset_type` | 与请求一致（`searchAssetInfo` 时存在；`searchIndex` 时为 `"index"`） |
| `row_count` | 结果行数（去重后） |
| `dataset_id` | 形如 `adv-{12位 SHA-256}`；writer 关闭时为 `""` |
| `dataset_status` | `created` / `reused` / `inline` 三选一 |
| `reused` | 是否命中既有 dataset（bool） |
| `preview_rows` | 前 N 条预览（默认 10，可在 Nacos 改 `tools.marketData.advanced.previewRows`） |
| `preview_limit` | 预览条数上限，与 `preview_rows.length` 取小 |
| `conditions_meta` | 每条 condition 的执行快照 |
| `empty_reason` | 仅 0 行且无上游错误时存在，例如 `no_matching_index_weights` |

`dataset_id` 为空字符串或写入失败时，`data.dataset` 内联完整 dataset map（含 `schema_version=1`、`tool`、`asset_type`、`query`（canonicalQuery）、`conditions_meta`、`row_count`、`results[]`）。dataset 落盘到 `DatabaseFetchedPathStrategy.resolveDataPath(... "market_data_advanced_search")` 下，文件格式 `data.json`。

行（`results[]` 元素）字段：

| 字段 | 含义 |
|---|---|
| `ts_code` | 命中的资产代码 |
| `name` | 资产名称 |
| `asset_type` | 该行资产类型 |
| `index_code` | 仅 ETF 两跳场景下回填（`searchAssetInfo(asset_type=etf, has_stock)`） |
| `index_name` | 仅 ETF 两跳场景下回填 |
| `match_conditions` | per-condition 命中数组；第 i 个元素 = 第 i 条 condition 的 `[trade_date, weight]`；`null` 表示该 condition 未命中 |

`conditions_meta[]` 每条 condition 对应一项，字段：`condition_index`、`type`、`slot_type`（含 `date_match_reason: "long"` 与 `weight_match_reason: "float|null"`）、`start_date`、`end_date`、`min_weight`、`max_weight`。

## ETF 两跳链

ETF 不在 `IndexWeightDao` 索引表里。`searchAssetInfo(asset_type=etf, has_stock)` 走两跳：

1. 第一跳：`has_stock` 反向查包含该 stock 的指数，调用 `indexWeightDao.getLatestIndexWeightsByConCodeAndDateRange(stockCode, startMs, endMs)`，按 `trade_date` 取每条指数的最新快照。
2. 第二跳：对第一跳返回的每个 index_code，调 `domesticListedAssetService.searchListedAssets(query=indexCode, assetTypes=[etf], marketScope="domestic", limit=200)`，命中 `ListedAssetInfoItem.indexCode` / `indexName` 字段的 ETF，结果行回填 `index_code` / `index_name` 与对应 `match_conditions` 槽位。

注意：第二跳对每个 index_code 是独立的 listedAsset 调用，但数量不计入 `maxParallelQueriesInAdvancedMode` 上限（上限只约束 `\|` 拆分出的代码数）。

## 并行与配置限制

调 `checkParallelLimits` 取 `advanced` 组，再做后续判断：

| 子字段 | 含义 | 默认 / clamp |
|---|---|---|
| `advanced.maxItems` | 单 condition `\|` 拆分出的代码数上限 | 默认 3，clamp [1,20] |
| `advanced.previewRows` | `data.preview_rows` 与 `data.preview_limit` 的来源 | 默认 10，clamp [0,100] |
| `advanced.tools` | advanced 模式覆盖的工具列表 | `["searchIndex(mode=advanced)", "searchAssetInfo(mode=advanced)"]` |
| `advanced.argumentFormat` | 提示语 | "conditions use \| separated index_code/stock_code values. Dates must be YYYYMMDD or NONE." |

`maxItems` 与 `previewRows` 的取值顺序：

1. Nacos 热加载 `agent-llm.local.json` → `runtime.parallel.maxParallelQueriesInAdvancedMode` / `tools.marketData.advanced.previewRows`。
2. Spring 静态配置 `application.yml` 同名字段。
3. 硬编码默认值（3 / 10）。

热加载机制由 `AgentLlmLocalConfigLoader` 提供：

- 启动时通过 `@PostConstruct load()` 首次加载配置文件。
- `@Scheduled(fixedDelayString = "${agent.llm.config-refresh-interval-ms:10000}")` 间隔 10s 轮询文件 `lastModified`，有变化则重新解析 JSON → 原子替换 `localConfig` 引用，调用方拿到的是替换后的新对象。
- Nacos 推送 → 写文件 → 轮询 → 解析 → 原子替换，全程不需要重启服务。
- 热加载失败时 `log.error` 但保留上一次成功加载的配置，正在运行的 agent 不受影响。

`fallbackRule`：`checkParallelLimits` 不可用时退化为单条查询（一次一个 condition，不批量）。

## 错误码

`AdvancedSearchEngine` 与 `MarketDataTools` 抛出或回填的错误码：

| 错误码 | 触发条件 | 典型场景 |
|---|---|---|
| `INVALID_ARGUMENT` | 缺 `mode=advanced`、name 与 conditions 都为空、conditions 非数组、字段别名 / 日期格式错误、`min_weight > max_weight`、`start_date > end_date`、`asset_type` 与 `condition.type` 不匹配、`searchIndex` 收到非 `has_stock` | 用户传入错误参数 |
| `BATCH_LIMIT_EXCEEDED` | 单 condition `\|` 拆分出的代码数超过 `advanced.maxItems` | 用户传入过多批量代码 |
| `UPSTREAM_ERROR` | 至少一个 Dubbo / DAO 调用抛异常（非全部失败时聚合到 `data.upstream_error`） | 某只股票 / 某只指数反查失败 |
| `NO_DATA` | `row_count = 0` 且无上游错误，附带 `empty_reason: "no_matching_index_weights"` | 时间区间内无成分股记录 |
| `TOOL_ERROR` | 写 dataset 文件失败、签名构造失败、`objectMapper.readValue` 失败 | 落盘 / 序列化异常 |

错误响应统一结构 `{ok: false, tool, data: {}, error: {code, message, details}}`；`BATCH_LIMIT_EXCEEDED` 当前在 advanced 模式下 `details` 为空对象 `{}`（`splitCodes` 仅抛 `(code, message)`，`MarketDataTools` catch 后 `Map.of()` 透传），不包含 `actual_items` / `max_items` / `requested_values` 等结构化字段；如需这些结构化信息，应先调 `checkParallelLimits` 拆批。

## 示例

下列 5 个示例覆盖最常见查询模式。`query` / `keyword` 字段值需以 `{` 开头，按 JSON 字符串传入。

### 示例 1：股票反查指数（searchAssetInfo(stock) + index_component）

查「沪深300 中权重 ≥ 2% 的成分股」。`asset_type=stock`、`type=index_component`、`min_weight=2.0`：

```json
{
  "tool": "searchAssetInfo",
  "params": {
    "query": "{\"mode\":\"advanced\",\"asset_type\":\"stock\",\"conditions\":[{\"type\":\"index_component\",\"index_code\":\"000300.SH\",\"start_date\":\"20240101\",\"end_date\":\"20241231\",\"min_weight\":2.0}]}"
  }
}
```

`data.row_count` 是沪深300中权重 ≥ 2% 的成分股个数；`data.dataset_id` 可复用，重复查询 `dataset_status=reused`。

### 示例 2：股票反查 ETF（searchAssetInfo(etf) + has_stock）

查「包含 000001.SZ 的 ETF」（两跳链：先反查指数，再查跟踪 ETF）。`asset_type=etf`、`type=has_stock`：

```json
{
  "tool": "searchAssetInfo",
  "params": {
    "query": "{\"mode\":\"advanced\",\"asset_type\":\"etf\",\"conditions\":[{\"type\":\"has_stock\",\"stock_code\":\"000001.SZ\",\"start_date\":\"20240101\",\"end_date\":\"20241231\"}]}"
  }
}
```

每行 `ts_code` 是 ETF 代码，`index_code` / `index_name` 是该 ETF 跟踪的指数，`match_conditions[0]` 是 `[trade_date, weight]`。

### 示例 3：searchIndex 多 condition AND

查「同时包含 000001.SZ 和 600519.SH 的指数」。多条 condition 取交集：

```json
{
  "tool": "searchIndex",
  "params": {
    "keyword": "{\"mode\":\"advanced\",\"conditions\":[{\"type\":\"has_stock\",\"stock_code\":\"000001.SZ\",\"start_date\":\"20240101\",\"end_date\":\"20241231\"},{\"type\":\"has_stock\",\"stock_code\":\"600519.SH\",\"start_date\":\"20240101\",\"end_date\":\"20241231\"}]}"
  }
}
```

`data.row_count` 通常是 1（沪深300 是常见交集）；结果行 `match_conditions` 是二维数组，对应两条 condition 的 `[trade_date, weight]`。

### 示例 4：BATCH_LIMIT_EXCEEDED

`stock_code` 一次传 4 个，超过 `advanced.maxItems=3`：

```json
{
  "tool": "searchIndex",
  "params": {
    "keyword": "{\"mode\":\"advanced\",\"conditions\":[{\"type\":\"has_stock\",\"stock_code\":\"000001.SZ|000002.SZ|000003.SZ|000004.SZ\",\"start_date\":\"20240101\",\"end_date\":\"20241231\"}]}"
  }
}
```

响应：`ok=false`、`error.code="BATCH_LIMIT_EXCEEDED"`、`error.message="stock_code count exceeds maxParallelQueriesInAdvancedMode"`、`error.details={}`（当前 advanced 模式下不填 details）。修法是先调 `checkParallelLimits`，按 `advanced.maxItems` 拆批（每批 ≤3 个），多批结果在 LLM 侧合并。

### 示例 5：NONE/NONE 最新公告期快照

查沪深300最新一期完整成分股（不指定起止日期）。`start_date="NONE"`、`end_date="NONE"`：

```json
{
  "tool": "searchAssetInfo",
  "params": {
    "query": "{\"mode\":\"advanced\",\"asset_type\":\"stock\",\"conditions\":[{\"type\":\"index_component\",\"index_code\":\"000300.SH\",\"start_date\":\"NONE\",\"end_date\":\"NONE\"}]}"
  }
}
```

工具内部调 `indexWeightDao.getMaxTradeDateByTsCode("000300.SH", MIN_DATE_MS, MAX_DATE_MS)` 拿到最新 trade_date，再调 `getIndexWeightsByTsCodeAndTradeDate` 取该日完整快照；`data.row_count` 即为该期成分股总数。
