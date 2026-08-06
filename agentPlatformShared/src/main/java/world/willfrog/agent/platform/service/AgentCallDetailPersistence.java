package world.willfrog.agent.platform.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persists large LLM/tool fields in Redis detail blobs; observability traces keep index/summary only.
 */
public final class AgentCallDetailPersistence {

    public static final String SOURCE_CALL_DETAIL_REDIS = "call_detail_redis";
    public static final String SOURCE_OBSERVABILITY_SNAPSHOT = "observability_snapshot";

    /** Max chars kept in observability/snapshot trace index (full payload lives in Redis detail blob). */
    public static final int OBSERVABILITY_PREVIEW_MAX_CHARS = 1000;

    private AgentCallDetailPersistence() {
    }

    public static boolean hasPersistableDetailBlob(Map<String, Object> blob) {
        return blob != null && blob.size() > 2;
    }

    public static boolean hasPersistableLlmRawContentBlob(Map<String, Object> blob) {
        return blob != null && (blob.get("httpRequest") != null || blob.get("httpResponse") != null);
    }

    public static Map<String, Object> toLlmDetailBlob(AgentObservabilityService.LlmTrace trace) {
        Map<String, Object> blob = new LinkedHashMap<>();
        blob.put("type", "llm");
        blob.put("traceId", trace.getTraceId());
        putIfPresent(blob, "inputMessages", trace.getInputMessages());
        putIfPresent(blob, "outputText", trace.getOutputText());
        putIfPresent(blob, "reasoningText", trace.getReasoningText());
        putIfPresent(blob, "reasoningDetails", trace.getReasoningDetails());
        putIfPresent(blob, "responsePreview", trace.getResponsePreview());
        return blob;
    }

    public static Map<String, Object> toLlmRawContentBlob(String runId, AgentObservabilityService.LlmTrace trace) {
        Map<String, Object> blob = new LinkedHashMap<>();
        blob.put("type", "llm_raw_http");
        blob.put("runId", runId);
        blob.put("traceId", trace.getTraceId());
        putIfPresent(blob, "httpRequest", trace.getHttpRequest());
        putIfPresent(blob, "httpResponse", trace.getHttpResponse());
        return blob;
    }

    public static Map<String, Object> toToolDetailBlob(AgentObservabilityService.ToolTrace trace) {
        Map<String, Object> blob = new LinkedHashMap<>();
        blob.put("type", "tool");
        blob.put("traceId", trace.getTraceId());
        putIfPresent(blob, "params", trace.getParams());
        putIfPresent(blob, "output", trace.getOutput());
        putIfPresent(blob, "decisionExcerpt", trace.getDecisionExcerpt());
        putIfPresent(blob, "cacheKey", trace.getCacheKey());
        return blob;
    }

    public static void scrubLlmTrace(AgentObservabilityService.LlmTrace trace) {
        scrubLlmTrace(trace, false);
    }

    public static void scrubLlmTrace(AgentObservabilityService.LlmTrace trace, boolean detailBlobStored) {
        if (trace == null) {
            return;
        }
        String preview = truncatePreview(firstNonBlank(trace.getResponsePreview(), trace.getOutputText()));
        trace.setResponsePreview(preview);
        trace.setInputMessages(null);
        trace.setOutputText(null);
        trace.setReasoningText(null);
        trace.setReasoningDetails(null);
        trace.setHttpRequest(null);
        trace.setHttpResponse(null);
        trace.setCurlCommand(null);
        trace.setAttempts(null);
        trace.setRequest(null);
        trace.setDetailBlobStored(detailBlobStored);
    }

    public static void scrubToolTrace(AgentObservabilityService.ToolTrace trace) {
        scrubToolTrace(trace, false);
    }

    public static void scrubToolTrace(AgentObservabilityService.ToolTrace trace, boolean detailBlobStored) {
        if (trace == null) {
            return;
        }
        String preview = truncatePreview(firstNonBlank(trace.getOutputPreview(), trace.getOutput()));
        trace.setOutputPreview(preview);
        trace.setOutput(null);
        trace.setParams(null);
        trace.setCacheKey(null);
        trace.setDecisionExcerpt(null);
        trace.setDetailBlobStored(detailBlobStored);
    }

    @SuppressWarnings("unchecked")
    public static void scrubObservabilityMap(Map<String, Object> observabilityMap) {
        if (observabilityMap == null) {
            return;
        }
        Object diagnosticsObj = observabilityMap.get("diagnostics");
        if (!(diagnosticsObj instanceof Map<?, ?> rawDiagnostics)) {
            return;
        }
        Map<String, Object> diagnostics = (Map<String, Object>) rawDiagnostics;
        scrubTraceList(diagnostics, "llmTraces");
        scrubTraceList(diagnostics, "toolTraces");
    }

    @SuppressWarnings("unchecked")
    private static void scrubTraceList(Map<String, Object> diagnostics, String listKey) {
        Object tracesObj = diagnostics.get(listKey);
        if (!(tracesObj instanceof List<?> traces)) {
            return;
        }
        for (Object item : traces) {
            if (!(item instanceof Map<?, ?> rawTrace)) {
                continue;
            }
            Map<String, Object> trace = (Map<String, Object>) rawTrace;
            if ("llmTraces".equals(listKey)) {
                scrubLlmTraceMap(trace);
            } else {
                scrubToolTraceMap(trace);
            }
        }
    }

    private static void scrubLlmTraceMap(Map<String, Object> trace) {
        Object preview = trace.get("responsePreview");
        if (preview == null) {
            preview = trace.get("outputText");
        }
        trace.remove("inputMessages");
        trace.remove("outputText");
        trace.remove("reasoningText");
        trace.remove("reasoningDetails");
        trace.remove("httpRequest");
        trace.remove("httpResponse");
        trace.remove("curlCommand");
        trace.remove("attempts");
        trace.remove("request");
        String truncated = truncatePreview(preview == null ? null : String.valueOf(preview));
        if (truncated != null) {
            trace.put("responsePreview", truncated);
        } else {
            trace.remove("responsePreview");
        }
    }

    private static void scrubToolTraceMap(Map<String, Object> trace) {
        Object preview = trace.get("outputPreview");
        if (preview == null) {
            preview = trace.get("output");
        }
        trace.remove("params");
        trace.remove("cacheKey");
        trace.remove("decisionExcerpt");
        trace.remove("output");
        String truncated = truncatePreview(preview == null ? null : String.valueOf(preview));
        if (truncated != null) {
            trace.put("outputPreview", truncated);
        } else {
            trace.remove("outputPreview");
        }
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof String text && text.isBlank()) {
            return;
        }
        if (value instanceof List<?> list && list.isEmpty()) {
            return;
        }
        if (value instanceof Map<?, ?> map && map.isEmpty()) {
            return;
        }
        target.put(key, value);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    static String truncatePreview(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        if (text.length() <= OBSERVABILITY_PREVIEW_MAX_CHARS) {
            return text;
        }
        return text.substring(0, OBSERVABILITY_PREVIEW_MAX_CHARS) + "...";
    }
}
