# agentToolsShared package boundary

## In scope (A2)

| Package | Classes |
|---------|---------|
| `world.willfrog.agent.tools.dataset` | DatasetRegistry, DatasetWriter |
| `world.willfrog.agent.tools.finance` | FinanceMethodSpec, FinanceMethodSpecCatalog, FinanceMethodResolverCatalog, FinanceMethodResolverClient, FinanceMethodResolutionValidator, FinanceMethodSuggestionRenderer, FinanceMethodResolutionSnapshot, FinanceMethodResolutionSink, FinanceMethodResolutionSinkException, FinanceMethodKnowledgeCatalog, FinanceMethodTools, FinanceResultModelProjector |
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

- `spawnSubAgent` / `waitForSubAgent` tool specs (ReactTodoExecutor)
- `AgentToolCatalogService`, simple tool fast path

These legacy-only surfaces are not part of the current langchain runtime contract.
If revived, migrate them explicitly instead of depending on the legacy `agentService`
module.
