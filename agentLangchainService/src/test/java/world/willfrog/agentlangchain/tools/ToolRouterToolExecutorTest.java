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
import world.willfrog.agent.platform.dataanalysis.ExternalToolJobPendingException;
import world.willfrog.agent.platform.dataanalysis.PythonSandboxDispatchStore;
import world.willfrog.agent.platform.service.AgentEventService;
import world.willfrog.agent.tools.router.ToolRouter;
import world.willfrog.agentlangchain.config.LangchainToolConcurrencyThrottle;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link ToolRouterToolExecutor} 的单元测试：验证 tool call 事件发射行为。
 */
@ExtendWith(MockitoExtension.class)
class ToolRouterToolExecutorTest {

    @Mock
    private ToolRouter toolRouter;

    @Mock
    private AgentEventService eventService;

    @Mock
    private PythonSandboxDispatchStore pythonSandboxDispatchStore;

    private ObjectMapper objectMapper;
    private ToolRouterToolExecutor executor;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        executor = new ToolRouterToolExecutor(toolRouter, objectMapper, eventService,
                new LangchainToolConcurrencyThrottle(false, 20, 60),
                pythonSandboxDispatchStore);
        // 设置 AgentContext
        AgentContext.setRunId("run-123");
        AgentContext.setUserId("user-456");
    }

    @AfterEach
    void tearDown() {
        AgentContext.clear();
    }

    @Test
    void execute_successfulToolCall_emitsStartedAndFinishedWithAttribution() {
        AgentContext.setPhase("linear_execution");
        AgentContext.setStage("todo_execution");
        AgentContext.setTodoContext("todo-1", 1);
        AgentContext.setWorkflow("linear");
        // Given
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("tool-call-1")
                .name("getStockInfo")
                .arguments("{\"symbol\":\"000001.SZ\"}")
                .build();

        ToolRouter.ToolInvocationResult result = ToolRouter.ToolInvocationResult.builder()
                .output("{\"price\":10.5}")
                .success(true)
                .durationMs(100L)
                .build();

        when(toolRouter.invokeWithMeta("getStockInfo", Map.of("symbol", "000001.SZ")))
                .thenReturn(result);

        // When
        String output = executor.execute(request, null);

        // Then
        assertEquals("{\"price\":10.5}", output);

        // Verify TOOL_CALL_STARTED
        ArgumentCaptor<Map<String, Object>> startedPayloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(eventService).append(eq("run-123"), eq("user-456"), eq("TOOL_CALL_STARTED"), startedPayloadCaptor.capture());
        Map<String, Object> startedPayload = startedPayloadCaptor.getValue();
        assertEquals("tool-call-1", startedPayload.get("tool_call_id"));
        assertEquals("getStockInfo", startedPayload.get("tool_name"));
        assertEquals(Map.of("symbol", "000001.SZ"), startedPayload.get("arguments"));
        assertEquals("linear_execution", startedPayload.get("phase"));
        assertEquals("todo-1", startedPayload.get("todo_id"));
        assertEquals(1, startedPayload.get("todo_sequence"));
        assertEquals("linear", startedPayload.get("workflow"));

        // Verify TOOL_CALL_FINISHED
        ArgumentCaptor<Map<String, Object>> finishedPayloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(eventService).append(eq("run-123"), eq("user-456"), eq("TOOL_CALL_FINISHED"), finishedPayloadCaptor.capture());
        Map<String, Object> finishedPayload = finishedPayloadCaptor.getValue();
        assertEquals("tool-call-1", finishedPayload.get("tool_call_id"));
        assertEquals(startedPayload.get("tool_call_id"), finishedPayload.get("tool_call_id"));
        assertEquals("getStockInfo", finishedPayload.get("tool_name"));
        assertEquals(Map.of("symbol", "000001.SZ"), finishedPayload.get("arguments"));
        assertEquals(true, finishedPayload.get("success"));
        assertEquals("{\"price\":10.5}", finishedPayload.get("result_preview"));
        assertNotNull(finishedPayload.get("duration_ms"));
        assertTrue((Long) finishedPayload.get("duration_ms") >= 0);
        assertEquals("todo-1", finishedPayload.get("todo_id"));
        assertEquals("linear", finishedPayload.get("workflow"));
    }

    @Test
    void execute_missingRequestId_generatesSameToolCallIdForStartAndFinish() {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .name("getStockInfo")
                .arguments("{\"symbol\":\"000001.SZ\"}")
                .build();

        ToolRouter.ToolInvocationResult result = ToolRouter.ToolInvocationResult.builder()
                .output("{\"price\":10.5}")
                .success(true)
                .durationMs(100L)
                .build();

        when(toolRouter.invokeWithMeta("getStockInfo", Map.of("symbol", "000001.SZ")))
                .thenReturn(result);

        executor.execute(request, null);

        ArgumentCaptor<Map<String, Object>> startedPayloadCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Map<String, Object>> finishedPayloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(eventService).append(eq("run-123"), eq("user-456"), eq("TOOL_CALL_STARTED"), startedPayloadCaptor.capture());
        verify(eventService).append(eq("run-123"), eq("user-456"), eq("TOOL_CALL_FINISHED"), finishedPayloadCaptor.capture());
        Object startedId = startedPayloadCaptor.getValue().get("tool_call_id");
        Object finishedId = finishedPayloadCaptor.getValue().get("tool_call_id");
        assertNotNull(startedId);
        assertEquals(startedId, finishedId);
    }

    @Test
    void execute_failedToolCall_emitsFinishedWithSuccessFalse() {
        // Given
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .name("getStockInfo")
                .arguments("{\"symbol\":\"INVALID\"}")
                .build();

        when(toolRouter.invokeWithMeta("getStockInfo", Map.of("symbol", "INVALID")))
                .thenThrow(new RuntimeException("API timeout"));

        // When
        String output = executor.execute(request, null);

        // Then
        assertEquals("API timeout", output);

        // Verify TOOL_CALL_STARTED still emitted
        verify(eventService).append(eq("run-123"), eq("user-456"), eq("TOOL_CALL_STARTED"), any());

        // Verify TOOL_CALL_FINISHED with success=false
        ArgumentCaptor<Map<String, Object>> finishedPayloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(eventService).append(eq("run-123"), eq("user-456"), eq("TOOL_CALL_FINISHED"), finishedPayloadCaptor.capture());
        Map<String, Object> finishedPayload = finishedPayloadCaptor.getValue();
        assertEquals("getStockInfo", finishedPayload.get("tool_name"));
        assertEquals(false, finishedPayload.get("success"));
        assertEquals("API timeout", finishedPayload.get("result_preview"));
    }

    @Test
    void execute_pendingToolCall_rethrowsWithoutFinishedEvent() {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("tool-call-pending")
                .name("executePython")
                .arguments("{\"code\":\"print(1)\"}")
                .build();
        ExternalToolJobPendingException pending =
                new ExternalToolJobPendingException("run-123", "tool-call-pending", 1, "external job pending");
        when(toolRouter.invokeWithMeta("executePython", Map.of("code", "print(1)"))).thenThrow(pending);

        ExternalToolJobPendingException thrown = assertThrows(
                ExternalToolJobPendingException.class,
                () -> executor.execute(request, null));

        assertSame(pending, thrown);
        verify(eventService).append(eq("run-123"), eq("user-456"), eq("TOOL_CALL_STARTED"), any());
        verify(eventService, never()).append(eq("run-123"), eq("user-456"), eq("TOOL_CALL_FINISHED"), any());
        verify(eventService, never()).appendOnce(anyString(), anyString(), anyString(), anyString(), any());
        verifyNoInteractions(pythonSandboxDispatchStore);
    }

    @Test
    void execute_synchronousPythonTerminalAppendsOnceBeforeClearingAnchor() {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("call-python-1")
                .name("executePython")
                .arguments("{\"dataset_ids\":\"1\",\"code\":\"print(1)\"}")
                .build();
        when(toolRouter.invokeWithMeta(
                "executePython", Map.of("dataset_ids", "1", "code", "print(1)")))
                .thenReturn(ToolRouter.ToolInvocationResult.builder()
                        .output("{\"ok\":true}").success(true).durationMs(1L).build());
        when(pythonSandboxDispatchStore.clearActive(
                "run-123", "run-123:call-python-1:1")).thenReturn(true);

        executor.execute(request, null);

        var order = inOrder(eventService, pythonSandboxDispatchStore);
        order.verify(eventService).appendOnce(
                eq("run-123"), eq("user-456"), eq("TOOL_CALL_FINISHED"),
                eq("run-123:call-python-1:logical_terminal"), any());
        order.verify(pythonSandboxDispatchStore).clearActive(
                "run-123", "run-123:call-python-1:1");
    }

    @Test
    void execute_missingContext_skipsEventEmission() {
        // Given: clear context
        AgentContext.clear();

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .name("getStockInfo")
                .arguments("{\"symbol\":\"000001.SZ\"}")
                .build();

        ToolRouter.ToolInvocationResult result = ToolRouter.ToolInvocationResult.builder()
                .output("{\"price\":10.5}")
                .success(true)
                .durationMs(100L)
                .build();

        when(toolRouter.invokeWithMeta("getStockInfo", Map.of("symbol", "000001.SZ")))
                .thenReturn(result);

        // When
        String output = executor.execute(request, null);

        // Then: tool still executed
        assertEquals("{\"price\":10.5}", output);

        // But no events emitted
        verifyNoInteractions(eventService);
    }

    @Test
    void execute_largeOutput_resultPreviewTruncated() {
        // Given
        String largeOutput = "x".repeat(2000);

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .name("getStockInfo")
                .arguments("{}")
                .build();

        ToolRouter.ToolInvocationResult result = ToolRouter.ToolInvocationResult.builder()
                .output(largeOutput)
                .success(true)
                .durationMs(100L)
                .build();

        when(toolRouter.invokeWithMeta("getStockInfo", Map.of()))
                .thenReturn(result);

        // When
        executor.execute(request, null);

        // Then
        ArgumentCaptor<Map<String, Object>> finishedPayloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(eventService).append(eq("run-123"), eq("user-456"), eq("TOOL_CALL_FINISHED"), finishedPayloadCaptor.capture());
        Map<String, Object> finishedPayload = finishedPayloadCaptor.getValue();
        String preview = (String) finishedPayload.get("result_preview");
        assertTrue(preview.length() < largeOutput.length());
        assertTrue(preview.endsWith("... (truncated, length=2000)"));
        // 核心边界：预览正文 + 截断后缀总长度不超过 OUTPUT_PREVIEW_MAX_CHARS + 后缀长度
        assertTrue(preview.length() <= 500 + "... (truncated, length=2000)".length(),
                "Preview should not exceed OUTPUT_PREVIEW_MAX_CHARS + suffix");
    }

    @Test
    void execute_pythonCallWithRunLevelIdsUnavailableError_appendsRunLevelRetryHint() {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .name("executePython")
                .arguments("{\"dataset_ids\":\"1\",\"code\":\"print(1)\"}")
                .build();

        String failureOutput = "RUN_LEVEL_IDS_UNAVAILABLE: Agent run-level dataset ids require an active run and AgentRunDatasetRegistry";
        ToolRouter.ToolInvocationResult result = ToolRouter.ToolInvocationResult.builder()
                .output(failureOutput)
                .success(false)
                .durationMs(100L)
                .build();

        when(toolRouter.invokeWithMeta("executePython", Map.of("dataset_ids", "1", "code", "print(1)")))
                .thenReturn(result);

        String output = executor.execute(request, null);

        assertTrue(output.contains(failureOutput));
        assertTrue(output.contains("_retry_hint_"));
        assertTrue(output.contains("run-level"));
        assertTrue(output.contains("listMyData"));
        assertTrue(output.contains("dataset_ids"));
        assertTrue(output.contains("manifest_ids"));
        assertTrue(output.contains("RUN_LEVEL_IDS_UNAVAILABLE"));
        assertTrue(output.contains("do not keep retrying the same raw ids"));
    }
}
