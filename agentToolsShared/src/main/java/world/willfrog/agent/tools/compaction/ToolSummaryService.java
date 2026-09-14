package world.willfrog.agent.tools.compaction;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.service.AgentAiServiceFactory;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;
import world.willfrog.agent.platform.service.AgentLlmResolver;
import world.willfrog.agent.platform.service.AgentPromptService;
import world.willfrog.agent.platform.service.AgentRunObservabilityService;

/**
 * 工具结果摘要 LLM，独立 plain ChatModel，phase=tool_result_summary。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ToolSummaryService {

    private static final String PHASE = "tool_result_summary";
    private static final double DEFAULT_TEMPERATURE = 0.2D;

    private final AgentAiServiceFactory aiServiceFactory;
    private final AgentLlmLocalConfigLoader localConfigLoader;
    private final AgentRunObservabilityService observabilityService;
    private final AgentPromptService promptService;

    public String summarize(String toolName, String rawOutput, String todoGoal) {
        int attempts = Math.max(0, resolveSummaryMaxRetries()) + 1;
        Exception lastError = null;
        for (int i = 0; i < attempts; i++) {
            try {
                return invokeSummary(toolName, rawOutput, todoGoal);
            } catch (Exception e) {
                lastError = e;
                log.warn("tool summary attempt {}/{} failed: tool={}, err={}", i + 1, attempts, toolName, e.getMessage());
            }
        }
        if (lastError != null) {
            log.warn("tool summary exhausted retries: tool={}", toolName);
        }
        return "";
    }

    private String invokeSummary(String toolName, String rawOutput, String todoGoal) {
        ChatModel model = buildSummaryModel();
        if (model == null) {
            throw new IllegalStateException("summary model unavailable");
        }
        String prompt = promptService.toolSummaryUserPrompt(nvl(todoGoal), nvl(toolName), nvl(rawOutput));
        long startedAt = System.currentTimeMillis();
        ChatResponse response = model.chat(
                new SystemMessage(promptService.toolSummarySystemPrompt()),
                new UserMessage(prompt)
        );
        long durationMs = Math.max(0L, System.currentTimeMillis() - startedAt);
        String summary = response == null || response.aiMessage() == null ? "" : nvl(response.aiMessage().text()).trim();
        recordObservability(response, durationMs, summary.isBlank() ? "empty summary" : null);
        if (summary.isBlank()) {
            throw new IllegalStateException("empty summary");
        }
        return summary;
    }

    private ChatModel buildSummaryModel() {
        SummaryRoute route = resolveSummaryRoute();
        if (route.endpoint().isBlank() || route.model().isBlank()) {
            return null;
        }
        try {
            AgentLlmResolver.ResolvedLlm resolved = aiServiceFactory.resolveLlm(route.endpoint(), route.model());
            return aiServiceFactory.buildChatModelWithTemperature(resolved, DEFAULT_TEMPERATURE);
        } catch (Exception e) {
            log.warn("build summary model failed: {}", e.getMessage());
            return null;
        }
    }

    private SummaryRoute resolveSummaryRoute() {
        var executionStage = AgentContext.getEffectiveExecutionStageConfig();
        var cfg = localConfigLoader.current();
        String endpoint = firstNonBlank(
                cfg.map(AgentLlmProperties::getTools).map(AgentLlmProperties.Tools::getResult)
                        .map(AgentLlmProperties.ToolResult::getSummaryEndpoint).orElse(""),
                executionStage != null && executionStage.isValid() ? executionStage.getEndpointName() : "",
                cfg.map(c -> c.getRuntime().getExecution()).map(AgentLlmProperties.Execution::getStaticFixEndpoint).orElse(""),
                cfg.map(AgentLlmProperties::getDefaultEndpoint).orElse("")
        );
        String model = firstNonBlank(
                cfg.map(AgentLlmProperties::getTools).map(AgentLlmProperties.Tools::getResult)
                        .map(AgentLlmProperties.ToolResult::getSummaryModel).orElse(""),
                executionStage != null && executionStage.isValid() ? executionStage.getModelName() : "",
                cfg.map(c -> c.getRuntime().getExecution()).map(AgentLlmProperties.Execution::getStaticFixModel).orElse(""),
                cfg.map(AgentLlmProperties::getDefaultModel).orElse("")
        );
        return new SummaryRoute(nvl(endpoint), nvl(model));
    }

    private int resolveSummaryMaxRetries() {
        return localConfigLoader.current()
                .map(AgentLlmProperties::getTools)
                .map(AgentLlmProperties.Tools::getSummary)
                .map(AgentLlmProperties.ToolSummary::getMaxRetries)
                .filter(v -> v != null && v >= 0)
                .orElse(1);
    }

    private void recordObservability(ChatResponse response, long durationMs, String errorMessage) {
        String runId = AgentContext.getRunId();
        if (runId == null || runId.isBlank()) {
            return;
        }
        TokenUsage tokenUsage = response == null ? null : response.tokenUsage();
        SummaryRoute route = resolveSummaryRoute();
        long now = System.currentTimeMillis();
        observabilityService.recordLlmCall(
                runId,
                PHASE,
                tokenUsage,
                durationMs,
                now - durationMs,
                now,
                route.endpoint(),
                route.model(),
                errorMessage,
                null,
                response == null || response.aiMessage() == null ? "" : nvl(response.aiMessage().text())
        );
    }

    private String firstNonBlank(String... candidates) {
        if (candidates == null) {
            return "";
        }
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.trim();
            }
        }
        return "";
    }

    private String nvl(String text) {
        return text == null ? "" : text;
    }

    private record SummaryRoute(String endpoint, String model) {
    }
}
