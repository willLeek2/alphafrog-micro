package world.willfrog.agentlangchain.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 等待成员的结果载荷：成员行 {@code result_ref_json} 里那份 JSON 的读写规则。
 *
 * <p>它同时被两处用到——节点执行器同步执行完一个工具时写它，后台作业终态被接回时也写它。
 * 放在一处是为了让「结果长什么样」只有一份定义，不出现两个写入方各写一套字段。</p>
 *
 * <p>结果正文按原样保存，不截断：恢复后这条正文要作为工具结果交回模型，截断会让模型看到的历史
 * 与没中断时不一样。超过配置上限时不用截断的文本冒充结果，而是把这个成员记成失败并写明原因——
 * 宁可让模型看到「这次结果太大没能接回」，也不能让它拿着半截数据继续推理。</p>
 */
public final class WaitMemberResultPayload {

    /** 结果过大时写进载荷的错误码，恢复后模型与验收都能看到。 */
    public static final String TOO_LARGE = "member_result_too_large";
    /**
     * 结果正文已经拿到，但写成 jsonb 失败时写进载荷的错误码。
     *
     * <p>PostgreSQL 的 jsonb 不能存 NUL。工具原文里一旦带上这个字符，整条成员结束语句会抛错，
     * 等待组停在「成员还待派发、下一段还在等待」，Run 一直执行到客户端超时。改写成这条失败后，
     * 组仍能齐备并放行下一段，模型看到的是一句明确说明。</p>
     */
    public static final String PERSIST_FAILED = "member_result_persist_failed";

    private WaitMemberResultPayload() {
    }

    /**
     * 结果正文是不是超过了上限。
     *
     * <p>写入方要用它决定成员落成功还是失败：载荷会把过大的结果改写成失败，成员行也必须是失败，
     * 不能让两处对同一次调用给出两个结论。</p>
     */
    public static boolean tooLarge(String output, int maxChars) {
        return maxChars > 0 && (output == null ? 0 : output.length()) > maxChars;
    }

    /**
     * 编码一份成员结果。
     *
     * @param toolName  工具名
     * @param toolCallId 模型给出的工具调用身份，可能为空
     * @param success   这一次工具调用是不是成功
     * @param output    结果正文
     * @param extra     额外字段（错误码、后台任务编号等），可为空
     * @param maxChars  结果正文的字符上限，超出时改写成失败载荷
     */
    public static String encode(ObjectMapper objectMapper,
                                String toolName,
                                String toolCallId,
                                boolean success,
                                String output,
                                Map<String, Object> extra,
                                int maxChars) {
        String text = stripJsonbUnsafe(output == null ? "" : output);
        boolean tooLarge = tooLarge(text, maxChars);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("toolName", toolName);
        payload.put("toolCallId", toolCallId);
        payload.put("status", success && !tooLarge ? "SUCCEEDED" : "FAILED");
        payload.put("outputLength", text.length());
        payload.put("outputDigest", "sha256:" + digest(text));
        if (tooLarge) {
            payload.put("output", "");
            payload.put("errorCode", TOO_LARGE);
            payload.put("errorDetail", "结果正文 " + text.length() + " 字符，超过上限 " + maxChars + "，没有保存");
        } else {
            payload.put("output", text);
        }
        if (extra != null) {
            extra.forEach((key, value) -> {
                if (value != null && !payload.containsKey(key)) {
                    payload.put(key, value);
                }
            });
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("成员结果载荷无法编码", e);
        }
    }

    /**
     * 第一次写入失败后改用的短失败载荷：不含工具原文，避免同一条非法字符或超大 JSON 再次把语句打爆。
     */
    public static String compactPersistFailure(ObjectMapper objectMapper,
                                               String toolName,
                                               String toolCallId,
                                               String errorDetail) {
        return compactPersistFailure(objectMapper, toolName, toolCallId, errorDetail, null);
    }

    /** 后台作业的短失败载荷仍保留任务编号，供取消与容量收尾核对外部身份。 */
    public static String compactPersistFailure(ObjectMapper objectMapper,
                                               String toolName,
                                               String toolCallId,
                                               String errorDetail,
                                               String taskId) {
        String detail = errorDetail == null || errorDetail.isBlank()
                ? "成员结果无法写入等待组" : errorDetail;
        if (detail.length() > 240) {
            detail = detail.substring(0, 240);
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("errorCode", PERSIST_FAILED);
        extra.put("errorDetail", detail);
        if (taskId != null && !taskId.isBlank()) extra.put("taskId", taskId);
        return encode(objectMapper, toolName, toolCallId, false, "", extra, Integer.MAX_VALUE);
    }

    /**
     * jsonb 拒绝 NUL。写入前去掉，摘要按去掉后的正文计算，恢复时模型看到的也是这份没有 NUL 的原文。
     */
    static String stripJsonbUnsafe(String text) {
        if (text == null || text.indexOf('\0') < 0) {
            return text == null ? "" : text;
        }
        return text.replace("\0", "");
    }

    /** 读结果正文；载荷不是对象或没有正文时返回空串。 */
    public static String output(String resultRefJson, ObjectMapper objectMapper) {
        JsonNode node = parse(resultRefJson, objectMapper);
        return node == null ? "" : node.path("output").asText("");
    }

    /** 这次工具调用是不是成功。 */
    public static boolean succeeded(String resultRefJson, ObjectMapper objectMapper) {
        JsonNode node = parse(resultRefJson, objectMapper);
        return node != null && "SUCCEEDED".equals(node.path("status").asText(""));
    }

    /**
     * 恢复时交给模型的工具结果正文。
     *
     * <p>失败的成员要把失败文本一并交回，模型才有机会换参数重试或改用别的做法；结果过大被改写成
     * 失败的成员同样如此，它看到的是一句明确的说明，不是半截数据。</p>
     */
    public static String modelText(String resultRefJson, ObjectMapper objectMapper) {
        JsonNode node = parse(resultRefJson, objectMapper);
        if (node == null) {
            return "";
        }
        if (succeeded(resultRefJson, objectMapper)) {
            return node.path("output").asText("");
        }
        String output = node.path("output").asText("");
        String errorCode = node.path("errorCode").asText("");
        String detail = node.path("errorDetail").asText("");
        if (!output.isBlank()) {
            return output;
        }
        String reason = !errorCode.isBlank() ? errorCode : "tool_failed";
        return detail.isBlank() ? "工具调用失败：" + reason : "工具调用失败：" + reason + "（" + detail + "）";
    }

    private static JsonNode parse(String resultRefJson, ObjectMapper objectMapper) {
        if (resultRefJson == null || resultRefJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(resultRefJson);
            return node instanceof ObjectNode ? node : null;
        } catch (Exception e) {
            throw new IllegalStateException("成员结果载荷无法解析", e);
        }
    }

    private static String digest(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                builder.append(Character.forDigit((value >> 4) & 0xF, 16));
                builder.append(Character.forDigit(value & 0xF, 16));
            }
            return builder.toString();
        } catch (Exception e) {
            throw new IllegalStateException("结果正文摘要无法计算", e);
        }
    }
}
