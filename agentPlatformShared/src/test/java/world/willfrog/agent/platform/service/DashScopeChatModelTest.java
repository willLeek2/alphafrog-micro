package world.willfrog.agent.platform.service;

import world.willfrog.agent.platform.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.context.AgentContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DashScopeChatModelTest {

    @Test
    void supportsThinking_shouldOnlyMatchQwen35AndQwen36Prefixes() {
        DashScopeChatModel model = newModel("qwen3.6-max-preview", true);

        assertTrue(invokeSupportsThinking(model, "qwen3.6-max-preview"));
        assertTrue(invokeSupportsThinking(model, "qwen3.6-plus"));
        assertTrue(invokeSupportsThinking(model, "qwen3.5-plus"));
        assertTrue(invokeSupportsThinking(model, "qwen3.5-flash"));
        assertTrue(invokeSupportsThinking(model, "QWEN3.6-MAX-PREVIEW"));

        // 旧版 qwen3-max / qwq 已不再被识别为 thinking 模型
        assertFalse(invokeSupportsThinking(model, "qwen3-max"));
        assertFalse(invokeSupportsThinking(model, "qwq-plus"));
        assertFalse(invokeSupportsThinking(model, "qwen-plus"));
        assertFalse(invokeSupportsThinking(model, ""));
        assertFalse(invokeSupportsThinking(model, null));
    }

    @Test
    void applyThinkingConfig_shouldEnableWhenFeatureOnAndModelSupports() {
        DashScopeChatModel model = newModel("qwen3.6-max-preview", true);
        Map<String, Object> request = new LinkedHashMap<>();

        ReflectionTestUtils.invokeMethod(model, "applyThinkingConfig", request, java.util.List.of());

        assertEquals(Boolean.TRUE, request.get("enable_thinking"));
        assertEquals(38912, request.get("thinking_budget"));
    }

    @Test
    void applyThinkingConfig_shouldSkipWhenFeatureDisabled() {
        DashScopeChatModel model = newModel("qwen3.6-max-preview", false);
        Map<String, Object> request = new LinkedHashMap<>();

        ReflectionTestUtils.invokeMethod(model, "applyThinkingConfig", request, java.util.List.of());

        assertTrue(request.isEmpty());
    }

    @Test
    void applyThinkingConfig_shouldSkipForUnsupportedModels() {
        // 即使 enableThinking=true，但 qwen-plus 不在支持列表内
        DashScopeChatModel model = newModel("qwen-plus", true);
        Map<String, Object> request = new LinkedHashMap<>();

        ReflectionTestUtils.invokeMethod(model, "applyThinkingConfig", request, java.util.List.of());

        assertTrue(request.isEmpty());
    }

    @Test
    void applyThinkingConfig_shouldCapThinkingBudgetWhenMaxCompletionTokensTooSmall() {
        DashScopeChatModel model = newModel("qwen3.6-flash", true);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("max_completion_tokens", 8192);

        ReflectionTestUtils.invokeMethod(model, "applyThinkingConfig", request, java.util.List.of());

        assertEquals(Boolean.TRUE, request.get("enable_thinking"));
        assertEquals(8191, request.get("thinking_budget"));
    }

    @Test
    void applyThinkingConfig_shouldKeepDefaultThinkingBudgetWhenMaxCompletionTokensLargeEnough() {
        DashScopeChatModel model = newModel("qwen3.6-flash", true);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("max_completion_tokens", 40000);

        ReflectionTestUtils.invokeMethod(model, "applyThinkingConfig", request, java.util.List.of());

        assertEquals(Boolean.TRUE, request.get("enable_thinking"));
        assertEquals(38912, request.get("thinking_budget"));
    }

    @Test
    void extractThinkingContent_shouldSplitThinkTags() {
        DashScopeChatModel model = newModel("qwen3.6-max-preview", true);

        Object thinkingContent = ReflectionTestUtils.invokeMethod(
                model,
                "extractThinkingContent",
                "<think>先推理A</think>结论A<think>再推理B</think>结论B"
        );

        assertEquals("结论A结论B", ReflectionTestUtils.invokeMethod(thinkingContent, "content"));
        assertEquals("先推理A\n再推理B", ReflectionTestUtils.invokeMethod(thinkingContent, "thinking"));
    }

    @Test
    void extractThinkingContent_shouldHandleNullOrBlank() {
        DashScopeChatModel model = newModel("qwen3.6-max-preview", true);

        Object empty = ReflectionTestUtils.invokeMethod(model, "extractThinkingContent", (Object) null);
        assertEquals("", ReflectionTestUtils.invokeMethod(empty, "thinking"));
    }

    @Test
    void reportLlmCall_shouldExposeProviderTraceIdForOuterDedup() {
        AgentContext.clear();
        AgentContext.setRunId("run-dashscope-trace");
        AgentObservabilityService observabilityService = mock(AgentObservabilityService.class);
        when(observabilityService.recordLlmCallWithRawHttp(
                anyString(),
                anyString(),
                any(),
                any(),
                anyLong(),
                anyLong(),
                anyLong(),
                anyString(),
                anyString(),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull()
        )).thenReturn("trace-dashscope-1");
        DashScopeChatModel model = new DashScopeChatModel(
                new ObjectMapper(),
                "https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
                "dashscope-key",
                "qwen-plus",
                0.6D,
                1024,
                mock(RawHttpLogger.class),
                observabilityService,
                "dashscope",
                true,
                mock(AgentLlmLocalConfigLoader.class),
                mock(AgentEventService.class)
        );

        String traceId = ReflectionTestUtils.invokeMethod(
                model,
                "reportLlmCall",
                null,
                null,
                null,
                100L,
                50L,
                null,
                null,
                null
        );

        assertEquals("trace-dashscope-1", traceId);
        assertEquals("trace-dashscope-1", AgentContext.consumeProviderLlmTraceId());
        AgentContext.clear();
    }

    @Test
    void doChat_shouldCheckBudgetBeforeNetworkAttempt() {
        DashScopeChatModel model = newModel("qwen-plus", true);
        AgentRunBudgetService budgetService = mock(AgentRunBudgetService.class);
        doThrow(new IllegalStateException("budget exceeded"))
                .when(budgetService).checkBeforeLlmCall();
        model.setBudgetService(budgetService);

        ChatRequest request = ChatRequest.builder()
                .messages(List.of(new UserMessage("hello")))
                .build();

        assertThrows(IllegalStateException.class, () -> model.doChat(request));
        verify(budgetService).checkBeforeLlmCall();
        verify(budgetService, never()).checkHttpAttempt(anyInt());
    }

    // ==================== structured output 禁 thinking 回归测试 ====================

    @Test
    void applyRequestFormatting_shouldSetJsonObjectAndDisableThinking_whenStructuredOutputEnabled() {
        AgentContext.clear();
        AgentContext.setStructuredOutputSpec(
                new AgentContext.StructuredOutputSpec("test_schema", false, Map.of("type", "object"), false, true)
        );
        DashScopeChatModel model = newModel("qwen3.6-max-preview", true);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("stream", true);

        model.applyRequestFormatting(request, List.of(), true);

        assertTrue(request.containsKey("response_format"));
        assertEquals("json_object", ((Map<?, ?>) request.get("response_format")).get("type"));
        assertFalse(request.containsKey("enable_thinking"), "structured output 时不应开启 enable_thinking");
        assertFalse(request.containsKey("thinking_budget"), "structured output 时不应设置 thinking_budget");

        AgentContext.clear();
    }

    @Test
    void applyRequestFormatting_shouldEnableThinking_whenStructuredOutputDisabled() {
        AgentContext.clear();
        // 不设置 StructuredOutputSpec，模拟普通（非结构化）请求
        DashScopeChatModel model = newModel("qwen3.6-max-preview", true);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("stream", true);

        model.applyRequestFormatting(request, List.of(), true);

        assertEquals(Boolean.TRUE, request.get("enable_thinking"), "普通请求应开启 thinking");
        assertEquals(38912, request.get("thinking_budget"), "普通请求应设置默认 thinking_budget");
        assertFalse(request.containsKey("response_format"), "普通请求不应设置 response_format");

        AgentContext.clear();
    }

    @Test
    void applyRequestFormatting_shouldDisableThinking_whenStreamIsFalseEvenWithoutStructuredOutput() {
        AgentContext.clear();
        // stream=false 时，即使 structured output 未启用，也不应开启 thinking
        DashScopeChatModel model = newModel("qwen3.6-max-preview", true);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("stream", false);

        model.applyRequestFormatting(request, List.of(), false);

        assertFalse(request.containsKey("enable_thinking"), "stream=false 时不应开启 thinking");
        assertFalse(request.containsKey("thinking_budget"), "stream=false 时不应设置 thinking_budget");
        assertFalse(request.containsKey("response_format"));

        AgentContext.clear();
    }

    @Test
    @SuppressWarnings("unchecked")
    void emitLlmEvents_shouldAppendDashScopeLiveEvents() {
        AgentContext.clear();
        AgentContext.setRunId("run-dashscope-live");
        AgentContext.setUserId("user-1");
        AgentContext.setPhase("planning");
        AgentEventService eventService = mock(AgentEventService.class);
        DashScopeChatModel model = newModel("qwen3.6-flash", true, eventService);

        ReflectionTestUtils.invokeMethod(model, "emitLlmCallStarted", "trace-live-1", true);
        ReflectionTestUtils.invokeMethod(
                model,
                "emitLlmCallDelta",
                "trace-live-1",
                new StreamingProgressTracker.StreamingProgressSnapshot(12, 8, 0, 20, 3, 2000L, 10.0)
        );
        ReflectionTestUtils.invokeMethod(
                model,
                "emitLlmCallFinished",
                "trace-live-1",
                null,
                2500L,
                null,
                "dashscope-generation-1",
                null,
                "obs-trace-1"
        );

        ArgumentCaptor<String> eventTypeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventService, times(3)).append(
                eq("run-dashscope-live"),
                eq("user-1"),
                eventTypeCaptor.capture(),
                payloadCaptor.capture()
        );

        assertEquals(List.of("LLM_CALL_STARTED", "LLM_CALL_DELTA", "LLM_CALL_FINISHED"),
                eventTypeCaptor.getAllValues());
        Map<String, Object> deltaPayload = (Map<String, Object>) payloadCaptor.getAllValues().get(1);
        assertEquals("trace-live-1", deltaPayload.get("trace_id"));
        assertEquals(12, deltaPayload.get("content_chars"));
        assertEquals(8, deltaPayload.get("reasoning_chars"));
        assertEquals(5, deltaPayload.get("estimated_output_tokens"));
        Map<String, Object> finishedPayload = (Map<String, Object>) payloadCaptor.getAllValues().get(2);
        assertEquals("dashscope-generation-1", finishedPayload.get("generation_id"));
        assertEquals("obs-trace-1", finishedPayload.get("observability_trace_id"));

        AgentContext.clear();
    }

    private static boolean invokeSupportsThinking(DashScopeChatModel model, String name) {
        Boolean result = ReflectionTestUtils.invokeMethod(model, "supportsThinking", name);
        return Boolean.TRUE.equals(result);
    }

    private DashScopeChatModel newModel(String modelName, boolean enableThinking) {
        return newModel(modelName, enableThinking, mock(AgentEventService.class));
    }

    private DashScopeChatModel newModel(String modelName, boolean enableThinking, AgentEventService eventService) {
        return new DashScopeChatModel(
                new ObjectMapper(),
                "https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
                "dashscope-key",
                modelName,
                0.6D,
                1024,
                mock(RawHttpLogger.class),
                mock(AgentObservabilityService.class),
                "dashscope",
                enableThinking,
                mock(AgentLlmLocalConfigLoader.class),
                eventService
        );
    }
}
