package world.willfrog.agent.tools.sandboxjob;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 沙箱长工具统一响应文本构造（工具中立）。
 *
 * <p>{@code {"ok":...,"tool":<工具名>,"data":...,"error":{code,message,details}}} 的形状
 * 是三条路径（同步返回、后台注入、恢复注入）共用的用户可见协议，字段顺序与序列化
 * 回退文本从 executePython 的既有实现原样搬入，逐字节保持一致。</p>
 */
public final class SandboxJobResponses {

    private SandboxJobResponses() {}

    /** 构造 {@code ok=true} 的标准 JSON 工具响应。 */
    public static String ok(ObjectMapper objectMapper, String tool, Map<String, Object> data) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", true);
        payload.put("tool", tool);
        payload.put("data", data == null ? Map.of() : data);
        payload.put("error", null);
        return writeJson(objectMapper, tool, payload);
    }

    /** 构造 {@code ok=false} 的标准 JSON 工具响应；{@code details} 供 LLM 或上层做结构化重试。 */
    public static String fail(ObjectMapper objectMapper, String tool, String code, String message,
                              Map<String, Object> details) {
        return fail(objectMapper, tool, code, message, details, Map.of());
    }

    /**
     * 构造 {@code ok=false} 的 JSON 工具响应，可在失败时仍附带部分 {@code data}
     *（例如 exit code 非零但 stdout 有内容的场景）。
     */
    public static String fail(ObjectMapper objectMapper, String tool, String code, String message,
                              Map<String, Object> details, Map<String, Object> data) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", false);
        payload.put("tool", tool);
        payload.put("data", data == null ? Map.of() : data);
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", nvl(code));
        err.put("message", nvl(message));
        err.put("details", details == null ? Map.of() : details);
        payload.put("error", err);
        return writeJson(objectMapper, tool, payload);
    }

    /** 序列化工具响应；序列化本身失败时返回最小可用的硬编码 JSON 错误串。 */
    private static String writeJson(ObjectMapper objectMapper, String tool, Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{\"ok\":false,\"tool\":\"" + nvl(tool) + "\",\"error\":{\"code\":\"JSON_SERIALIZE_ERROR\",\"message\":\""
                    + escapeJson(nvl(e.getMessage())) + "\"}}";
        }
    }

    public static String nvl(String text) {
        return text == null ? "" : text;
    }

    public static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String escapeJson(String text) {
        return nvl(text)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
