package world.willfrog.agent.tools.router;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.config.StressTestProperties;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.service.AgentObservabilityService;
import world.willfrog.agent.tools.compaction.RereadToolHandler;
import world.willfrog.agent.tools.docs.LoadToolGuideTool;
import world.willfrog.agent.tools.dataset.ListMyDataTool;
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

/**
 * 260623-harness-optimization-02 MF-new-1（Cindy round 2 review 拍板）：ToolRouter listMyData
 * 路由应到达 {@link ListMyDataTool} 的统一 8 形参公开方法，并支持 raw file content grep。
 *
 * <p>本测试不重复覆盖 ListMyDataTool 内部语义（ListMyDataToolTest 已覆盖），只验证
 * ToolRouter 路由层：参数别名 / 位置参数 arg0..arg7 → 8 形参 listMyData 方法对应形参。
 */
class ToolRouterListMyDataRouteTest {

    @SuppressWarnings("unchecked")
    @Test
    void invoke_shouldRouteListMyDataWithAllEightArgs() throws Exception {
        ListMyDataTool listMyDataTool = mock(ListMyDataTool.class);
        when(listMyDataTool.listMyData(
                eq("dataset"),                                       // query_type
                eq("600000.SH"),                                     // from_ts_code
                eq("hello"),                                         // grep
                eq(0),                                               // file_offset
                eq(10),                                              // file_limit
                eq(0),                                               // offset
                eq(50),                                              // limit
                eq("ds-a#ds-b")                                      // related_dataset_ids
        )).thenReturn("{\"ok\":true,\"tool\":\"listMyData\",\"data\":{},\"error\":null}");

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
                mock(LoadToolGuideTool.class),
                listMyDataTool,
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
                "listMyData",
                Map.of(
                        "query_type", "dataset",
                        "from_ts_code", "600000.SH",
                        "grep", "hello",
                        "file_offset", "0",
                        "file_limit", "10",
                        "offset", "0",
                        "limit", "50",
                        "related_dataset_ids", "ds-a#ds-b"
                )
        );

        assertTrue(result.isSuccess());
        // 8 形参 listMyData 方法被调用，参数 1:1 映射
        verify(listMyDataTool).listMyData(
                eq("dataset"),
                eq("600000.SH"),
                eq("hello"),
                eq(0),
                eq(10),
                eq(0),
                eq(50),
                eq("ds-a#ds-b")
        );
    }

    @SuppressWarnings("unchecked")
    @Test
    void invoke_shouldRouteListMyDataWithPositionalArgs() throws Exception {
        // 旧 prompt 风格：只用 arg0..arg7 位置参数
        ListMyDataTool listMyDataTool = mock(ListMyDataTool.class);
        when(listMyDataTool.listMyData(
                eq("dataset"),
                eq("arg1-val"),
                eq("arg2-val"),
                eq(0),
                eq(10),
                isNull(),
                isNull(),
                eq("arg7-val")
        )).thenReturn("{\"ok\":true,\"tool\":\"listMyData\",\"data\":{},\"error\":null}");

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
                mock(LoadToolGuideTool.class),
                listMyDataTool,
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
                "listMyData",
                Map.of(
                        "arg0", "dataset",
                        "arg1", "arg1-val",
                        "arg2", "arg2-val",
                        "arg3", "0",
                        "arg4", "10",
                        // arg5 (offset) 和 arg6 (limit) 故意不传 — 验证走 null
                        "arg7", "arg7-val"
                )
        );

        assertTrue(result.isSuccess());
        // 8 形参 listMyData 方法被调用
        verify(listMyDataTool).listMyData(
                eq("dataset"),
                eq("arg1-val"),
                eq("arg2-val"),
                eq(0),
                eq(10),
                isNull(),
                isNull(),
                eq("arg7-val")
        );
    }

    @SuppressWarnings("unchecked")
    @Test
    void invoke_shouldRouteListMyDataWithRawGrepSemantics() throws Exception {
        // MF-new-1 关键测试：ToolRouter 路由到统一签名后，grep 参数会传给 listMyDataTool
        // 的 8 形参方法，由 ListMyDataTool 内部决定走 raw file content grep。
        // 注：ToolRouter 的 str() 把 null 转成 ""，所以 from_ts_code 和 related_dataset_ids
        // 在空输入时是 ""，不是 null。整数参数走 toIntOrNull 保留 null。
        ListMyDataTool listMyDataTool = mock(ListMyDataTool.class);
        when(listMyDataTool.listMyData(
                eq("dataset"),
                eq(""),
                eq("alpha"),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                eq("")
        )).thenReturn("{\"ok\":true,\"tool\":\"listMyData\",\"data\":{\"matched_count\":2,\"matches\":[]},\"error\":null}");

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
                mock(LoadToolGuideTool.class),
                listMyDataTool,
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
                "listMyData",
                Map.of(
                        "query_type", "dataset",
                        "grep", "alpha"
                )
        );

        assertTrue(result.isSuccess());
        // grep 应原样传给 listMyDataTool（ListMyDataToolTest 单独验证 raw file grep 行为）
        verify(listMyDataTool).listMyData(
                eq("dataset"),
                eq(""),
                eq("alpha"),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                eq("")
        );
    }

    @SuppressWarnings("unchecked")
    @Test
    void supportedTools_shouldIncludeListMyData() {
        // ToolRouter 白名单必须包含 listMyData（Cindy MF-new-1 拍板）
        ToolRouter router = new ToolRouter(
                mock(MarketDataTools.class),
                mock(RagTools.class),
                mock(SearchTools.class),
                mock(PythonSandboxTools.class),
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
        assertTrue(router.supportedTools().contains("listMyData"),
                "supportedTools must include listMyData");
        assertEquals(1, router.supportedTools().stream().filter("listMyData"::equals).count(),
                "listMyData 应只出现一次（no duplication）");
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
