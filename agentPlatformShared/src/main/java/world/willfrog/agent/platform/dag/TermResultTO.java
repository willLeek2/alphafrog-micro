package world.willfrog.agent.platform.dag;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;

/**
 * 从 Sandbox 获取已完成任务的全量终态数据。
 * outcome=COMPLETED 时 resultJson 非 null；outcome=FAILED/CANCELED 时 terminalError 非 null。
 */
public record TermResultTO(
        @JsonProperty("taskId") String taskId,
        @JsonProperty("operationId") String operationId,
        @JsonProperty("toolCallId") String toolCallId,
        @JsonProperty("attempt") int attempt,
        @JsonProperty("requestDigest") String requestDigest,
        @JsonProperty("outcome") String outcome,
        @JsonProperty("resultJson") JsonNode resultJson,
        @JsonProperty("terminalError") JsonNode terminalError,
        @JsonProperty("modelId") String modelId,
        @JsonProperty("tokensIn") long tokensIn,
        @JsonProperty("tokensOut") long tokensOut,
        @JsonProperty("costUSD") BigDecimal costUSD) {
}
