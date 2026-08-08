# 金融 MethodSpec V5 共同协议

状态：`DRAFT_FOR_CROSS_REVIEW`

适用父任务：`#af-v1p1 task #96`

适用子任务：

- `task #97`：工作包 A—D；
- `task #98`：任务 0、工作包 E—H 与最终本地集成。

本文件冻结跨工作包共同字段、身份、顺序、失败语义和公开投影。具体类名、包内私有 DTO 和数据库索引可以在不改变本协议的前提下调整。改变本文件中的公共字段或顺序时，必须由两个子任务 owner 和受影响工作包负责人共同确认。

## 1. 产品边界

1. 用户输入保持自然语言，不要求先填写方法名、年份、期间数、数据频率或参数表。
2. `resolveFinanceMethods` 只建议候选方法，不计算数值、不授予权限、不阻止自定义计算。
3. 当前小型 MethodSpec 目录完整进入专用轻量模型的 system prompt；目录超出提示词预算后，才增加候选召回层，再由轻量模型复核。
4. `parameters` 描述方法真正执行时所需输入，不定义用户提问格式。CAGR 的 `periods` 只属于 CAGR。
5. `report()` 和 `report_custom()` 使用同一条结构化结果通道。
6. HTTP、proto、数据库、事件和 anchor 中的环境、摘要、身份、证据与限制字段只供后台使用。
7. 模型只看最小工具投影；用户只看“方法、结果、如何计算”。

## 2. 名词和两个工具调用身份

- `resolverToolCallId`：一次 `resolveFinanceMethods` 普通工具调用的框架身份，由 Java 从 `AgentContext.getToolCallId()` 取得。
- `sourceResolverToolCallId`：金融计算结果记录对来源 resolver 调用的显式引用。
- `executePythonToolCallId`：产生 stdout 的 `executePython` 调用身份。数据库批次和记录幂等使用这个身份。
- 方法三元组：`methodId + methodVersion + specDigest`。
- 解析快照身份：`runId + resolverToolCallId + methodId + methodVersion + specDigest`。
- 执行记录身份：`runId + todoId + executePythonToolCallId + recordIndex + rawDigest`。

两个 toolCallId 不能互换。一次 resolver 调用可以返回多个方法；它们共享一个 `resolverToolCallId`，由方法三元组区分。一次或多次 executePython 调用可以显式引用同一 resolver 调用，但每个执行批次仍使用自己的 toolCallId。

## 3. resolver 公共协议

### 3.1 工具输入

```json
{
  "query": "帮我看看这几年这只股票涨得怎么样，最好给个能比较的增长速度",
  "context": "已经取得一组交易日收盘价，但当前消息没有明确起止日"
}
```

约束：

- `query` 是必填、非空的自然语言字符串；
- `context` 是可选自然语言字符串；
- 字节上限只用于防止滥用和保护模型上下文，不定义金融领域结构；
- 工具不得要求调用方先抽取 `years`、`periods`、`frequency`、`startDate` 或其他固定领域字段；
- “这几年”“最近一段”“到现在”等词允许原样进入 resolver。

### 3.2 轻量模型输入

system prompt 由两部分组成：

1. 稳定规则：只选候选、解释理由和指出待澄清项；不得计算数值、补造日期、改写 canonical 定义或创造目录外方法；
2. 运行时紧凑目录：当前所有 `methodId/version/specDigest/displayName/aliases/commonPhrases/clarificationDimensions`。

user message 只包含 `query` 和可选 `context`。长篇知识正文、实际容器环境和用户未提供的推断事实不进入该模型。

专用模型路由规则：

- `stage=finance_method_resolver`；
- 温度为 0；
- 优先使用 run 的 `finance_method_resolver` 阶段配置，其次使用服务端专用轻量默认路由；
- 不得静默继承 planning、execution 或 final-answer 大模型；
- 调用前后恢复 `AgentContext` 的 phase、stage、StructuredOutputSpec 和 reasoning effort；
- provider 的结构化输出能力不能替代服务端 JSON、字段和目录身份校验。

### 3.3 轻量模型结构化输出

