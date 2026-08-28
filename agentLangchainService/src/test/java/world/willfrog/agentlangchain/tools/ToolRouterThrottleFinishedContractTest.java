package world.willfrog.agentlangchain.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.dataanalysis.PythonSandboxDispatchStore;
import world.willfrog.agent.platform.service.AgentRunEventService;
import world.willfrog.agent.tools.router.ToolRouter;
import world.willfrog.agentlangchain.config.LangchainToolConcurrencyThrottle;
import world.willfrog.agentlangchain.orchestration.ToolThrottleResult;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * D07 ToolRouterToolExecutor 限流 FINISHED 事件契约测试。
 *
 * <p>验证 {@link ToolRouterToolExecutor#execute} 在以下三类场景下对
 * {@code TOOL_CALL_FINISHED} payload 的处理：</p>
 * <ul>
 *   <li>LC4j Semaphore 拒绝：payload 带 creditsConsumed=0、rejected_by_throttle=true、throttle_layer=lc4j_semaphore；</li>
 *   <li>下游权重限流拒绝：throttle_layer=weight_limit，recordExecution 不被调用；</li>
 *   <li>正常成功：payload 不含上述限流字段，recordExecution 被调用一次。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ToolRouterThrottleFinishedContractTest {

    @Mock
    private ToolRouter toolRouter;

    @Mock
    private AgentRunEventService eventService;

    @Mock
    private PythonSandboxDispatchStore pythonSandboxDispatchStore;

    @Mock
    private LangchainToolConcurrencyThrottle toolThrottle;

    private ObjectMapper objectMapper;
    private ToolRouterToolExecutor executor;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        executor = new ToolRouterToolExecutor(toolRouter, objectMapper, eventService, toolThrottle, pythonSandboxDispatchStore);
        AgentContext.setRunId("run-123");
        AgentContext.setUserId("user-456");
        LangchainRepeatedToolCallContext.clear();
    }

    @AfterEach
    void tearDown() {
        AgentContext.clear();
        LangchainRepeatedToolCallContext.clear();
    }

    @Test
    void lc4jRejection_executePython_emitsFinishedWithThrottleLayerLc4jSemaphore() {
        String failureReason = "TOOL_THROTTLE_TIMEOUT: tool=executePython waitMs=10 permits=0";
        when(toolThrottle.tryAcquire("executePython"))
                .thenReturn(ToolThrottleResult.timeout("executePython", 10, 0));

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("tc-lc4j")
                .name("executePython")
                .arguments("{\"code\":\"print(1)\"}")
                .build();

        String output = executor.execute(request, null);

        assertEquals(failureReason, output);

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(eventService).appendOnce(
                eq("run-123"), eq("user-456"), eq("TOOL_CALL_FINISHED"),
                eq("run-123:tc-lc4j:logical_terminal"), payloadCaptor.capture());
        Map<String, Object> payload = payloadCaptor.getValue();
        assertEquals(0, payload.get("creditsConsumed"));
        assertEquals(Boolean.TRUE, payload.get("rejected_by_throttle"));
        assertEquals("lc4j_semaphore", payload.get("throttle_layer"));
        assertEquals(false, payload.get("success"));

        verify(toolThrottle, never()).recordExecution(anyString(), anyLong());
        verify(toolThrottle, never()).release(any());
    }

    @Test
    void weightRejection_getStockInfo_emitsFinishedWithThrottleLayerWeightLimit() {
        when(toolThrottle.tryAcquire("getStockInfo"))
                .thenReturn(ToolThrottleResult.acquired("getStockInfo"));

        String errorJson = "{\"ok\":false,\"tool\":\"getStockInfo\",\"data\":{},\"error\":{\"code\":\"TOOL_WEIGHT_LIMIT_EXCEEDED\",\"message\":\"exceeded\"}}";
        when(toolRouter.invokeWithMeta(eq("getStockInfo"), anyMap()))
                .thenReturn(ToolRouter.ToolInvocationResult.builder()
                        .output(errorJson)
                        .success(false)
                        .throttleRejected(true)
                        .build());

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("tc-weight")
                .name("getStockInfo")
                .arguments("{\"symbol\":\"A\"}")
                .build();

        String output = executor.execute(request, null);

        assertEquals(errorJson, output);

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(eventService).append(
                eq("run-123"), eq("user-456"), eq("TOOL_CALL_FINISHED"), payloadCaptor.capture());
        Map<String, Object> payload = payloadCaptor.getValue();
        assertEquals(0, payload.get("creditsConsumed"));
        assertEquals(Boolean.TRUE, payload.get("rejected_by_throttle"));
        assertEquals("weight_limit", payload.get("throttle_layer"));
        assertEquals(false, payload.get("success"));

        verify(toolThrottle, never()).recordExecution(anyString(), anyLong());
        verify(toolThrottle).release(any());
    }

    @Test
    void normalSuccess_getStockInfo_finishedPayloadHasNoThrottleKeys() {
        when(toolThrottle.tryAcquire("getStockInfo"))
                .thenReturn(ToolThrottleResult.acquired("getStockInfo"));

        when(toolRouter.invokeWithMeta(eq("getStockInfo"), anyMap()))
                .thenReturn(ToolRouter.ToolInvocationResult.builder()
                        .output("{\"ok\":true,\"price\":10}")
                        .success(true)
                        .durationMs(100L)
                        .build());

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("tc-normal-stock")
                .name("getStockInfo")
                .arguments("{\"symbol\":\"B\"}")
                .build();

        String output = executor.execute(request, null);
        assertEquals("{\"ok\":true,\"price\":10}", output);

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(eventService).append(
                eq("run-123"), eq("user-456"), eq("TOOL_CALL_FINISHED"), payloadCaptor.capture());
        Map<String, Object> payload = payloadCaptor.getValue();
        assertFalse(payload.containsKey("creditsConsumed"));
        assertFalse(payload.containsKey("rejected_by_throttle"));
        assertFalse(payload.containsKey("throttle_layer"));
        assertEquals(Boolean.TRUE, payload.get("success"));

        verify(toolThrottle).recordExecution(eq("getStockInfo"), anyLong());
        verify(toolThrottle).release(any());
    }

    @Test
    void normalSuccess_executePython_usesAppendOnce_finishedPayloadHasNoThrottleKeys() {
        when(toolThrottle.tryAcquire("executePython"))
                .thenReturn(ToolThrottleResult.acquired("executePython"));

        when(toolRouter.invokeWithMeta(eq("executePython"), anyMap()))
                .thenReturn(ToolRouter.ToolInvocationResult.builder()
                        .output("{\"ok\":true,\"tool\":\"executePython\",\"data\":{}}")
                        .success(true)
                        .durationMs(80L)
                        .build());
        when(pythonSandboxDispatchStore.clearSynchronouslyCompleted(anyString(), anyString()))
                .thenReturn(false);

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("tc-normal-py")
                .name("executePython")
                .arguments("{\"code\":\"print(2)\"}")
                .build();

        String output = executor.execute(request, null);
        assertEquals("{\"ok\":true,\"tool\":\"executePython\",\"data\":{}}", output);

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(eventService).appendOnce(
                eq("run-123"), eq("user-456"), eq("TOOL_CALL_FINISHED"),
                eq("run-123:tc-normal-py:logical_terminal"), payloadCaptor.capture());
        Map<String, Object> payload = payloadCaptor.getValue();
        assertFalse(payload.containsKey("creditsConsumed"));
        assertFalse(payload.containsKey("rejected_by_throttle"));
        assertFalse(payload.containsKey("throttle_layer"));
        assertEquals(Boolean.TRUE, payload.get("success"));

        verify(toolThrottle).recordExecution(eq("executePython"), anyLong());
        verify(toolThrottle).release(any());
    }
}
