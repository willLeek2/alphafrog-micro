package world.willfrog.alphafrogmicro.frontend.service.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentCallDetailResponse;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentCallDetailResponse.DetailLimits;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentCallDetailResponse.DetailLlm;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentCallDetailResponse.DetailMetrics;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentCallDetailResponse.DetailTool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Maps observability diagnostics traces to user-safe call detail (Step 1).
 */
public final class AgentCallDetailMapper {

    public static final int PREVIEW_MAX_CHARS = 2000;

    /** Safe param keys per tool for user-facing detail (unknown tools expose count only). */
    private static final Map<String, List<String>> PARAM_FIELD_WHITELIST = Map.of(
            "searchAssetInfo", List.of("query"),
            "searchWeb", List.of("query", "backend", "maxResults", "limit")
    );

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AgentCallDetailMapper() {
    }

    public static Optional<Map<String, Object>> findLlmTrace(Map<String, Object> diagnostics, String llmCallId) {
        return findTraceById(diagnostics, "llmTraces", llmCallId);
    }

    public static Optional<Map<String, Object>> findToolTrace(Map<String, Object> diagnostics, String toolCallId) {
        return findTraceById(diagnostics, "toolTraces", toolCallId);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseDiagnostics(String observabilityJson) throws Exception {
        if (observabilityJson == null || observabilityJson.isBlank()) {
            return Map.of();
        }
        Map<String, Object> obs = MAPPER.readValue(observabilityJson, new TypeReference<>() {});
        Object diagnostics = obs.get("diagnostics");
        if (diagnostics instanceof Map<?, ?> diag) {
            return (Map<String, Object>) diag;
        }
        return Map.of();
    }

    public static AgentCallDetailResponse unavailable(String type, String id, String runId) {
        return AgentCallDetailResponse.builder()
                .type(type)
                .detailKind(AgentCallDetailResponse.KIND_UNAVAILABLE)
                .source(AgentCallDetailResponse.SOURCE_OBSERVABILITY)
                .id(id)
                .runId(runId)
                .limits(limits(false))
                .build();
    }

    public static AgentCallDetailResponse expired(String type, String id, String runId, Map<String, Object> trace) {
        return AgentCallDetailResponse.builder()
                .type(type)
                .detailKind(AgentCallDetailResponse.KIND_EXPIRED)
                .source(AgentCallDetailResponse.SOURCE_OBSERVABILITY)
                .id(id)
                .runId(runId)
                .todoId(emptyToNull(str(trace.get("todoId"))))
                .todoSequence(intOrNull(trace.get("todoSequence")))
                .phase(emptyToNull(str(trace.get("phase"))))
                .stage(emptyToNull(str(trace.get("stage"))))
                .time(emptyToNull(str(trace.get("time"))))
                .durationMs(longOrNull(trace.get("durationMs")))
                .status("unknown")
                .summary("Detail expired or no longer available")
                .limits(limits(false))
                .build();
    }

    public static AgentCallDetailResponse resolveLlmDetail(
            Map<String, Object> trace,
            String llmCallId,
            String runId,
            Optional<String> detailBlobJson) {
        return resolveLlmDetail(trace, llmCallId, runId, detailBlobJson, false);
    }

    /**
     * Step 1 safe detail with optional thinking/reasoning opt-in.
     *
     * @param includeThinking 调用方显式请求 thinking 内容时传 true；为 false 时 thinking 字段一律不出，
     *                        与 Step 1 普通用户契约一致。
     */
    public static AgentCallDetailResponse resolveLlmDetail(
            Map<String, Object> trace,
            String llmCallId,
            String runId,
            Optional<String> detailBlobJson,
            boolean includeThinking) {
        if (detailBlobJson.isPresent()) {
            String reasoningContent = null;
            Boolean reasoningUnavailable = null;
            if (includeThinking) {
                Map<String, Object> blob = parseDetailBlob(detailBlobJson.get());
                reasoningContent = extractReasoningContent(blob);
                reasoningContent = AgentExternalObservabilityMapper.safePreview(
                        reasoningContent,
                        Integer.MAX_VALUE);
                if (reasoningContent == null) {
                    // blob 存在但没有 reasoningText（典型场景：非 thinking 模型没存该字段）
                    reasoningUnavailable = Boolean.TRUE;
                }
            }
            return fromLlmTrace(
                    trace, llmCallId, runId,
                    AgentCallDetailResponse.SOURCE_CALL_DETAIL_REDIS,
                    reasoningContent, reasoningUnavailable);
        }
        if (isDetailBlobStored(trace)) {
            // 整份 blob 过期：thinking 不可用，但 detailKind 不应被标 EXPIRED（thinking 是可选增强）。
            if (includeThinking) {
                return fromLlmTrace(
                        trace, llmCallId, runId,
                        AgentCallDetailResponse.SOURCE_OBSERVABILITY,
                        null, Boolean.TRUE);
            }
            return expired("llm", llmCallId, runId, trace);
        }
        if (includeThinking) {
            return fromLlmTrace(
                    trace, llmCallId, runId,
                    AgentCallDetailResponse.SOURCE_OBSERVABILITY,
                    null, Boolean.TRUE);
        }
        return fromLlmTrace(trace, llmCallId, runId, AgentCallDetailResponse.SOURCE_OBSERVABILITY);
    }

    /**
     * 从 detail blob 中提取 thinking/reasoning 文本。
     * 优先取 {@code reasoningText}（与 {@code AgentCallDetailPersistence.toLlmDetailBlob} 一致）；
     * 空字符串视为缺。
     */
    static String extractReasoningContent(Map<String, Object> blob) {
        if (blob == null || blob.isEmpty()) {
            return null;
        }
        Object value = blob.get("reasoningText");
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    public static AgentCallDetailResponse resolveToolDetail(
            Map<String, Object> trace,
            String toolCallId,
            String runId,
            Optional<String> detailBlobJson) {
        if (detailBlobJson.isPresent()) {
            Map<String, Object> blob = parseDetailBlob(detailBlobJson.get());
            return fromToolTraceWithBlob(trace, blob, toolCallId, runId);
        }
        if (isDetailBlobStored(trace)) {
            return expired("tool", toolCallId, runId, trace);
        }
        return fromToolTrace(trace, toolCallId, runId);
    }

    public static AgentCallDetailResponse fromLlmTrace(Map<String, Object> trace, String llmCallId, String runId) {
        return fromLlmTrace(trace, llmCallId, runId, AgentCallDetailResponse.SOURCE_OBSERVABILITY, null, null);
    }

    public static AgentCallDetailResponse fromLlmTrace(
            Map<String, Object> trace, String llmCallId, String runId, String source) {
        return fromLlmTrace(trace, llmCallId, runId, source, null, null);
    }

    /**
     * Overload with optional thinking/reasoning opt-in fields.
     *
     * @param reasoningContent   仅在 {@code includeThinking=true} 且 blob 含 {@code reasoningText} 时回传；
     *                           null 时不出 {@code reasoningContent} 字段（保持 Step 1 普通用户契约）。
     * @param reasoningUnavailable 仅在 {@code includeThinking=true} 但 blob 缺/无 {@code reasoningText} 时为 true；
     *                           null 时不出 {@code reasoningUnavailable} 字段。
     */
    public static AgentCallDetailResponse fromLlmTrace(
            Map<String, Object> trace, String llmCallId, String runId, String source,
            String reasoningContent, Boolean reasoningUnavailable) {
        String model = str(trace.get("model"));
        boolean hasError = bool(trace.get("hasError"));
        String error = emptyToNull(str(trace.get("error")));
        Long inputTokens = nullableLong(trace.get("inputTokens"));
        Long outputTokens = nullableLong(trace.get("outputTokens"));
        String summary = buildLlmSummary(model, hasError, error);

        PreviewResult summaryPreview = limitText(summary, PREVIEW_MAX_CHARS);

        DetailLlm.DetailLlmBuilder llmBuilder = DetailLlm.builder()
                .model(emptyToNull(AgentExternalObservabilityMapper.safePreview(model, PREVIEW_MAX_CHARS)));
        if (reasoningContent != null) {
            llmBuilder.reasoningContent(reasoningContent);
        }

        return AgentCallDetailResponse.builder()
                .type("llm")
                .detailKind(summaryPreview.detailKind())
                .source(source)
                .id(llmCallId)
                .runId(runId)
                .todoId(emptyToNull(str(trace.get("todoId"))))
                .todoSequence(intOrNull(trace.get("todoSequence")))
                .phase(emptyToNull(str(trace.get("phase"))))
                .stage(emptyToNull(str(trace.get("stage"))))
                .time(emptyToNull(str(trace.get("time"))))
                .durationMs(longOrNull(trace.get("durationMs")))
                .status(hasError ? "failed" : "success")
                .summary(summaryPreview.text())
                .metrics(buildMetrics(inputTokens, outputTokens, trace.get("actualCost")))
                .llm(llmBuilder.build())
                .limits(mergeLimits(summaryPreview.truncated()))
                .reasoningUnavailable(reasoningUnavailable)
                .build();
    }

    public static AgentCallDetailResponse fromToolTrace(Map<String, Object> trace, String toolCallId, String runId) {
        return fromToolTraceWithBlob(trace, Map.of(), toolCallId, runId, AgentCallDetailResponse.SOURCE_OBSERVABILITY);
    }

    public static AgentCallDetailResponse fromToolTraceWithBlob(
            Map<String, Object> trace,
            Map<String, Object> blob,
            String toolCallId,
            String runId) {
        return fromToolTraceWithBlob(trace, blob, toolCallId, runId, AgentCallDetailResponse.SOURCE_CALL_DETAIL_REDIS);
    }

    private static AgentCallDetailResponse fromToolTraceWithBlob(
            Map<String, Object> trace,
            Map<String, Object> blob,
            String toolCallId,
            String runId,
            String source) {
        String toolName = str(trace.get("toolName"));
        boolean success = bool(trace.get("success"));
        String error = emptyToNull(str(trace.get("error")));

        Object paramsSource = blob.containsKey("params") ? blob.get("params") : trace.get("params");
        String paramsSummary = summarizeParams(toolName, paramsSource);
        PreviewResult paramsPreview = limitText(paramsSummary, PREVIEW_MAX_CHARS);

        String outputRaw = blob.containsKey("output")
                ? str(blob.get("output"))
                : firstNonEmpty(str(trace.get("output")), str(trace.get("outputPreview")));
        String outputPreview = summarizeToolOutput(toolName, outputRaw);
        PreviewResult outputResult = limitText(outputPreview, PREVIEW_MAX_CHARS);

        String summary = buildToolSummary(toolName, success, error);
        PreviewResult summaryPreview = limitText(summary, PREVIEW_MAX_CHARS);

        boolean truncated = paramsPreview.truncated() || outputResult.truncated() || summaryPreview.truncated();
        String detailKind = truncated
                ? AgentCallDetailResponse.KIND_TRUNCATED
                : AgentCallDetailResponse.KIND_AVAILABLE;

        return AgentCallDetailResponse.builder()
                .type("tool")
                .detailKind(detailKind)
                .source(source)
                .id(toolCallId)
                .runId(runId)
                .todoId(emptyToNull(str(trace.get("todoId"))))
                .todoSequence(intOrNull(trace.get("todoSequence")))
                .phase(emptyToNull(str(trace.get("phase"))))
                .stage(emptyToNull(str(trace.get("stage"))))
                .time(emptyToNull(str(trace.get("time"))))
                .durationMs(longOrNull(trace.get("durationMs")))
                .status(success ? "success" : "failed")
                .summary(summaryPreview.text())
                .tool(DetailTool.builder()
                        .name(emptyToNull(AgentExternalObservabilityMapper.safePreview(toolName, PREVIEW_MAX_CHARS)))
                        .paramsSummary(paramsPreview.text())
                        .outputPreview(outputResult.text())
                        .build())
                .limits(mergeLimits(truncated))
                .build();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseDetailBlob(String json) {
        try {
            return MAPPER.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static boolean isDetailBlobStored(Map<String, Object> trace) {
        Object flag = trace.get("detailBlobStored");
        return flag instanceof Boolean b && b;
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static Optional<Map<String, Object>> findTraceById(
            Map<String, Object> diagnostics, String listKey, String callId) {
        if (callId == null || callId.isBlank() || diagnostics == null) {
            return Optional.empty();
        }
        Object tracesObj = diagnostics.get(listKey);
        if (!(tracesObj instanceof List<?> traces)) {
            return Optional.empty();
        }
        for (Object item : traces) {
            if (item instanceof Map<?, ?> raw) {
                @SuppressWarnings("unchecked")
                Map<String, Object> trace = (Map<String, Object>) raw;
                if (callId.equals(str(trace.get("traceId")))) {
                    return Optional.of(trace);
                }
            }
        }
        return Optional.empty();
    }

    private static String buildLlmSummary(String model, boolean hasError, String error) {
        if (hasError && error != null && !error.isBlank()) {
            return "LLM " + (model.isBlank() ? "call" : model) + " failed: " + error;
        }
        if (!model.isBlank()) {
            return "LLM call " + model + " completed";
        }
        return "LLM call completed";
    }

    private static String buildToolSummary(String toolName, boolean success, String error) {
        String label = toolName.isBlank() ? "Tool" : "Tool " + toolName;
        if (!success && error != null && !error.isBlank()) {
            return label + " failed: " + error;
        }
        return label + (success ? " completed" : " failed");
    }

    @SuppressWarnings("unchecked")
    static String summarizeParams(String toolName, Object paramsObj) {
        if (!(paramsObj instanceof Map<?, ?> raw) || raw.isEmpty()) {
            return "";
        }
        Map<String, Object> params = (Map<String, Object>) raw;
        List<String> allowedKeys = PARAM_FIELD_WHITELIST.get(toolName);
        if (allowedKeys == null) {
            return "parameterCount=" + params.size();
        }
        List<String> parts = new ArrayList<>();
        for (String key : allowedKeys) {
            Object value = params.get(key);
            if (value == null) {
                continue;
            }
            String rendered = String.valueOf(value).trim();
            if (rendered.isBlank()) {
                continue;
            }
            if (rendered.length() > 120) {
                rendered = rendered.substring(0, 120) + "...";
            }
            parts.add(key + "=" + rendered);
        }
        return String.join(", ", parts);
    }

    static String summarizeToolOutput(String toolName, String output) {
        if (output == null || output.isBlank()) {
            return "";
        }
        String trimmed = output.trim();
        if ("searchAssetInfo".equals(toolName)) {
            String structured = trySearchAssetInfoSummary(trimmed);
            if (structured != null) {
                return structured;
            }
        }
        return prettyOrRawPreview(trimmed);
    }

    private static String trySearchAssetInfoSummary(String jsonText) {
        try {
            Map<String, Object> root = MAPPER.readValue(jsonText, new TypeReference<>() {});
            List<String> hits = new ArrayList<>();
            collectAssetHits(root, hits, 5);
            if (!hits.isEmpty()) {
                return "hits: " + String.join("; ", hits);
            }
            Object total = root.get("total");
            if (total != null) {
                return "total=" + total;
            }
        } catch (Exception ignored) {
            // fall through
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static void collectAssetHits(Object node, List<String> hits, int max) {
        if (hits.size() >= max || node == null) {
            return;
        }
        if (node instanceof List<?> list) {
            for (Object item : list) {
                collectAssetHits(item, hits, max);
                if (hits.size() >= max) {
                    return;
                }
            }
            return;
        }
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> m = (Map<String, Object>) map;
            String code = firstNonBlank(m, "code", "symbol", "tsCode", "stockCode");
            String name = firstNonBlank(m, "name", "assetName", "title");
            if (!code.isBlank() || !name.isBlank()) {
                hits.add((code.isBlank() ? "" : code) + (name.isBlank() ? "" : " " + name).trim());
            }
            for (Object value : m.values()) {
                collectAssetHits(value, hits, max);
                if (hits.size() >= max) {
                    return;
                }
            }
        }
    }

    private static String firstNonBlank(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null) {
                String text = String.valueOf(value).trim();
                if (!text.isBlank()) {
                    return text;
                }
            }
        }
        return "";
    }

    private static String prettyOrRawPreview(String text) {
        if (text.startsWith("{") || text.startsWith("[")) {
            try {
                Object parsed = MAPPER.readValue(text, Object.class);
                return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(parsed);
            } catch (Exception ignored) {
                return text;
            }
        }
        return text;
    }

    private static DetailMetrics buildMetrics(Long inputTokens, Long outputTokens, Object actualCost) {
        Long total = null;
        if (inputTokens != null || outputTokens != null) {
            total = (inputTokens == null ? 0L : inputTokens) + (outputTokens == null ? 0L : outputTokens);
        }
        Double cost = null;
        if (actualCost instanceof Number n) {
            cost = n.doubleValue();
        }
        if (inputTokens == null && outputTokens == null && cost == null) {
            return null;
        }
        return DetailMetrics.builder()
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .totalTokens(total)
                .actualCost(cost)
                .build();
    }

    private static PreviewResult limitText(String text, int maxChars) {
        if (text == null) {
            return new PreviewResult("", false, AgentCallDetailResponse.KIND_AVAILABLE);
        }
        String scrubbed = AgentExternalObservabilityMapper.safePreview(text, Integer.MAX_VALUE);
        if (scrubbed == null) {
            return new PreviewResult("", false, AgentCallDetailResponse.KIND_AVAILABLE);
        }
        if (scrubbed.length() <= maxChars) {
            return new PreviewResult(scrubbed, false, AgentCallDetailResponse.KIND_AVAILABLE);
        }
        return new PreviewResult(
                scrubbed.substring(0, maxChars) + "...",
                true,
                AgentCallDetailResponse.KIND_TRUNCATED);
    }

    private static DetailLimits limits(boolean truncated) {
        return DetailLimits.builder()
                .previewMaxChars(PREVIEW_MAX_CHARS)
                .truncated(truncated)
                .build();
    }

    private static DetailLimits mergeLimits(boolean truncated) {
        return limits(truncated);
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static boolean bool(Object value) {
        return value instanceof Boolean b && b;
    }

    private static Long nullableLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        return null;
    }

    private static Long longOrNull(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        return null;
    }

    private static Integer intOrNull(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        return null;
    }

    record PreviewResult(String text, boolean truncated, String detailKind) {
    }
}