```json
{
  "status": "NEEDS_CLARIFICATION",
  "candidates": [
    {
      "methodId": "finance.growth.cagr",
      "version": "1.0.0",
      "specDigest": "sha256:spec-example",
      "matchReason": "用户希望比较一段区间的复合增长速度",
      "unresolvedTerms": ["这几年"],
      "clarificationQuestions": ["希望从哪个交易日算到哪个交易日？"]
    }
  ]
}
```

允许的语义状态：

- `MATCHED`；
- `AMBIGUOUS`；
- `NEEDS_CLARIFICATION`；
- `NO_ADVICE`。

Java 必须验证：

- 候选数量有界且没有重复方法三元组；
- 每个方法三元组在本次 catalog 中精确存在；
- 状态和候选数量相容；
- 理由、未解决表达和问题长度有界；
- 模型不能提供或覆盖 definition、parameters、sources、library、sample 或环境事实。

技术失败不能伪装成 `NO_ADVICE`。无专用路由、超时、坏 JSON、目录超预算或候选校验失败时，先允许精确别名兜底；仍无法确定时，公共工具返回 `ok=false`，错误码使用：

- `RESOLVER_UNAVAILABLE`；
- `RESOLVER_BAD_MODEL_OUTPUT`；
- `RESOLVER_CATALOG_BUDGET_EXCEEDED`。

这些失败不阻止主 Agent 随后自由调用 `executePython`。

### 3.4 resolver 公共工具输出

```json
{
  "ok": true,
  "tool": "resolveFinanceMethods",
  "data": {
    "resolverToolCallId": "tool-call-resolver-1",
    "status": "NEEDS_CLARIFICATION",
    "suggestions": [
      {
        "methodId": "finance.growth.cagr",
        "version": "1.0.0",
        "specDigest": "sha256:spec-example",
        "displayName": "复合增长率",
        "matchReason": "用户希望比较一段区间的复合增长速度",
        "definition": "根据起始值、结束值和实际区间长度计算复合增长速度",
        "requiredExecutionInputs": [
          {"name": "beginningValue", "meaning": "明确起点对应的数值"},
          {"name": "endingValue", "meaning": "明确终点对应的数值"},
          {"name": "intervalCount", "meaning": "按方法约定换算的实际区间长度"}
        ],
        "unresolvedTerms": ["这几年"],
        "clarificationQuestions": ["希望从哪个交易日算到哪个交易日？"]
      }
    ]
  },
  "error": null
}
```

`resolverToolCallId` 在 `data` 根部只出现一次，由 Java 注入。definition、执行输入、来源和库样例由 Java 从 canonical MethodSpec 补齐。解析快照必须在工具成功返回前整批原子保存；保存失败时工具失败，不返回 `adviceDurable`、`persisted` 或半数建议。

### 3.5 MethodSpec 公共字段和首批 YAML 草案

人工 YAML 固定放在：

```text
agentToolsShared/src/main/resources/finance/method-specs/v1/cagr.yaml
agentToolsShared/src/main/resources/finance/method-specs/v1/annualized_volatility.yaml
agentToolsShared/src/main/resources/finance/method-specs/v1/sharpe_ratio.yaml
```

三份文件共用一个 `method-spec-v1.schema.json`。公共字段固定为：

```yaml
schemaVersion: "1"
methodId: finance.growth.cagr
version: 1.0.0
displayName: 复合年均增长率
definition: 从起始值到结束值、按给定期数换算的平滑年均增长率
resolverHints:
  aliases: [复合增长率, CAGR, 年复合增长率]
  commonPhrases: [这段时间平均每期涨多少, 换成可比较的复合增长速度]
  clarificationDimensions:
    - id: rangeBoundary
      question: 希望从哪个日期或交易日算到哪个日期或交易日
parameters:
  beginningValue: {type: number, required: true}
  endingValue: {type: number, required: true}
  periods: {type: integer, required: true, minimum: 1}
conventions:
  finance.growth:
    periodMeaning: 起止时点之间的期间数
    calculationExpression: (endingValue / beginningValue) ** (1 / periods) - 1
outputs:
  - {name: cagr, unit: ratio, description: 复合年均增长率}
libraryBinding:
  package: alphafrog_finance
  function: cagr
  apiCompatRange: ">=1.0.0,<2.0.0"
sourceRefs:
  - method-knowledge:finance.growth.cagr@1.0.0
```

