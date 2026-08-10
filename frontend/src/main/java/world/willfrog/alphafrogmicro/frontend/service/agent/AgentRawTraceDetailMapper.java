package world.willfrog.alphafrogmicro.frontend.service.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds sanitized full trace payloads. Inline and parts endpoints must use this same mapper.
 */
public final class AgentRawTraceDetailMapper {

    public static final String REDACTION_TEXT = "***REDACTED***";

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private AgentRawTraceDetailMapper() {
    }

    public static FullTracePayload buildLlmPayload(ObjectMapper objectMapper,
                                                   String runId,
                                                   String traceId,
                                                   String rawJson,
                                                   Map<String, Object> meta) {
        Map<String, Object> raw = parseMap(objectMapper, rawJson);
        Map<String, Object> sanitizedRaw = new LinkedHashMap<>();
        sanitizedRaw.put("type", "llm");
        sanitizedRaw.put("runId", firstNonBlank(str(raw.get("runId")), runId));
        sanitizedRaw.put("traceId", firstNonBlank(str(raw.get("traceId")), traceId));
        sanitizedRaw.put("httpRequest", scrub(raw.get("httpRequest")));
        sanitizedRaw.put("httpResponse", scrub(raw.get("httpResponse")));
        Map<String, Object> base64Fields = new LinkedHashMap<>();
        base64Fields.put("httpRequestBase64", sanitizedRaw.get("httpRequest"));
        base64Fields.put("httpResponseBase64", sanitizedRaw.get("httpResponse"));
        return buildPayload(objectMapper, sanitizedRaw, meta, base64Fields);
    }

    public static FullTracePayload buildToolPayload(ObjectMapper objectMapper,
                                                    String runId,
                                                    String traceId,
                                                    String detailJson) {
        Map<String, Object> raw = parseMap(objectMapper, detailJson);
        Map<String, Object> sanitizedRaw = new LinkedHashMap<>();
        sanitizedRaw.put("type", "tool");
        sanitizedRaw.put("runId", runId);
        sanitizedRaw.put("traceId", firstNonBlank(str(raw.get("traceId")), traceId));
        sanitizedRaw.put("params", scrub(raw.get("params")));
        sanitizedRaw.put("output", scrub(raw.get("output")));
        Map<String, Object> base64Fields = new LinkedHashMap<>();
        base64Fields.put("paramsBase64", sanitizedRaw.get("params"));
        base64Fields.put("outputBase64", sanitizedRaw.get("output"));
        return buildPayload(objectMapper, sanitizedRaw, Map.of(), base64Fields);
    }

    public static Map<String, Object> parseMeta(ObjectMapper objectMapper, String metaJson) {
        return parseMap(objectMapper, metaJson);
    }

    @SuppressWarnings("unchecked")
    private static FullTracePayload buildPayload(ObjectMapper objectMapper,
                                                 Map<String, Object> sanitizedRaw,
                                                 Map<String, Object> meta,
                                                 Map<String, Object> base64Fields) {
        Map<String, Object> fullDetail = new LinkedHashMap<>();
        fullDetail.put("type", sanitizedRaw.get("type"));
        fullDetail.put("runId", sanitizedRaw.get("runId"));
        fullDetail.put("traceId", sanitizedRaw.get("traceId"));
        fullDetail.put("encoding", "base64");
        fullDetail.put("redaction", REDACTION_TEXT);
        Object createdAtMillis = meta == null ? null : meta.get("createdAtMillis");
        Object expiresAtMillis = meta == null ? null : meta.get("expiresAtMillis");
        if (createdAtMillis != null) {
            fullDetail.put("createdAtMillis", createdAtMillis);
        }
        if (expiresAtMillis != null) {
            fullDetail.put("expiresAtMillis", expiresAtMillis);
        }
        for (Map.Entry<String, Object> entry : base64Fields.entrySet()) {
            if (entry.getValue() != null) {
                fullDetail.put(entry.getKey(), base64Json(objectMapper, entry.getValue()));
            }
        }
        byte[] thresholdBytes = writeBytes(objectMapper, sanitizedRaw);
        byte[] fullDetailBytes = writeBytes(objectMapper, fullDetail);
        return new FullTracePayload((Map<String, Object>) fullDetail, thresholdBytes, fullDetailBytes);
    }

    private static Object scrub(Object value) {
        return AgentExternalObservabilityMapper.sanitize(
                value,
                AgentExternalObservabilityMapper.View.ADMIN);
    }

    private static Map<String, Object> parseMap(ObjectMapper objectMapper, String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception e) {
            throw new IllegalArgumentException("malformed raw trace detail", e);
        }
    }

    private static String base64Json(ObjectMapper objectMapper, Object value) {
        return Base64.getEncoder().encodeToString(writeBytes(objectMapper, value));
    }

    private static byte[] writeBytes(ObjectMapper objectMapper, Object value) {
        try {
            return objectMapper.writeValueAsBytes(value == null ? Map.of() : value);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize sanitized trace detail", e);
        }
    }

    private static String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    public record FullTracePayload(
            Map<String, Object> fullDetail,
            byte[] thresholdBytes,
            byte[] fullDetailBytes
    ) {
    }
}
