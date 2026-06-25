package world.willfrog.agent.platform.debug;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

/**
 * Run-level debug observability opt-in parsed from create-run context / ext.
 */
public record DebugObservabilityRequest(
        boolean enabled,
        String debugSessionId,
        String stressBatchId
) {
    public static final String EXT_KEY = "debug_observability";

    public static DebugObservabilityRequest disabled() {
        return new DebugObservabilityRequest(false, null, null);
    }

    public static DebugObservabilityRequest enabled(String stressBatchId) {
        return new DebugObservabilityRequest(true, null, blankToNull(stressBatchId));
    }

    public static DebugObservabilityRequest parse(Object raw, ObjectMapper objectMapper) {
        if (raw == null) {
            return disabled();
        }
        try {
            JsonNode node = raw instanceof JsonNode jsonNode
                    ? jsonNode
                    : objectMapper.valueToTree(raw);
            if (node == null || node.isNull()) {
                return disabled();
            }
            boolean enabled = node.path("enabled").asBoolean(false);
            if (!enabled) {
                return disabled();
            }
            String sessionId = node.has("debugSessionId") && !node.get("debugSessionId").isNull()
                    ? blankToNull(node.get("debugSessionId").asText())
                    : null;
            String stressBatchId = node.has("stressBatchId") && !node.get("stressBatchId").isNull()
                    ? blankToNull(node.get("stressBatchId").asText())
                    : null;
            return new DebugObservabilityRequest(true, sessionId, stressBatchId);
        } catch (Exception ignored) {
            return disabled();
        }
    }

    public static DebugObservabilityRequest parseContextJson(String contextJson, ObjectMapper objectMapper) {
        if (contextJson == null || contextJson.isBlank()) {
            return disabled();
        }
        try {
            JsonNode root = objectMapper.readTree(contextJson);
            if (root.has("debugObservability")) {
                return parse(root.get("debugObservability"), objectMapper);
            }
            if (root.has("debug_observability")) {
                return parse(root.get("debug_observability"), objectMapper);
            }
            return disabled();
        } catch (Exception ignored) {
            return disabled();
        }
    }

    public Map<String, Object> toExtMap() {
        if (!enabled) {
            return Map.of("enabled", false);
        }
        java.util.LinkedHashMap<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("enabled", true);
        if (debugSessionId != null) {
            map.put("debugSessionId", debugSessionId);
        }
        if (stressBatchId != null) {
            map.put("stressBatchId", stressBatchId);
        }
        return map;
    }

    public String resolveSessionId(String runId) {
        if (debugSessionId != null && !debugSessionId.isBlank()) {
            return debugSessionId;
        }
        String suffix = runId == null || runId.isBlank()
                ? UUID.randomUUID().toString().substring(0, 8)
                : runId.substring(0, Math.min(8, runId.length()));
        return "run-" + suffix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