schema 规则：

- `parameters` 的键保持开放，每个键描述真正的函数输入；
- `conventions` 和 `extensions` 是按命名空间分组的开放对象；
- `resolverHints` 只包含别名、常见自然语言说法和澄清维度；
- `libraryBinding` 可省略，存在时必须包含 package、function 和 `apiCompatRange`；
- canonical JSON 由构建插件生成并按稳定键序列化，`specDigest` 对不含自身摘要字段的 canonical 字节求 SHA-256；
- `index.json` 和 `resolver-catalog.json` 都由同一批 canonical JSON 生成，不能维护第二份方法身份或 resolver 文案。

首批三份 YAML 必须证明参数空间没有被 CAGR 固化：

| 方法 | 必填参数草案 | 方法特有约定 |
|---|---|---|
| CAGR | `beginningValue/endingValue/periods` | 期间含义与增长表达式 |
| 年化波动率 | `returns/periodsPerYear`，可选 `window` | 收益序列、年化因子和可选观察窗口；样本标准差固定 `ddof=1` |
| Sharpe | `returns`，可选 `riskFreeRate/riskFreeRateConvention/ddof/periodsPerYear/returnConvention` | 算术/几何收益、无风险利率频率和样本自由度 |

三份草案都不得新增全局 `window`，也不得把自然年、月、交易日或任意日期范围写成 resolver 工具的固定输入字段。构建产物进入 jar，人工 YAML 和 schema 不进入运行时 jar。

## 4. 金融计算结果记录协议

### 4.1 固定标记

标记族：

```text
__AF_FINANCE_RESULT_
```

首版标记：

```text
__AF_FINANCE_RESULT_v1__
```

`report()` 和 `report_custom()` 都输出首版标记。未知版本行进入后台格式审计，不能混回模型普通 stdout。

### 4.2 字节和摘要

- `rawPayload` 是首版标记后的原始 JSON UTF-8 字节，不含标记和换行；
- `rawDigest = SHA-256(rawPayload)`；
- `emittedRecordBytes` 是本批所有 rawPayload 字节数之和；
- 批次摘要输入按原顺序连接 `uint32be(rawPayload.length) || rawPayload`；
- `recordDigest = SHA-256(批次摘要输入)`；
- 空批次摘要是 SHA-256 空字节串；
- Python 和 Java 必须读取逐字一致的 fixture，不得各自重写 JSON 后再求摘要。
- 未知 marker 版本行进入后台格式审计，不能混回普通 stdout；
- stdout 未截断时保留原始字节和原行顺序；普通 stdout 超限时，先返回受限普通 stdout，再按原记录顺序附完整记录行并设置 `stdoutTruncated=true`，这是唯一允许改变原行顺序的情况。

共享 fixture：

```text
pythonSandboxService/tests/fixtures/finance-record-channel-v1.json
agentPlatformShared/src/test/resources/finance/finance-record-channel-v1.json
```

fixture 至少同时包含一条合法 CAGR、一条参数不含年度语义的合法自定义记录和一条 schema-invalid 自定义记录；每条都固定原始字节、单条摘要和批次摘要。schema-invalid 样例只用于跨语言解析与审计测试，不得进入用户结果块。

### 4.3 v1 记录字段

公共字段：

- `schemaVersion="1"`；
- `value`：合法 JSON 数值且有限；
- `unit`：非空字符串；
- `parameters`：开放 JSON object，不规定全局键；
- `inputRefs`：有界字符串数组；
- `checks`：有界 JSON object；
- `evidence`：`LIBRARY_CALL_DECLARED`、`CUSTOM_WITH_CHECKS`、`CUSTOM_UNVERIFIED`；
- `environmentId`：由报告函数从只读任务环境文件取得；
- `sourceResolverToolCallId`：可空，由模型从 resolver 根部显式复制。

