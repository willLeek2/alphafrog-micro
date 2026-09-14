package world.willfrog.alphafrogmicro.common.pojo.agent;

import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Data
public class AgentCreditRecharge {
    private Long id;
    private String rechargeId;
    private String ledgerId;
    private String userId;
    private String username;
    private String operatorId;
    private String currency;
    private BigDecimal originalAmount;
    private BigDecimal exchangeRateToUsd;
    private BigDecimal creditAmount;
    private String reason;
    private String idempotencyKey;
    private String ext;
    private OffsetDateTime createdAt;
}
