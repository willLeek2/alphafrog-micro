package world.willfrog.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ai4j.openai4j.chat.ChatCompletionRequest;
import dev.ai4j.openai4j.chat.ChatCompletionResponse;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.InternalOpenAiHelper;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import world.willfrog.agent.context.AgentContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DashScope OpenAI 兼容 ChatModel 实现 (ALP-28)
 *
 * <p>与 OpenRouterProviderRoutedChatModel 分离，避免 provider 路由冲突。</p>
 */
@RequiredArgsConstructor
@Slf4j
public class DashScopeChatModel implements ChatLanguageModel {

    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String apiKey;
    private final String modelName;
    private final Double temperature;
    private final Integer maxTokens;
    private final RawHttpLogger httpLogger;
    private final AgentObservabilityService observabilityService;
    private final String endpointName;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages) {
        return generate(messages, List.of());
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages, List<ToolSpecification> toolSpecifications) {
        String requestJson = null;
        long requestStartedAt = System.currentTimeMillis();

        boolean clientWantsCapture = observabilityService != null
                && observabilityService.isCaptureLlmRequestsEnabled(AgentContext.getRunId());
        boolean endpointAllowed = httpLogger != null && httpLogger.shouldCapture(endpointName);
        boolean shouldCapture = clientWantsCapture && endpointAllowed;
        RawHttpLogger.HttpRequestRecord requestRecord = null;
        RawHttpLogger.HttpResponseRecord responseRecord = null;
        String curlCommand = null;
        int statusCode = -1;
        String responseJson = null;

        try {
            ChatCompletionRequest.Builder builder = ChatCompletionRequest.builder()
                    .model(nvl(modelName))
                    .messages(InternalOpenAiHelper.toOpenAiMessages(messages == null ? List.of() : messages))
                    .temperature(temperature)
                    .maxCompletionTokens(maxTokens);

            if (toolSpecifications != null && !toolSpecifications.isEmpty()) {
                builder.tools(InternalOpenAiHelper.toTools(toolSpecifications, false));
            }

            ChatCompletionRequest request = builder.build();
            Map<String, Object> requestJsonMap = objectMapper.convertValue(
                    request,
                    new TypeReference<Map<String, Object>>() {
                    }
            );
            AgentContext.StructuredOutputSpec structuredOutputSpec = AgentContext.getStructuredOutputSpec();
            if (structuredOutputSpec != null) {
                requestJsonMap.put("response_format", structuredOutputSpec.asResponseFormat());
            }
            if (toolSpecifications != null && !toolSpecifications.isEmpty()) {
                requestJsonMap.put("stream", false);
            }
            applyThinkingConfig(requestJsonMap, messages);

            requestJson = objectMapper.writeValueAsString(requestJsonMap);
            String requestUrl = buildChatCompletionsUrl();
            Map<String, String> requestHeaders = buildRequestHeaders();

            HttpRequest.Builder httpRequestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(requestUrl))
                    .timeout(Duration.ofSeconds(180))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + nvl(apiKey))
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8));

            if (shouldCapture) {
                requestRecord = httpLogger.recordRequest(requestUrl, "POST", requestHeaders, requestJson);
            }

            HttpResponse<String> httpResponse = httpClient.send(
                    httpRequestBuilder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );

            statusCode = httpResponse.statusCode();
            responseJson = httpResponse.body();
            long durationMs = System.currentTimeMillis() - requestStartedAt;

            if (shouldCapture) {
                Map<String, String> responseHeaders = httpLogger.extractHeaders(httpResponse);
                responseRecord = httpLogger.recordResponse(statusCode, responseHeaders, responseJson, durationMs);
                curlCommand = httpLogger.toCurlCommand(requestRecord);
            }

            if (statusCode < 200 || statusCode >= 300) {
                if (shouldCapture && observabilityService != null) {
                    reportLlmCall(requestRecord, responseRecord, curlCommand, requestStartedAt, durationMs,
                            "HTTP_ERROR_" + statusCode);
                }
                String detail = "DashScope chat completion failed"
                        + " (http=" + statusCode
                        + ", model=" + nvl(modelName)
                        + ", error=" + shorten(responseJson)
                        + ", request=" + shorten(requestJson) + ")";
                log.warn(detail);
                throw new IllegalStateException(detail);
            }

            ChatCompletionResponse completion = objectMapper.readValue(responseJson, ChatCompletionResponse.class);
            AiMessage aiMessage = InternalOpenAiHelper.aiMessageFrom(completion);
            ThinkingContent thinking = extractThinkingContent(aiMessage == null ? null : aiMessage.text());
            if (aiMessage != null && thinking.hasThinking()) {
                List<dev.langchain4j.agent.tool.ToolExecutionRequest> tools = aiMessage.toolExecutionRequests();
                aiMessage = new AiMessage(thinking.content(), tools == null ? List.of() : tools);
            }
            TokenUsage tokenUsage = InternalOpenAiHelper.tokenUsageFrom(completion.usage());
            FinishReason finishReason = extractFinishReason(completion);

            Map<String, Object> metadata = new LinkedHashMap<>();
            if (completion.id() != null) {
                metadata.put("id", completion.id());
            }
            if (completion.model() != null) {
                metadata.put("model", completion.model());
            }
            if (thinking.hasThinking()) {
                metadata.put("thinking", thinking.thinking());
            }

            if (shouldCapture && observabilityService != null) {
                reportLlmCall(requestRecord, responseRecord, curlCommand, requestStartedAt, durationMs, null);
            }

            return Response.from(aiMessage, tokenUsage, finishReason, metadata);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            if (shouldCapture && observabilityService != null) {
                long durationMs = System.currentTimeMillis() - requestStartedAt;
                reportLlmCall(requestRecord, responseRecord, curlCommand, requestStartedAt, durationMs, "INTERRUPTED");
            }

            String detail = "DashScope chat completion interrupted"
                    + " (model=" + nvl(modelName) + ")";
            throw new IllegalStateException(detail, e);

        } catch (Exception e) {
            if (shouldCapture && observabilityService != null) {
                long durationMs = System.currentTimeMillis() - requestStartedAt;
                String errorType = e.getClass().getSimpleName();
                reportLlmCall(requestRecord, responseRecord, curlCommand, requestStartedAt, durationMs,
                        errorType + ": " + e.getMessage());
            }

            String detail = "DashScope chat completion failed"
                    + " (model=" + nvl(modelName)
                    + ", error=" + shorten(e.getMessage())
                    + ", request=" + shorten(requestJson) + ")";
            log.warn(detail, e);
            throw new IllegalStateException(detail, e);
        }
    }

    private void reportLlmCall(
            RawHttpLogger.HttpRequestRecord request,
            RawHttpLogger.HttpResponseRecord response,
            String curlCommand,
            long startedAtMillis,
            long durationMs,
            String errorMessage) {

        if (observabilityService == null) {
            return;
        }

        String runId = AgentContext.getRunId();
        String phase = AgentContext.getPhase();

        if (runId == null || runId.isBlank()) {
            return;
        }

        TokenUsage tokenUsage = extractTokenUsageFromResponse(response);
        long completedAtMillis = startedAtMillis + durationMs;

        observabilityService.recordLlmCallWithRawHttp(
                runId,
                phase != null ? phase : "unknown",
                tokenUsage,
                durationMs,
                startedAtMillis,
                completedAtMillis,
                endpointName,
                modelName,
                errorMessage,
                request,
                response,
                curlCommand
        );
    }

    private TokenUsage extractTokenUsageFromResponse(RawHttpLogger.HttpResponseRecord response) {
        if (response == null || response.getBody() == null || response.getBody().isBlank()) {
            return null;
        }

        try {
            Map<String, Object> json = objectMapper.readValue(response.getBody(), new TypeReference<>() {
            });
            Object usage = json.get("usage");
            if (usage instanceof Map<?, ?> usageMap) {
                Integer promptTokens = toInt(usageMap.get("prompt_tokens"));
                Integer completionTokens = toInt(usageMap.get("completion_tokens"));
                Integer totalTokens = toInt(usageMap.get("total_tokens"));
                int total = totalTokens != null ? totalTokens :
                        ((promptTokens != null ? promptTokens : 0) + (completionTokens != null ? completionTokens : 0));

                return new TokenUsage(
                        promptTokens != null ? promptTokens : 0,
                        completionTokens != null ? completionTokens : 0,
                        total
                );
            }
        } catch (Exception e) {
            log.debug("Failed to extract token usage from response: {}", e.getMessage());
        }

        return null;
    }

    private Integer toInt(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, String> buildRequestHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json");
        headers.put("Authorization", "Bearer " + nvl(apiKey));
        return headers;
    }

    private FinishReason extractFinishReason(ChatCompletionResponse completion) {
        if (completion == null || completion.choices() == null || completion.choices().isEmpty()) {
            return null;
        }
        String raw = completion.choices().get(0).finishReason();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return InternalOpenAiHelper.finishReasonFrom(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private void applyThinkingConfig(Map<String, Object> requestJsonMap, List<ChatMessage> messages) {
        if (!supportsThinking(modelName)) {
            return;
        }
        boolean enableThinking = true;
        if (messages != null) {
            for (ChatMessage message : messages) {
                if (message == null || message.text() == null) {
                    continue;
                }
                String text = message.text();
                if (text.contains("/no_think")) {
                    enableThinking = false;
                } else if (text.contains("/think")) {
                    enableThinking = true;
                }
            }
        }
        requestJsonMap.put("enable_thinking", enableThinking);
    }

    private boolean supportsThinking(String modelName) {
        if (modelName == null) {
            return false;
        }
        String normalized = modelName.trim().toLowerCase();
        return normalized.startsWith("qwen3") || normalized.startsWith("qwq");
    }

    private ThinkingContent extractThinkingContent(String content) {
        if (content == null || content.isBlank()) {
            return new ThinkingContent(content, "");
        }
        String text = content;
        StringBuilder cleaned = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        int index = 0;
        while (true) {
            int start = text.indexOf("<think>", index);
            if (start < 0) {
                break;
            }
            int end = text.indexOf("</think>", start);
            if (end < 0) {
                break;
            }
            cleaned.append(text, index, start);
            String chunk = text.substring(start + "<think>".length(), end).trim();
            if (!chunk.isEmpty()) {
                if (thinking.length() > 0) {
                    thinking.append("\n");
                }
                thinking.append(chunk);
            }
            index = end + "</think>".length();
        }
        cleaned.append(text.substring(index));
        String cleanedText = cleaned.toString().trim();
        return new ThinkingContent(cleanedText, thinking.toString().trim());
    }

    private String nvl(String value) {
        return value == null ? "" : value;
    }

    private String buildChatCompletionsUrl() {
        String normalized = nvl(baseUrl).trim();
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.endsWith("/chat/completions")) {
            return normalized;
        }
        if (normalized.endsWith("/v1")) {
            return normalized + "/chat/completions";
        }
        return normalized + "/v1/chat/completions";
    }

    private String shorten(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace('\n', ' ').replace('\r', ' ');
        if (normalized.length() <= 600) {
            return normalized;
        }
        return normalized.substring(0, 600) + "...";
    }

    private record ThinkingContent(String content, String thinking) {
        private boolean hasThinking() {
            return thinking != null && !thinking.isBlank();
        }
    }
}
