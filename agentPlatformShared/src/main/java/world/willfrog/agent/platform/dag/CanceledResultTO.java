package world.willfrog.agent.platform.dag;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/**
 * 从 Sandbox 获取被取消任务的无 usage 终态数据。
 * 用于 cancelTask 返回 CANCELED 时填充 child anchor。
 */
public record CanceledResultTO(
        @JsonProperty("taskId") String taskId,
        @JsonProperty("operationId") String operationId,
        @JsonProperty("toolCallId") String toolCallId,
        @JsonProperty("attempt") int attempt,
        @JsonProperty("requestDigest") String requestDigest,
        @JsonProperty("modelId") String modelId,
        @JsonProperty("tokensIn") long tokensIn,
        @JsonProperty("tokensOut") long tokensOut,
        @JsonProperty("costUSD") BigDecimal costUSD) {
}
