package world.willfrog.alphafrogmicro.frontend.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * D19 frontend 出站安全边界。
 *
 * <p>存储层 JSON 永远不直接作为 HTTP 响应。所有入口先严格解析，再按调用面删除
 * observability/raw trace 字段并递归脱敏。解析失败返回 {@code null}，禁止把原字符串
 * 当作兼容 fallback 回传。</p>
 */
public final class AgentExternalObservabilityMapper {

    public static final String REDACTION_TEXT = "***REDACTED***";

    public enum View {
        /** 管理员完整观测：保留结构，但仍递归脱敏凭证。 */
        ADMIN,
        /** 普通用户结构化答案等业务 JSON：保留业务形状，删除内部观测字段。 */
        STRUCTURED,
        /** 普通用户 Run snapshot / result payload：使用顶层与 completed_items 白名单。 */
        RUN_SNAPSHOT,
        /** 普通用户 status/summary。 */
        STATUS,
        /** 普通用户 event/timeline payload。 */
        EVENT
    }

    private static final Set<String> COMMON_INTERNAL_KEYS = Set.of(
            "observability", "observabilityjson", "diagnostics", "llmtraces", "tooltraces",
            "inputmessages", "reasoningtext", "reasoningcontent", "reasoningdetails", "httprequest", "httpresponse",
            "curlcommand", "attempts", "request", "raw", "rawrequest", "rawresponse", "rawparams", "rawoutput",
            "fulldetail", "fulldetailparts", "detailblob", "detailblobjson", "cachekey", "decisionexcerpt"
    );
    private static final Set<String> EVENT_ONLY_INTERNAL_KEYS = Set.of("params", "output");
    private static final Set<String> RUN_SNAPSHOT_KEYS = Set.of(
            "answer", "answermarkdown", "status", "failurereason", "toolcallsused", "engine", "partial",
            "skippedtodoids", "completeditems"
    );
    private static final Set<String> COMPLETED_ITEM_KEYS = Set.of("todoid", "sequence", "description", "summary");
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "authorization", "proxyauthorization", "apikey", "xapikey", "secret", "clientsecret",
            "password", "passwd", "credential", "credentials", "cookie", "setcookie",
            "token", "accesstoken", "refreshtoken", "authtoken", "bearertoken", "idtoken", "sessiontoken",
            "privatekey"
    );
    private static final Pattern BEARER_VALUE = Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._\\-+/=]+");
    private static final Pattern BASIC_VALUE = Pattern.compile("(?i)(basic\\s+)[A-Za-z0-9._\\-+/=]+");
    private static final Pattern SENSITIVE_HEADER_VALUE = Pattern.compile(
            "(?i)((?:authorization|cookie|set-cookie|x-api-key)\\s*[:=]\\s*)[^\\s,;]+");
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)([\"']?(?:api_?key|secret|password|access_?token|token)[\"']?\\s*[:=]\\s*[\"']?)[A-Za-z0-9._\\-+/=]+([\"']?)");
    private static final Pattern URL_SECRET_PARAM = Pattern.compile(
            "(?i)([?&][^=&\\s]*(?:api_?key|secret|password|access_?token|token|key)[^=]*=)[^&#\\s]+");
    private static final Pattern CREDENTIAL_SHAPED = Pattern.compile(
            "(?i)\\b(sk|ak|pk|rk|ghp|xox[baprs]|ya29)_[A-Za-z0-9._\\-]{12,}\\b"
                    + "|\\bsk-[A-Za-z0-9._\\-]{12,}\\b"
                    + "|\\bAKIA[0-9A-Z]{16}\\b"
                    + "|\\bAIza[0-9A-Za-z_\\-]{20,}\\b"
                    + "|\\bgithub_pat_[A-Za-z0-9_]{20,}\\b");

    private AgentExternalObservabilityMapper() {
    }

    /** Strict JSON parse + view mapping. Malformed input is absent, never an echoed string. */
    public static Object parse(ObjectMapper objectMapper, String json, View view) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return sanitize(objectMapper.readValue(json, Object.class), view);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Strictly maps a JSON string while preserving the legacy string-shaped HTTP field. */
    public static String parseToJson(ObjectMapper objectMapper, String json, View view) {
        Object mapped = parse(objectMapper, json, view);
        if (mapped == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(mapped);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Recursively applies the selected outbound view to an already parsed value. */
    public static Object sanitize(Object value, View view) {
        return sanitize(value, view, 0, "");
    }

    private static Object sanitize(Object value, View view, int depth, String parentKey) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                String normalized = normalizeKey(key);
                if (view == View.RUN_SNAPSHOT && depth == 0
                        && !RUN_SNAPSHOT_KEYS.contains(normalized)
                        && !normalized.startsWith("recovery")) {
                    continue;
                }
                if (view == View.RUN_SNAPSHOT && "completeditems".equals(normalizeKey(parentKey))
                        && !COMPLETED_ITEM_KEYS.contains(normalized)) {
                    continue;
                }
                if (isSensitiveKey(key)) {
                    out.put(key, REDACTION_TEXT);
                    continue;
                }
                if (shouldDrop(key, view)) {
                    continue;
                }
                Object sanitized = sanitize(entry.getValue(), view, depth + 1, key);
                if (view == View.EVENT && isPreviewKey(normalized) && sanitized instanceof String text) {
                    sanitized = safePreview(text, AgentCallDetailMapper.PREVIEW_MAX_CHARS);
                }
                out.put(key, sanitized);
            }
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(sanitize(item, view, depth + 1, parentKey));
            }
            return out;
        }
        if (value instanceof String text) {
            return scrubString(text);
        }
        return value;
    }

    /** Safe preview accepts only an already-designated preview field, never legacy raw output. */
    public static String safePreview(Object value, int maxChars) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(sanitize(value, View.ADMIN));
        if (text.isBlank()) {
            return null;
        }
        int limit = Math.max(0, maxChars);
        return text.length() <= limit ? text : text.substring(0, limit);
    }

    private static boolean shouldDrop(String key, View view) {
        if (view == View.ADMIN) {
            return false;
        }
        String normalized = normalizeKey(key);
        if (COMMON_INTERNAL_KEYS.contains(normalized)) {
            return true;
        }
        return view == View.EVENT && EVENT_ONLY_INTERNAL_KEYS.contains(normalized);
    }

    private static boolean isPreviewKey(String normalizedKey) {
        return "resultpreview".equals(normalizedKey)
                || "outputpreview".equals(normalizedKey)
                || "responsepreview".equals(normalizedKey)
                || "summary".equals(normalizedKey);
    }

    private static boolean isSensitiveKey(String key) {
        String normalized = normalizeKey(key);
        if (SENSITIVE_KEYS.contains(normalized)) {
            return true;
        }
        return normalized.endsWith("apikey")
                || normalized.endsWith("accesskey")
                || normalized.endsWith("secret")
                || normalized.endsWith("password")
                || normalized.endsWith("passwd")
                || normalized.endsWith("credential")
                || normalized.endsWith("credentials")
                || normalized.endsWith("authorization")
                || normalized.endsWith("cookie")
                || normalized.endsWith("privatekey")
                || normalized.endsWith("accesstoken")
                || normalized.endsWith("refreshtoken")
                || normalized.endsWith("authtoken")
                || normalized.endsWith("bearertoken")
                || normalized.endsWith("idtoken")
                || normalized.endsWith("sessiontoken")
                || normalized.endsWith("csrftoken");
    }

    private static String normalizeKey(String key) {
        return (key == null ? "" : key)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]", "");
    }

    private static String scrubString(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String out = BEARER_VALUE.matcher(text).replaceAll("$1" + REDACTION_TEXT);
        out = BASIC_VALUE.matcher(out).replaceAll("$1" + REDACTION_TEXT);
        out = SENSITIVE_HEADER_VALUE.matcher(out).replaceAll("$1" + REDACTION_TEXT);
        out = SECRET_ASSIGNMENT.matcher(out).replaceAll("$1" + REDACTION_TEXT + "$2");
        out = URL_SECRET_PARAM.matcher(out).replaceAll("$1" + REDACTION_TEXT);
        return CREDENTIAL_SHAPED.matcher(out).replaceAll(REDACTION_TEXT);
    }
}
