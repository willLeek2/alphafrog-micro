package world.willfrog.agent.tools.router;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.config.StressTestProperties;
import world.willfrog.agent.platform.service.AgentRunObservabilityService;
import world.willfrog.agent.tools.compaction.RereadToolHandler;
import world.willfrog.agent.tools.docs.LoadToolGuideTool;
import world.willfrog.agent.tools.dataset.ListMyDataTool;
import world.willfrog.agent.tools.finance.FinanceMethodTools;
import world.willfrog.agent.tools.market.MarketDataTools;
import world.willfrog.agent.tools.python.PythonSandboxTools;
import world.willfrog.agent.tools.rag.RagTools;
import world.willfrog.agent.tools.search.SearchTools;

import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

/**
 * Task #70 review fix: ToolRouter must route getStockSwIndustryInfo to MarketDataTools
 * with proper parameter alias resolution (tsCode / ts_code / code / stock_code / arg0).
 */
class ToolRouterGetStockSwIndustryInfoRouteTest {

    @SuppressWarnings("unchecked")
    @Test
    void invoke_shouldRouteGetStockSwIndustryInfoWithAliasParams() throws Exception {
        MarketDataTools marketDataTools = mock(MarketDataTools.class);
        when(marketDataTools.getStockSwIndustryInfo(eq("000001.SZ")))
                .thenReturn("{\"ok\":true,\"tool\":\"getStockSwIndustryInfo\",\"data\":{\"ts_code\":\"000001.SZ\",\"count\":1,\"items\":[]},\"error\":null}");

        ToolResultCacheService cacheService = mock(ToolResultCacheService.class);
        when(cacheService.executeWithCache(anyString(), any(), anyString(), any())).thenAnswer(inv -> {
            Supplier<ToolResultCacheService.ToolExecutionOutcome> supplier = inv.getArgument(3);
            ToolResultCacheService.ToolExecutionOutcome outcome = supplier.get();
            return ToolResultCacheService.CachedToolCallResult.builder()
                    .result(outcome.getResult())
                    .durationMs(outcome.getDurationMs())
                    .success(outcome.isSuccess())
                    .build();
        });

        ToolRouter router = new ToolRouter(
                marketDataTools,
                mock(RagTools.class),
                mock(SearchTools.class),
                mock(PythonSandboxTools.class),
                mock(FinanceMethodTools.class),
                mock(LoadToolGuideTool.class),
                mock(ListMyDataTool.class),
                new PythonStaticPrecheckService(),
                llmPropertiesWithStaticPrecheck(true),
                cacheService,
                mock(RereadToolHandler.class),
                mock(AgentRunObservabilityService.class),
                new ObjectMapper(),
                new SimpleMeterRegistry(),
                new StressTestProperties()
        );

        // Test with ts_code alias (most common LLM output)
        ToolRouter.ToolInvocationResult result = router.invokeWithMeta(
                "getStockSwIndustryInfo",
                Map.of("ts_code", "000001.SZ")
        );

        assertTrue(result.isSuccess());
        verify(marketDataTools).getStockSwIndustryInfo(eq("000001.SZ"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void invoke_shouldRouteGetStockSwIndustryInfoWithPositionalArg0() throws Exception {
        MarketDataTools marketDataTools = mock(MarketDataTools.class);
        when(marketDataTools.getStockSwIndustryInfo(eq("600519.SH")))
                .thenReturn("{\"ok\":true,\"tool\":\"getStockSwIndustryInfo\",\"data\":{\"ts_code\":\"600519.SH\",\"count\":2,\"items\":[]},\"error\":null}");

        ToolResultCacheService cacheService = mock(ToolResultCacheService.class);
        when(cacheService.executeWithCache(anyString(), any(), anyString(), any())).thenAnswer(inv -> {
            Supplier<ToolResultCacheService.ToolExecutionOutcome> supplier = inv.getArgument(3);
            ToolResultCacheService.ToolExecutionOutcome outcome = supplier.get();
            return ToolResultCacheService.CachedToolCallResult.builder()
                    .result(outcome.getResult())
                    .durationMs(outcome.getDurationMs())
                    .success(outcome.isSuccess())
                    .build();
        });

        ToolRouter router = new ToolRouter(
                marketDataTools,
                mock(RagTools.class),
                mock(SearchTools.class),
                mock(PythonSandboxTools.class),
                mock(FinanceMethodTools.class),
                mock(LoadToolGuideTool.class),
                mock(ListMyDataTool.class),
                new PythonStaticPrecheckService(),
                llmPropertiesWithStaticPrecheck(true),
                cacheService,
                mock(RereadToolHandler.class),
                mock(AgentRunObservabilityService.class),
                new ObjectMapper(),
                new SimpleMeterRegistry(),
                new StressTestProperties()
        );

        // Test with arg0 positional parameter (legacy prompt style)
        ToolRouter.ToolInvocationResult result = router.invokeWithMeta(
                "getStockSwIndustryInfo",
                Map.of("arg0", "600519.SH")
        );

        assertTrue(result.isSuccess());
        verify(marketDataTools).getStockSwIndustryInfo(eq("600519.SH"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void supportedTools_shouldIncludeGetStockSwIndustryInfo() {
        ToolRouter router = new ToolRouter(
                mock(MarketDataTools.class),
                mock(RagTools.class),
                mock(SearchTools.class),
                mock(PythonSandboxTools.class),
                mock(FinanceMethodTools.class),
                mock(LoadToolGuideTool.class),
                mock(ListMyDataTool.class),
                new PythonStaticPrecheckService(),
                llmPropertiesWithStaticPrecheck(true),
                mock(ToolResultCacheService.class),
                mock(RereadToolHandler.class),
                mock(AgentRunObservabilityService.class),
                new ObjectMapper(),
                new SimpleMeterRegistry(),
                new StressTestProperties()
        );
        assertTrue(router.supportedTools().contains("getStockSwIndustryInfo"),
                "supportedTools must include getStockSwIndustryInfo");
        assertEquals(1, router.supportedTools().stream().filter("getStockSwIndustryInfo"::equals).count(),
                "getStockSwIndustryInfo should appear exactly once (no duplication)");
    }

    private AgentLlmProperties llmPropertiesWithStaticPrecheck(boolean enabled) {
        AgentLlmProperties properties = new AgentLlmProperties();
        AgentLlmProperties.Runtime runtime = new AgentLlmProperties.Runtime();
        AgentLlmProperties.Execution execution = new AgentLlmProperties.Execution();
        execution.setStaticPrecheckEnabled(enabled);
        runtime.setExecution(execution);
        properties.setRuntime(runtime);
        return properties;
    }
}
