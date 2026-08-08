package world.willfrog.agent.tools.finance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.tools.finance.FinanceMethodResolverClient.ResolverResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 金融方法建议工具：把自然语言问题映射为目录内的候选方法，并返回待澄清项。
 *
 * <p>本工具是只读建议工具，不执行计算，不受代码解释器开关限制。</p>
 */
@Component
@Slf4j
public class FinanceMethodTools {

    private static final String RESOLVER_SCHEMA_VERSION = "1";
    private static final String EXACT_ALIAS_FALLBACK_ROUTE = "{\"route\":\"exact_alias_fallback\"}";
    private static final int MAX_QUERY_BYTES = 4096;
    private static final int MAX_CONTEXT_BYTES = 4096;

    private final FinanceMethodSpecCatalog specCatalog;
    private final FinanceMethodResolverCatalog resolverCatalog;
    private final FinanceMethodResolutionValidator validator;
    private final FinanceMethodSuggestionRenderer renderer;
    private final FinanceMethodKnowledgeCatalog knowledgeCatalog;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    private FinanceMethodResolverClient resolverClient;

    @Autowired(required = false)
    private FinanceMethodResolutionSink resolutionSink;

    @Autowired(required = false)
    private FinanceTargetEnvironmentProvider targetEnvironmentProvider;

    public FinanceMethodTools(FinanceMethodSpecCatalog specCatalog,
                              FinanceMethodResolverCatalog resolverCatalog,
                              FinanceMethodResolutionValidator validator,
                              FinanceMethodSuggestionRenderer renderer,
                              FinanceMethodKnowledgeCatalog knowledgeCatalog,
                              ObjectMapper objectMapper) {
        this.specCatalog = specCatalog;
        this.resolverCatalog = resolverCatalog;
        this.validator = validator;
        this.renderer = renderer;
        this.knowledgeCatalog = knowledgeCatalog;
        this.objectMapper = objectMapper;
    }

    // Package-private setters for unit tests.
    void setResolverClient(FinanceMethodResolverClient resolverClient) {
        this.resolverClient = resolverClient;
    }

    void setResolutionSink(FinanceMethodResolutionSink resolutionSink) {
        this.resolutionSink = resolutionSink;
    }

    void setTargetEnvironmentProvider(FinanceTargetEnvironmentProvider targetEnvironmentProvider) {
        this.targetEnvironmentProvider = targetEnvironmentProvider;
    }

    @Tool("""
        金融方法建议工具。只读建议工具，不计算数值。

        输入：
          query   - 用户自然语言问题，必填。允许包含“这几年”“最近一段”“到现在”等模糊时间表达。
          context - 可选自然语言上下文（例如已取得的字段、数据说明）。

        输出：候选方法、每个方法的定义、执行所需输入、未解决边界与澄清问题。
        如果轻量解析器不可用或目录超出预算，会返回明确的技术错误码；
        此时执行模型仍可继续调用 executePython 做自定义计算。
        """)
    public String resolveFinanceMethods(
            @P(value = "用户自然语言问题，必填", required = true) String query,
            @P(value = "可选自然语言上下文") String context) {
        try {
            return resolveFinanceMethodsInternal(query, context);
        } catch (Exception e) {
            log.error("resolveFinanceMethods failed", e);
            return fail("TOOL_ERROR", "Failed to resolve finance methods: " + nvl(e.getMessage()));
        }
    }

