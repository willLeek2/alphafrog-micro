package world.willfrog.agent.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.config.StageLlmConfig;
import world.willfrog.agent.platform.context.AgentContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Lightweight LLM service for the financial MethodSpec resolver.
 *
 * <p>Mirrors the boundary of {@link SearchEvidenceJudgeService}: builds a dedicated
 * ChatModel via {@link AgentAiServiceFactory}, sets {@code phase/stage=finance_method_resolver},
 * injects a structured output schema, records observability, and restores the outer
 * execution context afterwards.</p>
 *
 * <p>The catalog text (compact method list) is supplied by the caller; this service never
 * silently truncates a catalog that exceeds the configured budget.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FinanceMethodResolverModelService {

    public static final String STAGE = "finance_method_resolver";
    static final int DEFAULT_MAX_TOKENS = 2048;

    private final ObjectMapper objectMapper;
    private final AgentAiServiceFactory aiServiceFactory;
    private final FinanceMethodResolverModelResolver resolverModelResolver;
    private final AgentPromptService promptService;
    private final AgentObservabilityService observabilityService;
    private final AgentLlmProperties llmProperties;

    /**
     * Resolve candidate financial methods for a raw natural-language query.
     *
     * @param query           the user's financial question (required)
     * @param context         optional natural-language context
     * @param resolverCatalog compact catalog text built from the canonical MethodSpec directory
     * @return a resolution result carrying status, candidates and the resolver tool-call id
     */
    public ResolutionResult resolve(String query, String context, String resolverCatalog) {
        String safeQuery = nvl(query);
        if (safeQuery.isBlank()) {
            return unavailable("EMPTY_QUERY", null);
        }
        String safeCatalog = nvl(resolverCatalog);

        String budgetError = checkCatalogBudget(safeCatalog);
        if (budgetError != null) {
            return unavailable(budgetError, null);
        }

        SelectionAndModel selected = selectModel();
        if (selected == null) {
            return unavailable("NO_RESOLVER_ROUTE", null);
        }

        String resolverToolCallId = nvl(AgentContext.getToolCallId());
        if (resolverToolCallId.isBlank()) {
            resolverToolCallId = "resolver-" + UUID.randomUUID();
        }

        String previousPhase = AgentContext.getPhase();
        String previousStage = AgentContext.getStage();
        AgentContext.StructuredOutputSpec previousSpec = AgentContext.getStructuredOutputSpec();
        String previousReasoning = AgentContext.getReasoningEffort();

        long startedAtMillis = System.currentTimeMillis();
        ChatResponse lastResponse = null;
        Exception lastError = null;

        try {
            AgentContext.clearLastRecordedLlmTraceId();
            AgentContext.setLlmCallRequestMeta(buildRequestMeta(selected, safeQuery));
            AgentContext.setPhase(AgentObservabilityService.PHASE_SUMMARIZING);
            AgentContext.setStage(STAGE);
            AgentContext.setStructuredOutputSpec(buildOutputSchema());

            List<ChatMessage> messages = buildMessages(safeQuery, context, safeCatalog);
            int maxAttempts = resolveMaxAttempts(selected.config());

            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    lastResponse = selected.model().chat(messages);
                    String text = lastResponse.aiMessage() == null ? "" : nvl(lastResponse.aiMessage().text());
                    JsonNode root = parseJson(text);
                    Validation validation = validate(root);
                    if (validation.valid()) {
                        long durationMs = System.currentTimeMillis() - startedAtMillis;
                        recordObservability(selected, durationMs, startedAtMillis, safeQuery,
                                null, lastResponse.tokenUsage(), text);
                        return new ResolutionResult(
                                root.path("status").asText("NO_ADVICE"),
                                parseCandidates(root.path("candidates")),
                                resolverToolCallId,
                                ""
                        );
                    }
                    lastError = new RuntimeException(validation.error());
                } catch (Exception e) {
                    lastError = e;
                    log.warn("Finance method resolver attempt {}/{} failed: {}", attempt, maxAttempts, e.getMessage());
                }
            }

            long durationMs = System.currentTimeMillis() - startedAtMillis;
            recordObservability(selected, durationMs, startedAtMillis, safeQuery,
                    lastError != null ? lastError.getMessage() : "VALIDATION_FAILED",
                    lastResponse == null ? null : lastResponse.tokenUsage(),
                    lastResponse == null ? null : (lastResponse.aiMessage() == null ? null : lastResponse.aiMessage().text()));
            return unavailable("RESOLVER_CALL_FAILED: " + (lastError != null ? lastError.getMessage() : "validation failed"),
                    resolverToolCallId);
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startedAtMillis;
            log.warn("Finance method resolver failed: {}", e.getMessage());
            recordObservability(selected, durationMs, startedAtMillis, safeQuery, nvl(e.getMessage()), null, null);
            return unavailable("RESOLVER_CALL_FAILED: " + nvl(e.getMessage()), resolverToolCallId);
        } finally {
            restoreContext(previousPhase, previousStage, previousSpec, previousReasoning);
        }
    }

    private SelectionAndModel selectModel() {
        Optional<FinanceMethodResolverModelResolver.ResolvedStageModel> resolvedStage = resolverModelResolver.resolve();
        if (resolvedStage.isPresent()) {
            StageLlmConfig cfg = resolvedStage.get().config();
            try {
                AgentLlmResolver.ResolvedLlm resolved = aiServiceFactory.resolveLlm(
                        cfg.getEndpointName(), cfg.getModelName());
                List<String> providerOrder = cfg.getProviderOrder() == null ? List.of() : cfg.getProviderOrder();
                int maxTokens = cfg.getMaxTokens() != null && cfg.getMaxTokens() > 0
                        ? cfg.getMaxTokens()
                        : DEFAULT_MAX_TOKENS;
                ChatModel model = aiServiceFactory.buildChatModelWithProviderOrderAndTemperature(
                        resolved, providerOrder, 0.0D, maxTokens);
                return new SelectionAndModel(
                        cfg,
                        cfg.getEndpointName(),
                        cfg.getModelName(),
                        resolvedStage.get().source(),
                        model);
            } catch (Exception e) {
                log.warn("Init finance method resolver from stage config failed: endpoint={}, model={}, err={}",
                        cfg.getEndpointName(), cfg.getModelName(), e.getMessage());
            }
        }
        return null;
    }

    private String checkCatalogBudget(String catalog) {
        AgentLlmProperties.FinanceMethodResolver config = llmProperties == null ? null : llmProperties.getFinanceMethodResolver();
        int maxBytes = config != null && config.getCatalogPromptMaxBytes() != null
                ? config.getCatalogPromptMaxBytes()
                : 8192;
        int maxTokens = config != null && config.getCatalogPromptMaxTokens() != null
                ? config.getCatalogPromptMaxTokens()
                : 2048;
        byte[] bytes = catalog.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length > maxBytes) {
            return "CATALOG_EXCEEDS_BYTES";
        }
        // conservative byte-per-token estimate: do not assume all languages are 4 bytes/token
        if (bytes.length > maxTokens * 2L) {
            return "CATALOG_EXCEEDS_TOKENS";
        }
        return null;
    }

    private int resolveMaxAttempts(StageLlmConfig cfg) {
        AgentLlmProperties.FinanceMethodResolver config = llmProperties == null ? null : llmProperties.getFinanceMethodResolver();
        if (config != null && config.getDefaultRoute() != null && config.getDefaultRoute().getMaxAttempts() != null) {
            return Math.max(1, config.getDefaultRoute().getMaxAttempts());
        }
        return 2;
    }

    private List<ChatMessage> buildMessages(String query, String context, String resolverCatalog) {
        String systemPrompt = promptService.financeMethodResolverSystemPrompt(resolverCatalog);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("query", query);
        payload.put("context", nvl(context));
        return List.of(
                new SystemMessage(systemPrompt),
                new UserMessage(writeJson(payload))
        );
    }

    private AgentContext.StructuredOutputSpec buildOutputSchema() {
        Map<String, Object> candidateSchema = new LinkedHashMap<>();
        candidateSchema.put("type", "object");
        Map<String, Object> candidateProps = new LinkedHashMap<>();
        candidateProps.put("methodId", Map.of("type", "string"));
        candidateProps.put("version", Map.of("type", "string"));
        candidateProps.put("specDigest", Map.of("type", "string"));
        candidateProps.put("matchReason", Map.of("type", "string"));
        candidateProps.put("unresolvedTerms", Map.of("type", "array", "items", Map.of("type", "string")));
        candidateProps.put("clarificationQuestions", Map.of("type", "array", "items", Map.of("type", "string")));
        candidateSchema.put("properties", candidateProps);
        candidateSchema.put("required", List.of("methodId", "version", "specDigest", "matchReason"));
        candidateSchema.put("additionalProperties", false);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("status", Map.of("type", "string",
                "enum", List.of("MATCHED", "AMBIGUOUS", "NEEDS_CLARIFICATION", "NO_ADVICE")));
        properties.put("candidates", Map.of("type", "array", "items", candidateSchema));
        schema.put("properties", properties);
        schema.put("required", List.of("status", "candidates"));
        schema.put("additionalProperties", false);

        return new AgentContext.StructuredOutputSpec(
                "finance_method_resolver_output", false, schema, false, true);
    }

    private Validation validate(JsonNode root) {
        if (root == null || !root.isObject()) {
            return Validation.invalid("RESOLVER_BAD_JSON");
        }
        JsonNode status = root.path("status");
        if (!status.isTextual()) {
            return Validation.invalid("RESOLVER_MISSING_STATUS");
        }
        String statusText = status.asText("");
        if (!List.of("MATCHED", "AMBIGUOUS", "NEEDS_CLARIFICATION", "NO_ADVICE").contains(statusText)) {
            return Validation.invalid("RESOLVER_INVALID_STATUS");
        }
        JsonNode candidates = root.path("candidates");
        if (!candidates.isArray()) {
            return Validation.invalid("RESOLVER_MISSING_CANDIDATES");
        }
        for (JsonNode candidate : candidates) {
            if (!candidate.isObject()) {
                return Validation.invalid("RESOLVER_CANDIDATE_NOT_OBJECT");
            }
            if (isBlank(candidate.path("methodId"))
                    || isBlank(candidate.path("version"))
                    || isBlank(candidate.path("specDigest"))
                    || isBlank(candidate.path("matchReason"))) {
                return Validation.invalid("RESOLVER_CANDIDATE_MISSING_FIELDS");
            }
        }
        return Validation.ok();
    }

    private List<MethodCandidate> parseCandidates(JsonNode candidates) {
        List<MethodCandidate> out = new ArrayList<>();
        if (!candidates.isArray()) {
            return out;
        }
        for (JsonNode candidate : candidates) {
            out.add(new MethodCandidate(
                    textOrEmpty(candidate.path("methodId")),
                    textOrEmpty(candidate.path("version")),
                    textOrEmpty(candidate.path("specDigest")),
                    textOrEmpty(candidate.path("matchReason")),
                    stringList(candidate.path("unresolvedTerms")),
                    stringList(candidate.path("clarificationQuestions"))
            ));
        }
        return out;
    }

    private List<String> stringList(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (!node.isArray()) {
            return out;
        }
        for (JsonNode item : node) {
            String text = item.asText("").trim();
            if (!text.isEmpty()) {
                out.add(text);
            }
        }
        return out;
    }

    private void recordObservability(SelectionAndModel selected,
                                     long durationMs,
                                     long startedAtMillis,
                                     String query,
                                     String errorMessage,
                                     TokenUsage tokenUsage,
                                     String responseText) {
        try {
            String runId = AgentContext.getRunId();
            if (runId == null || runId.isBlank()) {
                return;
            }
            Map<String, Object> meta = buildRequestMeta(selected, query);
            meta.put("resolver_duration_ms", durationMs);
            String providerTraceId = AgentContext.getAndClearLastRecordedLlmTraceId();
            if (providerTraceId != null) {
                observabilityService.enrichLlmTrace(runId, providerTraceId, errorMessage, responseText, meta);
                return;
            }
            Map<String, Object> requestSnapshot = new LinkedHashMap<>(meta);
            requestSnapshot.put("stage", STAGE);
            observabilityService.recordLlmCall(
                    runId,
                    AgentObservabilityService.PHASE_SUMMARIZING,
                    tokenUsage,
                    durationMs,
                    startedAtMillis,
                    startedAtMillis + durationMs,
                    selected.endpointName(),
                    selected.modelName(),
                    errorMessage,
                    requestSnapshot,
                    responseText);
        } catch (Exception e) {
            log.warn("Finance method resolver observability failed: {}", e.getMessage());
        }
    }

    private Map<String, Object> buildRequestMeta(SelectionAndModel selected, String query) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("stage", STAGE);
        meta.put("resolver_model_source", selected.source().name().toLowerCase());
        meta.put("resolver_model", selected.modelName());
        meta.put("resolver_endpoint", selected.endpointName());
        meta.put("query_preview", truncate(query, 200));
        return meta;
    }

    private void restoreContext(String previousPhase, String previousStage,
                                AgentContext.StructuredOutputSpec previousSpec, String previousReasoning) {
        if (previousPhase == null || previousPhase.isBlank()) {
            AgentContext.clearPhase();
        } else {
            AgentContext.setPhase(previousPhase);
        }
        if (previousStage == null || previousStage.isBlank()) {
            AgentContext.clearStage();
        } else {
            AgentContext.setStage(previousStage);
        }
        if (previousSpec == null) {
            AgentContext.clearStructuredOutputSpec();
        } else {
            AgentContext.setStructuredOutputSpec(previousSpec);
        }
        if (previousReasoning == null || previousReasoning.isBlank()) {
            AgentContext.clearReasoningEffort();
        } else {
            AgentContext.setReasoningEffort(previousReasoning);
        }
    }

    private JsonNode parseJson(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.trim();
        int fenceStart = trimmed.indexOf("```");
        if (fenceStart >= 0) {
            int firstLineEnd = trimmed.indexOf('\n', fenceStart);
            int contentStart = firstLineEnd < 0 ? fenceStart + 3 : firstLineEnd + 1;
            int fenceEnd = trimmed.indexOf("```", contentStart);
            if (fenceEnd > contentStart) {
                trimmed = trimmed.substring(contentStart, fenceEnd).trim();
                if (trimmed.regionMatches(true, 0, "json", 0, 4)) {
                    trimmed = trimmed.substring(4).trim();
                }
            }
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        String json = (start >= 0 && end > start) ? trimmed.substring(start, end + 1) : trimmed;
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private ResolutionResult unavailable(String reason, String resolverToolCallId) {
        return new ResolutionResult("RESOLVER_UNAVAILABLE", List.of(),
                resolverToolCallId == null ? "" : resolverToolCallId, reason);
    }

    private String nvl(String value) {
        return value == null ? "" : value;
    }

    private String truncate(String value, int maxChars) {
        String text = nvl(value);
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars);
    }

    private static boolean isBlank(JsonNode node) {
        return node == null || !node.isTextual() || node.asText("").isBlank();
    }

    private static String textOrEmpty(JsonNode node) {
        return node == null ? "" : node.asText("");
    }

    private record SelectionAndModel(
            StageLlmConfig config,
            String endpointName,
            String modelName,
            FinanceMethodResolverModelResolver.ModelSource source,
            ChatModel model) {
    }

    private record Validation(boolean valid, String error) {
        static Validation ok() {
            return new Validation(true, "");
        }

        static Validation invalid(String error) {
            return new Validation(false, error == null ? "" : error);
        }
    }

    public record ResolutionResult(
            String status,
            List<MethodCandidate> candidates,
            String resolverToolCallId,
            String unavailableReason) {
    }

    public record MethodCandidate(
            String methodId,
            String version,
            String specDigest,
            String matchReason,
            List<String> unresolvedTerms,
            List<String> clarificationQuestions) {
    }
}
