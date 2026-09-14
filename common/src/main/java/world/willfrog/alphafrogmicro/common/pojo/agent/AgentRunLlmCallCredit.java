package world.willfrog.alphafrogmicro.common.pojo.agent;

import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Data
public class AgentRunLlmCallCredit {
    private Long id;
    private String recordId;
    private String runId;
    private String userId;
    private String llmCallId;
    private String endpointName;
    private String modelName;
    private String costSource;
    private String currency;
    private BigDecimal costAmount;
    private BigDecimal creditDelta;
    private String settlementStatus;
    private Integer settlementAttempt;
    private String reason;
    private String idempotencyKey;
    private String ext;
    private OffsetDateTime createdAt;
    private OffsetDateTime expiresAt;
}
