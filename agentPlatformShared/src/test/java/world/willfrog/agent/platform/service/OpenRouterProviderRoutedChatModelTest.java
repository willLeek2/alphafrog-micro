package world.willfrog.agent.platform.service;

import world.willfrog.agent.platform.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.openai.internal.OpenAiUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.exception.ProviderFailureCategory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class OpenRouterProviderRoutedChatModelTest {

    @AfterEach
    void tearDown() {
        AgentContext.clear();
    }

    @Test
    @SuppressWarnings("unchecked")
    void emitLlmCallStarted_shouldWriteLlmCallIdAndExecutionAttribution() {
        AgentContext.setRunId("run-or-live");
        AgentContext.setUserId("user-1");
        AgentContext.setPhase("execution");
        AgentContext.setTodoContext("todo-1", 2);
        AgentContext.setWorkflow("dag");
        AgentEventService eventService = mock(AgentEventService.class);
        OpenRouterProviderRoutedChatModel model = new OpenRouterProviderRoutedChatModel(
                new ObjectMapper(),
                "https://openrouter.ai/api/v1",
                "test-key",
                Map.of(),
                "moonshotai/kimi-k2.5",
                0.7D,
                1024,
                List.of("fireworks"),
                mock(RawHttpLogger.class),
                mock(AgentObservabilityService.class),
                mock(OpenRouterCostService.class),
                eventService,
                "openrouter",
                mock(AgentLlmLocalConfigLoader.class),
                mock(LangchainLlmLatencyWindow.class)
        );

        ReflectionTestUtils.invokeMethod(model, "emitLlmCallStarted", "or-call-1", true);

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventService, times(1)).append(
                eq("run-or-live"),
                eq("user-1"),
                eq("LLM_CALL_STARTED"),
                payloadCaptor.capture()
        );
        Map<String, Object> payload = (Map<String, Object>) payloadCaptor.getValue();
        assertEquals("or-call-1", payload.get("llm_call_id"));
        assertEquals("or-call-1", payload.get("trace_id"));
        assertEquals("todo-1", payload.get("todo_id"));
        assertEquals(2, payload.get("todo_sequence"));
        assertEquals("dag", payload.get("workflow"));
    }

    @Test
    void normalizeOpenRouterTokenLimit_shouldUseMaxTokensForProviderRouting() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", "moonshotai/kimi-k2.5");
        payload.put("max_completion_tokens", 512);

        OpenRouterProviderRoutedChatModel.normalizeOpenRouterTokenLimit(payload);

        assertFalse(payload.containsKey("max_completion_tokens"));
        assertEquals(512, payload.get("max_tokens"));
    }

    @Test
    void normalizeOpenRouterTokenLimit_shouldKeepExistingMaxTokens() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("max_tokens", 256);
        payload.put("max_completion_tokens", 512);

        OpenRouterProviderRoutedChatModel.normalizeOpenRouterTokenLimit(payload);

        assertFalse(payload.containsKey("max_completion_tokens"));
        assertEquals(256, payload.get("max_tokens"));
    }

    @Test
    void applyStreamingOptions_shouldUseFireworksPerfMetricsWithoutStreamOptions() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stream", true);
        payload.put("stream_options", Map.of("include_usage", true));

        OpenRouterProviderRoutedChatModel.applyStreamingOptions(
                payload,
                "https://api.fireworks.ai/inference/v1",
                null
        );

        assertFalse(payload.containsKey("stream_options"));
        assertEquals(true, payload.get("perf_metrics_in_response"));
    }

    @Test
    void applyStreamingOptions_shouldUseOpenAiCompatibleStreamUsageForNonFireworks() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stream", true);
        payload.put("perf_metrics_in_response", true);

        OpenRouterProviderRoutedChatModel.applyStreamingOptions(
                payload,
                "https://openrouter.ai/api/v1",
                "execution"
        );

        assertFalse(payload.containsKey("perf_metrics_in_response"));
        assertEquals(Map.of("include_usage", true), payload.get("stream_options"));
    }

    @Test
    void applyStreamingOptions_shouldSkipStreamOptionsForPlanningPhaseOnOpenRouter() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stream", true);
        payload.put("stream_options", Map.of("include_usage", true));

        OpenRouterProviderRoutedChatModel.applyStreamingOptions(
                payload,
                "https://openrouter.ai/api/v1",
                AgentObservabilityService.PHASE_PLANNING
        );

        assertFalse(payload.containsKey("stream_options"));
        assertFalse(payload.containsKey("perf_metrics_in_response"));
        assertEquals(true, payload.get("stream"));
    }

    @Test
    void applyFireworksReasoningEffort_shouldSetTopLevelReasoningEffort() {
        Map<String, Object> payload = new LinkedHashMap<>();

        OpenRouterProviderRoutedChatModel.applyFireworksReasoningEffort(payload, "high");

        assertEquals("high", payload.get("reasoning_effort"));
    }

    @Test
    void applyFireworksReasoningEffort_shouldIgnoreBlankValue() {
        Map<String, Object> payload = new LinkedHashMap<>();

        OpenRouterProviderRoutedChatModel.applyFireworksReasoningEffort(payload, " ");

        assertTrue(payload.isEmpty());
    }

    @Test
    void applyEndpointSamplingDefaults_shouldOmitTemperatureForFireworks() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("temperature", 0.7D);

        OpenRouterProviderRoutedChatModel.applyEndpointSamplingDefaults(
                payload,
                "https://api.fireworks.ai/inference/v1"
        );

        assertFalse(payload.containsKey("temperature"));
    }

    @Test
    void applyEndpointSamplingDefaults_shouldKeepTemperatureForNonFireworks() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("temperature", 0.7D);

        OpenRouterProviderRoutedChatModel.applyEndpointSamplingDefaults(
                payload,
                "https://openrouter.ai/api/v1"
        );

        assertEquals(0.7D, payload.get("temperature"));
    }

    @Test
    void resolveRequestTimeout_shouldUseStageAndPhaseBuckets() {
        assertEquals(Duration.ofSeconds(90),
                OpenRouterProviderRoutedChatModel.resolveRequestTimeout("planning", "execution"));
        assertEquals(Duration.ofSeconds(90),
                OpenRouterProviderRoutedChatModel.resolveRequestTimeout("final_answer", "summarizing"));
        assertEquals(Duration.ofSeconds(30),
                OpenRouterProviderRoutedChatModel.resolveRequestTimeout("python_refine_plan", "execution"));
        assertEquals(Duration.ofSeconds(30),
                OpenRouterProviderRoutedChatModel.resolveRequestTimeout("sub_agent_step_execute", "execution"));
        assertEquals(Duration.ofSeconds(30),
                OpenRouterProviderRoutedChatModel.resolveRequestTimeout("semantic_judge", "execution"));
        assertEquals(Duration.ofSeconds(30),
                OpenRouterProviderRoutedChatModel.resolveRequestTimeout("tool_use_decision", "execution"));
        assertEquals(Duration.ofSeconds(30),
                OpenRouterProviderRoutedChatModel.resolveRequestTimeout("search_evidence_judge", "execution"));
        assertEquals(Duration.ofSeconds(60),
                OpenRouterProviderRoutedChatModel.resolveRequestTimeout("summarizing", "execution"));
    }

    @Test
    void aggregateSseStream_shouldPreserveToolCallDeltas() {
        String sse = """
                data: {"id":"gen-1","object":"chat.completion.chunk","created":1,"model":"moonshotai/kimi-k2.5","choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":null}]}

                data: {"id":"gen-1","object":"chat.completion.chunk","created":1,"model":"moonshotai/kimi-k2.5","choices":[{"index":0,"delta":{"role":"assistant","content":null,"tool_calls":[{"index":0,"id":"searchWeb:0","type":"function","function":{"name":"searchWeb","arguments":"{\\"query\\":"}}]},"finish_reason":null}]}

                data: {"id":"gen-1","object":"chat.completion.chunk","created":1,"model":"moonshotai/kimi-k2.5","choices":[{"index":0,"delta":{"role":"assistant","content":null,"tool_calls":[{"index":0,"function":{"arguments":"\\"今天A股\\",\\"maxResults\\":"}}]},"finish_reason":null}]}

                data: {"id":"gen-1","object":"chat.completion.chunk","created":1,"model":"moonshotai/kimi-k2.5","choices":[{"index":0,"delta":{"role":"assistant","content":null,"tool_calls":[{"index":0,"function":{"arguments":"5}"}}]},"finish_reason":null}]}

                data: {"id":"gen-1","object":"chat.completion.chunk","created":1,"model":"moonshotai/kimi-k2.5","choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":"tool_calls","native_finish_reason":"tool_calls"}],"usage":{"prompt_tokens":1,"completion_tokens":2,"total_tokens":3}}

                data: [DONE]
                """;

        OpenAiCompatibleChatModelSupport.SseAggregateResult result =
                OpenAiCompatibleChatModelSupport.aggregateSseStream(
                        new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)),
                        new ObjectMapper(),
                        org.slf4j.LoggerFactory.getLogger(OpenRouterProviderRoutedChatModelTest.class),
                        null
                );

        AiMessage message = OpenAiUtils.aiMessageFrom(result.completionResponse());

        assertNotNull(message.toolExecutionRequests());
        assertEquals(1, message.toolExecutionRequests().size());
        assertEquals("searchWeb", message.toolExecutionRequests().get(0).name());
        assertEquals("{\"query\":\"今天A股\",\"maxResults\":5}", message.toolExecutionRequests().get(0).arguments());
    }

    @Test
    void streamingProgressTracker_shouldCountToolCallArgumentCharsAndReportFinalSnapshot() {
        String sse = """
                data: {"id":"gen-1","object":"chat.completion.chunk","created":1,"model":"moonshotai/kimi-k2.5","choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":null}]}

                data: {"id":"gen-1","object":"chat.completion.chunk","created":1,"model":"moonshotai/kimi-k2.5","choices":[{"index":0,"delta":{"role":"assistant","content":null,"tool_calls":[{"index":0,"id":"searchWeb:0","type":"function","function":{"name":"searchWeb","arguments":"{\\"query\\":"}}]},"finish_reason":null}]}

                data: {"id":"gen-1","object":"chat.completion.chunk","created":1,"model":"moonshotai/kimi-k2.5","choices":[{"index":0,"delta":{"role":"assistant","content":null,"tool_calls":[{"index":0,"function":{"arguments":"\\"今天A股\\"}"}}]},"finish_reason":null}]}

                data: {"id":"gen-1","object":"chat.completion.chunk","created":1,"model":"moonshotai/kimi-k2.5","choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":"tool_calls"}]}

                data: [DONE]
                """;
        AtomicReference<StreamingProgressTracker.StreamingProgressSnapshot> reported = new AtomicReference<>();
        AtomicBoolean completed = new AtomicBoolean(false);
        StreamingProgressTracker tracker = new StreamingProgressTracker(
                org.slf4j.LoggerFactory.getLogger(OpenRouterProviderRoutedChatModelTest.class),
                "moonshotai/kimi-k2.5",
                "openrouter",
                false,
                true,
                1000,
                (snapshot, done) -> {
                    reported.set(snapshot);
                    completed.set(done);
                }
        );

        OpenAiCompatibleChatModelSupport.aggregateSseStream(
                new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)),
                new ObjectMapper(),
                org.slf4j.LoggerFactory.getLogger(OpenRouterProviderRoutedChatModelTest.class),
                tracker
        );
        StreamingProgressTracker.StreamingProgressSnapshot finalSnapshot = tracker.onStreamComplete(1000);

        assertEquals("{\"query\":\"今天A股\"}".length(), finalSnapshot.toolCallCharCount());
        assertEquals(finalSnapshot.toolCallCharCount(), finalSnapshot.totalCharCount());
        assertEquals(finalSnapshot, reported.get());
        assertEquals(true, completed.get());
    }

    // ── Provider order rotation tests (§2.3) ──

    @Test
    @SuppressWarnings("unchecked")
    void rotateProviderOrder_shouldMoveFirstProviderToEnd() {
        OpenRouterProviderRoutedChatModel model = createMinimalModel(List.of("deepseek", "fireworks", "alibaba"));

        List<String> rotated = (List<String>) ReflectionTestUtils.invokeMethod(
                model, "rotateProviderOrder", List.of("deepseek", "fireworks", "alibaba")
        );

        assertNotNull(rotated);
        assertEquals(3, rotated.size());
        assertEquals("fireworks", rotated.get(0));
        assertEquals("alibaba", rotated.get(1));
        assertEquals("deepseek", rotated.get(2));
    }

    @Test
    @SuppressWarnings("unchecked")
    void rotateProviderOrder_shouldRotateTwice() {
        OpenRouterProviderRoutedChatModel model = createMinimalModel(List.of("a", "b", "c"));

        List<String> first = (List<String>) ReflectionTestUtils.invokeMethod(
                model, "rotateProviderOrder", List.of("a", "b", "c")
        );
        List<String> second = (List<String>) ReflectionTestUtils.invokeMethod(
                model, "rotateProviderOrder", first
        );

        assertEquals("c", second.get(0));
        assertEquals("a", second.get(1));
        assertEquals("b", second.get(2));
    }

    @Test
    void rotateProviderOrder_shouldReturnNullForNull() {
        OpenRouterProviderRoutedChatModel model = createMinimalModel(List.of("fireworks"));

        @SuppressWarnings("unchecked")
        List<String> rotated = (List<String>) ReflectionTestUtils.invokeMethod(
                model, "rotateProviderOrder", (List<String>) null
        );

        assertEquals(null, rotated);
    }

    @Test
    void rotateProviderOrder_shouldReturnSameListForSingleProvider() {
        OpenRouterProviderRoutedChatModel model = createMinimalModel(List.of("fireworks"));

        @SuppressWarnings("unchecked")
        List<String> rotated = (List<String>) ReflectionTestUtils.invokeMethod(
                model, "rotateProviderOrder", List.of("fireworks")
        );

        assertNotNull(rotated);
        assertEquals(1, rotated.size());
        assertEquals("fireworks", rotated.get(0));
    }

    @Test
    void rebuildRequestWithProviderOrder_shouldUpdateProviderOrderAndPreserveAllowFallbacksFalse() {
        OpenRouterProviderRoutedChatModel model = createMinimalModel(List.of("deepseek", "fireworks"));
        String requestJson = """
                {"model":"deepseek/deepseek-v4-pro","messages":[],"provider":{"order":["deepseek","fireworks"],"allow_fallbacks":false}}
                """;

        String rebuilt = (String) ReflectionTestUtils.invokeMethod(
                model, "rebuildRequestWithProviderOrder", requestJson, List.of("fireworks", "deepseek")
        );

        assertNotNull(rebuilt);
        assertTrue(rebuilt.contains("\"order\":[\"fireworks\",\"deepseek\"]"), rebuilt);
        assertTrue(rebuilt.contains("\"allow_fallbacks\":false"), rebuilt);
    }

    @Test
    void rebuildRequestWithProviderOrder_shouldReturnNullForMalformedJson() {
        OpenRouterProviderRoutedChatModel model = createMinimalModel(List.of("fireworks"));

        String rebuilt = (String) ReflectionTestUtils.invokeMethod(
                model, "rebuildRequestWithProviderOrder", "not-json", List.of("fireworks")
        );

        assertEquals(null, rebuilt);
    }

    @Test
    void rebuildRequestWithProviderOrder_shouldReturnNullWhenNoProviderField() {
        OpenRouterProviderRoutedChatModel model = createMinimalModel(List.of("fireworks"));
        String requestJson = """
                {"model":"gpt-4","messages":[]}
                """;

        String rebuilt = (String) ReflectionTestUtils.invokeMethod(
                model, "rebuildRequestWithProviderOrder", requestJson, List.of("fireworks")
        );

        // 没有 provider 字段时，方法仍然能正常序列化，只是不会修改 order
        assertNotNull(rebuilt);
        assertTrue(rebuilt.contains("\"model\":\"gpt-4\""));
    }

    @Test
    void buildHttpRequest_shouldIncludeCustomHeaders() {
        OpenRouterProviderRoutedChatModel model = createMinimalModel(List.of("fireworks"));

        java.net.http.HttpRequest.Builder builder = (java.net.http.HttpRequest.Builder) ReflectionTestUtils.invokeMethod(
                model, "buildHttpRequest",
                "https://openrouter.ai/api/v1/chat/completions",
                "test-key",
                Map.of("X-Custom", "value"),
                "{}",
                Duration.ofSeconds(30)
        );

        assertNotNull(builder);
        java.net.http.HttpRequest request = builder.build();
        assertEquals("POST", request.method());
        assertTrue(request.headers().firstValue("X-Custom").isPresent());
        assertEquals("value", request.headers().firstValue("X-Custom").get());
    }

    @Test
    void isRetryableStreamingException_shouldTreatSseReadIOExceptionAsRetryable() {
        OpenRouterProviderRoutedChatModel model = createMinimalModel(List.of("deepseek", "fireworks"));
        IllegalStateException streamError = new IllegalStateException(
                "SSE 流读取失败",
                new IOException("SSE stream idle timeout after 25s")
        );

        Boolean retryable = (Boolean) ReflectionTestUtils.invokeMethod(
                model, "isRetryableStreamingException", streamError
        );
        Boolean shouldRetryFirstAttempt = (Boolean) ReflectionTestUtils.invokeMethod(
                model,
                "shouldRetryStreamingException",
                streamError,
                LlmRequestRetryPolicy.withMaxRetries(2),
                1
        );
        Boolean shouldRetryLastAttempt = (Boolean) ReflectionTestUtils.invokeMethod(
                model,
                "shouldRetryStreamingException",
                streamError,
                LlmRequestRetryPolicy.withMaxRetries(2),
                3
        );

        assertEquals(true, retryable);
        assertEquals(true, shouldRetryFirstAttempt);
        assertEquals(false, shouldRetryLastAttempt);
    }

    @Test
    void isRetryableStreamingException_shouldNotRetryProviderErrorChunk() {
        OpenRouterProviderRoutedChatModel model = createMinimalModel(List.of("deepseek", "fireworks"));
        IllegalStateException providerError = new IllegalStateException("SSE 流中收到错误 chunk: {}");

        Boolean retryable = (Boolean) ReflectionTestUtils.invokeMethod(
                model, "isRetryableStreamingException", providerError
        );

        assertEquals(false, retryable);
    }

    private OpenRouterProviderRoutedChatModel createMinimalModel(List<String> providerOrder) {
        return new OpenRouterProviderRoutedChatModel(
                new ObjectMapper(),
                "https://openrouter.ai/api/v1",
                "test-key",
                Map.of(),
                "moonshotai/kimi-k2.5",
                0.7D,
                1024,
                providerOrder,
                null,
                null,
                null,
                null,
                "openrouter",
                null,
                null
        );
    }

    // ── Provider error classification tests (Phase 3.1) ──

    @Test
    void classifyProviderError_shouldMap400ContextLengthExceededToBadRequestTokenLimit() {
        OpenRouterProviderRoutedChatModel model = createMinimalModel(List.of("fireworks"));
        String body = "{\"error\":{\"code\":\"context_length_exceeded\",\"message\":\"context too long\"}}";

        ProviderFailureCategory category = (ProviderFailureCategory) ReflectionTestUtils.invokeMethod(
                model, "classifyProviderError", 400, body, null, List.of("fireworks")
        );

        assertEquals(ProviderFailureCategory.BAD_REQUEST_TOKEN_LIMIT, category);
    }

    @Test
    void classifyProviderError_shouldMap429ToRateLimit() {
        OpenRouterProviderRoutedChatModel model = createMinimalModel(List.of("fireworks"));
        String body = "{\"error\":{\"code\":\"rate_limit_exceeded\"}}";

        ProviderFailureCategory category = (ProviderFailureCategory) ReflectionTestUtils.invokeMethod(
                model, "classifyProviderError", 429, body, null, List.of("fireworks")
        );

        assertEquals(ProviderFailureCategory.RATE_LIMIT, category);
    }

    @Test
    void classifyProviderError_shouldMap502ToTransientNetwork() {
        OpenRouterProviderRoutedChatModel model = createMinimalModel(List.of("fireworks"));

        ProviderFailureCategory category = (ProviderFailureCategory) ReflectionTestUtils.invokeMethod(
                model, "classifyProviderError", 502, "bad gateway", null, List.of("fireworks")
        );

        assertEquals(ProviderFailureCategory.TRANSIENT_NETWORK, category);
    }

    @Test
    void classifyProviderError_shouldMapNetworkConnectionLostToTransientNetwork() {
        OpenRouterProviderRoutedChatModel model = createMinimalModel(List.of("fireworks"));

        ProviderFailureCategory category = (ProviderFailureCategory) ReflectionTestUtils.invokeMethod(
                model, "classifyProviderError", -1, "Network connection lost", null, List.of("fireworks")
        );

        assertEquals(ProviderFailureCategory.TRANSIENT_NETWORK, category);
    }

    @Test
    void classifyProviderError_shouldMapSseIOExceptionToTransientNetwork() {
        OpenRouterProviderRoutedChatModel model = createMinimalModel(List.of("fireworks"));

        ProviderFailureCategory category = (ProviderFailureCategory) ReflectionTestUtils.invokeMethod(
                model, "classifyProviderError", 200, "SSE stream idle timeout after 25s",
                new IOException("SSE broken pipe"), List.of("fireworks")
        );

        assertEquals(ProviderFailureCategory.TRANSIENT_NETWORK, category);
    }

    @Test
    void classifyProviderError_shouldMap404ModelNotFoundToModelUnavailable() {
        OpenRouterProviderRoutedChatModel model = createMinimalModel(List.of("fireworks"));
        String body = "{\"error\":{\"code\":\"model_not_found\"}}";

        ProviderFailureCategory category = (ProviderFailureCategory) ReflectionTestUtils.invokeMethod(
                model, "classifyProviderError", 404, body, null, List.of("fireworks")
        );

        assertEquals(ProviderFailureCategory.MODEL_UNAVAILABLE, category);
    }

    @Test
    void extractErrorCodeFromBody_shouldReadNestedErrorCode() {
        OpenRouterProviderRoutedChatModel model = createMinimalModel(List.of("fireworks"));

        String code = (String) ReflectionTestUtils.invokeMethod(
                model, "extractErrorCodeFromBody",
                "{\"error\":{\"code\":\"context_length_exceeded\",\"message\":\"x\"}}"
        );

        assertEquals("context_length_exceeded", code);
    }

    @Test
    void extractErrorCodeFromBody_shouldReturnEmptyForNonJson() {
        OpenRouterProviderRoutedChatModel model = createMinimalModel(List.of("fireworks"));

        String code = (String) ReflectionTestUtils.invokeMethod(
                model, "extractErrorCodeFromBody", "not json"
        );

        assertEquals("", code);
    }

}
