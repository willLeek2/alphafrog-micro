package world.willfrog.agent.tools.sandboxjob;

import com.fasterxml.jackson.databind.ObjectMapper;
import world.willfrog.agent.platform.failure.ToolFailureCategory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 沙箱长工具统一响应文本构造（工具中立）。
 *
 * <p>{@code {"ok":...,"tool":<工具名>,"data":...,"error":{code,message,details[,category]}}} 的形状
 * 是三条路径（同步返回、后台注入、恢复注入）共用的用户可见协议，字段顺序与序列化
 * 回退文本从 executePython 的既有实现原样搬入，逐字节保持一致；{@code category} 是失败类别的
 * 可选附加字段（四类通用失败类别，非故障结果族不写）。</p>
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
        return fail(objectMapper, tool, code, message, details, data, null);
    }

    /** 带 {@code category} 的失败响应（无部分 {@code data}）；类别规则见全参重载的说明。 */
    public static String fail(ObjectMapper objectMapper, String tool, String code, String message,
                              Map<String, Object> details, ToolFailureCategory category) {
        return fail(objectMapper, tool, code, message, details, Map.of(), category);
    }

    /**
     * 构造 {@code ok=false} 的 JSON 工具响应（全参版本）。
     *
     * <p>失败类别：{@code category} 传 null 时按 {@link ToolFailureCategory#of(String)} 兜底映射，
     * 构造点已知更细事实（HTTP 状态、失败分支）时传显式类别覆盖；非故障结果族解析为 null 时
     * error 里不写 {@code category} 字段。code/ok 语义与既有信封一致。</p>
     */
    public static String fail(ObjectMapper objectMapper, String tool, String code, String message,
                              Map<String, Object> details, Map<String, Object> data,
                              ToolFailureCategory category) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", false);
        payload.put("tool", tool);
        payload.put("data", data == null ? Map.of() : data);
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", nvl(code));
        err.put("message", nvl(message));
        err.put("details", details == null ? Map.of() : details);
        ToolFailureCategory.putCategoryIfResolved(err, code, category);
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

    /**
     * 16KB UTF-8 安全截断（含后缀），与 ToolJobFinalizer 的终态预览同一算法：
     * 超限时按字节截到上限减后缀长度，回退到 UTF-8 字符边界，再拼「…(truncated)」。
     * 恢复注入给模型看的载荷预览统一走这里，避免多处拷贝漂移。
     */
    public static String boundedPreview(String s) {
        if (s == null) {
            return null;
        }
        String suffix = "\u2026(truncated)";
        byte[] raw = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int max = world.willfrog.agent.platform.dataanalysis.DataAnalysisTerminalEnvelope.MAX_RESULT_PREVIEW_BYTES;
        if (raw.length <= max) {
            return s;
        }
        byte[] suffixBytes = suffix.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int cut = max - suffixBytes.length;
        if (cut <= 0) {
            return suffix;
        }
        while (cut > 0 && (raw[cut] & 0xC0) == 0x80) {
            cut--;
        }
        return new String(raw, 0, cut, java.nio.charset.StandardCharsets.UTF_8) + suffix;
    }

    private static String escapeJson(String text) {
        return nvl(text)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
