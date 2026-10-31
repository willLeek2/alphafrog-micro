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
        /** 普通用户 planner 计划：仅保留可展示的计划骨架，不返回 reasoning 或工具参数。 */
        PLAN,
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
            "skippedtodoids", "completeditems", "recoveryrationale", "recoveryjudgedecisionid"
    );
    private static final Set<String> COMPLETED_ITEM_KEYS = Set.of("todoid", "sequence", "description", "summary");
    private static final Set<String> PLAN_ROOT_KEYS = Set.of(
            "items", "tasks", "steps", "extractedentities", "executionmode", "strategy",
            "dependencycheck", "dependencyexecution"
    );
    private static final Set<String> PLAN_ITEM_CONTAINER_KEYS = Set.of("items", "tasks", "steps");
    private static final Set<String> PLAN_ID_LIST_KEYS = Set.of("dependson", "dependencies");
    private static final Set<String> PLAN_ITEM_KEYS = Set.of(
            "id", "sequence", "type", "toolname", "tool", "description", "status",
            "dependson", "dependencies", "groupkey", "parallelizable", "executionmode", "maxsteps",
            "expectedtools"
    );
    private static final Set<String> DEPENDENCY_REPORT_TEXT_KEYS = Set.of("checkedat");
    private static final Set<String> DEPENDENCY_REPORT_NUMBER_KEYS = Set.of(
            "version", "checkedplangeneration", "durationms", "checkeddependencycount", "omittedcount", "omittedtodocount");
    private static final Set<String> DEPENDENCY_HINT_TEXT_KEYS = Set.of(
            "todoid", "toolname", "dependencyid", "dependencylabel", "status", "basis", "reason");
    private static final Set<String> DEPENDENCY_RESULT_TEXT_KEYS = Set.of(
            "source", "todoid", "nodeid", "groupid", "memberid", "memberidentity", "toolname", "toolcallid",
            "operationid", "memberstate", "taskstatus", "errorcode", "taskid");
    private static final Set<String> DEPENDENCY_RESULT_NUMBER_KEYS = Set.of(
            "plangeneration", "nodeattempt", "segmentsequence", "modelturn", "attempt", "eventseq",
            "contextversion", "runcontrolversion");
    private static final Set<String> DEPENDENCY_RESULT_BOOLEAN_KEYS = Set.of("businesssuccess", "executed");
    private static final Set<String> DEPENDENCY_VIEW_NUMBER_KEYS = Set.of(
            "plangeneration", "checkeventseq", "resumeeventseq", "resultafterseq", "omittedcount");
    private static final Set<String> DEPENDENCY_VIEW_BOOLEAN_KEYS = Set.of("prepared", "frozenreuse", "requiresstartproof");
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "authorization", "proxyauthorization", "apikey", "xapikey", "secret", "clientsecret",
            "password", "passwd", "credential", "credentials", "cookie", "setcookie",
            "token", "accesstoken", "refreshtoken", "authtoken", "bearertoken", "idtoken", "sessiontoken",
            "privatekey"
    );
    private static final Pattern BEARER_VALUE = Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._\\-+/=]+");
    private static final Pattern BASIC_VALUE = Pattern.compile("(?i)(basic\\s+)[A-Za-z0-9._\\-+/=]+");
    private static final String SECRET_KEY_PATTERN =
            "(?:api[_-]?key|x[_-]?api[_-]?key|openai[_-]?api[_-]?key|access[_-]?key"
                    + "|secret|client[_-]?secret|password|passwd|credential|credentials|authorization"
                    + "|proxy[_-]?authorization|token|access[_-]?token|refresh[_-]?token|auth[_-]?token"
                    + "|x[_-]?auth[_-]?token|bearer[_-]?token|id[_-]?token|session[_-]?token"
                    + "|private[_-]?key|csrf[_-]?token)";
    /** Header values are line scoped: never leave a second cookie/header token visible. */
    private static final Pattern SENSITIVE_HEADER_VALUE = Pattern.compile(
            "(?i)(\\b(?:authorization|proxy-authorization|cookie|set-cookie|x-api-key|api-key|x-auth-token)"
                    + "\\s*:\\s*)[^\\r\\n]*");
    /** Quoted assignments accept whitespace and punctuation inside the secret value. */
    private static final Pattern DOUBLE_QUOTED_SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)([\"']?" + SECRET_KEY_PATTERN + "[\"']?\\s*[:=]\\s*\")((?:\\\\.|[^\"])*)\"");
    private static final Pattern SINGLE_QUOTED_SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)([\"']?" + SECRET_KEY_PATTERN + "[\"']?\\s*[:=]\\s*')((?:\\\\.|[^'])*)'");
    /** Unquoted form/query assignments consume the whole value up to a real field delimiter. */
    private static final Pattern UNQUOTED_SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)([\"']?" + SECRET_KEY_PATTERN + "[\"']?\\s*[:=]\\s*)(?![\"'])[^&;,\\r\\n]*");
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

    /** 状态摘要和时间线与实时事件沿用同一套依赖提示字段过滤。 */
    public static Object parseEvent(ObjectMapper objectMapper, String json, String eventType) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return sanitizeDependencyEvent(objectMapper.readValue(json, Object.class), eventType);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Recursively applies the selected outbound view to an already parsed value. */
    public static Object sanitize(Object value, View view) {
        if (view == View.PLAN) {
            return sanitizePlan(value);
        }
        return sanitize(value, view, 0, "");
    }

    /**
     * Planner output is treated as hostile even after JSON parsing. Keep only the documented
     * display skeleton and enforce each field's shape so a polluted list/map cannot smuggle
     * reasoning or tool arguments through an otherwise allowed key.
     */
    private static Object sanitizePlan(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            String normalized = normalizeKey(key);
            if (!PLAN_ROOT_KEYS.contains(normalized)) {
                continue;
            }
            Object sanitized;
            if (PLAN_ITEM_CONTAINER_KEYS.contains(normalized)) {
                sanitized = sanitizePlanItems(entry.getValue());
            } else if ("extractedentities".equals(normalized)) {
                sanitized = sanitizePlanScalarList(entry.getValue());
            } else if ("dependencycheck".equals(normalized)) {
                sanitized = sanitizeDependencyReport(entry.getValue());
            } else if ("dependencyexecution".equals(normalized)) {
                sanitized = sanitizeDependencyExecution(entry.getValue());
            } else {
                sanitized = sanitizePlanScalar(entry.getValue());
            }
            if (sanitized != null) {
                out.put(key, sanitized);
            }
        }
        return out;
    }

    private static Object sanitizePlanItems(Object value) {
        if (!(value instanceof List<?> list)) {
            return null;
        }
        List<Object> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            Map<String, Object> sanitizedItem = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                String normalized = normalizeKey(key);
                if (!PLAN_ITEM_KEYS.contains(normalized)) {
                    continue;
                }
                Object sanitized;
                if ("expectedtools".equals(normalized)) {
                    sanitized = sanitizeDisplayStringList(entry.getValue());
                } else {
                    sanitized = PLAN_ID_LIST_KEYS.contains(normalized)
                            ? sanitizePlanScalarList(entry.getValue()) : sanitizePlanScalar(entry.getValue());
                }
                if (sanitized != null) {
                    sanitizedItem.put(key, sanitized);
                }
            }
            out.add(sanitizedItem);
        }
        return out;
    }

    private static Object sanitizePlanScalarList(Object value) {
        if (!(value instanceof List<?> list)) {
            return null;
        }
        List<Object> out = new ArrayList<>(list.size());
        for (Object item : list) {
            Object sanitized = sanitizePlanScalar(item);
            if (sanitized != null) {
                out.add(sanitized);
            }
        }
        return out;
    }

    private static Object sanitizePlanScalar(Object value) {
        if (value instanceof String text) {
            return scrubString(text);
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        return null;
    }

    /** 新增的依赖提示只接收固定显示字段，不让配置、参数或工具正文进入页面。 */
    public static Object sanitizeDependencyEvent(Object value, String eventType) {
        if ("DEPENDENCIES_CHECKED".equals(eventType)) {
            Map<String, Object> out = sanitizeDisplayScalars(value, Set.of(), Set.of("plangeneration"),
                    Set.of("frozenreuse", "reportmissing"));
            if (out != null && value instanceof Map<?, ?> map) {
                copyDependencyField(map, out, "report", AgentExternalObservabilityMapper::sanitizeDependencyReport);
            }
            return out;
        }
        if ("TOOL_RESULT_RECORDED".equals(eventType)) {
            return sanitizeDependencyResult(value);
        }
        return sanitize(value, View.EVENT);
    }

    private static Object sanitizeDependencyReport(Object value) {
        Map<String, Object> out = sanitizeDisplayScalars(value, DEPENDENCY_REPORT_TEXT_KEYS,
                DEPENDENCY_REPORT_NUMBER_KEYS, Set.of("truncated"));
        if (out != null && value instanceof Map<?, ?> map) {
            copyDependencyField(map, out, "hints", item -> sanitizeDisplayObjectList(item,
                    hint -> sanitizeDisplayScalars(hint, DEPENDENCY_HINT_TEXT_KEYS, Set.of(), Set.of())));
            copyDependencyField(map, out, "omittedtodoids", AgentExternalObservabilityMapper::sanitizeDisplayStringList);
        }
        return out;
    }

    private static Object sanitizeDependencyExecution(Object value) {
        Map<String, Object> out = sanitizeDisplayScalars(value, Set.of(), DEPENDENCY_VIEW_NUMBER_KEYS,
                DEPENDENCY_VIEW_BOOLEAN_KEYS);
        if (out != null && value instanceof Map<?, ?> map) {
            copyDependencyField(map, out, "results", item -> sanitizeDisplayObjectList(item,
                    AgentExternalObservabilityMapper::sanitizeDependencyResult));
            copyDependencyField(map, out, "omittedtodoids", AgentExternalObservabilityMapper::sanitizeDisplayStringList);
        }
        return out;
    }

    private static Object sanitizeDependencyResult(Object value) {
        return sanitizeDisplayScalars(value, DEPENDENCY_RESULT_TEXT_KEYS,
                DEPENDENCY_RESULT_NUMBER_KEYS, DEPENDENCY_RESULT_BOOLEAN_KEYS);
    }

    private static Map<String, Object> sanitizeDisplayScalars(Object value, Set<String> strings,
                                                              Set<String> numbers, Set<String> booleans) {
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        map.forEach((rawKey, item) -> {
            String key = String.valueOf(rawKey);
            String normalized = normalizeKey(key);
            if (strings.contains(normalized) && item instanceof String text) {
                String safe = boundedDisplayText(text);
                if (safe != null) {
                    out.put(key, safe);
                }
            } else if (numbers.contains(normalized) && item instanceof Number number
                    && Double.isFinite(number.doubleValue()) && number.doubleValue() >= 0
                    && number.doubleValue() == number.longValue()) {
                out.put(key, number);
            } else if (booleans.contains(normalized) && item instanceof Boolean) {
                out.put(key, item);
            }
        });
        return out;
    }

    private static Object sanitizeDisplayStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            if (out.size() >= 32) {
                break;
            }
            if (item instanceof String text) {
                String safe = boundedDisplayText(text);
                if (safe != null) {
                    out.add(safe);
                }
            }
        }
        return out;
    }

    private static Object sanitizeDisplayObjectList(Object value, java.util.function.Function<Object, Object> mapper) {
        if (!(value instanceof List<?> list)) {
            return null;
        }
        List<Object> out = new ArrayList<>();
        for (Object item : list) {
            if (out.size() >= 32) {
                break;
            }
            Object mapped = mapper.apply(item);
            if (mapped != null) {
                out.add(mapped);
            }
        }
        return out;
    }

    private static void copyDependencyField(Map<?, ?> source, Map<String, Object> destination,
                                            String normalizedKey, java.util.function.Function<Object, Object> mapper) {
        source.forEach((rawKey, value) -> {
            String key = String.valueOf(rawKey);
            if (normalizedKey.equals(normalizeKey(key))) {
                Object mapped = mapper.apply(value);
                if (mapped != null) {
                    destination.put(key, mapped);
                }
            }
        });
    }

    private static String boundedDisplayText(String value) {
        String safe = scrubString(value);
        // 调用和步骤身份也经过这里；超长时省略，不能截成可能相同的前缀。
        return safe.length() <= 256 ? safe : null;
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
                        && !RUN_SNAPSHOT_KEYS.contains(normalized)) {
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
        out = DOUBLE_QUOTED_SECRET_ASSIGNMENT.matcher(out).replaceAll("$1" + REDACTION_TEXT + "\"");
        out = SINGLE_QUOTED_SECRET_ASSIGNMENT.matcher(out).replaceAll("$1" + REDACTION_TEXT + "'");
        out = UNQUOTED_SECRET_ASSIGNMENT.matcher(out).replaceAll("$1" + REDACTION_TEXT);
        out = URL_SECRET_PARAM.matcher(out).replaceAll("$1" + REDACTION_TEXT);
        return CREDENTIAL_SHAPED.matcher(out).replaceAll(REDACTION_TEXT);
    }
}