公共库记录另要求完整方法三元组。`report()` 从随包 canonical JSON 自动填写，调用者不能覆盖。

自定义记录要求 `formulaDescription`。Python API 的 `output_unit`/`outputUnit` 参数统一序列化为记录字段 `unit`。自定义记录可以：

- 不关联 resolver；
- 只保留 `sourceResolverToolCallId` 作为调用来源；
- 在完整提供方法三元组时精确关联某条解析快照。

缺少完整来源元组时 Java 不猜最近一次建议。来源关系不能升级三个后台证据等级。

公共库记录示例：

```text
__AF_FINANCE_RESULT_v1__{"schemaVersion":"1","methodId":"finance.growth.cagr","methodVersion":"1.0.0","specDigest":"sha256:spec-example","sourceResolverToolCallId":"tool-call-resolver-1","environmentId":"sha256:actual-runtime-example","value":0.12468265,"unit":"ratio","parameters":{"beginningValue":100.0,"endingValue":160.0,"periods":4},"inputRefs":["dataset:1"],"checks":{"finite":true},"evidence":"LIBRARY_CALL_DECLARED"}
```

该 rawPayload 固定为 401 个 UTF-8 字节：

- `rawDigest=eb4382d97e74ff45f9b2a28d967f44af2f083404ac535287e70bc1d9e36a8a20`；
- `recordDigest=d8df42125c1d75224cb9a91b7e254c9dedd342bcca4084ab66bfa2979396bdb9`。

## 5. Python HTTP 与 proto 草案

### 5.1 HTTP ExecuteResult

Python HTTP 保留受限 stdout/stderr、完整性元数据和实际环境，字段使用 snake_case。它是后端内部契约，不是模型输出。

```json
{
  "exit_code": 0,
  "stdout": "rows=5\n__AF_FINANCE_RESULT_v1__{...}",
  "stderr": "",
  "dataset_dir": "/sandbox/input",
  "finance_record_channel": {
    "emitted_record_count": 1,
    "emitted_record_bytes": 401,
    "record_set_complete": true,
    "drop_reason": "",
    "record_digest": "d8df42125c1d75224cb9a91b7e254c9dedd342bcca4084ab66bfa2979396bdb9",
    "stdout_truncated": false,
    "stderr_truncated": false
  },
  "execution_environment": {
    "environment_id": "sha256:actual-runtime-example",
    "image_digest": "sha256:image-example",
    "library_set_digest": "sha256:library-set-example",
    "package_apis": [
      {"name": "alphafrog_finance", "version": "1.0.3", "api_version": "1.0"}
    ],
    "inventory_complete": true
  },
  "retryable": false
}
```

### 5.2 proto 字段

现有 `TaskResultResponse` 1—9 不变。新增字段固定为 10 和 11：

```proto
message FinanceRecordChannelMetadata {
  int32 emittedRecordCount = 1;
  int64 emittedRecordBytes = 2;
  bool recordSetComplete = 3;
  string dropReason = 4;
  string recordDigest = 5;
  bool stdoutTruncated = 6;
  bool stderrTruncated = 7;
}

message SandboxPackageApi {
  string name = 1;
  string version = 2;
  string apiVersion = 3;
}

message SandboxEnvironmentIdentity {
  string environmentId = 1;
  string imageDigest = 2;
  string librarySetDigest = 3;
  repeated SandboxPackageApi packageApis = 4;
  bool inventoryComplete = 5;
}

message TaskResultResponse {
  // existing fields 1—9 unchanged
  FinanceRecordChannelMetadata financeRecordChannel = 10;
  SandboxEnvironmentIdentity executionEnvironment = 11;
}
```

父消息不存在表示旧生产方尚未实现本协议，不能把 proto 默认值当作完整批次。协议已生效但没有记录时，父消息仍存在，`recordSetComplete=true`、条数/字节为 0、摘要为 SHA-256 空值。

网关只做 HTTP DTO → proto 的存在性保持和类型映射：不解析 marker、不验证记录 schema、不判断证据、不生成模型投影。

## 6. 终态顺序

### 6.1 共同主链路

