package world.willfrog.agent.tools.router;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.config.StressTestProperties;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.service.AgentObservabilityService;
import world.willfrog.agent.tools.compaction.RereadToolHandler;
import world.willfrog.agent.tools.dataset.ListMyDataTool;
import world.willfrog.agent.tools.docs.LoadToolGuideTool;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ToolRouter 对 resolveFinanceMethods 的路由测试。
 */
class ToolRouterFinanceMethodRouteTest {

    @SuppressWarnings("unchecked")
    @Test
    void invoke_shouldRouteResolveFinanceMethods() throws Exception {
        FinanceMethodTools financeMethodTools = mock(FinanceMethodTools.class);
        when(financeMethodTools.resolveFinanceMethods(eq("这几年涨了多少"), eq("已有收盘价")))
                .thenReturn("{\"ok\":true,\"tool\":\"resolveFinanceMethods\",\"data\":{},\"error\":null}");

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
                mock(MarketDataTools.class),
                mock(RagTools.class),
                mock(SearchTools.class),
                mock(PythonSandboxTools.class),
                financeMethodTools,
                mock(LoadToolGuideTool.class),
                mock(ListMyDataTool.class),
                new PythonStaticPrecheckService(),
                llmPropertiesWithStaticPrecheck(true),
                cacheService,
                mock(RereadToolHandler.class),
                mock(AgentObservabilityService.class),
                new ObjectMapper(),
                new SimpleMeterRegistry(),
                new StressTestProperties()
        );

        ToolRouter.ToolInvocationResult result = router.invokeWithMeta(
                "resolveFinanceMethods",
                Map.of("query", "这几年涨了多少", "context", "已有收盘价")
        );

        assertTrue(result.isSuccess());
        verify(financeMethodTools).resolveFinanceMethods(eq("这几年涨了多少"), eq("已有收盘价"));
    }

    @Test
    void supportedTools_shouldIncludeResolveFinanceMethods() {
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
                mock(AgentObservabilityService.class),
                new ObjectMapper(),
                new SimpleMeterRegistry(),
                new StressTestProperties()
        );
        assertTrue(router.supportedTools().contains("resolveFinanceMethods"));
        assertEquals(1, router.supportedTools().stream().filter("resolveFinanceMethods"::equals).count());
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
