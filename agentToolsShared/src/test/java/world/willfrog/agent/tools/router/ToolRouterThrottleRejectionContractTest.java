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
import world.willfrog.agent.platform.service.AgentRunObservabilityService;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;
import world.willfrog.agent.tools.compaction.RereadToolHandler;
import world.willfrog.agent.tools.dataset.ListMyDataTool;
import world.willfrog.agent.tools.docs.LoadToolGuideTool;
import world.willfrog.agent.tools.finance.FinanceMethodTools;
import world.willfrog.agent.tools.market.MarketDataTools;
import world.willfrog.agent.tools.dataanalysis.SqlQueryTools;
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
 *   <li>checkParallelLimits 元工具仍被 observability 豁免；</li>
 *   <li>Router→cache scope 接线：user+run→{@code user:<id>}、仅 run→{@code run:<id>}、
 *       皆无→blank（由缓存层 fail-closed，Router 不得改写为 global）。</li>
 * </ul>
 */
class ToolRouterThrottleRejectionContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AgentRunObservabilityService observabilityService;
    private SimpleMeterRegistry meterRegistry;
    private ToolResultCacheService cacheService;

    @BeforeEach
    void setUp() {
        AgentContext.setRunId("run-d07-1");
        AgentContext.setUserId("user-d07-1");
        observabilityService = mock(AgentRunObservabilityService.class);
        meterRegistry = new SimpleMeterRegistry();
        cacheService = mock(ToolResultCacheService.class);
    }

    @AfterEach
    void tearDown() {
        AgentContext.clear();
    }




    // ── D07 Router→cache scope 接线反测：resolveScope 回归为 global 时此处应失败 ──




    private ToolWeightedLimitService passingLimitService(String toolName) {
        ToolWeightedLimitService limitService = mock(ToolWeightedLimitService.class);
        when(limitService.tryAcquire(eq(toolName), anyMap()))
                .thenReturn(Optional.of(ToolWeightedLimitService.WeightLease.noop()));
        return limitService;
    }

    private void stubCachePassthrough(String toolName) {
        when(cacheService.executeWithCache(eq(toolName), anyMap(), anyString(), any(Supplier.class)))
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
    }

    private String captureScopeFor(String toolName) {
        ArgumentCaptor<String> scopeCaptor = ArgumentCaptor.forClass(String.class);
        verify(cacheService).executeWithCache(eq(toolName), anyMap(), scopeCaptor.capture(), any(Supplier.class));
        return scopeCaptor.getValue();
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
                mock(SqlQueryTools.class),
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