```text
Python HTTP ExecuteResult
→ Gateway presence-aware 映射为 TaskResultResponse
→ SandboxTerminalResultValidator 校验终态/成功/退出码/结果存在性
→ FinanceRecordChannelProcessor 解析、数量/字节/摘要/schema/环境校验
→ 幂等保存解析快照关联、批次和记录
→ 写完成标记
→ 生成模型 allowlist 投影
→ 执行模型
```

### 6.2 同步入口

```text
完整 TaskResultResponse
→ SandboxTerminalResultValidator
→ FinanceRecordChannelProcessor
→ 对去 marker 普通 stdout 做 boundedPreview
→ anchor.finalizerStep=ENVELOPE
→ persistAttached
→ formatResult 生成最小投影
→ 返回模型
```

### 6.3 异步入口

```text
ToolJobFinalizer 取得完整终态结果
→ SandboxTerminalResultValidator
→ FinanceRecordChannelProcessor
→ 对去 marker 普通 stdout/allowlist 错误做 boundedPreview
→ 写 ENVELOPE/terminalResultPreview
→ resumeTerminalOutput 注入同语义最小投影
→ 返回模型
```

resume 不重新解析 16KB preview。同步和异步保存同一执行批次时依赖执行记录五元组幂等；同键内容不同是身份冲突，禁止覆盖。

ENVELOPE 写入失败后再次进入该步骤时，Processor 必须对同一批次幂等；已经保存的同键同内容记录是无操作，同键不同内容仍按身份冲突失败。

缺少可信 `runId`、`todoId` 或 `executePythonToolCallId` 时整批拒绝，不能生成临时身份。数据库使用五列复合唯一约束，不依赖字符串拼接；冲突后必须读回比较内容，不能只用 `ON CONFLICT DO NOTHING` 静默吞掉差异。

保存失败时不得写 ENVELOPE，也不得返回 `ok=true`。FAILED、CANCELED、RESULT_LOST 即使 stdout 中含 marker，也不得保存业务记录。

## 7. Java 校验顺序

1. taskId 和结果对象存在；
2. 终态为 SUCCEEDED、退出码为 0；
3. `financeRecordChannel` 父消息存在；
4. 记录条数、字节数和批次摘要一致；
5. 单条和总量不超过 Java 硬上限/生效快照；
6. 每条 JSON 符合 v1 字段规则；
7. 记录 `environmentId` 和 proto 实际环境一致，proto 为权威值；
8. 来源 ID 和方法三元组完整时，使用可信 runId 精确查询解析快照；
9. 目标/实际包 API 兼容性检查；
10. 幂等保存，再写后台审计事件。

环境与包接口检查区分三种事实：

- 记录声明的 `environmentId` 与 proto 实际环境不一致、实际环境父消息缺失、实际包 API 不兼容时，记录保留后台审计，内部证据降为 `CUSTOM_UNVERIFIED`；普通任务仍可成功，呈现资格按后台规则决定，不能一律改成 `renderable=false`；记录本身缺少必填 `environmentId` 时仍按 schema-invalid 处理；
- 已保存 resolver 目标环境与 proto 实际环境不一致时，写 `FINANCE_CROSS_ENVIRONMENT`，并降级内部证据；
- 上述差异、证据降级、环境身份和包版本都不得进入模型投影或用户结果；若记录仍符合后台呈现规则，公开内容继续只有三列。

解析输出要区分：

- 后台保存结果；
- 去除全部 marker 行后的有界普通 stdout；
- 面向模型的可操作 warning/error 分类。

## 8. 数据库草案

迁移名称和正式发布版本由 frog 确认。本节只冻结逻辑字段与唯一键。

### 8.1 `alphafrog_agent_finance_method_resolution`

每个 suggestion 一行：

```text
run_id
resolver_tool_call_id
todo_id
method_id
method_version
spec_digest
catalog_digest
resolver_schema_version
resolver_prompt_version
model_route_json
match_reason
clarification_json
target_environment_id
target_package_api_json
resolution_payload_json
resolution_content_digest
created_at
```

唯一键：

