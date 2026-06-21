package world.willfrog.alphafrogmicro.frontend.service.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Builds sanitized full trace payloads. Inline and parts endpoints must use this same mapper.
 */
public final class AgentRawTraceDetailMapper {

    public static final String REDACTION_TEXT = "***REDACTED***";

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final Pattern SENSITIVE_KEY = Pattern.compile(
            "(?i)(authorization|api[-_]?key|secret|password|access[-_]?token|token|credential|cookie|set-cookie|x-api-key)");
    private static final Pattern BEARER_VALUE = Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._\\-+/=]+");
    private static final Pattern BASIC_VALUE = Pattern.compile("(?i)(basic\\s+)[A-Za-z0-9._\\-+/=]+");
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)([\"']?(?:api_?key|secret|password|access_?token|token)[\"']?\\s*[:=]\\s*[\"']?)[A-Za-z0-9._\\-+/=]+([\"']?)");
    private static final Pattern URL_SECRET_PARAM = Pattern.compile(
            "(?i)([?&][^=&\\s]*(?:api_?key|secret|password|access_?token|token|key)[^=]*=)[^&#\\s]+");
    private static final Pattern CREDENTIAL_SHAPED = Pattern.compile(
            "(?i)\\b(sk|ak|pk|rk|ghp|xox[baprs]|ya29)_[A-Za-z0-9._\\-]{12,}\\b|\\bsk-[A-Za-z0-9._\\-]{12,}\\b");

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
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (SENSITIVE_KEY.matcher(key).find()) {
                    out.put(key, REDACTION_TEXT);
                } else {
                    out.put(key, scrub(entry.getValue()));
                }
            }
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object item : list) {
                out.add(scrub(item));
            }
            return out;
        }
        if (value instanceof String text) {
            return scrubString(text);
        }
        return value;
    }

    private static String scrubString(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String out = BEARER_VALUE.matcher(text).replaceAll("$1" + REDACTION_TEXT);
        out = BASIC_VALUE.matcher(out).replaceAll("$1" + REDACTION_TEXT);
        out = SECRET_ASSIGNMENT.matcher(out).replaceAll("$1" + REDACTION_TEXT + "$2");
        out = URL_SECRET_PARAM.matcher(out).replaceAll("$1" + REDACTION_TEXT);
        out = CREDENTIAL_SHAPED.matcher(out).replaceAll(REDACTION_TEXT);
        return out;
    }

    private static Map<String, Object> parseMap(ObjectMapper objectMapper, String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception e) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("raw", scrubString(json));
            return fallback;
        }
    }

    private static String base64Json(ObjectMapper objectMapper, Object value) {
        return Base64.getEncoder().encodeToString(writeBytes(objectMapper, value));
    }

    private static byte[] writeBytes(ObjectMapper objectMapper, Object value) {
        try {
            return objectMapper.writeValueAsBytes(value == null ? Map.of() : value);
        } catch (Exception e) {
            return String.valueOf(value).getBytes(StandardCharsets.UTF_8);
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
