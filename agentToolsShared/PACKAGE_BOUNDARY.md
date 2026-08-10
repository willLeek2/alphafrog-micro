# agentToolsShared package boundary

## In scope (A2)

| Package | Classes |
|---------|---------|
| `world.willfrog.agent.tools.dataset` | DatasetRegistry, DatasetWriter |
| `world.willfrog.agent.tools.finance` | FinanceMethodSpec, FinanceMethodSpecCatalog, FinanceMethodResolverCatalog, FinanceMethodResolutionValidator, FinanceMethodSuggestionRenderer, FinanceMethodKnowledgeCatalog, FinanceMethodTools, FinanceResultModelProjector, FinanceTargetEnvironmentProvider（resolver client/sink/snapshot SPI 位于下层 agentPlatformShared 的 world.willfrog.agent.platform.finance，避免依赖环） |
| `world.willfrog.agent.tools.market` | MarketDataTools |
| `world.willfrog.agent.tools.python` | PythonSandboxTools |
| `world.willfrog.agent.tools.rag` | RagTools |
| `world.willfrog.agent.tools.search` | SearchTools |
| `world.willfrog.agent.tools.router` | ToolRouter, ToolResultCacheService, ToolWeightedLimitService, PythonStaticPrecheckService |

## Dependencies

- `agentPlatformShared` for config/context/service platform types
- Dubbo API modules for market/rag/search/python tools
- Must not depend on `agentService` or `workflow/*` executors

## Out of scope (not active in agentLangchainService)

- `spawnSubAgent` / `waitForSubAgent` tool specs
- `AgentToolCatalogService`, simple tool fast path

`spawnSubAgent` / `waitForSubAgent` 在 D05 之后已从生产声明面移除；当前 `ToolRouter.supportedTools()`
和 `AgentToolRegistry` 均不包含这两个工具。D06 会在补齐同生同死的可执行路由与注册表声明后，
再让它们同时回归声明面。

## Single source of truth

`AgentToolRegistry`（`world.willfrog.agent.tools.registry`）是平台工具的单一声明源。
可路由工具名、压缩资格、缓存族、并行上限说明组、批量计数键族、canonical 覆盖来源、
能力门控等元数据全部在此登记一次。派生面（`ToolRouter.supportedTools()`、
`CompactionEligibleTools`、`ToolResultCacheService` 的缓存族集合等）由注册表派生，
并通过契约测试校验，确保「声明 ⊆ 可路由」的不变量：注册表中出现的每个名字都必须在
`ToolRouter` 有可执行分发；能力关闭返回 `CAPABILITY_DISABLED` 属已实现语义，
允许；仅因未实现而返回 `UNSUPPORTED_TOOL` 不允许。

These legacy-only surfaces are not part of the current langchain runtime contract.
If revived, migrate them explicitly instead of depending on the legacy `agentService`
module.
