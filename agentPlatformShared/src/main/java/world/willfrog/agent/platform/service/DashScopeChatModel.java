package world.willfrog.agent.platform.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.openai.internal.OpenAiUtils;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.context.AgentContext;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * DashScope OpenAI 兼容 ChatModel 实现 (ALP-28)
 *
 * <p>与 OpenRouterProviderRoutedChatModel 分离，避免 provider 路由冲突。</p>
 * <p>默认启用流式输出（stream=true），内部聚合 SSE 流为完整响应。</p>
 * <p>支持 thinking 模式开关（enable_thinking），默认开启。</p>
 */
@RequiredArgsConstructor
@Slf4j
public class DashScopeChatModel implements ChatModel {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .proxy(ProxySelector.getDefault())
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    private static final int DEFAULT_THINKING_BUDGET = 38912;

    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String apiKey;
    private final String modelName;
    private final Double temperature;
    private final Integer maxTokens;
    private final RawHttpLogger httpLogger;
    private final AgentObservabilityService observabilityService;
    private final String endpointName;
    private final boolean enableThinking;
    private final AgentLlmLocalConfigLoader localConfigLoader;
    private final AgentEventService eventService;

    @Setter
    private AgentRunBudgetService budgetService;

    @Override
    public ChatResponse doChat(ChatRequest chatRequest) {
        List<ChatMessage> messages = chatRequest.messages();
        List<ToolSpecification> toolSpecifications = chatRequest.toolSpecifications();
        String requestJson = null;
        long requestStartedAt = System.currentTimeMillis();
        String llmTraceId = UUID.randomUUID().toString().replace("-", "");
        boolean liveEventStarted = false;

        boolean clientWantsCapture = observabilityService != null
                && observabilityService.isCaptureLlmRequestsEnabled(AgentContext.getRunId());
        boolean endpointAllowed = httpLogger != null && httpLogger.shouldCapture(endpointName);
        boolean shouldCapture = clientWantsCapture && endpointAllowed;
        RawHttpLogger.HttpRequestRecord requestRecord = null;
        RawHttpLogger.HttpResponseRecord responseRecord = null;
        String curlCommand = null;

        try {
            if (budgetService != null) {
                budgetService.checkBeforeLlmCall();
            }
            ChatCompletionRequest.Builder builder = ChatCompletionRequest.builder()
                    .model(OpenAiCompatibleChatModelSupport.nvl(modelName))
                    .messages(OpenAiUtils.toOpenAiMessages(messages == null ? List.of() : messages))
                    .temperature(temperature)
                    .maxCompletionTokens(maxTokens);

            if (toolSpecifications != null && !toolSpecifications.isEmpty()) {
                builder.tools(OpenAiUtils.toTools(toolSpecifications, false));
            }

            ChatCompletionRequest request = builder.build();
            Map<String, Object> requestJsonMap = objectMapper.convertValue(
                    request,
                    new TypeReference<Map<String, Object>>() {
                    }
            );

            // 默认启用流式输出
            boolean useStream = true;
            // DashScope 不支持 tools + stream 同时使用
            if (toolSpecifications != null && !toolSpecifications.isEmpty()) {
                useStream = false;
            }
            requestJsonMap.put("stream", useStream);
            if (useStream) {
                requestJsonMap.put("stream_options", Map.of("include_usage", true));
            }

            applyRequestFormatting(requestJsonMap, messages, useStream);

            requestJson = objectMapper.writeValueAsString(requestJsonMap);
            String requestUrl = OpenAiCompatibleChatModelSupport.buildChatCompletionsUrl(baseUrl);
            Map<String, String> requestHeaders = OpenAiCompatibleChatModelSupport.buildRequestHeaders(apiKey);

            Duration requestTimeout = OpenRouterProviderRoutedChatModel.resolveRequestTimeout(
                    AgentContext.getStage(), AgentContext.getPhase());
            HttpRequest.Builder httpRequestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(requestUrl))
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + OpenAiCompatibleChatModelSupport.nvl(apiKey))
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8));

            if (shouldCapture) {
                requestRecord = httpLogger.recordRequest(requestUrl, "POST", requestHeaders, requestJson);
            }

            // Debug curl 日志（热加载配置）
            if (isDebugCurlEnabled()) {
                curlCommand = buildCurlCommand(requestUrl, requestHeaders, requestJson);
                log.info("[LLM Debug CURL] endpoint={} model={}\n{}", endpointName, modelName, curlCommand);
            }

            if (budgetService != null) {
                budgetService.checkHttpAttempt(1);
            }
            emitLlmCallStarted(llmTraceId, useStream);
            liveEventStarted = true;
            HttpResponse<java.io.InputStream> httpResponse = HTTP_CLIENT.send(
                    httpRequestBuilder.build(),
                    HttpResponse.BodyHandlers.ofInputStream()
            );

            int statusCode = httpResponse.statusCode();
            long durationMs = System.currentTimeMillis() - requestStartedAt;

            ChatCompletionResponse completion;
            String reasoningContent = null;
            StreamingProgressTracker.StreamingProgressSnapshot progressSnapshot = null;
            String generationId = null;
            Map<String, Object> usageMap = null;

            if (useStream && statusCode >= 200 && statusCode < 300) {
                // 流式响应：解析 SSE
                StreamingProgressTracker tracker = createStreamingProgressTracker(llmTraceId);
                OpenAiCompatibleChatModelSupport.SseAggregateResult aggregateResult =
                        OpenAiCompatibleChatModelSupport.aggregateSseStream(
                                httpResponse.body(), objectMapper, log, tracker
                        );
                durationMs = System.currentTimeMillis() - requestStartedAt;
                progressSnapshot = tracker.onStreamComplete(durationMs);
                completion = aggregateResult.completionResponse();
                reasoningContent = aggregateResult.reasoningContent();
                generationId = aggregateResult.lastId();
                usageMap = aggregateResult.lastUsage();

                // 为了 HTTP 捕获，将聚合后的响应体序列化
                String aggregatedBody = objectMapper.writeValueAsString(
                        objectMapper.convertValue(completion, new TypeReference<Map<String, Object>>() {
                        })
                );
                if (shouldCapture) {
                    Map<String, String> responseHeaders = new java.util.HashMap<>();
                    responseHeaders.put("Content-Type", "application/json");
                    responseRecord = httpLogger.recordResponse(statusCode, responseHeaders, aggregatedBody, durationMs);
                    curlCommand = httpLogger.toCurlCommand(requestRecord);
                }
            } else {
                // 非流式响应或错误：读取完整 body
                String responseJson;
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(httpResponse.body(), StandardCharsets.UTF_8))) {
                    responseJson = reader.lines().collect(java.util.stream.Collectors.joining("\n"));
                }

                if (shouldCapture) {
                    Map<String, String> responseHeaders = httpLogger.extractHeaders(httpResponse);
                    responseRecord = httpLogger.recordResponse(statusCode, responseHeaders, responseJson, durationMs);
                    curlCommand = httpLogger.toCurlCommand(requestRecord);
                }

                if (statusCode < 200 || statusCode >= 300) {
                    String detail = "DashScope chat completion failed"
                            + " (http=" + statusCode
                            + ", model=" + OpenAiCompatibleChatModelSupport.nvl(modelName)
                            + ", error=" + OpenAiCompatibleChatModelSupport.shorten(responseJson)
                            + ", request=" + OpenAiCompatibleChatModelSupport.shorten(requestJson) + ")";
                    log.warn(detail);
                    throw new IllegalStateException(detail);
                }

                completion = objectMapper.readValue(responseJson, ChatCompletionResponse.class);
                // 非流式响应也可能包含 reasoning_content
                reasoningContent = extractReasoningContentFromResponse(responseJson);
            }

            AiMessage aiMessage = OpenAiUtils.aiMessageFrom(completion);

            // 从 reasoningContent 或 <think> 标签提取 thinking
            String finalThinking = reasoningContent;
            if (finalThinking == null || finalThinking.isBlank()) {
                ThinkingContent thinking = extractThinkingContent(aiMessage == null ? null : aiMessage.text());
                if (thinking.hasThinking()) {
                    finalThinking = thinking.thinking();
                    if (aiMessage != null && thinking.hasThinking()) {
                        List<dev.langchain4j.agent.tool.ToolExecutionRequest> tools = aiMessage.toolExecutionRequests();
                        aiMessage = new AiMessage(thinking.content(), tools == null ? List.of() : tools);
                    }
                }
            }

            if (finalThinking != null && !finalThinking.isBlank()) {
                AgentContext.setThinkingContent(finalThinking);
            }
            if (progressSnapshot != null) {
                AgentContext.setStreamingProgress(progressSnapshot);
            }

            TokenUsage tokenUsage = OpenAiUtils.tokenUsageFrom(completion.usage());
            FinishReason finishReason = OpenAiCompatibleChatModelSupport.extractFinishReason(completion);
            Double actualCost = extractActualCostFromUsage(usageMap);

            String observabilityTraceId = null;
            if (shouldCapture && observabilityService != null) {
                observabilityTraceId = reportLlmCall(llmTraceId, requestRecord, responseRecord, curlCommand, requestStartedAt, durationMs, null,
                        finalThinking, progressSnapshot);
            }
            emitLlmCallFinished(llmTraceId, tokenUsage, durationMs, actualCost, generationId, null, observabilityTraceId);

            return ChatResponse.builder()
                    .aiMessage(aiMessage)
                    .metadata(ChatResponseMetadata.builder()
                            .tokenUsage(tokenUsage)
                            .finishReason(finishReason)
                            .build())
                    .build();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            long durationMs = System.currentTimeMillis() - requestStartedAt;
            String observabilityTraceId = null;
            if (shouldCapture && observabilityService != null) {
                observabilityTraceId = reportLlmCall(llmTraceId, requestRecord, responseRecord, curlCommand, requestStartedAt, durationMs, "INTERRUPTED",
                        null, null);
            }
            if (liveEventStarted) {
                emitLlmCallFinished(llmTraceId, null, durationMs, null, null, "INTERRUPTED", observabilityTraceId);
            }
            String detail = "DashScope chat completion interrupted"
                    + " (model=" + OpenAiCompatibleChatModelSupport.nvl(modelName) + ")";
            throw new IllegalStateException(detail, e);

        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - requestStartedAt;
            String observabilityTraceId = null;
            if (shouldCapture && observabilityService != null) {
                String errorType = e.getClass().getSimpleName();
                observabilityTraceId = reportLlmCall(llmTraceId, requestRecord, responseRecord, curlCommand, requestStartedAt, durationMs,
                        errorType + ": " + e.getMessage(), null, null);
            }
            if (liveEventStarted) {
                String errorType = e.getClass().getSimpleName();
                emitLlmCallFinished(llmTraceId, null, durationMs, null, null,
                        errorType + ": " + e.getMessage(), observabilityTraceId);
            }
            String detail = "DashScope chat completion failed"
                    + " (model=" + OpenAiCompatibleChatModelSupport.nvl(modelName)
                    + ", error=" + OpenAiCompatibleChatModelSupport.shorten(e.getMessage())
                    + ", request=" + OpenAiCompatibleChatModelSupport.shorten(requestJson) + ")";
            log.warn(detail, e);
            throw new IllegalStateException(detail, e);
        }
    }

    private String extractReasoningContentFromResponse(String responseJson) {
        if (responseJson == null || responseJson.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> json = objectMapper.readValue(responseJson, new TypeReference<>() {
            });
            Object choices = json.get("choices");
            if (choices instanceof List<?> list && !list.isEmpty()) {
                Object first = list.get(0);
                if (first instanceof Map<?, ?> choice) {
                    Object message = choice.get("message");
                    if (message instanceof Map<?, ?> msg) {
                        Object rc = msg.get("reasoning_content");
                        if (rc instanceof String s && !s.isBlank()) {
                            return s;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("从非流式响应提取 reasoning_content 失败: {}", e.getMessage());
        }
        return null;
    }

    private String reportLlmCall(
            String llmCallId,
            RawHttpLogger.HttpRequestRecord request,
            RawHttpLogger.HttpResponseRecord response,
            String curlCommand,
            long startedAtMillis,
            long durationMs,
            String errorMessage,
            String thinkingContent,
            StreamingProgressTracker.StreamingProgressSnapshot streamingProgress) {

        if (observabilityService == null) {
            return null;
        }

        String runId = AgentContext.getRunId();
        String phase = AgentContext.getPhase();

        if (runId == null || runId.isBlank()) {
            return null;
        }

        TokenUsage tokenUsage = OpenAiCompatibleChatModelSupport.extractTokenUsageFromResponse(objectMapper, response, log);
        Integer cachedTokens = OpenAiCompatibleChatModelSupport.extractCachedTokensFromResponse(objectMapper, response, log);
        long completedAtMillis = startedAtMillis + durationMs;

        return observabilityService.recordLlmCallWithRawHttp(
                runId,
                phase != null ? phase : "unknown",
                tokenUsage,
                cachedTokens,
                durationMs,
                startedAtMillis,
                completedAtMillis,
                endpointName,
                modelName,
                errorMessage,
                thinkingContent,
                streamingProgress,
                request,
                response,
                curlCommand,
                llmCallId
        );
    }

    /**
     * 应用请求格式化：structured output 时设置 response_format，同时禁用 thinking。
     * package-private，供回归测试直接访问。
     */
    void applyRequestFormatting(Map<String, Object> requestJsonMap, List<ChatMessage> messages, boolean useStream) {
        AgentContext.StructuredOutputSpec structuredOutputSpec = AgentContext.getStructuredOutputSpec();
        if (structuredOutputSpec != null) {
            // DashScope 使用 json_object，不支持 OpenRouter 的 json_schema 格式
            requestJsonMap.put("response_format", Map.of("type", "json_object"));
        }
        // DashScope 深度思考模型多数仅支持流式输出，stream=false 时不应开启 thinking
        // 结构化输出与 thinking 模式冲突（Json mode 不支持 enable_thinking=true）
        boolean structuredOutputEnabled = structuredOutputSpec != null || isStructuredOutputEnabledInConfig();
        if (useStream && !structuredOutputEnabled) {
            applyThinkingConfig(requestJsonMap, messages);
        }
    }

    private boolean isStructuredOutputEnabledInConfig() {
        if (localConfigLoader == null) {
            return false;
        }
        Optional<AgentLlmProperties> current = localConfigLoader.current();
        if (current == null || current.isEmpty()) {
            return false;
        }
        Boolean enabled = current
                .map(AgentLlmProperties::getRuntime)
                .map(AgentLlmProperties.Runtime::getPlanning)
                .map(AgentLlmProperties.Planning::getStructuredOutput)
                .map(AgentLlmProperties.StructuredOutput::getEnabled)
                .orElse(null);
        return enabled == null || enabled;
    }

    private void applyThinkingConfig(Map<String, Object> requestJsonMap, List<ChatMessage> messages) {
        if (!enableThinking) {
            return;
        }
        if (!supportsThinking(modelName)) {
            return;
        }
        requestJsonMap.put("enable_thinking", true);
        int thinkingBudget = DEFAULT_THINKING_BUDGET;
        Object mct = requestJsonMap.get("max_completion_tokens");
        if (mct instanceof Number n) {
            int maxCompletionTokens = n.intValue();
            if (maxCompletionTokens > 0 && maxCompletionTokens <= thinkingBudget) {
                thinkingBudget = Math.max(1, maxCompletionTokens - 1);
            }
        }
        requestJsonMap.put("thinking_budget", thinkingBudget);
    }

    private boolean supportsThinking(String modelName) {
        if (modelName == null) {
            return false;
        }
        String normalized = modelName.trim().toLowerCase();
        return normalized.startsWith("qwen3.5") || normalized.startsWith("qwen3.6");
    }

    private ThinkingContent extractThinkingContent(String content) {
        if (content == null || content.isBlank()) {
            return new ThinkingContent(content, "");
        }
        StringBuilder cleaned = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        int index = 0;
        while (true) {
            int start = content.indexOf("<think>", index);
            if (start < 0) {
                break;
            }
            int end = content.indexOf("</think>", start);
            if (end < 0) {
                break;
            }
            cleaned.append(content, index, start);
            String chunk = content.substring(start + "<think>".length(), end).trim();
            if (!chunk.isEmpty()) {
                if (thinking.length() > 0) {
                    thinking.append("\n");
                }
                thinking.append(chunk);
            }
            index = end + "</think>".length();
        }
        cleaned.append(content.substring(index));
        String cleanedText = cleaned.toString().trim();
        return new ThinkingContent(cleanedText, thinking.toString().trim());
    }

    private record ThinkingContent(String content, String thinking) {
        private boolean hasThinking() {
            return thinking != null && !thinking.isBlank();
        }
    }

    /**
     * 检查是否开启 curl debug 日志（热加载）。
     */
    private boolean isDebugCurlEnabled() {
        if (localConfigLoader == null) {
            return false;
        }
        return localConfigLoader.current()
                .map(cfg -> cfg.getDebug())
                .map(debug -> debug.getLogLlmCurl())
                .orElse(false);
    }

    private StreamingProgressTracker createStreamingProgressTracker(String llmTraceId) {
        String runId = AgentContext.getRunId();
        String userId = AgentContext.getUserId();
        String phase = AgentContext.getPhase();
        boolean observabilityEnabled = isStreamingProgressReportEnabled()
                && observabilityService != null
                && runId != null
                && !runId.isBlank();
        boolean liveEventEnabled = eventService != null
                && runId != null
                && !runId.isBlank()
                && userId != null
                && !userId.isBlank();
        boolean reportEnabled = observabilityEnabled || liveEventEnabled;
        return new StreamingProgressTracker(
                log,
                modelName,
                endpointName,
                isSseProgressLogEnabled(),
                reportEnabled,
                streamingProgressUpdateIntervalMs(),
                (snapshot, completed) -> {
                    if (observabilityEnabled) {
                        observabilityService.recordStreamingProgress(
                                runId,
                                phase != null ? phase : "unknown",
                                endpointName,
                                modelName,
                                snapshot,
                                completed
                        );
                    }
                    emitLlmCallDelta(llmTraceId, snapshot);
                }
        );
    }

    private boolean isStreamingProgressReportEnabled() {
        return localConfigLoader == null
                || localConfigLoader.current()
                .map(AgentLlmProperties::getObservability)
                .map(AgentLlmProperties.Observability::getStreamingProgress)
                .map(AgentLlmProperties.StreamingProgress::getEnabled)
                .map(Boolean.TRUE::equals)
                .orElse(true);
    }

    private long streamingProgressUpdateIntervalMs() {
        return localConfigLoader == null ? 3000L
                : localConfigLoader.current()
                .map(AgentLlmProperties::getObservability)
                .map(AgentLlmProperties.Observability::getStreamingProgress)
                .map(AgentLlmProperties.StreamingProgress::getUpdateIntervalMs)
                .filter(v -> v != null && v > 0)
                .map(Integer::longValue)
                .orElse(3000L);
    }

    private boolean isSseProgressLogEnabled() {
        return localConfigLoader != null
                && localConfigLoader.current()
                .map(AgentLlmProperties::getDebug)
                .map(AgentLlmProperties.Debug::getLogSseProgress)
                .map(Boolean.TRUE::equals)
                .orElse(false);
    }

    /**
     * 构建 curl 命令字符串。
     */
    private String buildCurlCommand(String url, Map<String, String> headers, String body) {
        StringBuilder curl = new StringBuilder();
        curl.append("curl -X POST \\\n");
        curl.append("  \"").append(url).append("\" \\\n");
        if (headers != null) {
            headers.forEach((key, value) -> {
                String headerName = key.toLowerCase();
                if (headerName.contains("authorization")) {
                    curl.append("  -H \"").append(key).append(": Bearer $API_KEY\" \\\n");
                } else {
                    curl.append("  -H \"").append(key).append(": ").append(value).append("\" \\\n");
                }
            });
        }
        if (body != null && !body.isEmpty()) {
            String escapedBody = body.replace("'", "'\"'\"'");
            curl.append("  -d '").append(escapedBody).append("'");
        }
        return curl.toString();
    }

    private void emitLlmCallStarted(String llmTraceId, boolean stream) {
        if (eventService == null) {
            return;
        }
        String runId = AgentContext.getRunId();
        String userId = AgentContext.getUserId();
        if (runId == null || userId == null) {
            log.debug("Skip DashScope LLM_CALL_STARTED: missing runId or userId in AgentContext");
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        AgentSsePayloadSupport.putLlmCallIds(payload, llmTraceId);
        payload.put("model", OpenAiCompatibleChatModelSupport.nvl(modelName));
        payload.put("endpoint", OpenAiCompatibleChatModelSupport.nvl(endpointName));
        payload.put("phase", OpenAiCompatibleChatModelSupport.nvl(AgentContext.getPhase()));
        AgentSsePayloadSupport.putExecutionAttribution(payload);
        payload.put("stream", stream);
        payload.put("started_at_ms", System.currentTimeMillis());
        try {
            eventService.append(runId, userId, "LLM_CALL_STARTED", payload);
        } catch (Exception e) {
            log.warn("DashScope LLM_CALL_STARTED event emit failed (ignored): {}", e.getMessage());
        }
    }

    private void emitLlmCallDelta(String llmTraceId, StreamingProgressTracker.StreamingProgressSnapshot snapshot) {
        if (eventService == null || snapshot == null) {
            return;
        }
        String runId = AgentContext.getRunId();
        String userId = AgentContext.getUserId();
        if (runId == null || userId == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        AgentSsePayloadSupport.putLlmCallIds(payload, llmTraceId);
        AgentSsePayloadSupport.putExecutionAttribution(payload);
        payload.put("content_chars", snapshot.contentCharCount());
        payload.put("reasoning_chars", snapshot.reasoningCharCount());
        payload.put("tool_call_chars", snapshot.toolCallCharCount());
        payload.put("chunk_count", snapshot.chunkCount());
        payload.put("chars_per_second", Math.round(snapshot.charsPerSecond() * 10.0) / 10.0);
        payload.put("estimated_output_tokens", snapshot.totalCharCount() / 4);
        try {
            eventService.append(runId, userId, "LLM_CALL_DELTA", payload);
        } catch (Exception e) {
            log.warn("DashScope LLM_CALL_DELTA event emit failed (ignored): {}", e.getMessage());
        }
    }

    private void emitLlmCallFinished(String llmTraceId,
                                     TokenUsage tokenUsage,
                                     long durationMs,
                                     Double actualCost,
                                     String generationId,
                                     String errorPreview,
                                     String observabilityTraceId) {
        if (eventService == null) {
            return;
        }
        String runId = AgentContext.getRunId();
        String userId = AgentContext.getUserId();
        if (runId == null || userId == null) {
            log.debug("Skip DashScope LLM_CALL_FINISHED: missing runId or userId in AgentContext");
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        AgentSsePayloadSupport.putLlmCallIds(payload, llmTraceId);
        payload.put("model", OpenAiCompatibleChatModelSupport.nvl(modelName));
        payload.put("endpoint", OpenAiCompatibleChatModelSupport.nvl(endpointName));
        payload.put("phase", OpenAiCompatibleChatModelSupport.nvl(AgentContext.getPhase()));
        AgentSsePayloadSupport.putExecutionAttribution(payload);
        payload.put("duration_ms", Math.max(0L, durationMs));
        payload.put("success", errorPreview == null);
        if (errorPreview != null) {
            payload.put("error_preview", errorPreview.length() > 500 ? errorPreview.substring(0, 500) : errorPreview);
        }
        if (tokenUsage != null) {
            payload.put("input_tokens", tokenUsage.inputTokenCount());
            payload.put("output_tokens", tokenUsage.outputTokenCount());
            payload.put("total_tokens", tokenUsage.totalTokenCount());
        }
        if (actualCost != null) {
            payload.put("actual_cost", actualCost);
        }
        if (generationId != null && !generationId.isBlank()) {
            payload.put("generation_id", generationId);
        }
        if (observabilityTraceId != null && !observabilityTraceId.isBlank()) {
            payload.put("observability_trace_id", observabilityTraceId);
        }
        try {
            eventService.append(runId, userId, "LLM_CALL_FINISHED", payload);
        } catch (Exception e) {
            log.warn("DashScope LLM_CALL_FINISHED event emit failed (ignored): {}", e.getMessage());
        }
    }

    private Double extractActualCostFromUsage(Map<String, Object> usage) {
        if (usage == null) {
            return null;
        }
        Object cost = usage.get("cost");
        if (cost instanceof Number n) {
            return n.doubleValue();
        }
        return null;
    }
}
