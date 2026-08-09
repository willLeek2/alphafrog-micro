package world.willfrog.agent.tools.router;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.config.StressTestProperties;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.service.AgentObservabilityService;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;
import world.willfrog.agent.tools.compaction.RereadToolHandler;
import world.willfrog.agent.tools.dataset.ListMyDataTool;
import world.willfrog.agent.tools.docs.LoadToolGuideTool;
import world.willfrog.agent.tools.finance.FinanceMethodTools;
import world.willfrog.agent.tools.market.MarketDataTools;
import world.willfrog.agent.tools.python.PythonSandboxTools;
import world.willfrog.agent.tools.rag.RagTools;
import world.willfrog.agent.tools.search.SearchTools;

import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * D07 工具路由限流拒绝契约测试。
 *
 * <p>验证 {@link ToolRouter#invokeWithMeta} 在权重限流失败时的行为：</p>
 * <ul>
 *   <li>返回带 {@code throttleRejected=true} 的标准错误结果，error.code 为 TOOL_WEIGHT_LIMIT_EXCEEDED；</li>
 *   <li>不向 observability 写入 tool trace（不抬高 toolCalls / 不消耗预算）；</li>
 *   <li>只增加 {@code tool.call.throttle.rejected} counter，不记录 {@code tool.call} Timer；</li>
 *   <li>正常调用走成功路径；</li>
 *   <li>checkParallelLimits 元工具仍被 observability 豁免。</li>
 * </ul>
 */
class ToolRouterThrottleRejectionContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AgentObservabilityService observabilityService;
    private SimpleMeterRegistry meterRegistry;
    private ToolResultCacheService cacheService;

    @BeforeEach
    void setUp() {
        AgentContext.setRunId("run-d07-1");
        AgentContext.setUserId("user-d07-1");
        observabilityService = mock(AgentObservabilityService.class);
        meterRegistry = new SimpleMeterRegistry();
        cacheService = mock(ToolResultCacheService.class);
    }

    @AfterEach
    void tearDown() {
        AgentContext.clear();
    }

    @Test
    void weightRejection_returnsThrottleRejectedWithoutObservabilityAndTimer() throws Exception {
        ToolWeightedLimitService limitService = mock(ToolWeightedLimitService.class);
        when(limitService.tryAcquire(eq("getStockInfo"), anyMap())).thenReturn(Optional.empty());
        when(limitService.previewEffectiveWeight(eq("getStockInfo"), anyMap())).thenReturn(6);

        ToolRouter router = createRouter(limitService);

        ToolRouter.ToolInvocationResult result = router.invokeWithMeta("getStockInfo", Map.of("tsCode", "000001.SZ"));

        // 结果语义
        assertFalse(result.isSuccess());
        assertTrue(result.isThrottleRejected());
        JsonNode root = objectMapper.readTree(result.getOutput());
        assertEquals("TOOL_WEIGHT_LIMIT_EXCEEDED", root.path("error").path("code").asText());
        assertEquals(6, root.path("error").path("details").path("effectiveWeight").asInt());

        // 未写入 observability
        verify(observabilityService, never()).recordToolCall(
                anyString(), anyString(), anyString(), anyMap(), anyString(), anyLong(), anyBoolean(),
                anyBoolean(), anyBoolean(), anyString(), anyString(), anyLong(), anyLong(), anyString()
        );

        // 拒绝计数器存在且为 1
        Counter counter = meterRegistry.find("tool.call.throttle.rejected")
                .tag("toolName", "getStockInfo")
                .tag("layer", "weight_limit")
                .counter();
        assertNotNull(counter, "应注册 tool.call.throttle.rejected counter");
        assertEquals(1.0, counter.count(), 0.0001, "拒绝计数器应只增加一次");

        // 该工具没有 tool.call Timer 记录
        Timer timer = meterRegistry.find("tool.call")
                .tag("toolName", "getStockInfo")
                .timer();
        assertNull(timer, "限流拒绝不应注册/记录 tool.call Timer");
    }

    @Test
    void normalSuccess_recordsObservabilityAndTimerAndNoThrottleCounter() throws Exception {
        ToolWeightedLimitService limitService = mock(ToolWeightedLimitService.class);
        when(limitService.tryAcquire(eq("getStockInfo"), anyMap()))
                .thenReturn(Optional.of(ToolWeightedLimitService.WeightLease.noop()));

        String okJson = "{\"ok\":true,\"tool\":\"getStockInfo\",\"data\":{},\"error\":null}";
        when(cacheService.executeWithCache(eq("getStockInfo"), anyMap(), anyString(), any(Supplier.class)))
                .thenAnswer(inv -> {
                    Supplier<ToolResultCacheService.ToolExecutionOutcome> supplier = inv.getArgument(3);
                    ToolResultCacheService.ToolExecutionOutcome outcome = supplier.get();
                    return ToolResultCacheService.CachedToolCallResult.builder()
                            .result(outcome.getResult())
                            .observabilityResult(outcome.getResult())
                            .durationMs(outcome.getDurationMs())
                            .success(outcome.isSuccess())
                            .build();
                });

        ToolRouter router = createRouter(limitService);
        ToolRouter.ToolInvocationResult result = router.invokeWithMeta("getStockInfo", Map.of("tsCode", "000001.SZ"));

        assertTrue(result.isSuccess());
        assertFalse(result.isThrottleRejected());
        assertEquals(okJson, result.getOutput());

        // observability 被记录一次
        ArgumentCaptor<String> toolNameCaptor = ArgumentCaptor.forClass(String.class);
        verify(observabilityService).recordToolCall(
                eq("run-d07-1"), any(), toolNameCaptor.capture(), anyMap(), anyString(),
                anyLong(), eq(true), anyBoolean(), anyBoolean(), anyString(), anyString(), anyLong(), anyLong(), any()
        );
        assertEquals("getStockInfo", toolNameCaptor.getValue());

        // tool.call Timer 已记录
        Timer timer = meterRegistry.find("tool.call")
                .tag("toolName", "getStockInfo")
                .timer();
        assertNotNull(timer, "成功调用应注册 tool.call Timer");
        assertEquals(1, timer.count(), "Timer 应记录一次");

        // 拒绝计数器不存在
        Counter counter = meterRegistry.find("tool.call.throttle.rejected")
                .tag("toolName", "getStockInfo")
                .tag("layer", "weight_limit")
                .counter();
        assertNull(counter, "成功调用不应产生限流拒绝计数器");
    }

    @Test
    void metaTool_checkParallelLimits_stillSkippedFromObservability() throws Exception {
        ToolWeightedLimitService limitService = mock(ToolWeightedLimitService.class);
        when(limitService.tryAcquire(eq("checkParallelLimits"), anyMap()))
                .thenReturn(Optional.of(ToolWeightedLimitService.WeightLease.noop()));

        String okJson = "{\"ok\":true,\"tool\":\"checkParallelLimits\",\"data\":{},\"error\":null}";
        when(cacheService.executeWithCache(eq("checkParallelLimits"), anyMap(), anyString(), any(Supplier.class)))
                .thenAnswer(inv -> {
                    Supplier<ToolResultCacheService.ToolExecutionOutcome> supplier = inv.getArgument(3);
                    ToolResultCacheService.ToolExecutionOutcome outcome = supplier.get();
                    return ToolResultCacheService.CachedToolCallResult.builder()
                            .result(outcome.getResult())
                            .observabilityResult(outcome.getResult())
                            .durationMs(outcome.getDurationMs())
                            .success(outcome.isSuccess())
                            .build();
                });

        ToolRouter router = createRouter(limitService);
        ToolRouter.ToolInvocationResult result = router.invokeWithMeta("checkParallelLimits", Map.of());

        assertTrue(result.isSuccess());
        assertEquals(okJson, result.getOutput());

        // checkParallelLimits 元工具不计入 observability
        verify(observabilityService, never()).recordToolCall(
                anyString(), anyString(), anyString(), anyMap(), anyString(), anyLong(), anyBoolean(),
                anyBoolean(), anyBoolean(), anyString(), anyString(), anyLong(), anyLong(), anyString()
        );
    }

    private ToolRouter createRouter(ToolWeightedLimitService limitService) {
        MarketDataTools marketDataTools = mock(MarketDataTools.class);
        when(marketDataTools.checkParallelLimits()).thenReturn(
                "{\"ok\":true,\"tool\":\"checkParallelLimits\",\"data\":{},\"error\":null}");
        when(marketDataTools.getStockInfo(anyString())).thenReturn(
                "{\"ok\":true,\"tool\":\"getStockInfo\",\"data\":{},\"error\":null}");

        ToolRouter router = new ToolRouter(
                marketDataTools,
                mock(RagTools.class),
                mock(SearchTools.class),
                mock(PythonSandboxTools.class),
                mock(FinanceMethodTools.class),
                mock(LoadToolGuideTool.class),
                mock(ListMyDataTool.class),
                new PythonStaticPrecheckService(),
                llmPropertiesWithAllEnabled(),
                cacheService,
                mock(RereadToolHandler.class),
                observabilityService,
                objectMapper,
                meterRegistry,
                new StressTestProperties()
        );
        // 非 final 依赖通过反射注入
        org.springframework.test.util.ReflectionTestUtils.setField(router, "toolWeightedLimitService", limitService);
        org.springframework.test.util.ReflectionTestUtils.setField(router, "budgetService", null);
        org.springframework.test.util.ReflectionTestUtils.setField(router, "localConfigLoader", null);
        return router;
    }

    private AgentLlmProperties llmPropertiesWithAllEnabled() {
        AgentLlmProperties properties = new AgentLlmProperties();
        AgentLlmProperties.Runtime runtime = new AgentLlmProperties.Runtime();
        AgentLlmProperties.Execution execution = new AgentLlmProperties.Execution();
        execution.setStaticPrecheckEnabled(false);
        execution.setAdjFactorEnabled(false);
        runtime.setExecution(execution);
        properties.setRuntime(runtime);
        return properties;
    }
}
