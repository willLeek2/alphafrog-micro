package world.willfrog.alphafrogmicro.common.pojo.agent;

import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Data
public class AgentCreditLedger {
    private Long id;
    private String ledgerId;
    private String userId;
    private String bizType;
    private BigDecimal delta;
    private BigDecimal balanceBefore;
    private BigDecimal balanceAfter;
    private String sourceType;
    private String sourceId;
    private String operatorId;
    private String idempotencyKey;
    private String reason;
    private String ext;
    private OffsetDateTime createdAt;

    public void setDelta(BigDecimal delta) {
        this.delta = delta;
    }

    public void setDelta(Integer delta) {
        this.delta = delta == null ? null : BigDecimal.valueOf(delta);
    }

    public void setBalanceBefore(BigDecimal balanceBefore) {
        this.balanceBefore = balanceBefore;
    }

    public void setBalanceBefore(Integer balanceBefore) {
        this.balanceBefore = balanceBefore == null ? null : BigDecimal.valueOf(balanceBefore);
    }

    public void setBalanceAfter(BigDecimal balanceAfter) {
        this.balanceAfter = balanceAfter;
    }

    public void setBalanceAfter(Integer balanceAfter) {
        this.balanceAfter = balanceAfter == null ? null : BigDecimal.valueOf(balanceAfter);
    }
}
