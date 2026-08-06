package world.willfrog.alphafrogmicro.common.pojo.agent;

import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Data
public class AgentRunCreditSummary {
    private Long id;
    private String runId;
    private String userId;
    private BigDecimal totalCreditConsumed;
    private BigDecimal immediateCreditConsumed;
    private BigDecimal delayedCreditConsumed;
    private String currency;
    private String settlementStatus;
    private String idempotencyKey;
    private String ext;
    private OffsetDateTime lastSettlementAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
