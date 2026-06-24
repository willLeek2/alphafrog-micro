package world.willfrog.agent.platform.exception;

import lombok.Getter;
import world.willfrog.agent.platform.service.OpenAiCompatibleChatModelSupport;

import java.util.List;

/**
 * Provider/模型 HTTP 调用失败的结构化异常。
 *
 * <p>继承 {@link IllegalStateException} 以保持对现有 {@code catch (IllegalStateException)}
 * 代码的兼容，同时携带稳定的分类字段，避免下游仅靠文本关键词匹配。</p>
 *
 * <p>{@link #rawProviderMessage} 在构造时即被 bounded（≤ 800 字符）并移除 Authorization 等
 * 敏感 header，完整 provider raw 仍通过 raw HTTP capture 开关查询，不进 failure event。</p>
 */
@Getter
public class ProviderChatException extends IllegalStateException {

    public static final int RAW_MESSAGE_MAX_LENGTH = 800;

    private final int statusCode;
    private final String errorCode;
    private final List<String> providerOrder;
    private final String modelName;
    private final String endpointName;
    private final String rawProviderMessage;
    private final ProviderFailureCategory category;

    public ProviderChatException(int statusCode,
                                 String errorCode,
                                 List<String> providerOrder,
                                 String modelName,
                                 String endpointName,
                                 String rawProviderMessage,
                                 ProviderFailureCategory category,
                                 String detailMessage,
                                 Throwable cause) {
        super(detailMessage, cause);
        this.statusCode = statusCode;
        this.errorCode = errorCode == null ? "" : errorCode;
        this.providerOrder = providerOrder == null ? List.of() : List.copyOf(providerOrder);
        this.modelName = modelName == null ? "" : modelName;
        this.endpointName = endpointName == null ? "" : endpointName;
        this.rawProviderMessage = scrubRawMessage(rawProviderMessage);
        this.category = category == null ? ProviderFailureCategory.UNKNOWN : category;
    }

    private static String scrubRawMessage(String raw) {
        if (raw == null) {
            return "";
        }
        String normalized = raw.replace('\n', ' ').replace('\r', ' ');
        // 移除 Authorization header 等敏感片段（不区分大小写）
        normalized = normalized.replaceAll("(?i)Authorization\\s*[:=]\\s*Bearer\\s+[^\\s\"']+", "Authorization: Bearer <redacted>");
        normalized = normalized.replaceAll("(?i)\\b[sk]-[a-zA-Z0-9_-]{10,}\\b", "<redacted>");
        if (normalized.length() <= RAW_MESSAGE_MAX_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, RAW_MESSAGE_MAX_LENGTH) + "...";
    }

    /**
     * 便捷构造：使用 {@link OpenAiCompatibleChatModelSupport#shorten(String)} 生成 detail message。
     */
    public static ProviderChatException of(int statusCode,
                                            String errorCode,
                                            List<String> providerOrder,
                                            String modelName,
                                            String endpointName,
                                            String rawProviderMessage,
                                            ProviderFailureCategory category,
                                            Throwable cause) {
        String detail = "Provider chat failed (http=" + statusCode
                + ", category=" + category
                + ", errorCode=" + (errorCode == null ? "" : errorCode)
                + ", providers=" + providerOrder
                + ", model=" + modelName
                + ", endpoint=" + endpointName
                + ", raw=" + OpenAiCompatibleChatModelSupport.shorten(rawProviderMessage) + ")";
        return new ProviderChatException(statusCode, errorCode, providerOrder, modelName, endpointName,
                rawProviderMessage, category, detail, cause);
    }
}