    private String resolveFinanceMethodsInternal(String query, String context) {
        if (query == null || query.isBlank()) {
            return fail("INVALID_INPUT", "query is required");
        }
        if (query.getBytes(StandardCharsets.UTF_8).length > MAX_QUERY_BYTES
                || (context != null && context.getBytes(StandardCharsets.UTF_8).length > MAX_CONTEXT_BYTES)) {
            return fail("INPUT_TOO_LARGE", "query or context exceeds byte limit");
        }

        String resolverToolCallId = nvl(AgentContext.getToolCallId());
        if (resolverToolCallId.isBlank()) {
            return fail("TOOL_CALL_ID_MISSING", "resolverToolCallId is not available in AgentContext");
        }

        String systemPrompt = resolverCatalog.renderSystemPrompt();
        JsonNode modelOutput;
        String modelRouteJson;
        boolean usedExactAliasFallback;

        ResolverResult resolverResult = resolverClient == null
                ? new FinanceMethodResolverClient.TechnicalError(FinanceMethodResolverClient.ErrorKind.NO_ROUTE, "Resolver client not configured")
                : resolverClient.resolve(query, context, systemPrompt);

        if (resolverResult instanceof FinanceMethodResolverClient.Ok ok) {
            modelOutput = parseModelJson(ok.rawJson());
            modelRouteJson = serializeRouteInfo(ok.route());
            usedExactAliasFallback = false;
        } else if (resolverResult instanceof FinanceMethodResolverClient.TechnicalError err) {
            // 技术失败先走精确别名兜底
            Optional<JsonNode> fallback = exactAliasFallback(query, context);
            if (fallback.isPresent()) {
                modelOutput = fallback.get();
                modelRouteJson = EXACT_ALIAS_FALLBACK_ROUTE;
                usedExactAliasFallback = true;
            } else {
                return technicalError(err.kind());
            }
        } else {
            return fail("RESOLVER_BAD_MODEL_OUTPUT", "Unknown resolver result type");
        }

        FinanceMethodResolutionValidator.ValidationResult validation = validator.validate(modelOutput);
        if (!validation.isValid()) {
            // 校验失败也允许别名兜底一次
            Optional<JsonNode> fallback = exactAliasFallback(query, context);
            if (fallback.isPresent()) {
                validation = validator.validate(fallback.get());
                if (validation.isValid()) {
                    modelOutput = fallback.get();
                    modelRouteJson = EXACT_ALIAS_FALLBACK_ROUTE;
                    usedExactAliasFallback = true;
                }
            }
            if (!validation.isValid()) {
                return fail("RESOLVER_BAD_MODEL_OUTPUT",
                        validation.getErrorCode() + ": " + validation.getErrorMessage());
            }
        }

        String status = validation.getStatus();
        FinanceMethodSuggestionRenderer.TargetEnvironment targetEnv = readTargetEnvironment();
        List<Map<String, Object>> suggestions = renderSuggestions(
                validation.getCandidates(), status, modelOutput, targetEnv);

        // 有候选建议时才强制 runId 与 sink；NO_ADVICE 等空建议时允许不保存
        if (!suggestions.isEmpty()) {
            String runId = nvl(AgentContext.getRunId());
            if (runId.isBlank()) {
                return fail("RESOLVER_RUN_ID_MISSING", "Run ID is required to persist resolution snapshots");
            }
            if (resolutionSink == null) {
                return fail("RESOLVER_SINK_NOT_CONFIGURED", "Resolution sink is required to persist snapshots");
            }
            String todoId = nvl(AgentContext.getTodoId());
            List<FinanceMethodResolutionSnapshot> snapshots = buildSnapshots(
                    runId, resolverToolCallId, todoId, status, suggestions, modelRouteJson, targetEnv);
            try {
                resolutionSink.saveAll(snapshots);
            } catch (FinanceMethodResolutionSinkException sinkEx) {
                return fail("RESOLVER_SNAPSHOT_SAVE_FAILED", sinkEx.getMessage());
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("resolverToolCallId", resolverToolCallId);
        data.put("status", status);
        data.put("suggestions", suggestions);
        if (usedExactAliasFallback) {
            data.put("usedExactAliasFallback", true);
        }
        return ok(data);
    }

    private JsonNode parseModelJson(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(rawJson);
        } catch (Exception e) {
            log.warn("Failed to parse resolver model JSON: {}", rawJson, e);
            return null;
        }
    }

    private Optional<JsonNode> exactAliasFallback(String query, String context) {
        if (query == null) {
            return Optional.empty();
        }
        String normalized = normalizeForExactAlias(query);
        FinanceMethodSpec matchedSpec = null;
        for (FinanceMethodSpec spec : specCatalog.listAll()) {
            FinanceMethodSpec.FinanceResolverHints hints = spec.getResolverHints();
            if (hints == null) {
                continue;
            }
            for (String alias : hints.getAliases()) {
                if (alias.isBlank()) {
                    continue;
                }
                if (normalized.equals(normalizeForExactAlias(alias))) {
                    if (matchedSpec != null && !matchedSpec.getMethodId().equals(spec.getMethodId())) {
                        return Optional.empty(); // 跨方法多命中 → 不兜底
                    }
                    matchedSpec = spec;
                }
            }
        }
        if (matchedSpec == null) {
            return Optional.empty();
        }
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("status", "MATCHED");
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("methodId", matchedSpec.getMethodId());
        candidate.put("version", matchedSpec.getVersion());
        candidate.put("specDigest", matchedSpec.getSpecDigest());
        candidate.put("matchReason", "精确别名匹配：" + query);
        candidate.put("unresolvedTerms", Collections.emptyList());
        candidate.put("clarificationQuestions", Collections.emptyList());
        fallback.put("candidates", List.of(candidate));
        fallback.put("matchReason", "基于目录别名精确匹配");
        fallback.put("unresolvedTerms", Collections.emptyList());
        fallback.put("clarificationQuestions", Collections.emptyList());
        try {
            return Optional.of(objectMapper.valueToTree(fallback));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private String normalizeForExactAlias(String text) {
        if (text == null) {
            return "";
        }
        return text.trim().toLowerCase().replace('　', ' ');
    }

    private String technicalError(FinanceMethodResolverClient.ErrorKind kind) {
        String code = switch (kind) {
            case NO_ROUTE -> "RESOLVER_UNAVAILABLE";
            case TIMEOUT -> "RESOLVER_UNAVAILABLE";
            case BAD_JSON -> "RESOLVER_BAD_MODEL_OUTPUT";
            case CATALOG_BUDGET_EXCEEDED -> "RESOLVER_CATALOG_BUDGET_EXCEEDED";
        };
        String message = switch (kind) {
            case NO_ROUTE -> "Finance method resolver route is not available";
            case TIMEOUT -> "Finance method resolver timed out";
            case BAD_JSON -> "Finance method resolver returned invalid JSON";
            case CATALOG_BUDGET_EXCEEDED -> "Finance method catalog exceeds prompt budget";
        };
        return fail(code, message);
    }

    private List<Map<String, Object>> renderSuggestions(JsonNode candidates, String status, JsonNode modelOutput,
                                                        FinanceMethodSuggestionRenderer.TargetEnvironment targetEnv) {
        if (candidates == null || !candidates.isArray()) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (JsonNode cand : candidates) {
            String methodId = text(cand, "methodId");
            String version = text(cand, "version");
            String specDigest = text(cand, "specDigest");
            String matchReason = text(cand, "matchReason");
            List<String> unresolvedTerms = textList(cand, "unresolvedTerms");
            List<String> clarificationQuestions = textList(cand, "clarificationQuestions");
            result.add(renderer.render(methodId, version, specDigest, matchReason,
                    unresolvedTerms, clarificationQuestions, targetEnv));
        }
        return result;
    }

    private FinanceMethodSuggestionRenderer.TargetEnvironment readTargetEnvironment() {
        if (targetEnvironmentProvider == null) {
            return null;
        }
        return targetEnvironmentProvider.currentTargetEnvironment().orElse(null);
    }

    private List<FinanceMethodResolutionSnapshot> buildSnapshots(
            String runId,
            String resolverToolCallId,
            String todoId,
            String status,
            List<Map<String, Object>> suggestions,
            String modelRouteJson,
            FinanceMethodSuggestionRenderer.TargetEnvironment targetEnv) {
        if (runId.isBlank()) {
            return Collections.emptyList();
        }
        List<FinanceMethodResolutionSnapshot> snapshots = new ArrayList<>();
        String catalogDigest = resolverCatalog.getCatalogDigest();
        String resolverPromptVersion = resolverCatalog.getPromptVersion();
        String resolutionPayloadJson = serializeSafe(suggestions);
        String resolutionContentDigest = sha256(resolutionPayloadJson.getBytes(StandardCharsets.UTF_8));
        String targetEnvironmentId = targetEnv == null ? null : targetEnv.environmentId();
        String targetPackageApiJson = targetEnv == null ? null : serializePackageApis(targetEnv.packageApis());
        for (Map<String, Object> suggestion : suggestions) {
            String methodId = String.valueOf(suggestion.get("methodId"));
            String version = String.valueOf(suggestion.get("version"));
            String specDigest = String.valueOf(suggestion.get("specDigest"));
            String matchReason = String.valueOf(suggestion.get("matchReason"));
            String clarificationJson = serializeSafe(suggestion.get("clarificationQuestions"));
            snapshots.add(new FinanceMethodResolutionSnapshot(
                    runId,
                    resolverToolCallId,
                    todoId,
                    methodId,
                    version,
                    specDigest,
                    catalogDigest,
                    RESOLVER_SCHEMA_VERSION,
                    resolverPromptVersion,
                    modelRouteJson,
                    matchReason,
                    clarificationJson,
                    targetEnvironmentId,
                    targetPackageApiJson,
                    resolutionPayloadJson,
                    resolutionContentDigest,
                    Instant.now()
            ));
        }
        return snapshots;
    }

    private List<String> textList(JsonNode parent, String field) {
        JsonNode node = parent.get(field);
        if (node == null || !node.isArray()) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : node) {
            if (item.isTextual()) {
                result.add(item.asText());
            }
        }
        return result;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return "";
        }
        return value.asText("");
    }

    private String serializeRouteInfo(FinanceMethodResolverClient.RouteInfo route) {
        if (route == null) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("endpoint", route.endpoint());
        map.put("model", route.model());
        map.put("provider", route.provider());
        return serializeSafe(map);
    }

    private String serializePackageApis(List<FinanceMethodSuggestionRenderer.TargetEnvironment.PackageApi> packageApis) {
        if (packageApis == null) {
            return null;
        }
        List<Map<String, Object>> list = new ArrayList<>(packageApis.size());
        for (FinanceMethodSuggestionRenderer.TargetEnvironment.PackageApi api : packageApis) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", api.name());
            map.put("version", api.version());
            map.put("apiVersion", api.apiVersion());
            list.add(map);
        }
        return serializeSafe(list);
    }

    private String serializeSafe(Object value) {
        if (value == null) {
            return "null";
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "\"serialization_failed\"";
        }
    }

    private String ok(Map<String, Object> data) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", true);
        payload.put("tool", "resolveFinanceMethods");
        payload.put("data", data == null ? Map.of() : data);
        payload.put("error", null);
        return writeJson(payload);
    }

    private String fail(String code, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", false);
        payload.put("tool", "resolveFinanceMethods");
        payload.put("data", Map.of());
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", nvl(code));
        err.put("message", nvl(message));
        err.put("details", Map.of());
        payload.put("error", err);
        return writeJson(payload);
    }

    private String writeJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{\"ok\":false,\"tool\":\"resolveFinanceMethods\",\"error\":{\"code\":\"JSON_SERIALIZE_ERROR\",\"message\":\""
                    + escapeJson(nvl(e.getMessage())) + "\"}}";
        }
    }

    private String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return "sha256:" + java.util.HexFormat.of().formatHex(digest.digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private String nvl(String value) {
        return value == null ? "" : value;
    }

    private String escapeJson(String text) {
        return nvl(text).replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
