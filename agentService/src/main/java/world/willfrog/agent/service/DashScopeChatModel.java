package world.willfrog.agent.service;

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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
@Slf4j
public class DashScopeChatModel implements ChatLanguageModel {

    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String apiKey;
    private final String modelName;
    private final Double temperature;
    private final Integer maxTokens;
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
        try {
            ChatCompletionRequest.Builder builder = ChatCompletionRequest.builder()
                    .model(nvl(modelName))
                    .messages(InternalOpenAiHelper.toOpenAiMessages(messages == null ? List.of() : messages))
                    .temperature(temperature)
                    .maxTokens(maxTokens);
            if (toolSpecifications != null && !toolSpecifications.isEmpty()) {
                builder.tools(InternalOpenAiHelper.toTools(toolSpecifications, false));
            }

            requestJson = objectMapper.writeValueAsString(builder.build());
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(buildChatCompletionsUrl()))
                    .timeout(Duration.ofSeconds(180))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + nvl(apiKey))
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> httpResponse = httpClient.send(
                    httpRequest,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            int statusCode = httpResponse.statusCode();
            String responseJson = httpResponse.body();
            if (statusCode < 200 || statusCode >= 300) {
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
            TokenUsage tokenUsage = InternalOpenAiHelper.tokenUsageFrom(completion.usage());
            FinishReason finishReason = extractFinishReason(completion);
            Map<String, Object> metadata = new LinkedHashMap<>();
            if (completion.id() != null) {
                metadata.put("id", completion.id());
            }
            if (completion.model() != null) {
                metadata.put("model", completion.model());
            }
            return Response.from(aiMessage, tokenUsage, finishReason, metadata);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            String detail = "DashScope chat completion interrupted"
                    + " (model=" + nvl(modelName) + ")";
            throw new IllegalStateException(detail, e);
        } catch (Exception e) {
            String detail = "DashScope chat completion failed"
                    + " (model=" + nvl(modelName)
                    + ", error=" + shorten(e.getMessage())
                    + ", request=" + shorten(requestJson) + ")";
            log.warn(detail, e);
            throw new IllegalStateException(detail, e);
        }
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
}
