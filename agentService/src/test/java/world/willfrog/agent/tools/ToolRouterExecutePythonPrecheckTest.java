package world.willfrog.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.config.StressTestProperties;
import world.willfrog.agent.platform.service.AgentObservabilityService;
import world.willfrog.agent.tools.docs.LoadToolGuideTool;
import world.willfrog.agent.tools.dataset.ListMyDataTool;
import world.willfrog.agent.tools.market.MarketDataTools;
import world.willfrog.agent.tools.python.PythonSandboxTools;
import world.willfrog.agent.tools.rag.RagTools;
import world.willfrog.agent.tools.router.PythonStaticPrecheckService;
import world.willfrog.agent.tools.router.ToolRouter;
import world.willfrog.agent.tools.router.ToolResultCacheService;
import world.willfrog.agent.tools.search.SearchTools;

import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolRouterExecutePythonPrecheckTest {

    @SuppressWarnings("unchecked")
    @Test
    void invoke_shouldRejectExecutePythonBeforeSandboxWhenDatasetIdsMissing() throws Exception {
        // 260623-harness-optimization-02: dataset_ids / manifest_ids 都缺失时改用统一的 MISSING_IDS 错误码。
        // 验证 5 形参 executePython overload 不被调用。
        PythonSandboxTools pythonSandboxTools = mock(PythonSandboxTools.class);
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
                pythonSandboxTools,
                mock(LoadToolGuideTool.class),
                mock(ListMyDataTool.class),
                new PythonStaticPrecheckService(),
                llmPropertiesWithStaticPrecheck(true),
                cacheService,
                mock(world.willfrog.agent.tools.compaction.RereadToolHandler.class),
                mock(AgentObservabilityService.class),
                new ObjectMapper(),
                new SimpleMeterRegistry(),
                new StressTestProperties()
        );

        ToolRouter.ToolInvocationResult result = router.invokeWithMeta(
                "executePython",
                Map.of("code", "print(1)")
        );

        assertFalse(result.isSuccess());
        JsonNode json = new ObjectMapper().readTree(result.getOutput());
        assertEquals("MISSING_IDS", json.path("error").path("code").asText());
        assertTrue(json.path("error").path("details").path("pre_validation_failed").asBoolean());
        // 5 形参 overload 不被调用
        verify(pythonSandboxTools, never()).executePython(anyString(), anyString(), anyString(), anyString(), any());
        // 4 形参 backward-compat shim 也不被调用
        verify(pythonSandboxTools, never()).executePython(anyString(), anyString(), anyString(), any());
    }

    @SuppressWarnings("unchecked")
    @Test
    void invoke_shouldCallSandboxWhenPrecheckPasses() {
        // 260623-harness-optimization-02: stub 5 形参 overload，verify 5 形参被调。
        PythonSandboxTools pythonSandboxTools = mock(PythonSandboxTools.class);
        when(pythonSandboxTools.executePython(eq("print(1)"), eq("ds-1"), anyString(), anyString(), any()))
                .thenReturn("{\"ok\":true,\"tool\":\"executePython\",\"data\":{},\"error\":null}");
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
                pythonSandboxTools,
                mock(LoadToolGuideTool.class),
                mock(ListMyDataTool.class),
                new PythonStaticPrecheckService(),
                llmPropertiesWithStaticPrecheck(true),
                cacheService,
                mock(world.willfrog.agent.tools.compaction.RereadToolHandler.class),
                mock(AgentObservabilityService.class),
                new ObjectMapper(),
                new SimpleMeterRegistry(),
                new StressTestProperties()
        );

        ToolRouter.ToolInvocationResult result = router.invokeWithMeta(
                "executePython",
                Map.of("code", "print(1)", "dataset_ids", "ds-1")
        );

        assertTrue(result.isSuccess());
        // 5 形参 overload 被调：code="print(1)", datasetIds="ds-1", manifestIds="", libraries=any, timeout=any
        verify(pythonSandboxTools).executePython(eq("print(1)"), eq("ds-1"), eq(""), anyString(), any());
    }

    @SuppressWarnings("unchecked")
    @Test
    void invoke_shouldRejectWhenBothIdsMissing() {
        // 新增测试 1: code 存在但 dataset_ids / manifest_ids 都缺失 → MISSING_IDS
        PythonSandboxTools pythonSandboxTools = mock(PythonSandboxTools.class);
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
                pythonSandboxTools,
                mock(LoadToolGuideTool.class),
                mock(ListMyDataTool.class),
                new PythonStaticPrecheckService(),
                llmPropertiesWithStaticPrecheck(true),
                cacheService,
                mock(world.willfrog.agent.tools.compaction.RereadToolHandler.class),
                mock(AgentObservabilityService.class),
                new ObjectMapper(),
                new SimpleMeterRegistry(),
                new StressTestProperties()
        );

        ToolRouter.ToolInvocationResult result = router.invokeWithMeta(
                "executePython",
                Map.of("code", "print(1)")
        );

        assertFalse(result.isSuccess());
        // executePython 5 形参 / 4 形参都不应被调用
        verify(pythonSandboxTools, never()).executePython(anyString(), anyString(), anyString(), anyString(), any());
        verify(pythonSandboxTools, never()).executePython(anyString(), anyString(), anyString(), any());
    }

    @SuppressWarnings("unchecked")
    @Test
    void invoke_shouldCallSandboxWhenOnlyManifestProvided() throws Exception {
        // 新增测试 2: 只传 manifest_ids → 5 形参第二个参数（datasetIds）= ""
        PythonSandboxTools pythonSandboxTools = mock(PythonSandboxTools.class);
        when(pythonSandboxTools.executePython(eq("print(1)"), eq(""), eq("1"), anyString(), any()))
                .thenReturn("{\"ok\":true,\"tool\":\"executePython\",\"data\":{},\"error\":null}");
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
                pythonSandboxTools,
                mock(LoadToolGuideTool.class),
                mock(ListMyDataTool.class),
                new PythonStaticPrecheckService(),
                llmPropertiesWithStaticPrecheck(true),
                cacheService,
                mock(world.willfrog.agent.tools.compaction.RereadToolHandler.class),
                mock(AgentObservabilityService.class),
                new ObjectMapper(),
                new SimpleMeterRegistry(),
                new StressTestProperties()
        );

        ToolRouter.ToolInvocationResult result = router.invokeWithMeta(
                "executePython",
                Map.of("code", "print(1)", "manifest_ids", "1")
        );

        assertTrue(result.isSuccess());
        verify(pythonSandboxTools).executePython(eq("print(1)"), eq(""), eq("1"), anyString(), any());
    }

    @SuppressWarnings("unchecked")
    @Test
    void invoke_shouldCallSandboxWhenBothProvided() throws Exception {
        // 新增测试 3: dataset_ids + manifest_ids 同时传 → 5 形参两个空间都填
        PythonSandboxTools pythonSandboxTools = mock(PythonSandboxTools.class);
        when(pythonSandboxTools.executePython(eq("print(1)"), eq("1"), eq("2"), anyString(), any()))
                .thenReturn("{\"ok\":true,\"tool\":\"executePython\",\"data\":{},\"error\":null}");
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
                pythonSandboxTools,
                mock(LoadToolGuideTool.class),
                mock(ListMyDataTool.class),
                new PythonStaticPrecheckService(),
                llmPropertiesWithStaticPrecheck(true),
                cacheService,
                mock(world.willfrog.agent.tools.compaction.RereadToolHandler.class),
                mock(AgentObservabilityService.class),
                new ObjectMapper(),
                new SimpleMeterRegistry(),
                new StressTestProperties()
        );

        ToolRouter.ToolInvocationResult result = router.invokeWithMeta(
                "executePython",
                Map.of("code", "print(1)", "dataset_ids", "1", "manifest_ids", "2")
        );

        assertTrue(result.isSuccess());
        verify(pythonSandboxTools).executePython(eq("print(1)"), eq("1"), eq("2"), anyString(), any());
    }

    @SuppressWarnings("unchecked")
    @Test
    void invoke_shouldCollectManifestIdsAliasKeys() throws Exception {
        // 新增测试 4: camelCase manifestIds + manifest_refs 同时传 → 三个合并去重为 "1,2,3"
        PythonSandboxTools pythonSandboxTools = mock(PythonSandboxTools.class);
        when(pythonSandboxTools.executePython(eq("print(1)"), eq(""), eq("1,2,3"), anyString(), any()))
                .thenReturn("{\"ok\":true,\"tool\":\"executePython\",\"data\":{},\"error\":null}");
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
                pythonSandboxTools,
                mock(LoadToolGuideTool.class),
                mock(ListMyDataTool.class),
                new PythonStaticPrecheckService(),
                llmPropertiesWithStaticPrecheck(true),
                cacheService,
                mock(world.willfrog.agent.tools.compaction.RereadToolHandler.class),
                mock(AgentObservabilityService.class),
                new ObjectMapper(),
                new SimpleMeterRegistry(),
                new StressTestProperties()
        );

        ToolRouter.ToolInvocationResult result = router.invokeWithMeta(
                "executePython",
                Map.of("code", "print(1)", "manifestIds", "1,2", "manifest_refs", "3")
        );

        assertTrue(result.isSuccess());
        // 合并去重顺序：manifestIds(1,2) → manifest_refs(3) → "1,2,3"
        verify(pythonSandboxTools).executePython(eq("print(1)"), eq(""), eq("1,2,3"), anyString(), any());
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