```text
(run_id, resolver_tool_call_id, method_id, method_version, spec_digest)
```

同键同内容重放为幂等；同键内容不同为身份冲突。建议必须整批原子保存；`NO_ADVICE` 不写 suggestion 行，普通工具事件表达调用状态。`todo_id` 只记录调用归属，不参与解析快照查询。

### 8.2 `alphafrog_agent_finance_record_batch`

至少保存：

```text
run_id
todo_id
execute_python_tool_call_id
entry_point
terminal_status
exit_code
record_count
record_bytes
record_digest
record_set_complete
drop_reason
schema_valid
renderable
actual_environment_json
validation_error_json
created_at
updated_at
```

批次身份：`run_id + todo_id + execute_python_tool_call_id`。

### 8.3 `alphafrog_agent_finance_record`

至少保存：

```text
record_id
run_id
todo_id
execute_python_tool_call_id
record_index
raw_digest
raw_payload
source_resolver_tool_call_id nullable
method_id nullable
method_version nullable
spec_digest nullable
value_json
unit
parameters_json
input_refs_json
checks_json
formula_description nullable
declared_evidence
effective_internal_evidence
actual_environment_id
renderable
validation_error_json
created_at
```

唯一键：

```text
(run_id, todo_id, execute_python_tool_call_id, record_index, raw_digest)
```

`recordId` 对同一五元组的长度前缀编码求 SHA-256。结果表不建立强制来源外键；只有可信 `runId + sourceResolverToolCallId` 和完整方法三元组精确命中时才关联解析快照，无效、跨 run 或不完整来源仍需保留审计记录并降级。

## 9. 保存与呈现失败矩阵

| 情况 | 业务记录 | 后台审计 | 完成标记 | 模型 | 用户结果 |
|---|---|---|---|---|---|
| 无记录且协议完整 | 无 | 批次可记 | 可写 | 普通 stdout | 无金融表 |
| 通道条数/字节/摘要失败 | 不保存 | 只保存批次 | 普通任务可继续 | 成功 + 可操作 warning | 不生成 |
| 单条 schema 不合法 | 整批业务不可呈现；保留原始审计 | 批次+原始记录 | 普通任务可继续 | 成功 + 可操作 warning | 不生成 |
| 环境/包 API 不一致 | 保存并降级内部证据 | 事件+记录 | 可写 | 不泄漏环境警告 | 按后台资格决定；若显示仍三列 |
| 解析/数据库暂时失败 | 不确认成功 | 错误事件 | 不得写 | 不得返回成功 | 不生成 |
| FAILED/CANCELED/RESULT_LOST | 不保存 | 终态错误 | 按原终态 | 可操作失败 | 不生成 |

## 10. 模型投影

### 10.1 成功 allowlist

```json
{
  "ok": true,
  "tool": "executePython",
  "data": {
    "stdout": "rows=5",
    "results": [
      {
        "method": "复合增长率",
        "value": 0.12468265,
        "unit": "ratio",
        "howCalculated": "按已明确的起止值和实际区间长度计算复合增长速度"
      }
    ],
    "warnings": [
      {
        "code": "FINANCE_RESULT_REJECTED",
        "message": "本次结构化金融结果没有被接收",
        "action": "检查 report_custom() 必填字段或减少单批记录数量后重试"
      }
    ]
  },
  "error": null
}
```

`stdout/results/warnings` 空时省略。`stdout` 必须有界且删除全部 marker 族行。

### 10.2 失败 allowlist

```json
{
  "ok": false,
  "tool": "executePython",
  "data": {
    "stdout": "rows=5",
    "stderr": "NameError: name 'resolved_interval_count' is not defined"
  },
  "error": {
    "code": "PYTHON_EXECUTION_FAILED",
    "message": "代码执行失败",
    "retryable": true,
    "action": "定义缺失变量，或先根据明确的起止边界计算该值"
  }
}
```

### 10.3 denylist

模型投影不得包含：

```text
taskId / datasetDir / runId / todoId / toolCallId
recordId / recordIndex / rawPayload / marker JSON
count / bytes / digest / truncated flags
environmentId / imageDigest / librarySetDigest / packageApis / SBOM
evidence / renderable / blockId / rendererVersion
```

