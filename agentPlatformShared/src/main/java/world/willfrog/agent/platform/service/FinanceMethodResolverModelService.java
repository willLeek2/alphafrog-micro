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
import world.willfrog.agent.platform.finance.FinanceMethodResolverClient;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * Lightweight LLM service for the financial MethodSpec resolver; the platform-side
 * implementation of {@link FinanceMethodResolverClient}.
 *
 * <p>Builds a dedicated ChatModel via {@link AgentAiServiceFactory} with
 * {@code phase/stage=finance_method_resolver} and temperature 0, assembles the final
 * system prompt from the actual AgentPromptService template (local file takes precedence
 * over classpath) plus the caller-supplied compact catalog fragment, and returns the raw
 * model JSON untouched. It performs only fail-closed technical pre-checks (request/response
 * byte bounds, candidate-count bound, strict single-JSON shape) and never silently truncates;
 * per-item semantic validation is the canonical tools-side validator's job.</p>
 *
 * <p>The outer execution context (phase, stage, structuredOutputSpec, reasoningEffort,
 * providerLlmTraceId, llmCallRequestMeta, lastRecordedLlmTraceId) is snapshotted on entry and
 * restored verbatim in {@code finally}; reasoningEffort is additionally cleared explicitly
 * before the resolver call because provider-routed chat models read it directly from
 * AgentContext. No synthetic resolver identity is generated: when the outer toolCallId is
 * absent the tools layer fails with TOOL_CALL_ID_MISSING before this service runs.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FinanceMethodResolverModelService implements FinanceMethodResolverClient {

    public static final String STAGE = "finance_method_resolver";
    static final int DEFAULT_MAX_TOKENS = 2048;
    private static final String CATALOG_PLACEHOLDER = "{{RESOLVER_CATALOG}}";

    private final ObjectMapper objectMapper;
    private final AgentAiServiceFactory aiServiceFactory;
    private final FinanceMethodResolverModelResolver resolverModelResolver;
    private final AgentPromptService promptService;
    private final AgentObservabilityService observabilityService;

    @Override
    public ResolverResult resolve(String query, String context, String catalogFragment) {
        String safeQuery = nvl(query);
        if (safeQuery.isBlank()) {
            return new TechnicalError(ErrorKind.CALL_FAILED, "query must not be blank");
        }
        String safeContext = nvl(context);
        String safeCatalog = nvl(catalogFragment);

        // 路由与边界读同一份 effective config（local 显式节优先，否则静态；见 resolver 的 javadoc），
        // 每次调用只取一次快照，避免同一次调用内路由和边界取到不同来源。
        AgentLlmProperties.FinanceMethodResolver bounds = resolverModelResolver.effectiveResolverConfig();
        int requestMaxBytes = positiveOr(bounds == null ? null : bounds.getRequestMaxBytes(), 8192);
        int responseMaxBytes = positiveOr(bounds == null ? null : bounds.getResponseMaxBytes(), 16384);
        int maxCandidates = positiveOr(bounds == null ? null : bounds.getMaxCandidates(), 8);

        String budgetError = checkCatalogBudget(safeCatalog, bounds);
        if (budgetError != null) {
            return new TechnicalError(ErrorKind.CATALOG_BUDGET_EXCEEDED, budgetError);
        }

        // 请求序列化失败必须技术失败，不能静默丢 query/context 继续。
        String userPayload;
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("query", safeQuery);
            payload.put("context", safeContext);
            userPayload = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return new TechnicalError(ErrorKind.CALL_FAILED,
                    "failed to serialize resolver request payload: " + nvl(e.getMessage()));
        }

        SelectionAndModel selected = selectModel(bounds);
        if (selected == null) {
            return new TechnicalError(ErrorKind.NO_ROUTE,
                    "no finance_method_resolver stage config and no enabled default route");
        }

        // 实际模板只解析一次：同一文本既用于摘要也用于最终 prompt，避免热加载竞态导致两者不一致。
        // 模板必须 fail-closed 地恰含 1 个 {{RESOLVER_CATALOG}}：0 个会静默不注入目录，多个会重复注入。
        String template = nvl(promptService.financeMethodResolverSystemPromptTemplate());
        if (template.isBlank()) {
            return new TechnicalError(ErrorKind.CALL_FAILED, "resolver system prompt template is blank");
        }
        int placeholderCount = countOccurrences(template, CATALOG_PLACEHOLDER);
        if (placeholderCount != 1) {
            return new TechnicalError(ErrorKind.CALL_FAILED,
                    "resolver system prompt template must contain exactly one " + CATALOG_PLACEHOLDER
                            + " placeholder, found " + placeholderCount);
        }
        String resolverPromptVersion = "sha256:" + sha256Hex(template.getBytes(StandardCharsets.UTF_8));
        String systemPrompt = template.replace(CATALOG_PLACEHOLDER, safeCatalog);

        // 请求字节上限钉实际送给 ChatModel 的两个 message content（render 后 systemPrompt + 序列化 user JSON）
        // 的 UTF-8 bytes 总和：catalog 有独立预算，但 local template 本身也可能异常巨大。只报 size/cap，不回显内容。
        int requestBytes = systemPrompt.getBytes(StandardCharsets.UTF_8).length
                + userPayload.getBytes(StandardCharsets.UTF_8).length;
        if (requestBytes > requestMaxBytes) {
            return new TechnicalError(ErrorKind.REQUEST_TOO_LARGE,
                    "resolver request message bytes " + requestBytes + " exceed configured limit " + requestMaxBytes);
        }

        String previousPhase = AgentContext.getPhase();
        String previousStage = AgentContext.getStage();
        AgentContext.StructuredOutputSpec previousSpec = AgentContext.getStructuredOutputSpec();
        String previousReasoning = AgentContext.getReasoningEffort();
        String previousProviderTraceId = AgentContext.peekProviderLlmTraceId();
        Map<String, Object> previousRequestMeta = copyMeta(AgentContext.peekLlmCallRequestMeta());
        String previousLastRecordedTraceId = AgentContext.peekLastRecordedLlmTraceId();

        long startedAtMillis = System.currentTimeMillis();
        ChatResponse lastResponse = null;
        ErrorKind lastKind = ErrorKind.CALL_FAILED;
        String lastError = "";

        try {
            AgentContext.setPhase(STAGE);
            AgentContext.setStage(STAGE);
            AgentContext.setStructuredOutputSpec(buildOutputSchema());
            // provider-routed ChatModel 直接读 AgentContext.reasoningEffort 写 reasoning 配置；
            // 轻量 resolver 必须显式清除，不能沿用外层执行模型的推理模式。
            AgentContext.clearReasoningEffort();
            AgentContext.clearLastRecordedLlmTraceId();
            // observability 记录会 consumeProviderLlmTraceId()；进入时清空外层值，
            // 避免 resolver 未写新 trace 时错记/重复归属外层 trace（finally 会逐字恢复）。
            AgentContext.setProviderLlmTraceId(null);
            AgentContext.setLlmCallRequestMeta(buildRequestMeta(selected, safeQuery));

            List<ChatMessage> messages = List.of(
                    new SystemMessage(systemPrompt),
                    new UserMessage(userPayload)
            );
            int maxAttempts = resolveMaxAttempts(bounds);

            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    lastResponse = selected.model().chat(messages);
                    String text = lastResponse.aiMessage() == null ? "" : nvl(lastResponse.aiMessage().text());
                    String rejection = checkResponse(text, responseMaxBytes, maxCandidates);
                    if (rejection == null) {
                        long durationMs = System.currentTimeMillis() - startedAtMillis;
                        recordObservability(selected, durationMs, startedAtMillis, safeQuery,
                                null, lastResponse.tokenUsage(), text);
                        return new Ok(text, selected.route(), resolverPromptVersion);
                    }
                    lastKind = ErrorKind.BAD_JSON;
                    lastError = rejection;
                    log.warn("Finance method resolver attempt {}/{} rejected response: {}", attempt, maxAttempts, rejection);
                } catch (Exception e) {
                    lastKind = isTimeout(e) ? ErrorKind.TIMEOUT : ErrorKind.CALL_FAILED;
                    lastError = nvl(e.getMessage());
                    log.warn("Finance method resolver attempt {}/{} failed: {}", attempt, maxAttempts, e.getMessage());
                }
            }

            long durationMs = System.currentTimeMillis() - startedAtMillis;
            recordObservability(selected, durationMs, startedAtMillis, safeQuery,
                    lastError.isBlank() ? "RESOLVER_ATTEMPTS_EXHAUSTED" : lastError,
                    lastResponse == null ? null : lastResponse.tokenUsage(),
                    lastResponse == null ? null
                            : (lastResponse.aiMessage() == null ? null : lastResponse.aiMessage().text()));
            return new TechnicalError(lastKind, lastError);
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startedAtMillis;
            log.warn("Finance method resolver failed: {}", e.getMessage());
            recordObservability(selected, durationMs, startedAtMillis, safeQuery, nvl(e.getMessage()), null, null);
            return new TechnicalError(isTimeout(e) ? ErrorKind.TIMEOUT : ErrorKind.CALL_FAILED, nvl(e.getMessage()));
        } finally {
            restoreContext(previousPhase, previousStage, previousSpec, previousReasoning,
                    previousProviderTraceId, previousRequestMeta, previousLastRecordedTraceId);
        }
    }

    /**
     * 响应侧 fail-closed 预检：字节上限、严格单一 JSON、候选数上限。
     * 通过返回 null；否则返回拒绝原因（不静默截断、不做逐项语义校验）。
     */
    private String checkResponse(String text, int responseMaxBytes, int maxCandidates) {
        if (text.isBlank()) {
            return "resolver response is blank";
        }
        int responseBytes = text.getBytes(StandardCharsets.UTF_8).length;
        if (responseBytes > responseMaxBytes) {
            return "resolver response UTF-8 bytes " + responseBytes + " exceed configured limit " + responseMaxBytes;
        }
        JsonNode root = parseStrictJson(text);
        if (root == null || !root.isObject()) {
            return "resolver response is not a strict single JSON object";
        }
        JsonNode candidates = root.get("candidates");
        if (candidates != null && candidates.isArray() && candidates.size() > maxCandidates) {
            return "resolver candidate count " + candidates.size() + " exceeds configured limit " + maxCandidates;
        }
        return null;
    }

    /**
     * 严格单一 JSON：拒绝 fence、前后缀说明文字与 trailing garbage
     * （FAIL_ON_TRAILING_TOKENS 等价严格预检）。解析失败返回 null。
     */
    private JsonNode parseStrictJson(String text) {
        try {
            return objectMapper.reader()
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(text);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 按 frozen 顺序尝试候选路由：dedicated stage 优先，其次 server default route；
     * 前一个候选构建失败继续尝试下一个，全部不可用才返回 null（NO_ROUTE）。严禁继承 execution 大模型。
     * default route 腿读取与本次调用边界相同的 effective config。
     */
    private SelectionAndModel selectModel(AgentLlmProperties.FinanceMethodResolver effectiveConfig) {
        for (FinanceMethodResolverModelResolver.ResolvedStageModel candidate
                : resolverModelResolver.resolveCandidates(effectiveConfig)) {
            StageLlmConfig cfg = candidate.config();
            try {
                AgentLlmResolver.ResolvedLlm resolved = aiServiceFactory.resolveLlm(
                        cfg.getEndpointName(), cfg.getModelName());
                List<String> providerOrder = cfg.getProviderOrder() == null ? List.of() : cfg.getProviderOrder();
                int maxTokens = cfg.getMaxTokens() != null && cfg.getMaxTokens() > 0
                        ? cfg.getMaxTokens()
                        : DEFAULT_MAX_TOKENS;
                ChatModel model = aiServiceFactory.buildChatModelWithProviderOrderAndTemperature(
                        resolved, providerOrder, 0.0D, maxTokens);
                RouteInfo route = new RouteInfo(
                        resolveProviderType(resolved),
                        resolveEffectiveEndpoint(resolved),
                        resolved.modelName());
                return new SelectionAndModel(cfg, candidate.source(), model, route);
            } catch (Exception e) {
                log.warn("Init finance method resolver from {} failed: endpoint={}, model={}, err={}",
                        candidate.source(), cfg.getEndpointName(), cfg.getModelName(), e.getMessage());
            }
        }
        return null;
    }

    /**
     * RouteInfo.provider 语义钉死为 HTTP 平台类型（不猜 providerOrder[0]，不回填 stage 别名，
     * 也不是 OpenRouter retry 后的实际 winning provider）。
     */
    private String resolveProviderType(AgentLlmResolver.ResolvedLlm resolved) {
        String baseUrl = resolved.baseUrl() == null ? "" : resolved.baseUrl().toLowerCase();
        if (isDashScope(resolved) || baseUrl.contains("dashscope")) {
            return "dashscope";
        }
        if (baseUrl.contains("openrouter.ai")) {
            return "openrouter";
        }
        return "openai-compatible";
    }

    /**
     * RouteInfo.endpoint = 解析后的真实 baseUrl；仅 DashScope 允许按 region 推导缺省端点，
     * 其他端点 blank baseUrl 一律 fail closed（抛错由候选循环捕获后继续/降级），不得回填假地址。
     */
    private String resolveEffectiveEndpoint(AgentLlmResolver.ResolvedLlm resolved) {
        if (resolved.baseUrl() != null && !resolved.baseUrl().isBlank()) {
            return resolved.baseUrl();
        }
        if (!isDashScope(resolved)) {
            throw new IllegalStateException(
                    "resolved endpoint has blank baseUrl and is not dashscope: " + resolved.endpointName());
        }
        String region = resolved.region() == null ? "" : resolved.region().trim().toLowerCase();
        return switch (region) {
            case "us" -> "https://dashscope-us.aliyuncs.com/compatible-mode/v1";
            case "cn" -> "https://dashscope.aliyuncs.com/compatible-mode/v1";
            case "singapore" -> "https://dashscope-intl.aliyuncs.com/compatible-mode/v1";
            default -> "https://dashscope-intl.aliyuncs.com/compatible-mode/v1";
        };
    }

    private boolean isDashScope(AgentLlmResolver.ResolvedLlm resolved) {
        String endpointName = resolved.endpointName() == null ? "" : resolved.endpointName().trim();
        return endpointName.equalsIgnoreCase("dashscope");
    }

    private String checkCatalogBudget(String catalog, AgentLlmProperties.FinanceMethodResolver config) {
        int maxBytes = positiveOr(config == null ? null : config.getCatalogPromptMaxBytes(), 8192);
        int maxTokens = positiveOr(config == null ? null : config.getCatalogPromptMaxTokens(), 2048);
        byte[] bytes = catalog.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxBytes) {
            return "catalog UTF-8 bytes " + bytes.length + " exceed configured limit " + maxBytes;
        }
        // conservative byte-per-token estimate: do not assume all languages are 4 bytes/token
        if (bytes.length > maxTokens * 2L) {
            return "catalog UTF-8 bytes " + bytes.length + " exceed conservative token estimate for limit " + maxTokens;
        }
        return null;
    }

    private int resolveMaxAttempts(AgentLlmProperties.FinanceMethodResolver config) {
        if (config != null && config.getDefaultRoute() != null && config.getDefaultRoute().getMaxAttempts() != null) {
            return Math.max(1, config.getDefaultRoute().getMaxAttempts());
        }
        return 2;
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
                    STAGE,
                    tokenUsage,
                    durationMs,
                    startedAtMillis,
                    startedAtMillis + durationMs,
                    selected.route().endpoint(),
                    selected.route().model(),
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
        meta.put("resolver_model", selected.route().model());
        meta.put("resolver_endpoint", selected.route().endpoint());
        meta.put("resolver_provider", selected.route().provider());
        meta.put("query_preview", truncate(query, 200));
        return meta;
    }

    private void restoreContext(String previousPhase, String previousStage,
                                AgentContext.StructuredOutputSpec previousSpec, String previousReasoning,
                                String previousProviderTraceId, Map<String, Object> previousRequestMeta,
                                String previousLastRecordedTraceId) {
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
        // setters 对 null/空白执行 remove，天然恢复"外层未设置"状态
        AgentContext.setProviderLlmTraceId(previousProviderTraceId);
        AgentContext.setLlmCallRequestMeta(previousRequestMeta);
        AgentContext.setLastRecordedLlmTraceId(previousLastRecordedTraceId);
    }

    private boolean isTimeout(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SocketTimeoutException
                    || current instanceof TimeoutException
                    || current instanceof HttpTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private Map<String, Object> copyMeta(Map<String, Object> meta) {
        return meta == null ? null : new LinkedHashMap<>(meta);
    }

    private int countOccurrences(String text, String token) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(token, index)) >= 0) {
            count++;
            index += token.length();
        }
        return count;
    }

    private int positiveOr(Integer value, int fallback) {
        return value != null && value > 0 ? value : fallback;
    }

    private String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
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

    private record SelectionAndModel(
            StageLlmConfig config,
            FinanceMethodResolverModelResolver.ModelSource source,
            ChatModel model,
            RouteInfo route) {
    }
}
