package world.willfrog.agent.platform.exception;

/**
 * Provider/模型 HTTP 调用失败的后端预分类。
 *
 * <p>由 {@link world.willfrog.agent.platform.service.OpenRouterProviderRoutedChatModel}
 * 在不可再重试或重试耗尽时产出，供下游 {@code LangchainFailureMapper} 直接映射为稳定的
 * {@code failure_category}，避免 harness/报告重新解析 SSE chunk 或 provider 错误文本。</p>
 */
public enum ProviderFailureCategory {
    TRANSIENT_NETWORK,
    RATE_LIMIT,
    BAD_REQUEST_TOKEN_LIMIT,
    MODEL_UNAVAILABLE,
    AUTH_REJECTED,
    UNKNOWN
}