若以后需要模型读取大型产物，只能返回正式、稳定、可消费且受权限保护的 `rawRef`，不能返回沙箱内部路径。

## 11. 用户最终结果

服务端只生成：

```markdown
### 金融计算结果

| 方法 | 结果 | 如何计算 |
|---|---:|---|
| 复合增长率 | 12.47% | 使用已明确的起始值 100、结束值 160 和实际区间长度 4，按 `(结束值 / 起始值)^(1 / 区间长度) - 1` 计算 |
```

最终字符串不得包含方法版本、specDigest、包/API 版本、参数 JSON、输入引用、证据名、环境身份、resolver/executePython 身份、recordId、blockId、rendererVersion 或 CROSS_ENVIRONMENT。自定义方法的“如何计算”来自通过字段检查的 `formulaDescription`，公共方法来自 canonical MethodSpec 和已保存执行参数。

## 12. 可观测性和通用工具事件

resolver 调用成功/失败继续使用现有 `TOOL_CALL_STARTED/FINISHED` 与 LLM trace，不新增 `adviceDurable` 状态事件。

金融后台事件：

```text
FINANCE_RECORD_BATCH_ACCEPTED
FINANCE_RECORD_BATCH_DROPPED
FINANCE_RECORD_SCHEMA_INVALID
FINANCE_RECORD_ENVIRONMENT_MISMATCH
FINANCE_CROSS_ENVIRONMENT
FINANCE_RESULT_BLOCK_RENDERED
```

事件使用稳定去重键，只进入后台事件、日志和低基数指标，不进入模型或最终字符串。

## 13. 输出上限与配置快照

1. 子进程、Python HTTP、Java 网关/Dubbo、Java 解析/保存四段分别实测；生产默认取最小已验证值并留余量。
2. 静态硬上限只能被 Nacos 调低，不能被动态配置提高。
3. Python 在 task 创建时冻结 output limit 快照；幂等 create 返回原快照。
4. Java 在 run/anchor 冻结 finance limit 快照；同步/异步读取同一份。
5. 模型成功 stdout 和失败 stdout/stderr 再设独立上下文上限。
6. 目录 prompt 字节/令牌超预算时不得静默截断或遗漏方法。

Python task 快照固定 `recordChannelMaxRecords/recordChannelMaxBytes/stdoutMaxBytes/stderrMaxBytes` 和 source revision；Java run/anchor 快照固定 `recordCountMax/recordMaxBytes/recordChannelMaxBytes/stdoutMaxBytes/stderrMaxBytes` 及 `targetEnvironmentId`。配置加载顺序是应用默认值 → 整份合法动态值 → 代码硬上限缩小；非法动态值保留 last-known-good，幂等 create 返回原快照，执行中不得读取更新后的配置。

正式数字必须由工作包 C/D 的四段测试确认，本协议不编造生产值。

## 14. 兼容与非目标

- proto 1—9 不改号；字段 10/11 使用 presence；
- `executePython` 保持现有五个对外参数；
- 不增加可算指标白名单、平台计算入口或 `PLATFORM_COMPUTE`；
- 不证明计算来源真实性；三个 evidence 只表示后台声明强度；
- 不在首批三个方法阶段建设向量/BM25 检索；
- 不做集群、灰度、严格 run 级环境冻结或远程注册中心；
- Agent 不 push origin。

## 15. 冻结门禁

本文件从 `DRAFT_FOR_CROSS_REVIEW` 改为 `FROZEN` 前，必须取得：

- Kimi：MethodSpec/resolver/记录语义 PASS；
- ccmax：HTTP/proto/Java presence 与环境字段 PASS；
- dpsk：幂等、完成顺序、finalizer/resume PASS；
- Codex：汇总修订、双 fixture 字节一致、digest 重算、基线提交。

冻结后发布 `CONTRACT_BASE_SHA`。A—H 不得在各自工作包重定义公共字段；变更只能以 additive follow-up commit 进行，并重新取得受影响 owner/reviewer PASS。
