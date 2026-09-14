package world.willfrog.agent.tools.router;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.config.StressTestProperties;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.service.AgentRunObservabilityService;
import world.willfrog.agent.tools.compaction.RereadToolHandler;
import world.willfrog.agent.tools.dataset.ListMyDataTool;
import world.willfrog.agent.tools.docs.LoadToolGuideTool;
import world.willfrog.agent.tools.finance.FinanceMethodTools;
import world.willfrog.agent.tools.market.MarketDataTools;
import world.willfrog.agent.tools.python.PythonSandboxTools;
import world.willfrog.agent.tools.rag.RagTools;
import world.willfrog.agent.tools.registry.AgentToolRegistry;
import world.willfrog.agent.tools.search.SearchTools;
import world.willfrog.agent.tools.subagent.SubAgentControlHandler;

import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ToolRouter 与 AgentToolRegistry 的一致性契约测试。
 *
 * <p>对注册表中声明的每个工具名，调用真实 Router 都不能落到 UNSUPPORTED_TOOL；
 * capability-gated 工具返回 CAPABILITY_DISABLED 是已实现语义，允许。</p>
 */
class ToolRouterRegistryConsistencyTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void enableCapabilities() {
        // searchWeb 需要显式开启；其余门控默认关闭的会在构造 Router 的属性里打开
        AgentContext.setWebSearchEnabled(true);
    }

    @AfterEach
    void resetContext() {
        AgentContext.clear();
    }

    // 直接以注册表声明面为数据源：新增声明若没有可执行路由，本参数化测试会立即失败
    static Set<String> declaredToolNames() {
        return AgentToolRegistry.declaredToolNames();
    }

    @ParameterizedTest
    @MethodSource("declaredToolNames")
    void everyDeclaredTool_doesNotReturnUnsupported(String toolName) throws Exception {
        ToolRouter.ToolInvocationResult result = invokeTool(toolName);
        JsonNode root = objectMapper.readTree(result.getOutput());
        JsonNode errorCode = root.path("error").path("code");
        assertNotEquals("UNSUPPORTED_TOOL", errorCode.asText(""),
                toolName + " 已声明但 Router 返回 UNSUPPORTED_TOOL");
    }

    @Test
    void supportedTools_equalsDeclaredToolNames() {
        ToolRouter router = new ToolRouter(
                mock(MarketDataTools.class),
                mock(RagTools.class),
                mock(SearchTools.class),
                mock(PythonSandboxTools.class),
                mock(FinanceMethodTools.class),
                mock(LoadToolGuideTool.class),
                mock(ListMyDataTool.class),
                new PythonStaticPrecheckService(),
                llmPropertiesWithAllEnabled(),
                mock(ToolResultCacheService.class),
                mock(RereadToolHandler.class),
                mock(AgentRunObservabilityService.class),
                new ObjectMapper(),
                new SimpleMeterRegistry(),
                new StressTestProperties()
        );
        assertEquals(AgentToolRegistry.declaredToolNames(), router.supportedTools(),
                "supportedTools 必须精确等于注册表声明面");
    }

    @Test
    void bogusTool_returnsUnsupported() throws Exception {
        ToolRouter.ToolInvocationResult result = invokeTool("nonExistentTool");
        JsonNode root = objectMapper.readTree(result.getOutput());
        assertFalse(root.path("ok").asBoolean(true));
        assertEquals("UNSUPPORTED_TOOL", root.path("error").path("code").asText());
    }

    @Test
    void subAgentControls_delegateToProductionHandler() throws Exception {
        SubAgentControlHandler handler = new SubAgentControlHandler() {
            @Override
            public String spawn(Map<String, Object> params) {
                return okJson("spawnSubAgent");
            }

            @Override
            public String waitFor(Map<String, Object> params) {
                return okJson("waitForSubAgent");
            }
        };

        assertTrue(objectMapper.readTree(invokeTool("spawnSubAgent", handler).getOutput())
                .path("ok").asBoolean());
        assertTrue(objectMapper.readTree(invokeTool("waitForSubAgent", handler).getOutput())
                .path("ok").asBoolean());
    }

    private ToolRouter.ToolInvocationResult invokeTool(String toolName) throws Exception {
        return invokeTool(toolName, null);
    }

    private ToolRouter.ToolInvocationResult invokeTool(String toolName,
                                                       SubAgentControlHandler subAgentControlHandler) throws Exception {
        MarketDataTools marketDataTools = mock(MarketDataTools.class);
        when(marketDataTools.checkParallelLimits()).thenReturn(okJson("checkParallelLimits"));
        when(marketDataTools.getStockInfo(anyString())).thenReturn(okJson("getStockInfo"));
        when(marketDataTools.getStockDaily(anyString(), anyString(), anyString())).thenReturn(okJson("getStockDaily"));
        when(marketDataTools.getStockSwIndustryInfo(anyString())).thenReturn(okJson("getStockSwIndustryInfo"));
        when(marketDataTools.searchStock(anyString())).thenReturn(okJson("searchStock"));
        when(marketDataTools.searchFund(anyString())).thenReturn(okJson("searchFund"));
        when(marketDataTools.getIndexInfo(anyString())).thenReturn(okJson("getIndexInfo"));
        when(marketDataTools.getIndexDaily(anyString(), anyString(), anyString())).thenReturn(okJson("getIndexDaily"));
        when(marketDataTools.searchIndex(anyString(), any(), any())).thenReturn(okJson("searchIndex"));
        when(marketDataTools.searchIndexAdvanced(any())).thenReturn(okJson("searchIndex"));
        when(marketDataTools.searchAssetInfo(anyString(), anyString(), anyString(), any(), any())).thenReturn(okJson("searchAssetInfo"));
        when(marketDataTools.searchAssetInfoAdvanced(any())).thenReturn(okJson("searchAssetInfo"));
        when(marketDataTools.getTradingDaysSummary(anyString(), anyString(), anyString())).thenReturn(okJson("getTradingDaysSummary"));
        when(marketDataTools.isTradingDay(anyString(), anyString())).thenReturn(okJson("isTradingDay"));
        when(marketDataTools.getExchangeAssetDaily(anyString(), anyString(), anyString(), anyString(), anyString(), any(), any())).thenReturn(okJson("getExchangeAssetDaily"));
        when(marketDataTools.getExchangeAssetDailyAdvanced(any(), anyString(), anyString(), anyString(), anyString())).thenReturn(okJson("getExchangeAssetDaily"));
        when(marketDataTools.getOffExchangeAssetDaily(anyString(), anyString(), anyString())).thenReturn(okJson("getOffExchangeAssetDaily"));
        when(marketDataTools.getEtfAdj(anyString(), anyString(), anyString())).thenReturn(okJson("getEtfAdj"));
        when(marketDataTools.getListedAssetShareSize(anyString(), anyString(), anyString(), anyString())).thenReturn(okJson("getListedAssetShareSize"));
        when(marketDataTools.getFinancialReport(anyString(), anyString(), anyString(), anyString())).thenReturn(okJson("getFinancialReport"));

        RagTools ragTools = mock(RagTools.class);
        when(ragTools.ragSearch(anyString(), anyString(), anyString(), anyString(), anyInt())).thenReturn(okJson("ragSearch"));
        when(ragTools.loadDocument(anyString())).thenReturn(okJson("loadDocument"));

        SearchTools searchTools = mock(SearchTools.class);
        when(searchTools.searchWeb(anyString(), anyString(), anyString(), anyString(), anyBoolean(), anyBoolean(), anyString(), anyString(), anyInt())).thenReturn(okJson("searchWeb"));

        PythonSandboxTools pythonSandboxTools = mock(PythonSandboxTools.class);
        when(pythonSandboxTools.executePython(anyString(), anyString(), anyString(), anyString(), any())).thenReturn(okJson("executePython"));

        FinanceMethodTools financeMethodTools = mock(FinanceMethodTools.class);
        when(financeMethodTools.resolveFinanceMethods(anyString(), anyString())).thenReturn(okJson("resolveFinanceMethods"));

        LoadToolGuideTool loadToolGuideTool = mock(LoadToolGuideTool.class);
        when(loadToolGuideTool.loadToolGuide(anyString())).thenReturn(okJson("loadToolGuide"));

        ListMyDataTool listMyDataTool = mock(ListMyDataTool.class);
        when(listMyDataTool.listMyData(anyString(), anyString(), anyString(), any(), any(), any(), any(), anyString())).thenReturn(okJson("listMyData"));

        RereadToolHandler rereadToolHandler = mock(RereadToolHandler.class);
        when(rereadToolHandler.reread(anyString(), anyString(), any(), any())).thenReturn(okJson("rereadToolResult"));

        ToolResultCacheService cacheService = mock(ToolResultCacheService.class);
        when(cacheService.executeWithCache(anyString(), any(), anyString(), any())).thenAnswer(inv -> {
            Supplier<ToolResultCacheService.ToolExecutionOutcome> supplier = inv.getArgument(3);
            ToolResultCacheService.ToolExecutionOutcome outcome = supplier.get();
            return ToolResultCacheService.CachedToolCallResult.builder()
                    .result(outcome.getResult())
                    .observabilityResult(outcome.getResult())
                    .durationMs(outcome.getDurationMs())
                    .success(outcome.isSuccess())
                    .build();
        });

        ToolRouter router = new ToolRouter(
                marketDataTools,
                ragTools,
                searchTools,
                pythonSandboxTools,
                financeMethodTools,
                loadToolGuideTool,
                listMyDataTool,
                new PythonStaticPrecheckService(),
                llmPropertiesWithAllEnabled(),
                cacheService,
                rereadToolHandler,
                mock(AgentRunObservabilityService.class),
                new ObjectMapper(),
                new SimpleMeterRegistry(),
                new StressTestProperties()
        );
        ReflectionTestUtils.setField(router, "subAgentControlHandler", subAgentControlHandler);

        Map<String, Object> params = toolSpecificParams(toolName);
        return router.invokeWithMeta(toolName, params);
    }

    private Map<String, Object> toolSpecificParams(String toolName) {
        return switch (toolName) {
            case "executePython" -> Map.of("code", "print(1)");
            case "searchWeb" -> Map.of("query", "q");
            case "getStockDaily", "getIndexDaily", "getOffExchangeAssetDaily",
                 "getEtfAdj", "getListedAssetShareSize" -> Map.of("tsCode", "000001.SZ", "startDateStr", "20240101", "endDateStr", "20240105");
            case "getExchangeAssetDaily" -> Map.of("tsCode", "000001.SZ", "assetType", "stock", "startDateStr", "20240101", "endDateStr", "20240105", "priceMode", "raw_ohlc");
            case "getStockInfo", "getIndexInfo", "getStockSwIndustryInfo" -> Map.of("tsCode", "000001.SZ");
            case "searchStock", "searchFund", "searchIndex" -> Map.of("keyword", "q");
            case "searchAssetInfo" -> Map.of("query", "q", "assetTypes", "stock", "marketScope", "domestic");
            case "getTradingDaysSummary" -> Map.of("startDate", "20240101", "endDate", "20240105");
            case "isTradingDay" -> Map.of("date", "20240102");
            case "getFinancialReport" -> Map.of("tsCode", "000001.SZ", "reportType", "income", "startPeriod", "20240101", "endPeriod", "20240331");
            case "ragSearch" -> Map.of("queryText", "q");
            case "loadDocument" -> Map.of("ossUrl", "http://x");
            case "resolveFinanceMethods" -> Map.of("query", "q", "context", "c");
            case "loadToolGuide" -> Map.of("topic", "t");
            case "rereadToolResult" -> Map.of("rawRef", "ref");
            case "listMyData" -> Map.of("query_type", "dataset");
            case "spawnSubAgent" -> Map.of("goal", "check one bounded fact");
            case "waitForSubAgent" -> Map.of("subAgentIds", java.util.List.of("sa_1"));
            default -> Map.of();
        };
    }

    private String okJson(String toolName) {
        return "{\"ok\":true,\"tool\":\"" + toolName + "\",\"data\":{},\"error\":null}";
    }

    private AgentLlmProperties llmPropertiesWithAllEnabled() {
        AgentLlmProperties properties = new AgentLlmProperties();
        AgentLlmProperties.Runtime runtime = new AgentLlmProperties.Runtime();
        AgentLlmProperties.Execution execution = new AgentLlmProperties.Execution();
        execution.setStaticPrecheckEnabled(false);
        execution.setAdjFactorEnabled(true);
        runtime.setExecution(execution);
        properties.setRuntime(runtime);
        return properties;
    }
}
