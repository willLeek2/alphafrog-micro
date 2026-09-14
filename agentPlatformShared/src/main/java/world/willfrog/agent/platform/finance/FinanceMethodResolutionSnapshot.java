package world.willfrog.agent.platform.finance;

import java.time.Instant;

/**
 * 金融方法解析快照，按共同协议 §8.1 定义。
 *
 * <p>身份键：runId + resolverToolCallId + methodId + version + specDigest。</p>
 */
public record FinanceMethodResolutionSnapshot(
        String runId,
        String resolverToolCallId,
        String todoId,
        String methodId,
        String methodVersion,
        String specDigest,
        String catalogDigest,
        String resolverSchemaVersion,
        String resolverPromptVersion,
        String modelRouteJson,
        String matchReason,
        String clarificationJson,
        String targetEnvironmentId,
        String targetPackageApiJson,
        String resolutionPayloadJson,
        String resolutionContentDigest,
        Instant createdAt
) {
}
